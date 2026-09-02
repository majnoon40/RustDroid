package dev.rustdroid.ide

import dev.rustdroid.ide.toolchain.ArtifactExtractor
import dev.rustdroid.ide.toolchain.ToolchainPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * OPTIONAL integration test against the real published bundle.
 * Activated only when system property `rd.bundle` points to a downloaded
 * rustdroid-app-bundle-aarch64.zip (dev machines; CI skips silently).
 * Guards against layout drift between the publish workflow and the app.
 */
class RealBundleExtractionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val bundle: File? =
        System.getProperty("rd.bundle")?.takeIf { it.isNotEmpty() }?.let(::File)

    @Test
    fun `real bundle extracts into the validated prefix layout`() {
        assumeTrue("rd.bundle not set — skipping", bundle != null && bundle!!.isFile)
        val zip = bundle!!

        val filesDir = tmp.newFolder("files")
        val paths = ToolchainPaths(filesDir)
        val info = ArtifactExtractor(paths).install(zip)

        assertEquals("1.85.0", info.rustVersion)
        assertTrue("kit entries: ${info.kitEntryCount}", info.kitEntryCount >= 20)
        assertTrue(paths.isInstalled())

        val prefix = paths.prefix
        assertTrue(File(prefix, "bin/rustc").canExecute())
        assertTrue(File(prefix, "bin/cargo").canExecute())
        for (shim in listOf("cc", "clang", "gcc")) {
            assertTrue(File(prefix, "bin/$shim").canExecute())
            assertTrue(
                "shebang",
                File(prefix, "bin/$shim").readText().startsWith("#!/system/bin/sh"),
            )
        }
        assertTrue(File(prefix, "lib/rustlib/aarch64-linux-android/bin/gcc-ld/ld.lld").isFile)
        assertTrue(File(prefix, "lib/rustdroid-link/crtbegin_dynamic.o").isFile)
        assertTrue(File(prefix, "lib/rustdroid-link/libunwind.a").isFile)
        assertTrue(File(prefix, "lib/rustdroid-link/sysroot/libc.so").isFile)
        assertTrue(File(prefix, "lib/libc++_shared.so").isFile)
        // libstd from the rust-std tarball
        assertTrue(
            File(prefix, "lib/rustlib/aarch64-linux-android/lib").isDirectory,
        )
    }
}
