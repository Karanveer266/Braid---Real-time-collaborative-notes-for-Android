package dev.braid.app.ui.peers

import android.content.Intent
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.braid.app.ui.common.Avatar
import dev.braid.sync.CloseReason
import dev.braid.sync.SessionState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeersScreen(onBack: () -> Unit, onLeft: () -> Unit, vm: PeersViewModel = hiltViewModel()) {
    val peers by vm.peers.collectAsStateWithLifecycle()
    val transport by vm.transport.collectAsStateWithLifecycle()
    val members by vm.members.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val ghost by vm.ghostRunning.collectAsStateWithLifecycle()
    var showQr by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var name by remember(members) { mutableStateOf(members[vm.localReplica].orEmpty()) }
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Devices & sync") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Invite a device", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Anyone with this code can read and edit every note in “${vm.workspaceName}”. Share it only in person or over a channel you trust.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.size(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { showQr = true }) { Text("Show QR code") }
                        OutlinedButton(onClick = {
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, vm.inviteUri), "Share invite"))
                        }) { Icon(Icons.Default.Share, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text("Share link") }
                    }
                }
            }

            Text("Peers on this network", style = MaterialTheme.typography.titleMedium)
            ListItem(
                headlineContent = { Text("Simulated collaborator") },
                supportingContent = { Text("A second replica in this app, synced through the real encrypted protocol.") },
                trailingContent = { Switch(checked = ghost, onCheckedChange = { vm.toggleGhost() }) },
            )
            if (peers.isEmpty()) {
                Text(
                    if (transport.running) "Searching… Open Braid on another device on the same Wi-Fi and join with the invite." else "Networking paused.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            peers.values.sortedBy { it.replica }.forEach { p ->
                val label = members[p.replica] ?: "Device ${p.replica.toString().takeLast(4)}"
                ListItem(
                    leadingContent = { Avatar(p.replica, label) },
                    headlineContent = { Text(label) },
                    supportingContent = { Text(describe(p.state, p.attempts)) },
                )
            }
            transport.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            HorizontalDivider()
            Text("Your name", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(name, { name = it.take(40) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                trailingIcon = { TextButton(onClick = { vm.rename(name) }) { Text("Save") } })

            HorizontalDivider()
            Text("Replica internals", style = MaterialTheme.typography.titleMedium)
            val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
            Text(
                """
                replica id       ${vm.localReplica}
                listening        ${if (transport.running) "tcp/${transport.port}" else "—"}
                ops (memory)     ${stats.opCount}
                ops (durable)    ${stats.durableOps}
                replicas seen    ${stats.replicas}
                notes            ${stats.notes}
                visible chars    ${stats.visibleChars}
                rga blocks       ${stats.blocks}
                tombstones       ${stats.tombstonedChars} chars
                cold load        ${stats.loadMillis} ms
                """.trimIndent(),
                style = mono,
            )

            HorizontalDivider()
            TextButton(onClick = { confirmLeave = true }) { Text("Leave workspace and delete local data", color = MaterialTheme.colorScheme.error) }
        }
    }

    if (showQr) {
        SecureWindow()
        AlertDialog(
            onDismissRequest = { showQr = false },
            confirmButton = { TextButton(onClick = { showQr = false }) { Text("Done") } },
            title = { Text("Scan to join") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    QrCode(vm.inviteUri, Modifier.widthIn(max = 280.dp).fillMaxWidth())
                    Spacer(Modifier.size(8.dp))
                    Text("Screenshots are blocked while this is visible.", style = MaterialTheme.typography.bodySmall)
                }
            },
        )
    }
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            confirmButton = { TextButton(onClick = { vm.leave(onLeft) }) { Text("Leave") } },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Cancel") } },
            title = { Text("Leave workspace?") },
            text = { Text("Notes are deleted from this device only. Other devices keep them, and you can rejoin with an invite to get everything back.") },
        )
    }
}

/** FLAG_SECURE while the secret QR is on screen: blocks screenshots, recordings and the recents thumbnail. */
@Composable
private fun SecureWindow() {
    val activity = LocalActivity.current ?: return
    DisposableEffect(Unit) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

private fun describe(s: SessionState, attempts: Int): String = when (s) {
    SessionState.Handshaking -> if (attempts > 0) "Reconnecting (attempt $attempts)…" else "Connecting…"
    is SessionState.Syncing -> "Exchanging history…"
    is SessionState.Live -> "Live · ↑${s.sent} ↓${s.received} ops · ${s.peer.endpoint}"
    is SessionState.Closed -> when (s.reason) {
        CloseReason.AUTH_FAILED -> "Rejected: different workspace key"
        CloseReason.VERSION_MISMATCH -> "Incompatible app version"
        CloseReason.CLOCK_DRIFT -> "Rejected: device clock is far off"
        CloseReason.SELF -> "This device"
        else -> "Disconnected" + if (attempts > 0) " · retrying" else ""
    }
}
