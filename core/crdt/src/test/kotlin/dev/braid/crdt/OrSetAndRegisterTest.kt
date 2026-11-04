package dev.braid.crdt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OrSetAndRegisterTest {
    private val clock = FakeClock()

    @Test
    fun `concurrent add and remove of the same tag resolves to add-wins`() {
        val a = replica(1, clock); val b = replica(2, clock)
        val create = a.createNote(); b.receive(create)
        val id = create.id
        b.receive(a.addTag(id, "work")!!)

        val removeOnA = a.removeTag(id, "work")!!
        val reAddOnB = b.addTag(id, "#Work") // normalized duplicate -> no-op on B
        val addOnB = b.addTag(id, "urgent")!!
        assertEquals(null, reAddOnB)

        a.receive(addOnB); b.receive(removeOnA)
        assertEquals(setOf("urgent"), a.workspace.note(id)!!.tags.elements)
        assertEquals(a.workspace.fingerprint(), b.workspace.fingerprint())

        // Truly concurrent: A removes "urgent" while B re-tags it (remove + fresh add).
        val removeOnA2 = a.removeTag(id, "urgent")!!
        val bOps = listOf(b.removeTag(id, "urgent")!!, b.addTag(id, "urgent")!!)
        a.receiveAll(bOps); b.receive(removeOnA2)
        assertTrue("urgent" in a.workspace.note(id)!!.tags, "B's unobserved addition must survive")
        assertEquals(a.workspace.fingerprint(), b.workspace.fingerprint())
    }

    @Test
    fun `last writer wins on registers regardless of delivery order`() {
        val clockA = FakeClock(1_000); val clockB = FakeClock(1_000)
        val a = replica(1, clockA); val b = replica(2, clockB)
        val create = a.createNote(); b.receive(create)
        clockA.now = 2_000; val pinA = a.setRegister(create.id, Registers.PINNED, "true")!!
        clockB.now = 3_000; val pinB = b.setRegister(create.id, Registers.PINNED, "false")
        // B's note was unpinned already ("" != "false") so an op is emitted.
        a.receive(pinB!!); b.receive(pinA)
        assertEquals("false", a.workspace.note(create.id)!!.register(Registers.PINNED))
        assertEquals("false", b.workspace.note(create.id)!!.register(Registers.PINNED))
    }
}
