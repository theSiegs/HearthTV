package com.thesiegs.esphome.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiSessionTest {
    private val identity = ProxyIdentity("wall-tablet", "Wall tablet", "Kitchen", mac = 0x02AABBCCDDEEL)
    private fun session() = ApiSession(identity) { 1_700_000_000L }
    private fun msg(type: Int, payload: ByteArray = ByteArray(0)) = ApiMessage(type, payload)
    private val subscribeRaw = msg(
        MessageType.SUBSCRIBE_BLUETOOTH_LE_ADVERTISEMENTS_REQUEST,
        ProtoWriter().uint32(1, BluetoothSubscriptionFlag.RAW_ADVERTISEMENTS).toByteArray(),
    )

    private fun connected() = session().also { it.onMessage(HelloRequest("Home Assistant", 1, 12).encode()) }

    @Test
    fun helloThenDeviceInfo() {
        val s = session()
        val hello = s.onMessage(HelloRequest("Home Assistant 2026.11", 1, 12).encode()).replies.single()
        assertEquals(MessageType.HELLO_RESPONSE, hello.type)
        val r = HelloResponse.decode(hello.payload)
        assertEquals(1, r.apiVersionMajor)
        assertEquals(12, r.apiVersionMinor)
        assertEquals("wall-tablet", r.name)
        assertEquals("Home Assistant 2026.11", s.clientInfo)

        // aioesphomeapi sends an empty AuthenticationRequest right after hello; no reply.
        assertTrue(s.onMessage(msg(MessageType.AUTHENTICATION_REQUEST)).replies.isEmpty())
        val info = s.onMessage(msg(MessageType.DEVICE_INFO_REQUEST)).replies.single()
        assertEquals("02:AA:BB:CC:DD:EE", DeviceInfoResponse.decode(info.payload).macAddress)
        assertEquals(MessageType.LIST_ENTITIES_DONE_RESPONSE, s.onMessage(msg(MessageType.LIST_ENTITIES_REQUEST)).replies.single().type)
    }

    @Test
    fun messagesBeforeHelloClose() {
        val r = session().onMessage(msg(MessageType.DEVICE_INFO_REQUEST))
        assertTrue(r.close)
        assertTrue(r.replies.isEmpty())
    }

    @Test
    fun pingAndIgnoredSubscriptions() {
        val s = connected()
        assertEquals(MessageType.PING_RESPONSE, s.onMessage(msg(MessageType.PING_REQUEST)).replies.single().type)
        for (t in listOf(20, 28, 34, 38, 37, 9999)) assertTrue(s.onMessage(msg(t)).replies.isEmpty())
        val time = s.onMessage(msg(MessageType.GET_TIME_REQUEST)).replies.single()
        assertEquals(1_700_000_000L, ProtoReader.fields(time.payload).single().value)
    }

    @Test
    fun disconnectRequestIsAnsweredThenCloses() {
        val s = connected()
        val r = s.onMessage(msg(MessageType.DISCONNECT_REQUEST))
        assertEquals(MessageType.DISCONNECT_RESPONSE, r.replies.single().type)
        assertTrue(r.close)
        assertEquals(ApiSession.Phase.CLOSED, s.phase)
    }

    @Test
    fun advertisementsOnlyWhileSubscribed() {
        val s = connected()
        val batch = listOf(RawAdvertisement(0xA1B2C3D4E5F6L, -70, 0, byteArrayOf(2, 1, 6)))
        assertNull(s.advertisements(batch))
        s.onMessage(subscribeRaw)
        assertTrue(s.subscribed)
        val m = s.advertisements(batch)!!
        assertEquals(MessageType.BLUETOOTH_LE_RAW_ADVERTISEMENTS_RESPONSE, m.type)
        assertEquals(batch, RawAdvertisementsResponse.decode(m.payload))
        s.onMessage(msg(MessageType.UNSUBSCRIBE_BLUETOOTH_LE_ADVERTISEMENTS_REQUEST))
        assertFalse(s.subscribed)
        assertNull(s.advertisements(batch))
    }

    @Test
    fun legacyParsedSubscriptionIsNotHonored() {
        val s = connected()
        s.onMessage(msg(MessageType.SUBSCRIBE_BLUETOOTH_LE_ADVERTISEMENTS_REQUEST))
        assertFalse(s.subscribed)
    }

    @Test
    fun scannerStateIsReportedOnSubscribeAndChange() {
        val s = connected()
        assertNull(s.scannerState(ScannerState.IDLE))
        val onSubscribe = s.onMessage(subscribeRaw).replies.single()
        assertEquals(ScannerState.IDLE, ScannerStateResponse.decode(onSubscribe.payload).state)
        val running = s.scannerState(ScannerState.RUNNING)!!
        val decoded = ScannerStateResponse.decode(running.payload)
        assertEquals(ScannerState.RUNNING, decoded.state)
        assertEquals(ScannerMode.ACTIVE, decoded.mode)
        // HA asking for passive gets the truth back: Android scans actively.
        val afterSet = s.onMessage(msg(MessageType.BLUETOOTH_SCANNER_SET_MODE_REQUEST, ProtoWriter().enum(1, 0).toByteArray()))
        assertEquals(ScannerMode.ACTIVE, ScannerStateResponse.decode(afterSet.replies.single().payload).mode)
    }

    @Test
    fun keyProvisioningIsRefused() {
        val r = connected().onMessage(msg(MessageType.NOISE_ENCRYPTION_SET_KEY_REQUEST, ProtoWriter().bytes(1, ByteArray(32)).toByteArray()))
        val reply = r.replies.single()
        assertEquals(MessageType.NOISE_ENCRYPTION_SET_KEY_RESPONSE, reply.type)
        assertTrue(ProtoReader.fields(reply.payload).isEmpty()) // success=false
        assertNotNull(r)
    }

    @Test
    fun derivedMacIsStableLocalUnicast() {
        val a = ProxyIdentity.deriveMac("0123456789abcdef")
        assertEquals(a, ProxyIdentity.deriveMac("0123456789abcdef"))
        assertTrue(a != ProxyIdentity.deriveMac("fedcba9876543210"))
        val first = (a ushr 40).toInt()
        assertEquals(0x02, first and 0x03) // locally administered, not multicast
        assertEquals(0L, a ushr 48)
        assertEquals(0x02AABBCCDD01L, ProxyIdentity.bluetoothMacFor(0x02AABBCCDDFFL))
    }

    @Test
    fun namesBecomeHostnames() {
        assertEquals("kitchen-wall-tablet", ProxyIdentity.sanitizeName("Kitchen Wall Tablet!"))
        assertEquals("android-proxy", ProxyIdentity.sanitizeName("***"))
        assertTrue(ProxyIdentity.sanitizeName("a".repeat(40)).length <= 31)
    }
}
