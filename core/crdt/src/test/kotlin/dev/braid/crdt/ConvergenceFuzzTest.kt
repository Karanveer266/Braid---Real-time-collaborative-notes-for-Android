package dev.braid.crdt

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Property-based convergence test (the single most important test in the project).
 *
 * N replicas perform random edits (typing bursts, deletions, selection replacements, tag and
 * register changes) while exchanging *random subsets* of ops with random peers in random
 * causally-valid orders, simulating partitions and gossip. At the end everyone syncs fully and
 * all fingerprints must be identical. Additionally every replica's text must equal the text it
 * expects from its own local edits at each step (no lost local edits).
 *
 * Seeds are fixed so failures are reproducible; bump ROUNDS locally for soak testing.
 */
class ConvergenceFuzzTest {

    private class Peer(val replica: Replica, val log: MutableList<Op> = mutableListOf()) {
        val known = HashMap<Pair<ReplicaId, Long>, Op>()
    }

    @Test
    fun `random concurrent editing converges`() {
        for (seed in 1..SEEDS) runScenario(seed)
    }

    private fun runScenario(seed: Int) {
        val rnd = Random(seed)
        val clock = FakeClock()
        val peers = (1..rnd.nextInt(2, 5)).map { Peer(Replica(ReplicaId(it.toLong()), wallClock = clock)) }
        val allOps = ArrayList<Op>()

        val creator = peers.first()
        val create = creator.replica.createNote()
        allOps += create
        peers.drop(1).forEach { it.replica.receive(create) }
        val noteId = create.id
        peers.forEach { p -> p.known[create.author to create.seq] = create }

        repeat(ROUNDS) {
            clock.now += rnd.nextLong(0, 3) // frequent same-millisecond events stress the HLC counter
            val p = peers[rnd.nextInt(peers.size)]
            if (rnd.nextInt(100) < 70) {
                val ops = randomEdit(rnd, p.replica, noteId)
                ops.forEach { p.known[it.author to it.seq] = it }
                allOps += ops
            } else {
                // Partial gossip: deliver a random prefix of a random peer's missing ops.
                val q = peers[rnd.nextInt(peers.size)]
                if (q !== p) deliver(from = q, to = p, rnd = rnd, fraction = rnd.nextDouble())
            }
        }
        // Heal the partition: everyone gets everything.
        repeat(2) { peers.forEach { a -> peers.forEach { b -> if (a !== b) deliver(a, b, rnd, 1.0) } } }

        val expected = peers.first().replica.workspace.fingerprint()
        peers.forEach { assertEquals(expected, it.replica.workspace.fingerprint(), "seed=$seed diverged") }

        // Replaying all ops in pure timestamp order on a fresh replica must give the same state.
        val fresh = Replica(ReplicaId(999))
        fresh.receiveAll(allOps)
        assertEquals(expected, fresh.workspace.fingerprint(), "seed=$seed replay diverged")

        // Binary round-trip of the whole log must also give the same state.
        val decoded = OpCodec.decodeAll(OpCodec.encodeAll(allOps))
        val fresh2 = Replica(ReplicaId(998)); fresh2.receiveAll(decoded)
        assertEquals(expected, fresh2.workspace.fingerprint(), "seed=$seed codec diverged")
    }

    /** Sends to [to] the ops [from] knows that [to] lacks, preserving per-author order. */
    private fun deliver(from: Peer, to: Peer, rnd: Random, fraction: Double) {
        val missing = from.known.values
            .filter { !to.replica.workspace.version.contains(it.author, it.seq) }
            .sortedBy { it.id }
        val count = (missing.size * fraction).toInt()
        // A prefix in HLC order is always causally closed, so it can be applied in that order.
        missing.take(count).forEach { op ->
            to.replica.receive(op)
            to.known[op.author to op.seq] = op
        }
    }

    private fun randomEdit(rnd: Random, r: Replica, noteId: Timestamp): List<Op> {
        val note = r.workspace.note(noteId)!!
        val field = if (rnd.nextInt(10) == 0) TextField.TITLE else TextField.BODY
        val text = note.text(field).text
        return when (rnd.nextInt(10)) {
            in 0..4 -> { // typing burst at a random position
                var cur = text
                var caret = adjust(cur, rnd.nextInt(cur.length + 1))
                val out = ArrayList<Op>()
                repeat(rnd.nextInt(1, 6)) {
                    val ch = ALPHABET[rnd.nextInt(ALPHABET.size)]
                    cur = cur.substring(0, caret) + ch + cur.substring(caret)
                    caret += ch.length
                    out += r.editText(noteId, field, cur, caret)
                    assertEquals(cur, note.text(field).text, "local edit lost")
                }
                out
            }
            in 5..6 -> { // delete a range
                if (text.isEmpty()) emptyList() else {
                    val s = rnd.nextInt(text.length)
                    val e = (s + rnd.nextInt(1, 6)).coerceAtMost(text.length)
                    val new = safeCut(text, s, e)
                    r.editText(noteId, field, new).also { assertEquals(new, note.text(field).text) }
                }
            }
            7 -> { // replace selection (delete + insert)
                if (text.isEmpty()) emptyList() else {
                    val s = rnd.nextInt(text.length)
                    val e = (s + rnd.nextInt(1, 4)).coerceAtMost(text.length)
                    val cut = safeCut(text, s, e)
                    val at = adjust(cut, s)
                    val new = cut.substring(0, at) + "XY" + cut.substring(at)
                    r.editText(noteId, field, new).also { assertEquals(new, note.text(field).text) }
                }
            }
            8 -> listOfNotNull(
                if (rnd.nextBoolean()) r.addTag(noteId, TAGS.random(rnd)) else r.removeTag(noteId, TAGS.random(rnd)),
            )
            else -> listOfNotNull(r.setRegister(noteId, Registers.COLOR, COLORS.random(rnd)))
        }
    }

    private fun adjust(s: String, i: Int): Int {
        var x = i.coerceIn(0, s.length)
        if (x in 1 until s.length && Character.isLowSurrogate(s[x])) x--
        return x
    }

    private fun safeCut(s: String, start: Int, end: Int): String {
        val a = adjust(s, start)
        val b = adjust(s, end).coerceAtLeast(a)
        return s.substring(0, a) + s.substring(b)
    }

    companion object {
        const val SEEDS = 150
        const val ROUNDS = 400
        val ALPHABET = listOf("a", "b", "c", " ", "\n", "é", "😀")
        val TAGS = listOf("work", "home", "idea", "urgent")
        val COLORS = listOf("red", "green", "blue")
    }
}
