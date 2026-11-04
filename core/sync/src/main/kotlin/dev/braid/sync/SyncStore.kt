package dev.braid.sync

import dev.braid.crdt.Op
import dev.braid.crdt.ReplicaId
import dev.braid.crdt.VersionVector
import kotlinx.coroutines.flow.Flow

/**
 * What the sync engine needs from a replica's storage. Implemented by the Room-backed
 * repository on Android and by [InMemorySyncStore] for tests and the in-app simulated peer.
 *
 * Contract:
 *  - [opsMissingFrom] returns only **durable** ops, in HLC order (a causally valid order).
 *  - [integrateRemote] applies and persists ops; duplicates are ignored. It may throw
 *    [dev.braid.crdt.ClockDriftException] to reject a peer with a broken clock.
 *  - [commits] emits (possibly conflated) after new ops become durable, local or remote.
 */
interface SyncStore {
    val localReplica: ReplicaId
    suspend fun version(): VersionVector
    suspend fun opsMissingFrom(remote: VersionVector): List<Op>
    suspend fun integrateRemote(ops: List<Op>)
    val commits: Flow<Unit>
}
