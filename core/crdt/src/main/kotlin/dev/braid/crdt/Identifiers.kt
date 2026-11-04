package dev.braid.crdt

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import java.security.SecureRandom

/**
 * Identity of one replica (one install participating in one workspace).
 *
 * A random 63-bit number rather than a UUID: it is compared on every merge and stored in every
 * operation, so a primitive keeps it allocation-free (value class) and compact on the wire.
 * Collision probability for 1 000 replicas in a workspace is ~5e-14.
 */
@JvmInline
@Serializable
value class ReplicaId(val raw: Long) : Comparable<ReplicaId> {
    override fun compareTo(other: ReplicaId): Int = raw.compareTo(other.raw)
    override fun toString(): String = raw.toULong().toString(16).padStart(16, '0')

    companion object {
        private val random = SecureRandom()
        fun random(): ReplicaId = ReplicaId(random.nextLong() ushr 1)
        fun parse(hex: String): ReplicaId = ReplicaId(hex.toULong(16).toLong())
    }
}

/**
 * Hybrid Logical Clock timestamp. Totally ordered: (millis, counter, replica).
 *
 * It doubles as the globally unique identity of an operation: a replica never issues the same
 * (millis, counter) twice, and the replica id disambiguates across replicas.
 */
@Serializable
data class Timestamp(
    @ProtoNumber(1) val millis: Long,
    @ProtoNumber(2) val counter: Int,
    @ProtoNumber(3) val replica: ReplicaId,
) : Comparable<Timestamp> {
    override fun compareTo(other: Timestamp): Int {
        if (millis != other.millis) return millis.compareTo(other.millis)
        if (counter != other.counter) return counter.compareTo(other.counter)
        return replica.compareTo(other.replica)
    }

    override fun toString(): String = "$millis.$counter@$replica"
}

/**
 * Identity of a single UTF-16 unit inside a collaborative text.
 *
 * An insert operation of a run of `n` characters assigns ids `(op.ts, 0) .. (op.ts, n-1)`, so a
 * whole typed word costs one timestamp instead of `n`. Ordering is lexicographic, which keeps the
 * RGA invariant "a child is always greater than its parent" (see [RgaText]).
 */
@Serializable
data class CharId(
    @ProtoNumber(1) val ts: Timestamp,
    @ProtoNumber(2) val offset: Int,
) : Comparable<CharId> {
    override fun compareTo(other: CharId): Int {
        val c = ts.compareTo(other.ts)
        return if (c != 0) c else offset.compareTo(other.offset)
    }

    override fun toString(): String = "$ts+$offset"
}
