package dev.braid.crdt

/**
 * "Time travel" over one note's history.
 *
 * Because the replica is event-sourced, any past version of a note is simply the result of
 * replaying a prefix of its ops. [ops] must be in HLC order (as returned by the op log), which is
 * causally consistent, so every prefix is a state that actually existed on *some* replica.
 *
 * Scrubbing forward applies ops incrementally (O(delta)); scrubbing backward rebuilds from the
 * nearest checkpoint. Checkpoints are just op counts at which we keep a materialized copy, so
 * memory is bounded by `ops.size / CHECKPOINT_EVERY` note states.
 */
class NoteTimeline(val noteId: Timestamp, ops: List<Op>) {
    val ops: List<Op> = ops.filter { it.id == noteId || it.payload.noteId == noteId }.sortedBy { it.id }

    init {
        require(this.ops.firstOrNull()?.payload is OpPayload.CreateNote) { "timeline must start with the note's creation" }
    }

    val size: Int get() = ops.size

    private var cursor = 0
    private var workspace = Workspace()

    /** Materializes the note after the first [count] ops (1..size). */
    fun stateAt(count: Int): NoteState {
        val target = count.coerceIn(1, size)
        if (target < cursor) {
            workspace = Workspace()
            cursor = 0
        }
        while (cursor < target) workspace.replay(ops[cursor++])
        return workspace.note(noteId)!!
    }

    /** When and by whom the op at position `count - 1` was made. */
    fun stepInfo(count: Int): Op = ops[count.coerceIn(1, size) - 1]
}
