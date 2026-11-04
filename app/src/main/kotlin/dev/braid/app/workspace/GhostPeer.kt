package dev.braid.app.workspace

import dev.braid.crdt.CharId
import dev.braid.crdt.IdRange
import dev.braid.crdt.OpPayload
import dev.braid.crdt.Replica
import dev.braid.crdt.ReplicaId
import dev.braid.crdt.TextField
import dev.braid.crdt.Timestamp
import dev.braid.data.engine.WorkspaceEngine
import dev.braid.sync.InMemoryLink
import dev.braid.sync.InMemorySyncStore
import dev.braid.sync.SyncSession
import dev.braid.sync.WireMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * A simulated collaborator living inside the app, so the real-time collaboration can be
 * evaluated with a single phone.
 *
 * It is *not* a mock: the ghost is a full second replica with its own CRDT state, connected to
 * the real engine through the real [SyncSession], Noise handshake and encryption, over an
 * in-memory link instead of a socket. Everything it does reaches the engine exactly as a remote
 * peer's edits would: same code path, same caret transformation, same persistence.
 *
 * It follows the note you have open and types into it at its own cursor, with human-like
 * timing, typos and corrections, sometimes right next to your caret to show convergence.
 *
 * Each start uses a fresh replica id: the ghost's state is in memory only, and restarting
 * with an old id would re-issue sequence numbers the engine already holds (the exact invariant
 * [dev.braid.data.prefs.AppPreferences.replicaFor] protects on real devices).
 */
class GhostPeer(
    private val engine: WorkspaceEngine,
    private val psk: ByteArray,
    private val prologue: ByteArray,
    private val presence: PresenceHub,
    private val scope: CoroutineScope,
) {
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private var job: Job? = null
    private var ghostSession: SyncSession? = null
    private var localSession: SyncSession? = null
    @Volatile private var userNote: Timestamp? = null
    @Volatile private var userCaret: CharId? = null
    private val random = Random.Default
    private var ghostId: ReplicaId? = null

    fun start() {
        if (job != null) return
        val store = InMemorySyncStore(Replica(ReplicaId.random()))
        ghostId = store.localReplica
        val (localEnd, ghostEnd) = InMemoryLink.pair("you", "ghost", maxLatencyMillis = 40)
        val local = SyncSession(engine, engine.displayName(engine.replicaId).orEmpty(), presence::update)
        val ghost = SyncSession(store, NAME) { _, p -> userNote = p.noteId; userCaret = p.caret }
        localSession = local
        ghostSession = ghost
        _running.value = true
        job = scope.launch(Dispatchers.Default) {
            launch { local.run(localEnd, initiator = false, psk = psk, prologue = prologue) }
            launch { ghost.run(ghostEnd, initiator = true, psk = psk, prologue = prologue) }
            store.localOps { listOf(it.setProfile(NAME)) }
            behave(store, ghost)
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        localSession = null
        ghostSession = null
        _running.value = false
        ghostId?.let(presence::remove)
    }

    /** Forwards the user's caret so the ghost knows which note is open. */
    fun onLocalPresence(p: WireMessage.Presence) {
        localSession?.sendPresence(p)
    }

    private suspend fun behave(store: InMemorySyncStore, session: SyncSession) {
        var anchor: CharId? = null
        var anchorNote: Timestamp? = null
        while (scope.isActive) {
            delay(random.nextLong(1_200, 3_000))
            val noteId = userNote ?: continue
            if (store.read { it.workspace.note(noteId) } == null) continue // not synced yet

            if (anchorNote != noteId || anchor == null || random.nextInt(4) == 0) {
                anchor = store.read { r -> pickSpot(r, noteId) }
                anchorNote = noteId
            }
            val phrase = PHRASES.random(random)
            for ((i, ch) in phrase.withIndex()) {
                // A typo every now and then, corrected right away (exercises remote deletes).
                if (i > 3 && random.nextInt(30) == 0) {
                    val typo = typeChar(store, noteId, anchor, 'x' + random.nextInt(3))
                    session.sendPresence(caret(noteId, typo))
                    delay(random.nextLong(150, 350))
                    store.localOps { r -> listOfNotNull(r.deleteIds(noteId, TextField.BODY, listOf(IdRange(typo.ts, typo.offset, 1)))) }
                    session.sendPresence(caret(noteId, anchor))
                    delay(random.nextLong(100, 250))
                }
                anchor = typeChar(store, noteId, anchor, ch)
                session.sendPresence(caret(noteId, anchor))
                delay(if (ch == ' ') random.nextLong(90, 220) else random.nextLong(45, 140))
                if (userNote != noteId) break
            }
            if (random.nextInt(6) == 0) {
                store.localOps { r -> listOfNotNull(r.addTag(noteId, TAGS.random(random))) }
            }
        }
    }

    /** Usually the end of a paragraph; sometimes right next to the user's caret. */
    private fun pickSpot(r: Replica, noteId: Timestamp): CharId? {
        val body = r.workspace.note(noteId)!!.body
        val text = body.text
        if (text.isEmpty()) return null
        val nearUser = userCaret?.takeIf { random.nextInt(3) == 0 && body.contains(it) }
        if (nearUser != null) return nearUser
        val breaks = text.indices.filter { text[it] == '\n' } + text.length
        val idx = breaks.random(random)
        return body.anchorAt(idx)
    }

    private suspend fun typeChar(store: InMemorySyncStore, noteId: Timestamp, after: CharId?, ch: Char): CharId {
        val op = store.localOps { r -> listOf(r.insertAfter(noteId, TextField.BODY, after, ch.toString())) }.single()
        return (op.payload as OpPayload.InsertText).firstCharId(op.id)
    }

    private fun caret(noteId: Timestamp, anchor: CharId?) =
        WireMessage.Presence(noteId = noteId, field = TextField.BODY, caret = anchor, caretAtStart = anchor == null)

    companion object {
        const val NAME = "Ghost"
        val TAGS = listOf("ghost-was-here", "crdt", "idea")
        val PHRASES = listOf(
            " Concurrent edits never conflict here.",
            " (typed by a second replica, merged by RGA)",
            " Try typing right where my cursor is!",
            " Every keystroke is an op with a hybrid logical clock.",
            " Go offline, edit, come back: it all converges.",
            " Undo only reverts your own changes, not mine.",
            "\n- idea: scrub the history slider",
        )
    }
}
