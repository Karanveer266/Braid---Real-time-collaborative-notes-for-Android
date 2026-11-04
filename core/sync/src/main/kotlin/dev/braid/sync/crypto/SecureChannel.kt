package dev.braid.sync.crypto

import dev.braid.sync.FrameConnection
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * An authenticated, encrypted, message-oriented channel on top of any [FrameConnection].
 *
 * 1. Runs the Noise NNpsk0 handshake. The [prologue] binds the session to the workspace id and
 *    protocol version, so a peer from another workspace, or speaking another version, fails
 *    authentication instead of being misinterpreted.
 * 2. Encrypts each logical message with ChaCha20-Poly1305. Noise messages are capped at 64 KiB,
 *    so larger messages are fragmented: every Noise plaintext starts with a 1-byte flag
 *    (1 = more fragments follow, 0 = last fragment).
 *
 * Replay/reorder protection comes for free from the Noise nonce counter: a replayed or reordered
 * frame fails authentication and kills the session.
 */
class SecureChannel private constructor(
    private val conn: FrameConnection,
    private val sender: CipherState,
    private val receiver: CipherState,
    val remotePayload: ByteArray,
) {
    private val sendLock = Mutex()
    private val recvLock = Mutex()

    val remoteLabel: String get() = conn.remoteLabel

    suspend fun send(message: ByteArray) = sendLock.withLock {
        var offset = 0
        do {
            val chunk = minOf(MAX_FRAGMENT, message.size - offset)
            val more = offset + chunk < message.size
            val plain = ByteArray(chunk + 1)
            plain[0] = if (more) 1 else 0
            System.arraycopy(message, offset, plain, 1, chunk)
            conn.send(sender.encryptWithAd(EMPTY, plain))
            offset += chunk
        } while (more)
    }

    suspend fun receive(): ByteArray = recvLock.withLock {
        val acc = ByteArrayOutputStream()
        while (true) {
            val plain = receiver.decryptWithAd(EMPTY, conn.receive())
            if (plain.isEmpty()) throw IOException("empty fragment")
            acc.write(plain, 1, plain.size - 1)
            if (acc.size() > MAX_MESSAGE) throw IOException("message too large")
            if (plain[0].toInt() == 0) return acc.toByteArray()
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    fun close() = conn.close()

    companion object {
        private val EMPTY = ByteArray(0)
        private const val MAX_FRAGMENT = Noise.MAX_PLAINTEXT - 1
        private const val MAX_MESSAGE = 32 * 1024 * 1024
        const val HANDSHAKE_TIMEOUT_MILLIS = 10_000L

        /**
         * Performs the handshake. [localPayload] (e.g. the Hello message) is sent encrypted;
         * the peer's payload is available as [remotePayload].
         * @throws NoiseException if the peer does not know the workspace key.
         */
        suspend fun establish(
            conn: FrameConnection,
            initiator: Boolean,
            psk: ByteArray,
            prologue: ByteArray,
            localPayload: ByteArray,
        ): SecureChannel = withTimeout(HANDSHAKE_TIMEOUT_MILLIS) {
            val hs = NnPsk0Handshake(initiator, psk, prologue)
            val remote: ByteArray
            if (initiator) {
                conn.send(hs.writeMessage(localPayload))
                remote = hs.readMessage(conn.receive())
            } else {
                remote = hs.readMessage(conn.receive())
                conn.send(hs.writeMessage(localPayload))
            }
            val (s, r) = hs.split()
            SecureChannel(conn, s, r, remote)
        }
    }
}
