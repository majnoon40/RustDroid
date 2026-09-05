package dev.rustdroid.ide.ui.home

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.rustdroid.ide.di.AppContainer
import dev.rustdroid.ide.model.ProjectSummary

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class HomeViewModel(val container: AppContainer) : ViewModel() {

    private val repo = container.projectRepository

    private val _projects = MutableStateFlow<List<ProjectSummary>>(emptyList())
    val projects: StateFlow<List<ProjectSummary>> = _projects.asStateFlow()

    private val _creating = MutableStateFlow(false)
    val creating: StateFlow<Boolean> = _creating.asStateFlow()

    private val _createLog = MutableStateFlow<String?>(null)
    val createLog: StateFlow<String?> = _createLog.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _projects.value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                repo.list()
            }
        }
    }

    fun create(name: String, isLib: Boolean, onDone: (Boolean) -> Unit) {
        if (_creating.value) return
        _creating.value = true
        _createLog.value = null
        viewModelScope.launch {
            try {
                val lines = StringBuilder()
                val dir = repo.create(name, isLib) { line ->
                    lines.appendLine(line)
                    _createLog.value = lines.toString()
                }
                _projects.value = repo.list()
                onDone(true)
            } catch (e: Exception) {
                // Append the failure detail to whatever cargo printed, so
                // the dialog shows the real cause (not just an exit code).
                val detail = e.message ?: "failed"
                _createLog.value =
                    if (_createLog.value.isNullOrBlank()) detail
                    else "${_createLog.value}\n$detail"
                onDone(false)
            } finally {
                _creating.value = false
            }
        }
    }

    fun delete(project: ProjectSummary) {
        viewModelScope.launch {
            try {
                repo.delete(project.dir)
            } catch (e: IOException) {
                _error.value = e.message
            }
            _projects.value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                repo.list()
            }
        }
    }

    fun rename(project: ProjectSummary, newName: String) {
        viewModelScope.launch {
            try {
                repo.rename(project.dir, newName)
            } catch (e: Exception) {
                _error.value = e.message ?: "rename failed"
            }
            _projects.value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                repo.list()
            }
        }
    }

    fun clearError() { _error.value = null }

    /** Surfaces a non-fatal message through the shared error dialog. */
    fun reportError(message: String) { _error.value = message }

    // ---- folders opened in place (external projects) ----

    /** True when WRITE_EXTERNAL_STORAGE is granted (legacy model — see the
     *  targetSdk 28 note in build.gradle.kts; required to edit
     *  shared-storage folders in place). */
    fun hasStoragePermission(): Boolean =
        ContextCompat.checkSelfPermission(
            container.context, Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED

    /** The permission request string the screen should ask for, if any. */
    fun storagePermissionToRequest(): String? =
        if (hasStoragePermission()) null else Manifest.permission.WRITE_EXTERNAL_STORAGE

    /**
     * Validates a picked folder for opening in place: translates the SAF
     * tree Uri to a real path (cargo needs POSIX paths, not content://)
     * and checks the folder is readable AND writable. [onReady] gets the
     * folder; [onError] gets a human message.
     */
    fun linkFolder(treeUri: Uri, onReady: (File) -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    val dir = container.folderLink.toFolder(treeUri)
                        ?: throw IOException(
                            "that location is not a folder on this device's storage — " +
                                "pick from Downloads or the internal storage view",
                        )
                    if (!dir.isDirectory) throw IOException("'${dir.name}' is not a folder")
                    if (!dir.canRead()) {
                        throw IOException("cannot read '${dir.path}' — storage permission missing")
                    }
                    if (!dir.canWrite()) {
                        throw IOException(
                            "cannot write '${dir.path}' — grant storage access and try again",
                        )
                    }
                    dir
                }
            }.fold(onSuccess = onReady, onFailure = { onError(it.message ?: "could not open the folder") })
        }
    }

    /**
     * Adopts a validated folder as a project, IN PLACE: optionally writes
     * Cargo.toml + src/main.rs when missing ([withCargo]), registers it in
     * the external list, refreshes Home. [onDone] receives the project ref
     * (absolute path) on success, or an error message on failure.
     */
    fun adoptFolder(
        dir: File,
        withCargo: Boolean,
        packageName: String,
        onDone: (String?, String?) -> Unit,
    ) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (withCargo) repo.ensureCargoProject(dir, packageName)
                    val canonical = repo.registerExternal(dir)
                    (canonical ?: dir.canonicalFile).absolutePath
                }
            }
            _projects.value = withContext(Dispatchers.IO) { repo.list() }
            result.fold(
                onSuccess = { ref -> onDone(ref, null) },
                onFailure = { onDone(null, it.message ?: "could not open the folder as a project") },
            )
        }
    }

    /** Forgets a folder opened in place — the folder itself is never touched. */
    fun removeExternal(project: ProjectSummary) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.unregisterExternal(project.dir) }
            _projects.value = withContext(Dispatchers.IO) { repo.list() }
        }
    }

    companion object {
        fun factory(container: AppContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                HomeViewModel(container) as T
        }
    }
}
