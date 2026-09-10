package dev.rustdroid.ide.runtime

import dev.rustdroid.ide.util.Fs
import java.io.File

/**
 * Crash flight recorder (v0.1.8): turns "the app just closed" into a
 * diagnosable, IN-APP artifact — no external tooling, no files handed
 * around.
 *
 * Two recorders, one contract:
 *  - [record]: the default uncaught-exception handler (installed from
 *    RustDroidApp) persists the thread, exception, stack, cause chain
 *    and the breadcrumb tail to files/last-crash.txt, then CHAINS to
 *    the previous handler so the system's crash flow (dialog, tombstone
 *    bookkeeping) is unchanged. The next launch surfaces the report in
 *    an in-app dialog (AppRoot) with a Copy button.
 *  - [crumb]: step-level breadcrumbs around the risky terminal-bring-up
 *    path (session create, view attach, JNI fork window). A NATIVE
 *    crash (SIGSEGV in the JNI layer) cannot be caught in Java — but it
 *    leaves the breadcrumbs mid-operation, which the next run's report
 *    (or their absence) localizes exactly: "died after view-attach,
 *    before view-attached" = the fork window.
 *
 * Pure JVM (no Android imports) so the persistence contract is
 * unit-tested. Every operation is fire-and-forget: a crashing app must
 * never crash harder because its recorder did.
 */
object CrashRecorder {

    private const val CRASH_FILE = "last-crash.txt"
    private const val BREADCRUMB_FILE = "breadcrumbs.txt"
    private const val MAX_BREADCRUMBS = 40
    private const val MAX_STACK_FRAMES = 80
    private const val MAX_CAUSE_DEPTH = 4
    private const val MAX_REPORT_CHARS = 16_000

    @Volatile
    private var crumbDir: File? = null

    /**
     * Installs the default uncaught-exception handler. [chainTo]
     * overrides the chain target (tests inject a sentinel so the JVM
     * default handler is never reached); the app passes null = chain to
     * the handler installed before this call (the platform's).
     */
    fun install(
        filesDir: File,
        chainTo: Thread.UncaughtExceptionHandler? = null,
    ): Thread.UncaughtExceptionHandler {
        val previous = chainTo ?: Thread.getDefaultUncaughtExceptionHandler()
        crumbDir = filesDir
        val handler = Thread.UncaughtExceptionHandler { thread, throwable ->
            runCatching { record(filesDir, thread, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
        Thread.setDefaultUncaughtExceptionHandler(handler)
        return handler
    }

    /** Appends a step breadcrumb (capped ring, newest last). Never throws. */
    fun crumb(tag: String) {
        val dir = crumbDir ?: return
        runCatching {
            val f = File(dir, BREADCRUMB_FILE)
            val lines = (if (f.isFile) f.readLines() else emptyList()).toMutableList()
            lines += "${System.currentTimeMillis()} $tag"
            val capped = if (lines.size > MAX_BREADCRUMBS) {
                lines.subList(lines.size - MAX_BREADCRUMBS, lines.size).toList()
            } else lines
            Fs.writeAtomic(f, capped.joinToString("\n") + "\n")
        }
    }

    /** Persists the report for [throwable] on [thread] (also used directly by tests). */
    fun record(filesDir: File, thread: Thread, throwable: Throwable) {
        val sb = StringBuilder()
        sb.append("time-ms=").append(System.currentTimeMillis()).append('\n')
        sb.append("thread=").append(thread.name).append('\n')
        sb.append("exception=").append(throwable.toString()).append('\n')
        appendFrames(sb, throwable)
        var cause = throwable.cause
        var depth = 0
        while (cause != null && depth < MAX_CAUSE_DEPTH) {
            sb.append("caused-by=").append(cause.toString()).append('\n')
            appendFrames(sb, cause)
            cause = cause.cause
            depth++
        }
        val crumbs = breadcrumbs(filesDir)
        if (crumbs.isNotEmpty()) {
            sb.append("--- breadcrumbs (oldest first) ---").append('\n')
            crumbs.forEach { sb.append(it).append('\n') }
        }
        Fs.writeAtomic(File(filesDir, CRASH_FILE), sb.toString().take(MAX_REPORT_CHARS))
    }

    private fun appendFrames(sb: StringBuilder, t: Throwable) {
        for (frame in t.stackTrace.take(MAX_STACK_FRAMES)) {
            sb.append("  at ").append(frame.toString()).append('\n')
        }
    }

    /** The persisted report, or null when the previous run did not crash. */
    fun lastCrash(filesDir: File): String? {
        val f = File(filesDir, CRASH_FILE)
        return if (f.isFile) runCatching { f.readText() }.getOrNull() else null
    }

    /** Clears the report and the breadcrumbs (the in-app dialog's Dismiss). */
    fun clear(filesDir: File) {
        runCatching { File(filesDir, CRASH_FILE).delete() }
        runCatching { File(filesDir, BREADCRUMB_FILE).delete() }
    }

    internal fun breadcrumbs(filesDir: File): List<String> {
        val f = File(filesDir, BREADCRUMB_FILE)
        return if (f.isFile) {
            runCatching { f.readLines() }.getOrDefault(emptyList())
        } else emptyList()
    }
}
