package dev.braid.app.workspace

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.braid.data.db.RoomOpLog
import dev.braid.data.db.deleteWorkspaceDatabase
import dev.braid.data.db.openWorkspaceDatabase
import dev.braid.data.engine.WorkspaceEngine
import dev.braid.data.prefs.AppPreferences
import dev.braid.network.LanTransport
import dev.braid.sync.Invite
import dev.braid.sync.PeerMesh
import dev.braid.sync.WireMessage
import dev.braid.sync.syncPrologue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Everything that exists while a workspace is open. Its [scope] dies when the workspace closes. */
class ActiveWorkspace internal constructor(
    val invite: Invite,
    val engine: WorkspaceEngine,
    val mesh: PeerMesh,
    val lan: LanTransport,
    val presence: PresenceHub,
    val ghost: GhostPeer,
    internal val scope: CoroutineScope,
    private val db: androidx.room.RoomDatabase,
) {
    /** Sends our caret to every connected peer, including the simulated one. */
    fun broadcastPresence(p: WireMessage.Presence) {
        mesh.broadcastPresence(p)
        ghost.onLocalPresence(p)
    }

    internal fun close() {
        ghost.stop()
        mesh.stop()
        lan.stop()
        scope.cancel()
        db.close()
    }
}

/**
 * Owns the lifecycle of the (single) open workspace: creation, joining, leaving, and starting /
 * stopping networking with app visibility.
 *
 * A process-wide singleton rather than a ViewModel because the workspace outlives any screen and
 * must be shared by all of them; ViewModels observe [state].
 */
@Singleton
class WorkspaceManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: AppPreferences,
) {
    sealed interface State {
        data object Loading : State
        data object None : State
        data class Open(val workspace: ActiveWorkspace) : State
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()

    val active: ActiveWorkspace? get() = (state.value as? State.Open)?.workspace

    private var foreground = false

    init {
        appScope.launch {
            val invite = prefs.activeInvite()
            _state.value = if (invite == null) State.None else State.Open(open(invite))
        }
    }

    suspend fun create(workspaceName: String, displayName: String) = switchTo(Invite.create(workspaceName), displayName)

    suspend fun join(invite: Invite, displayName: String) = switchTo(invite, displayName)

    /** Leaves the workspace and deletes local data. Peers keep their copies. */
    suspend fun leave() {
        val ws = active ?: return
        ws.close()
        deleteWorkspaceDatabase(context, ws.invite.workspaceId)
        prefs.setActiveInvite(null)
        _state.value = State.None
    }

    fun setForeground(value: Boolean) {
        foreground = value
        active?.let { applyNetworking(it) }
    }

    suspend fun setDisplayName(name: String) {
        prefs.setDisplayName(name)
        active?.engine?.setProfile(name)
    }

    private suspend fun switchTo(invite: Invite, displayName: String) {
        if (active?.invite?.workspaceId == invite.workspaceId) return
        active?.close()
        prefs.setDisplayName(displayName)
        prefs.setActiveInvite(invite)
        _state.value = State.Loading
        _state.value = State.Open(open(invite))
    }

    private suspend fun open(invite: Invite): ActiveWorkspace {
        val scope = CoroutineScope(SupervisorJob(appScope.coroutineContext[kotlinx.coroutines.Job]) + Dispatchers.Main.immediate)
        val replicaId = prefs.replicaFor(invite.workspaceId)
        val db = openWorkspaceDatabase(context, invite.workspaceId)
        val engine = WorkspaceEngine(
            log = RoomOpLog(db),
            replicaId = replicaId,
            scope = scope,
            stateDispatcher = Dispatchers.Main.immediate,
            ioDispatcher = Dispatchers.IO,
            computeDispatcher = Dispatchers.Default,
        )
        engine.open()
        val name = prefs.displayName.first()
        if (name.isNotBlank()) engine.setProfile(name)

        val presence = PresenceHub()
        val prologue = syncPrologue(invite.workspaceId)
        val lan = LanTransport(context, scope, replicaId, invite.discoveryTag)
        val mesh = PeerMesh(
            store = engine,
            transport = lan,
            psk = invite.psk,
            prologue = prologue,
            // Called from mesh coroutines (Dispatchers.Default): read the immutable members
            // snapshot published by the engine, never the state-thread-confined Workspace.
            localName = { engine.members.value[replicaId].orEmpty() },
            onPresence = presence::update,
            onDisconnect = presence::remove,
        )
        val ghost = GhostPeer(engine, invite.psk, prologue, presence, scope)
        val ws = ActiveWorkspace(invite, engine, mesh, lan, presence, ghost, scope, db)
        scope.launch {
            while (isActive) { delay(5_000); presence.prune() }
        }
        applyNetworking(ws)
        return ws
    }

    private fun applyNetworking(ws: ActiveWorkspace) {
        if (foreground) {
            runCatching { ws.lan.start() }
            ws.mesh.start(CoroutineScope(ws.scope.coroutineContext + Dispatchers.Default))
        } else {
            ws.mesh.stop()
            ws.lan.stop()
            ws.presence.clear()
        }
    }
}
