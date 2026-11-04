package dev.braid.crdt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RgaTextTest {
    private val clock = FakeClock()

    private fun pair(): Pair<Replica, Replica> {
        val a = replica(1, clock)
        val b = replica(2, clock)
        val create = a.createNote()
        b.receive(create)
        return a to b
    }

    private val Replica.note get() = workspace.allNotes.first()
    private fun Replica.body() = note.body.text

    @Test
    fun `sequential typing is stored as a single block`() {
        val (a, _) = pair()
        val id = a.note.id
        var text = ""
        for (ch in "The quick brown fox jumps over the lazy dog") {
            text += ch
            a.editText(id, TextField.BODY, text)
        }
        assertEquals(text, a.body())
        assertEquals(1, a.note.body.blockCount, "run extension should keep one block")
    }

    @Test
    fun `concurrent inserts at the same position converge and do not interleave`() {
        val (a, b) = pair()
        val id = a.note.id
        val base = a.editText(id, TextField.BODY, "Hello !")
        b.receiveAll(base)

        val opsA = mutableListOf<Op>()
        val opsB = mutableListOf<Op>()
        // Both users type a word, char by char, at index 6 at the same time.
        var ta = a.body(); var tb = b.body()
        for (ch in "Alice") { ta = ta.substring(0, 6 + opsA.size) + ch + ta.substring(6 + opsA.size); opsA += a.editText(id, TextField.BODY, ta) }
        for (ch in "Bob") { tb = tb.substring(0, 6 + opsB.size) + ch + tb.substring(6 + opsB.size); opsB += b.editText(id, TextField.BODY, tb) }

        a.receiveAll(opsB)
        b.receiveAll(opsA)
        assertEquals(a.body(), b.body())
        val merged = a.body()
        assertTrue(merged.contains("Alice") && merged.contains("Bob"), merged)
        assertTrue(merged == "Hello AliceBob!" || merged == "Hello BobAlice!", merged)
    }

    @Test
    fun `insert inside text deleted concurrently survives`() {
        val (a, b) = pair()
        val id = a.note.id
        b.receiveAll(a.editText(id, TextField.BODY, "abcdef"))

        val del = a.editText(id, TextField.BODY, "af")          // delete "bcde"
        val ins = b.editText(id, TextField.BODY, "abcXdef")     // insert inside it

        a.receiveAll(ins)
        b.receiveAll(del)
        assertEquals("aXf", a.body())
        assertEquals("aXf", b.body())
    }

    @Test
    fun `concurrent overlapping deletes are idempotent`() {
        val (a, b) = pair()
        val id = a.note.id
        b.receiveAll(a.editText(id, TextField.BODY, "0123456789"))
        val da = a.editText(id, TextField.BODY, "0189")   // delete 2..7
        val db = b.editText(id, TextField.BODY, "012389") // delete 4..7
        a.receiveAll(db); b.receiveAll(da)
        assertEquals("0189", a.body())
        assertEquals("0189", b.body())
    }

    @Test
    fun `anchors keep the caret stable across remote edits`() {
        val (a, b) = pair()
        val id = a.note.id
        b.receiveAll(a.editText(id, TextField.BODY, "hello world"))
        val anchor = b.note.body.anchorAt(5) // caret after "hello"
        a.receiveAll(emptyList())
        b.receiveAll(a.editText(id, TextField.BODY, ">> hello world"))
        assertEquals(8, b.note.body.resolveAnchor(anchor))
    }

    @Test
    fun `emoji are never split into lone surrogates`() {
        val (a, b) = pair()
        val id = a.note.id
        b.receiveAll(a.editText(id, TextField.BODY, "x😀y"))
        b.receiveAll(a.editText(id, TextField.BODY, "x😃y")) // same high surrogate, different low
        assertEquals("x😃y", b.body())
        b.note.body.text.forEachIndexed { i, c ->
            if (Character.isHighSurrogate(c)) assertTrue(Character.isLowSurrogate(b.body()[i + 1]))
        }
    }

    @Test
    fun `out of order delivery is detected rather than silently corrupting`() {
        val (a, b) = pair()
        val id = a.note.id
        val ops = a.editText(id, TextField.BODY, "ab") + a.editText(id, TextField.BODY, "abc")
        assertFailsWith<MissingDependencyException> { b.workspace.apply(ops.last()) }
    }

    @Test
    fun `extension is not used when a newer sibling already follows the caret`() {
        val (a, b) = pair()
        val id = a.note.id
        b.receiveAll(a.editText(id, TextField.BODY, "ab"))
        // B inserts after A's "b" (end of A's run), then A receives it and types after its own "b".
        val fromB = b.editText(id, TextField.BODY, "abZ")
        a.receiveAll(fromB)
        a.editText(id, TextField.BODY, "abXZ", caretHint = 3)
        assertEquals("abXZ", a.body())
    }

    @Test
    fun `delete referencing an unknown range fails without partial effects`() {
        val (a, _) = pair()
        val id = a.note.id
        a.editText(id, TextField.BODY, "hello")
        val known = a.note.body.idRanges(0, 2).single()
        val bogus = IdRange(Timestamp(1, 0, ReplicaId(77)), 0, 1)
        assertFailsWith<MissingDependencyException> { a.note.body.integrateDelete(listOf(known, bogus)) }
        assertEquals("hello", a.body())
    }
}
