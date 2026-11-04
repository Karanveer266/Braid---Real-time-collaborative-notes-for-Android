package dev.braid.app.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import dev.braid.app.ui.common.Avatar
import dev.braid.app.ui.common.absolute

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(onBack: () -> Unit, vm: HistoryViewModel = hiltViewModel()) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Time travel") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        val f = vm.frame
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            if (f == null) { Text("Loading history…"); return@Column }
            Text("Operation ${vm.position} of ${vm.size}", style = MaterialTheme.typography.labelLarge)
            Slider(
                value = vm.position.toFloat(),
                onValueChange = { vm.seek(it.toInt()) },
                valueRange = 1f..vm.size.coerceAtLeast(2).toFloat(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(f.op.author, f.authorName, size = 24.dp)
                Spacer(Modifier.size(8.dp))
                Column {
                    Text("${f.authorName} ${vm.describe(f.op)}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text(f.op.id.absolute() + "  ·  HLC ${f.op.id}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.size(12.dp))
            Card(Modifier.fillMaxWidth().weight(1f)) {
                Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                    Text(f.title.ifBlank { "Untitled" }, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.size(8.dp))
                    Text(f.body, style = MaterialTheme.typography.bodyLarge)
                }
            }
            Spacer(Modifier.size(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    vm.contributors.entries.joinToString { (r, n) -> "${vm.name(r)}: $n ops" },
                    style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f),
                )
                Button(onClick = { vm.restore(); onBack() }, enabled = vm.position < vm.size) { Text("Restore this version") }
            }
        }
    }
}
