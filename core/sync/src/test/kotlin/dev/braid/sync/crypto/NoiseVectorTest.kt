package dev.braid.sync.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

/**
 * Official test vector for Noise_NNpsk0_25519_ChaChaPoly_SHA256 from the cacophony suite
 * (https://github.com/haskell-cryptography/cacophony, vectors/cacophony.txt). If any byte of the
 * key schedule were wrong, the ciphertexts below would not match.
 */
class NoiseVectorTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private val prologue = hex("4a6f686e2047616c74")
    private val psk = hex("54686973206973206d7920417573747269616e20706572737065637469766521")
    private val initE = hex("893e28b9dc6ca8d611ab664754b8ceb7bac5117349a4439a6b0569da977c464a")
    private val respE = hex("bbdb4cdbd309f1a1f2e1456967fe288cadd6f712d65dc7b7793d5e63da6b375b")
    private val handshakeHash = hex("f4d03dc34495c95729ea6de9e1b59004b59733102488b3e24bc441e0be208eaf")
    private val messages = listOf(
        "4c756477696720766f6e204d69736573" to "ca35def5ae56cec33dc2036731ab14896bc4c75dbb07a61f879f8e3afa4c794479b962b8aff8485742ac32f905ba45369e2465fb59e138a93d67a0d1266b6a54",
        "4d757272617920526f746862617264" to "95ebc60d2b1fa672c1f46a8aa265ef51bfe38e7ccb39ec5be34069f144808843d6062704d5a9c422a8e834423f8c1feada7e8d0d910a1a2cd030fb584221e3",
        "462e20412e20486179656b" to "e632c3763d7669067383433197a3baddf146e9e70ad4b4e9e59e0f",
        "4361726c204d656e676572" to "64c6bee32ea91c8474bb4c21d7a700109ad45af77b29764ba5eb1e",
        "4a65616e2d426170746973746520536179" to "e2fa0bed0603b62d3ccac2ecabbf3fe33f3e86514909b323361626266cb2471cc8",
        "457567656e2042f6686d20766f6e2042617765726b" to "0c01dc9cec1fe4ddd692e8dd32188aa351088dc91183639a53b57aa4692b5ebdef8b8ca111",
    ).map { (p, c) -> hex(p) to hex(c) }

    @Test
    fun `matches cacophony vector byte for byte`() {
        val init = NnPsk0Handshake(true, psk, prologue, initE.copyOf())
        val resp = NnPsk0Handshake(false, psk, prologue, respE.copyOf())

        val m0 = init.writeMessage(messages[0].first)
        assertContentEquals(messages[0].second, m0)
        assertContentEquals(messages[0].first, resp.readMessage(m0))

        val m1 = resp.writeMessage(messages[1].first)
        assertContentEquals(messages[1].second, m1)
        assertContentEquals(messages[1].first, init.readMessage(m1))

        assertContentEquals(handshakeHash, init.handshakeHash)
        assertContentEquals(handshakeHash, resp.handshakeHash)

        val (iSend, iRecv) = init.split()
        val (rSend, rRecv) = resp.split()
        messages.drop(2).forEachIndexed { i, (pt, ct) ->
            val fromInitiator = i % 2 == 0
            val (enc, dec) = if (fromInitiator) iSend to rRecv else rSend to iRecv
            val c = enc.encryptWithAd(ByteArray(0), pt)
            assertContentEquals(ct, c)
            assertContentEquals(pt, dec.decryptWithAd(ByteArray(0), c))
        }
    }

    @Test
    fun `wrong psk fails on the first message`() {
        val init = NnPsk0Handshake(true, psk, prologue)
        val wrong = psk.copyOf().also { it[0] = (it[0] + 1).toByte() }
        val resp = NnPsk0Handshake(false, wrong, prologue)
        assertFailsWith<NoiseException> { resp.readMessage(init.writeMessage(ByteArray(0))) }
    }

    @Test
    fun `different prologue (workspace binding) fails`() {
        val init = NnPsk0Handshake(true, psk, prologue)
        val resp = NnPsk0Handshake(false, psk, hex("00"))
        assertFailsWith<NoiseException> { resp.readMessage(init.writeMessage(ByteArray(0))) }
    }

    @Test
    fun `tampered transport message is rejected`() {
        val init = NnPsk0Handshake(true, psk, prologue)
        val resp = NnPsk0Handshake(false, psk, prologue)
        resp.readMessage(init.writeMessage(ByteArray(0)))
        init.readMessage(resp.writeMessage(ByteArray(0)))
        val (send, _) = init.split()
        val (_, recv) = resp.split()
        val ct = send.encryptWithAd(ByteArray(0), "hello".toByteArray())
        ct[2] = (ct[2].toInt() xor 1).toByte()
        assertFailsWith<NoiseException> { recv.decryptWithAd(ByteArray(0), ct) }
    }
}
