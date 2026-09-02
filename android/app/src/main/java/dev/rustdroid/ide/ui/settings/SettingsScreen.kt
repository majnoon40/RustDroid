package dev.rustdroid.ide.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.rustdroid.ide.di.AppContainer
import dev.rustdroid.ide.model.ToolchainState
import dev.rustdroid.ide.toolchain.ToolchainDistro
import dev.rustdroid.ide.ui.components.CheckRow
import dev.rustdroid.ide.ui.components.StorageRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(container: AppContainer) {
    val vm: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(container))
    val state by vm.toolchainState.collectAsState()
    val storage by vm.storage.collectAsState()
    val busy by vm.busy.collectAsState()
    val message by vm.message.collectAsState()

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.clearMessage() }
    }
    LaunchedEffect(Unit) { vm.refreshStorage() }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Settings") }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ---- toolchain ----
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Toolchain", style = MaterialTheme.typography.titleMedium)
                    when (state) {
                        is ToolchainState.Ready -> {
                            Text(
                                (state as ToolchainState.Ready).rustcVersion,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                (state as ToolchainState.Ready).cargoVersion,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                "distro: ${ToolchainDistro.RELEASE_TAG} · ${ToolchainDistro.RUST_VERSION} · aarch64-linux-android",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        is ToolchainState.Verifying -> {
                            Text("re-verifying…", style = MaterialTheme.typography.bodySmall)
                            (state as ToolchainState.Verifying).checks.forEach { CheckRow(it) }
                        }
                        is ToolchainState.Failed -> {
                            Text(
                                "FAILED (${(state as ToolchainState.Failed).stage}): " +
                                    (state as ToolchainState.Failed).message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        else -> Text("not installed", style = MaterialTheme.typography.bodySmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { vm.reverify() }, enabled = !busy) {
                            Text("Re-verify health")
                        }
                        Button(onClick = { vm.uninstall() }, enabled = !busy) {
                            Text("Uninstall toolchain")
                        }
                    }
                    Text(
                        "Re-verify runs the full 10-check suite incl. compiling and running a test program.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ---- storage ----
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Storage", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    storage.forEach { (label, bytes) -> StorageRow(label, bytes) }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { vm.clearCaches() }, enabled = !busy) {
                        Text("Clear cargo caches")
                    }
                }
            }

            // ---- about ----
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("About", style = MaterialTheme.typography.titleMedium)
                    Text("RustDroid 0.1.0 — an on-device Rust IDE.", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "Toolchain: rust ${ToolchainDistro.RUST_VERSION} (aarch64-linux-android), built from source " +
                            "by GitHub Actions; bundled via the rustdroid-link kit (crt objects, bionic stubs, " +
                            "libunwind.a, cc shim -> ld.lld).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Editor: sora-editor (LGPL-2.1) with the VS Code Rust grammar (MIT). " +
                            "Free software; F-Droid distribution intended.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
