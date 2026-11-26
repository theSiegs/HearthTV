package com.thesiegs.esphome.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Opt-in: runs the real aioesphomeapi client (the library HA uses) against [ProxyServer] on
 * loopback, plaintext and Noise. Set ESPHOME_PROXY_PYTHON to a Python that has aioesphomeapi
 * installed; without it the test is skipped. See docs/design/esphome-proxy.md.
 */
class AioesphomeapiInteropTest {
    private val python = System.getenv("ESPHOME_PROXY_PYTHON")

    @Test
    fun plaintext() = run(null)

    @Test
    fun noise() = run(Noise.generatePsk())

    /** HA shows "invalid encryption key" for this one. */
    @Test
    fun wrongKey() = run(Noise.generatePsk(), clientKey = Noise.generatePsk(), expectError = "InvalidEncryptionKeyAPIError")

    /** HA asks for the key for this one. */
    @Test
    fun keyRequired() = run(Noise.generatePsk(), clientKey = null, expectError = "RequiresEncryptionAPIError")

    /** HA says the device isn't encrypted. */
    @Test
    fun keyNotUsed() = run(null, clientKey = Noise.generatePsk(), expectError = "EncryptionPlaintextAPIError")

    private fun run(key: String?, clientKey: String? = key, expectError: String? = null) {
        assumeTrue("ESPHOME_PROXY_PYTHON not set", !python.isNullOrBlank())
        val script = File.createTempFile("esphome_proxy_client", ".py").apply {
            deleteOnExit()
            writeBytes(AioesphomeapiInteropTest::class.java.getResourceAsStream("/esphome_proxy_client.py")!!.readBytes())
        }
        val feeding = java.util.concurrent.atomic.AtomicBoolean(false)
        val server = ProxyServer(
            ProxyIdentity("wall-tablet", "Wall tablet", "Kitchen", mac = 0x02AABBCCDDEEL, bluetoothMac = 0x02AABBCCDDF0L, encrypted = key != null),
            key?.let { Noise.decodePsk(it) },
            port = 0,
            listener = object : ProxyServer.Listener {
                override fun onSubscribedChanged(subscribed: Boolean) { feeding.set(subscribed) }
            },
            log = { println("server: $it") },
        )
        val port = server.start()
        server.setScannerState(ScannerState.RUNNING)
        val feeder = Thread {
            var i = 0
            while (!Thread.currentThread().isInterrupted) {
                if (feeding.get()) {
                    server.publish(RawAdvertisement(0xC0FFEE000000L + (i % 5), -50 - (i % 30), 1, byteArrayOf(2, 1, 6, 3, 3, 0x6F, 0xFD.toByte())))
                    i++
                }
                try { Thread.sleep(5) } catch (_: InterruptedException) { break }
            }
        }.apply { isDaemon = true; start() }
        try {
            val cmd = mutableListOf(python!!, "-I", script.absolutePath, "127.0.0.1", "--port", port.toString(), "--expect", "50", "--seconds", "20", "--quiet")
            if (clientKey != null) cmd += listOf("--key", clientKey)
            val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val output = p.inputStream.bufferedReader().readText()
            p.waitFor(60, TimeUnit.SECONDS)
            println(output)
            if (expectError == null) {
                assertEquals("aioesphomeapi client failed:\n$output", 0, p.exitValue())
            } else {
                assertTrue("expected $expectError:\n$output", p.exitValue() != 0 && expectError in output)
            }
        } finally {
            feeder.interrupt()
            server.stop()
        }
    }
}
