package dev.rustdroid.ide.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.rustdroid.ide.di.AppContainer
import dev.rustdroid.ide.model.Diagnostic
import dev.rustdroid.ide.model.EditorTab
import dev.rustdroid.ide.model.FileNode
import dev.rustdroid.ide.model.JumpRequest
import dev.rustdroid.ide.model.RunResult
import dev.rustdroid.ide.runtime.CargoRunner
import dev.rustdroid.ide.runtime.ConsoleBuffer
import dev.rustdroid.ide.runtime.DiagnosticsParser
import dev.rustdroid.ide.runtime.ProcEnv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Drives the editor screen: tabs, file tree, console, problems, runs.
 * One build at a time — [runJob] is the single in-flight invocation.
 */
class EditorViewModel(
    val container: AppContainer,
    val projectName: String,
) : ViewModel() {

    val projectDir: File = File(container.projectsRoot, projectName)

    private val repo = container.projectRepository
    private val runner = container.cargoRunner
    private val env by lazy {
        ProcEnv.env(container.toolchainPaths.prefix, container.context.filesDir)
    }

    // ---- tabs ----
    private val _tabs = MutableStateFlow<List<EditorTab>>(emptyList())
    val tabs: StateFlow<List<EditorTab>> = _tabs.asStateFlow()

    private val _activeIndex = MutableStateFlow(-1)
    val activeIndex: StateFlow<Int> = _activeIndex.asStateFlow()

    val activeTab: EditorTab? get() = _tabs.value.getOrNull(_activeIndex.value)

    /** Editor text of the active tab — the view pushes changes here. */
    private val _activeText = MutableStateFlow("")
    val activeText: StateFlow<String> = _activeText.asStateFlow()

    // ---- tree ----
    private val _tree = MutableStateFlow<List<FileNode>>(emptyList())
    val tree: StateFlow<List<FileNode>> = _tree.asStateFlow()

    private val _treeAllFiles = MutableStateFlow(false)
    val treeAllFiles: StateFlow<Boolean> = _treeAllFiles.asStateFlow()

    // ---- run state ----
    val console = ConsoleBuffer()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _lastResult = MutableStateFlow<RunResult?>(null)
    val lastResult: StateFlow<RunResult?> = _lastResult.asStateFlow()

    private val _problems = MutableStateFlow<List<Diagnostic>>(emptyList())
    val problems: StateFlow<List<Diagnostic>> = _problems.asStateFlow()

    private val _jump = MutableStateFlow<JumpRequest?>(null)
    val jump: StateFlow<JumpRequest?> = _jump.asStateFlow()

    private val _bottomTab = MutableStateFlow(BottomTab.CONSOLE)
    val bottomTab: StateFlow<BottomTab> = _bottomTab.asStateFlow()

    val stdinPipe = CargoRunner.StdinPipe()

    private var runJob: Job? = null
    private val parser = DiagnosticsParser()

    enum class BottomTab { CONSOLE, PROBLEMS }

    init {
        refreshTree()
        // open the entry file the way cargo new creates it
        val entry = listOf("src/main.rs", "src/lib.rs", "Cargo.toml")
            .firstOrNull { File(projectDir, it).isFile }
        entry?.let { openFile(it) }
    }

    // ---------------- tabs ----------------

    fun openFile(relativePath: String) {
        val idx = _tabs.value.indexOfFirst { it.relativePath == relativePath }
        if (idx >= 0) {
            setActive(idx)
            return
        }
        val file = File(projectDir, relativePath)
        viewModelScope.launch {
            try {
                val text = withContext(Dispatchers.IO) { repo.readFile(file) }
                _tabs.value = _tabs.value + EditorTab(relativePath, file, text)
                setActive(_tabs.value.size - 1)
            } catch (e: IOException) {
                console.system("cannot open $relativePath: ${e.message}")
            }
        }
    }

    fun setActive(index: Int) {
        if (index !in _tabs.value.indices) return
        // flush current text into the tab object before switching
        activeTab?.let { it.cachedText = _activeText.value }
        _activeIndex.value = index
        _activeText.value = _tabs.value[index].cachedText
    }

    /** Called by the editor view on every keystroke. */
    fun onTextChange(text: String) {
        _activeText.value = text
        activeTab?.let {
            it.cachedText = text
            it.dirty = true
        }
    }

    fun closeTab(index: Int, force: Boolean) {
        val tab = _tabs.value.getOrNull(index) ?: return
        if (tab.dirty && !force) {
            _pendingClose = index
            _showCloseConfirm.value = true
            return
        }
        doClose(index)
    }

    private var _pendingClose = -1
    private val _showCloseConfirm = MutableStateFlow(false)
    val showCloseConfirm: StateFlow<Boolean> = _showCloseConfirm.asStateFlow()

    fun confirmClose(save: Boolean) {
        val idx = _pendingClose
        _showCloseConfirm.value = false
        if (save) {
            setActive(idx)
            saveActive()
        }
        doClose(idx)
    }

    private fun doClose(index: Int) {
        if (index !in _tabs.value.indices) return
        val wasActive = index == _activeIndex.value
        _tabs.value = _tabs.value.filterIndexed { i, _ -> i != index }
        if (wasActive) {
            val next = (index - 1).coerceAtLeast(0).coerceAtMost(_tabs.value.size - 1)
            _activeIndex.value = if (_tabs.value.isEmpty()) -1 else next
            _activeText.value = if (_tabs.value.isEmpty()) "" else _tabs.value[next].cachedText
        } else if (index < _activeIndex.value) {
            _activeIndex.value -= 1
        }
    }

    fun saveActive(): Boolean = activeTab?.let { tab ->
        try {
            repo.writeFile(tab.file, _activeText.value)
            tab.dirty = false
            true
        } catch (e: IOException) {
            console.system("save failed: ${e.message}")
            false
        }
    } ?: false

    fun saveAll() {
        // flush active text first
        activeTab?.let { it.cachedText = _activeText.value }
        _tabs.value.forEach { tab ->
            try {
                repo.writeFile(tab.file, tab.cachedText)
                tab.dirty = false
            } catch (e: IOException) {
                console.system("save failed (${tab.relativePath}): ${e.message}")
            }
        }
    }

    // ---------------- tree ----------------

    fun refreshTree() {
        viewModelScope.launch {
            _tree.value = withContext(Dispatchers.IO) { repo.fileTree(projectDir, _treeAllFiles.value) }
        }
    }

    fun toggleAllFiles() {
        _treeAllFiles.value = !_treeAllFiles.value
        refreshTree()
    }

    // ---------------- runs ----------------

    // Absolute path: Android's JVM does not resolve bare command names
    // against the child env's PATH — only absolute paths exec reliably.
    private val cargoPath: String
        get() = ProcEnv.toolchainCommand(container.toolchainPaths.prefix, "cargo")

    fun run() = startCargo(listOf(cargoPath, "run"))
    fun build() = startCargo(listOf(cargoPath, "build"))
    fun clean() = startCargo(listOf(cargoPath, "clean"))

    private fun startCargo(command: List<String>) {
        if (_running.value) return
        saveAll()
        runJob = viewModelScope.launch {
            _running.value = true
            _lastResult.value = null
            _problems.value = emptyList()
            parser.reset()
            console.clear()
            // Show the tool's short name, not its full path
            val display = command.first().substringAfterLast('/') +
                command.drop(1).joinToString("", prefix = " ")
            console.system("\$ $display")
            try {
                val result = runner.run(
                    command, cwd = projectDir, env = env, stdin = stdinPipe,
                    onLine = { line ->
                        console.append(line)
                        if (line.stream == dev.rustdroid.ide.model.Stream.STDERR) {
                            parser.feed(line.text)
                            if (parser.errorCount() + parser.warningCount() > 0) {
                                _problems.value = parser.snapshot()
                            }
                        }
                    },
                )
                _lastResult.value = result
                console.system(
                    if (result.cancelled) "(terminated)"
                    else "(exit ${result.exitCode} in ${result.durationMs / 1000.0}s)"
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                console.system("(terminated)")
            } finally {
                _running.value = false
            }
        }
    }

    fun stop() {
        runJob?.cancel()
    }

    fun sendStdin(line: String) {
        stdinPipe.sendLine(line)
    }

    // ---------------- problems ----------------

    fun jumpTo(diagnostic: Diagnostic) {
        val resolved = diagnostic.resolvePath(projectDir) ?: return
        if (!resolved.isFile) return
        val rel = diagnostic.file ?: return
        if (rel.startsWith("/")) return // absolute paths outside project: no jump
        openFile(rel)
        _jump.value = JumpRequest(
            rel,
            (diagnostic.line ?: 1),
            (diagnostic.col ?: 1),
        )
        _bottomTab.value = BottomTab.PROBLEMS
    }

    fun clearJump() {
        _jump.value = null
    }

    fun selectBottomTab(tab: BottomTab) {
        _bottomTab.value = tab
    }

    companion object {
        fun factory(container: AppContainer, projectName: String) =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    EditorViewModel(container, projectName) as T
            }
    }
}
