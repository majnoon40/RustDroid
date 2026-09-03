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
    }

    @Test
    fun `TLS trust - SSL_CERT_DIR is never exported (CApath poisons verify setup)`() {
        // libcurl maps SSL_CERT_DIR to CURLOPT_CAPATH; some statically-linked
        // TLS backends in Android cargo builds fail the whole
        // SSL_CTX_load_verify_locations when a CApath is present — curl 77
        // even with a valid CAfile. The env must stay CAfile-only.
        val prefix = tmp.newFolder("usr-capath")
        val files = tmp.newFolder("files-capath")
        val bundle = tmp.newFile("cacert-capath.pem")
        val withBundle = ProcEnv.env(prefix, files, caBundle = bundle)
        assertNull(withBundle["SSL_CERT_DIR"])
        val withoutBundle = ProcEnv.env(prefix, files, caBundle = null)
        assertNull(withoutBundle["SSL_CERT_DIR"])
    }

    @Test
    fun `TLS escape hatch - marker file or flag enables danger-accept-invalid-certs`() {
        val prefix = tmp.newFolder("usr-hatch")
        val files = tmp.newFolder("files-hatch")

        // default: off
        val plain = ProcEnv.env(prefix, files, caBundle = null)
        assertNull(plain["CARGO_HTTP_DANGER_ACCEPT_INVALID_CERTS"])

        // per-invocation flag (project marker found by the caller)
        val flagged = ProcEnv.env(prefix, files, caBundle = null, insecureTlsOk = true)
        assertEquals("true", flagged["CARGO_HTTP_DANGER_ACCEPT_INVALID_CERTS"])

        // $HOME marker file
        val marker = File(ProcEnv.homeDir(files), ProcEnv.INSECURE_TLS_MARKER_HOME)
        marker.parentFile!!.mkdirs()
        assertTrue(marker.createNewFile())
        val marked = ProcEnv.env(prefix, files, caBundle = null)
        assertEquals("true", marked["CARGO_HTTP_DANGER_ACCEPT_INVALID_CERTS"])
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
