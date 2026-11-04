package dev.braid.app.workspace

import dev.braid.crdt.ReplicaId
import dev.braid.crdt.Timestamp

/** Note ids are HLC timestamps; navigation needs them as strings. */
fun Timestamp.toKey(): String = "$millis.$counter.$replica"

fun String.toTimestamp(): Timestamp {
    val (m, c, r) = split('.')
    return Timestamp(m.toLong(), c.toInt(), ReplicaId.parse(r))
}

fun ReplicaId.short(): String = toString().takeLast(4)
