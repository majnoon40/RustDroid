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
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-wide terminal session registry (plan §8.1). Sessions live HERE,
 * not in the ViewModel — rotation swaps the VM without touching them —
 * and the foreground service ([TerminalService]) keeps the PROCESS (and
 * therefore the sessions) alive while ≥ 1 session exists.
 *
 * The session I/O-owner scope is a DEDICATED single thread (v0.2.1): every
 * master fd close and every teardown runs confined to it, so exactly one
 * thread ever performs them (plan §5.3 / review P1-4: the close happens
 * after the reader is joined — never under a blocked read).
 *
 * v0.2.1 correction (review P0): the previous owner scope was
 * `Dispatchers.IO.limitedParallelism(1)` — and worse, closeSession
 * re-derived that limiter in an inner `withContext(...)` on EVERY call.
 * `limitedParallelism` is a concurrency limiter over an elastic thread
 * pool, not thread confinement, and it returns a NEW view each invocation
 * (it does not memoize), so concurrent closeSession calls ran fully in
 * parallel with no single-flight guarantee at all — contradicting this
 * class's own doc comment. Reachable by ordinary use (closing a tab just
 * as its shell exits, where onSessionFinished's teardown races the user's
 * close). Fixed with one `Executors.newSingleThreadExecutor`
 * dispatcher stored in a field and never re-derived.
 *
 * STATE DISCIPLINE (review P1): [_sessions] is mutated from the main
 * thread (create), the owner thread (close), and the vendored session's
 * callback handler (updateEntry on title/finish). All three now use
 * `update { }` (CAS-looped) rather than `value = value ± x`, which is a
 * non-atomic read-modify-write that can lose one of two concurrent
 * updates — a vanished new session (orphaned shell with no UI affordance
 * to kill it) or a resurrected closed one (a dead fd behind a live tab).
 * [views] is a ConcurrentHashMap: it is touched from the client callbacks
 * and from the screen's bind/unbind, and a plain HashMap mutated
 * concurrently can corrupt its table.
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
        /** Review P2 fix: the project this session belongs to (the same
         *  string TerminalScreen's `projectRef` param carries), or null for
         *  a plain projects-root session. TerminalScreen filters [sessions]
         *  by this before deciding "does a session already exist here" —
         *  previously that check ran against the GLOBAL session list, so
         *  navigating from project A's terminal to project B's reused A's
         *  session (any live session made `sessions.isEmpty()` false) under
         *  a title that claimed to be B. */
        val projectRef: String? = null,
    )

    /**
     * The session I/O owner: ONE dedicated daemon thread, created once.
     * Genuine thread confinement — unlike `limitedParallelism`, which
     * limits concurrency without pinning a thread and returns a fresh
     * view per call.
     */
    private val ioDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "rd-terminal-io").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val ioScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val nextId = AtomicLong(1)

    /** Transcript (scrollback) rows — CAPPED (plan §8.4). */
    val transcriptRows: Int = 200

    private val _sessions = MutableStateFlow<List<SessionEntry>>(emptyList())
    val sessions: StateFlow<List<SessionEntry>> = _sessions

    /** Views currently displaying a session (the screen unregisters on dispose).
     *  ConcurrentHashMap: written from the session callbacks and the screen. */
    private val views = ConcurrentHashMap<TerminalSession, com.termux.view.TerminalView>()

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
    fun createSession(cwd: File? = null, projectRef: String? = null): CreateResult {
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
                projectRef = projectRef,
            )
            // CAS update: create (main), close (owner) and updateEntry
            // (callback handler) all mutate this list from different
            // threads — `value = value + x` would drop a concurrent update.
            _sessions.update { it + entry }
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
     *
     * The entry is removed from [_sessions] BEFORE teardown is enqueued
     * (v0.2.1): a second close tap therefore finds nothing and cannot
     * enqueue a duplicate teardown for the same session, and the tab strip
     * stops offering a session that is already on its way out.
     */
    fun closeSession(id: Long) {
        val entry = _sessions.value.firstOrNull { it.id == id } ?: return
        _sessions.update { list -> list.filterNot { it.id == id } }
        // The session is gone from the UI now; stop the FGS if it was the
        // last live one (the teardown below runs regardless).
        TerminalService.ensureStopped(context, _sessions.value.count { !it.finished })
        ioScope.launch {
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

    /** Registers the view currently displaying a session (redraw routing). */
    fun bindView(session: TerminalSession, view: com.termux.view.TerminalView) {
        views[session] = view
    }

    fun unbindView(session: TerminalSession) {
        views.remove(session)
    }

    private fun updateEntry(session: TerminalSession, transform: (SessionEntry) -> SessionEntry) {
        _sessions.update { list ->
            list.map { if (it.session === session) transform(it) else it }
        }
        TerminalService.ensureStopped(context, _sessions.value.count { !it.finished })
    }

    /**
     * Tears down every live session, then retires the I/O-owner thread.
     *
     * External bug report #8 (confirmed): the previous version called
     * `ioScope.cancel()` immediately after `ioScope.launch { ... }` —
     * since both run on the SAME scope, cancel() could tear down the
     * launched job before the single-thread dispatcher ever got to start
     * executing it (or interrupt it mid-teardown), so nothing was
     * actually torn down. Fixed by cancelling only after the job
     * completes.
     *
     * WIRING GAP, stated rather than silently left: nothing in the app
     * currently calls shutdown(). The natural hook is
     * TerminalService.onDestroy() (via the Application's AppContainer),
     * so terminal sessions are torn down when the service itself is
     * killed (app swiped away while a terminal is open) rather than
     * relying solely on the kernel's SIGHUP-on-master-close side effect.
     * Not wired here — confirming the exact Application/container access
     * pattern needs a look at RustDroidApp.kt, deliberately deferred
     * rather than guessing a cross-file reference that could be wrong.
     */
    fun shutdown() {
        val job = ioScope.launch {
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
        job.invokeOnCompletion { ioScope.cancel() }
    }
}
