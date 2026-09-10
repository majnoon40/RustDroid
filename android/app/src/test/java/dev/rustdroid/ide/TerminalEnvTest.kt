package dev.rustdroid.ide

import dev.rustdroid.ide.runtime.ProcEnv
import dev.rustdroid.ide.runtime.terminal.TerminalEnv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Table-driven JVM test for the terminal env deltas vs ProcEnv.env
 * (plan §6.4 / §8.2): TerminalEnv is DERIVED from ProcEnv — every shared
 * channel must be string-identical (one source of truth), and the ONLY
 * delta is TERM.
 */
class TerminalEnvTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun envs(): Pair<Map<String, String>, Map<String, String>> {
        val prefix = File(tmp.root, "usr")
        val filesDir = File(tmp.root, "files")
        // caBundle injected null: no file I/O, no asset provider — pure map
        val build = ProcEnv.env(prefix, filesDir, caBundle = null)
        val terminal = TerminalEnv.env(prefix, filesDir, caBundle = null)
        return build to terminal
    }

    @Test
    fun `shared channels are string-identical to ProcEnv - one source of truth`() {
        val (build, terminal) = envs()
        for (key in TerminalEnv.SHARED_KEYS) {
            assertTrue("ProcEnv must provide $key", build.containsKey(key))
            assertEquals(
                "terminal env must not drift from ProcEnv on $key (plan §8.2)",
                build[key],
                terminal[key],
            )
        }
    }

    @Test
    fun `the only delta is TERM - xterm-256color replaces dumb`() {
        val (build, terminal) = envs()
        assertEquals("dumb", build["TERM"])
        assertEquals("xterm-256color", terminal["TERM"])
        val changed = build.keys.filter { build[it] != terminal[it] } +
            terminal.keys.filter { it !in build }
        assertEquals("TERM is the ONLY difference (plan §8.2)", listOf("TERM"), changed)
    }

    @Test
    fun `CA bundle channels carry through when a bundle is present`() {
        val prefix = File(tmp.root, "usr")
        val filesDir = File(tmp.root, "files")
        val caFile = File(tmp.root, "ca.pem").apply { writeText("dummy") }
        val terminal = TerminalEnv.env(prefix, filesDir, caBundle = caFile)
        assertEquals(caFile.absolutePath, terminal["CARGO_HTTP_CAINFO"])
        assertEquals(caFile.absolutePath, terminal["SSL_CERT_FILE"])
        assertEquals(caFile.absolutePath, terminal["CURL_CA_BUNDLE"])
        assertEquals(prefix.absolutePath, terminal["RUSTDROID_PREFIX"])
    }

    @Test
    fun `no SSL_CERT_DIR in the terminal env either - the CApath lesson holds`() {
        val (_, terminal) = envs()
        assertTrue(terminal.keys.none { it == "SSL_CERT_DIR" })
    }

    @Test
    fun `PATH leads with the toolchain bin and gains nothing new`() {
        val (build, terminal) = envs()
        assertEquals(build["PATH"], terminal["PATH"]) // §8.2: PATH gains nothing
        assertTrue(terminal["PATH"]!!.startsWith(File(tmp.root, "usr/bin").absolutePath))
    }

    @Test
    fun `cargo color choice stays never - unrelated to terminal capability`() {
        val (_, terminal) = envs()
        assertEquals("never", terminal["CARGO_TERM_COLOR"])
    }

    @Test
    fun `shell launch parameters - PREFIX bin sh, argv sh, cwd HOME`() {
        val prefix = File(tmp.root, "usr")
        assertEquals(File(prefix, "bin/sh"), TerminalEnv.shellPath(prefix))
        assertEquals(arrayOf("sh"), TerminalEnv.shellArgs())
    }

    @Test
    fun `term is stable and exported`() {
        assertEquals("xterm-256color", TerminalEnv.TERM)
        assertNotEquals(ProcEnv::class.java.simpleName, TerminalEnv::class.java.simpleName)
    }
}
