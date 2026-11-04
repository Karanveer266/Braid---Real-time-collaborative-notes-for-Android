package dev.braid.sync.crypto

import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.math.ec.rfc7748.X25519
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Minimal, spec-exact implementation of the Noise Protocol Framework (revision 34) for exactly
 * one pattern: `Noise_NNpsk0_25519_ChaChaPoly_SHA256`.
 *
 * ```
 * NNpsk0:
 *   -> psk, e
 *   <- e, ee
 * ```
 *
 * Why this pattern: the workspace key exchanged via QR code is a 32-byte pre-shared key (PSK).
 *  - psk0 → both sides prove knowledge of the workspace key in the first round trip; a device
 *    without it cannot complete the handshake or even learn the payload of message 1.
 *  - e/ee → fresh ephemeral X25519 keys per connection give forward secrecy: leaking the PSK
 *    later does not decrypt recorded traffic.
 *  - No static keys → no long-term identity keys to manage, rotate or back up.
 *
 * Why Noise instead of TLS: TLS needs certificates or TLS-PSK, which Android's platform TLS
 * stack does not expose. Noise is ~250 lines, has no negotiation (no downgrade attacks) and is
 * the same framework used by WireGuard and WhatsApp. Correctness is verified against the
 * official cacophony test vectors in `NoiseVectorTest`.
 *
 * Primitives: BouncyCastle lightweight API for X25519 and ChaCha20-Poly1305 (constant-time,
 * available on every API level, unlike the platform providers), JCA for SHA-256/HMAC.
 */
object Noise {
    const val PROTOCOL_NAME = "Noise_NNpsk0_25519_ChaChaPoly_SHA256"
    const val DH_LEN = 32
    const val HASH_LEN = 32
    const val TAG_LEN = 16
    const val MAX_MESSAGE = 65535
    const val MAX_PLAINTEXT = MAX_MESSAGE - TAG_LEN
}

class NoiseException(message: String, cause: Throwable? = null) : GeneralSecurityException(message, cause)

/** Noise CipherState: a key plus a 64-bit nonce counter. */
class CipherState internal constructor(private var k: ByteArray?) {
    private var n: Long = 0

    val hasKey: Boolean get() = k != null

    internal fun initializeKey(key: ByteArray?) {
        k = key?.copyOf()
        n = 0
    }

    fun encryptWithAd(ad: ByteArray, plaintext: ByteArray): ByteArray {
        val key = k ?: return plaintext
        val out = aead(true, key, nextNonce(), ad, plaintext)
        return out
    }

    fun decryptWithAd(ad: ByteArray, ciphertext: ByteArray): ByteArray {
        val key = k ?: return ciphertext
        val nonce = n
        val out = aead(false, key, nonce, ad, ciphertext)
        n = nonce + 1 // only advance after successful authentication
        return out
    }

    private fun nextNonce(): Long {
        // 2^64-1 is reserved by the spec; unreachable in practice but enforce it anyway.
        if (n == -1L) throw NoiseException("nonce exhausted")
        return n++
    }

    private fun aead(encrypt: Boolean, key: ByteArray, nonce: Long, ad: ByteArray, input: ByteArray): ByteArray {
        val iv = ByteArray(12)
        for (i in 0 until 8) iv[4 + i] = (nonce ushr (8 * i)).toByte() // 32 zero bits + LE64
        val cipher = ChaCha20Poly1305()
        cipher.init(encrypt, AEADParameters(KeyParameter(key), Noise.TAG_LEN * 8, iv, ad))
        val out = ByteArray(cipher.getOutputSize(input.size))
        return try {
            var len = cipher.processBytes(input, 0, input.size, out, 0)
            len += cipher.doFinal(out, len)
            if (len == out.size) out else out.copyOf(len)
        } catch (e: InvalidCipherTextException) {
            throw NoiseException("authentication failed", e)
        }
    }
}

/** Noise SymmetricState (chaining key + handshake hash). */
internal class SymmetricState(protocolName: String) {
    private var ck: ByteArray
    var h: ByteArray
        private set
    val cipher = CipherState(null)

    init {
        val name = protocolName.toByteArray(Charsets.US_ASCII)
        h = if (name.size <= Noise.HASH_LEN) name.copyOf(Noise.HASH_LEN) else sha256(name)
        ck = h.copyOf()
    }

    fun mixKey(ikm: ByteArray) {
        val (newCk, tempK) = hkdf(ck, ikm, 2)
        ck = newCk
        cipher.initializeKey(tempK)
    }

    fun mixHash(data: ByteArray) {
        h = sha256(h, data)
    }

    fun mixKeyAndHash(ikm: ByteArray) {
        val (newCk, tempH, tempK) = hkdf(ck, ikm, 3)
        ck = newCk
        mixHash(tempH)
        cipher.initializeKey(tempK)
    }

    fun encryptAndHash(plaintext: ByteArray): ByteArray =
        cipher.encryptWithAd(h, plaintext).also { mixHash(it) }

    fun decryptAndHash(ciphertext: ByteArray): ByteArray =
        cipher.decryptWithAd(h, ciphertext).also { mixHash(ciphertext) }

    fun split(): Pair<CipherState, CipherState> {
        val (k1, k2) = hkdf(ck, ByteArray(0), 2)
        ck.fill(0)
        return CipherState(k1) to CipherState(k2)
    }

    companion object {
        fun sha256(vararg parts: ByteArray): ByteArray {
            val md = MessageDigest.getInstance("SHA-256")
            parts.forEach(md::update)
            return md.digest()
        }

        private fun hmac(key: ByteArray, vararg data: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            data.forEach(mac::update)
            return mac.doFinal()
        }

        fun hkdf(chainingKey: ByteArray, ikm: ByteArray, outputs: Int): List<ByteArray> {
            val temp = hmac(chainingKey, ikm)
            val out1 = hmac(temp, byteArrayOf(1))
            val out2 = hmac(temp, out1, byteArrayOf(2))
            if (outputs == 2) return listOf(out1, out2)
            val out3 = hmac(temp, out2, byteArrayOf(3))
            return listOf(out1, out2, out3)
        }
    }
}

/**
 * One side of an NNpsk0 handshake. Usage: initiator `writeMessage` → responder `readMessage` →
 * responder `writeMessage` → initiator `readMessage`, then [split] for transport ciphers.
 *
 * Handshake payloads are encrypted (the PSK keys the cipher from the first token), so they are
 * used to carry the protocol version and replica id confidentially.
 */
class NnPsk0Handshake(
    val initiator: Boolean,
    psk: ByteArray,
    prologue: ByteArray,
    private val ephemeralPrivate: ByteArray = randomPrivateKey(),
) {
    private val ss = SymmetricState(Noise.PROTOCOL_NAME)
    private val psk = psk.copyOf()
    private val ePub = ByteArray(Noise.DH_LEN).also { X25519.generatePublicKey(ephemeralPrivate, 0, it, 0) }
    private var remoteE: ByteArray? = null
    private var step = 0

    init {
        require(psk.size == 32) { "PSK must be 32 bytes" }
        require(ephemeralPrivate.size == Noise.DH_LEN)
        ss.mixHash(prologue)
    }

    val isComplete: Boolean get() = step == 2
    val handshakeHash: ByteArray get() = ss.h.copyOf()

    fun writeMessage(payload: ByteArray): ByteArray {
        check(isMyTurn()) { "not our turn to write" }
        val out = java.io.ByteArrayOutputStream()
        if (step == 0) {
            ss.mixKeyAndHash(psk)
            writeE(out)
        } else {
            writeE(out)
            ss.mixKey(dh(ephemeralPrivate, remoteE!!))
        }
        out.write(ss.encryptAndHash(payload))
        step++
        return out.toByteArray()
    }

    fun readMessage(message: ByteArray): ByteArray {
        check(!isMyTurn()) { "not our turn to read" }
        if (message.size < Noise.DH_LEN + Noise.TAG_LEN) throw NoiseException("handshake message too short")
        val re = message.copyOfRange(0, Noise.DH_LEN)
        if (step == 0) ss.mixKeyAndHash(psk)
        remoteE = re
        ss.mixHash(re)
        ss.mixKey(re) // psk modes: e token also calls MixKey
        if (step == 1) ss.mixKey(dh(ephemeralPrivate, re))
        val payload = ss.decryptAndHash(message.copyOfRange(Noise.DH_LEN, message.size))
        step++
        return payload
    }

    /** Returns (sender, receiver) cipher states for *this* side. */
    fun split(): Pair<CipherState, CipherState> {
        check(isComplete) { "handshake not complete" }
        val (c1, c2) = ss.split()
        psk.fill(0)
        ephemeralPrivate.fill(0)
        return if (initiator) c1 to c2 else c2 to c1
    }

    private fun writeE(out: java.io.ByteArrayOutputStream) {
        out.write(ePub)
        ss.mixHash(ePub)
        ss.mixKey(ePub)
    }

    private fun isMyTurn(): Boolean = (step == 0 && initiator) || (step == 1 && !initiator)

    private fun dh(priv: ByteArray, pub: ByteArray): ByteArray {
        val shared = ByteArray(32)
        // X25519.calculateAgreement returns false for low-order points (all-zero output).
        if (!X25519.calculateAgreement(priv, 0, pub, 0, shared, 0)) throw NoiseException("invalid remote public key")
        return shared
    }

    companion object {
        private val random = SecureRandom()
        fun randomPrivateKey(): ByteArray = ByteArray(Noise.DH_LEN).also { X25519.generatePrivateKey(random, it) }
    }
}
