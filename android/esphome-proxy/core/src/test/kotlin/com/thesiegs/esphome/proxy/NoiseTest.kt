package com.thesiegs.esphome.proxy

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The vector below was produced by the noiseprotocol 0.3.1 library (the Noise implementation
 * aioesphomeapi 46.30.1 uses, with its ESPHome backend) for Noise_NNpsk0_25519_ChaChaPoly_SHA256
 * with ESPHome's prologue, fixed ephemeral keys and empty handshake payloads; then three
 * transport messages. Matching it byte for byte checks the whole handshake, key split and nonces.
 */
class NoiseTest {
    private val psk = Noise.decodePsk("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=")
    private val ie = hex("1111111111111111111111111111111111111111111111111111111111111140")
    private val re = hex("2222222222222222222222222222222222222222222222222222222222222241")
    private val m1 = hex("4acfbdccb9b23bda220b8e32417247ad507abe43b8620cae2b6728eaa8fdfc1ec9cc35376d4f0d34c004d168743b1b08")
    private val m2 = hex("586f9df7f3b33210803d042e76d7c21ec033450a86a1e2dac239abef7285ab7cd04e80aba7dd7696453595c0963258d7")
    private val t1 = hex("df35697250bad411e716e3f191d61c409e638c2bc63b6277ac64c337")
    private val t2 = hex("cd09402e8a7fe2f40e8b9b5f82dad00379493a61ba69c4ec29079c9e")
    private val t3 = hex("d7bacea6d1260cebbe36f69bef3bb4aea4f63f5fed")

    @Test
    fun matchesReferenceVector() {
        val i = NoiseHandshake(initiator = true, psk = psk, ephemeralPrivate = ie)
        val r = NoiseHandshake(initiator = false, psk = psk, ephemeralPrivate = re)
        assertArrayEquals(m1, i.writeMessage())
        r.readMessage(m1)
        assertArrayEquals(m2, r.writeMessage())
        i.readMessage(m2)
        assertArrayEquals(t1, i.encryptor!!.encrypt(ByteArray(0), "hello server".toByteArray()))
        assertArrayEquals(t2, r.encryptor!!.encrypt(ByteArray(0), "hello client".toByteArray()))
        assertArrayEquals(t3, r.encryptor!!.encrypt(ByteArray(0), "again".toByteArray()))
    }

    @Test
    fun responderDecryptsReferenceTraffic() {
        val r = NoiseHandshake(initiator = false, psk = psk, ephemeralPrivate = re)
        r.readMessage(m1)
        r.writeMessage()
        assertEquals("hello server", String(r.decryptor!!.decrypt(ByteArray(0), t1)))
    }

    @Test
    fun wrongKeyFailsOnTheResponder() {
        val other = ByteArray(32) { 7 }
        val r = NoiseHandshake(initiator = false, psk = other, ephemeralPrivate = re)
        assertThrows(NoiseException::class.java) { r.readMessage(m1) }
    }

    @Test
    fun randomEphemeralsAgree() {
        val i = NoiseHandshake(initiator = true, psk = psk)
        val r = NoiseHandshake(initiator = false, psk = psk)
        r.readMessage(i.writeMessage())
        i.readMessage(r.writeMessage())
        repeat(3) {
            val c = i.encryptor!!.encrypt(ByteArray(0), byteArrayOf(it.toByte()))
            assertArrayEquals(byteArrayOf(it.toByte()), r.decryptor!!.decrypt(ByteArray(0), c))
        }
    }

    @Test
    fun pskDecoding() {
        assertEquals(32, Noise.decodePsk(Noise.generatePsk()).size)
        assertThrows(IllegalArgumentException::class.java) { Noise.decodePsk("c2hvcnQ=") }
        assertThrows(IllegalArgumentException::class.java) { Noise.decodePsk("not base64!") }
    }

    companion object {
        fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    }
}
