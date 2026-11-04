package dev.braid.app.ui.notes

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.braid.app.ui.common.Avatar
import dev.braid.app.ui.common.BraidIcons
import dev.braid.app.ui.common.relative
import dev.braid.app.ui.theme.NoteColors
import dev.braid.crdt.Timestamp
import dev.braid.data.engine.NoteSummary
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun NotesScreen(onOpen: (Timestamp) -> Unit, onPeers: () -> Unit, vm: NotesViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val ghost by vm.ghostRunning.collectAsStateWithLifecycle()
    val storageError by vm.storageError.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.workspaceName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = {
                    IconButton(onClick = vm::toggleGhost) {
                        Icon(Icons.Default.Face, if (ghost) "Stop simulated collaborator" else "Start simulated collaborator", tint = if (ghost) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onPeers) {
                        BadgedBox(badge = { if (state.livePeers > 0) Badge { Text("${state.livePeers}") } }) { Icon(BraidIcons.Devices, "Devices & sync") }
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { onOpen(vm.createNote()) }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("New note") })
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            storageError?.let {
                Text(
                    "Can't save changes ($it). Retrying… Edits are kept in memory meanwhile.",
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(12.dp),
                )
            }
            OutlinedTextField(
                value = state.query,
                onValueChange = vm::setQuery,
                leadingIcon = { Icon(Icons.Default.Search, null) },
                placeholder = { Text("Search notes") },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
            if (state.allTags.isNotEmpty()) {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.allTags) { t -> FilterChip(selected = state.tagFilter == t, onClick = { vm.setTag(t) }, label = { Text("#$t") }) }
                }
            }
            if (state.notes.isEmpty()) {
                EmptyState(ghost)
            } else {
                LazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Adaptive(170.dp),
                    contentPadding = PaddingValues(12.dp, 8.dp, 12.dp, 96.dp),
                    verticalItemSpacing = 10.dp,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.notes, key = { it.id.toString() }) { n ->
                        NoteCard(
                            n, state.members,
                            onClick = { onOpen(n.id) },
                            onLongClick = {
                                vm.trash(n)
                                scope.launch {
                                    if (snackbar.showSnackbar("Moved to trash", "Undo") == SnackbarResult.ActionPerformed) vm.restore(n)
                                }
                            },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteCard(
    n: NoteSummary,
    members: Map<dev.braid.crdt.ReplicaId, String>,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint = NoteColors[n.color]?.takeIf { it != Color.Transparent }
    Card(
        modifier = modifier.fillMaxWidth().animateContentSize().combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = CardDefaults.cardColors(containerColor = tint ?: MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    n.title.ifBlank { "Untitled" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                if (n.pinned) Icon(BraidIcons.Pin, "Pinned", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            }
            if (n.snippet.isNotBlank()) {
                Spacer(Modifier.size(6.dp))
                Text(n.snippet, style = MaterialTheme.typography.bodyMedium, maxLines = 8, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (n.tags.isNotEmpty()) {
                Spacer(Modifier.size(6.dp))
                Text(n.tags.joinToString("  ") { "#$it" }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
            }
            Spacer(Modifier.size(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy((-6).dp)) {
                    n.contributors.take(4).forEach { Avatar(it, members[it], size = 20.dp) }
                }
                Spacer(Modifier.weight(1f))
                Text(n.updatedAt.relative(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun EmptyState(ghost: Boolean) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(72.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape))
            Spacer(Modifier.size(16.dp))
            Text("No notes yet", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.size(8.dp))
            Text(
                if (ghost) "The simulated collaborator is online. Create a note and open it: they'll join you."
                else "Create a note, then invite a second device on the same Wi-Fi, or tap the face icon to start a simulated collaborator.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
