package dev.braid.crdt

/**
 * Materialised state of one note: two collaborative texts, LWW attributes and an OR-Set of tags.
 */
class NoteState internal constructor(val id: Timestamp) {
    val title = RgaText()
    val body = RgaText()
    val tags = OrSet()
    internal val registers = HashMap<String, LwwRegister<String>>()

    /** Timestamp of the newest op that touched this note, used for sorting "recently edited". */
    var updatedAt: Timestamp = id
        internal set

    /** Every replica that ever contributed an op to this note. */
    val contributors: MutableSet<ReplicaId> = linkedSetOf(id.replica)

    fun text(field: TextField): RgaText = when (field) {
        TextField.TITLE -> title
        TextField.BODY -> body
    }

    fun register(key: String): String? = registers[key]?.value
    val isPinned: Boolean get() = register(Registers.PINNED) == "true"
    val isTrashed: Boolean get() = register(Registers.TRASHED) == "true"
    val color: String? get() = register(Registers.COLOR)
}

/**
 * What changed as the result of applying one op. The UI layer consumes these to update screens
 * and to transform the local caret when a *remote* edit lands in the note being edited.
 */
sealed interface Effect {
    val noteId: Timestamp?

    data object None : Effect { override val noteId: Timestamp? get() = null }
    data class NoteCreated(override val noteId: Timestamp) : Effect
    data class TextInserted(override val noteId: Timestamp, val field: TextField, val index: Int, val length: Int) : Effect
    data class TextDeleted(override val noteId: Timestamp, val field: TextField, val deletions: List<VisibleDeletion>) : Effect
    data class AttributeChanged(override val noteId: Timestamp) : Effect
    data class ProfileChanged(val replica: ReplicaId) : Effect { override val noteId: Timestamp? get() = null }
}

/**
 * The replicated root object: a workspace of notes plus member profiles.
 *
 * ## Guarantees
 * - **Convergence (strong eventual consistency):** any two workspaces that applied the same *set*
 *   of ops (in any causally consistent order) are in identical states. Verified by randomized
 *   multi-replica fuzz tests in `ConvergenceFuzzTest`.
 * - **Idempotence:** re-applying an op is a no-op, so at-least-once delivery is safe.
 * - **Causal delivery check:** [apply] refuses an op whose author's previous op is missing
 *   (`seq` gap). The version vector therefore always describes a gap-free prefix per replica.
 *
 * Not thread-safe by design; the data layer confines it to a single-threaded "actor".
 */
class Workspace {
    private val notes = LinkedHashMap<Timestamp, NoteState>()
    private val profiles = HashMap<ReplicaId, LwwRegister<String>>()

    var version: VersionVector = VersionVector.EMPTY
        private set

    /** Highest HLC timestamp integrated, so a restarted replica can restore its clock. */
    var maxTimestamp: Timestamp? = null
        private set

    val noteIds: Collection<Timestamp> get() = notes.keys
    val allNotes: Collection<NoteState> get() = notes.values
    fun note(id: Timestamp): NoteState? = notes[id]

    fun displayName(replica: ReplicaId): String? = profiles[replica]?.value
    val members: Map<ReplicaId, String> get() = profiles.mapValues { it.value.value }

    /** Classifies an incoming op relative to the current state. */
    fun check(op: Op): Admission {
        val have = version[op.author]
        return when {
            op.seq <= have -> Admission.DUPLICATE
            op.seq == have + 1 -> Admission.READY
            else -> Admission.GAP
        }
    }

    /**
     * Applies [op]. Ops must be applied in a causal order (always true when ops are applied in
     * (author seq) order per author and HLC order across authors — what the sync engine does).
     */
    fun apply(op: Op): Effect {
        when (check(op)) {
            Admission.DUPLICATE -> return Effect.None
            Admission.GAP -> throw MissingDependencyException(
                "op ${op.id} has seq ${op.seq} but we only have ${version[op.author]} from ${op.author}",
            )
            Admission.READY -> Unit
        }
        val effect = integrate(op)
        version = version.with(op.author, op.seq)
        if (maxTimestamp.let { it == null || op.id > it }) maxTimestamp = op.id
        return effect
    }

    /**
     * Integrates without the per-author sequence check. Only for [NoteTimeline], which replays a
     * *filtered* (single-note) but still causally ordered subset of the log.
     */
    internal fun replay(op: Op): Effect = integrate(op)

    private fun integrate(op: Op): Effect = when (val p = op.payload) {
        is OpPayload.CreateNote -> {
            notes.getOrPut(op.id) { NoteState(op.id) }
            Effect.NoteCreated(op.id)
        }
        is OpPayload.InsertText -> {
            val note = requireNote(p.noteId, op)
            val idx = note.text(p.field).integrateInsert(p.firstCharId(op.id), p.after, p.text)
            note.touch(op)
            if (idx < 0) Effect.None else Effect.TextInserted(note.id, p.field, idx, p.text.length)
        }
        is OpPayload.DeleteText -> {
            val note = requireNote(p.noteId, op)
            val dels = note.text(p.field).integrateDelete(p.ranges)
            note.touch(op)
            Effect.TextDeleted(note.id, p.field, dels)
        }
        is OpPayload.SetRegister -> {
            val note = requireNote(p.noteId, op)
            note.registers.getOrPut(p.key) { LwwRegister("") }.set(p.value, op.id)
            note.touch(op)
            Effect.AttributeChanged(note.id)
        }
        is OpPayload.AddTag -> {
            val note = requireNote(p.noteId, op)
            note.tags.add(normalizeTag(p.tag), op.id)
            note.touch(op)
            Effect.AttributeChanged(note.id)
        }
        is OpPayload.RemoveTag -> {
            val note = requireNote(p.noteId, op)
            note.tags.remove(normalizeTag(p.tag), p.observed)
            note.touch(op)
            Effect.AttributeChanged(note.id)
        }
        is OpPayload.SetProfile -> {
            profiles.getOrPut(op.author) { LwwRegister("") }.set(p.displayName, op.id)
            Effect.ProfileChanged(op.author)
        }
    }

    private fun requireNote(id: Timestamp, op: Op): NoteState =
        notes[id] ?: throw MissingDependencyException("op ${op.id} targets unknown note $id")

    private fun NoteState.touch(op: Op) {
        if (op.id > updatedAt) updatedAt = op.id
        contributors += op.author
    }

    /** Deterministic digest of the visible state; used by tests and the debug "convergence check". */
    fun fingerprint(): String = buildString {
        for (id in notes.keys.sorted()) {
            val n = notes.getValue(id)
            append(id).append('|').append(n.title.text).append('|').append(n.body.text).append('|')
            append(n.tags.elements.joinToString(",")).append('|')
            n.registers.toSortedMap().forEach { (k, v) -> append(k).append('=').append(v.value).append(';') }
            append('\n')
        }
        profiles.toSortedMap().forEach { (r, v) -> append(r).append(':').append(v.value).append('\n') }
    }

    companion object {
        fun normalizeTag(tag: String): String = tag.trim().lowercase().removePrefix("#").take(MAX_TAG_LENGTH)
        const val MAX_TAG_LENGTH: Int = 32
    }
}

enum class Admission { READY, DUPLICATE, GAP }
