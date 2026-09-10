package dev.rustdroid.ide.runtime.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import dev.rustdroid.ide.model.ToolchainState
import dev.rustdroid.ide.runtime.CrashRecorder
import dev.rustdroid.ide.runtime.ProcEnv
import dev.rustdroid.ide.toolchain.ToolchainManager

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-wide terminal session registry (plan §8.1). Sessions live HERE,
 * not in the ViewModel — rotation swaps the VM without touching them —
 * and the foreground service ([TerminalService]) keeps the PROCESS (and
 * therefore the sessions) alive while ≥ 1 session exists.
 *
 * The session I/O-owner scope is a single-worker dispatcher: every master
 * fd close and every teardown runs confined to it, so exactly one thread
 * ever performs them (plan §5.3 / review P1-4: the close happens after
 * the reader is joined — never under a blocked read).
 *
 * BACKPRESSURE (plan §8.4, a hard constraint — not a nice-to-have): no
 * unbounded Kotlin-side output buffering exists in this layer. The child
 * writing to the pts BLOCKS when the reader falls behind — the kernel
 * doing flow control for us; the vendored 64 KiB ByteQueue is bounded;
 * scrollback is capped by [TRANSCRIPT_ROWS]. A 64 MB burst must
 * translate to bounded memory plus a capped transcript, never to an
 * unbounded queue.
 *
 * NO SESSION-PERSISTENCE CLAIM (plan §5.4): terminal sessions die with
 * the app process — no UI or doc claims otherwise.
 */
class TerminalCenter(
    private val context: Context,
    private val toolchainManager: ToolchainManager,
    /** Internal projects root (files/projects) — the DEFAULT session cwd:
     *  a fresh session lands next to the user's projects so `cd <project>
     *  && cargo run` is one keystroke away (v0.1.8, the PyDroid-style
     *  workflow). HOME stays $RUSTDROID_HOME via the env — CARGO_HOME etc.
     *  are absolute and do not care about the session cwd. */
    private val projectsRoot: File,
) {
    /** One entry per live session; observed by the ViewModel and the service glue. */
    data class SessionEntry(
        val id: Long,
        val session: TerminalSession,
        val title: String,
        val finished: Boolean,
    )

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val nextId = AtomicLong(1)

    /** Transcript (scrollback) rows — CAPPED (plan §8.4). */
    val transcriptRows: Int = 200

    private val _sessions = MutableStateFlow<List<SessionEntry>>(emptyList())
    val sessions: StateFlow<List<SessionEntry>> = _sessions

    /** Views currently displaying a session (weak: the screen unregisters on dispose). */
    private val views = HashMap<TerminalSession, com.termux.view.TerminalView>()

    private val client = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {
            views[changedSession]?.onScreenUpdated()
        }

        override fun onTitleChanged(changedSession: TerminalSession) {
            updateEntry(changedSession) { it.copy(title = changedSession.mSessionName ?: it.title) }
        }

        override fun onSessionFinished(finishedSession: TerminalSession) {
            // Natural end (EIO/waiter path): mark finished, then close the
            // master on the I/O-owner scope with the same join-then-close
            // discipline as teardown — never from this (main) thread.
            updateEntry(finishedSession) { it.copy(finished = true) }
            ioScope.launch {
                finishedSession.requestReaderStop()
                finishedSession.joinReader(2_000)
                finishedSession.closeMasterFd()
            }
        }

        override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            cm.setPrimaryClip(ClipData.newPlainText("terminal", text))
        }

        override fun onPasteTextFromClipboard(session: TerminalSession?) {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            (cm.primaryClip?.getItemAt(0)?.text as? String)?.let { session?.write(it) }
        }

        override fun onBell(session: TerminalSession) {}
        override fun onColorsChanged(session: TerminalSession) {}
        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}

        override fun getTerminalCursorStyle(): Int? = null

        override fun logError(tag: String, message: String) { Log.e(tag, message) }
        override fun logWarn(tag: String, message: String) { Log.w(tag, message) }
        override fun logInfo(tag: String, message: String) { Log.i(tag, message) }
        override fun logDebug(tag: String, message: String) { Log.d(tag, message) }
        override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }
        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception?) {
            Log.e(tag, message, e)
        }
        override fun logStackTrace(tag: String, e: Exception?) { Log.e(tag, "", e) }
    }

    /** Result of a create attempt — the UI explains failures, never falls back silently. */
    sealed interface CreateResult {
        data class Ok(val entry: SessionEntry) : CreateResult
        data class NotInstalled(val detail: String) : CreateResult

        /** An unexpected exception during session construction (v0.1.8):
         *  loud in the terminal's error state — the app NEVER dies with
         *  an uncaught create-path exception. */
        data class Error(val detail: String) : CreateResult
    }

    /**
     * Creates a session running `$PREFIX/bin/sh` — by default in the
     * PROJECTS directory (see [projectsRoot]), or [cwd] when given — with
     * the [TerminalEnv] environment. Fails LOUDLY (never silently) when
     * the toolchain is not Ready, the shell is absent, or session
     * construction throws: the exception is captured into the error
     * state (and a breadcrumb) instead of killing the process.
     */
    fun createSession(cwd: File? = null): CreateResult {
        val state = toolchainManager.state.value
        if (state !is ToolchainState.Ready) {
            return CreateResult.NotInstalled("Toolchain not installed")
        }
        val filesDir = context.filesDir
        val prefix = File(filesDir, "usr")
        val shell = TerminalEnv.shellPath(prefix)
        if (!shell.isFile) {
            return CreateResult.NotInstalled(
                "Shell not found at ${shell.absolutePath} — ships with the Phase 4 busybox bundle"
            )
        }
        CrashRecorder.crumb("terminal:create:start")
        return try {
            val env = TerminalEnv.env(prefix, filesDir)
            CrashRecorder.crumb("terminal:create:env-built")
            val sessionCwd = cwd ?: projectsRoot.apply { mkdirs() }
            val termSession = TerminalSession(
                shell.absolutePath,
                sessionCwd.absolutePath,
                TerminalEnv.shellArgs(),
                env.entries.map { "${it.key}=${it.value}" }.toTypedArray(),
                transcriptRows,
                client,
            )
            CrashRecorder.crumb("terminal:create:session-built")
            // v0.2: a project-rooted session is titled by its project so
            // the tab strip tells sessions apart (plain ones stay "sh").
            val entry = SessionEntry(
                nextId.getAndIncrement(), termSession,
                cwd?.name?.takeIf { it.isNotBlank() } ?: "sh", false,
            )
            _sessions.value = _sessions.value + entry
            TerminalService.ensureRunning(context, _sessions.value.size)
            CrashRecorder.crumb("terminal:create:fgs-started")
            CreateResult.Ok(entry)
        } catch (e: Exception) {
            CrashRecorder.crumb("terminal:create:threw:${e.javaClass.simpleName}")
            CreateResult.Error(
                "session create failed: ${e.message ?: e.javaClass.simpleName}"
            )
        }
    }

    /**
     * Full session teardown via [TerminalSessionController] — the exact
     * plan §5.3 sequence — on the I/O-owner scope.
     */
    fun closeSession(id: Long) {
        val entry = _sessions.value.firstOrNull { it.id == id } ?: return
        ioScope.launch {
            withContext(Dispatchers.IO.limitedParallelism(1)) {
                TerminalSessionController(
                    procRoot = File("/proc"),
                    shellPid = entry.session.pid.toLong(),
                    ptsDevice = entry.session.ptsDevice.toLong(),
                    signal = { pid, sig -> com.termux.terminal.JNIHelper.signal(pid, sig) },
                    killpg = { pgid, sig -> com.termux.terminal.JNIHelper.sendSignalToGroup(pgid, sig) },
                    closeMaster = { entry.session.closeMasterFd() },
                    stopReader = { entry.session.requestReaderStop() },
                    joinReader = { entry.session.joinReader(it) },
                ).teardown()
            }
            _sessions.value = _sessions.value.filterNot { it.id == id }
            TerminalService.ensureStopped(context, _sessions.value.size)
        }
    }

    /** Registers the view currently displaying a session (redraw routing). */
    fun bindView(session: TerminalSession, view: com.termux.view.TerminalView) {
        views[session] = view
    }

    fun unbindView(session: TerminalSession) {
        views.remove(session)
    }

    private fun updateEntry(session: TerminalSession, transform: (SessionEntry) -> SessionEntry) {
        _sessions.value = _sessions.value.map { if (it.session === session) transform(it) else it }
        TerminalService.ensureStopped(context, _sessions.value.count { !it.finished })
    }

    fun shutdown() {
        ioScope.launch {
            _sessions.value.forEach { entry ->
                runCatching {
                    TerminalSessionController(
                        procRoot = File("/proc"),
                        shellPid = entry.session.pid.toLong(),
                        ptsDevice = entry.session.ptsDevice.toLong(),
                        signal = { pid, sig -> com.termux.terminal.JNIHelper.signal(pid, sig) },
                        killpg = { pgid, sig -> com.termux.terminal.JNIHelper.sendSignalToGroup(pgid, sig) },
                        closeMaster = { entry.session.closeMasterFd() },
                        stopReader = { entry.session.requestReaderStop() },
                        joinReader = { entry.session.joinReader(it) },
                    ).teardown()
                }
            }
        }
        ioScope.cancel()
    }
}
