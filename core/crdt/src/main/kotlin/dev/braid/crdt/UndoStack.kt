package dev.braid.crdt

/**
 * Selective, collaboration-aware undo/redo for one note.
 *
 * In a shared document, "undo" must revert *my* last change, never a collaborator's change that
 * happened to land after it, and it must work even if the text around my change has moved.
 * A global op-log undo (or snapshot restore) would get both wrong.
 *
 * Each undo step stores *intentions expressed in character ids*, not positions:
 *  - [Entry.Inserted]: the id range I inserted. Undo = delete whatever of it is still visible.
 *  - [Entry.Deleted]: the text I deleted and the id of the character that preceded it.
 *    Undo = re-insert that text after that character (valid even if it is now a tombstone).
 *
 * Undo itself produces ordinary ops, so it syncs to peers, is persisted, and shows up in history.
 * Consecutive edits within [groupWindowMillis] in the same field are grouped (typing a word is one
 * undo step), mirroring what users expect from native text editors.
 */
class UndoStack(
    private val replica: Replica,
    val noteId: Timestamp,
    private val now: () -> Long = System::currentTimeMillis,
    private val groupWindowMillis: Long = 1_000,
    private val maxDepth: Int = 200,
) {
    sealed interface Entry {
        val field: TextField

        data class Inserted(override val field: TextField, val range: IdRange, val after: CharId?) : Entry
        /** [ranges] are the ids of the deleted chars, in document order (total length == text.length). */
        data class Deleted(override val field: TextField, val after: CharId?, val text: String, val ranges: List<IdRange>) : Entry
    }

    private class Group(val entries: MutableList<Entry>, var lastAt: Long)

    /** Result of an undo/redo: the ops to persist/broadcast and where to put the caret. */
    data class Outcome(val ops: List<Op>, val field: TextField, val caret: Int)

    /**
     * Re-inserting deleted text (undoing a delete) necessarily creates *new* character ids. Older
     * entries still reference the original ids, so we remember original -> replacement and
     * translate through it (transitively: a char may be deleted and restored several times).
     */
    private val replaced = HashMap<CharId, CharId>()

    private val undo = ArrayDeque<Group>()
    private val redo = ArrayDeque<Group>()

    val canUndo: Boolean get() = undo.isNotEmpty()
    val canRedo: Boolean get() = redo.isNotEmpty()

    /** Performs and records a local text edit (old text → [newText]). */
    fun edit(field: TextField, newText: String, caretHint: Int = -1): List<Op> {
        val rga = text(field)
        val splice = Splice.between(rga.text, newText, caretHint)
        if (splice.isNoop) return emptyList()
        val entries = ArrayList<Entry>(2)
        if (splice.deleteCount > 0) {
            entries += Entry.Deleted(
                field, rga.anchorAt(splice.start),
                rga.text.substring(splice.start, splice.start + splice.deleteCount),
                rga.idRanges(splice.start, splice.start + splice.deleteCount),
            )
        }
        val ops = replica.editText(noteId, field, newText, caretHint)
        ops.forEach { op -> (op.payload as? OpPayload.InsertText)?.let { entries += insertedEntry(op, it) } }
        record(entries)
        redo.clear()
        return ops
    }

    fun undo(): Outcome? = transfer(undo, redo)
    fun redo(): Outcome? = transfer(redo, undo)

    private fun transfer(from: ArrayDeque<Group>, to: ArrayDeque<Group>): Outcome? {
        while (from.isNotEmpty()) {
            val group = from.removeLast()
            val ops = ArrayList<Op>()
            val inverse = ArrayList<Entry>()
            var caret = -1
            var field = group.entries.last().field
            for (e in group.entries.asReversed()) {
                field = e.field
                when (e) {
                    is Entry.Inserted -> {
                        val rga = text(e.field)
                        val parts = translate(e.range).flatMap { rga.visibleParts(it) }
                        if (parts.isEmpty()) continue
                        // Caret goes where the removed text started (resolved by id, not anchor:
                        // a collaborator may have inserted at the same anchor).
                        val first = parts.first().first
                        caret = rga.resolveAnchor(CharId(first.ts, first.offset)) - 1
                        replica.deleteIds(noteId, e.field, parts.map { it.first })?.let(ops::add)
                        inverse += Entry.Deleted(e.field, e.after, parts.joinToString("") { it.second }, parts.map { it.first })
                    }
                    is Entry.Deleted -> {
                        val op = replica.insertAfter(noteId, e.field, e.after?.let(::translate), e.text)
                        ops += op
                        val inserted = insertedEntry(op, op.payload as OpPayload.InsertText)
                        rememberReplacement(e.ranges, inserted.range)
                        inverse += inserted
                        val r = inserted.range
                        caret = text(e.field).resolveAnchor(CharId(r.ts, r.offset + r.length - 1))
                    }
                }
            }
            // A step whose effects were entirely overwritten by collaborators is skipped,
            // so pressing undo always does something visible.
            if (ops.isEmpty()) continue
            // Stored in processing order: transfer() walks groups backwards, so the inverse
            // entries are replayed in exactly the reverse order of what they undo.
            to.addLast(Group(inverse.toMutableList(), lastAt = Long.MIN_VALUE))
            return Outcome(ops, field, caret.coerceAtLeast(0))
        }
        return null
    }

    private fun record(entries: List<Entry>) {
        if (entries.isEmpty()) return
        val t = now()
        val last = undo.lastOrNull()
        if (last != null && t - last.lastAt <= groupWindowMillis && last.entries.last().field == entries.first().field) {
            last.entries += entries
            last.lastAt = t
        } else {
            undo.addLast(Group(entries.toMutableList(), t))
            if (undo.size > maxDepth) undo.removeFirst()
        }
    }

    private fun translate(id: CharId): CharId {
        var cur = id
        while (true) cur = replaced[cur] ?: return cur
    }

    /** Translates a range char by char and regroups contiguous results. */
    private fun translate(range: IdRange): List<IdRange> {
        val out = ArrayList<IdRange>()
        for (k in 0 until range.length) {
            val c = translate(CharId(range.ts, range.offset + k))
            val last = out.lastOrNull()
            if (last != null && last.ts == c.ts && last.offset + last.length == c.offset) {
                out[out.lastIndex] = last.copy(length = last.length + 1)
            } else {
                out += IdRange(c.ts, c.offset, 1)
            }
        }
        return out
    }

    private fun rememberReplacement(original: List<IdRange>, replacement: IdRange) {
        var k = 0
        for (r in original) for (i in 0 until r.length) {
            replaced[CharId(r.ts, r.offset + i)] = CharId(replacement.ts, replacement.offset + k++)
        }
    }

    private fun insertedEntry(op: Op, p: OpPayload.InsertText): Entry.Inserted {
        val first = p.firstCharId(op.id)
        return Entry.Inserted(p.field, IdRange(first.ts, first.offset, p.text.length), p.after)
    }

    private fun text(field: TextField): RgaText =
        (replica.workspace.note(noteId) ?: error("note $noteId missing")).text(field)
}
