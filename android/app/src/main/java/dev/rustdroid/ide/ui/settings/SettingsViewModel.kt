package dev.rustdroid.ide.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.rustdroid.ide.di.AppContainer
import dev.rustdroid.ide.model.ToolchainState
import dev.rustdroid.ide.toolchain.ToolchainInstallService
import dev.rustdroid.ide.util.Fs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(val container: AppContainer) : ViewModel() {

    private val manager = container.toolchainManager
    val toolchainState = manager.state

    private val _storage = MutableStateFlow<Map<String, Long>>(emptyMap())
    val storage: StateFlow<Map<String, Long>> = _storage.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun refreshStorage() {
        viewModelScope.launch {
            _storage.value = withContext(Dispatchers.IO) {
                val paths = container.toolchainPaths
                val files = container.context.filesDir
                mapOf(
                    "toolchain (files/usr)" to Fs.sizeOf(paths.prefix),
                    "cargo caches (.cargo)" to Fs.sizeOf(java.io.File(files, "home/.cargo")),
                    "download cache" to Fs.sizeOf(paths.cacheDir),
                    "projects" to Fs.sizeOf(container.projectsRoot),
                )
            }
        }
    }

    fun clearCaches() {
        viewModelScope.launch {
            _busy.value = true
            withContext(Dispatchers.IO) {
                Fs.deleteRecursively(java.io.File(container.context.filesDir, "home/.cargo/registry/cache"))
                Fs.deleteRecursively(java.io.File(container.context.filesDir, "home/.cargo/registry/src"))
                Fs.deleteRecursively(container.toolchainPaths.cacheDir)
                Fs.deleteRecursively(container.toolchainPaths.scratch)
            }
            _message.value = "cargo download caches cleared (registry index kept)"
            _busy.value = false
            refreshStorage()
        }
    }

    /**
     * Re-verify runs in the toolchain FOREGROUND SERVICE, not in this
     * viewModelScope: the smoke test takes minutes and users background the
     * app mid-run — a plain coroutine dies with the process, the tick never
     * increments, and the return-to-app pop never fires (the bug that kept
     * coming back). The service keeps the process alive until the run
     * completes; this VM merely tracks progress via the state flow.
     */
    fun reverify() {
        if (_busy.value) return
        _busy.value = true
        val started = runCatching {
            ToolchainInstallService.startReverify(container.context)
        }.isSuccess
        if (!started) {
            _busy.value = false
            _message.value = "could not start re-verification"
            return
        }
        viewModelScope.launch {
            // busy while the service runs the verify; the pre-run Ready
            // state passes through untouched
            var seenVerifying = false
            manager.state.collect { st ->
                if (st is ToolchainState.Verifying) seenVerifying = true
                if (seenVerifying && st !is ToolchainState.Verifying) _busy.value = false
                // reverify with nothing installed short-circuits to
                // NotInstalled without ever entering Verifying
                if (st is ToolchainState.NotInstalled) _busy.value = false
            }
        }
    }

    fun uninstall() {
        viewModelScope.launch {
            _busy.value = true
            withContext(Dispatchers.IO) { manager.uninstall() }
            _busy.value = false
        }
    }

    fun clearMessage() { _message.value = null }

    companion object {
        fun factory(container: AppContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SettingsViewModel(container) as T
        }
    }
}
