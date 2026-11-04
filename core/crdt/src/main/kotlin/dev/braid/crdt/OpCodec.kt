package dev.braid.crdt

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.protobuf.ProtoBuf

/**
 * Binary codec for ops, used both for persistence (Room BLOB column) and the wire.
 *
 * Protocol Buffers via kotlinx.serialization rather than JSON: ~3-4x smaller for typing-heavy
 * logs (most ops are single-char inserts where JSON field names would dominate), no reflection,
 * and field numbers ([kotlinx.serialization.protobuf.ProtoNumber]) give forward/backward
 * compatibility when fields are added.
 */
@OptIn(ExperimentalSerializationApi::class)
object OpCodec {
    private val proto = ProtoBuf { encodeDefaults = false }
    private val listSerializer = ListSerializer(Op.serializer())

    fun encode(op: Op): ByteArray = proto.encodeToByteArray(Op.serializer(), op)
    fun decode(bytes: ByteArray): Op = proto.decodeFromByteArray(Op.serializer(), bytes)

    fun encodeAll(ops: List<Op>): ByteArray = proto.encodeToByteArray(listSerializer, ops)
    fun decodeAll(bytes: ByteArray): List<Op> = proto.decodeFromByteArray(listSerializer, bytes)

    fun encodeVersion(v: VersionVector): ByteArray = proto.encodeToByteArray(VersionVector.serializer(), v)
    fun decodeVersion(bytes: ByteArray): VersionVector = proto.decodeFromByteArray(VersionVector.serializer(), bytes)
}
