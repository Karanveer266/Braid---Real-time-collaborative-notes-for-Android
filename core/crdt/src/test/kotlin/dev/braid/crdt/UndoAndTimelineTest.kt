package dev.braid.crdt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class UndoAndTimelineTest {
    private val clock = FakeClock()

    private fun typed(stack: UndoStack, r: Replica, field: TextField, s: String): List<Op> {
        val out = ArrayList<Op>()
        for (ch in s) {
            val cur = r.workspace.note(stack.noteId)!!.text(field).text
            out += stack.edit(field, cur + ch)
        }
        return out
    }

    @Test
    fun `typing burst is one undo step and redo restores it`() {
        val a = replica(1, clock)
        val id = a.createNote().id
        val undo = UndoStack(a, id, now = clock)
        typed(undo, a, TextField.BODY, "hello")
        clock.now += 5_000
        typed(undo, a, TextField.BODY, " world")
        assertEquals("hello world", a.workspace.note(id)!!.body.text)

        assertEquals(5, undo.undo()!!.caret)
        assertEquals("hello", a.workspace.note(id)!!.body.text)
        undo.undo()
        assertEquals("", a.workspace.note(id)!!.body.text)
        assertNull(undo.undo())
        undo.redo(); undo.redo()
        assertEquals("hello world", a.workspace.note(id)!!.body.text)
    }

    @Test
    fun `undo only reverts my changes and keeps collaborator text in place`() {
        val a = replica(1, clock); val b = replica(2, clock)
        val create = a.createNote(); b.receive(create)
        val id = create.id
        val undoA = UndoStack(a, id, now = clock)

        b.receiveAll(typed(undoA, a, TextField.BODY, "AAA"))
        // Bob types before and after Alice's text.
        // (Two keystrokes: a single "AAA" -> "[AAA]" diff would be a replace-all.)
        val bobOps = b.editText(id, TextField.BODY, "[AAA") + b.editText(id, TextField.BODY, "[AAA]")
        a.receiveAll(bobOps)
        assertEquals("[AAA]", a.workspace.note(id)!!.body.text)

        val out = undoA.undo()!!
        b.receiveAll(out.ops)
        assertEquals("[]", a.workspace.note(id)!!.body.text)
        assertEquals("[]", b.workspace.note(id)!!.body.text)
        assertEquals(1, out.caret)
    }

    @Test
    fun `undoing a delete restores text at its original place even after concurrent edits`() {
        val a = replica(1, clock); val b = replica(2, clock)
        val create = a.createNote(); b.receive(create)
        val id = create.id
        val undoA = UndoStack(a, id, now = clock)
        b.receiveAll(a.editText(id, TextField.BODY, "one two three"))

        clock.now += 5_000
        b.receiveAll(undoA.edit(TextField.BODY, "one three"))          // Alice deletes "two "
        a.receiveAll(b.editText(id, TextField.BODY, "zero one three"))  // Bob prepends
        val out = undoA.undo()!!
        b.receiveAll(out.ops)
        assertEquals("zero one two three", a.workspace.note(id)!!.body.text)
        assertEquals(a.workspace.fingerprint(), b.workspace.fingerprint())
    }

    @Test
    fun `steps fully erased by a collaborator are skipped`() {
        val a = replica(1, clock); val b = replica(2, clock)
        val create = a.createNote(); b.receive(create)
        val id = create.id
        val undoA = UndoStack(a, id, now = clock)
        b.receiveAll(typed(undoA, a, TextField.BODY, "xyz"))
        a.receiveAll(b.editText(id, TextField.BODY, ""))
        assertNull(undoA.undo())
        assertFalse(undoA.canUndo)
    }

    @Test
    fun `timeline replays every historical version`() {
        val a = replica(1, clock)
        val create = a.createNote()
        val id = create.id
        val all = mutableListOf(create)
        val versions = mutableListOf("")
        for (s in listOf("a", "ab", "abc", "bc", "bXc")) {
            all += a.editText(id, TextField.BODY, s)
            versions += s
        }
        val other = a.createNote() // ops of other notes are filtered out
        all += other
        all += a.editText(other.id, TextField.BODY, "noise")

        val tl = NoteTimeline(id, all)
        assertEquals(1 + 3 + 1 + 1, tl.size) // create, 3 inserts, 1 delete, 1 insert
        assertEquals("abc", tl.stateAt(4).body.text)
        assertEquals("a", tl.stateAt(2).body.text)  // backwards
        assertEquals("bXc", tl.stateAt(tl.size).body.text)
    }

    @Test
    fun `undo and redo of a burst containing a backspace`() {
        val a = replica(1, clock)
        val id = a.createNote().id
        val undo = UndoStack(a, id, now = clock)
        typed(undo, a, TextField.BODY, "abc")
        undo.edit(TextField.BODY, "ab") // backspace inside the same group
        undo.undo()
        assertEquals("", a.workspace.note(id)!!.body.text)
        undo.redo()
        assertEquals("ab", a.workspace.note(id)!!.body.text)
        undo.undo()
        assertEquals("", a.workspace.note(id)!!.body.text)
    }

    @Test
    fun `undo stack survives random edit sequences`() {
        val rnd = kotlin.random.Random(5)
        repeat(300) { round ->
            val r = replica(1, clock)
            val id = r.createNote().id
            val undo = UndoStack(r, id, now = clock)
            val history = mutableListOf("")
            repeat(rnd.nextInt(1, 12)) {
                clock.now += 5_000 // every edit its own step
                val t = r.workspace.note(id)!!.body.text
                val n = if (t.isNotEmpty() && rnd.nextBoolean()) {
                    val s = rnd.nextInt(t.length); t.removeRange(s, (s + rnd.nextInt(1, 3)).coerceAtMost(t.length))
                } else {
                    val at = rnd.nextInt(t.length + 1); t.substring(0, at) + "xy"[rnd.nextInt(2)] + t.substring(at)
                }
                undo.edit(TextField.BODY, n)
                history += n
            }
            // Undo everything step by step, checking each intermediate version, then redo all.
            for (i in history.size - 2 downTo 0) {
                undo.undo()
                assertEquals(history[i], r.workspace.note(id)!!.body.text, "round $round undo to $i")
            }
            for (i in 1 until history.size) {
                undo.redo()
                assertEquals(history[i], r.workspace.note(id)!!.body.text, "round $round redo to $i")
            }
        }
    }
}
