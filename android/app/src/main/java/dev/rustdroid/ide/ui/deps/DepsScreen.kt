package dev.rustdroid.ide.ui.deps

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.rustdroid.ide.di.AppContainer
import dev.rustdroid.ide.model.CrateSummary
import dev.rustdroid.ide.ui.components.SectionTitle
import dev.rustdroid.ide.util.Fs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DepsScreen(
    container: AppContainer,
    projectName: String,
    onBack: () -> Unit = {},
) {
    val vm: DepsViewModel = viewModel(
        key = "deps-$projectName",
        factory = DepsViewModel.factory(container, projectName),
    )
    val deps by vm.deps.collectAsState()
    val query by vm.query.collectAsState()
    val results by vm.results.collectAsState()
    val searching by vm.searching.collectAsState()
    val message by vm.message.collectAsState()
    val fetching by vm.fetching.collectAsState()
    val fetchLog by vm.fetchLog.collectAsState()

    val snackbar = remember { SnackbarHostState() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("Dependencies — $projectName") },
                actions = {
                    if (fetching) {
                        CircularProgressIndicator(
                            Modifier
                                .padding(end = 16.dp)
                                .size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        TextButton(onClick = { vm.fetchNow() }) { Text("Fetch") }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        // imePadding keeps the search box above the keyboard on edge-to-edge
        // builds (adjustResize alone does not resize once edge-to-edge is on).
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { vm.setQuery(it) },
                label = { Text("Search crates.io") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = {
                    when {
                        searching -> CircularProgressIndicator(
                            Modifier.size(18.dp), strokeWidth = 2.dp,
                        )
                        query.isNotEmpty() -> IconButton(
                            onClick = {
                                vm.setQuery("")
                                focusManager.clearFocus()
                            },
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        vm.searchNow()
                        focusManager.clearFocus()
                    },
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )

            // ONE scrollable body below the pinned search field. The old
            // layout stacked two independent LazyColumns (weights 0.4/0.6);
            // wheel events jumped between them and content scrolled up into
            // the search box. A single list cannot exhibit that.
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
            ) {
                if (deps.isNotEmpty()) {
                    item(key = "deps-header") {
                        SectionTitle("In Cargo.toml · ${deps.size}")
                    }
                    items(deps, key = { "dep-${it.name}" }) { dep ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(
                                Modifier
                                    .weight(1f)
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                            ) {
                                Text(
                                    dep.name,
                                    style = MaterialTheme.typography.bodyMedium
                                        .copy(fontFamily = FontFamily.Monospace),
                                )
                                Text(
                                    dep.spec,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(
                                onClick = { vm.remove(dep.name) },
                                modifier = Modifier.padding(end = 8.dp),
                            ) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = "Remove ${dep.name}",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        HorizontalDivider(
                            Modifier.padding(start = 16.dp, end = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant
                                .copy(alpha = 0.4f),
                        )
                    }
                }

                if (results.isNotEmpty()) {
                    item(key = "results-header") {
                        SectionTitle("crates.io results · ${results.size}")
                    }
                    items(results, key = { "crate-${it.name}" }) { crate ->
                        CrateResultRow(crate) { vm.add(crate) }
                    }
                } else if (!searching && query.trim().length >= 2 && !fetching) {
                    item(key = "no-results") {
                        Text(
                            "no results for “${query.trim()}”",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                }

                if (fetchLog.isNotEmpty()) {
                    item(key = "fetch-header") {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SectionTitle(
                                if (fetching) "fetch output — downloading…" else "fetch output",
                                Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = { vm.clearFetchLog() },
                                enabled = !fetching,
                            ) { Text("Clear") }
                        }
                    }
                    // Single selectable text block: long-press selects, so the
                    // whole cargo output can be copied in one gesture.
                    item(key = "fetch-log") {
                        SelectionContainer {
                            Text(
                                fetchLog.takeLast(40).joinToString("\n"),
                                style = MaterialTheme.typography.bodySmall
                                    .copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CrateResultRow(crate: CrateSummary, onAdd: () -> Unit) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onAdd),
    ) {
        Row(
            Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        crate.name,
                        style = MaterialTheme.typography.titleSmall
                            .copy(fontFamily = FontFamily.Monospace),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "v${crate.max_version}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${Fs.humanBytes(crate.downloads)} downloads",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                if (!crate.description.isNullOrBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        crate.description,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onAdd) {
                Icon(
                    Icons.Filled.Add, contentDescription = "Add ${crate.name}",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
