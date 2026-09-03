package dev.rustdroid.ide.ui.deps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.rustdroid.ide.di.AppContainer
import dev.rustdroid.ide.model.CrateSummary
import dev.rustdroid.ide.projects.CargoToml
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

@OptIn(FlowPreview::class)
class DepsViewModel(
    val container: AppContainer,
    val projectName: String,
) : ViewModel() {

    private val repo = container.projectRepository
    private val crates = container.cratesIoClient

    val projectDir: File = File(container.projectsRoot, projectName)
    private val manifest: File get() = File(projectDir, "Cargo.toml")

    private val _deps = MutableStateFlow<List<CargoToml.Dependency>>(emptyList())
    val deps: StateFlow<List<CargoToml.Dependency>> = _deps.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _results = MutableStateFlow<List<CrateSummary>>(emptyList())
    val results: StateFlow<List<CrateSummary>> = _results.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** True while `cargo fetch` (the actual dependency download) is running. */
    private val _fetching = MutableStateFlow(false)
    val fetching: StateFlow<Boolean> = _fetching.asStateFlow()

    /**
     * Tail of the last `cargo fetch` output — shown (selectable) in the
     * deps screen so download errors are visible, not a mystery snackbar.
     */
    private val _fetchLog = MutableStateFlow<List<String>>(emptyList())
    val fetchLog: StateFlow<List<String>> = _fetchLog.asStateFlow()

    private var searchJob: Job? = null
    private var fetchJob: Job? = null

    private val cargoPath: String
        get() = dev.rustdroid.ide.runtime.ProcEnv.toolchainCommand(
            container.toolchainPaths.prefix, "cargo",
        )

    private val env: Map<String, String> by lazy {
        dev.rustdroid.ide.runtime.ProcEnv.env(
            container.toolchainPaths.prefix, container.context.filesDir,
            // APK-pinned CA store: keeps `cargo fetch` working on devices
            // whose system trust store is empty/unreadable (curl err 77).
            assetProvider = {
                dev.rustdroid.ide.runtime.CaBundle.readAssetPem(
                    container.context.assets,
                )
            },
        )
    }

    init {
        refresh()
        // debounce search
        viewModelScope.launch {
            _query.collect { q ->
                searchJob?.cancel()
                if (q.trim().length < 2) {
                    _results.value = emptyList()
                    return@collect
                }
                searchJob = launch {
                    delay(350)
                    runSearch(q)
                }
            }
        }
    }

    private suspend fun runSearch(q: String) {
        _searching.value = true
        try {
            _results.value = crates.search(q)
            if (_results.value.isEmpty()) {
                _message.value = "no crates matched '$q'"
            }
        } catch (e: Exception) {
            _results.value = emptyList()
            _message.value = "crates.io unreachable: ${e.message}"
        } finally {
            _searching.value = false
        }
    }

    /** Runs the search immediately (IME "Search" action), skipping the debounce. */
    fun searchNow() {
        val q = _query.value.trim()
        if (q.length < 2) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch { runSearch(q) }
    }

    fun refresh() {
        viewModelScope.launch {
            try {
                _deps.value = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    CargoToml.readDependencies(manifest)
                }
            } catch (e: IOException) {
                _message.value = "Cargo.toml: ${e.message}"
            }
        }
    }

    fun setQuery(q: String) {
        _query.value = q
    }

    fun add(crate: CrateSummary) {
        viewModelScope.launch {
            try {
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    CargoToml.addDependency(
                        manifest, crate.name, crate.max_version.ifEmpty { "0.0.0" },
                    )
                }
                _message.value = "added ${crate.name} = \"${crate.max_version}\" — downloading…"
                refresh()
                // Download right away so errors surface here (with the real
                // cargo output) instead of as a mysterious build failure later.
                fetchNow()
            } catch (e: Exception) {
                _message.value = "add failed: ${e.message}"
            }
        }
    }

    fun remove(name: String) {
        viewModelScope.launch {
            try {
                val removed = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    CargoToml.removeDependency(manifest, name)
                }
                if (removed) _message.value = "removed $name"
                refresh()
            } catch (e: Exception) {
                _message.value = "remove failed: ${e.message}"
            }
        }
    }

    /**
     * Downloads all dependencies now via `cargo fetch`. Single-flight.
     * Output streams into [fetchLog]; on failure the last error lines are
     * surfaced in the snackbar AND kept selectable in the log area.
     */
    fun fetchNow() {
        if (_fetching.value) return
        if (!container.toolchainPaths.isInstalled()) {
            _message.value = "toolchain not installed — install it from Settings first"
            return
        }
        fetchJob?.cancel()
        fetchJob = viewModelScope.launch {
            _fetching.value = true
            // first access builds the CA bundle (disk) — keep it off Main
            val fetchEnv = withContext(kotlinx.coroutines.Dispatchers.IO) { env }
            val log = mutableListOf<String>()
            try {
                val result = container.cargoRunner.run(
                    listOf(cargoPath, "fetch"),
                    cwd = projectDir,
                    env = fetchEnv,
                    onLine = { line ->
                        synchronized(log) {
                            log.add(line.text)
                            if (log.size > 400) log.removeAt(0)
                        }
                        _fetchLog.value = synchronized(log) { log.toList() }
                    },
                )
                if (result.success) {
                    _message.value = "dependencies downloaded"
                } else {
                    // Snackbar is 2-line-capped — keep it a pointer; the full
                    // (selectable/copyable) error output is in fetchLog.
                    _message.value =
                        "cargo fetch failed (exit ${result.exitCode}) — see fetch output"
                    // libcurl 77/60 = TLS trust store could not be loaded —
                    // bare curl codes confuse users; surface an actionable hint.
                    val tlsFailed = synchronized(log) { log.toList() }.any {
                        it.contains("error setting certificate verify locations") ||
                            it.contains("[77]") || it.contains("[60]")
                    }
                    if (tlsFailed) {
                        _fetchLog.value = _fetchLog.value + TLS_HINT
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = "fetch error: ${e.message}"
            } finally {
                _fetching.value = false
            }
        }
    }

    fun clearFetchLog() {
        fetchJob?.cancel()
        _fetchLog.value = emptyList()
    }

    fun clearMessage() {
        _message.value = null
    }

    companion object {
        /** Appended to the fetch log when cargo output shows a TLS trust failure. */
        const val TLS_HINT =
            "hint: TLS trust failure (curl 77/60). RustDroid ships its own CA bundle and " +
                "rebuilds it automatically on every run — if this persists, update to the " +
                "latest app build and retry the fetch"

        fun factory(container: AppContainer, projectName: String) =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    DepsViewModel(container, projectName) as T
            }
    }
}
