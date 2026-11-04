package dev.braid.sync

import dev.braid.crdt.ClockDriftException
import dev.braid.crdt.ReplicaId
import dev.braid.crdt.VersionVector
import dev.braid.sync.crypto.NoiseException
import dev.braid.sync.crypto.SecureChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.EOFException
import java.io.IOException

/** Session-level state, surfaced in the UI peer list. */
sealed interface SessionState {
    data object Handshaking : SessionState
    data class Syncing(val peer: PeerInfo) : SessionState
    data class Live(val peer: PeerInfo, val sent: Long, val received: Long) : SessionState
    data class Closed(val peer: PeerInfo?, val reason: CloseReason) : SessionState
}

data class PeerInfo(val replica: ReplicaId, val displayName: String, val endpoint: String)

enum class CloseReason { NORMAL, AUTH_FAILED, VERSION_MISMATCH, CLOCK_DRIFT, IO_ERROR, PROTOCOL_ERROR, SELF }

/**
 * Runs the sync protocol over one authenticated connection until it closes.
 *
 * ## Concurrency model
 * Two coroutines per session:
 *  - **reader**: decodes incoming messages, integrates ops, updates the remote vector.
 *  - **writer**: whenever local state may have changed ([SyncStore.commits], conflated) or the
 *    remote vector was learned, sends `opsMissingFrom(remoteKnown)` and advances `remoteKnown`.
 *
 * `remoteKnown` is our *optimistic* view of what the peer has. It is advanced when we send
 * (TCP + Noise give ordered, reliable delivery within a session) and merged with every Summary
 * and every op batch the peer sends us (they obviously have their own ops). If the connection
 * drops, the next session starts from a fresh Summary exchange, so optimism can never lose data.
 *
 * Ops are *never* sent before they are durable in the local store, so a crash can't cause a
 * peer to know an op that we later "forget" and then re-issue with the same sequence number.
 */
class SyncSession(
    private val store: SyncStore,
    private val localName: String,
    private val onPresence: (ReplicaId, WireMessage.Presence) -> Unit = { _, _ -> },
) {
    private val _state = MutableStateFlow<SessionState>(SessionState.Handshaking)
    val state: StateFlow<SessionState> = _state

    private val outgoingPresence = Channel<WireMessage.Presence>(Channel.CONFLATED)

    @Volatile
    var peer: PeerInfo? = null
        private set

    fun sendPresence(p: WireMessage.Presence) {
        outgoingPresence.trySend(p)
    }

    /**
     * Runs the full lifecycle. Returns (never throws for expected network/auth failures) when
     * the session ends; the final state says why.
     */
    suspend fun run(conn: FrameConnection, initiator: Boolean, psk: ByteArray, prologue: ByteArray) {
        val hello = Hello(PROTOCOL_VERSION, store.localReplica, localName)
        val channel = try {
            SecureChannel.establish(conn, initiator, psk, prologue, WireCodec.encodeHello(hello))
        } catch (e: CancellationException) {
            conn.close(); throw e
        } catch (e: NoiseException) {
            conn.close(); _state.value = SessionState.Closed(null, CloseReason.AUTH_FAILED); return
        } catch (e: Exception) {
            conn.close(); _state.value = SessionState.Closed(null, CloseReason.IO_ERROR); return
        }

        val remoteHello = runCatching { WireCodec.decodeHello(channel.remotePayload) }.getOrNull()
        val info = PeerInfo(remoteHello?.replica ?: ReplicaId(0), remoteHello?.displayName.orEmpty(), conn.remoteLabel)
        peer = info
        val reason = when {
            remoteHello == null -> CloseReason.PROTOCOL_ERROR
            remoteHello.protocolVersion != PROTOCOL_VERSION -> CloseReason.VERSION_MISMATCH
            remoteHello.replica == store.localReplica -> CloseReason.SELF
            else -> null
        }
        if (reason != null) {
            channel.close(); _state.value = SessionState.Closed(info, reason); return
        }

        _state.value = SessionState.Syncing(info)
        val closeReason = try {
            exchange(channel, info)
            CloseReason.NORMAL
        } catch (e: CancellationException) {
            runCatching { channel.close() }
            _state.value = SessionState.Closed(info, CloseReason.NORMAL)
            throw e
        } catch (e: ClockDriftException) {
            CloseReason.CLOCK_DRIFT
        } catch (e: NoiseException) {
            CloseReason.AUTH_FAILED
        } catch (e: EOFException) {
            CloseReason.NORMAL
        } catch (e: IOException) {
            CloseReason.IO_ERROR
        } catch (e: Exception) {
            CloseReason.PROTOCOL_ERROR
        }
        channel.close()
        _state.value = SessionState.Closed(info, closeReason)
    }

    private suspend fun exchange(channel: SecureChannel, info: PeerInfo) = coroutineScope {
        var sent = 0L
        var received = 0L
        val remoteKnown = MutableStateFlow<VersionVector?>(null)
        val wake = Channel<Unit>(Channel.CONFLATED)

        channel.send(WireCodec.encode(WireMessage.Summary(store.version())))

        val writer = launch {
            launch { store.commits.conflateFlow().collect { wake.trySend(Unit) } }
            launch {
                for (p in outgoingPresence) channel.send(WireCodec.encode(p))
            }
            for (tick in wake) {
                val known = remoteKnown.value ?: continue
                val delta = store.opsMissingFrom(known)
                if (delta.isEmpty()) continue
                for (batch in delta.chunked(MAX_OPS_PER_MESSAGE)) {
                    channel.send(WireCodec.encode(WireMessage.Ops(batch)))
                    sent += batch.size
                }
                // update{} (not assignment): the reader may have merged a newer Summary meanwhile.
                remoteKnown.update { cur -> delta.fold(cur ?: known) { v, op -> v.with(op.author, op.seq) } }
                _state.value = SessionState.Live(info, sent, received)
            }
        }

        try {
            while (true) {
                when (val msg = WireCodec.decode(channel.receive())) {
                    is WireMessage.Summary -> {
                        remoteKnown.update { cur -> (cur ?: VersionVector.EMPTY).merge(msg.version) }
                        wake.trySend(Unit)
                        _state.value = SessionState.Live(info, sent, received)
                    }
                    is WireMessage.Ops -> {
                        // Record first that the peer has these ops, so the commit triggered by
                        // integrating them does not make the writer echo them straight back.
                        remoteKnown.update { cur -> msg.ops.fold(cur ?: VersionVector.EMPTY) { v, op -> v.with(op.author, op.seq) } }
                        store.integrateRemote(msg.ops)
                        received += msg.ops.size
                        _state.value = SessionState.Live(info, sent, received)
                    }
                    is WireMessage.Presence -> onPresence(info.replica, msg)
                    is WireMessage.Bye -> break
                }
            }
        } finally {
            writer.cancel()
        }
    }

    private fun Flow<Unit>.conflateFlow(): Flow<Unit> = conflate()

    companion object {
        /** Bounded batches keep each encrypted message well under the 32 MiB cap and stream nicely. */
        const val MAX_OPS_PER_MESSAGE = 2_000
    }
}

/** Builds the Noise prologue binding a session to one workspace and protocol version. */
fun syncPrologue(workspaceId: String): ByteArray = "braid/v$PROTOCOL_VERSION/$workspaceId".toByteArray(Charsets.UTF_8)
