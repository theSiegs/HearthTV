package com.thesiegs.esphome.proxy.android

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import com.thesiegs.esphome.proxy.Noise
import com.thesiegs.esphome.proxy.ProxyIdentity
import com.thesiegs.esphome.proxy.ProxyServer
import com.thesiegs.esphome.proxy.Retry
import com.thesiegs.esphome.proxy.ScannerState
import java.io.IOException

/**
 * The proxy itself: API server + BLE scanner + mDNS record, wired so the radio and the wake locks
 * are on only while at least one HA client is subscribed to advertisements. [EspProxyService]
 * runs one; a host with its own foreground service can run one directly. Call [start] and [stop]
 * on the main thread.
 */
class EspProxyRuntime(
    context: Context,
    private val config: EspProxyConfig,
    private val log: (String) -> Unit = { Log.i(TAG, it) },
) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    /** What HA sees: the name, the host's MAC (else a stable one from ANDROID_ID), this tablet's model. */
    val identity: ProxyIdentity = run {
        @SuppressLint("HardwareIds")
        val mac = config.mac ?: ProxyIdentity.deriveMac(Settings.Secure.getString(app.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown")
        ProxyIdentity(
            name = ProxyIdentity.sanitizeName(config.name),
            friendlyName = config.friendlyName,
            suggestedArea = config.area,
            mac = mac,
            bluetoothMac = ProxyIdentity.bluetoothMacFor(mac),
            model = Build.MODEL ?: "Android",
            manufacturer = Build.MANUFACTURER ?: "Android",
            encrypted = config.psk != null,
        )
    }

    /** HA clients connected now, for a host's status line. */
    @Volatile var clients: Int = 0
        private set

    /** Advertisements heard since start, for a host's "ads per minute". */
    val advertisements = java.util.concurrent.atomic.AtomicLong()

    /** The scanner's last state, for a host's status line. */
    @Volatile var scannerState: ScannerState = ScannerState.IDLE
        private set

    /** Whether the proxy is up: started, not stopped, and its API port bound. */
    val isRunning: Boolean get() = running && listening

    private val scanner = BleScanner(
        app,
        onAdvertisement = { advertisements.incrementAndGet(); server.publish(it) },
        onState = { scannerState = it; server.setScannerState(it) },
        log = log,
    )
    private val mdns = MdnsAdvertiser(app, log)
    private val server = ProxyServer(
        identity,
        config.psk?.let { Noise.decodePsk(it) },
        port = config.port,
        listener = object : ProxyServer.Listener {
            override fun onSubscribedChanged(subscribed: Boolean) {
                main.post { if (running) onSubscribed(subscribed) }
            }
            override fun onClientsChanged(count: Int) {
                clients = count
                log("HA clients: $count")
            }
        },
        log = log,
    )
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    @Volatile private var running = false
    /** The API port is bound: set only after the bind, so a failed one never reads as running (code review 2026-11-20). */
    @Volatile private var listening = false
    private val lock = Any()

    fun start() {
        synchronized(lock) {
            if (running) return
            running = true
        }
        server.setScannerState(ScannerState.IDLE)
        bind(0)
    }

    /** Binds the API port off the main thread; a port in use is tried again, backing off ([Retry]). */
    private fun bind(attempt: Int) {
        Thread({
            try {
                val port = server.start()
                synchronized(lock) {
                    // Stopped while binding: the server is let go, no listener left on the port.
                    if (!running) { server.stop(); return@Thread }
                    listening = true
                }
                main.post { if (running) mdns.start(identity, port, Build.MODEL ?: "android") }
            } catch (e: IOException) {
                val wait = Retry.delayMs(attempt)
                log("can't listen on ${config.port}: ${e.message}; trying again in ${wait / 1000} s")
                main.postDelayed({ if (running && !listening) bind(attempt + 1) }, wait)
            } catch (e: IllegalStateException) {
                // Already listening (a bind raced a restart): nothing to do.
                synchronized(lock) { if (running) listening = true }
            }
        }, "esphome-proxy-start").start()
    }

    fun stop() {
        synchronized(lock) {
            if (!running) return
            running = false
            listening = false
        }
        mdns.stop()
        scanner.stop()
        releaseLocks()
        server.stop() // closes sockets only; quick, and frees the port before a restart binds it
    }

    private fun onSubscribed(subscribed: Boolean) {
        if (subscribed) {
            acquireLocks()
            scanner.start()
        } else {
            scanner.stop()
            releaseLocks()
        }
    }

    /**
     * While HA listens: keep the CPU and Wi-Fi awake so advertisements flow with the screen off.
     * Released the moment the last subscriber leaves.
     */
    @SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        if (wakeLock == null) {
            wakeLock = (app.getSystemService(Context.POWER_SERVICE) as? PowerManager)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "esphome-proxy:scan")
                ?.apply { setReferenceCounted(false); acquire() }
        }
        if (wifiLock == null) {
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            else @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = (app.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
                ?.createWifiLock(mode, "esphome-proxy")
                ?.apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
    }

    companion object {
        const val TAG = "EspProxy"
    }
}
