package com.thesiegs.esphome.proxy

import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * The ESPHome API server on plain java.net sockets (runs the same on the JVM and Android).
 * Each HA client gets a reader thread and a writer thread; advertisements from [publish] are
 * batched and fanned out to every subscribed client. [Listener.onSubscribedChanged] tells the host
 * when to scan: only while at least one client wants advertisements.
 *
 * @param psk the 32-byte Noise key, or null for a plaintext API (ESPHome without `encryption:`).
 */
class ProxyServer(
    private val identity: ProxyIdentity,
    private val psk: ByteArray?,
    private val port: Int = DEFAULT_PORT,
    private val listener: Listener,
    private val maxClients: Int = 4,
    private val idleTimeoutMs: Int = 150_000,
    private val log: (String) -> Unit = {},
) {
    interface Listener {
        /** Called on a server thread when the first client subscribes or the last one leaves. */
        fun onSubscribedChanged(subscribed: Boolean)
        fun onClientsChanged(count: Int) {}
    }

    private val connections = CopyOnWriteArrayList<Connection>()
    private val batcher = AdvertisementBatcher()
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var flusher: ScheduledExecutorService? = null
    private var flushTask: ScheduledFuture<*>? = null
    @Volatile private var anySubscribed = false
    @Volatile private var scannerState = ScannerState.IDLE
    @Volatile private var running = false

    val subscribed get() = anySubscribed
    val clientCount get() = connections.size

    /** Binds and starts accepting. Returns the bound port (useful with port 0 in tests). */
    @Synchronized
    fun start(): Int {
        check(!running) { "already running" }
        if (psk != null) require(psk.size == 32) { "psk must be 32 bytes" }
        val ss = ServerSocket()
        ss.reuseAddress = true
        // A port in use: the socket is closed, not leaked, and the caller may try again.
        try { ss.bind(InetSocketAddress(port)) } catch (e: IOException) { try { ss.close() } catch (_: IOException) {}; throw e }
        serverSocket = ss
        running = true
        flusher = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "esphome-proxy-flush").apply { isDaemon = true } }
        acceptThread = Thread({ acceptLoop(ss) }, "esphome-proxy-accept").apply { isDaemon = true; start() }
        log("listening on ${ss.localPort} (${if (psk != null) "Noise" else "plaintext"})")
        return ss.localPort
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        try { serverSocket?.close() } catch (_: IOException) {}
        for (c in connections) c.shutdown()
        connections.clear()
        flusher?.shutdownNow()
        flusher = null; flushTask = null
        synchronized(batcher) { batcher.clear() }
        updateSubscribed()
    }

    /** Queues one advertisement; dropped unless a client is subscribed. Thread-safe. */
    fun publish(ad: RawAdvertisement) {
        if (!anySubscribed) return
        val batch = synchronized(batcher) { batcher.add(ad, nowMs()) } ?: return
        broadcast(batch)
    }

    /** Reports the scanner's state to subscribed clients (the STATE_AND_MODE feature). */
    fun setScannerState(state: ScannerState) {
        scannerState = state
        for (c in connections) c.sendScannerState(state)
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running) {
            val socket = try { ss.accept() } catch (e: IOException) { break }
            // Only clients past the handshake hold a slot; half-open ones can't lock HA out (review 2026-11-20).
            if (connections.count { it.ready } >= maxClients || connections.size >= maxClients * 2) {
                log("too many clients, refusing ${socket.inetAddress.hostAddress}")
                try { socket.close() } catch (_: IOException) {}
                continue
            }
            val c = Connection(socket)
            connections += c
            listener.onClientsChanged(connections.size)
            c.start()
        }
    }

    private fun broadcast(batch: List<RawAdvertisement>) {
        for (c in connections) c.sendAdvertisements(batch)
    }

    private fun flushDue() {
        val batch = synchronized(batcher) { batcher.poll(nowMs()) } ?: return
        broadcast(batch)
    }

    /**
     * Works out whether any client is subscribed and tells the listener on a change, all under one
     * lock: two clients subscribing and leaving at once can't deliver true and false out of order
     * (code review 2026-11-20). The listener must be quick (the runtime posts to its main thread).
     */
    private fun updateSubscribed() {
        synchronized(this) {
            val now = running && connections.any { it.subscribed }
            val changed = now != anySubscribed
            anySubscribed = now
            if (changed) {
                flushTask?.cancel(false)
                flushTask = if (now) flusher?.scheduleAtFixedRate({ flushDue() }, FLUSH_TICK_MS, FLUSH_TICK_MS, TimeUnit.MILLISECONDS) else null
                if (!now) synchronized(batcher) { batcher.clear() }
                listener.onSubscribedChanged(now)
            }
        }
    }

    private fun removed(c: Connection) {
        if (connections.remove(c)) {
            listener.onClientsChanged(connections.size)
            updateSubscribed()
        }
    }

    private inner class Connection(private val socket: Socket) {
        private val codec: ServerFrameCodec =
            if (psk != null) NoiseServerCodec(psk, identity.name, MacAddress.formatPlain(identity.mac)) else PlaintextServerCodec()
        private val session = ApiSession(identity).also { it.scannerState(scannerState) }
        private val out = LinkedBlockingQueue<ByteArray>()
        private val peer = socket.inetAddress?.hostAddress ?: "?"
        @Volatile private var closing = false

        val subscribed get() = session.subscribed
        @Volatile private var readyOnce = false
        /** Past the handshake (Noise done). */
        val ready: Boolean get() = readyOnce

        fun start() {
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.soTimeout = HANDSHAKE_MS
            Thread({ readLoop() }, "esphome-proxy-read-$peer").apply { isDaemon = true; start() }
            Thread({ writeLoop() }, "esphome-proxy-write-$peer").apply { isDaemon = true; start() }
        }

        private fun readLoop() {
            val buf = ByteArray(4096)
            try {
                val input = socket.getInputStream()
                while (!closing) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (!handle(codec.receive(buf, 0, n))) break
                    if (!readyOnce && codec.ready) { readyOnce = true; socket.soTimeout = idleTimeoutMs }
                }
            } catch (e: SocketTimeoutException) {
                log("$peer idle, closing")
            } catch (e: IOException) {
                // closed by peer or by stop()
            } catch (e: ProtocolException) {
                log("$peer protocol error: ${e.message}")
            }
            finish()
        }

        /** Returns false when the connection should end (after its queued bytes are sent). */
        private fun handle(events: List<FrameEvent>): Boolean {
            for (e in events) when (e) {
                is FrameEvent.Write -> out.put(e.bytes)
                is FrameEvent.Fail -> { log("$peer: ${e.reason}"); return false }
                is FrameEvent.Message -> {
                    val wasSubscribed = session.subscribed
                    val result = synchronized(this) {
                        val r = session.onMessage(e.message)
                        for (m in r.replies) out.put(codec.encode(m))
                        r
                    }
                    if (wasSubscribed != session.subscribed) updateSubscribed()
                    if (result.close) return false
                }
            }
            return true
        }

        fun sendAdvertisements(batch: List<RawAdvertisement>) = synchronized(this) {
            if (closing) return@synchronized
            val m = session.advertisements(batch) ?: return@synchronized
            // A client that stopped reading loses advertisements rather than growing memory.
            if (out.size < MAX_QUEUED) out.put(codec.encode(m))
        }

        fun sendScannerState(state: ScannerState) = synchronized(this) {
            if (closing) return@synchronized
            session.scannerState(state)?.let { out.put(codec.encode(it)) }
        }

        private fun writeLoop() {
            try {
                val os = socket.getOutputStream()
                while (true) {
                    val b = out.poll(1, TimeUnit.SECONDS)
                    if (b == null) { if (closing) break else continue }
                    if (b.isEmpty()) break // end marker
                    os.write(b)
                    if (out.isEmpty()) os.flush()
                }
                os.flush()
            } catch (_: IOException) {
            } catch (_: InterruptedException) {
            }
            try { socket.close() } catch (_: IOException) {}
        }

        private fun finish() {
            synchronized(this) {
                if (closing) return
                closing = true
                session.closed()
                out.put(ByteArray(0))
            }
            removed(this)
        }

        fun shutdown() {
            synchronized(this) { closing = true; session.closed() }
            try { socket.close() } catch (_: IOException) {}
        }
    }

    private fun nowMs() = System.nanoTime() / 1_000_000

    companion object {
        const val DEFAULT_PORT = 6053
        private const val FLUSH_TICK_MS = 50L
        private const val MAX_QUEUED = 64
    }
}

/** How long a new client has to finish the handshake before it's closed. */
private const val HANDSHAKE_MS = 10_000
