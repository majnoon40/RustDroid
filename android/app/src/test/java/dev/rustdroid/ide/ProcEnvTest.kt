package dev.rustdroid.ide

import dev.rustdroid.ide.runtime.ProcEnv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProcEnvTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `env contract matches the Phase 1 validated recipe`() {
        val prefix = tmp.newFolder("usr")
        val files = tmp.newFolder("files")
        val env = ProcEnv.env(prefix, files)

        assertEquals("${prefix.absolutePath}/bin:/system/bin:/system/xbin", env["PATH"])
        assertEquals("${prefix.absolutePath}/lib", env["LD_LIBRARY_PATH"])
        assertEquals("${files.absolutePath}/home", env["HOME"])
        assertEquals("${files.absolutePath}/home/.cargo", env["CARGO_HOME"])
        assertEquals("${files.absolutePath}/home/tmp", env["TMPDIR"])
        assertEquals("never", env["CARGO_TERM_COLOR"])
    }

    @Test
    fun `dirs are created on demand`() {
        val prefix = tmp.newFolder("usr2")
        val files = tmp.newFolder("files2")
        ProcEnv.env(prefix, files)
        assertTrue(ProcEnv.homeDir(files).isDirectory)
        assertTrue(ProcEnv.cargoHome(files).isDirectory)
        assertTrue(ProcEnv.tmpDir(files).isDirectory)
    }

    @Test
    fun `toolchain command is absolute`() {
        val prefix = tmp.newFolder("usr3")
        assertEquals(
            java.io.File(prefix, "bin/cargo").absolutePath,
            ProcEnv.toolchainCommand(prefix, "cargo"),
        )
    }
}
