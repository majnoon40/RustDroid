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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.rustdroid.ide.di.AppContainer
import dev.rustdroid.ide.model.ToolchainState
import dev.rustdroid.ide.toolchain.ToolchainDistro
import dev.rustdroid.ide.toolchain.ToolchainInstallService
import dev.rustdroid.ide.ui.components.CheckRow
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("RustDroid", style = MaterialTheme.typography.headlineSmall)
        Text(
            "A self-hosting Rust toolchain lives on this device: ${ToolchainDistro.RUST_VERSION} for aarch64 Android, with the on-device link kit that Phase 1 proved out.",
            style = MaterialTheme.typography.bodyMedium,
        )

        when (state) {
            is ToolchainState.NotInstalled -> InstallPrompt(
                onDownload = {
                    if (Build.VERSION.SDK_INT >= 33) {
                        notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    ToolchainInstallService.start(container.context)
                },
                onImport = { zipPicker.launch(arrayOf("application/zip", "application/x-zip-compressed")) },
            )

            is ToolchainState.Downloading -> DownloadCard(state as ToolchainState.Downloading)

            is ToolchainState.Extracting -> {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Extracting toolchain…", style = MaterialTheme.typography.titleMedium)
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(
                            "Placing ~117 MB under files/usr — modes preserved from tar. This takes a minute or two.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            is ToolchainState.Verifying -> VerifyCard(state as ToolchainState.Verifying)

            is ToolchainState.Failed -> FailedCard(
                state as ToolchainState.Failed,
                onRetry = { ToolchainInstallService.start(container.context) },
                onImport = { zipPicker.launch(arrayOf("application/zip")) },
            )

            is ToolchainState.Ready -> LaunchedEffect(Unit) { onReady() }
        }

        Spacer(Modifier.height(8.dp))
        LogTail(manager)
    }
}

@Composable
private fun InstallPrompt(onDownload: () -> Unit, onImport: () -> Unit) {
    Card(colors = CardDefaults.cardColors()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("First run: install the toolchain", style = MaterialTheme.typography.titleMedium)
            Text(
                "Download the RustDroid app bundle (${Fs.humanBytes(ToolchainDistro.expectedSizeBytes)}, " +
                    "rustc + cargo + std + link kit) from the project's GitHub release. " +
                    "The download runs in the foreground and survives screen-off.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (ToolchainDistro.isPinned) {
                Text(
                    "Integrity: SHA-256 pinned in-app — a corrupted or tampered download fails loudly.",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onDownload) { Text("Download (~117 MB)") }
                OutlinedButton(onClick = onImport) { Text("Import zip…") }
            }
            Text(
                "Offline / metered data? Download the bundle on a PC, push it to the phone, and Import zip here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DownloadCard(state: ToolchainState.Downloading) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Downloading toolchain…", style = MaterialTheme.typography.titleMedium)
            val pct = if (state.total != null && state.total > 0) {
                (state.bytes * 100f / state.total).coerceIn(0f, 100f)
            } else null
            if (pct != null) {
                LinearProgressIndicator(
                    progress = { pct / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Text(
                if (state.total != null && state.total > 0)
                    "${Fs.humanBytes(state.bytes)} of ${Fs.humanBytes(state.total)} — SHA-256 checked on completion"
                else
                    "${Fs.humanBytes(state.bytes)} — SHA-256 checked on completion",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun VerifyCard(state: ToolchainState.Verifying) {
    Card {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(
                "Verifying install health",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            Text(
                "The last check compiles, links AND runs hello.rs on-device — the same gate CI runs.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.checks.forEach { CheckRow(it) }
        }
    }
}

@Composable
private fun FailedCard(state: ToolchainState.Failed, onRetry: () -> Unit, onImport: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Install failed at ${state.stage}",
                style = MaterialTheme.typography.titleMedium,
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
    val log = remember(manager) { manager.logTail }
    if (log.isEmpty()) return
    Card {
        Column(Modifier.padding(12.dp)) {
            Text("log", style = MaterialTheme.typography.labelSmall)
            log.takeLast(8).forEach {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 3,
                )
            }
        }
    }
}
