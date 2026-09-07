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

    /**
     * Increments every time verification completes successfully (install
     * or re-verify). Unlike [state]'s intermediate Verifying emissions, a
     * counter survives StateFlow conflation: the re-verify smoke test takes
     * minutes, users background the app mid-run, Compose pauses
     * recomposition while stopped and every intermediate Verifying value is
     * conflated away — so "did I see Verifying?" watchers miss the pass.
     * UI code snapshots the tick at screen entry and reacts to any increase.
     */
    private val _verifyPassTick = MutableStateFlow(0L)
    val verifyPassTick: StateFlow<Long> = _verifyPassTick.asStateFlow()

    private val mutex = Mutex()
    private val defaultScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + Dispatchers.Default
    )

    private val downloader = ArtifactDownloader(http)
    private val extractor = ArtifactExtractor(paths)
    private val verifier = ToolchainVerifier(
        paths, context.filesDir, runner,
        caAssetProvider = { CaBundle.readAssetPem(context.assets) },
    )

    init {
        // Installs marked Ready by an older app version never re-run verify,
        // so backfill config.toml CA trust + warm the bundle once at startup.
        // Idempotent and IO-dispatched; skips entirely when not installed.
        if (_state.value is ToolchainState.Ready) {
            defaultScope.launch { writeCargoDefaults() }
        }
        // Crash recovery: an install interrupted between SWAP and READY
        // leaves a pending-transaction marker on disk. Reconcile it BEFORE
        // any user-triggered action can race the repair.
        defaultScope.launch { recoverInterruptedInstall() }
    }

    /**
     * Extra log lines surfaced by the UI (extraction/verify output tail).
     * [logTail] stays private; readers get an immutable snapshot via
     * [logTailSnapshot] — iterating a live ArrayDeque from the UI thread
     * while an install thread mutates it is a ConcurrentModificationException
     * waiting to happen.
     */
    private val logTail = ArrayDeque<String>()

    /** Immutable copy of the log tail (newest last), safe from any thread. */
    fun logTailSnapshot(): List<String> = synchronized(logTail) { logTail.toList() }

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
            // preflight BEFORE any network I/O: the download plus the
            // extracted prefix must fit (one check — install bytes covers
            // the zip + extraction with headroom)
            requireFreeSpace(ToolchainDistro.EXPECTED_INSTALLED_BYTES, "toolchain install")
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
                        var lastReport = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            copied += n
                            // threshold, NOT modulo: SAF streams return short
                            // counts, after which a modulo never fires again
                            // and progress looks hung for the rest of the copy
                            if (copied - lastReport >= 4L * 1024 * 1024) {
                                lastReport = copied
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
        var swapped = false
        try {
            paths.ensureDirs()
            ProcEnv.ensureDirs(context.filesDir)

            // 1) Fetch the whole bundle to the cache FIRST. The old flow
            //    uninstalled the working prefix BEFORE the download, so any
            //    fetch failure — no network, retries exhausted, storage
            //    full mid-download, cancel — left the user with NO
            //    toolchain, having started from a fully working one. Now
            //    the prefix is only touched after the new bundle is
            //    completely on disk.
            val zip = fetch(paths.bundleZip)

            // 2) Free-space preflight for extraction (re-checked here: the
            //    download just consumed space; usableSpace reflects that).
            requireFreeSpace(ToolchainDistro.EXPECTED_INSTALLED_BYTES, "toolchain extraction")

            // 3) Extract into a STAGING dir, then swap into place as ONE
            //    step of a durable transaction:
            //      PENDING marker (before any destructive step)
            //      prefix -> aside (old install RETAINED for rollback)
            //      staging -> prefix
            //      VERIFY (the smoke test is the gate)
            //      READY: commit deletes aside; marker cleared
            //    A crash at any boundary is recovered at startup; a FAILED
            //    verification restores the previous known-good install.
            _state.value = ToolchainState.Extracting(0, null)
            val staging = paths.staging
            val aside = paths.aside
            val marker = paths.pendingInstall
            withContext(Dispatchers.IO) {
                Fs.deleteRecursively(staging)
                try {
                    val info = extractor.install(zip, staging) { done, total ->
                        _state.value = ToolchainState.Extracting(done, total)
                    }
                    // PENDING — durably recorded BEFORE the destructive swap
                    ToolchainTransaction.begin(marker, ToolchainDistro.RELEASE_TAG)
                    ToolchainSwap.swap(paths.prefix, staging, aside)
                    swapped = true
                    ToolchainTransaction.advance(marker, "verify")
                    log("toolchain ${info.rustVersion} installed (kit files: ${info.kitEntryCount})")
                } finally {
                    // staging is gone (swapped) or failed — never keep it
                    Fs.deleteRecursively(staging)
                }
            }

            // 4) Verify — the smoke test is the gate. runVerifyLocked()
            //    writes the ready marker (atomically, fsync'd) only on a
            //    fully green run.
            runVerifyLocked()

            if (_state.value is ToolchainState.Ready) {
                // READY — make it final: delete the retained old install,
                // then clear the transaction marker
                withContext(Dispatchers.IO) {
                    if (!ToolchainSwap.commit(aside)) {
                        log("WARN: could not delete the old toolchain at ${aside.path} — retried on the next install")
                    }
                    if (!ToolchainTransaction.clear(marker)) {
                        log("WARN: could not clear ${marker.path} — startup recovery will re-run")
                    }
                }
            } else {
                // verification FAILED: restore the previous known-good
                // install from aside (when one exists)
                withContext(Dispatchers.IO) {
                    if (aside.exists()) {
                        try {
                            ToolchainSwap.rollback(paths.prefix, aside)
                            log("verification failed — previous toolchain restored")
                            // the restored install is the working one; its
                            // own ready marker makes the state truthful
                            _state.value = initialState()
                        } catch (rb: java.io.IOException) {
                            log("FAILED to restore the previous toolchain: ${rb.message}")
                            // keep the Failed state — the prefix is unusable
                        }
                    }
                    ToolchainTransaction.clear(marker)
                }
            }

            // Clean the 100+ MB zip: prefix is self-contained now
            paths.bundleZip.delete()
        } catch (e: kotlinx.coroutines.CancellationException) {
            // A cancelled install is NOT a failure: coroutine cancellation
            // must propagate (the service scope or VM scope is going away).
            // If the swap already happened, the pending marker stays on
            // disk and startup recovery restores the previous install.
            when (val s = _state.value) {
                is ToolchainState.Ready, is ToolchainState.Failed, is ToolchainState.NotInstalled -> {}
                else -> _state.value = ToolchainState.NotInstalled // stale progress state
            }
            throw e
        } catch (e: Exception) {
            if (swapped) {
                // the new prefix is in place but unverified: restore the
                // previous known-good one. A rollback failure must not
                // replace the original failure — attach it.
                withContext(Dispatchers.IO) {
                    try {
                        ToolchainSwap.rollback(paths.prefix, paths.aside)
                    } catch (rb: java.io.IOException) {
                        e.addSuppressed(rb)
                    }
                    ToolchainTransaction.clear(paths.pendingInstall)
                }
                _state.value = initialState()
            }
            val current = _state.value
            if (current is ToolchainState.Failed) {
                // a more specific failure was already recorded (e.g. a
                // verification check) — keep its stage, do NOT relabel it
                // as a generic "install" failure
                log("FAILED at ${current.stage}: ${current.message}")
            } else {
                val stage = when (val s = _state.value) {
                    is ToolchainState.Downloading -> "download"
                    is ToolchainState.Extracting -> "extraction"
                    is ToolchainState.Verifying -> "verification"
                    else -> "install"
                }
                log("FAILED at $stage: ${e.message}")
                _state.value = ToolchainState.Failed(stage, e.message ?: e.javaClass.simpleName)
                if (e.suppressed.isNotEmpty()) {
                    e.suppressed.forEach { log("  (also: ${it.message})") }
                }
            }
        }
    }

    /** Refuses to start a multi-hundred-MB operation that cannot fit. */
    private fun requireFreeSpace(requiredBytes: Long, what: String) {
        val usable = context.filesDir.usableSpace
        if (usable < requiredBytes) {
            throw java.io.IOException(
                "not enough free space for the $what: " +
                    "need ~${Fs.humanBytes(requiredBytes)}, only ${Fs.humanBytes(usable)} available",
            )
        }
    }

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
            // The ready marker is the durable READY record of the
            // transaction — atomic + fsync'd, never a torn file
            Fs.writeAtomic(
                paths.readyMarker,
                buildString {
                    appendLine("verified=${System.currentTimeMillis()}")
                    appendLine("rustc_version=${firstLine(rustc)}")
                    appendLine("cargo_version=${firstLine(cargo)}")
                },
            )
            writeCargoDefaults()
            _state.value = ToolchainState.Ready(firstLine(rustc), firstLine(cargo))
            _verifyPassTick.value += 1L
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

    /**
     * Startup crash recovery for an interrupted install transaction.
     * Decision table lives in [ToolchainTransaction.recoveryAction];
     * this only executes it with checked file operations and logging.
     * Guarded by the mutex so it cannot race a user-triggered install.
     */
    private suspend fun recoverInterruptedInstall() = mutex.withLock {
        val marker = paths.pendingInstall
        val pending = ToolchainTransaction.read(marker) ?: return
        withContext(Dispatchers.IO) {
            val action = ToolchainTransaction.recoveryAction(
                pending = pending,
                prefixInstalled = paths.isInstalled(),
                readyMarkerPresent = paths.readyMarker.isFile,
                asideExists = paths.aside.exists(),
            )
            when (action) {
                ToolchainTransaction.Action.NONE -> return@withContext
                ToolchainTransaction.Action.KEEP_VERIFIED -> {
                    // crashed after verification passed (ready marker
                    // written) but before commit/clear: finish the job
                    if (!ToolchainSwap.commit(paths.aside)) {
                        log("recovery: could not delete the old toolchain at ${paths.aside.path} (retried next install)")
                    }
                    log("recovered interrupted install (stage=${pending.stage}): verified toolchain kept")
                }
                ToolchainTransaction.Action.RESTORE_ASIDE -> {
                    try {
                        ToolchainSwap.rollback(paths.prefix, paths.aside)
                        Fs.deleteRecursively(paths.staging)
                        log("recovered interrupted install (stage=${pending.stage}): previous toolchain restored")
                    } catch (rb: java.io.IOException) {
                        log("recovery FAILED to restore the previous toolchain: ${rb.message}")
                    }
                }
                ToolchainTransaction.Action.DISCARD -> {
                    Fs.deleteRecursively(paths.staging)
                    log("recovered interrupted install (stage=${pending.stage}): nothing usable — cleaned up")
                }
            }
            if (!ToolchainTransaction.clear(marker)) {
                log("recovery: could not clear ${marker.path} — will retry at next startup")
            }
            // disk truth becomes the state (Ready when a verified install
            // survived, NotInstalled otherwise)
            _state.value = initialState()
        }
    }

    /**
     * Removes the toolchain. Suspends and dispatches to IO: the delete of
     * a ~500 MB prefix takes seconds, and the old runBlocking wrapper
     * called from a click handler could ANR while waiting on the mutex
     * behind a running install/verify.
     */
    suspend fun uninstall() = withContext(Dispatchers.IO) {
        mutex.withLock { uninstallLocked() }
    }

    private fun uninstallLocked() {
        Fs.deleteRecursively(paths.prefix)
        paths.readyMarker.delete()
        paths.bundleZip.delete()
        // transaction leftovers must not outlive an explicit uninstall
        Fs.deleteRecursively(paths.aside)
        Fs.deleteRecursively(paths.staging)
        ToolchainTransaction.clear(paths.pendingInstall)
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
        defaultScope.launch { installFromUri(uri) }
    }

    suspend fun installFromUri(uri: Uri): Boolean {
        return try {
            val stream = context.contentResolver.openInputStream(uri)
                ?: throw java.io.IOException("cannot open $uri")
            stream.use { s -> installFromImport { s } }
            _state.value is ToolchainState.Ready
        } catch (e: kotlinx.coroutines.CancellationException) {
            // cancellation is not a failure result — propagate it
            throw e
        } catch (e: Exception) {
            false
        }
    }
}
