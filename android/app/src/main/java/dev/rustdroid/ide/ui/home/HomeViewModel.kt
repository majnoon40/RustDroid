package dev.rustdroid.ide.ui.home

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.rustdroid.ide.di.AppContainer
import dev.rustdroid.ide.model.ProjectSummary
import dev.rustdroid.ide.runtime.Stream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
                _createLog.value = (e as? IOException)?.message ?: e.message ?: "failed"
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

    companion object {
        fun factory(container: AppContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                HomeViewModel(container) as T
        }
    }
}
