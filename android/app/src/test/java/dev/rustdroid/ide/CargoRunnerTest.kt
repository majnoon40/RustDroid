package dev.rustdroid.ide

import dev.rustdroid.ide.runtime.CargoRunner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * probe() stream discipline. The probe reads stdout in a pump coroutine
 * while the child runs; stderr must be drained CONCURRENTLY, or a child
 * that writes more stderr than the kernel pipe buffer (~64 KB) blocks
 * in write() forever, never exits, and a perfectly healthy toolchain
 * probes as dead (waitFor timeout, empty stdout).
 *
 * These are real-subprocess tests: they run in the HOST JVM of the
 * Gradle sandbox (unit tests execute on the developer/CI machine, not
 * on an Android device), where /bin/sh and POSIX pipes behave normally.
 * No android.jar APIs are touched (CargoRunner is pure JVM at this
 * layer), so the deadlock reproduces faithfully. Hosts without a POSIX
 * sh (e.g. Windows) skip the subprocess tests via assumeTrue rather
 * than failing — the production behavior is unchanged either way.
 */
class CargoRunnerTest {

    /**
     * ~1.2 MB of stderr (20k padded lines) — far beyond the 64 KB pipe
     * buffer — printed BEFORE the single stdout answer. Without the
     * concurrent stderr drain, the child blocks inside the loop, the
     * probe times out (probe then returns "") and the caller concludes
     * the probed binary is broken.
     */
    @Test
    fun `chatty stderr cannot deadlock the probe`() = runBlocking {
        assumeTrue("/bin/sh required for a real pipe-buffer overflow", File("/bin/sh").canExecute())
        val runner = CargoRunner()
        val script = """
            i=0
            while [ ${'$'}i -lt 20000 ]; do
              echo noise line ${'$'}i with plenty of padding to overflow the stderr pipe buffer 1>&2
              i=${'$'}((i+1))
            done
            echo PROBE_OK
        """.trimIndent()

        val out = runner.probe(listOf("/bin/sh", "-c", script), emptyMap(), timeoutSec = 30)

        // the child could only finish (and print PROBE_OK) if its stderr
        // pipe was drained while stdout was being read
        assertEquals("PROBE_OK", out)
    }

    /** Baseline: probe returns the child's stdout, trimmed. */
    @Test
    fun `probe returns stdout of a well-behaved child`() = runBlocking {
        assumeTrue("/bin/sh required", File("/bin/sh").canExecute())
        val runner = CargoRunner()
        val out = runner.probe(listOf("/bin/sh", "-c", "  echo hello-probe "), emptyMap(), timeoutSec = 30)
        assertEquals("hello-probe", out)
    }

    /** A child that cannot even start probes as empty, not as a throw. */
    @Test
    fun `probe of a nonexistent binary returns empty`() = runBlocking {
        val runner = CargoRunner()
        val out = runner.probe(
            listOf("/definitely/not/a/real/binary-rustdroid-test"),
            emptyMap(),
            timeoutSec = 30,
        )
        assertTrue(out.isEmpty())
    }
}
