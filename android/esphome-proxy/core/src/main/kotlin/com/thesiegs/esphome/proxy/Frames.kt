package com.thesiegs.esphome.proxy

import java.io.ByteArrayOutputStream

/** What a frame codec makes of incoming bytes. */
sealed class FrameEvent {
    /** A whole API message arrived. */
    class Message(val message: ApiMessage) : FrameEvent()
    /** Raw bytes the codec itself must send (the Noise hello and handshake). */
    class Write(val bytes: ByteArray) : FrameEvent()
    /** The peer broke the protocol; send nothing more and close. */
    class Fail(val reason: String) : FrameEvent()
}

/**
 * The server side of one connection's framing, without sockets: feed it what the socket read,
 * send what it returns, and wrap outgoing messages with [encode].
 */
interface ServerFrameCodec {
    /** True once messages may be sent (always for plaintext; after the handshake for Noise). */
    val ready: Boolean
    fun receive(data: ByteArray, offset: Int = 0, length: Int = data.size): List<FrameEvent>
    fun encode(message: ApiMessage): ByteArray
}

/** Collects stream bytes until whole frames are available. */
internal class ByteBuffer {
    var data = ByteArray(0); private set
    var size = 0; private set

    fun append(src: ByteArray, off: Int, len: Int) {
        if (size + len > data.size) data = data.copyOf(maxOf(size + len, data.size * 2, 256))
        System.arraycopy(src, off, data, size, len)
        size += len
    }

    fun consume(n: Int) {
        System.arraycopy(data, n, data, 0, size - n)
        size -= n
    }

    operator fun get(i: Int) = data[i].toInt() and 0xFF
}

/** Largest message we accept; the requests a proxy sees are a few bytes. */
const val MAX_INCOMING_MESSAGE = 64 * 1024

/**
 * Plaintext frames (ESPHome api_frame_helper_plaintext.cpp): 0x00, varint payload size,
 * varint message type, payload.
 */
class PlaintextServerCodec : ServerFrameCodec {
    private val buf = ByteBuffer()
    private var failed = false
    override val ready get() = !failed

    override fun receive(data: ByteArray, offset: Int, length: Int): List<FrameEvent> {
        if (failed) return emptyList()
        buf.append(data, offset, length)
        val events = ArrayList<FrameEvent>()
        while (buf.size > 0) {
            if (buf[0] != 0x00) {
                failed = true
                // An encrypted client sent its Noise hello. Answer with an empty plaintext frame so
                // aioesphomeapi reports "the device is using plaintext" rather than a bare reset.
                events += FrameEvent.Write(byteArrayOf(0, 0, 0))
                events += FrameEvent.Fail("bad indicator byte ${buf[0]} (client wants encryption)")
                return events
            }
            var pos = 1
            val size = readVarint(pos) ?: return events
            pos = size.second
            val type = readVarint(pos) ?: return events
            pos = type.second
            if (size.first > MAX_INCOMING_MESSAGE) {
                failed = true
                return events + FrameEvent.Fail("message too big: ${size.first}")
            }
            val len = size.first.toInt()
            if (buf.size < pos + len) return events
            events += FrameEvent.Message(ApiMessage(type.first.toInt(), buf.data.copyOfRange(pos, pos + len)))
            buf.consume(pos + len)
        }
        return events
    }

    /** Returns (value, next position), or null when the varint isn't complete yet. */
    private fun readVarint(start: Int): Pair<Long, Int>? {
        var pos = start
        var shift = 0
        var v = 0L
        while (pos < buf.size) {
            val b = buf[pos++]
            v = v or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return v to pos
            shift += 7
            if (shift > 35) throw ProtocolException("varint too long")
        }
        return null
    }

    override fun encode(message: ApiMessage): ByteArray = encodePlaintext(message)

    companion object {
        fun encodePlaintext(message: ApiMessage): ByteArray {
            val out = ByteArrayOutputStream(message.payload.size + 8)
            out.write(0)
            writeVarint(out, message.payload.size.toLong())
            writeVarint(out, message.type.toLong())
            out.write(message.payload)
            return out.toByteArray()
        }

        private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
            var v = value
            while (v >= 0x80) { out.write(((v and 0x7F) or 0x80).toInt()); v = v ushr 7 }
            out.write(v.toInt())
        }
    }
}

/**
 * Noise frames (ESPHome api_frame_helper_noise.cpp, aioesphomeapi _frame_helper/noise.py): every
 * frame is 0x01, a big-endian u16 length, then the frame. The client opens with an empty hello
 * frame and a handshake frame (0x00 + Noise message 1). The server answers with its hello —
 * 0x01 (the chosen protocol), the device name, 0x00, the MAC as 12 lowercase hex digits, 0x00 — and
 * then 0x00 + Noise message 2, or 0x01 + "Handshake MAC failure" when the key doesn't match.
 * After that each frame is one encrypted message: u16 type, u16 payload length, payload.
 */
class NoiseServerCodec(
    private val psk: ByteArray,
    private val deviceName: String,
    private val macPlain: String,
    private val handshakeFactory: (ByteArray) -> NoiseHandshake = { NoiseHandshake(initiator = false, psk = it) },
) : ServerFrameCodec {
    private enum class State { HELLO, HANDSHAKE, DATA, FAILED }

    private val buf = ByteBuffer()
    private var state = State.HELLO
    private var handshake: NoiseHandshake? = null
    private var encryptor: CipherState? = null
    private var decryptor: CipherState? = null

    override val ready get() = state == State.DATA

    override fun receive(data: ByteArray, offset: Int, length: Int): List<FrameEvent> {
        if (state == State.FAILED) return emptyList()
        buf.append(data, offset, length)
        val events = ArrayList<FrameEvent>()
        while (buf.size >= 3) {
            if (buf[0] != 0x01) {
                state = State.FAILED
                // A plaintext client: answer with a Noise frame so aioesphomeapi reports
                // "requires encryption" (it checks for the 0x01 preamble).
                events += FrameEvent.Write(frame(byteArrayOf(0x01) + "Bad indicator byte".toByteArray()))
                events += FrameEvent.Fail("bad indicator byte ${buf[0]} (client sent plaintext)")
                return events
            }
            val len = (buf[1] shl 8) or buf[2]
            if (buf.size < 3 + len) return events
            val body = buf.data.copyOfRange(3, 3 + len)
            buf.consume(3 + len)
            when (state) {
                State.HELLO -> {
                    // The client hello's content is unused (ESPHome ignores it too).
                    events += FrameEvent.Write(frame(serverHello()))
                    handshake = handshakeFactory(psk)
                    state = State.HANDSHAKE
                }
                State.HANDSHAKE -> {
                    if (body.isEmpty() || body[0] != 0.toByte()) {
                        return events + fail("bad handshake frame")
                    }
                    val hs = handshake!!
                    try {
                        hs.readMessage(body.copyOfRange(1, body.size))
                    } catch (e: NoiseException) {
                        events += FrameEvent.Write(frame(byteArrayOf(0x01) + "Handshake MAC failure".toByteArray()))
                        return events + fail("handshake failed: wrong encryption key")
                    }
                    events += FrameEvent.Write(frame(byteArrayOf(0x00) + hs.writeMessage()))
                    encryptor = hs.encryptor; decryptor = hs.decryptor
                    handshake = null
                    state = State.DATA
                }
                State.DATA -> {
                    val plain = try { decryptor!!.decrypt(ByteArray(0), body) } catch (e: NoiseException) {
                        return events + fail("decrypt failed")
                    }
                    if (plain.size < 4) return events + fail("decrypted message too short")
                    val type = ((plain[0].toInt() and 0xFF) shl 8) or (plain[1].toInt() and 0xFF)
                    val size = ((plain[2].toInt() and 0xFF) shl 8) or (plain[3].toInt() and 0xFF)
                    if (size != plain.size - 4) return events + fail("bad inner length")
                    events += FrameEvent.Message(ApiMessage(type, plain.copyOfRange(4, plain.size)))
                }
                State.FAILED -> return events
            }
        }
        return events
    }

    private fun fail(reason: String): FrameEvent {
        state = State.FAILED
        return FrameEvent.Fail(reason)
    }

    private fun serverHello(): ByteArray =
        byteArrayOf(0x01) + deviceName.toByteArray(Charsets.UTF_8) + byteArrayOf(0) +
            macPlain.toByteArray(Charsets.US_ASCII) + byteArrayOf(0)

    override fun encode(message: ApiMessage): ByteArray {
        val enc = checkNotNull(encryptor) { "handshake not finished" }
        val p = message.payload
        require(p.size <= 0xFFFF - 4 - Noise.TAG_LEN) { "message too big for one Noise frame: ${p.size}" }
        val inner = ByteArray(4 + p.size)
        inner[0] = (message.type ushr 8).toByte(); inner[1] = message.type.toByte()
        inner[2] = (p.size ushr 8).toByte(); inner[3] = p.size.toByte()
        System.arraycopy(p, 0, inner, 4, p.size)
        return frame(enc.encrypt(ByteArray(0), inner))
    }

    companion object {
        fun frame(body: ByteArray): ByteArray {
            require(body.size <= 0xFFFF)
            return byteArrayOf(0x01, (body.size ushr 8).toByte(), body.size.toByte()) + body
        }
    }
}
