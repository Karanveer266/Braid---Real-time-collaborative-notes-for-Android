package dev.braid.sync

import dev.braid.crdt.Replica
import dev.braid.crdt.ReplicaId
import dev.braid.crdt.TextField
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

/** A fake LAN: every node sees every other node; dialing creates an in-memory link. */
private class FakeLan {
    val nodes = MutableStateFlow<Set<DiscoveredPeer>>(emptySet())
    val inboxes = HashMap<ReplicaId, Channel<FrameConnection>>()
    var dials = 0

    fun join(id: ReplicaId, sees: (ReplicaId) -> Boolean = { true }): MeshTransport {
        val inbox = Channel<FrameConnection>(Channel.UNLIMITED)
        inboxes[id] = inbox
        nodes.value = nodes.value + DiscoveredPeer(id, "fake://$id")
        return object : MeshTransport {
            override val discovered: Flow<Set<DiscoveredPeer>> = nodes.map { all -> all.filterTo(HashSet()) { sees(it.replica) } }
            override val incoming: Flow<FrameConnection> = inbox.receiveAsFlow()
            override suspend fun dial(peer: DiscoveredPeer): FrameConnection {
                dials++
                val (mine, theirs) = InMemoryLink.pair("$id", "${peer.replica}", maxLatencyMillis = 2)
                inboxes.getValue(peer.replica).send(theirs)
                return mine
            }
        }
    }
}

class PeerMeshTest {
    private val psk = ByteArray(32) { 7 }
    private val prologue = syncPrologue("mesh")

    @Test
    fun `full mesh of four devices converges with exactly one session per pair`() = runBlocking(Dispatchers.Default) {
        val lan = FakeLan()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val stores = (1L..4L).map { InMemorySyncStore(Replica(ReplicaId(it))) }
        val meshes = stores.map { s -> PeerMesh(s, lan.join(s.localReplica), psk, prologue, { "n" }).also { it.start(scope) } }

        val noteId = stores[0].localOps { listOf(it.createNote()) }.single().id
        withTimeout(10_000) { while (stores.any { s -> s.read { it.workspace.note(noteId) } == null }) delay(5) }
        stores.forEachIndexed { i, s -> s.localOps { it.editText(noteId, TextField.BODY, "edit $i ") } }

        withTimeout(10_000) {
            while (stores.map { s -> s.read { it.workspace.fingerprint() } }.toSet().size != 1) delay(5)
        }
        assertEquals(6, lan.dials, "4 nodes -> 6 pairs, each dialed once by the smaller id")
        meshes.forEach { m -> withTimeout(5_000) { while (m.peers.value.values.count { it.state is SessionState.Live } != 3) delay(5) } }
        scope.cancel()
    }

    @Test
    fun `asymmetric discovery still connects via the fallback dialer`() = runBlocking(Dispatchers.Default) {
        val lan = FakeLan()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val small = InMemorySyncStore(Replica(ReplicaId(1)))
        val large = InMemorySyncStore(Replica(ReplicaId(2)))
        // The smaller id (the canonical dialer) cannot see anyone; only the larger sees it.
        PeerMesh(small, lan.join(small.localReplica) { false }, psk, prologue, { "s" }, fallbackGraceMillis = 50).start(scope)
        PeerMesh(large, lan.join(large.localReplica), psk, prologue, { "l" }, fallbackGraceMillis = 50).start(scope)
        val noteId = small.localOps { listOf(it.createNote()) }.single().id
        withTimeout(10_000) { while (large.read { it.workspace.note(noteId) } == null) delay(10) }
        assertEquals(1, lan.dials)
        scope.cancel()
    }
}
