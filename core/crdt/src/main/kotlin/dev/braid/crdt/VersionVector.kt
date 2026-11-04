package dev.braid.crdt

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * Immutable version vector: for every replica, the highest *contiguous* sequence number known.
 *
 * Every replica numbers its own operations 1, 2, 3, ... and the system maintains the invariant
 * that a replica only ever holds a gap-free prefix of every other replica's log. That makes a
 * version vector an exact, O(replicas)-sized summary of "which operations do I have", which is
 * what makes the sync protocol's delta computation trivial and exact.
 */
@Serializable
class VersionVector(
    @ProtoNumber(1) private val entries: Map<Long, Long> = emptyMap(),
) {
    val replicas: Set<ReplicaId> get() = entries.keys.mapTo(LinkedHashSet()) { ReplicaId(it) }
    val totalOps: Long get() = entries.values.sum()
    val size: Int get() = entries.size

    operator fun get(replica: ReplicaId): Long = entries[replica.raw] ?: 0L

    fun contains(replica: ReplicaId, seq: Long): Boolean = seq <= get(replica)

    fun with(replica: ReplicaId, seq: Long): VersionVector {
        if (get(replica) >= seq) return this
        return VersionVector(entries + (replica.raw to seq))
    }

    /** Point-wise maximum: the least upper bound in the version-vector lattice. */
    fun merge(other: VersionVector): VersionVector {
        if (other.entries.isEmpty()) return this
        if (entries.isEmpty()) return other
        val out = HashMap(entries)
        for ((r, s) in other.entries) if ((out[r] ?: 0L) < s) out[r] = s
        return VersionVector(out)
    }

    /** True if this vector has seen everything [other] has seen. */
    fun dominates(other: VersionVector): Boolean = other.entries.all { (r, s) -> (entries[r] ?: 0L) >= s }

    /** True if [this] knows at least one operation that [other] does not. */
    fun hasOpsMissingFrom(other: VersionVector): Boolean = !other.dominates(this)

    fun forEach(action: (ReplicaId, Long) -> Unit) = entries.forEach { (r, s) -> action(ReplicaId(r), s) }

    fun asMap(): Map<ReplicaId, Long> = entries.mapKeys { ReplicaId(it.key) }

    override fun equals(other: Any?): Boolean =
        other is VersionVector && entries.filterValues { it > 0 } == other.entries.filterValues { it > 0 }

    override fun hashCode(): Int = entries.filterValues { it > 0 }.hashCode()

    override fun toString(): String = entries.entries.joinToString(prefix = "{", postfix = "}") { (r, s) ->
        "${ReplicaId(r)}:$s"
    }

    companion object {
        val EMPTY = VersionVector()
    }
}
