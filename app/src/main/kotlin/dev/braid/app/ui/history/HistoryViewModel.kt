package dev.braid.app.ui.history

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.braid.app.workspace.WorkspaceManager
import dev.braid.app.workspace.toTimestamp
import dev.braid.crdt.NoteTimeline
import dev.braid.crdt.Op
import dev.braid.crdt.OpPayload
import dev.braid.crdt.ReplicaId
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HistoryFrame(val title: String, val body: String, val op: Op, val authorName: String)

/** Time-travel screen: scrub through every op that ever touched the note. */
@HiltViewModel
class HistoryViewModel @Inject constructor(savedState: SavedStateHandle, manager: WorkspaceManager) : ViewModel() {
    private val engine = requireNotNull(manager.active).engine
    private val noteId = savedState.get<String>("noteKey")!!.toTimestamp()
    private var timeline: NoteTimeline? = null

    var size by mutableIntStateOf(0)
        private set
    var position by mutableIntStateOf(0)
        private set
    var frame by mutableStateOf<HistoryFrame?>(null)
        private set
    var contributors by mutableStateOf<Map<ReplicaId, Int>>(emptyMap())
        private set

    init {
        viewModelScope.launch {
            val tl = engine.timeline(noteId)
            timeline = tl
            size = tl.size
            contributors = tl.ops.groupingBy { it.author }.eachCount()
            seek(tl.size)
        }
    }

    fun seek(pos: Int) {
        val tl = timeline ?: return
        position = pos.coerceIn(1, tl.size)
        val state = tl.stateAt(position)
        val op = tl.stepInfo(position)
        frame = HistoryFrame(state.title.text, state.body.text, op, name(op.author))
    }

    fun restore() {
        val f = frame ?: return
        engine.restoreVersion(noteId, f.title, f.body)
    }

    fun name(r: ReplicaId) = engine.displayName(r) ?: if (r == engine.replicaId) "You" else "Peer ${r.toString().takeLast(4)}"

    fun describe(op: Op): String = when (val p = op.payload) {
        is OpPayload.CreateNote -> "created the note"
        is OpPayload.InsertText -> "typed “${p.text.replace("\n", "⏎").take(24)}” in ${p.field.name.lowercase()}"
        is OpPayload.DeleteText -> "deleted ${p.ranges.sumOf { it.length }} chars in ${p.field.name.lowercase()}"
        is OpPayload.SetRegister -> "set ${p.key} = ${p.value}"
        is OpPayload.AddTag -> "added #${p.tag}"
        is OpPayload.RemoveTag -> "removed #${p.tag}"
        is OpPayload.SetProfile -> "renamed to ${p.displayName}"
    }
}
