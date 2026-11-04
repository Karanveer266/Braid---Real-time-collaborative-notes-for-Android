package dev.braid.sync

import dev.braid.crdt.ReplicaId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.random.Random

/** A peer seen by discovery, before any connection. */
data class DiscoveredPeer(val replica: ReplicaId, val address: String)

/** Platform hooks. Android implements them with NsdManager + sockets; tests use in-memory fakes. */
interface MeshTransport {
    /** Peers currently advertised in the same workspace (excluding ourselves). */
    val discovered: Flow<Set<DiscoveredPeer>>

    /** Incoming connections; each is handed to [PeerMesh] which runs a responder session. */
    val incoming: Flow<FrameConnection>

    suspend fun dial(peer: DiscoveredPeer): FrameConnection
}

data class MeshPeer(val replica: ReplicaId, val state: SessionState, val attempts: Int = 0)

/**
 * Keeps one live [SyncSession] per discovered peer, with automatic reconnection.
 *
 * ## Who dials whom
 * Both devices discover each other at roughly the same time. If both dialed, we'd get two
 * sessions per pair. The rule "the smaller replica id dials" (both sides know both ids from the
 * advertisement) decides without any coordination. The larger id dials only as a delayed
 * fallback (asymmetric mDNS). Duplicates that still occur are harmless, because the protocol is
 * idempotent, and are resolved deterministically in [register].
 *
 * ## Backoff
 * Failed dials retry with exponential backoff (500 ms → 30 s) with full jitter, so a flapping
 * Wi-Fi network does not drain the battery, and several devices don't retry in lockstep.
 * Authentication failures are *not* retried quickly: they indicate a different key, not a blip.
 */
class PeerMesh(
    private val store: SyncStore,
    private val transport: MeshTransport,
    private val psk: ByteArray,
    private val prologue: ByteArray,
    private val localName: () -> String,
    private val onPresence: (ReplicaId, WireMessage.Presence) -> Unit = { _, _ -> },
    private val onDisconnect: (ReplicaId) -> Unit = {},
    private val random: Random = Random.Default,
    private val fallbackGraceMillis: Long = FALLBACK_GRACE_MILLIS,
) {
    private class Live(val session: SyncSession, val job: Job, val initiator: Boolean)

    private val _peers = MutableStateFlow<Map<ReplicaId, MeshPeer>>(emptyMap())
    val peers: StateFlow<Map<ReplicaId, MeshPeer>> = _peers.asStateFlow()

    private val sessions = HashMap<ReplicaId, Live>()
    private val dialers = HashMap<ReplicaId, Job>()
    private var scope: CoroutineScope? = null

    @Synchronized
    fun start(parent: CoroutineScope) {
        if (scope != null) return
        val s = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
        scope = s
        s.launch { transport.incoming.collect { conn -> accept(s, conn) } }
        s.launch { transport.discovered.collect { found -> reconcile(s, found) } }
    }

    @Synchronized
    fun stop() {
        scope?.cancel()
        scope = null
        sessions.clear()
        dialers.clear()
        _peers.value = emptyMap()
    }

    /** Broadcasts our caret/selection to every live session. */
    @Synchronized
    fun broadcastPresence(p: WireMessage.Presence) {
        sessions.values.forEach { it.session.sendPresence(p) }
    }

    @Synchronized
    private fun reconcile(scope: CoroutineScope, found: Set<DiscoveredPeer>) {
        val me = store.localReplica
        val wanted = found.filter { it.replica != me }.associateBy { it.replica }
        // Stop dialing peers that disappeared (their live sessions end on their own via EOF).
        (dialers.keys - wanted.keys).forEach { dialers.remove(it)?.cancel() }
        for ((replica, peer) in wanted) {
            if (dialers[replica]?.isActive == true) continue
            // The smaller id dials right away. The larger id only dials as a *fallback* if no
            // session appeared after a grace period: mDNS visibility is not always symmetric
            // (multicast filtering, one side's resolve failing), and without this a pair where
            // only the larger id sees the smaller would never connect.
            dialers[replica] = scope.launch { dialLoop(peer, fallback = replica < me) }
        }
        found.filter { it.replica != me }.forEach { p ->
            _peers.update { m -> if (p.replica in m) m else m + (p.replica to MeshPeer(p.replica, SessionState.Handshaking)) }
        }
        val visible = found.mapTo(HashSet()) { it.replica }
        _peers.update { m -> m.filter { (r, v) -> r in visible || v.state !is SessionState.Closed } }
    }

    @Synchronized
    private fun hasSession(replica: ReplicaId) = sessions.containsKey(replica)

    private suspend fun dialLoop(peer: DiscoveredPeer, fallback: Boolean) {
        var attempt = 0
        while (scope?.isActive == true) {
            if (fallback || hasSession(peer.replica)) {
                // Fallback dialers wait for the canonical dialer; nobody dials an already
                // connected peer (incoming sessions count too).
                delay(random.nextLong(fallbackGraceMillis, fallbackGraceMillis * 2))
                if (hasSession(peer.replica)) { attempt = 0; continue }
            }
            val reason = try {
                val conn = transport.dial(peer)
                attempt = 0
                runSession(conn, initiator = true)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                CloseReason.IO_ERROR
            }
            attempt++
            val base = if (reason == CloseReason.AUTH_FAILED || reason == CloseReason.VERSION_MISMATCH) {
                MAX_BACKOFF_MILLIS
            } else {
                min(MAX_BACKOFF_MILLIS, MIN_BACKOFF_MILLIS shl min(attempt, 6))
            }
            _peers.update { m -> m[peer.replica]?.let { m + (peer.replica to it.copy(attempts = attempt)) } ?: m }
            delay(random.nextLong(base / 2, base + 1)) // "equal jitter"
        }
    }

    private fun accept(scope: CoroutineScope, conn: FrameConnection) {
        scope.launch { runSession(conn, initiator = false) }
    }

    /**
     * Runs one session in its *own* child job, so that replacing a duplicate session cancels
     * only that session, never the dial loop that created it.
     */
    private suspend fun runSession(conn: FrameConnection, initiator: Boolean): CloseReason = coroutineScope {
        val session = SyncSession(store, localName(), onPresence)
        val runner = launch(start = CoroutineStart.LAZY) { session.run(conn, initiator, psk, prologue) }
        val watcher = launch {
            session.state.collect { st ->
                val replica = session.peer?.replica ?: return@collect
                if (st is SessionState.Syncing && !register(replica, Live(session, runner, initiator))) runner.cancel()
                _peers.update { m -> m + (replica to MeshPeer(replica, st, m[replica]?.attempts ?: 0)) }
            }
        }
        runner.start()
        runner.join()
        watcher.cancel()
        session.peer?.replica?.let { r ->
            val wasCurrent = synchronized(this@PeerMesh) {
                (sessions[r]?.session === session).also { if (it) sessions.remove(r) }
            }
            if (wasCurrent) {
                _peers.update { m -> m + (r to MeshPeer(r, session.state.value, m[r]?.attempts ?: 0)) }
                onDisconnect(r)
            }
        }
        (session.state.value as? SessionState.Closed)?.reason ?: CloseReason.NORMAL
    }

    /**
     * Registers a newly authenticated session. If one already exists for the replica, both
     * devices must keep the *same* connection, or each might kill a different one and leave none:
     *  - different directions: keep the one dialed by the smaller replica id (both sides agree);
     *  - same direction (a reconnect racing a stale session): the newest wins.
     * @return false if the new session lost and must be closed.
     */
    @Synchronized
    private fun register(replica: ReplicaId, new: Live): Boolean {
        val old = sessions[replica]
        if (old != null && old.session !== new.session) {
            val canonicalInitiator = store.localReplica < replica
            val keepNew = old.initiator == new.initiator || new.initiator == canonicalInitiator
            if (!keepNew) return false
            old.job.cancel()
        }
        sessions[replica] = new
        return true
    }

    companion object {
        const val MIN_BACKOFF_MILLIS = 500L
        const val MAX_BACKOFF_MILLIS = 30_000L
        const val FALLBACK_GRACE_MILLIS = 4_000L
    }
}
