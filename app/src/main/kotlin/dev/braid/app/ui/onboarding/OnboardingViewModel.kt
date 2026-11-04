package dev.braid.app.ui.onboarding

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.braid.app.workspace.WorkspaceManager
import dev.braid.sync.Invite
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(private val manager: WorkspaceManager) : ViewModel() {
    var name by mutableStateOf("")
    var workspaceName by mutableStateOf("Our notes")
    var inviteText by mutableStateOf("")
    var error by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set

    fun create() = launchBusy { manager.create(workspaceName, name.ifBlank { "Me" }) }

    fun join(uri: String = inviteText) {
        val invite = Invite.parse(uri).getOrElse { error = it.message; return }
        launchBusy { manager.join(invite, name.ifBlank { "Me" }) }
    }

    fun prefill(uri: String) { inviteText = uri; error = null }

    private fun launchBusy(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        viewModelScope.launch {
            runCatching { block() }.onFailure { error = it.message ?: "Something went wrong" }
            busy = false
        }
    }
}
