package dev.braid.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.braid.crdt.Replica
import dev.braid.crdt.ReplicaId
import dev.braid.crdt.TextField
import dev.braid.crdt.VersionVector
import dev.braid.data.db.BraidDatabase
import dev.braid.data.db.RoomOpLog
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** Runs the real Room/SQLite op log (Robolectric's native SQLite), not a fake. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RoomOpLogTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), BraidDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val log = RoomOpLog(db)

    @After fun close() = db.close()

    @Test
    fun `append is idempotent and replay order is causal`() = runBlocking {
        val a = Replica(ReplicaId(1)); val b = Replica(ReplicaId(2))
        val create = a.createNote(); b.receive(create)
        val opsA = a.editText(create.id, TextField.BODY, "hello")
        val opsB = b.editText(create.id, TextField.TITLE, "title")
        log.append(listOf(create) + opsA + opsB)
        log.append(opsA) // duplicate delivery
        assertEquals(1L + opsA.size + opsB.size, log.count())

        val fresh = Replica(ReplicaId(3))
        log.loadAll().forEach { fresh.receive(it) }
        a.receive(opsB.single())
        assertEquals(a.workspace.fingerprint(), fresh.workspace.fingerprint())
    }

    @Test
    fun `opsMissingFrom returns the exact delta per author`() = runBlocking {
        val a = Replica(ReplicaId(1))
        val id = a.createNote().also { log.append(listOf(it)) }.id
        repeat(10) { i -> log.append(a.editText(id, TextField.BODY, "x".repeat(i + 1))) }
        val remote = VersionVector.EMPTY.with(ReplicaId(1), 6)
        val delta = log.opsMissingFrom(remote)
        assertEquals((7L..11L).toList(), delta.map { it.seq })
    }

    @Test
    fun `paged loading returns every op`() = runBlocking {
        val a = Replica(ReplicaId(1))
        val id = a.createNote().also { log.append(listOf(it)) }.id
        val ops = (1..12_000).map { a.insertAt(id, TextField.BODY, 0, "a") }
        log.append(ops)
        assertEquals(12_001, log.loadAll().size)
    }
}
