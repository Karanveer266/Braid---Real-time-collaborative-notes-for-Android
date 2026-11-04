package dev.braid.sync

import dev.braid.crdt.Replica
import dev.braid.crdt.ReplicaId
import dev.braid.crdt.TextField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyncSessionTest {
    private val psk = ByteArray(32) { it.toByte() }
    private val prologue = syncPrologue("ws-test")

    private fun store(id: Long) = InMemorySyncStore(Replica(ReplicaId(id)))

    private suspend fun InMemorySyncStore.fp() = read { it.workspace.fingerprint() }

    /** Waits until all stores have the same fingerprint (or times out). */
    private suspend fun awaitConvergence(vararg stores: InMemorySyncStore) = withTimeout(10_000) {
        while (stores.map { it.fp() }.toSet().size != 1) delay(5)
    }

    @Test
    fun `two peers exchange history then stream live edits`() = runBlocking(Dispatchers.Default) {
        val a = store(1); val b = store(2)
        val noteId = a.localOps { listOf(it.createNote()) }.single().id
        a.localOps { it.editText(noteId, TextField.BODY, "offline edit on A") }

        val (ca, cb) = InMemoryLink.pair(maxLatencyMillis = 3)
        val sa = SyncSession(a, "A"); val sb = SyncSession(b, "B")
        val ja = launch { sa.run(ca, initiator = true, psk = psk, prologue = prologue) }
        val jb = launch { sb.run(cb, initiator = false, psk = psk, prologue = prologue) }

        awaitConvergence(a, b)
        assertEquals("offline edit on A", b.read { it.workspace.note(noteId)!!.body.text })

        // Live concurrent typing on both sides.
        repeat(30) { i ->
            a.localOps { r -> r.editText(noteId, TextField.BODY, r.workspace.note(noteId)!!.body.text + "a") }
            b.localOps { r -> r.editText(noteId, TextField.BODY, "b" + r.workspace.note(noteId)!!.body.text) }
            if (i % 7 == 0) delay(2)
        }
        awaitConvergence(a, b)
        val text = a.read { it.workspace.note(noteId)!!.body.text }
        assertEquals(30, text.count { it == 'a' } - "offline edit on A".count { it == 'a' })
        assertTrue(text.startsWith("b".repeat(30)))
        ja.cancel(); jb.cancel()
    }

    @Test
    fun `wrong workspace key is rejected and nothing leaks`() = runBlocking(Dispatchers.Default) {
        val a = store(1); val b = store(2)
        a.localOps { listOf(it.createNote()) }
        val (ca, cb) = InMemoryLink.pair()
        val sa = SyncSession(a, "A"); val sb = SyncSession(b, "B")
        val wrong = psk.copyOf().also { it[31] = 99 }
        coroutineScope {
            launch { sa.run(ca, true, psk, prologue) }
            launch { sb.run(cb, false, wrong, prologue) }
        }
        assertEquals(CloseReason.AUTH_FAILED, (sb.state.value as SessionState.Closed).reason)
        assertTrue(b.read { it.workspace.allNotes.isEmpty() })
    }

    @Test
    fun `ops propagate transitively through a relay peer`() = runBlocking(Dispatchers.Default) {
        // A <-> B <-> C, but A and C never meet (e.g. different rooms, B walks between them).
        val a = store(1); val b = store(2); val c = store(3)
        val noteId = a.localOps { listOf(it.createNote()) }.single().id
        val (ab1, ab2) = InMemoryLink.pair(maxLatencyMillis = 2)
        val (bc1, bc2) = InMemoryLink.pair(maxLatencyMillis = 2)
        val jobs = listOf(
            launch { SyncSession(a, "A").run(ab1, true, psk, prologue) },
            launch { SyncSession(b, "B").run(ab2, false, psk, prologue) },
            launch { SyncSession(b, "B").run(bc1, true, psk, prologue) },
            launch { SyncSession(c, "C").run(bc2, false, psk, prologue) },
        )
        awaitConvergence(a, b, c)
        c.localOps { it.editText(noteId, TextField.TITLE, "from C") }
        a.localOps { it.editText(noteId, TextField.BODY, "from A") }
        awaitConvergence(a, b, c)
        assertEquals("from C", a.read { it.workspace.note(noteId)!!.title.text })
        assertEquals("from A", c.read { it.workspace.note(noteId)!!.body.text })
        jobs.forEach { it.cancel() }
    }

    @Test
    fun `reconnect after drop resumes with exact delta`() = runBlocking(Dispatchers.Default) {
        val a = store(1); val b = store(2)
        val noteId = a.localOps { listOf(it.createNote()) }.single().id
        suspend fun connectOnce(): List<kotlinx.coroutines.Job> {
            val (ca, cb) = InMemoryLink.pair(maxLatencyMillis = 2)
            return listOf(
                launch { SyncSession(a, "A").run(ca, true, psk, prologue) },
                launch { SyncSession(b, "B").run(cb, false, psk, prologue) },
            )
        }
        var jobs = connectOnce()
        awaitConvergence(a, b)
        jobs.forEach { it.cancel() }

        // Partition: both sides keep editing.
        val rnd = Random(7)
        repeat(50) {
            val target = if (rnd.nextBoolean()) a else b
            target.localOps { r ->
                val t = r.workspace.note(noteId)!!.body.text
                val i = rnd.nextInt(t.length + 1)
                r.editText(noteId, TextField.BODY, t.substring(0, i) + "xyz"[rnd.nextInt(3)] + t.substring(i))
            }
        }
        jobs = connectOnce()
        awaitConvergence(a, b)
        assertEquals(50, a.read { it.workspace.note(noteId)!!.body.text.length })
        jobs.forEach { it.cancel() }
    }

    @Test
    fun `large histories are fragmented and streamed`() = runBlocking(Dispatchers.Default) {
        val a = store(1); val b = store(2)
        val noteId = a.localOps { listOf(it.createNote()) }.single().id
        // ~6000 ops with big payloads -> multiple Ops messages, several Noise fragments each.
        val chunk = "lorem ipsum dolor sit amet ".repeat(8)
        repeat(6_000) { a.localOps { r -> listOf(r.insertAt(noteId, TextField.BODY, 0, chunk)) } }
        val (ca, cb) = InMemoryLink.pair()
        val ja = launch { SyncSession(a, "A").run(ca, true, psk, prologue) }
        val jb = launch { SyncSession(b, "B").run(cb, false, psk, prologue) }
        awaitConvergence(a, b)
        assertEquals(6_000 * chunk.length, b.read { it.workspace.note(noteId)!!.body.length })
        ja.cancel(); jb.cancel()
    }
}
