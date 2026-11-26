package com.thesiegs.esphome.proxy

import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

/** The client side of the framing, for tests: what aioesphomeapi does, reduced to blocking reads. */
class TestClient(private val input: InputStream, private val output: OutputStream, psk: ByteArray?) {
    private val data = DataInputStream(input)
    private val handshake = psk?.let { NoiseHandshake(initiator = true, psk = it) }
    var serverHello: ByteArray? = null; private set

    constructor(socket: Socket, psk: ByteArray?) : this(socket.getInputStream(), socket.getOutputStream(), psk)

    /** Runs the Noise handshake (no-op for plaintext). Returns false if the server refused the key. */
    fun handshake(): Boolean {
        val hs = handshake ?: return true
        output.write(byteArrayOf(1, 0, 0))
        output.write(NoiseServerCodec.frame(byteArrayOf(0) + hs.writeMessage()))
        output.flush()
        serverHello = readNoiseFrame()
        val reply = readNoiseFrame()
        if (reply[0] != 0.toByte()) return false
        hs.readMessage(reply.copyOfRange(1, reply.size))
        return true
    }

    fun send(m: ApiMessage) {
        val hs = handshake
        if (hs == null) {
            output.write(PlaintextServerCodec.encodePlaintext(m))
        } else {
            val p = m.payload
            val inner = byteArrayOf((m.type ushr 8).toByte(), m.type.toByte(), (p.size ushr 8).toByte(), p.size.toByte()) + p
            output.write(NoiseServerCodec.frame(hs.encryptor!!.encrypt(ByteArray(0), inner)))
        }
        output.flush()
    }

    fun receive(): ApiMessage {
        val hs = handshake
        if (hs == null) {
            check(data.readUnsignedByte() == 0)
            val size = readVarint()
            val type = readVarint()
            val payload = ByteArray(size).also { data.readFully(it) }
            return ApiMessage(type, payload)
        }
        val plain = hs.decryptor!!.decrypt(ByteArray(0), readNoiseFrame())
        val type = ((plain[0].toInt() and 0xFF) shl 8) or (plain[1].toInt() and 0xFF)
        return ApiMessage(type, plain.copyOfRange(4, plain.size))
    }

    /** Receives until a message of [type] arrives, skipping others. */
    fun receive(type: Int): ApiMessage {
        while (true) { val m = receive(); if (m.type == type) return m }
    }

    fun readNoiseFrame(): ByteArray {
        check(data.readUnsignedByte() == 1)
        val len = data.readUnsignedShort()
        return ByteArray(len).also { data.readFully(it) }
    }

    private fun readVarint(): Int {
        var shift = 0; var v = 0
        while (true) {
            val b = data.readUnsignedByte()
            v = v or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) return v
            shift += 7
        }
    }
}
