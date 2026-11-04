package dev.braid.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dagger.hilt.android.AndroidEntryPoint
import dev.braid.app.ui.editor.EditorScreen
import dev.braid.app.ui.history.HistoryScreen
import dev.braid.app.ui.notes.NotesScreen
import dev.braid.app.ui.onboarding.OnboardingScreen
import dev.braid.app.ui.peers.PeersScreen
import dev.braid.app.ui.theme.BraidTheme
import dev.braid.app.workspace.WorkspaceManager
import dev.braid.app.workspace.toKey
import kotlinx.serialization.Serializable
import javax.inject.Inject

@Serializable object NotesRoute
@Serializable data class EditorRoute(val noteKey: String)
@Serializable data class HistoryRoute(val noteKey: String)
@Serializable object PeersRoute

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var workspaces: WorkspaceManager

    /** An invite link that opened the app; consumed by onboarding. */
    private var pendingInvite by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent {
            BraidTheme {
                Surface(Modifier.fillMaxSize()) { Root() }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent?.data?.takeIf { it.scheme == "braid" }?.let { pendingInvite = it.toString() }
    }

    @Composable
    private fun Root() {
        val state by workspaces.state.collectAsStateWithLifecycle()
        when (val s = state) {
            WorkspaceManager.State.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            WorkspaceManager.State.None -> OnboardingScreen(pendingInvite)
            is WorkspaceManager.State.Open -> AppNav(s.workspace.invite.workspaceId)
        }
    }

    @Composable
    private fun AppNav(@Suppress("UNUSED_PARAMETER") workspaceId: String) {
        val nav = rememberNavController()
        NavHost(nav, startDestination = NotesRoute) {
            composable<NotesRoute> {
                NotesScreen(onOpen = { nav.navigate(EditorRoute(it.toKey())) }, onPeers = { nav.navigate(PeersRoute) })
            }
            composable<EditorRoute> { entry ->
                val key = entry.toRoute<EditorRoute>().noteKey
                EditorScreen(onBack = { nav.popBackStack() }, onHistory = { nav.navigate(HistoryRoute(key)) })
            }
            composable<HistoryRoute> { HistoryScreen(onBack = { nav.popBackStack() }) }
            composable<PeersRoute> { PeersScreen(onBack = { nav.popBackStack() }, onLeft = { }) }
        }
    }
}
