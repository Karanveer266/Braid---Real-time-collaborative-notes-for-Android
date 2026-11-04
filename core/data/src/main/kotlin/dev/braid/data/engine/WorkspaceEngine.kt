package dev.braid.data.engine

import dev.braid.crdt.Admission
import dev.braid.crdt.ClockDriftException
import dev.braid.crdt.Effect
import dev.braid.crdt.NoteTimeline
import dev.braid.crdt.Op
import dev.braid.crdt.Registers
import dev.braid.crdt.Replica
import dev.braid.crdt.ReplicaId
import dev.braid.crdt.TextField
import dev.braid.crdt.Timestamp
import dev.braid.crdt.UndoStack
import dev.braid.crdt.VersionVector
import dev.braid.crdt.Workspace
import dev.braid.data.db.OpLog
import dev.braid.sync.SyncStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/**
 * The heart of the app: one replica of one workspace.
 *
 * ## Threading model: "state thread" confinement
 * All CRDT state (the [Workspace], [Replica], undo stacks) is owned by a single thread,
 * [stateDispatcher], which in production is the **main thread**. That sounds surprising, so the
 * reasoning (more in details.md):
 *
 *  - The text field and the CRDT must agree *exactly* at the moment a keystroke is diffed.
 *    If the CRDT lived on a background thread, a remote edit could land between "user typed" and
 *    "diff computed", and the diff would silently delete the remote text. Confining both to one
 *    thread makes this race impossible by construction, with zero locks.
 *  - Local CRDT operations cost microseconds (one block insert in an RGA), far below a frame.
 *  - Expensive work is kept off this thread: initial replay runs on [computeDispatcher] into a
 *    private Workspace that is then handed over; remote batches are applied in slices with
 *    [yield] in between; persistence is asynchronous (below).
 *
 * This is the same model Yjs/Automerge use in browsers, where JS is single-threaded.
 *
 * ## Durability: asynchronous group commit
 * New ops (local or remote) go into an in-order queue drained by a writer coroutine on
 * [ioDispatcher], which appends everything queued so far in *one* transaction. Typing therefore
 * never waits for disk, and a burst of 20 keystrokes costs one SQLite commit, not 20.
 * [durableVersion] tracks what has reached disk and [opsMissingFrom] serves **only durable ops**,
 * so a peer can never learn an op that we could lose in a crash (which would otherwise let us
 * re-issue the same sequence number with different content).
 *
 * ## Read models
 * [notes] is a lazy projection rebuilt on the state thread from [revision], only while observed.
 * Screens that need keystroke-exact synchronous updates (the editor) register a [TextListener].
 */
class WorkspaceEngine(
    private val log: OpLog,
    val replicaId: ReplicaId,
    private val scope: CoroutineScope,
    private val stateDispatcher: CoroutineDispatcher,
    private val ioDispatcher: CoroutineDispatcher,
    private val computeDispatcher: CoroutineDispatcher = ioDispatcher,
    private val wallClock: () -> Long = System::currentTimeMillis,
) : SyncStore {

    fun interface TextListener {
        /** Called on the state thread, synchronously, after a *remote* op changed a note. */
        fun onRemoteEffect(effect: Effect)
    }

    private lateinit var replica: Replica
    private val undoStacks = HashMap<Timestamp, UndoStack>()
    private val listeners = LinkedHashSet<TextListener>()

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val _revision = MutableStateFlow(0L)
    /** Increments on every state change (local or remote). Cheap to observe. */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    /**
     * Note list projection, computed lazily on the state thread and only while collected. The
     * list screen collects it with lifecycle awareness, so while you type in the editor (list
     * screen stopped) no projection work happens at all; on return it is rebuilt once.
     */
    val notes: Flow<List<NoteSummary>> = revision
        .map { if (_ready.value) buildSummaries() else emptyList() }
        .flowOn(stateDispatcher)

    private val _members = MutableStateFlow<Map<ReplicaId, String>>(emptyMap())
    val members: StateFlow<Map<ReplicaId, String>> = _members.asStateFlow()

    // -------------------------------------------------------------- durability
    private val writeQueue = Channel<List<Op>>(Channel.UNLIMITED)
    private val durableLock = Mutex()
    @Volatile private var durableVersion = VersionVector.EMPTY
    @Volatile private var durableCount = 0L
    private val _commits = MutableSharedFlow<Unit>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val commits: Flow<Unit> = _commits.asSharedFlow()
    private var loadMillis = 0L

    /** Remote ops that arrived before their causal predecessors (should be rare, see integrateRemote). */
    private val parked = ArrayList<Op>()

    override val localReplica: ReplicaId get() = replicaId

    /**
     * Loads the op log and rebuilds state. Replay happens on [computeDispatcher]; the finished
     * workspace is then published to the state thread. Must be called once before anything else.
     */
    suspend fun open() {
        val t0 = System.nanoTime()
        val (ws, count) = withContext(computeDispatcher) {
            val ops = log.loadAll()
            val ws = Workspace()
            ops.forEach { ws.apply(it) }
            ws to ops.size.toLong()
        }
        loadMillis = (System.nanoTime() - t0) / 1_000_000
        withContext(stateDispatcher) {
            replica = Replica(replicaId, ws, wallClock)
            durableVersion = ws.version
            durableCount = count
            _ready.value = true
            publish()
        }
        scope.launch(ioDispatcher) { writerLoop() }
    }

    private val _storageError = MutableStateFlow<String?>(null)
    /** Non-null while the op log cannot be written (e.g. disk full). Surfaced in the UI. */
    val storageError: StateFlow<String?> = _storageError.asStateFlow()

    /**
     * Drains the queue in group commits. A failed transaction is *retried* (same batch, same
     * order, exponential backoff up to [MAX_WRITE_BACKOFF_MILLIS]) rather than killing the loop:
     * a dead writer would silently freeze durability and sync for the rest of the session.
     * Room inserts are idempotent per (author, seq), so a retry after a partial failure is safe.
     */
    private suspend fun writerLoop() {
        for (first in writeQueue) {
            val batch = ArrayList(first)
            while (true) batch += (writeQueue.tryReceive().getOrNull() ?: break) // group commit
            var backoff = 100L
            while (true) {
                try {
                    log.append(batch)
                    break
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _storageError.value = e.message ?: e.javaClass.simpleName
                    kotlinx.coroutines.delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(MAX_WRITE_BACKOFF_MILLIS)
                }
            }
            _storageError.value = null
            durableLock.withLock {
                durableVersion = batch.fold(durableVersion) { v, op -> v.with(op.author, op.seq) }
                durableCount += batch.size
            }
            _commits.tryEmit(Unit)
        }
    }

    // -------------------------------------------------------------- state-thread API (UI)

    fun addTextListener(l: TextListener) { listeners += l }
    fun removeTextListener(l: TextListener) { listeners -= l }

    val workspace: Workspace get() = replica.workspace

    fun note(id: Timestamp) = replica.workspace.note(id)

    fun displayName(replica: ReplicaId): String? = this.replica.workspace.displayName(replica)

    fun undoStack(noteId: Timestamp): UndoStack =
        undoStacks.getOrPut(noteId) { UndoStack(replica, noteId, wallClock) }

    fun createNote(initialTitle: String = ""): Timestamp {
        val op = replica.createNote()
        commitLocal(listOf(op))
        if (initialTitle.isNotEmpty()) editText(op.id, TextField.TITLE, initialTitle)
        return op.id
    }

    /** Records a text edit made in the UI. Goes through the undo stack so it can be undone. */
    fun editText(noteId: Timestamp, field: TextField, newText: String, caretHint: Int = -1) =
        commitLocal(undoStack(noteId).edit(field, newText, caretHint))

    fun undo(noteId: Timestamp): UndoStack.Outcome? = undoStack(noteId).undo()?.also { commitLocal(it.ops) }
    fun redo(noteId: Timestamp): UndoStack.Outcome? = undoStack(noteId).redo()?.also { commitLocal(it.ops) }

    fun setPinned(noteId: Timestamp, pinned: Boolean) =
        commitLocal(listOfNotNull(replica.setRegister(noteId, Registers.PINNED, pinned.toString())))

    fun setColor(noteId: Timestamp, color: String) =
        commitLocal(listOfNotNull(replica.setRegister(noteId, Registers.COLOR, color)))

    fun setTrashed(noteId: Timestamp, trashed: Boolean) =
        commitLocal(listOfNotNull(replica.setRegister(noteId, Registers.TRASHED, trashed.toString())))

    fun addTag(noteId: Timestamp, tag: String) = commitLocal(listOfNotNull(replica.addTag(noteId, tag)))
    fun removeTag(noteId: Timestamp, tag: String) = commitLocal(listOfNotNull(replica.removeTag(noteId, tag)))

    fun setProfile(name: String) {
        if (replica.workspace.displayName(replicaId) == name.trim()) return
        commitLocal(listOf(replica.setProfile(name)))
    }

    /** Restores a historical version by *editing forward* to it, so history is never rewritten. */
    fun restoreVersion(noteId: Timestamp, title: String, body: String) {
        editText(noteId, TextField.TITLE, title)
        editText(noteId, TextField.BODY, body)
    }

    private fun commitLocal(ops: List<Op>) {
        if (ops.isEmpty()) return
        writeQueue.trySend(ops)
        publish()
    }

    // -------------------------------------------------------------- history & diagnostics

    /** Loads a note's full history from disk for the time-travel screen. */
    suspend fun timeline(noteId: Timestamp): NoteTimeline = withContext(computeDispatcher) {
        NoteTimeline(noteId, log.loadAll())
    }

    fun stats(): EngineStats {
        val notes = replica.workspace.allNotes
        return EngineStats(
            opCount = replica.workspace.version.totalOps,
            durableOps = durableCount,
            notes = notes.size,
            replicas = replica.workspace.version.size,
            blocks = notes.sumOf { it.title.blockCount + it.body.blockCount },
            tombstonedChars = notes.sumOf { it.title.tombstoneCount + it.body.tombstoneCount },
            visibleChars = notes.sumOf { it.title.length + it.body.length },
            loadMillis = loadMillis,
        )
    }

    // -------------------------------------------------------------- SyncStore (any thread)

    override suspend fun version(): VersionVector = durableVersion

    override suspend fun opsMissingFrom(remote: VersionVector): List<Op> {
        // Snapshot durability *before* querying: the query can only return durable rows anyway.
        if (!durableVersion.hasOpsMissingFrom(remote)) return emptyList()
        return log.opsMissingFrom(remote)
    }

    /**
     * Applies remote ops on the state thread in slices of [APPLY_SLICE] (yielding between slices
     * so a 50 000-op initial sync never blocks a frame for long), then queues them for disk.
     *
     * If a peer's clock is too far ahead, the ops applied before the offending one are still
     * committed and the [ClockDriftException] propagates so the session is closed.
     */
    override suspend fun integrateRemote(ops: List<Op>) {
        if (ops.isEmpty()) return
        withContext(stateDispatcher) {
            val applied = ArrayList<Op>(ops.size)
            try {
                for ((i, op) in ops.withIndex()) {
                    integrateOne(op, applied)
                    if (i % APPLY_SLICE == APPLY_SLICE - 1) {
                        flushApplied(applied)
                        yield()
                    }
                }
                drainParked(applied)
            } finally {
                flushApplied(applied)
            }
        }
    }

    private fun integrateOne(op: Op, applied: MutableList<Op>) {
        when (replica.workspace.check(op)) {
            Admission.DUPLICATE -> Unit
            Admission.GAP -> {
                // Never drop silently: the sending session already counts this op as delivered.
                // Failing closes the session; the reconnect's Summary exchange recomputes the
                // exact delta, so nothing is lost.
                if (parked.size >= MAX_PARKED) throw ParkOverflowException(parked.size)
                parked += op
            }
            Admission.READY -> {
                val effect = replica.receive(op) // may throw ClockDriftException -> session closes
                applied += op
                if (effect != Effect.None) listeners.toList().forEach { it.onRemoteEffect(effect) }
            }
        }
    }

    private fun drainParked(applied: MutableList<Op>) {
        var progress = true
        while (progress && parked.isNotEmpty()) {
            progress = false
            val it = parked.sortedBy { it.id }
            parked.clear()
            for (op in it) {
                val before = applied.size
                integrateOne(op, applied)
                if (applied.size > before) progress = true
            }
        }
    }

    private fun flushApplied(applied: MutableList<Op>) {
        if (applied.isEmpty()) return
        writeQueue.trySend(ArrayList(applied))
        applied.clear()
        publish()
    }

    // -------------------------------------------------------------- projections

    private fun publish() {
        _revision.value++
        _members.value = replica.workspace.members
    }

    private fun buildSummaries(): List<NoteSummary> =
        replica.workspace.allNotes
            .asSequence()
            .filterNot { it.isTrashed }
            .map { n ->
                NoteSummary(
                    id = n.id,
                    title = n.title.text,
                    snippet = n.body.text.take(SNIPPET),
                    tags = n.tags.elements.toList(),
                    pinned = n.isPinned,
                    color = n.color,
                    updatedAt = n.updatedAt,
                    contributors = n.contributors.toList(),
                )
            }
            .sortedWith(compareByDescending<NoteSummary> { it.pinned }.thenByDescending { it.updatedAt })
            .toList()

    companion object {
        const val APPLY_SLICE = 256
        const val SNIPPET = 160
        const val MAX_PARKED = 10_000
        const val MAX_WRITE_BACKOFF_MILLIS = 5_000L
    }
}

class ParkOverflowException(count: Int) : IllegalStateException("$count out-of-order ops parked; resync required")
