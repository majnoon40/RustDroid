package dev.rustdroid.ide.toolchain

import android.content.Context
import android.net.Uri
import dev.rustdroid.ide.model.CheckStatus
import dev.rustdroid.ide.model.ToolchainState
import dev.rustdroid.ide.runtime.CaBundle
import dev.rustdroid.ide.runtime.CargoRunner
import dev.rustdroid.ide.runtime.ProcEnv
import dev.rustdroid.ide.util.Fs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File

/**
 * Owns the toolchain lifecycle: install (network or imported zip), verify,
 * state, uninstall. Single-flight: concurrent callers await the same install.
 * State is observable for the Gate screen + Settings.
 */
class ToolchainManager(
    private val context: Context,
    val paths: ToolchainPaths,
    private val runner: CargoRunner,
    private val http: OkHttpClient,
) {
    private val _state = MutableStateFlow<ToolchainState>(initialState())
    val state: StateFlow<ToolchainState> = _state.asStateFlow()

    private val mutex = Mutex()
    private val uiScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + Dispatchers.Main
    )

    private val downloader = ArtifactDownloader(http)
    private val extractor = ArtifactExtractor(paths)
    private val verifier = ToolchainVerifier(paths, context.filesDir, runner)

    init {
        // Installs marked Ready by an older app version never re-run verify,
        // so backfill config.toml CA trust + warm the bundle once at startup.
        // Idempotent and IO-dispatched; skips entirely when not installed.
        if (_state.value is ToolchainState.Ready) {
            uiScope.launch { writeCargoDefaults() }
        }
    }

    /** Extra log lines surfaced by the UI (extraction/verify output tail). */
    val logTail = ArrayDeque<String>()

    private fun initialState(): ToolchainState {
        // Fast path: the ready marker is only written after a fully green
        // verification (including the compile+link+run smoke test), so its
        // presence + structural isInstalled() is sufficient to be Ready.
        // This keeps app startup off subprocess probes (no runBlocking on
        // the main thread); Settings "Re-verify health" re-runs everything.
        if (paths.isInstalled() && paths.readyMarker.isFile) {
            val lines = runCatching { paths.readyMarker.readLines() }
                .getOrDefault(emptyList())
            fun value(key: String) =
                lines.firstOrNull { it.startsWith("$key=") }?.removePrefix("$key=")
            return ToolchainState.Ready(
                value("rustc_version") ?: "rustc ${ToolchainDistro.RUST_VERSION}",
                value("cargo_version") ?: "cargo ${ToolchainDistro.RUST_VERSION}",
            )
        }
        return ToolchainState.NotInstalled
    }

    private fun firstLine(s: String) = s.lineSequence().firstOrNull() ?: s

    private fun log(line: String) {
        synchronized(logTail) {
            logTail.addLast(line)
            while (logTail.size > 200) logTail.removeFirst()
        }
    }

    /** Install path A: download the pinned bundle from the GitHub release. */
    suspend fun installFromNetwork() =
        installWith { zip ->
            _state.value = ToolchainState.Downloading(0, ToolchainDistro.expectedSizeBytes)
            withContext(Dispatchers.IO) {
                downloader.downloadBlocking(
                    ToolchainDistro.url,
                    zip,
                    ToolchainDistro.SHA256.takeIf { it.length == 64 },
                ) { bytes, total ->
                    _state.value = ToolchainState.Downloading(bytes, total)
                }
            }
            log("download complete: ${Fs.humanBytes(zip.length())}")
            zip
        }

    /**
     * Install path B: user-imported zip (SAF). [open] yields the zip stream;
     * we spool it to the cache file, then run the shared install pipeline.
     */
    suspend fun installFromImport(open: () -> java.io.InputStream) =
        installWith { zip ->
            _state.value = ToolchainState.Downloading(0, null)
            // multi-GB spool copy — must not run on the caller (Main) thread
            withContext(Dispatchers.IO) {
                zip.parentFile?.mkdirs()
                val tmp = File(zip.parentFile, zip.name + ".part")
                open().use { input ->
                    tmp.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var copied = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            copied += n
                            if (copied % (4 * 1024 * 1024) == 0L) {
                                _state.value = ToolchainState.Downloading(copied, null)
                            }
                        }
                    }
                }
                if (zip.exists()) zip.delete()
                check(tmp.renameTo(zip)) { "cannot finalize imported zip" }
            }
            log("import complete: ${Fs.humanBytes(zip.length())}")
            zip
        }

    private suspend fun installWith(fetch: suspend (File) -> File) = mutex.withLock {
        try {
            if (paths.isInstalled()) {
                uninstallLocked()
            }
            paths.ensureDirs()
            ProcEnv.ensureDirs(context.filesDir)

            val zip = fetch(paths.bundleZip)

            // Extract
            _state.value = ToolchainState.Extracting(0, null)
            withContext(Dispatchers.IO) {
                val info = extractor.install(zip) { done, total ->
                    _state.value = ToolchainState.Extracting(done, total)
                }
                log("toolchain ${info.rustVersion} installed (kit files: ${info.kitEntryCount})")
            }

            // Verify — the smoke test is the gate
            runVerifyLocked()

            // Clean the 100+ MB zip: prefix is self-contained now
            paths.bundleZip.delete()
        } catch (e: Exception) {
            val stage = when (val s = _state.value) {
                is ToolchainState.Downloading -> "download"
                is ToolchainState.Extracting -> "extraction"
                is ToolchainState.Verifying -> "verification"
                else -> "install"
            }
            log("FAILED at $stage: ${e.message}")
            _state.value = ToolchainState.Failed(stage, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun ToolchainToolchainProgress(done: Int, total: Int?): ToolchainState =
        ToolchainState.Extracting(done, total)

    /** Re-run the full health check (Settings button, or after import). */
    suspend fun reverify() = mutex.withLock {
        if (!paths.isInstalled()) {
            _state.value = ToolchainState.NotInstalled
            return
        }
        runVerifyLocked()
    }

    private suspend fun runVerifyLocked() {
        _state.value = ToolchainState.Verifying(emptyList())
        val order = mutableListOf<String>()
        val checks = withContext(Dispatchers.IO) {
            verifier.verify { check ->
                if (check.id !in order) order += check.id
                val list = (_state.value as? ToolchainState.Verifying)?.checks ?: emptyList()
                val next = (list.filter { it.id != check.id } + check)
                    .sortedBy { order.indexOf(it.id) }
                _state.value = ToolchainState.Verifying(next)
            }
        }
        val failed = checks.filter { it.status == CheckStatus.FAIL }
        if (failed.isEmpty()) {
            val env = ProcEnv.env(paths.prefix, context.filesDir)
            val rustc = runner.probe(listOf(paths.rustc.absolutePath, "--version"), env)
            val cargo = runner.probe(listOf(paths.cargo.absolutePath, "--version"), env)
            paths.readyMarker.writeText(
                buildString {
                    appendLine("verified=${System.currentTimeMillis()}")
                    appendLine("rustc_version=${firstLine(rustc)}")
                    appendLine("cargo_version=${firstLine(cargo)}")
                }
            )
            writeCargoDefaults()
            _state.value = ToolchainState.Ready(firstLine(rustc), firstLine(cargo))
            log("verification PASSED — toolchain ready")
        } else {
            val first = failed.first()
            _state.value = ToolchainState.Failed(
                "verification",
                "check '${first.title}' failed: ${first.detail ?: "unknown"}"
            )
            log("verification FAILED (${failed.size} checks)")
        }
    }

    fun uninstall() {
        kotlinx.coroutines.runBlocking { mutex.withLock { uninstallLocked() } }
    }

    private fun uninstallLocked() {
        Fs.deleteRecursively(paths.prefix)
        paths.readyMarker.delete()
        paths.bundleZip.delete()
        _state.value = ToolchainState.NotInstalled
        log("toolchain removed")
    }

    /**
     * Writes $CARGO_HOME/config.toml with `new.vcs = "none"` (the bundle
     * ships no git binary, and cargo's default is vcs=git) and merges
     * `http.cainfo` pointing at the generated CA bundle — cargo's
     * crates.io downloads die with libcurl error 77 ("Problem with the
     * SSL CA cert") or 60 otherwise (statically linked OpenSSL has no
     * Android trust store). User-set values are never clobbered.
     *
     * Never leaves a dangling cainfo: cargo turns a missing CAfile into
     * error 77 *before any network I/O*, so when no usable bundle can be
     * built (no APK asset, no readable system store), an app-managed
     * cainfo value pointing at the missing file is stripped instead.
     */
    private suspend fun writeCargoDefaults() = withContext(Dispatchers.IO) {
        runCatching {
            // Warm the bundle first so cainfo points at a real file even
            // if the first cargo invocation races ProcEnv's lazy ensure().
            // The APK asset fallback keeps this working on devices whose
            // system CA store is empty or unreadable (Android 14+/OEM).
            val bundle = CaBundle.ensure(
                context.filesDir, paths.prefix,
                assetProvider = { CaBundle.readAssetPem(context.assets) },
            )
            val canonical = CaBundle.bundleFile(context.filesDir).absolutePath
            val cargoHome = ProcEnv.cargoHome(context.filesDir)
            cargoHome.mkdirs()
            val cfg = File(cargoHome, "config.toml")
            if (bundle == null) {
                stripAppManagedCainfo(cfg, canonical)
                return@runCatching
            }
            val cainfo = bundle.absolutePath
            if (!cfg.isFile) {
                cfg.writeText(
                    "[new]\nvcs = \"none\"\n\n[http]\ncainfo = \"$cainfo\"\n"
                )
                return@runCatching
            }
            val text = cfg.readText()
            // present and pointing at the canonical bundle -> nothing to do;
            // present with any other value -> user-set, leave it alone
            if (cainfoValue(text) != null) {
                return@runCatching
            }
            if (Regex("""(?m)^\[http\]""").containsMatchIn(text)) {
                cfg.writeText(
                    text.replaceFirst(
                        Regex("""(?m)^(\[http\])[ \t]*$"""),
                        "$1\ncainfo = \"$cainfo\"",
                    )
                )
            } else {
                cfg.writeText(
                    text.trimEnd() + "\n\n[http]\ncainfo = \"$cainfo\"\n"
                )
            }
        }
    }

    /** Current `cainfo` value in a config.toml text, if any. */
    private fun cainfoValue(text: String): String? =
        Regex("""(?m)^\s*cainfo\s*=\s*"([^"]*)"\s*$""").find(text)?.groupValues?.get(1)

    /**
     * Removes an app-managed cainfo line (value == [canonical]) from [cfg]
     * when that value can no longer be backed by a real bundle file. Any
     * other cainfo value is considered user-set and kept untouched.
     */
    private fun stripAppManagedCainfo(cfg: File, canonical: String) {
        if (!cfg.isFile) return
        val text = runCatching { cfg.readText() }.getOrNull() ?: return
        val current = cainfoValue(text) ?: return
        if (current != canonical) return
        val cleaned = text.lines()
            .filterNot { it.trim().startsWith("cainfo") && it.contains(canonical) }
            .joinToString("\n")
        runCatching { cfg.writeText(cleaned) }
    }

    /** Convenience: [uri] content import via SAF (fire-and-forget from UI). */
    fun launchImport(uri: Uri) {
        uiScope.launch { installFromUri(uri) }
    }

    suspend fun installFromUri(uri: Uri): Boolean {
        return try {
            val stream = context.contentResolver.openInputStream(uri)
                ?: throw java.io.IOException("cannot open $uri")
            stream.use { s -> installFromImport { s } }
            _state.value is ToolchainState.Ready
        } catch (e: Exception) {
            false
        }
    }
}
