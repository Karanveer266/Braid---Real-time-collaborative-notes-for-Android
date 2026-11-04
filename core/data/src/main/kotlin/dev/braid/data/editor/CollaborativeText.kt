package dev.braid.data.editor

import dev.braid.crdt.Splice
import dev.braid.crdt.VisibleDeletion

/** Framework-independent mirror of a text field value (Compose's TextFieldValue maps 1:1). */
data class TextSnapshot(
    val text: String,
    val selectionStart: Int = text.length,
    val selectionEnd: Int = selectionStart,
    val compositionStart: Int = -1,
    val compositionEnd: Int = -1,
) {
    val hasComposition: Boolean get() = compositionStart in 0..compositionEnd
}

/** Primitive edit in visible-index space. */
sealed interface Prim {
    data class Ins(val at: Int, val length: Int) : Prim
    data class Del(val at: Int, val length: Int) : Prim
}

/**
 * Binds one editable text widget to one CRDT text, keeping the caret, selection and IME
 * composition correct while remote edits stream in.
 *
 * ## The race this class exists for
 * Text widgets (Compose's legacy `BasicTextField`, Android's `EditText`) keep their *own* edit
 * buffer. When the app pushes a new value, the widget adopts it at the next composition/layout.
 * If a remote edit changes our value and the user types *before* that happens, the widget reports
 * an edit relative to the stale text. Diffing that against the CRDT would "undo" the remote edit,
 * i.e. silently delete a collaborator's characters.
 *
 * ## The fix: rebasing
 * We track [base] (the text the widget currently edits against) and [pending], the primitive
 * edits that turn [base] into our current text. A user edit is diffed against [base] (recovering
 * exactly what the user did), then its indices are mapped through [pending] (operational-transform
 * style index mapping) and the rebased splice is applied to the current text and committed.
 *
 * Index mapping rules (also used for the caret):
 *  - a remote insertion strictly before an index shifts it; one *exactly at* the caret does not
 *    (the collaborator's text appears after your caret, as in Google Docs);
 *  - a deletion covering an index collapses it to the start of the deleted range.
 * A remote insertion strictly inside a selection therefore extends the selection, so deleting a
 * selection deletes what is visibly selected, never more and never less.
 *
 * Pure Kotlin, single-threaded (state thread), fully unit-tested.
 */
class CollaborativeText(
    initial: String,
    /** Applies new text to the CRDT and returns the CRDT's authoritative text afterwards. */
    private val commit: (newText: String, caret: Int) -> String,
) {
    var value: TextSnapshot = TextSnapshot(initial, 0)
        private set

    private var base: String = initial
    private val pending = ArrayList<Prim>()

    /** Number of edits that needed rebasing (diagnostics; surfaced in the debug panel). */
    var rebasedEdits: Int = 0
        private set

    /** Times the widget and CRDT disagreed and were resynchronized (should stay 0). */
    var desyncs: Int = 0
        private set

    /** The widget now displays [value] (call after composition/layout applied it). */
    fun onRendered(text: String) {
        if (text == value.text) {
            base = text
            pending.clear()
        }
    }

    /** The user edited the widget; [incoming] is relative to what the widget showed ([base]). */
    fun onUserEdit(incoming: TextSnapshot): TextSnapshot {
        if (incoming.text == base && pending.isEmpty()) {
            value = incoming // selection / composition change only
            return value
        }
        val splice = Splice.between(base, incoming.text, incoming.selectionEnd)
        val target: String
        val selStart: Int
        val selEnd: Int
        val compStart: Int
        val compEnd: Int
        if (pending.isEmpty()) {
            target = incoming.text
            selStart = incoming.selectionStart; selEnd = incoming.selectionEnd
            compStart = incoming.compositionStart; compEnd = incoming.compositionEnd
        } else {
            rebasedEdits++
            val start = map(splice.start, pending)
            val end = map(splice.start + splice.deleteCount, pending).coerceAtLeast(start)
            val rebased = Splice(start, end - start, splice.inserted)
            target = rebased.applyTo(value.text)
            // Map the widget's new caret into target coordinates: the base->incoming edit is
            // `splice`, base->current is `pending`; so incoming->target is pending transformed past
            // the splice. Approximating that by a fresh diff is exact whenever the edits are
            // disjoint (the overwhelmingly common case) and always *valid*.
            val toTarget = prims(Splice.between(incoming.text, target, -1))
            selStart = map(incoming.selectionStart, toTarget)
            selEnd = map(incoming.selectionEnd, toTarget)
            compStart = if (incoming.hasComposition) map(incoming.compositionStart, toTarget) else -1
            compEnd = if (incoming.hasComposition) map(incoming.compositionEnd, toTarget) else -1
        }
        val authoritative = commit(target, selEnd)
        if (authoritative != target) {
            // Cannot happen while every CRDT change of this field is forwarded to us; if it ever
            // does, the CRDT is the source of truth: resynchronize instead of crashing.
            desyncs++
            base = incoming.text
            pending.clear()
            pending += prims(Splice.between(incoming.text, authoritative, -1))
            value = TextSnapshot(authoritative, selEnd).clamped()
            return value
        }
        // The widget's buffer now holds `incoming`; until it renders our value, edits are relative
        // to `incoming`, so that becomes the base and pending re-expresses incoming -> target.
        base = incoming.text
        pending.clear()
        if (incoming.text != target) pending += prims(Splice.between(incoming.text, target, -1))
        value = TextSnapshot(target, selStart, selEnd, compStart, compEnd).clamped()
        return value
    }

    /** A remote insert landed at [index] ([length] chars); [newText] is the CRDT text after it. */
    fun onRemoteInsert(index: Int, length: Int, newText: String): TextSnapshot =
        applyExternal(listOf(Prim.Ins(index, length)), newText)

    /** Remote deletions (each relative to the text right before it). */
    fun onRemoteDelete(deletions: List<VisibleDeletion>, newText: String): TextSnapshot =
        applyExternal(deletions.map { Prim.Del(it.index, it.count) }, newText)

    /**
     * A local, programmatic change made directly on the CRDT (undo/redo, restore). The caret is
     * placed at [caret] if given.
     */
    fun onExternalChange(newText: String, caret: Int? = null): TextSnapshot {
        val snap = applyExternal(prims(Splice.between(value.text, newText, -1)), newText)
        if (caret != null) value = snap.copy(selectionStart = caret, selectionEnd = caret, compositionStart = -1, compositionEnd = -1).clamped()
        return value
    }

    private fun applyExternal(edits: List<Prim>, newText: String): TextSnapshot {
        val v = value
        pending += edits
        value = TextSnapshot(
            text = newText,
            selectionStart = map(v.selectionStart, edits),
            selectionEnd = map(v.selectionEnd, edits),
            compositionStart = if (v.hasComposition) map(v.compositionStart, edits) else -1,
            compositionEnd = if (v.hasComposition) map(v.compositionEnd, edits) else -1,
        ).clamped()
        // Safety net: if our prim bookkeeping ever disagrees with the CRDT, fall back to a diff.
        if (pending.isNotEmpty() && replayLength(base.length, pending) != newText.length) {
            pending.clear()
            pending += prims(Splice.between(base, newText, -1))
        }
        return value
    }

    private fun TextSnapshot.clamped(): TextSnapshot {
        val n = text.length
        val cs = compositionStart.coerceAtMost(n)
        val ce = compositionEnd.coerceAtMost(n)
        return copy(
            selectionStart = selectionStart.coerceIn(0, n),
            selectionEnd = selectionEnd.coerceIn(0, n),
            compositionStart = if (cs in 0 until ce) cs else -1,
            compositionEnd = if (cs in 0 until ce) ce else -1,
        )
    }

    companion object {
        fun map(index: Int, edits: List<Prim>): Int {
            var i = index
            for (e in edits) {
                i = when (e) {
                    is Prim.Ins -> if (e.at < i) i + e.length else i
                    is Prim.Del -> when {
                        i <= e.at -> i
                        i >= e.at + e.length -> i - e.length
                        else -> e.at
                    }
                }
            }
            return i
        }

        fun prims(s: Splice): List<Prim> = buildList {
            if (s.deleteCount > 0) add(Prim.Del(s.start, s.deleteCount))
            if (s.inserted.isNotEmpty()) add(Prim.Ins(s.start, s.inserted.length))
        }

        /** Length of [baseLength] text after [edits], or -1 if an edit is out of bounds. */
        internal fun replayLength(baseLength: Int, edits: List<Prim>): Int {
            var n = baseLength
            for (e in edits) when (e) {
                is Prim.Ins -> if (e.at > n) return -1 else n += e.length
                is Prim.Del -> if (e.at + e.length > n) return -1 else n -= e.length
            }
            return n
        }
    }
}
