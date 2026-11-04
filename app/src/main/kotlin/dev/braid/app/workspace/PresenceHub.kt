package dev.braid.app.workspace

import dev.braid.crdt.ReplicaId
import dev.braid.sync.WireMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class RemotePresence(val replica: ReplicaId, val presence: WireMessage.Presence, val receivedAtMillis: Long)

/**
 * Ephemeral collaborator cursors. Deliberately *outside* the CRDT: presence is high-frequency,
 * worthless after a few seconds, and must never be persisted or replayed into history.
 */
class PresenceHub(private val clock: () -> Long = System::currentTimeMillis) {
    private val _peers = MutableStateFlow<Map<ReplicaId, RemotePresence>>(emptyMap())
    val peers: StateFlow<Map<ReplicaId, RemotePresence>> = _peers.asStateFlow()

    fun update(replica: ReplicaId, p: WireMessage.Presence) {
        _peers.update { it + (replica to RemotePresence(replica, p, clock())) }
    }

    fun remove(replica: ReplicaId) = _peers.update { it - replica }

    fun clear() { _peers.value = emptyMap() }

    /** Drops cursors not refreshed for [STALE_MILLIS] (peer went away without closing cleanly). */
    fun prune() {
        val now = clock()
        _peers.update { m -> m.filterValues { now - it.receivedAtMillis < STALE_MILLIS } }
    }

    companion object {
        const val STALE_MILLIS = 60_000L
    }
}
