package dev.braid.data.db

import dev.braid.crdt.Op
import dev.braid.crdt.VersionVector

/**
 * Durable, append-only storage of operations: the single source of truth of a replica.
 *
 * Event sourcing: the in-memory CRDT state is a pure function of this log. There is no
 * separately persisted "current note text" that could disagree with the log after a crash.
 */
interface OpLog {
    suspend fun append(ops: List<Op>)

    /** Every op in HLC order (a causally valid order for replay). */
    suspend fun loadAll(): List<Op>

    /** Every op the holder of [remote] lacks, in HLC order. */
    suspend fun opsMissingFrom(remote: VersionVector): List<Op>

    suspend fun count(): Long

    suspend fun clear()
}

/** Non-durable implementation for unit tests. */
class InMemoryOpLog : OpLog {
    private val lock = Any()
    private val ops = ArrayList<Op>()
    var appendCalls = 0
        private set

    override suspend fun append(ops: List<Op>) = synchronized(lock) {
        appendCalls++
        this.ops += ops
        Unit
    }

    override suspend fun loadAll(): List<Op> = synchronized(lock) { ops.sortedBy { it.id } }

    override suspend fun opsMissingFrom(remote: VersionVector): List<Op> = synchronized(lock) {
        ops.filter { !remote.contains(it.author, it.seq) }.sortedBy { it.id }
    }

    override suspend fun count(): Long = synchronized(lock) { ops.size.toLong() }
    override suspend fun clear() = synchronized(lock) { ops.clear() }
}
