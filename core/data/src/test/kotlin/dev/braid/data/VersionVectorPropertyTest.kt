package dev.braid.data

import dev.braid.crdt.ReplicaId
import dev.braid.crdt.VersionVector
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Lattice laws the sync protocol relies on. */
class VersionVectorPropertyTest {
    private fun rnd(r: Random) = (1..r.nextInt(5)).fold(VersionVector.EMPTY) { v, _ -> v.with(ReplicaId(r.nextLong(1, 5)), r.nextLong(1, 50)) }

    @Test
    fun `merge is commutative associative idempotent and an upper bound`() {
        val r = Random(3)
        repeat(2_000) {
            val a = rnd(r); val b = rnd(r); val c = rnd(r)
            assertEquals(a.merge(b), b.merge(a))
            assertEquals(a.merge(b).merge(c), a.merge(b.merge(c)))
            assertEquals(a, a.merge(a))
            assertTrue(a.merge(b).dominates(a) && a.merge(b).dominates(b))
        }
    }
}
