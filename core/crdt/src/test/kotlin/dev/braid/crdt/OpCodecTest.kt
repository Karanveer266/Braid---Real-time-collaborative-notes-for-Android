package dev.braid.crdt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpCodecTest {
    @Test
    fun `every payload type round trips`() {
        val ts = Timestamp(1_700_000_000_000, 3, ReplicaId(0x7abcdef012345678))
        val payloads = listOf(
            OpPayload.CreateNote(),
            OpPayload.InsertText(ts, TextField.BODY, CharId(ts, 4), "héllo 😀", run = ts, offset = 9),
            OpPayload.InsertText(ts, TextField.TITLE, null, "x"),
            OpPayload.DeleteText(ts, TextField.BODY, listOf(IdRange(ts, 0, 3), IdRange(ts, 7, 1))),
            OpPayload.SetRegister(ts, Registers.PINNED, "true"),
            OpPayload.AddTag(ts, "work"),
            OpPayload.RemoveTag(ts, "work", listOf(ts)),
            OpPayload.SetProfile("Ada"),
        )
        payloads.forEachIndexed { i, p ->
            val op = Op(ts.copy(counter = i), i + 1L, p)
            assertEquals(op, OpCodec.decode(OpCodec.encode(op)))
        }
    }

    @Test
    fun `single keystroke ops are compact`() {
        val ts = Timestamp(1_700_000_000_000, 0, ReplicaId(0x7abcdef012345678))
        val op = Op(ts.copy(counter = 1), 42, OpPayload.InsertText(ts, TextField.BODY, CharId(ts, 9), "a", ts, 10))
        val size = OpCodec.encode(op).size
        // Budget guards against accidental format bloat (e.g. a field losing its ProtoNumber).
        assertTrue(size <= 120, "keystroke op is $size bytes")
    }

    @Test
    fun `version vector round trips`() {
        val v = VersionVector.EMPTY.with(ReplicaId(1), 5).with(ReplicaId(2), 9)
        assertEquals(v, OpCodec.decodeVersion(OpCodec.encodeVersion(v)))
    }
}
