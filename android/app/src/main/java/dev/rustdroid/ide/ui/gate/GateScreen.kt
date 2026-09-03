package dev.rustdroid.ide.ui.gate

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.rustdroid.ide.di.AppContainer
import dev.rustdroid.ide.model.ToolchainState
import dev.rustdroid.ide.toolchain.ToolchainDistro
import dev.rustdroid.ide.toolchain.ToolchainInstallService
import dev.rustdroid.ide.ui.components.CheckRow
import dev.rustdroid.ide.ui.components.RdIcons
import dev.rustdroid.ide.util.Fs

@Composable
fun GateScreen(
    container: AppContainer,
    onReady: () -> Unit,
) {
    val manager = container.toolchainManager
    val state by manager.state.collectAsState()

    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* proceed regardless: FGS works without visible notification */ }

    val zipPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            manager.launchImport(uri)
        }
    }

    // Scaffold paints the theme background (the window behind it can be the
    // XML theme's gray) and insets the content below the status bar, so the
    // hero sits comfortably in the middle of the screen instead of the very
    // top edge.
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            // Centered when content is short; scrolls when it grows
            // (progress states + log tail on small screens).
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                RdIcons.Terminal,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(64.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "RustDroid",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "A Rust toolchain that lives on this device: " +
                    "${ToolchainDistro.RUST_VERSION} for aarch64 Android, with the " +
                    "on-device link kit Phase 1 proved out.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (state) {
                    is ToolchainState.NotInstalled -> InstallPrompt(
                        onDownload = {
                            if (Build.VERSION.SDK_INT >= 33) {
                                notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            ToolchainInstallService.start(container.context)
                        },
                        onImport = {
                            zipPicker.launch(
                                arrayOf("application/zip", "application/x-zip-compressed")
                            )
                        },
                    )

                    is ToolchainState.Downloading -> DownloadCard(state as ToolchainState.Downloading)

                    is ToolchainState.Extracting -> ExtractCard(state as ToolchainState.Extracting)

                    is ToolchainState.Verifying -> VerifyCard(state as ToolchainState.Verifying)

                    is ToolchainState.Failed -> FailedCard(
                        state as ToolchainState.Failed,
                        onRetry = { ToolchainInstallService.start(container.context) },
                        onImport = { zipPicker.launch(arrayOf("application/zip")) },
                    )

                    is ToolchainState.Ready -> ReadyCard()
                }

                // Navigation is driven from AppRoot; the card is the visual
                // confirmation between the state flip and the screen change.
                if (state is ToolchainState.Ready) {
                    LaunchedEffect(Unit) { onReady() }
                }

                LogTail(manager)
            }
        }
    }
}

@Composable
private fun ReadyCard() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        )
    ) {
        Column(
            Modifier.padding(20.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Toolchain verified — opening your projects…",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                "rustc, cargo, std and the link kit all passed the on-device smoke test.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun InstallPrompt(onDownload: () -> Unit, onImport: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(20.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "First run: install the toolchain",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "Downloads the RustDroid bundle (${Fs.humanBytes(ToolchainDistro.expectedSizeBytes)}) — " +
                    "rustc, cargo, std and the link kit — from the project's GitHub release. " +
                    "The download runs in a foreground service and survives screen-off.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (ToolchainDistro.isPinned) {
                Text(
                    "Integrity: SHA-256 pinned in-app — a corrupted or tampered " +
                        "download fails loudly instead of producing a broken install.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = onDownload,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Download (${Fs.humanBytes(ToolchainDistro.expectedSizeBytes)})") }
            OutlinedButton(
                onClick = onImport,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Import zip file…") }
            Text(
                "Offline or on metered data? Download the bundle on a PC, copy it " +
                    "to the phone, and import it here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DownloadCard(state: ToolchainState.Downloading) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(20.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Downloading toolchain…",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            val knownTotal = state.total != null && state.total > 0
            val pct = if (knownTotal) {
                (state.bytes * 100f / state.total!!).coerceIn(0f, 100f)
            } else null
            if (pct != null) {
                LinearProgressIndicator(
                    progress = { pct / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "${pct.toInt()}% — ${Fs.humanBytes(state.bytes)} of ${Fs.humanBytes(state.total!!)}",
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    "${Fs.humanBytes(state.bytes)} downloaded",
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                "SHA-256 is checked when the download completes. You can put the " +
                    "app in the background — the download continues.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ExtractCard(state: ToolchainState.Extracting) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(20.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Extracting toolchain…",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            LinearProgressIndicator(Modifier.fillMaxWidth())
            val entries = state.done
            Text(
                if (entries > 0) "$entries entries placed" else "starting…",
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "Placing ~117 MB under files/usr — file modes preserved from tar. " +
                    "This takes a minute or two.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun VerifyCard(state: ToolchainState.Verifying) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(vertical = 12.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "Verifying install health",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Text(
                "The last check compiles, links AND runs hello.rs on-device — the same gate CI runs.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            state.checks.forEach { CheckRow(it) }
        }
    }
}

@Composable
private fun FailedCard(state: ToolchainState.Failed, onRetry: () -> Unit, onImport: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            Modifier.padding(20.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Install failed at ${state.stage}",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                state.message,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onRetry) { Text("Retry download") }
                OutlinedButton(onClick = onImport) { Text("Import zip…") }
            }
        }
    }
}

@Composable
private fun LogTail(manager: dev.rustdroid.ide.toolchain.ToolchainManager) {
    val log = manager.logTail
    if (log.isEmpty()) return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "log",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            for (line in log.takeLast(8)) {
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                )
            }
        }
    }
}
