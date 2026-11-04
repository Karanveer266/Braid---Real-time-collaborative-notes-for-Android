package dev.braid.app.ui.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.braid.app.workspace.WorkspaceManager
import dev.braid.crdt.ReplicaId
import dev.braid.crdt.Timestamp
import dev.braid.data.engine.NoteSummary
import dev.braid.sync.SessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class NotesUiState(
    val workspaceName: String = "",
    val notes: List<NoteSummary> = emptyList(),
    val query: String = "",
    val tagFilter: String? = null,
    val allTags: List<String> = emptyList(),
    val livePeers: Int = 0,
    val members: Map<ReplicaId, String> = emptyMap(),
)

@HiltViewModel
class NotesViewModel @Inject constructor(private val manager: WorkspaceManager) : ViewModel() {
    private val ws = requireNotNull(manager.active)
    private val query = MutableStateFlow("")
    private val tag = MutableStateFlow<String?>(null)

    val state: StateFlow<NotesUiState> = combine(
        ws.engine.notes, query, tag, ws.mesh.peers.map { p -> p.values.count { it.state is SessionState.Live } }, ws.engine.members,
    ) { notes, q, t, live, members ->
        val filtered = notes.filter { n ->
            (t == null || t in n.tags) &&
                (q.isBlank() || n.title.contains(q, ignoreCase = true) || n.snippet.contains(q, ignoreCase = true))
        }
        NotesUiState(ws.invite.workspaceName, filtered, q, t, notes.flatMap { it.tags }.distinct().sorted(), live, members)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotesUiState(ws.invite.workspaceName))

    val ghostRunning: StateFlow<Boolean> = ws.ghost.running
    val storageError: StateFlow<String?> = ws.engine.storageError

    fun setQuery(q: String) { query.value = q }
    fun setTag(t: String?) { tag.value = if (tag.value == t) null else t }
    fun createNote(): Timestamp = ws.engine.createNote()
    fun togglePin(n: NoteSummary) = ws.engine.setPinned(n.id, !n.pinned)
    fun trash(n: NoteSummary) = ws.engine.setTrashed(n.id, true)
    fun restore(n: NoteSummary) = ws.engine.setTrashed(n.id, false)
    fun toggleGhost() = if (ws.ghost.running.value) ws.ghost.stop() else ws.ghost.start()
}
