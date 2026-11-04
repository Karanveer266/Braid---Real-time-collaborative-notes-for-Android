package dev.braid.crdt

import kotlin.random.Random
import kotlin.system.measureNanoTime
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Coarse performance guards with generous limits (CI machines vary). They print the measured
 * numbers, which are quoted in details.md. Not a substitute for a Jetpack Macrobenchmark on a
 * device, which is listed as future work.
 */
class PerformanceTest {

    @Test
    fun `typing a 20k character note sequentially`() {
        val r = Replica(ReplicaId(1))
        val id = r.createNote().id
        val text = StringBuilder()
        val ns = measureNanoTime {
            repeat(20_000) { i ->
                text.append(if (i % 60 == 59) '\n' else 'a' + (i % 26))
                r.editText(id, TextField.BODY, text.toString(), text.length)
            }
        }
        val blocks = r.workspace.note(id)!!.body.blockCount
        println("PERF typing 20k chars: ${ns / 1_000_000} ms total, ${ns / 20_000 / 1000} µs/keystroke, blocks=$blocks")
        assertTrue(blocks == 1)
        assertTrue(ns / 20_000 < 2_000_000, "keystroke too slow")
    }

    @Test
    fun `random edits on a fragmented document and cold replay`() {
        val rnd = Random(1)
        val a = Replica(ReplicaId(1)); val b = Replica(ReplicaId(2))
        val create = a.createNote(); b.receive(create)
        val id = create.id
        val log = mutableListOf(create)
        // Two authors editing at random positions -> heavily fragmented RGA (worst-ish case).
        val editNs = measureNanoTime {
            repeat(20_000) { i ->
                val r = if (i % 2 == 0) a else b
                val other = if (r === a) b else a
                val t = r.workspace.note(id)!!.body.text
                val ops = if (t.length > 50 && rnd.nextInt(4) == 0) {
                    val s = rnd.nextInt(t.length - 5); r.editText(id, TextField.BODY, t.removeRange(s, s + 3))
                } else {
                    val at = rnd.nextInt(t.length + 1); listOf(r.insertAt(id, TextField.BODY, at, "word "))
                }
                ops.forEach(other::receive)
                log += ops
            }
        }
        val body = a.workspace.note(id)!!.body
        val bytes = OpCodec.encodeAll(log).size
        val fresh = Replica(ReplicaId(9))
        val replayNs = measureNanoTime { log.forEach(fresh::receive) }
        println(
            "PERF fragmented doc: ops=${log.size} visible=${body.length} blocks=${body.blockCount} tombstones=${body.tombstoneCount} " +
                "edit+merge=${editNs / log.size / 1000} µs/op replay=${replayNs / 1_000_000} ms encoded=${bytes / 1024} KiB (${bytes / log.size} B/op)",
        )
        assertTrue(replayNs < 30_000_000_000)
    }
}
