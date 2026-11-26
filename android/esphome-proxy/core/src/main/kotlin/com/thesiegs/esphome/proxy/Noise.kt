package com.thesiegs.esphome.proxy

import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.math.ec.rfc7748.X25519
import java.security.SecureRandom
import java.util.Base64

/**
 * Noise_NNpsk0_25519_ChaChaPoly_SHA256, the only protocol ESPHome's encrypted API speaks
 * (Noise spec rev 34, sections 5 and 9; pattern `-> psk, e` / `<- e, ee`). Written against the
 * spec and checked byte for byte against the noiseprotocol library aioesphomeapi uses
 * (NoiseTest). The primitives come from Bouncy Castle's lightweight API, which works the same on
 * every Android version, including Android 11 whose platform has no X25519.
 */
object Noise {
    const val PROTOCOL_NAME = "Noise_NNpsk0_25519_ChaChaPoly_SHA256"
    /** ESPHome's prologue (api_frame_helper_noise.cpp): "NoiseAPIInit" and two zero bytes. */
    val ESPHOME_PROLOGUE = "NoiseAPIInit\u0000\u0000".toByteArray(Charsets.US_ASCII)
    const val DH_LEN = 32
    const val TAG_LEN = 16

    /** Decodes ESPHome's `api: encryption: key:`, a base64 32-byte key. */
    fun decodePsk(base64: String): ByteArray {
        val key = try { Base64.getDecoder().decode(base64.trim()) } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("encryption key is not base64", e)
        }
        require(key.size == 32) { "encryption key must be 32 bytes, got ${key.size}" }
        return key
    }

    /** A fresh random key in the same base64 form, for a host that has none yet. */
    fun generatePsk(random: SecureRandom = SecureRandom()): String =
        Base64.getEncoder().encodeToString(ByteArray(32).also { random.nextBytes(it) })
}

/** A Noise CipherState: ChaCha20-Poly1305 with a 64-bit little-endian counter nonce. */
class CipherState(private val key: ByteArray) {
    private var nonce = 0L

    fun encrypt(ad: ByteArray, plaintext: ByteArray): ByteArray = run(true, ad, plaintext)

    /** Throws [NoiseException] when the tag doesn't verify (wrong key or tampered data). */
    fun decrypt(ad: ByteArray, ciphertext: ByteArray): ByteArray = run(false, ad, ciphertext)

    private fun run(encrypt: Boolean, ad: ByteArray, input: ByteArray): ByteArray {
        if (nonce == -1L) throw NoiseException("nonce exhausted")
        val iv = ByteArray(12)
        for (i in 0 until 8) iv[4 + i] = (nonce ushr (8 * i)).toByte()
        val aead = ChaCha20Poly1305()
        aead.init(encrypt, AEADParameters(KeyParameter(key), 128, iv, ad))
        val out = ByteArray(aead.getOutputSize(input.size))
        try {
            val n = aead.processBytes(input, 0, input.size, out, 0)
            aead.doFinal(out, n)
        } catch (e: InvalidCipherTextException) {
            throw NoiseException("MAC failure")
        }
        nonce++
        return out
    }
}

/** Noise's SymmetricState for SHA-256. */
private class SymmetricState(protocolName: ByteArray) {
    var ck: ByteArray
    var h: ByteArray
    private var k: CipherState? = null

    init {
        h = if (protocolName.size <= 32) protocolName.copyOf(32) else sha256(protocolName)
        ck = h.copyOf()
    }

    fun mixHash(data: ByteArray) { h = sha256(h, data) }

    fun mixKey(ikm: ByteArray) {
        val (newCk, tempK) = hkdf(ck, ikm, 2)
        ck = newCk; k = CipherState(tempK)
    }

    fun mixKeyAndHash(ikm: ByteArray) {
        val (newCk, tempH, tempK) = hkdf(ck, ikm, 3)
        ck = newCk; mixHash(tempH); k = CipherState(tempK)
    }

    fun encryptAndHash(plaintext: ByteArray): ByteArray {
        val c = k?.encrypt(h, plaintext) ?: plaintext
        mixHash(c)
        return c
    }

    fun decryptAndHash(ciphertext: ByteArray): ByteArray {
        val p = k?.decrypt(h, ciphertext) ?: ciphertext
        mixHash(ciphertext)
        return p
    }

    fun split(): Pair<CipherState, CipherState> {
        val (k1, k2) = hkdf(ck, ByteArray(0), 2)
        return CipherState(k1) to CipherState(k2)
    }

    companion object {
        fun sha256(vararg parts: ByteArray): ByteArray {
            val d = SHA256Digest()
            for (p in parts) d.update(p, 0, p.size)
            return ByteArray(32).also { d.doFinal(it, 0) }
        }

        fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
            val m = HMac(SHA256Digest())
            m.init(KeyParameter(key))
            for (p in parts) m.update(p, 0, p.size)
            return ByteArray(32).also { m.doFinal(it, 0) }
        }

        fun hkdf(ck: ByteArray, ikm: ByteArray, outputs: Int): List<ByteArray> {
            val temp = hmac(ck, ikm)
            val o1 = hmac(temp, byteArrayOf(1))
            val o2 = hmac(temp, o1, byteArrayOf(2))
            return if (outputs == 2) listOf(o1, o2) else listOf(o1, o2, hmac(temp, o2, byteArrayOf(3)))
        }
    }
}

/**
 * One side of the NNpsk0 handshake. The responder (the proxy) reads message 1 and writes message 2;
 * the initiator (used by tests and the interop client) does the reverse. Both payloads are empty,
 * as in ESPHome. After the second message, [encryptor] and [decryptor] carry the session.
 */
class NoiseHandshake(
    private val initiator: Boolean,
    psk: ByteArray,
    prologue: ByteArray = Noise.ESPHOME_PROLOGUE,
    ephemeralPrivate: ByteArray? = null,
    random: SecureRandom = SecureRandom(),
) {
    private val ss = SymmetricState(Noise.PROTOCOL_NAME.toByteArray(Charsets.US_ASCII))
    private val ePriv: ByteArray = ephemeralPrivate?.copyOf() ?: ByteArray(32).also { X25519.generatePrivateKey(random, it) }
    private val ePub = ByteArray(32).also { X25519.scalarMultBase(ePriv, 0, it, 0) }
    private var remoteE: ByteArray? = null
    private var step = 0

    var encryptor: CipherState? = null; private set
    var decryptor: CipherState? = null; private set
    val finished get() = encryptor != null

    init {
        require(psk.size == 32)
        ss.mixHash(prologue)
        // psk0: the PSK token comes first in the initiator's message.
        ss.mixKeyAndHash(psk)
    }

    /** Initiator: message 1. Responder: message 2 (after [readMessage] of message 1). */
    fun writeMessage(payload: ByteArray = ByteArray(0)): ByteArray {
        check(step == if (initiator) 0 else 1) { "out of turn" }
        ss.mixHash(ePub)
        ss.mixKey(ePub) // psk modes mix e into the key too
        if (!initiator) ss.mixKey(dh(ePriv, remoteE!!))
        val c = ss.encryptAndHash(payload)
        step++
        if (!initiator) finish()
        return ePub + c
    }

    /** Responder: message 1. Initiator: message 2. Throws [NoiseException] on a bad PSK. */
    fun readMessage(message: ByteArray): ByteArray {
        check(step == if (initiator) 1 else 0) { "out of turn" }
        if (message.size < Noise.DH_LEN + Noise.TAG_LEN) throw NoiseException("handshake message too short")
        val re = message.copyOfRange(0, Noise.DH_LEN)
        remoteE = re
        ss.mixHash(re)
        ss.mixKey(re)
        if (initiator) ss.mixKey(dh(ePriv, re))
        val p = ss.decryptAndHash(message.copyOfRange(Noise.DH_LEN, message.size))
        step++
        if (initiator) finish()
        return p
    }

    private fun finish() {
        val (c1, c2) = ss.split()
        // c1 carries initiator -> responder traffic.
        if (initiator) { encryptor = c1; decryptor = c2 } else { encryptor = c2; decryptor = c1 }
    }

    private fun dh(priv: ByteArray, pub: ByteArray): ByteArray {
        val out = ByteArray(32)
        if (!X25519.calculateAgreement(priv, 0, pub, 0, out, 0)) throw NoiseException("bad public key")
        return out
    }
}

class NoiseException(message: String) : Exception(message)
