package dev.braid.crdt

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * The unit of replication. Every change a user makes becomes exactly one immutable [Op].
 *
 * - [id] is a Hybrid Logical Clock timestamp: globally unique, totally ordered and consistent
 *   with causality. Sorting any set of ops by [id] yields a valid causal delivery order.
 * - [seq] is the author's private, gap-free sequence number (1, 2, 3, ...). Together with
 *   [VersionVector] it lets two peers compute *exactly* which ops the other is missing.
 *
 * The author is `id.replica`; it is not repeated in the payload.
 */
@Serializable
data class Op(
    @ProtoNumber(1) val id: Timestamp,
    @ProtoNumber(2) val seq: Long,
    @ProtoNumber(3) val payload: OpPayload,
) {
    val author: ReplicaId get() = id.replica
    override fun toString(): String = "Op($id #$seq $payload)"
}

/** Which text field of a note an operation targets. */
@Serializable
enum class TextField {
    @ProtoNumber(1) TITLE,
    @ProtoNumber(2) BODY,
}

/** A contiguous run of character ids created by a single insert op: `(ts, offset) ..< (ts, offset + length)`. */
@Serializable
data class IdRange(
    @ProtoNumber(1) val ts: Timestamp,
    @ProtoNumber(2) val offset: Int,
    @ProtoNumber(3) val length: Int,
) {
    init {
        require(offset >= 0 && length > 0) { "Invalid range $offset+$length" }
    }
}

/**
 * Payloads are a closed (sealed) hierarchy so `when` over them is exhaustive: adding an op type
 * is a compile error everywhere it must be handled. Stable [SerialName]s and explicit
 * [ProtoNumber]s make the persisted/wire format evolvable independently of class names.
 */
@Serializable
sealed interface OpPayload {
    /** The note whose state this op changes (`null` for workspace-level ops). */
    val noteId: Timestamp?

    /** Creates a note. The note's id *is* this op's [Op.id], so ids need no separate UUID. */
    @Serializable
    @SerialName("note.create")
    data class CreateNote(@ProtoNumber(1) val schema: Int = 1) : OpPayload {
        override val noteId: Timestamp? get() = null
    }

    /**
     * RGA insert of [text] immediately after character [after] (`null` = start of the field).
     *
     * Character ids: normally `(op.id, 0..n)`. When the author keeps typing at the end of their
     * own most recent run, the op instead *extends* that run: [run] names the run and [offset]
     * the first new offset, so ids are `(run, offset..offset+n)`. This lets the text CRDT store a
     * whole typing session as a single block (Yjs-style run merging) even though every keystroke
     * is still its own durable, individually-synced op. Only the run's owner may extend it,
     * which keeps ids unique.
     */
    @Serializable
    @SerialName("text.insert")
    data class InsertText(
        @ProtoNumber(1) override val noteId: Timestamp,
        @ProtoNumber(2) val field: TextField,
        @ProtoNumber(3) val after: CharId? = null,
        @ProtoNumber(4) val text: String,
        @ProtoNumber(5) val run: Timestamp? = null,
        @ProtoNumber(6) val offset: Int = 0,
    ) : OpPayload {
        fun firstCharId(opId: Timestamp): CharId = if (run == null) CharId(opId, 0) else CharId(run, offset)
    }

    /** Tombstones the characters in [ranges]. Ranges reference ids, never positions. */
    @Serializable
    @SerialName("text.delete")
    data class DeleteText(
        @ProtoNumber(1) override val noteId: Timestamp,
        @ProtoNumber(2) val field: TextField,
        @ProtoNumber(3) val ranges: List<IdRange>,
    ) : OpPayload

    /** Last-writer-wins assignment of a small scalar attribute (pinned, color, trashed...). */
    @Serializable
    @SerialName("register.set")
    data class SetRegister(
        @ProtoNumber(1) override val noteId: Timestamp,
        @ProtoNumber(2) val key: String,
        @ProtoNumber(3) val value: String,
    ) : OpPayload

    /** OR-Set add. The op's own id is the unique "tag" of this addition. */
    @Serializable
    @SerialName("tag.add")
    data class AddTag(
        @ProtoNumber(1) override val noteId: Timestamp,
        @ProtoNumber(2) val tag: String,
    ) : OpPayload

    /** OR-Set remove: removes only the additions the author had *observed* (add-wins semantics). */
    @Serializable
    @SerialName("tag.remove")
    data class RemoveTag(
        @ProtoNumber(1) override val noteId: Timestamp,
        @ProtoNumber(2) val tag: String,
        @ProtoNumber(3) val observed: List<Timestamp>,
    ) : OpPayload

    /** Workspace-level: the author's display name, shown in authorship ("blame") views. */
    @Serializable
    @SerialName("member.profile")
    data class SetProfile(@ProtoNumber(1) val displayName: String) : OpPayload {
        override val noteId: Timestamp? get() = null
    }
}

/** Well-known register keys. Registers are open-ended strings so new attributes need no new op type. */
object Registers {
    const val PINNED = "pinned"
    const val COLOR = "color"
    const val TRASHED = "trashed"
}
