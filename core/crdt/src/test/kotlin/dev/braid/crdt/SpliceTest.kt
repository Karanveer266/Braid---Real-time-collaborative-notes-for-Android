package dev.braid.crdt

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class SpliceTest {
    @Test
    fun `typing a repeated char uses caret hint`() {
        val s = Splice.between("aa", "aaa", caretHint = 1)
        assertEquals(Splice(0, 0, "a"), s)
    }

    @Test
    fun `replacement of selection`() {
        assertEquals(Splice(4, 5, "slow"), Splice.between("the quick fox", "the slow fox"))
    }

    @Test
    fun `random splices always reconstruct the new text`() {
        val rnd = Random(42)
        val alphabet = "ab😀 \n"
        repeat(5_000) {
            val old = buildString { repeat(rnd.nextInt(12)) { append(alphabet[rnd.nextInt(alphabet.length)]) } }
            val new = buildString { repeat(rnd.nextInt(12)) { append(alphabet[rnd.nextInt(alphabet.length)]) } }
            val s = Splice.between(old, new, caretHint = rnd.nextInt(-1, new.length + 1))
            assertEquals(new, s.applyTo(old), "old='$old' new='$new' splice=$s")
        }
    }
}
