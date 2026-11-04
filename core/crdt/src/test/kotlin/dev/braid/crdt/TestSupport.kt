package dev.braid.crdt

/** Deterministic, manually advanced wall clock for reproducible tests. */
class FakeClock(var now: Long = 1_700_000_000_000L) : () -> Long {
    override fun invoke(): Long = now
}

fun replica(raw: Long, clock: FakeClock = FakeClock()): Replica = Replica(ReplicaId(raw), wallClock = clock)

/** Delivers every op in [ops] that [target] has not yet seen, in a causal (HLC) order. */
fun Replica.receiveAll(ops: Collection<Op>) {
    ops.sortedBy { it.id }.forEach { receive(it) }
}
