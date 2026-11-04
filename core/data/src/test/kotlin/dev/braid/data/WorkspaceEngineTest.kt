package dev.braid.data

import dev.braid.crdt.Effect
import dev.braid.crdt.Replica
import dev.braid.crdt.ReplicaId
import dev.braid.crdt.TextField
import dev.braid.crdt.VersionVector
import dev.braid.data.db.InMemoryOpLog
import dev.braid.data.engine.WorkspaceEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceEngineTest {

    /**
     * Engines run in a *foreground* child scope (cancelled at the end of each test) rather than
     * backgroundScope, because advanceUntilIdle() deliberately does not wait for background work
     * and we need the group-commit writer to actually run.
     */
    private val engineJob = Job()

    private fun TestScope.engine(log: InMemoryOpLog, id: Long = 1): WorkspaceEngine {
        val d = StandardTestDispatcher(testScheduler)
        return WorkspaceEngine(log, ReplicaId(id), CoroutineScope(coroutineContext + engineJob), d, d, d)
    }

    private fun test(body: suspend TestScope.() -> Unit) = runTest {
        try { body() } finally { engineJob.cancel() }
    }

    @Test
    fun `keystroke bursts are group committed and only durable ops are served`() = test {
        val log = InMemoryOpLog()
        val e = engine(log)
        e.open()
        val id = e.createNote()
        var text = ""
        for (c in "hello world") { text += c; e.editText(id, TextField.BODY, text) }

        // Before the writer runs, nothing is durable, so nothing is offered to peers.
        assertTrue(e.opsMissingFrom(VersionVector.EMPTY).isEmpty())
        advanceUntilIdle()
        assertEquals(12, e.opsMissingFrom(VersionVector.EMPTY).size)
        assertEquals(1, log.appendCalls, "12 ops queued while writer idle -> one transaction")
    }

    @Test
    fun `state is rebuilt identically from the log after restart`() = test {
        val log = InMemoryOpLog()
        val e1 = engine(log)
        e1.open()
        val id = e1.createNote("Groceries")
        e1.editText(id, TextField.BODY, "milk, eggs")
        e1.addTag(id, "home")
        e1.setPinned(id, true)
        advanceUntilIdle()

        val e2 = engine(log)
        e2.open()
        assertEquals(e1.workspace.fingerprint(), e2.workspace.fingerprint())
        // The restarted replica continues its sequence and clock without collisions.
        e2.editText(id, TextField.BODY, "milk, eggs, bread")
        advanceUntilIdle()
        assertEquals(e2.workspace.version[ReplicaId(1)], log.count())
    }

    @Test
    fun `remote ops notify text listeners and out of order batches are parked`() = test {
        val e = engine(InMemoryOpLog())
        e.open()
        val peer = Replica(ReplicaId(2))
        val create = peer.createNote()
        val edits = peer.editText(create.id, TextField.BODY, "abc") + peer.editText(create.id, TextField.BODY, "abcd")

        val effects = mutableListOf<Effect>()
        e.addTextListener { effects += it }
        // Deliver out of order: the later ops first. They must be parked, not dropped.
        e.integrateRemote(edits)
        assertEquals(null, e.note(create.id))
        e.integrateRemote(listOf(create))
        assertEquals("abcd", e.note(create.id)!!.body.text)
        assertTrue(effects.any { it is Effect.TextInserted })
        advanceUntilIdle()
        assertEquals(3, e.opsMissingFrom(VersionVector.EMPTY).size)
    }

    @Test
    fun `notes projection hides trashed notes and sorts pinned first`() = test {
        val e = engine(InMemoryOpLog())
        e.open()
        val a = e.createNote("a")
        val b = e.createNote("b")
        val c = e.createNote("c")
        e.setPinned(a, true)
        e.setTrashed(b, true)
        val notes = e.notes.first()
        assertEquals(listOf("a", "c"), notes.map { it.title })
        assertEquals(c, notes.last().id)
    }

    @Test
    fun `a failing disk write is retried instead of stopping persistence`() = test {
        val log = object : dev.braid.data.db.OpLog by InMemoryOpLog() {
            val inner = InMemoryOpLog()
            var failures = 2
            override suspend fun append(ops: List<dev.braid.crdt.Op>) {
                if (failures-- > 0) throw java.io.IOException("disk full")
                inner.append(ops)
            }
            override suspend fun opsMissingFrom(remote: VersionVector) = inner.opsMissingFrom(remote)
        }
        val d = StandardTestDispatcher(testScheduler)
        val e = WorkspaceEngine(log, ReplicaId(1), CoroutineScope(coroutineContext + engineJob), d, d, d)
        e.open()
        e.createNote("x")
        runCurrent()
        assertEquals("disk full", e.storageError.value)
        advanceUntilIdle()
        assertEquals(null, e.storageError.value)
        assertEquals(2, e.opsMissingFrom(VersionVector.EMPTY).size)
    }
}
