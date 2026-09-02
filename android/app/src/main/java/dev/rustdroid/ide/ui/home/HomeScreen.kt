package dev.rustdroid.ide.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit

import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.rustdroid.ide.di.AppContainer
import dev.rustdroid.ide.model.ProjectSummary
import dev.rustdroid.ide.ui.components.ConfirmDialog
import dev.rustdroid.ide.ui.components.EmptyState
import dev.rustdroid.ide.ui.components.RdIcons
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    container: AppContainer,
    onOpenProject: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val vm: HomeViewModel = viewModel(factory = HomeViewModel.factory(container))
    val projects by vm.projects.collectAsState()
    val creating by vm.creating.collectAsState()
    val createLog by vm.createLog.collectAsState()
    val error by vm.error.collectAsState()

    var showNewDialog by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<ProjectSummary?>(null) }
    var renameTarget by remember { mutableStateOf<ProjectSummary?>(null) }

    androidx.compose.runtime.LaunchedEffect(Unit) { vm.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("RustDroid") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        floatingActionButton = {
            if (!creating) {
                ExtendedFloatingActionButton(
                    onClick = { showNewDialog = true },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("New project") },
                )
            }
        },
    ) { padding ->
        if (projects.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding)) {
                EmptyState(
                    icon = RdIcons.Folder,
                    title = "No cargo projects yet",
                    subtitle = "Create one with cargo new — it compiles and runs right on this device.",
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(projects, key = { it.dir.absolutePath }) { project ->
                    ProjectCard(
                        project = project,
                        onClick = { onOpenProject(project.name) },
                        onDelete = { deleteTarget = project },
                        onRename = { renameTarget = project },
                    )
                }
            }
        }
    }

    if (showNewDialog) {
        NewProjectDialog(
            busy = creating,
            log = createLog,
            onCreate = { name, isLib ->
                vm.create(name, isLib) { ok ->
                    if (ok) showNewDialog = false
                }
            },
            onDismiss = { if (!creating) showNewDialog = false },
        )
    }

    deleteTarget?.let { target ->
        ConfirmDialog(
            title = "Delete '${target.name}'?",
            text = "The whole project directory is removed, including target/ build outputs.",
            confirmLabel = "Delete",
            onConfirm = { vm.delete(target); deleteTarget = null },
            onDismiss = { deleteTarget = null },
        )
    }

    renameTarget?.let { target ->
        RenameDialog(
            initial = target.name,
            onRename = { newName ->
                vm.rename(target, newName)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }

    error?.let { msg ->
        AlertDialog(
            onDismissRequest = { vm.clearError() },
            title = { Text("Error") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { vm.clearError() }) { Text("OK") } },
        )
    }
}

@Composable
private fun ProjectCard(
    project: ProjectSummary,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(project.name, style = MaterialTheme.typography.titleMedium)
                val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
                Text(
                    "modified ${fmt.format(Date(project.lastModifiedMs))}" +
                        if (project.dependencyCount >= 0) " · ${project.dependencyCount} deps" else " · Cargo.toml unreadable",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onRename) {
                Icon(Icons.Filled.Edit, contentDescription = "Rename", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun NewProjectDialog(
    busy: Boolean,
    log: String?,
    onCreate: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var isLib by remember { mutableStateOf(false) }
    val nameOk = name.matches(Regex("[a-zA-Z][a-zA-Z0-9_-]*"))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New cargo project") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    enabled = !busy,
                    isError = name.isNotEmpty() && !nameOk,
                    supportingText = {
                        if (name.isNotEmpty() && !nameOk) Text("letters, digits, - and _; must start with a letter")
                    },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = isLib, onCheckedChange = { isLib = it }, enabled = !busy)
                    Text("Library (--lib) instead of binary (--bin)")
                }
                if (busy) {
                    Text("running cargo new…", style = MaterialTheme.typography.bodySmall)
                }
                if (log != null) {
                    Text(
                        log.takeLast(400),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 6,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name, isLib) },
                enabled = !busy && nameOk,
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}

@Composable
private fun RenameDialog(initial: String, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val nameOk = name.matches(Regex("[a-zA-Z][a-zA-Z0-9_-]*"))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename project") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                isError = name.isNotEmpty() && !nameOk,
            )
        },
        confirmButton = {
            TextButton(onClick = { onRename(name) }, enabled = nameOk) { Text("Rename") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
