package dev.braid.app.ui.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.braid.app.workspace.RemotePresence
import dev.braid.app.workspace.WorkspaceManager
import dev.braid.app.workspace.toTimestamp
import dev.braid.crdt.AuthorSpan
import dev.braid.crdt.Effect
import dev.braid.crdt.ReplicaId
import dev.braid.crdt.TextField
import dev.braid.data.editor.CollaborativeText
import dev.braid.data.editor.TextSnapshot
import dev.braid.data.engine.WorkspaceEngine
import dev.braid.sync.WireMessage
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject

/** A collaborator's caret resolved to a visible index in the current text. */
data class RemoteCaret(val replica: ReplicaId, val name: String, val field: TextField, val index: Int, val selectionFrom: Int?)

/**
 * Editor state holder. Runs entirely on the main thread, which is also the engine's state thread
 * (see WorkspaceEngine docs), so the text field, [CollaborativeText] bindings and CRDT are always
 * mutually consistent without locks.
 */
@HiltViewModel
class EditorViewModel @Inject constructor(
    savedState: SavedStateHandle,
    manager: WorkspaceManager,
) : ViewModel() {

    private val ws = requireNotNull(manager.active) { "editor opened without workspace" }
    private val engine: WorkspaceEngine = ws.engine
    val noteId = savedState.get<String>("noteKey")!!.toTimestamp()

    private val note = engine.note(noteId)
    val exists: Boolean = note != null

    var title by mutableStateOf(TextFieldValue(note?.title?.text.orEmpty()))
        private set
    var body by mutableStateOf(TextFieldValue(note?.body?.text.orEmpty()))
        private set
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set
    var blameMode by mutableStateOf(false)
        private set
    var blameSpans by mutableStateOf<List<AuthorSpan>>(emptyList())
        private set

    /** Bumped on every remote change so remote carets re-resolve against the new text. */
    private var textRevision by mutableIntStateOf(0)

    private val bindings = mapOf(
        TextField.TITLE to CollaborativeText(title.text) { t, c -> engine.editText(noteId, TextField.TITLE, t, c); text(TextField.TITLE) },
        TextField.BODY to CollaborativeText(body.text) { t, c -> engine.editText(noteId, TextField.BODY, t, c); text(TextField.BODY) },
    )

    val tags: StateFlow<List<String>> = engine.revision
        .map { engine.note(noteId)?.tags?.elements?.toList().orEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val pinned: StateFlow<Boolean> = engine.revision.map { engine.note(noteId)?.isPinned == true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val color: StateFlow<String?> = engine.revision.map { engine.note(noteId)?.color }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val members: StateFlow<Map<ReplicaId, String>> = engine.members

    /** Collaborators currently in this note. */
    val remotePresence: StateFlow<List<RemotePresence>> = combine(ws.presence.peers, engine.revision) { peers, _ ->
        peers.values.filter { it.presence.noteId == noteId }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val listener = WorkspaceEngine.TextListener { effect ->
        if (effect.noteId != noteId) return@TextListener
        when (effect) {
            is Effect.TextInserted -> apply(effect.field, binding(effect.field).onRemoteInsert(effect.index, effect.length, text(effect.field)))
            is Effect.TextDeleted -> apply(effect.field, binding(effect.field).onRemoteDelete(effect.deletions, text(effect.field)))
            else -> Unit
        }
        textRevision++
        if (blameMode) refreshBlame()
        refreshUndo()
    }

    init {
        engine.addTextListener(listener)
        refreshUndo()
        publishPresence()
        // Catch-all for local changes made *outside* this editor while it sits in the back stack
        // (e.g. "Restore this version" on the history screen). Remote edits are handled
        // synchronously by [listener]. Dispatchers.Main (not .immediate) on purpose: with
        // .immediate this collector could run re-entrantly *inside* a keystroke commit, before
        // the binding has recorded the edit. Dispatched, it runs after; for local edits from this
        // screen it then finds nothing to do.
        viewModelScope.launch(Dispatchers.Main) {
            engine.revision.collect {
                TextField.entries.forEach { f ->
                    val crdt = text(f)
                    if (crdt != binding(f).value.text) apply(f, binding(f).onExternalChange(crdt))
                }
            }
        }
    }

    override fun onCleared() {
        engine.removeTextListener(listener)
        ws.broadcastPresence(WireMessage.Presence(noteId = null))
    }

    // ------------------------------------------------------------------ user actions

    fun onTitleChange(v: TextFieldValue) = onUserEdit(TextField.TITLE, v)
    fun onBodyChange(v: TextFieldValue) = onUserEdit(TextField.BODY, v)

    /** Called from a SideEffect once the text field has been composed with [text]. */
    fun onRendered(field: TextField, text: String) = binding(field).onRendered(text)

    private fun onUserEdit(field: TextField, v: TextFieldValue) {
        val incoming = TextSnapshot(
            v.text, v.selection.start, v.selection.end,
            v.composition?.start ?: -1, v.composition?.end ?: -1,
        )
        apply(field, binding(field).onUserEdit(incoming))
        refreshUndo()
        if (field == TextField.BODY) publishPresence()
    }

    fun undo() = applyOutcome(engine.undo(noteId))
    fun redo() = applyOutcome(engine.redo(noteId))

    private fun applyOutcome(o: dev.braid.crdt.UndoStack.Outcome?) {
        if (o == null) return
        TextField.entries.forEach { f ->
            apply(f, binding(f).onExternalChange(text(f), if (f == o.field) o.caret else null))
        }
        refreshUndo()
        publishPresence()
    }

    fun togglePinned() = engine.setPinned(noteId, !pinned.value)
    fun setColor(c: String) = engine.setColor(noteId, c)
    fun addTag(tag: String) = engine.addTag(noteId, tag)
    fun removeTag(tag: String) = engine.removeTag(noteId, tag)
    fun trash() = engine.setTrashed(noteId, true)

    fun toggleBlame() {
        blameMode = !blameMode
        if (blameMode) refreshBlame()
    }

    /** Resolves every collaborator caret to an index in the *current* text. */
    fun remoteCarets(presence: List<RemotePresence>, names: Map<ReplicaId, String>): List<RemoteCaret> {
        @Suppress("UNUSED_VARIABLE") val dependsOn = textRevision // recompute when text changes
        val n = engine.note(noteId) ?: return emptyList()
        return presence.mapNotNull { rp ->
            val p = rp.presence
            val rga = n.text(p.field)
            val index = when {
                p.caretAtStart -> 0
                p.caret != null && rga.contains(p.caret!!) -> rga.resolveAnchor(p.caret)
                else -> return@mapNotNull null
            }
            val from = if (p.hasSelection) p.selectionAnchor?.let { rga.resolveAnchor(it) } ?: 0 else null
            RemoteCaret(rp.replica, names[rp.replica] ?: "Peer", p.field, index, from)
        }
    }

    // ------------------------------------------------------------------ internals

    private fun binding(field: TextField) = bindings.getValue(field)
    private fun text(field: TextField) = engine.note(noteId)?.text(field)?.text.orEmpty()

    private fun apply(field: TextField, s: TextSnapshot) {
        val v = TextFieldValue(
            text = s.text,
            selection = TextRange(s.selectionStart, s.selectionEnd),
            composition = if (s.hasComposition) TextRange(s.compositionStart, s.compositionEnd) else null,
        )
        when (field) {
            TextField.TITLE -> title = v
            TextField.BODY -> body = v
        }
    }

    private fun refreshUndo() {
        val st = engine.undoStack(noteId)
        canUndo = st.canUndo
        canRedo = st.canRedo
    }

    private fun refreshBlame() {
        blameSpans = engine.note(noteId)?.body?.authorSpans().orEmpty()
    }

    private fun publishPresence() {
        val rga = engine.note(noteId)?.body ?: return
        val sel = body.selection
        ws.broadcastPresence(
            WireMessage.Presence(
                noteId = noteId,
                field = TextField.BODY,
                caret = rga.anchorAt(sel.end),
                caretAtStart = sel.end == 0,
                selectionAnchor = if (sel.collapsed) null else rga.anchorAt(sel.start),
                hasSelection = !sel.collapsed,
            ),
        )
    }

    fun memberName(r: ReplicaId): String = engine.displayName(r) ?: if (r == engine.replicaId) "You" else "Peer"
    val localReplica: ReplicaId get() = engine.replicaId
}
