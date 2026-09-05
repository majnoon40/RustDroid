package dev.rustdroid.ide.ui.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.rustdroid.ide.di.AppContainer
import dev.rustdroid.ide.model.ProjectSummary
import dev.rustdroid.ide.projects.RsImport
import dev.rustdroid.ide.ui.components.ConfirmDialog
import dev.rustdroid.ide.ui.components.EmptyState
import dev.rustdroid.ide.ui.components.RdIcons
import java.io.File
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
    var folderCandidate by remember { mutableStateOf<File?>(null) }
    var adopting by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(Unit) { vm.refresh() }

    // ---- open folder in place ----
    // Permission first (legacy storage model at targetSdk 28 — see
    // build.gradle.kts), then the system folder picker; the picked tree
    // is translated to a real path and validated by the VM.
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            vm.linkFolder(
                uri,
                onReady = { folderCandidate = it },
                onError = { vm.reportError(it) },
            )
        }
    }
    val storagePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            folderPicker.launch(null)
        } else {
            vm.reportError(
                "storage access is required to edit a folder in place — grant it and try again",
            )
        }
    }
    val openFolderPicker = {
        when (val perm = vm.storagePermissionToRequest()) {
            null -> folderPicker.launch(null)
            else -> storagePermission.launch(perm)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("RustDroid") },
                actions = {
                    IconButton(onClick = openFolderPicker) {
                        Icon(RdIcons.FolderOpen, contentDescription = "Open folder as project")
                    }
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
                Column(
                    Modifier.align(Alignment.Center).padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    EmptyState(
                        icon = RdIcons.Folder,
                        title = "No cargo projects yet",
                        subtitle = "Create one with cargo new — it compiles and runs right on this device.",
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = openFolderPicker) {
                        Icon(RdIcons.FolderOpen, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Open a folder as a project")
                    }
                }
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
                        onClick = {
                            onOpenProject(container.projectRepository.refOf(project.dir))
                        },
                        onDelete = { deleteTarget = project },
                        onRename = { if (!project.external) renameTarget = project },
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
        if (target.external) {
            // external folders belong to the user — removing the entry must
            // never delete the underlying files
            ConfirmDialog(
                title = "Remove '${target.name}' from RustDroid?",
                text = "The folder stays where it is, with every file untouched — " +
                    "only the RustDroid project entry is removed.",
                confirmLabel = "Remove",
                onConfirm = { vm.removeExternal(target); deleteTarget = null },
                onDismiss = { deleteTarget = null },
            )
        } else {
            ConfirmDialog(
                title = "Delete '${target.name}'?",
                text = "The whole project directory is removed, including target/ build outputs.",
                confirmLabel = "Delete",
                onConfirm = { vm.delete(target); deleteTarget = null },
                onDismiss = { deleteTarget = null },
            )
        }
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

    folderCandidate?.let { dir ->
        AdoptFolderDialog(
            dir = dir,
            busy = adopting,
            onAdopt = { withCargo, packageName ->
                adopting = true
                vm.adoptFolder(dir, withCargo, packageName) { ref, err ->
                    adopting = false
                    folderCandidate = null
                    if (ref != null) onOpenProject(ref) else vm.reportError(err ?: "failed")
                }
            },
            onDismiss = { if (!adopting) folderCandidate = null },
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (project.external) {
                        Icon(
                            RdIcons.FolderOpen,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.height(16.dp).width(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        project.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
                Text(
                    "modified ${fmt.format(Date(project.lastModifiedMs))}" +
                        when {
                            project.dependencyCount >= 0 -> " · ${project.dependencyCount} deps"
                            project.external -> " · no Cargo.toml (editing only)"
                            else -> " · Cargo.toml unreadable"
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (project.external) {
                    // opened in place: show where it lives on storage
                    Text(
                        project.dir.parent ?: project.dir.path,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (!project.external) {
                IconButton(onClick = onRename) {
                    Icon(Icons.Filled.Edit, contentDescription = "Rename", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = if (project.external) "Remove from RustDroid" else "Delete",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Landing dialog for a folder picked to be opened IN PLACE: the folder is
 * never copied or moved — RustDroid edits it where it lives. Folders
 * without a manifest can be turned into a cargo project right there
 * (Cargo.toml + src/main.rs written into the folder, only when missing),
 * or opened for plain editing.
 */
@Composable
private fun AdoptFolderDialog(
    dir: File,
    busy: Boolean,
    onAdopt: (withCargo: Boolean, packageName: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val hasManifest = File(dir, "Cargo.toml").isFile
    var name by remember(dir) {
        mutableStateOf(RsImport.suggestProjectName(dir.name + ".rs"))
    }
    val nameOk = name.matches(Regex("[a-zA-Z][a-zA-Z0-9_-]*"))

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Open '${dir.name}' in place?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "The folder stays where it is and every edit saves straight into it — " +
                        "nothing is copied or moved.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    dir.path,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!hasManifest) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        enabled = !busy,
                        label = { Text("Cargo package name") },
                        isError = name.isNotEmpty() && !nameOk,
                        supportingText = {
                            if (name.isNotEmpty() && !nameOk) {
                                Text("letters, digits, - and _; must start with a letter")
                            }
                        },
                    )
                }
                if (busy) {
                    Text("opening…", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            if (hasManifest) {
                TextButton(
                    onClick = { onAdopt(false, name) },
                    enabled = !busy,
                ) { Text("Open") }
            } else {
                TextButton(
                    onClick = { onAdopt(true, name) },
                    enabled = !busy && nameOk,
                ) { Text("Add Cargo.toml") }
            }
        },
        dismissButton = {
            if (!hasManifest) {
                // plain-folder mode: edit files, no cargo scaffold
                TextButton(
                    onClick = { onAdopt(false, name) },
                    enabled = !busy,
                ) { Text("Just edit files") }
            } else {
                TextButton(onClick = { if (!busy) onDismiss() }, enabled = !busy) { Text("Cancel") }
            }
        },
    )
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
                        log.takeLast(800),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 10,
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
