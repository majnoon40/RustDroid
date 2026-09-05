package dev.rustdroid.ide.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons


import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close

import androidx.compose.material.icons.filled.PlayArrow


import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import dev.rustdroid.ide.di.AppContainer
import dev.rustdroid.ide.model.ConsoleLine
import dev.rustdroid.ide.ui.components.RdIcons
import dev.rustdroid.ide.model.Diagnostic
import dev.rustdroid.ide.model.FileNode
import dev.rustdroid.ide.model.Severity
import dev.rustdroid.ide.model.Stream
import dev.rustdroid.ide.ui.theme.LocalEditorPalette
import dev.rustdroid.ide.ui.theme.MonoSmall

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditorScreen(
    container: AppContainer,
    projectName: String,
    onOpenDeps: (String) -> Unit,
    initialFile: String? = null,
) {
    val vm: EditorViewModel = viewModel(
        key = "editor-$projectName",
        factory = EditorViewModel.factory(container, projectName, initialFile),
    )
    val tabs by vm.tabs.collectAsState()
    val activeIndex by vm.activeIndex.collectAsState()
    val activeText by vm.activeText.collectAsState()
    val tree by vm.tree.collectAsState()
    val running by vm.running.collectAsState()
    val problems by vm.problems.collectAsState()
    val lastResult by vm.lastResult.collectAsState()
    val bottomTab by vm.bottomTab.collectAsState()
    val jump by vm.jump.collectAsState()
    val showCloseConfirm by vm.showCloseConfirm.collectAsState()
    val allFiles by vm.treeAllFiles.collectAsState()
    val consoleLines by vm.console.lines.collectAsState()

    // new-file dialog state: name lives locally; success/error come from the
    // VM (one-shot createdFile closes the dialog, createFileError shows inline)
    val createdFile by vm.createdFile.collectAsState()
    val createFileError by vm.createFileError.collectAsState()
    var showNewFile by remember { mutableStateOf(false) }

    // delete-file dialog state: the row long-press picks the target; the
    // VM's one-shot deletedFile closes the dialog, deleteFileError shows
    // inline (e.g. the root Cargo.toml guard)
    val deletedFile by vm.deletedFile.collectAsState()
    val deleteFileError by vm.deleteFileError.collectAsState()
    var pendingDelete by remember { mutableStateOf<FileNode?>(null) }

    // Re-entering this screen (e.g. back from the Deps screen) re-runs this
    // effect: pull fresh bytes for any tab that has no unsaved edits AND
    // re-list the file tree, so files changed on disk elsewhere (another
    // app, or — for folders opened in place — the user on a PC) are
    // reflected here instead of shown stale (or clobbered on save).
    LaunchedEffect(Unit) { vm.reloadUnchangedTabs(); vm.refreshTree() }

    // Deep-linked file ("open with" import): the VM init already opened it
    // for fresh instances; this covers re-entering an existing VM for the
    // same project with a different file. openFile is idempotent (focuses
    // the tab when already open).
    LaunchedEffect(initialFile) {
        initialFile?.let { vm.openFile(it) }
    }

    val dark = isSystemInDarkTheme()
    val palette = LocalEditorPalette.current

    // IME-aware layout: editor focus + visible keyboard => hide the console
    // panel so the script gets the whole space above the IME (see bottom
    // panel below). Track View-level focus from sora-editor.
    var editorFocused by remember { mutableStateOf(false) }

    val drawerState = androidx.compose.material3.rememberDrawerState(
        initialValue = androidx.compose.material3.DrawerValue.Closed,
    )
    val drawerScope = androidx.compose.runtime.rememberCoroutineScope()

    // auto-scroll console to bottom on new lines
    val consoleListState = rememberLazyListState()
    LaunchedEffect(consoleLines.size) {
        if (consoleLines.isNotEmpty()) {
            consoleListState.scrollToItem(consoleLines.size - 1)
        }
    }
    // clear the jump request after the editor consumed it
    LaunchedEffect(jump, activeText) {
        if (jump != null) vm.clearJump()
    }
    // close the new-file dialog once the VM reports the file was created
    LaunchedEffect(createdFile) {
        if (createdFile != null) {
            showNewFile = false
            vm.clearNewFileSignals()
        }
    }
    // close the delete dialog once the VM reports the file is gone
    LaunchedEffect(deletedFile) {
        if (deletedFile != null) {
            pendingDelete = null
            vm.clearDeleteSignals()
        }
    }

    ModalNavigationDrawer(
        // The editor is a sora-editor Android View (AndroidView interop): it
        // scrolls and long-press-selects inside the View system and never
        // consumes pointer events in Compose's gesture pipeline. With drawer
        // gestures enabled while CLOSED, M3's anchoredDraggable watches the
        // same pointer stream over the content area and (a) steals a
        // long-press once the finger drifts past touch slop — cancelling the
        // editor mid-hold, so text selection never fires, and (b) reads
        // scroll/fling motion as a horizontal drag, popping the drawer open
        // while scrolling long files. Compose-native scrollables (the
        // console LazyColumn) consume their deltas first, so only interop
        // children are exposed.
        //
        // Gestures are enabled only while the drawer is OPEN: the sheet then
        // covers the editor, nothing interop sits under the gesture area,
        // and a right-to-left swipe (off the sheet or the scrim) closes it.
        // Opening stays a deliberate act — the toolbar folder button — so
        // scrolling code can never accidentally summon the drawer.
        gesturesEnabled = drawerState.targetValue == androidx.compose.material3.DrawerValue.Open,
        drawerContent = {
            ModalDrawerSheet {
                Column(Modifier.fillMaxHeight()) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(RdIcons.Folder, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("Files", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.weight(1f))
                        IconButton(
                            onClick = { showNewFile = true },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                Icons.Filled.Add,
                                contentDescription = "New file",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                        TextButton(onClick = { vm.toggleAllFiles() }) {
                            Text(if (allFiles) "Sources" else "All files")
                        }
                    }
                    LazyColumn(Modifier.weight(1f)) {
                        items(tree, key = { it.relativePath }) { node ->
                            FileTreeRow(
                                node,
                                onOpen = { rel, isDir ->
                                    if (!isDir) vm.openFile(rel)
                                    drawerScope.launch { drawerState.close() }
                                },
                                onDelete = { pendingDelete = node },
                            )
                        }
                    }
                }
            }
        },
        drawerState = drawerState,
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = {
                            // fresh tree every open: the folder may have
                            // changed on disk since (opened-in-place projects
                            // are shared with the rest of the system)
                            vm.refreshTree()
                            drawerScope.launch { drawerState.open() }
                        }) {
                            Icon(RdIcons.Folder, contentDescription = "Files")
                        }
                    },
                    title = { Text(projectName.substringAfterLast('/')) },
                    actions = {
                        TextButton(onClick = { onOpenDeps(projectName) }) { Text("Deps") }
                        if (running) {
                            IconButton(onClick = { vm.stop() }) {
                                Icon(RdIcons.Stop, contentDescription = "Stop", tint = MaterialTheme.colorScheme.error)
                            }
                        } else {
                            IconButton(onClick = { vm.run() }) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = "Run", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    },
                )
            },
        ) { padding ->
            // imePadding on the whole column: with edge-to-edge + decor not
            // fitting system windows, adjustResize is neutralized — IME
            // insets flow through WindowInsets and must be applied manually.
            // Children (editor / bottom panel) then always sit above the
            // keyboard, whichever of them owns the input focus.
            Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
                // ---- tabs row (compact 30dp strip — M3 Tab's 48dp minimum
                // + padding ate a quarter of the screen; every dp counts on a
                // phone with the keyboard open) ----
                if (tabs.isNotEmpty()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(30.dp)
                            .horizontalScroll(rememberScrollState())
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        tabs.forEachIndexed { i, tab ->
                            val selected = i == activeIndex
                            val indicator = MaterialTheme.colorScheme.primary
                            val activeBg = MaterialTheme.colorScheme.surface
                            Row(
                                Modifier
                                    .height(30.dp)
                                    .background(if (selected) activeBg else Color.Transparent)
                                    .drawBehind {
                                        if (selected) {
                                            val h = 2.dp.toPx()
                                            drawRect(
                                                color = indicator,
                                                topLeft = Offset(0f, size.height - h),
                                                size = Size(size.width, h),
                                            )
                                        }
                                    }
                                    .clickable { vm.setActive(i) }
                                    .padding(start = 10.dp, end = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (tab.dirty) {
                                    Box(
                                        Modifier
                                            .width(6.dp).height(6.dp)
                                            .background(MaterialTheme.colorScheme.primary, CircleShape)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                }
                                Text(
                                    tab.relativePath.substringAfterLast('/'),
                                    maxLines = 1,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (selected) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.width(4.dp))
                                // close: 22dp hit area (touch targets shrink for
                                // icon-only affordances); inner clickable consumes
                                // the tap so the tab itself doesn't also switch
                                Box(
                                    Modifier
                                        .width(22.dp).height(22.dp)
                                        .clickable { vm.closeTab(i, force = false) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = "Close tab",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.height(13.dp).width(13.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                // ---- editor ----
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (tabs.isEmpty()) {
                        Text(
                            "Open a file from the drawer",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    } else {
                        CodeEditorPane(
                            text = activeText,
                            dark = dark,
                            onTextChange = { vm.onTextChange(it) },
                            jumpRequest = jump,
                            onFocusChanged = { editorFocused = it },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                // ---- bottom panel ----
                // Hidden while the editor is focused with the IME open: the
                // keyboard already eats half the screen, and a 220dp console
                // + its tab row on top of that left only a sliver of code
                // visible (the "tiny portion of the script" report). The
                // panel comes back the moment the keyboard closes. Typing in
                // the console's stdin bar keeps the panel (it owns focus
                // there) with the console shrunk to 88dp instead of 220dp.
                val imeVisible = WindowInsets.isImeVisible
                val hideBottom = imeVisible && editorFocused
                val errorCount = problems.count { it.severity == Severity.ERROR }
                val warningCount = problems.count { it.severity == Severity.WARNING }
                if (!hideBottom) {
                    Surface(tonalElevation = 2.dp) {
                    Column(Modifier.fillMaxWidth()) {
                        TabRow(selectedTabIndex = if (bottomTab == EditorViewModel.BottomTab.CONSOLE) 0 else 1) {
                            Tab(
                                selected = bottomTab == EditorViewModel.BottomTab.CONSOLE,
                                onClick = { vm.selectBottomTab(EditorViewModel.BottomTab.CONSOLE) },
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(RdIcons.Terminal, null, Modifier.height(14.dp).width(14.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Console")
                                        lastResult?.let {
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                if (it.success) "· ok" else "· exit ${it.exitCode}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (it.success) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error,
                                            )
                                        }
                                    }
                                },
                            )
                            Tab(
                                selected = bottomTab == EditorViewModel.BottomTab.PROBLEMS,
                                onClick = { vm.selectBottomTab(EditorViewModel.BottomTab.PROBLEMS) },
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Filled.Warning, null, Modifier.height(14.dp).width(14.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Problems")
                                        if (errorCount + warningCount > 0) {
                                            Spacer(Modifier.width(6.dp))
                                            Badge { Text("$errorCount/$warningCount") }
                                        }
                                    }
                                },
                            )
                        }
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(if (imeVisible) 88.dp else 220.dp)
                                .background(palette.consoleBg)
                        ) {
                            when (bottomTab) {
                                EditorViewModel.BottomTab.CONSOLE -> ConsolePanel(
                                    lines = consoleLines,
                                    running = running,
                                    onSend = { vm.sendStdin(it) },
                                    onClear = { vm.clearConsole() },
                                    listState = consoleListState,
                                )
                                EditorViewModel.BottomTab.PROBLEMS -> ProblemsPanel(
                                    problems = problems,
                                    onJump = { vm.jumpTo(it) },
                                )
                            }
                        }
                    }
                    }
                }
            }
        }
    }

    if (showCloseConfirm) {
        AlertDialog(
            onDismissRequest = { vm.confirmClose(save = false) },
            title = { Text("Unsaved changes") },
            text = { Text("Save before closing this tab?") },
            confirmButton = { TextButton(onClick = { vm.confirmClose(save = true) }) { Text("Save & close") } },
            dismissButton = { TextButton(onClick = { vm.confirmClose(save = false) }) { Text("Discard") } },
        )
    }

    if (showNewFile) {
        NewFileDialog(
            error = createFileError,
            onCreate = { vm.createFile(it) },
            onDismiss = {
                vm.clearNewFileSignals()
                showNewFile = false
            },
        )
    }

    pendingDelete?.let { node ->
        DeleteFileDialog(
            node = node,
            error = deleteFileError,
            onDelete = { vm.deleteFile(node.relativePath) },
            onDismiss = {
                vm.clearDeleteSignals()
                pendingDelete = null
            },
        )
    }
}

/**
 * Creates an empty file inside the project. Accepts a path relative to the
 * project root ("src/foo.rs" or "notes.md"); the name is validated for
 * traversal/escape and must not already exist (errors show inline).
 */
@Composable
private fun NewFileDialog(
    error: String?,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    val trimmed = name.trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New file") },
        text = {
            Column {
                Text(
                    "Path relative to the project root, e.g. src/main.rs or notes.md",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("File name") },
                    isError = error != null,
                    supportingText = if (error != null) {
                        { Text(error, color = MaterialTheme.colorScheme.error) }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = { if (trimmed.isNotEmpty()) onCreate(trimmed) },
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(trimmed) },
                enabled = trimmed.isNotEmpty(),
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * One drawer row. Tap opens (files) / toggles nothing (dirs close the
 * drawer as before); long-press offers deletion via [onDelete]. The drawer
 * sheet is pure Compose, so combinedClickable's long-press does not fight
 * the sora-editor interop view (which only ever sits under the CLOSED
 * drawer — see ModalNavigationDrawer gesturesEnabled note above).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileTreeRow(
    node: FileNode,
    onOpen: (String, Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { onOpen(node.relativePath, node.isDirectory) },
                onLongClick = onDelete,
            )
            .padding(
                start = (12 + node.depth * 16).dp,
                top = 8.dp, bottom = 8.dp, end = 12.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (node.isDirectory) {
            Icon(
                RdIcons.Folder, null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.width(18.dp).height(18.dp),
            )
        } else {
            Spacer(Modifier.width(18.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(
            if (node.isDirectory) node.file.name + "/" else node.file.name,
            style = MaterialTheme.typography.bodySmall,
            color = if (node.isDirectory) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Confirms deleting [node] (a whole subtree when it is a directory).
 * Errors from the repository guards (root Cargo.toml, root itself, …)
 * show inline and keep the dialog open, mirroring NewFileDialog.
 */
@Composable
private fun DeleteFileDialog(
    node: FileNode,
    error: String?,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (node.isDirectory) "Delete folder?" else "Delete file?") },
        text = {
            Column {
                Text(
                    node.relativePath +
                        if (node.isDirectory) " and everything inside it" else "",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "This cannot be undone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (error != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDelete) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ConsolePanel(
    lines: List<ConsoleLine>,
    running: Boolean,
    onSend: (String) -> Unit,
    onClear: () -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState,
) {
    val palette = LocalEditorPalette.current
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxSize()) {
        // Console toolbar: guaranteed copy path. Long-press selection
        // (SelectionContainer below) can be fiddly on some devices and is
        // limited to on-screen lines — "Copy all" always works.
        Row(
            Modifier
                .fillMaxWidth()
                .background(palette.consoleBg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${lines.size} lines",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 10.dp),
            )
            Spacer(Modifier.weight(1f))
            IconButton(
                onClick = {
                    clipboard.setText(AnnotatedString(lines.joinToString("\n") { it.text }))
                },
                enabled = lines.isNotEmpty(),
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    RdIcons.Copy,
                    contentDescription = "Copy all output",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
            IconButton(
                onClick = onClear,
                enabled = lines.isNotEmpty() && !running,
                modifier = Modifier
                    .size(32.dp)
                    .padding(end = 6.dp),
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Clear console",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        // Selectable: long-press any build/run output to copy it (error
        // messages, compiler output, backtraces).
        SelectionContainer(Modifier.weight(1f)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
            ) {
                itemsIndexed(lines) { _, line ->
                    val color = when (line.stream) {
                        Stream.STDOUT -> palette.stdout
                        Stream.STDERR -> palette.stderr
                        Stream.SYSTEM -> palette.system
                    }
                    Text(
                        line.text.ifEmpty { " " },
                        style = MonoSmall,
                        color = color,
                    )
                }
            }
        }
        // stdin is only meaningful while a program is actually running —
        // hiding it otherwise removes the dead send button confusion.
        if (running) {
            StdinBar(onSend = onSend)
        }
    }
}

@Composable
private fun StdinBar(onSend: (String) -> Unit) {
    val palette = LocalEditorPalette.current
    var input by remember { mutableStateOf("") }

    fun submit() {
        val text = input
        if (text.isNotEmpty()) {
            onSend(text)
            input = ""
        }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 10.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("> ", color = MaterialTheme.colorScheme.primary, style = MonoSmall)
        BasicTextField(
            value = input,
            onValueChange = { input = it },
            singleLine = true,
            textStyle = MonoSmall.copy(color = palette.stdout),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { submit() }),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        IconButton(
            onClick = { submit() },
            enabled = input.isNotEmpty(),
            modifier = Modifier.size(32.dp),
        ) {
            Icon(
                RdIcons.Send,
                contentDescription = "Send to program",
                tint = if (input.isNotEmpty()) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun ProblemsPanel(
    problems: List<Diagnostic>,
    onJump: (Diagnostic) -> Unit,
) {
    if (problems.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "no problems — build clean",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        itemsIndexed(problems) { _, d ->
            val tint = when (d.severity) {
                Severity.ERROR -> MaterialTheme.colorScheme.error
                Severity.WARNING -> Color(0xFFF5A623)
                Severity.NOTE -> MaterialTheme.colorScheme.tertiary
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = d.file != null) { onJump(d) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        buildString {
                            append(d.severity.name.lowercase())
                            d.code?.let { append(" [$it]") }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = tint,
                    )
                    Text(
                        d.message,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 3,
                    )
                    if (d.file != null) {
                        Text(
                            "${d.file}:${d.line ?: "?"}:${d.col ?: "?"}",
                            style = MonoSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
