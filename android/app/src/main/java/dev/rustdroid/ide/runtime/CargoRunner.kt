package dev.rustdroid.ide.runtime

import dev.rustdroid.ide.model.ConsoleLine
import dev.rustdroid.ide.model.RunResult
import dev.rustdroid.ide.model.Stream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit

/**
 * Subprocess engine for cargo/rustc invocations. Streams stdout+stderr as
 * line events, supports stdin send and cooperative cancellation
 * (destroy -> grace -> destroyForcibly). Cleanup runs before rethrowing
 * cancellation, so state stays consistent under structured concurrency.
 *
 * Cancellation kills the WHOLE process tree, not just the direct child:
 * [signal] (injected; Android wires it to android.os.Process.sendSignal)
 * SIGKILLs every descendant found via [ProcTree] — Java's ProcessBuilder
 * creates no process group, so process.destroy() alone leaves cargo's
 * rustc/ld.lld children running as orphans, burning CPU and battery.
 */
class CargoRunner(
    /** Raw-pid signal sender. Default: no-op (pure JVM tests). */
    private val signal: (pid: Long, sig: Int) -> Unit = { _, _ -> },
) {

    /**
     * Runs [command] in [cwd] with [env]; calls [onLine] per output line.
     * Pumps are child coroutines of the caller: cancelling the caller
     * tears the process down.
     */
    suspend fun run(
        command: List<String>,
        cwd: File,
        env: Map<String, String>,
        onLine: (ConsoleLine) -> Unit = {},
        stdin: StdinPipe? = null,
    ): RunResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val pb = ProcessBuilder(command).apply {
            directory(cwd)
            environment().clear()
            environment().putAll(env)
        }
        val process = try {
            pb.start()
        } catch (e: IOException) {
            onLine(ConsoleLine(Stream.SYSTEM, "failed to start ${command.first()}: ${e.message}"))
            return@withContext RunResult(-1, false, 0)
        }

        stdin?.attach(BufferedOutputStream(process.outputStream))

        val pumps = listOf(
            launch { pump(BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)), Stream.STDOUT, onLine) },
            launch { pump(BufferedReader(InputStreamReader(process.errorStream, Charsets.UTF_8)), Stream.STDERR, onLine) },
        )

        try {
            // delay() is the cancellation point: VM cancellation lands here,
            // unwinding into the catch below which destroys the process.
            while (process.isAlive) {
                kotlinx.coroutines.delay(150)
            }
            val exit = runCatching { process.exitValue() }.getOrDefault(-1)
            // pumps drain remaining buffered lines; guard against a stray
            // grandchild holding the pipe open
            pumps.forEach { runCatching { kotlinx.coroutines.withTimeoutOrNull(2000) { it.join() } } }
            stdin?.detach()
            RunResult(exit, false, System.currentTimeMillis() - started)
        } catch (e: CancellationException) {
            kill(process)
            stdin?.detach()
            pumps.forEach { it.cancel() }
            throw e
        } finally {
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { process.outputStream.close() }
        }
    }

    private suspend fun pump(
        reader: BufferedReader,
        stream: Stream,
        onLine: (ConsoleLine) -> Unit,
    ) {
        try {
            while (true) {
                val line = reader.readLine() ?: break
                onLine(ConsoleLine(stream, line))
            }
        } catch (_: IOException) {
            // stream closed by destroy()
        }
    }

    private fun kill(process: Process) {
        val pid = pidOf(process)
        if (pid != null) {
            // parent first: it cannot spawn replacements once dead, then
            // sweep the orphaned descendants (rustc, ld.lld, build scripts)
            process.destroy()
            ProcTree.descendantPids(File("/proc"), pid).forEach { signal(it, 9) }
        } else {
            process.destroy()
        }
        try {
            if (process.isAlive) process.waitFor(2, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (process.isAlive) process.destroyForcibly()
    }

    /**
     * Best-effort pid of a [Process]: public `pid()` method (Java 9+ /
     * newer Android) first, then the private `pid` field (Android's
     * ProcessManager$ProcessImpl). Null when both fail.
     */
    private fun pidOf(process: Process): Long? {
        runCatching {
            val v = process.javaClass.getMethod("pid").invoke(process) as? Number
            if (v != null) return v.toLong()
        }
        return runCatching {
            val f = process.javaClass.getDeclaredField("pid")
            f.isAccessible = true
            (f.get(process) as? Number)?.toLong()
        }.getOrNull()
    }

    /** Runs a short probe (e.g. `rustc --version`) and returns its stdout. */
    suspend fun probe(
        command: List<String>,
        env: Map<String, String>,
        timeoutSec: Long = 15,
    ): String = withContext(Dispatchers.IO) {
        try {
            val p = ProcessBuilder(command).apply {
                environment().clear()
                environment().putAll(env)
            }.start()
            val out = StringBuilder()
            val reader = BufferedReader(InputStreamReader(p.inputStream, Charsets.UTF_8))
            val job = launch {
                try {
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (out.isNotEmpty()) out.append('\n')
                        out.append(line)
                    }
                } catch (_: IOException) {}
            }
            val finished = try {
                p.waitFor(timeoutSec, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                false
            }
            if (!finished) {
                p.destroyForcibly()
                job.cancel()
                return@withContext ""
            }
            job.join()
            out.toString().trim()
        } catch (e: IOException) {
            ""
        }
    }

    /** Bidirectional stdin for interactive `cargo run` programs. */
    class StdinPipe {
        private var writer: OutputStreamWriter? = null

        internal fun attach(stream: BufferedOutputStream) {
            writer = OutputStreamWriter(stream, Charsets.UTF_8)
        }

        internal fun detach() {
            try {
                writer?.flush()
                writer?.close()
            } catch (_: IOException) {}
            writer = null
        }

        fun sendLine(line: String) {
            try {
                writer?.let {
                    it.write(line)
                    it.write("\n")
                    it.flush()
                }
            } catch (_: IOException) {
                // process gone
            }
        }
    }
}
