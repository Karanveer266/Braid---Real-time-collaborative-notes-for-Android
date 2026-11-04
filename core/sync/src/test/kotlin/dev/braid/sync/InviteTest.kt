package dev.braid.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InviteTest {
    @Test
    fun `round trips including unicode names`() {
        val inv = Invite.create("Ünïcode 📒 notes / team")
        assertEquals(inv, Invite.parse(inv.toUri()).getOrThrow())
    }

    @Test
    fun `corrupted invites are rejected with a clear error`() {
        val uri = Invite.create("x").toUri()
        val broken = uri.replaceRange(25, 26, if (uri[25] == 'a') "b" else "a")
        val r = Invite.parse(broken)
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("checksum"))
    }

    @Test
    fun `toString never leaks the key`() {
        val inv = Invite.create("x")
        assertFalse(inv.toString().contains(java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(inv.psk)))
    }

    @Test
    fun `discovery tag differs per workspace and is stable`() {
        val a = Invite.create("a"); val b = Invite.create("b")
        assertEquals(a.discoveryTag, a.copy().discoveryTag)
        assertTrue(a.discoveryTag != b.discoveryTag)
    }
}
