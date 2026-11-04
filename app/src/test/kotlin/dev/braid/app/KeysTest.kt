package dev.braid.app

import dev.braid.app.workspace.PresenceHub
import dev.braid.app.workspace.toKey
import dev.braid.app.workspace.toTimestamp
import dev.braid.crdt.ReplicaId
import dev.braid.crdt.Timestamp
import dev.braid.sync.WireMessage
import kotlin.test.Test
import kotlin.test.assertEquals

class KeysTest {
    @Test
    fun `note keys survive navigation round trip including max replica ids`() {
        listOf(ReplicaId(1), ReplicaId(Long.MAX_VALUE), ReplicaId.random()).forEach { r ->
            val ts = Timestamp(1_700_000_000_123, 42, r)
            assertEquals(ts, ts.toKey().toTimestamp())
        }
    }

    @Test
    fun `stale presence is pruned`() {
        var now = 0L
        val hub = PresenceHub { now }
        hub.update(ReplicaId(1), WireMessage.Presence())
        now += PresenceHub.STALE_MILLIS + 1
        hub.update(ReplicaId(2), WireMessage.Presence())
        hub.prune()
        assertEquals(setOf(ReplicaId(2)), hub.peers.value.keys)
    }
}
