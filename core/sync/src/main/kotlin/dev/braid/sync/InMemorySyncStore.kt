package dev.braid.sync

import dev.braid.crdt.Admission
import dev.braid.crdt.Op
import dev.braid.crdt.Replica
import dev.braid.crdt.ReplicaId
import dev.braid.crdt.VersionVector
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A non-persistent [SyncStore] around a [Replica]. All access is serialized by a mutex. */
class InMemorySyncStore(val replica: Replica) : SyncStore {
    private val mutex = Mutex()
    private val log = ArrayList<Op>()
    private val _commits = MutableSharedFlow<Unit>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val _applied = MutableSharedFlow<List<Op>>(extraBufferCapacity = 64)

    /** Remote ops as they are applied (lets the simulated peer react to the user's edits). */
    val applied: SharedFlow<List<Op>> = _applied.asSharedFlow()

    override val localReplica: ReplicaId get() = replica.id
    override val commits: SharedFlow<Unit> = _commits.asSharedFlow()

    override suspend fun version(): VersionVector = mutex.withLock { replica.workspace.version }

    override suspend fun opsMissingFrom(remote: VersionVector): List<Op> = mutex.withLock {
        log.filter { !remote.contains(it.author, it.seq) }
    }

    override suspend fun integrateRemote(ops: List<Op>) {
        val applied = mutex.withLock {
            ops.filter { op ->
                (replica.workspace.check(op) == Admission.READY).also { ready -> if (ready) { replica.receive(op); log += op } }
            }
        }
        if (applied.isNotEmpty()) {
            _applied.tryEmit(applied)
            _commits.tryEmit(Unit)
        }
    }

    /** Runs a local mutation through a [Replica] intent; the returned ops are logged and announced. */
    suspend fun localOps(block: (Replica) -> List<Op>): List<Op> {
        val ops = mutex.withLock { block(replica).also { log += it } }
        if (ops.isNotEmpty()) _commits.tryEmit(Unit)
        return ops
    }

    suspend fun <T> read(block: (Replica) -> T): T = mutex.withLock { block(replica) }
}
