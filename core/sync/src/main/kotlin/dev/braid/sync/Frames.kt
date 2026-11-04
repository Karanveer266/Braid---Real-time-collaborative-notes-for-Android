package dev.braid.sync

/**
 * A bidirectional, message-oriented, ordered byte channel. Everything above this interface
 * (handshake, encryption, sync protocol) is transport-agnostic, so the same code runs over TCP
 * on a LAN, an in-memory pipe in tests, or (future) Bluetooth / Wi-Fi Aware / a relay server.
 */
interface FrameConnection {
    /** Human-readable endpoint for logs/UI, e.g. "192.168.1.20:41234". */
    val remoteLabel: String

    suspend fun send(frame: ByteArray)

    /** Suspends until the next frame. Throws [java.io.EOFException] when the peer closed. */
    suspend fun receive(): ByteArray

    fun close()
}

object FrameLimits {
    /** Upper bound protects against memory exhaustion from a malicious or buggy peer. */
    const val MAX_FRAME_BYTES: Int = 4 * 1024 * 1024
}
