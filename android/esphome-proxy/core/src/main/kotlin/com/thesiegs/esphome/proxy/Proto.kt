package com.thesiegs.esphome.proxy

import java.io.ByteArrayOutputStream

/**
 * Just enough protobuf (proto3 wire format) for the ESPHome API messages a Bluetooth proxy uses:
 * varints, zigzag sint32, fixed32, length-delimited bytes and nested messages. Proto3 leaves
 * default values off the wire, so the writer skips zeros, false and empty strings the same way.
 */
class ProtoWriter {
    private val out = ByteArrayOutputStream()

    fun toByteArray(): ByteArray = out.toByteArray()

    fun uint32(field: Int, value: Int) = apply {
        if (value != 0) { tag(field, WIRE_VARINT); varint(value.toLong() and 0xFFFFFFFFL) }
    }

    /** Enums travel as varints; negative enum values never occur in the messages used here. */
    fun enum(field: Int, value: Int) = uint32(field, value)

    fun uint64(field: Int, value: Long) = apply {
        if (value != 0L) { tag(field, WIRE_VARINT); varint(value) }
    }

    fun sint32(field: Int, value: Int) = apply {
        if (value != 0) { tag(field, WIRE_VARINT); varint(((value shl 1) xor (value shr 31)).toLong() and 0xFFFFFFFFL) }
    }

    fun bool(field: Int, value: Boolean) = apply {
        if (value) { tag(field, WIRE_VARINT); varint(1) }
    }

    fun fixed32(field: Int, value: Int) = apply {
        if (value != 0) {
            tag(field, WIRE_FIXED32)
            for (i in 0 until 4) out.write((value ushr (8 * i)) and 0xFF)
        }
    }

    fun string(field: Int, value: String) = apply {
        if (value.isNotEmpty()) bytes(field, value.toByteArray(Charsets.UTF_8))
    }

    fun bytes(field: Int, value: ByteArray) = apply {
        if (value.isNotEmpty()) lengthDelimited(field, value)
    }

    /** A nested message; written even when empty, since a repeated element must stay in the list. */
    fun message(field: Int, value: ByteArray) = apply { lengthDelimited(field, value) }

    private fun lengthDelimited(field: Int, value: ByteArray) {
        tag(field, WIRE_LEN)
        varint(value.size.toLong())
        out.write(value, 0, value.size)
    }

    private fun tag(field: Int, wire: Int) = varint(((field shl 3) or wire).toLong())

    private fun varint(value: Long) {
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }

    companion object {
        const val WIRE_VARINT = 0
        const val WIRE_FIXED64 = 1
        const val WIRE_LEN = 2
        const val WIRE_FIXED32 = 5
    }
}

/** One decoded field: [number], its wire type, and either a numeric [value] or [bytes]. */
class ProtoField(val number: Int, val wireType: Int, val value: Long, val bytes: ByteArray?) {
    val int: Int get() = value.toInt()
    val bool: Boolean get() = value != 0L
    val sint32: Int get() = ((value ushr 1) xor -(value and 1)).toInt()
    val string: String get() = String(bytes ?: ByteArray(0), Charsets.UTF_8)
}

/** Reads every field of a message in order, skipping nothing; callers ignore numbers they don't know. */
object ProtoReader {
    fun fields(data: ByteArray): List<ProtoField> {
        val result = ArrayList<ProtoField>()
        var pos = 0
        fun varint(): Long {
            var shift = 0
            var v = 0L
            while (true) {
                if (pos >= data.size) throw ProtocolException("truncated varint")
                if (shift >= 64) throw ProtocolException("varint too long")
                val b = data[pos++].toInt() and 0xFF
                v = v or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return v
                shift += 7
            }
        }
        while (pos < data.size) {
            val key = varint()
            val number = (key ushr 3).toInt()
            val wire = (key and 7).toInt()
            if (number <= 0) throw ProtocolException("bad field number $number")
            when (wire) {
                ProtoWriter.WIRE_VARINT -> result += ProtoField(number, wire, varint(), null)
                ProtoWriter.WIRE_FIXED64 -> {
                    if (pos + 8 > data.size) throw ProtocolException("truncated fixed64")
                    var v = 0L
                    for (i in 0 until 8) v = v or ((data[pos + i].toLong() and 0xFF) shl (8 * i))
                    pos += 8
                    result += ProtoField(number, wire, v, null)
                }
                ProtoWriter.WIRE_LEN -> {
                    val len = varint()
                    if (len < 0 || pos + len > data.size) throw ProtocolException("truncated field $number")
                    val bytes = data.copyOfRange(pos, pos + len.toInt())
                    pos += len.toInt()
                    result += ProtoField(number, wire, 0, bytes)
                }
                ProtoWriter.WIRE_FIXED32 -> {
                    if (pos + 4 > data.size) throw ProtocolException("truncated fixed32")
                    var v = 0L
                    for (i in 0 until 4) v = v or ((data[pos + i].toLong() and 0xFF) shl (8 * i))
                    pos += 4
                    result += ProtoField(number, wire, v, null)
                }
                else -> throw ProtocolException("unsupported wire type $wire")
            }
        }
        return result
    }
}

/** A malformed frame or message; the connection that sent it is closed. */
class ProtocolException(message: String) : Exception(message)
