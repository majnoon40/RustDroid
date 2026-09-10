package dev.rustdroid.ide.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.rustdroid.ide.model.LicenseEntry
import dev.rustdroid.ide.model.parseLicenseIndex

/**
 * Settings → Licenses (plan §7.5): a list (component, license, upstream
 * URL, pin) with the FULL license text on tap. Text files live in
 * assets/licenses/, indexed by LICENSE_INDEX.json.
 *
 * The BusyBox source-asset URL is REQUIRED content here, not a nicety
 * (review Part 3 / condition 11): the GPL-2.0 source offer must reach
 * bundle RECIPIENTS, and the bundle is downloaded through the app's own
 * downloader — not the release page — so this screen is where compliance
 * is visible.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val entries by remember {
        mutableStateOf(
            runCatching {
                context.assets.open("licenses/LICENSE_INDEX.json")
                    .readBytes().decodeToString().let(::parseLicenseIndex)
            }.getOrElse { emptyList() },
        )
    }
    var selected by remember { mutableStateOf<LicenseEntry?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Licenses") },
                navigationIcon = {
                    IconButton(onClick = { selected?.let { selected = null } ?: onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        val entry = selected
        if (entry == null) {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            ) {
                items(entries) { e ->
                    Card(Modifier.fillMaxWidth().clickable { selected = e }) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(e.component, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "${e.license} · ${e.pin}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            // The GPL source-offer URL is load-bearing
                            // compliance content — always visible, not
                            // buried behind the tap (plan §7.5).
                            e.sourceAssetUrl?.let { url ->
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "Corresponding source: $url",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                    }
                }
            }
        } else {
            val text = remember(entry) { readLicenseText(context, entry) }
            LazyColumn(
                Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Text(entry.component, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${entry.license} · ${entry.pin}\n${entry.url}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                item {
                    Text(
                        text ?: "(license text missing from APK assets)",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

private fun readLicenseText(context: android.content.Context, entry: LicenseEntry): String? {
    for (name in listOfNotNull(entry.file, entry.file2)) {
        runCatching {
            return context.assets.open("licenses/$name").readBytes().decodeToString()
        }
    }
    return null
}
