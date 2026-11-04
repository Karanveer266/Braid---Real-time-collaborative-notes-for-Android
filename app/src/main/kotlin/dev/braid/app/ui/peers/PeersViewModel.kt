package dev.braid.app.ui.peers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.braid.app.workspace.WorkspaceManager
import dev.braid.data.engine.EngineStats
import dev.braid.network.TransportStatus
import dev.braid.sync.MeshPeer
import dev.braid.crdt.ReplicaId
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PeersViewModel @Inject constructor(private val manager: WorkspaceManager) : ViewModel() {
    private val ws = requireNotNull(manager.active)
    val inviteUri: String = ws.invite.toUri()
    val workspaceName: String = ws.invite.workspaceName
    val localReplica: ReplicaId = ws.engine.replicaId
    val peers: StateFlow<Map<ReplicaId, MeshPeer>> = ws.mesh.peers
    val transport: StateFlow<TransportStatus> = ws.lan.status
    val members: StateFlow<Map<ReplicaId, String>> = ws.engine.members
    val ghostRunning: StateFlow<Boolean> = ws.ghost.running

    private val _stats = MutableStateFlow(ws.engine.stats())
    val stats: StateFlow<EngineStats> = _stats

    init {
        viewModelScope.launch { while (true) { _stats.value = ws.engine.stats(); delay(1_000) } }
    }

    fun rename(name: String) = viewModelScope.launch { manager.setDisplayName(name) }
    fun leave(done: () -> Unit) = viewModelScope.launch { manager.leave(); done() }
    fun toggleGhost() = if (ws.ghost.running.value) ws.ghost.stop() else ws.ghost.start()
}
