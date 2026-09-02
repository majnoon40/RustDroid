package dev.rustdroid.ide.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import dev.rustdroid.ide.di.AppContainer
import dev.rustdroid.ide.model.ConsoleLine
import dev.rustdroid.ide.model.Diagnostic
import dev.rustdroid.ide.model.FileNode
import dev.rustdroid.ide.model.Severity
import dev.rustdroid.ide.model.Stream
import dev.rustdroid.ide.ui.theme.LocalEditorPalette
import dev.rustdroid.ide.ui.theme.MonoSmall

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    container: AppContainer,
    projectName: String,
    onOpenDeps: (String) -> Unit,
) {
    val vm: EditorViewModel = viewModel(
        key = "editor-$projectName",
        factory = EditorViewModel.factory(container, projectName),
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

    val dark = isSystemInDarkTheme()
    val palette = LocalEditorPalette.current

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

    ModalNavigationDrawer(
        drawerContent = {
            ModalDrawerSheet {
                Column(Modifier.fillMaxHeight()) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("Files", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { vm.toggleAllFiles() }) {
                            Text(if (allFiles) "Sources" else "All files")
                        }
                    }
                    LazyColumn(Modifier.weight(1f)) {
                        items(tree, key = { it.relativePath }) { node ->
                            FileTreeRow(node) { rel, isDir ->
                                if (!isDir) vm.openFile(rel)
                                drawerScope.launch { drawerState.close() }
                            }
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
                        IconButton(onClick = { drawerScope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Folder, contentDescription = "Files")
                        }
                    },
                    title = { Text(projectName) },
                    actions = {
                        TextButton(onClick = { onOpenDeps(projectName) }) { Text("Deps") }
                        if (running) {
                            IconButton(onClick = { vm.stop() }) {
                                Icon(Icons.Filled.Stop, contentDescription = "Stop", tint = MaterialTheme.colorScheme.error)
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
            Column(Modifier.fillMaxSize().padding(padding)) {
                // ---- tabs row ----
                if (tabs.isNotEmpty()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        tabs.forEachIndexed { i, tab ->
                            Tab(
                                selected = i == activeIndex,
                                onClick = { vm.setActive(i) },
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (tab.dirty) {
                                            Box(
                                                Modifier
                                                    .width(6.dp).height(6.dp)
                                                    .background(
                                                        MaterialTheme.colorScheme.primary,
                                                        androidx.compose.foundation.shape.CircleShape
                                                    )
                                            )
                                            Spacer(Modifier.width(6.dp))
                                        }
                                        Text(
                                            tab.relativePath.substringAfterLast('/'),
                                            maxLines = 1,
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        IconButton(
                                            onClick = { vm.closeTab(i, force = false) },
                                            modifier = Modifier.height(24.dp).width(24.dp),
                                        ) {
                                            Icon(
                                                Icons.Filled.Close, contentDescription = "Close",
                                                modifier = Modifier.height(14.dp).width(14.dp),
                                            )
                                        }
                                    }
                                },
                            )
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
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                // ---- bottom panel ----
                val errorCount = problems.count { it.severity == Severity.ERROR }
                val warningCount = problems.count { it.severity == Severity.WARNING }
                Surface(tonalElevation = 2.dp) {
                    Column(Modifier.fillMaxWidth()) {
                        TabRow(selectedTabIndex = if (bottomTab == EditorViewModel.BottomTab.CONSOLE) 0 else 1) {
                            Tab(
                                selected = bottomTab == EditorViewModel.BottomTab.CONSOLE,
                                onClick = { vm.selectBottomTab(EditorViewModel.BottomTab.CONSOLE) },
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Filled.Terminal, null, Modifier.height(14.dp).width(14.dp))
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
                                .height(220.dp)
                                .background(palette.consoleBg)
                        ) {
                            when (bottomTab) {
                                EditorViewModel.BottomTab.CONSOLE -> ConsolePanel(
                                    lines = consoleLines,
                                    running = running,
                                    onSend = { vm.sendStdin(it) },
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

    if (showCloseConfirm) {
        AlertDialog(
            onDismissRequest = { vm.confirmClose(save = false) },
            title = { Text("Unsaved changes") },
            text = { Text("Save before closing this tab?") },
            confirmButton = { TextButton(onClick = { vm.confirmClose(save = true) }) { Text("Save & close") } },
            dismissButton = { TextButton(onClick = { vm.confirmClose(save = false) }) { Text("Discard") } },
        )
    }
}

@Composable
private fun FileTreeRow(node: FileNode, onOpen: (String, Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onOpen(node.relativePath, node.isDirectory) }
            .padding(
                start = (12 + node.depth * 16).dp,
                top = 8.dp, bottom = 8.dp, end = 12.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (node.isDirectory) {
            Icon(
                Icons.Filled.Folder, null,
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

@Composable
private fun ConsolePanel(
    lines: List<ConsoleLine>,
    running: Boolean,
    onSend: (String) -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState,
) {
    val palette = LocalEditorPalette.current
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
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
        // stdin
        var input by remember { mutableStateOf("") }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("> ", color = palette.system, style = MonoSmall)
            BasicTextField(
                value = input,
                onValueChange = { input = it },
                singleLine = true,
                textStyle = MonoSmall.copy(color = palette.stdout),
                modifier = Modifier.weight(1f),
                enabled = true,
            )
            TextButton(
                onClick = { if (input.isNotEmpty()) { onSend(input); input = "" } },
                enabled = input.isNotEmpty() && running,
            ) { Text("send") }
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
