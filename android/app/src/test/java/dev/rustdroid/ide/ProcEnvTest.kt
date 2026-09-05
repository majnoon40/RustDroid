package dev.rustdroid.ide

import dev.rustdroid.ide.runtime.ProcEnv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

    // ---- noexec-storage workaround: CARGO_TARGET_DIR redirect ----

    @Test
    fun `redirectedTargetDir is null for anything under app data`() {
        val files = tmp.newFolder("files-redirect")
        // internal projects live under files/projects
        assertNull(ProcEnv.redirectedTargetDir(files, File(files, "projects/hello")))
        // toolchain scratch lives under files/home/scratch
        assertNull(ProcEnv.redirectedTargetDir(files, File(files, "home/scratch")))
        // filesDir itself (defensive) and a deeper synthetic path
        assertNull(ProcEnv.redirectedTargetDir(files, files))
        assertNull(ProcEnv.redirectedTargetDir(files, File(files, "a/b/c/d")))
    }

    @Test
    fun `redirectedTargetDir is stable, hashed and lives under files build`() {
        val files = tmp.newFolder("files-redirect2")
        val external = tmp.newFolder("storage").resolve("Download/proj")
        val dir = ProcEnv.redirectedTargetDir(files, external)
        assertNotNull(dir)
        // always under files/build, keyed by a 16-hex digest…
        assertEquals(
            File(ProcEnv.redirectedTargetRoot(files), dir!!.name).canonicalPath,
            dir.canonicalPath,
        )
        assertTrue(dir.name.matches(Regex("^[0-9a-f]{16}$")))
        // …derived from the project PATH, never its basename…
        assertTrue(dir.name != "proj")
        // …and stable across calls
        assertEquals(
            dir.canonicalPath,
            ProcEnv.redirectedTargetDir(files, external)!!.canonicalPath,
        )
    }

    @Test
    fun `redirectedTargetDir separates same-named folders in different places`() {
        val files = tmp.newFolder("files-redirect3")
        val storage = tmp.newFolder("storage-redirect3")
        val a = ProcEnv.redirectedTargetDir(files, File(storage, "Download/proj"))!!
        val b = ProcEnv.redirectedTargetDir(files, File(storage, "Documents/proj"))!!
        assertTrue(a.name != b.name)
    }

    @Test
    fun `env exports CARGO_TARGET_DIR only when a redirect is supplied`() {
        val prefix = tmp.newFolder("usr-targetdir")
        val files = tmp.newFolder("files-targetdir")
        val external = tmp.newFolder("ext-targetdir")
        val redirect = ProcEnv.redirectedTargetDir(files, external)

        val plain = ProcEnv.env(prefix, files, caBundle = null)
        assertNull(plain["CARGO_TARGET_DIR"])

        val redirected = ProcEnv.env(prefix, files, caBundle = null, cargoTargetDir = redirect)
        assertEquals(redirect!!.absolutePath, redirected["CARGO_TARGET_DIR"])
    }
}
