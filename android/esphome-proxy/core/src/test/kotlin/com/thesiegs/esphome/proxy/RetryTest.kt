package com.thesiegs.esphome.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.ServerSocket

/** Trying again (code review 2026-11-20): the backoff, and a port in use that frees up. */
class RetryTest {
    @Test
    fun `waits double from two seconds to a minute`() {
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L), (0..6).map(Retry::delayMs))
        assertEquals(60_000L, Retry.delayMs(100))
    }

    @Test
    fun `a port in use fails the start, and the same server starts once it's free`() {
        val holder = ServerSocket(0)
        val port = holder.localPort
        val server = ProxyServer(ProxyIdentity("wall-tablet", "Wall tablet", mac = 0x02AABBCCDDEEL), null, port = port, listener = object : ProxyServer.Listener {
            override fun onSubscribedChanged(subscribed: Boolean) {}
        })
        try {
            server.start()
            // Some systems let a reuse-address bind share the port; then there's nothing to retry.
            server.stop()
            holder.close()
            return
        } catch (e: IOException) {
            // Expected: the port is held.
        }
        holder.close()
        try {
            assertEquals(port, server.start())
            assertTrue(server.clientCount == 0)
        } catch (e: IOException) {
            fail("the server should start once the port is free: ${e.message}")
        } finally {
            server.stop()
        }
    }
}
