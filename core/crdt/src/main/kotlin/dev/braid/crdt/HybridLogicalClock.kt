package dev.braid.crdt

/**
 * Hybrid Logical Clock (Kulkarni et al., 2014).
 *
 * Why HLC and not a plain Lamport clock? Both capture causality (if a → b then ts(a) < ts(b)),
 * which the merge logic depends on. HLC additionally stays within a bounded distance of physical
 * time, so "last writer wins" for titles matches what users perceive as "the latest edit", and the
 * timestamps are meaningful in the history UI.
 *
 * Why not wall-clock only? Phone clocks drift and can go backwards (NTP, manual changes, timezone
 * bugs). A wall clock alone would break causality and let an older edit beat a newer one.
 *
 * Safety valve: a peer whose clock is far in the future could "poison" every LWW register (its
 * writes would win for hours). [observe] therefore rejects remote timestamps more than
 * [maxForwardDriftMillis] ahead of our physical clock.
 */
class HybridLogicalClock(
    val replica: ReplicaId,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val maxForwardDriftMillis: Long = DEFAULT_MAX_DRIFT_MILLIS,
) {
    private var lastMillis = 0L
    private var lastCounter = 0

    /** Issues a new timestamp for a local event. Strictly greater than anything seen before. */
    @Synchronized
    fun now(): Timestamp {
        val physical = wallClock()
        if (physical > lastMillis) {
            lastMillis = physical
            lastCounter = 0
        } else {
            bumpCounter()
        }
        return Timestamp(lastMillis, lastCounter, replica)
    }

    /** Merges a remote timestamp so that every later local event is ordered after it. */
    @Synchronized
    fun observe(remote: Timestamp) {
        val physical = wallClock()
        if (remote.millis - physical > maxForwardDriftMillis) {
            throw ClockDriftException(remote, physical)
        }
        val newMillis = maxOf(lastMillis, remote.millis, physical)
        lastCounter = when {
            newMillis == lastMillis && newMillis == remote.millis -> maxOf(lastCounter, remote.counter) + 1
            newMillis == lastMillis -> lastCounter + 1
            newMillis == remote.millis -> remote.counter + 1
            else -> 0
        }
        lastMillis = newMillis
        if (lastCounter == Int.MAX_VALUE) bumpCounter()
    }

    /**
     * Restores state after a restart from the newest persisted timestamp. Unlike [observe] it
     * never rejects: our own history is trusted, and refusing it would brick the replica.
     */
    @Synchronized
    fun restore(latest: Timestamp) {
        if (latest.millis > lastMillis || (latest.millis == lastMillis && latest.counter > lastCounter)) {
            lastMillis = latest.millis
            lastCounter = latest.counter
        }
    }

    private fun bumpCounter() {
        if (lastCounter == Int.MAX_VALUE) {
            // 2^31 events in one millisecond is impossible in practice, but be total anyway:
            // borrowing a millisecond keeps monotonicity at the cost of 1 ms of drift.
            lastMillis += 1
            lastCounter = 0
        } else {
            lastCounter += 1
        }
    }

    companion object {
        const val DEFAULT_MAX_DRIFT_MILLIS: Long = 10 * 60 * 1000L
    }
}

class ClockDriftException(val remote: Timestamp, val physicalMillis: Long) :
    IllegalStateException("Remote timestamp $remote is ${remote.millis - physicalMillis} ms ahead of local clock")
