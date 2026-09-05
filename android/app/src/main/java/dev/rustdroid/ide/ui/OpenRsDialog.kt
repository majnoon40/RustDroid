package dev.rustdroid.ide.ui

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.rustdroid.ide.di.AppContainer
import dev.rustdroid.ide.model.ProjectSummary
import dev.rustdroid.ide.projects.RsImport
import dev.rustdroid.ide.ui.components.RdIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Open with RustDroid" landing dialog for ACTION_VIEW .rs intents. The
 * user picks the destination instead of files landing in a fixed scratch
 * project: create a NEW project they name right here, or add the file to
 * an EXISTING project (internal or an opened-in-place folder). Everything
 * is template-based (no toolchain needed), so this works before install —
 * editing right away, Run once the toolchain is up.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenRsDialog(
    container: AppContainer,
    uri: Uri,
    /** Navigates to the landed file: project ref (name or absolute path) + relative path. */
    onOpened: (ref: String, relativePath: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val repo = container.projectRepository
    val scope = rememberCoroutineScope()

    var source by remember { mutableStateOf<RsImport.Source?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var projects by remember { mutableStateOf<List<ProjectSummary>>(emptyList()) }
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(uri) {
        val peeked = withContext(Dispatchers.IO) {
            runCatching { container.rsImport.peek(uri) }
        }
        peeked.fold(
            onSuccess = {
                source = it
                if (name.isEmpty()) name = RsImport.suggestProjectName(it.fileName)
            },
            onFailure = { loadError = it.message ?: "cannot read the file" },
        )
        projects = withContext(Dispatchers.IO) { repo.list() }
    }

    val nameOk = name.matches(Regex("[a-zA-Z][a-zA-Z0-9_-]*"))

    fun createNewProject() {
        val s = source ?: return
        if (busy || !nameOk) return
        busy = true
        actionError = null
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    val dir = repo.createStandaloneProject(name.trim())
                    repo.importRsContent(dir, s.fileName, s.content) to dir.name
                }
            }.fold(
                onSuccess = { (rel, ref) -> onOpened(ref, rel) },
                onFailure = { actionError = it.message ?: "could not create the project" },
            )
            busy = false
        }
    }

    fun addToExisting(project: ProjectSummary) {
        val s = source ?: return
        if (busy) return
        busy = true
        actionError = null
        val ref = repo.refOf(project.dir)
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching { repo.importRsContent(project.dir, s.fileName, s.content) }
            }.fold(
                onSuccess = { rel -> onOpened(ref, rel) },
                onFailure = { actionError = it.message ?: "could not add the file" },
            )
            busy = false
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = {
            Text(
                source?.let { "Open '${it.fileName}'" } ?: "Open Rust file",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    loadError != null -> Text(
                        loadError ?: "",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )

                    source == null -> Text(
                        "reading file…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    else -> {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            singleLine = true,
                            enabled = !busy,
                            label = { Text("New project name") },
                            isError = name.isNotEmpty() && !nameOk,
                            supportingText = {
                                if (name.isNotEmpty() && !nameOk) {
                                    Text("letters, digits, - and _; must start with a letter")
                                }
                            },
                        )
                        HorizontalDivider()
                        Text(
                            "…or add to an existing project:",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (projects.isEmpty()) {
                            Text(
                                "no projects yet — create one above",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            LazyColumn(
                                Modifier.fillMaxWidth().heightIn(max = 208.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                items(projects, key = { it.dir.absolutePath }) { project ->
                                    ProjectRow(project, enabled = !busy) { addToExisting(project) }
                                }
                            }
                        }
                        if (busy) {
                            Text(
                                "importing…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (actionError != null) {
                            Text(
                                actionError ?: "",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { createNewProject() },
                enabled = !busy && source != null && nameOk,
            ) { Text("Create project") }
        },
        dismissButton = {
            TextButton(onClick = { if (!busy) onDismiss() }, enabled = !busy) { Text("Cancel") }
        },
    )
}

@Composable
private fun ProjectRow(
    project: ProjectSummary,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            RdIcons.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(project.name, style = MaterialTheme.typography.bodyMedium)
            Text(
                if (project.external) project.dir.parent ?: project.dir.path
                else "in RustDroid projects",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
