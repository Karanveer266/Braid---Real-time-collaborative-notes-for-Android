package dev.braid.data.engine

import dev.braid.crdt.ReplicaId
import dev.braid.crdt.Timestamp

/** Immutable list-item projection of a note, safe to hand to Compose (stable, value equality). */
data class NoteSummary(
    val id: Timestamp,
    val title: String,
    val snippet: String,
    val tags: List<String>,
    val pinned: Boolean,
    val color: String?,
    val updatedAt: Timestamp,
    val contributors: List<ReplicaId>,
)

data class EngineStats(
    val opCount: Long,
    val durableOps: Long,
    val notes: Int,
    val replicas: Int,
    val blocks: Int,
    val tombstonedChars: Int,
    val visibleChars: Int,
    val loadMillis: Long,
)
