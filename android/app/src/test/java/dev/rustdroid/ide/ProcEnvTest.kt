package dev.rustdroid.ide

import dev.rustdroid.ide.runtime.ProcEnv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

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

    @Test
    fun `TLS trust - env vars point cargo and openssl at the CA bundle`() {
        val prefix = tmp.newFolder("usr-ca")
        val files = tmp.newFolder("files-ca")
        val bundle = tmp.newFile("cacert.pem")
        val env = ProcEnv.env(prefix, files, caBundle = bundle)
        assertEquals(prefix.absolutePath, env["RUSTDROID_PREFIX"])
        assertEquals(bundle.absolutePath, env["CARGO_HTTP_CAINFO"])
        assertEquals(bundle.absolutePath, env["SSL_CERT_FILE"])
        assertEquals(bundle.absolutePath, env["CURL_CA_BUNDLE"])
        if (File(ProcEnv.SYSTEM_CA_DIR).isDirectory) {
            assertEquals(ProcEnv.SYSTEM_CA_DIR, env["SSL_CERT_DIR"])
        }
    }

    @Test
    fun `TLS trust - no bundle means no CA vars, prefix still exported`() {
        val prefix = tmp.newFolder("usr-ca2")
        val files = tmp.newFolder("files-ca2")
        val env = ProcEnv.env(prefix, files, caBundle = null)
        assertEquals(prefix.absolutePath, env["RUSTDROID_PREFIX"])
        assertNull(env["CARGO_HTTP_CAINFO"])
        assertNull(env["SSL_CERT_FILE"])
        assertNull(env["CURL_CA_BUNDLE"])
    }
}
