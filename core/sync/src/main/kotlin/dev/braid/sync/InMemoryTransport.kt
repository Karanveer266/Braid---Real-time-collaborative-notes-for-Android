package dev.braid.sync

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import java.io.EOFException
import kotlin.random.Random

/**
 * In-process duplex pipe. Used by tests and by the in-app "ghost collaborator" demo, which runs
 * a second replica inside the app and syncs it through the *real* handshake, encryption and
 * protocol code, only the socket is replaced.
 *
 * Supports injected latency so race conditions in the protocol are exercised in tests.
 */
class InMemoryLink private constructor(
    private val inbox: Channel<ByteArray>,
    private val outbox: Channel<ByteArray>,
    override val remoteLabel: String,
    private val latency: () -> Long,
) : FrameConnection {

    override suspend fun send(frame: ByteArray) {
        val d = latency()
        if (d > 0) delay(d)
        try {
            outbox.send(frame.copyOf())
        } catch (e: Exception) {
            throw EOFException("link closed")
        }
    }

    override suspend fun receive(): ByteArray = inbox.receiveCatching().getOrNull() ?: throw EOFException("link closed")

    override fun close() {
        inbox.close()
        outbox.close()
    }

    companion object {
        fun pair(
            labelA: String = "a",
            labelB: String = "b",
            maxLatencyMillis: Long = 0,
            random: Random = Random.Default,
        ): Pair<FrameConnection, FrameConnection> {
            val ab = Channel<ByteArray>(Channel.UNLIMITED)
            val ba = Channel<ByteArray>(Channel.UNLIMITED)
            val lat = { if (maxLatencyMillis <= 0) 0L else random.nextLong(0, maxLatencyMillis + 1) }
            return InMemoryLink(ba, ab, labelB, lat) to InMemoryLink(ab, ba, labelA, lat)
        }
    }
}
