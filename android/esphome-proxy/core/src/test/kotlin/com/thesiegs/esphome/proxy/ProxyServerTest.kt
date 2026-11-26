package com.thesiegs.esphome.proxy

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** The real server on loopback, driven by [TestClient]. */
class ProxyServerTest {
    private val identity = ProxyIdentity("wall-tablet", "Wall tablet", mac = 0x02AABBCCDDEEL)
    private val subscribedEvents = LinkedBlockingQueue<Boolean>()
    private val listener = object : ProxyServer.Listener {
        override fun onSubscribedChanged(subscribed: Boolean) { subscribedEvents.put(subscribed) }
    }
    private var server: ProxyServer? = null

    @After
    fun tearDown() { server?.stop() }

    private fun startServer(psk: ByteArray?): Int {
        val s = ProxyServer(identity.copy(encrypted = psk != null), psk, port = 0, listener = listener)
        server = s
        return s.start()
    }

    private fun runClient(psk: ByteArray?) {
        val port = startServer(psk)
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5000
            val c = TestClient(socket, psk)
            assertTrue(c.handshake())
            c.send(HelloRequest("test", 1, 12).encode())
            c.send(ApiMessage(MessageType.AUTHENTICATION_REQUEST))
            assertEquals("wall-tablet", HelloResponse.decode(c.receive(MessageType.HELLO_RESPONSE).payload).name)
            c.send(ApiMessage(MessageType.DEVICE_INFO_REQUEST))
            val info = DeviceInfoResponse.decode(c.receive(MessageType.DEVICE_INFO_RESPONSE).payload)
            assertEquals(psk != null, info.apiEncryptionSupported)

            c.send(ApiMessage(MessageType.SUBSCRIBE_BLUETOOTH_LE_ADVERTISEMENTS_REQUEST, ProtoWriter().uint32(1, 1).toByteArray()))
            assertEquals(true, subscribedEvents.poll(5, TimeUnit.SECONDS))
            server!!.setScannerState(ScannerState.RUNNING)
            // 20 ads: one full batch of 16 right away, then 4 after the 100 ms delay.
            for (i in 0 until 20) server!!.publish(RawAdvertisement(0x100000L + i, -40 - i, 0, byteArrayOf(2, 1, 6)))
            val got = ArrayList<RawAdvertisement>()
            val sizes = ArrayList<Int>()
            while (got.size < 20) {
                val m = c.receive()
                when (m.type) {
                    MessageType.BLUETOOTH_LE_RAW_ADVERTISEMENTS_RESPONSE ->
                        RawAdvertisementsResponse.decode(m.payload).also { sizes += it.size; got += it }
                    MessageType.BLUETOOTH_SCANNER_STATE_RESPONSE -> {}
                    else -> error("unexpected ${m.type}")
                }
            }
            assertEquals(listOf(16, 4), sizes)
            assertEquals(-59, got.last().rssi)

            c.send(ApiMessage(MessageType.UNSUBSCRIBE_BLUETOOTH_LE_ADVERTISEMENTS_REQUEST))
            assertEquals(false, subscribedEvents.poll(5, TimeUnit.SECONDS))
            c.send(ApiMessage(MessageType.PING_REQUEST))
            c.receive(MessageType.PING_RESPONSE)
            c.send(ApiMessage(MessageType.DISCONNECT_REQUEST))
            c.receive(MessageType.DISCONNECT_RESPONSE)
        }
    }

    @Test
    fun plaintextEndToEnd() = runClient(null)

    @Test
    fun noiseEndToEnd() = runClient(ByteArray(32) { (it * 3).toByte() })

    @Test
    fun droppedSubscriberStopsScanning() {
        val port = startServer(null)
        Socket("127.0.0.1", port).use { socket ->
            val c = TestClient(socket, null)
            c.send(HelloRequest("test", 1, 12).encode())
            c.receive(MessageType.HELLO_RESPONSE)
            c.send(ApiMessage(MessageType.SUBSCRIBE_BLUETOOTH_LE_ADVERTISEMENTS_REQUEST, ProtoWriter().uint32(1, 1).toByteArray()))
            assertEquals(true, subscribedEvents.poll(5, TimeUnit.SECONDS))
        }
        // The socket closed without an unsubscribe: scanning must still stop.
        assertEquals(false, subscribedEvents.poll(5, TimeUnit.SECONDS))
        assertFalse(server!!.subscribed)
    }

    @Test
    fun wrongKeyIsRefused() {
        val port = startServer(ByteArray(32) { 1 })
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5000
            assertFalse(TestClient(socket, ByteArray(32) { 2 }).handshake())
        }
    }
}
