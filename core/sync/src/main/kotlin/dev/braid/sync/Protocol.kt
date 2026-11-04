package dev.braid.sync

import dev.braid.crdt.CharId
import dev.braid.crdt.Op
import dev.braid.crdt.ReplicaId
import dev.braid.crdt.TextField
import dev.braid.crdt.Timestamp
import dev.braid.crdt.VersionVector
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * Wire protocol, version [PROTOCOL_VERSION].
 *
 * ```
 *  A                                   B
 *  |-- Noise msg 1 (psk, e, Hello) --->|
 *  |<-- Noise msg 2 (e, ee, Hello) ----|        authenticated + forward secret from here on
 *  |-- Summary(versionVector) -------->|
 *  |<-------- Summary(versionVector) --|
 *  |-- Ops(delta for B) -------------->|        delta = every op whose seq > B's vector entry
 *  |<-------------- Ops(delta for A) --|
 *  |<======= live Ops / Presence =====>|        same delta rule, triggered by new commits
 * ```
 *
 * The protocol is symmetric (no client/server roles after the handshake) and idempotent, so a
 * dropped connection is recovered from simply by reconnecting: the Summary exchange recomputes
 * the exact delta. There is no per-op acknowledgement because version vectors make it unnecessary.
 */
const val PROTOCOL_VERSION: Int = 1

/** Sent inside the encrypted Noise handshake payloads. */
@Serializable
data class Hello(
    @ProtoNumber(1) val protocolVersion: Int,
    @ProtoNumber(2) val replica: ReplicaId,
    @ProtoNumber(3) val displayName: String,
)

@Serializable
sealed interface WireMessage {
    @Serializable
    @SerialName("summary")
    data class Summary(@ProtoNumber(1) val version: VersionVector) : WireMessage

    @Serializable
    @SerialName("ops")
    data class Ops(@ProtoNumber(1) val ops: List<Op>) : WireMessage

    /**
     * Ephemeral cursor/selection of a collaborator. Not persisted, not part of the CRDT.
     * Anchors are character ids so they resolve correctly even if text shifted in between.
     */
    @Serializable
    @SerialName("presence")
    data class Presence(
        @ProtoNumber(1) val noteId: Timestamp? = null,
        @ProtoNumber(2) val field: TextField = TextField.BODY,
        @ProtoNumber(3) val caret: CharId? = null,
        @ProtoNumber(4) val caretAtStart: Boolean = false,
        @ProtoNumber(5) val selectionAnchor: CharId? = null,
        @ProtoNumber(6) val hasSelection: Boolean = false,
    ) : WireMessage

    @Serializable
    @SerialName("bye")
    data class Bye(@ProtoNumber(1) val reason: String) : WireMessage
}

@OptIn(ExperimentalSerializationApi::class)
object WireCodec {
    private val proto = ProtoBuf { encodeDefaults = false }
    fun encode(m: WireMessage): ByteArray = proto.encodeToByteArray(WireMessage.serializer(), m)
    fun decode(b: ByteArray): WireMessage = proto.decodeFromByteArray(WireMessage.serializer(), b)
    fun encodeHello(h: Hello): ByteArray = proto.encodeToByteArray(Hello.serializer(), h)
    fun decodeHello(b: ByteArray): Hello = proto.decodeFromByteArray(Hello.serializer(), b)
}
