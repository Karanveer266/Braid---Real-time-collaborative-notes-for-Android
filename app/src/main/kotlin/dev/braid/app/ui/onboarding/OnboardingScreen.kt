package dev.braid.app.ui.onboarding

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import dev.braid.app.ui.common.BraidIcons
import dev.braid.app.ui.join.QrScanner

@Composable
fun OnboardingScreen(pendingInvite: String?, vm: OnboardingViewModel = hiltViewModel()) {
    val context = LocalContext.current
    var scanning by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> scanning = granted }
    LaunchedEffect(pendingInvite) { pendingInvite?.let(vm::prefill) }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Braid", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Text(
            "Notes your devices edit together, in real time, with no server and no account. Everything stays on your local network, end-to-end encrypted.",
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(vm.name, { vm.name = it.take(40) }, label = { Text("Your name (shown to collaborators)") }, singleLine = true, modifier = Modifier.fillMaxWidth())

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Start a new workspace", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(vm.workspaceName, { vm.workspaceName = it.take(40) }, label = { Text("Workspace name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(onClick = vm::create, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) { Text("Create") }
            }
        }
        HorizontalDivider()
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Join an existing one", style = MaterialTheme.typography.titleMedium)
                if (scanning) {
                    QrScanner(
                        onResult = { scanning = false; vm.prefill(it); vm.join(it) },
                        modifier = Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(12.dp)),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) scanning = !scanning
                        else permission.launch(Manifest.permission.CAMERA)
                    }) { Icon(BraidIcons.QrCode, null); Text(if (scanning) "  Stop scanning" else "  Scan QR") }
                }
                OutlinedTextField(vm.inviteText, { vm.prefill(it) }, label = { Text("…or paste an invite link") }, modifier = Modifier.fillMaxWidth(), maxLines = 3)
                Button(onClick = { vm.join() }, enabled = !vm.busy && vm.inviteText.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Join") }
            }
        }
        vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
