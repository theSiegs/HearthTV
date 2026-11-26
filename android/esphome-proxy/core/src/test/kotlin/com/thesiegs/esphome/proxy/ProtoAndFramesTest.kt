package com.thesiegs.esphome.proxy

import com.thesiegs.esphome.proxy.NoiseTest.Companion.hex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtoAndFramesTest {
    @Test
    fun rawAdvertisementWireBytes() {
        // Expected bytes from aioesphomeapi's own api_pb2 (protobuf SerializeToString).
        val ad = RawAdvertisement(0x112233445566L, -60, 1, byteArrayOf(2, 1))
        assertArrayEquals(hex("08e6aa919aa3a4041077180122020201"), ad.encode())
        assertEquals(ad, RawAdvertisement.decode(ad.encode()))
    }

    @Test
    fun deviceInfoWireBytesMatchApiPb2() {
        val info = DeviceInfoResponse(
            name = "wall-tablet", macAddress = "02:AA:BB:CC:DD:EE", esphomeVersion = "2025.10.0",
            bluetoothProxyFeatureFlags = 97, suggestedArea = "Kitchen", apiEncryptionSupported = true,
        )
        assertArrayEquals(
            hex("120b77616c6c2d7461626c65741a1130323a41413a42423a43433a44443a45452209323032352e31302e3078618201074b69746368656e980101"),
            info.encode().payload,
        )
    }

    @Test
    fun paddingIsTrimmedToWholeStructures() {
        val padded = byteArrayOf(2, 1, 6, 3, 3, 0x6F, 0xFD.toByte()) + ByteArray(55)
        assertArrayEquals(byteArrayOf(2, 1, 6, 3, 3, 0x6F, 0xFD.toByte()), RawAdvertisement.trimPadding(padded))
        // A structure that runs past the end is dropped, not cut.
        assertArrayEquals(byteArrayOf(2, 1, 6), RawAdvertisement.trimPadding(byteArrayOf(2, 1, 6, 9, 0xFF.toByte(), 1)))
        assertEquals(0, RawAdvertisement.trimPadding(null).size)
    }

    @Test
    fun sint32Zigzag() {
        for (v in listOf(-1, 1, -127, -100, 0, Int.MIN_VALUE, Int.MAX_VALUE)) {
            val f = ProtoReader.fields(ProtoWriter().sint32(2, v).toByteArray()).firstOrNull()
            assertEquals(v, f?.sint32 ?: 0)
        }
    }

    @Test
    fun deviceInfoRoundTripAndFlags() {
        val id = ProxyIdentity("wall-tablet", "Wall tablet", "Kitchen", mac = 0x02AABBCCDDEEL, bluetoothMac = 0x02AABBCCDDF0L, encrypted = true)
        val info = DeviceInfoResponse.decode(id.deviceInfo().encode().payload)
        assertEquals("wall-tablet", info.name)
        assertEquals("02:AA:BB:CC:DD:EE", info.macAddress)
        assertEquals("02:AA:BB:CC:DD:F0", info.bluetoothMacAddress)
        assertEquals("Kitchen", info.suggestedArea)
        assertEquals(1 or 32 or 64, info.bluetoothProxyFeatureFlags)
        assertTrue(info.apiEncryptionSupported)
        assertEquals("02aabbccddee", MacAddress.formatPlain(id.mac))
        assertEquals(0x02AABBCCDDEEL, MacAddress.parse("02:AA:BB:CC:DD:EE"))
    }

    @Test
    fun plaintextFramesSplitAnywhere() {
        val a = PlaintextServerCodec.encodePlaintext(HelloRequest("test", 1, 10).encode())
        val b = PlaintextServerCodec.encodePlaintext(ApiMessage(MessageType.PING_REQUEST))
        val c = PlaintextServerCodec.encodePlaintext(ApiMessage(300, ByteArray(200) { it.toByte() }))
        val all = a + b + c
        val codec = PlaintextServerCodec()
        val got = all.flatMap { codec.receive(byteArrayOf(it)) }.map { (it as FrameEvent.Message).message }
        assertEquals(listOf(1, 7, 300), got.map { it.type })
        assertEquals("test", HelloRequest.decode(got[0].payload).clientInfo)
        assertEquals(200, got[2].payload.size)
    }

    @Test
    fun plaintextServerRejectsNoiseClient() {
        val ev = PlaintextServerCodec().receive(byteArrayOf(1, 0, 0))
        assertArrayEquals(byteArrayOf(0, 0, 0), (ev[0] as FrameEvent.Write).bytes)
        assertTrue(ev[1] is FrameEvent.Fail)
    }

    @Test
    fun noiseServerHandshakeAndMessages() {
        val psk = ByteArray(32) { it.toByte() }
        val server = NoiseServerCodec(psk, "wall-tablet", "02aabbccddee")
        // The client's hello and handshake frames arrive in one read.
        val hs = NoiseHandshake(initiator = true, psk = psk)
        val hello = byteArrayOf(1, 0, 0) + NoiseServerCodec.frame(byteArrayOf(0) + hs.writeMessage())
        val events = server.receive(hello)
        assertEquals(2, events.size)
        val serverHello = (events[0] as FrameEvent.Write).bytes
        assertArrayEquals(NoiseServerCodec.frame(byteArrayOf(1) + "wall-tablet".toByteArray() + 0 + "02aabbccddee".toByteArray() + 0), serverHello)
        val reply = (events[1] as FrameEvent.Write).bytes
        assertEquals(0, reply[3].toInt())
        hs.readMessage(reply.copyOfRange(4, reply.size))
        assertTrue(server.ready)

        // Client -> server: a PingRequest, then server -> client: a PingResponse.
        val inner = byteArrayOf(0, 7, 0, 0)
        val ev = server.receive(NoiseServerCodec.frame(hs.encryptor!!.encrypt(ByteArray(0), inner)))
        assertEquals(7, ((ev.single() as FrameEvent.Message).message.type))
        val out = server.encode(ApiMessage(8))
        val plain = hs.decryptor!!.decrypt(ByteArray(0), out.copyOfRange(3, out.size))
        assertArrayEquals(byteArrayOf(0, 8, 0, 0), plain)
    }

    @Test
    fun noiseServerRefusesWrongKey() {
        val server = NoiseServerCodec(ByteArray(32) { 1 }, "wall-tablet", "02aabbccddee")
        val hs = NoiseHandshake(initiator = true, psk = ByteArray(32) { 2 })
        val events = server.receive(byteArrayOf(1, 0, 0) + NoiseServerCodec.frame(byteArrayOf(0) + hs.writeMessage()))
        val refusal = (events[1] as FrameEvent.Write).bytes
        assertArrayEquals(NoiseServerCodec.frame(byteArrayOf(1) + "Handshake MAC failure".toByteArray()), refusal)
        assertTrue(events[2] is FrameEvent.Fail)
        assertFalse(server.ready)
    }

    @Test
    fun noiseServerRejectsPlaintextClient() {
        val server = NoiseServerCodec(ByteArray(32), "n", "m")
        val ev = server.receive(PlaintextServerCodec.encodePlaintext(HelloRequest("x", 1, 10).encode()))
        assertEquals(1, (ev[0] as FrameEvent.Write).bytes[0].toInt())
        assertTrue(ev[1] is FrameEvent.Fail)
    }

    @Test
    fun batcherFlushesOnCountOrAge() {
        val b = AdvertisementBatcher(maxBatch = 16, maxDelayMs = 100)
        val ad = RawAdvertisement(1, -50, 0, byteArrayOf(1))
        for (i in 0 until 15) assertNull(b.add(ad, 1000L + i))
        assertEquals(16, b.add(ad, 1020)!!.size)
        assertNull(b.poll(5000))
        assertNull(b.add(ad, 2000))
        assertNull(b.poll(2099))
        assertEquals(1, b.poll(2100)!!.size)
        assertEquals(0, b.size)
    }
}
