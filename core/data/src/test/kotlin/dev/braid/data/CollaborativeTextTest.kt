package dev.braid.data

import dev.braid.crdt.Effect
import dev.braid.crdt.Replica
import dev.braid.crdt.ReplicaId
import dev.braid.crdt.TextField
import dev.braid.data.editor.CollaborativeText
import dev.braid.data.editor.TextSnapshot
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Drives [CollaborativeText] the way Compose does, including the nasty case where the widget
 * reports a user edit against text that is already stale because a remote edit just arrived.
 */
class CollaborativeTextTest {
    private val me = Replica(ReplicaId(1))
    private val peer = Replica(ReplicaId(2))
    private val noteId = me.createNote().also { peer.receive(it) }.id

    private fun binding() = CollaborativeText(me.workspace.note(noteId)!!.body.text) { text, caret ->
        me.editText(noteId, TextField.BODY, text, caret).forEach(peer::receive)
        me.workspace.note(noteId)!!.body.text
    }

    /** Peer edit delivered to "me" and forwarded to the binding like the editor does. */
    private fun remote(b: CollaborativeText, newPeerText: String) {
        for (op in peer.editText(noteId, TextField.BODY, newPeerText)) {
            val text = { me.workspace.note(noteId)!!.body.text }
            when (val e = me.receive(op)) {
                is Effect.TextInserted -> b.onRemoteInsert(e.index, e.length, text())
                is Effect.TextDeleted -> b.onRemoteDelete(e.deletions, text())
                else -> Unit
            }
        }
    }

    private fun peerText() = peer.workspace.note(noteId)!!.body.text

    @Test
    fun `caret shifts with remote insert before it and stays for insert after`() {
        val b = binding()
        b.onUserEdit(TextSnapshot("hello world", 5)); b.onRendered("hello world")
        remote(b, ">> " + peerText())
        assertEquals(8, b.value.selectionEnd)
        remote(b, peerText() + "!")
        assertEquals(8, b.value.selectionEnd)
    }

    @Test
    fun `typing against stale widget text never deletes the remote edit`() {
        val b = binding()
        b.onUserEdit(TextSnapshot("abc", 3)); b.onRendered("abc")
        // Remote inserts "XYZ" at the start, but the widget has not re-rendered yet...
        remote(b, "XYZ" + peerText())
        assertEquals("XYZabc", b.value.text)
        // ...and the user types "d" at the end of what *they see*: "abc" -> "abcd".
        val out = b.onUserEdit(TextSnapshot("abcd", 4))
        assertEquals("XYZabcd", out.text)
        assertEquals(7, out.selectionEnd)
        assertEquals("XYZabcd", peerText())
        assertEquals(1, b.rebasedEdits)
    }

    @Test
    fun `remote deletion of the selected range collapses the selection`() {
        val b = binding()
        b.onUserEdit(TextSnapshot("one two four", 4, 7)); b.onRendered("one two four")
        remote(b, "one four")
        assertEquals(4, b.value.selectionStart)
        assertEquals(4, b.value.selectionEnd)
    }

    @Test
    fun `randomized interleaving of stale user edits and remote edits converges`() {
        val rnd = Random(11)
        repeat(200) { round ->
            val me = Replica(ReplicaId(1)); val peer = Replica(ReplicaId(2))
            val id = me.createNote().also { peer.receive(it) }.id
            val widget = StringBuilder() // what the widget buffer holds (may be stale)
            val b = CollaborativeText("") { t, c -> me.editText(id, TextField.BODY, t, c).forEach(peer::receive); me.workspace.note(id)!!.body.text }
            repeat(60) {
                when (rnd.nextInt(3)) {
                    0 -> { // user types into the (possibly stale) widget buffer
                        val at = rnd.nextInt(widget.length + 1)
                        widget.insert(at, "abc"[rnd.nextInt(3)])
                        val out = b.onUserEdit(TextSnapshot(widget.toString(), at + 1))
                        if (rnd.nextBoolean()) { widget.setLength(0); widget.append(out.text); b.onRendered(out.text) }
                    }
                    1 -> { // remote edit
                        val t = peer.workspace.note(id)!!.body.text
                        val nt = if (t.isNotEmpty() && rnd.nextBoolean()) {
                            val s = rnd.nextInt(t.length); t.removeRange(s, (s + rnd.nextInt(1, 3)).coerceAtMost(t.length))
                        } else {
                            val at = rnd.nextInt(t.length + 1); t.substring(0, at) + "XY" + t.substring(at)
                        }
                        for (op in peer.editText(id, TextField.BODY, nt)) {
                            val txt = { me.workspace.note(id)!!.body.text }
                            when (val e = me.receive(op)) {
                                is Effect.TextInserted -> b.onRemoteInsert(e.index, e.length, txt())
                                is Effect.TextDeleted -> b.onRemoteDelete(e.deletions, txt())
                                else -> Unit
                            }
                        }
                    }
                    else -> { widget.setLength(0); widget.append(b.value.text); b.onRendered(b.value.text) } // recomposition
                }
                assertEquals(me.workspace.note(id)!!.body.text, b.value.text, "round $round")
                assertEquals(me.workspace.fingerprint(), peer.workspace.fingerprint())
            }
        }
    }
}
