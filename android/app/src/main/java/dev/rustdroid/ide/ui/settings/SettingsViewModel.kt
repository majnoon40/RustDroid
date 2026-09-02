package dev.rustdroid.ide.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.rustdroid.ide.di.AppContainer
import dev.rustdroid.ide.model.ToolchainState
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

    fun reverify() {
        viewModelScope.launch {
            _busy.value = true
            manager.reverify()
            _busy.value = false
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
