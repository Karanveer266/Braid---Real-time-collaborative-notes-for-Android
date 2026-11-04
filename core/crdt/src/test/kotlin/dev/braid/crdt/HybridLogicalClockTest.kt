package dev.braid.crdt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HybridLogicalClockTest {
    private val r = ReplicaId(1)

    @Test
    fun `timestamps are strictly monotonic even when wall clock stalls`() {
        val clock = FakeClock()
        val hlc = HybridLogicalClock(r, clock)
        val a = hlc.now()
        val b = hlc.now()
        val c = hlc.now()
        assertTrue(a < b && b < c)
        assertEquals(a.millis, c.millis)
        assertEquals(2, c.counter)
    }

    @Test
    fun `timestamps stay monotonic when wall clock goes backwards`() {
        val clock = FakeClock(10_000)
        val hlc = HybridLogicalClock(r, clock)
        val a = hlc.now()
        clock.now = 5_000 // NTP correction / user changed time
        val b = hlc.now()
        assertTrue(b > a)
        assertEquals(10_000, b.millis)
    }

    @Test
    fun `observing a remote timestamp orders later local events after it`() {
        val clock = FakeClock(1_000)
        val hlc = HybridLogicalClock(r, clock)
        val remote = Timestamp(1_500, 7, ReplicaId(2)) // remote clock slightly ahead
        hlc.observe(remote)
        assertTrue(hlc.now() > remote)
    }

    @Test
    fun `remote timestamps far in the future are rejected`() {
        val clock = FakeClock(1_000)
        val hlc = HybridLogicalClock(r, clock, maxForwardDriftMillis = 60_000)
        assertFailsWith<ClockDriftException> {
            hlc.observe(Timestamp(1_000 + 3_600_000, 0, ReplicaId(2)))
        }
    }

    @Test
    fun `restore resumes after the newest persisted timestamp`() {
        val clock = FakeClock(1_000)
        val hlc = HybridLogicalClock(r, clock)
        val persisted = Timestamp(2_000, 4, r)
        hlc.restore(persisted)
        assertTrue(hlc.now() > persisted)
    }
}
