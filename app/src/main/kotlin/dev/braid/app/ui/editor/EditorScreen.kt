package dev.braid.app.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.braid.app.ui.common.Avatar
import dev.braid.app.ui.common.BraidIcons
import dev.braid.app.ui.theme.NoteColors
import dev.braid.app.ui.theme.color
import dev.braid.crdt.TextField

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditorScreen(onBack: () -> Unit, onHistory: () -> Unit, vm: EditorViewModel = hiltViewModel()) {
    if (!vm.exists) {
        LaunchedEffect(Unit) { onBack() } // never navigate during composition
        return
    }
    val tags by vm.tags.collectAsStateWithLifecycle()
    val pinned by vm.pinned.collectAsStateWithLifecycle()
    val color by vm.color.collectAsStateWithLifecycle()
    val presence by vm.remotePresence.collectAsStateWithLifecycle()
    val members by vm.members.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    var tagInput by remember { mutableStateOf("") }
    val carets = vm.remoteCarets(presence, members)
    val bg = NoteColors[color] ?: Color.Transparent

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(horizontalArrangement = Arrangement.spacedBy((-6).dp)) {
                        presence.forEach { p -> Avatar(p.replica, members[p.replica], size = 26.dp) }
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = vm::undo, enabled = vm.canUndo) { Icon(BraidIcons.Undo, "Undo") }
                    IconButton(onClick = vm::redo, enabled = vm.canRedo) { Icon(BraidIcons.Redo, "Redo") }
                    IconButton(onClick = vm::togglePinned) {
                        Icon(BraidIcons.Pin, if (pinned) "Unpin" else "Pin", tint = if (pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("History & time travel") }, leadingIcon = { Icon(BraidIcons.History, null) }, onClick = { menu = false; onHistory() })
                        DropdownMenuItem(text = { Text(if (vm.blameMode) "Hide authorship" else "Show authorship") }, leadingIcon = { Icon(Icons.Default.Person, null) }, onClick = { menu = false; vm.toggleBlame() })
                        DropdownMenuItem(text = { Text("Move to trash") }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { menu = false; vm.trash(); onBack() })
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().background(bg).padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        ) {
            CollabField(
                value = vm.title,
                onValueChange = vm::onTitleChange,
                onRendered = { vm.onRendered(TextField.TITLE, it) },
                carets = carets.filter { it.field == TextField.TITLE },
                placeholder = "Title",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface),
                singleLine = true,
            )
            Spacer(Modifier.size(8.dp))
            if (vm.blameMode) {
                BlameLegend(vm)
                Text(blameText(vm), style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp))
            } else {
                CollabField(
                    value = vm.body,
                    onValueChange = vm::onBodyChange,
                    onRendered = { vm.onRendered(TextField.BODY, it) },
                    carets = carets.filter { it.field == TextField.BODY },
                    placeholder = "Start typing… collaborators see every keystroke.",
                    style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp, color = MaterialTheme.colorScheme.onSurface),
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                tags.forEach { t -> InputChip(selected = false, onClick = { vm.removeTag(t) }, label = { Text("#$t  ×") }) }
            }
            OutlinedTextField(
                value = tagInput,
                onValueChange = { tagInput = it.take(32) },
                label = { Text("Add tag") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { vm.addTag(tagInput); tagInput = "" }),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NoteColors.forEach { (key, c) ->
                    Box(
                        Modifier.size(28.dp).background(if (c == Color.Transparent) MaterialTheme.colorScheme.surfaceVariant else c.copy(alpha = 0.9f), CircleShape)
                            .clickable { vm.setColor(key) }
                            .semantics { contentDescription = "Color $key" },
                    )
                }
            }
        }
    }
}

/**
 * A text field that also paints collaborators' carets and selections, resolved from CRDT
 * anchors to the current layout via [TextLayoutResult.getCursorRect].
 */
@Composable
private fun CollabField(
    value: androidx.compose.ui.text.input.TextFieldValue,
    onValueChange: (androidx.compose.ui.text.input.TextFieldValue) -> Unit,
    onRendered: (String) -> Unit,
    carets: List<RemoteCaret>,
    placeholder: String,
    style: TextStyle,
    singleLine: Boolean,
    modifier: Modifier = Modifier,
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val measurer = rememberTextMeasurer()
    // Tell the binding the widget has adopted this text (see CollaborativeText "rebasing").
    SideEffect { onRendered(value.text) }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        textStyle = style,
        singleLine = singleLine,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        onTextLayout = { layout = it },
        modifier = modifier.fillMaxWidth().drawWithContent {
            val l = layout
            if (l != null) {
                val len = l.layoutInput.text.length
                for (c in carets) {
                    val color = c.replica.color()
                    c.selectionFrom?.let { from ->
                        val (a, b) = minOf(from, c.index).coerceIn(0, len) to maxOf(from, c.index).coerceIn(0, len)
                        if (a < b) drawPath(l.getPathForRange(a, b), color.copy(alpha = 0.25f))
                    }
                    val r = l.getCursorRect(c.index.coerceIn(0, len))
                    drawRect(color, Offset(r.left, r.top), Size(2.dp.toPx(), r.height))
                    drawRect(color, Offset(r.left, r.top - 14.sp.toPx()), Size(measurer.measure(c.name, TextStyle(fontSize = 10.sp)).size.width + 8f, 14.sp.toPx()))
                    drawText(measurer, c.name, Offset(r.left + 4f, r.top - 14.sp.toPx()), TextStyle(fontSize = 10.sp, color = Color.White))
                }
            }
            drawContent()
        },
        decorationBox = { inner ->
            Box {
                if (value.text.isEmpty()) Text(placeholder, style = style.copy(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)))
                inner()
            }
        },
    )
}

@Composable
private fun blameText(vm: EditorViewModel): AnnotatedString {
    val text = vm.body.text
    return buildAnnotatedString {
        append(text)
        vm.blameSpans.forEach { s ->
            if (s.end <= text.length) addStyle(SpanStyle(background = s.author.color().copy(alpha = 0.28f)), s.start, s.end)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BlameLegend(vm: EditorViewModel) {
    val authors = vm.blameSpans.groupBy { it.author }.mapValues { (_, v) -> v.sumOf { it.end - it.start } }
    FlowRow(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        authors.forEach { (r, chars) ->
            AssistChip(onClick = {}, leadingIcon = { Avatar(r, vm.memberName(r), size = 18.dp) }, label = {
                Text(buildAnnotatedString { withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(vm.memberName(r)) }; append("  $chars chars") })
            })
        }
    }
}

