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

    private var searchJob: Job? = null

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
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            try {
                _deps.value = CargoToml.readDependencies(manifest)
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
                CargoToml.addDependency(manifest, crate.name, crate.max_version.ifEmpty { "0.0.0" })
                _message.value = "added ${crate.name} = \"${crate.max_version}\" — fetched at next build"
                refresh()
            } catch (e: Exception) {
                _message.value = "add failed: ${e.message}"
            }
        }
    }

    fun remove(name: String) {
        viewModelScope.launch {
            try {
                if (CargoToml.removeDependency(manifest, name)) {
                    _message.value = "removed $name"
                }
                refresh()
            } catch (e: Exception) {
                _message.value = "remove failed: ${e.message}"
            }
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    companion object {
        fun factory(container: AppContainer, projectName: String) =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    DepsViewModel(container, projectName) as T
            }
    }
}
