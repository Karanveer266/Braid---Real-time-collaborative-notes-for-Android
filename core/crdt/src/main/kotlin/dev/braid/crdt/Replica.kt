package dev.braid.crdt

/**
 * The *local author* side: converts user intents into ops, applies them to the [workspace]
 * and returns them so the caller can persist and broadcast them.
 *
 * Intent methods are high level ("the body changed from A to B") because that is what UI widgets
 * report. Translation into id-based CRDT ops happens here, in pure Kotlin, so it is unit-tested
 * without Android.
 */
class Replica(
    val id: ReplicaId,
    val workspace: Workspace = Workspace(),
    wallClock: () -> Long = System::currentTimeMillis,
) {
    val clock = HybridLogicalClock(id, wallClock)

    init {
        workspace.maxTimestamp?.let(clock::restore)
    }


    /** Applies an op received from another replica. Returns the effect for UI updates. */
    fun receive(op: Op): Effect {
        if (workspace.check(op) == Admission.READY) clock.observe(op.id)
        return workspace.apply(op)
    }

    // ------------------------------------------------------------------ intents

    fun createNote(): Op = emit(OpPayload.CreateNote())

    fun setProfile(name: String): Op = emit(OpPayload.SetProfile(name.trim().take(40)))

    /**
     * Records the edit that turned the field's current text into [newText].
     * Returns 0..2 ops (a delete and/or an insert). Replacing a selection produces both.
     */
    fun editText(noteId: Timestamp, field: TextField, newText: String, caretHint: Int = -1): List<Op> {
        val rga = noteOrThrow(noteId).text(field)
        require(!newText.hasLoneSurrogate()) { "text contains an unpaired surrogate" }
        val splice = Splice.between(rga.text, newText, caretHint)
        if (splice.isNoop) return emptyList()
        val ops = ArrayList<Op>(2)
        if (splice.deleteCount > 0) {
            val ranges = rga.idRanges(splice.start, splice.start + splice.deleteCount)
            ops += emit(OpPayload.DeleteText(noteId, field, ranges))
        }
        if (splice.inserted.isNotEmpty()) {
            ops += insertAt(noteId, field, splice.start, splice.inserted)
        }
        return ops
    }

    fun insertAt(noteId: Timestamp, field: TextField, index: Int, text: String): Op =
        insertAfter(noteId, field, noteOrThrow(noteId).text(field).anchorAt(index), text)

    /** Inserts [text] directly after character [after] (which may be a tombstone). */
    fun insertAfter(noteId: Timestamp, field: TextField, after: CharId?, text: String): Op {
        require(text.isNotEmpty())
        val rga = noteOrThrow(noteId).text(field)
        val ext = rga.extensionIdFor(after, id)
        return emit(
            OpPayload.InsertText(
                noteId = noteId,
                field = field,
                after = after,
                text = text,
                run = ext?.ts,
                offset = ext?.offset ?: 0,
            ),
        )
    }

    /** Deletes the given id ranges (already-deleted parts are harmless). */
    fun deleteIds(noteId: Timestamp, field: TextField, ranges: List<IdRange>): Op? {
        if (ranges.isEmpty()) return null
        return emit(OpPayload.DeleteText(noteId, field, ranges))
    }

    fun deleteRange(noteId: Timestamp, field: TextField, start: Int, end: Int): Op? {
        val ranges = noteOrThrow(noteId).text(field).idRanges(start, end)
        if (ranges.isEmpty()) return null
        return emit(OpPayload.DeleteText(noteId, field, ranges))
    }

    fun setRegister(noteId: Timestamp, key: String, value: String): Op? {
        if (noteOrThrow(noteId).register(key) == value) return null
        return emit(OpPayload.SetRegister(noteId, key, value))
    }

    fun addTag(noteId: Timestamp, tag: String): Op? {
        val normalized = Workspace.normalizeTag(tag)
        if (normalized.isEmpty() || normalized in noteOrThrow(noteId).tags) return null
        return emit(OpPayload.AddTag(noteId, normalized))
    }

    fun removeTag(noteId: Timestamp, tag: String): Op? {
        val normalized = Workspace.normalizeTag(tag)
        val observed = noteOrThrow(noteId).tags.observedAdditions(normalized)
        if (observed.isEmpty()) return null
        return emit(OpPayload.RemoveTag(noteId, normalized, observed))
    }

    private fun noteOrThrow(noteId: Timestamp): NoteState =
        workspace.note(noteId) ?: throw IllegalArgumentException("Unknown note $noteId")

    private fun emit(payload: OpPayload): Op {
        // The sequence number is *derived* from the version vector, never cached: if peers hand
        // us back our own ops (e.g. after a restore from an old backup), we continue after them
        // instead of re-issuing a sequence number that already exists with different content.
        val op = Op(clock.now(), workspace.version[id] + 1, payload)
        workspace.apply(op)
        return op
    }
}

/** Lone surrogates cannot be encoded as UTF-8 and would silently become '?' on other replicas. */
internal fun String.hasLoneSurrogate(): Boolean {
    var i = 0
    while (i < length) {
        val c = this[i]
        if (Character.isHighSurrogate(c)) {
            if (i + 1 >= length || !Character.isLowSurrogate(this[i + 1])) return true
            i += 2
            continue
        }
        if (Character.isLowSurrogate(c)) return true
        i++
    }
    return false
}
