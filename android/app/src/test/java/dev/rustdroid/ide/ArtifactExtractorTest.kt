package dev.rustdroid.ide

import dev.rustdroid.ide.toolchain.ArtifactExtractor
import dev.rustdroid.ide.toolchain.ToolchainPaths
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * End-to-end JVM test of the bundle installer: synthetic tarballs + kit,
 * zipped exactly like the CI publish workflow does, then extracted.
 */
class ArtifactExtractorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun tarXz(vararg entries: Pair<String, String>): ByteArray {
        val bytes = java.io.ByteArrayOutputStream()
        TarArchiveOutputStream(XZCompressorOutputStream(bytes)).use { tar ->
            for ((name, content) in entries) {
                val e = TarArchiveEntry(name)
                e.mode = if (name.startsWith("top/") && name.count { it == '/' } >= 2) {
                    if (name.endsWith("bin/rustc") || name.endsWith("bin/cargo")) 0b111_101_101 else 0b110_100_100
                } else 0b110_100_100
                e.size = content.toByteArray().size.toLong()
                tar.putArchiveEntry(e)
                tar.write(content.toByteArray())
                tar.closeArchiveEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun writeBundle(zip: File) {
        ZipArchiveOutputStream(zip.outputStream()).use { zos ->
            fun put(name: String, data: ByteArray, mode: Int = 0b110_100_100) {
                val e = ZipArchiveEntry(name)
                e.externalAttributes = (mode shl 16).toLong()
                zos.putArchiveEntry(e)
                zos.write(data)
                zos.closeArchiveEntry()
            }

            val manifest = """
                {
                  "format": 1,
                  "rust_version": "1.85.0",
                  "target": "aarch64-linux-android",
                  "created": "2026-09-02T00:00:00Z",
                  "source_run": 26,
                  "source_commit": "2cc5296",
                  "components": {}
                }
            """.trimIndent().toByteArray()
            put("rustdroid-app-bundle.json", manifest)

            put(
                "rustc-1.85.0-aarch64-linux-android.tar.xz",
                tarXz(
                    "rustc-1.85.0-aarch64-linux-android/rustc/bin/rustc" to "#!/bin/sh\nfake-rustc",
                    "rustc-1.85.0-aarch64-linux-android/rustc/lib/rustlib/aarch64-linux-android/bin/gcc-ld/ld.lld" to "fake-lld",
                    "rustc-1.85.0-aarch64-linux-android/rustc/lib/rustlib/aarch64-linux-android/bin/rust-lld" to "fake-lld2",
                    "rustc-1.85.0-aarch64-linux-android/install.sh" to "#!/bin/sh",
                ),
            )
            put(
                "cargo-1.85.0-aarch64-linux-android.tar.xz",
                tarXz("cargo-1.85.0-aarch64-linux-android/cargo/bin/cargo" to "#!/bin/sh\nfake-cargo"),
            )
            put(
                "rust-std-1.85.0-aarch64-linux-android.tar.xz",
                tarXz(
                    "rust-std-1.85.0-aarch64-linux-android/rust-std-aarch64-linux-android/lib/rustlib/aarch64-linux-android/lib/libstd.rlib" to "fake-rlib",
                ),
            )
            put("libc++_shared.so", "fake-libcxx".toByteArray())

            put("rustdroid-link/bin/cc", "#!/system/bin/sh\n# shim referencing gcc-ld/ld.lld\nexec lld".toByteArray(), 0b111_101_101)
            put("rustdroid-link/bin/clang", "#!/system/bin/sh\nexec lld".toByteArray(), 0b111_101_101)
            put("rustdroid-link/bin/gcc", "#!/system/bin/sh\nexec lld".toByteArray(), 0b111_101_101)
            put("rustdroid-link/crtbegin_dynamic.o", "crt-object".toByteArray())
            put("rustdroid-link/libunwind.a", "!<arch>\n".toByteArray())
            put("rustdroid-link/sysroot/libc.so", "stub".toByteArray())
        }
    }

    @Test
    fun `install lays out the validated prefix exactly`() {
        val zip = File(tmp.root, "bundle.zip")
        writeBundle(zip)

        val filesDir = tmp.newFolder("files")
        val paths = ToolchainPaths(filesDir)
        val extractor = ArtifactExtractor(paths)
        val info = extractor.install(zip)

        val prefix = paths.prefix
        assertEquals("1.85.0", info.rustVersion)

        // tarball flattening: <top>/<component>/x -> prefix/x
        val rustc = File(prefix, "bin/rustc")
        assertTrue("bin/rustc missing", rustc.isFile)
        assertTrue("rustc not executable", rustc.canExecute())
        assertTrue(File(prefix, "bin/cargo").canExecute())
        assertTrue(
            File(prefix, "lib/rustlib/aarch64-linux-android/bin/gcc-ld/ld.lld").isFile
        )
        // install.sh at depth 1 must NOT land in the prefix
        assertFalse(File(prefix, "install.sh").exists())

        // kit placement
        val kit = File(prefix, "lib/rustdroid-link")
        assertTrue(File(kit, "bin/cc").canExecute())
        assertTrue(File(kit, "crtbegin_dynamic.o").isFile)
        assertTrue(File(kit, "sysroot/libc.so").isFile)

        // shims copied into prefix/bin and executable
        for (shim in listOf("cc", "clang", "gcc")) {
            val f = File(prefix, "bin/$shim")
            assertTrue("bin/$shim missing", f.isFile)
            assertTrue("bin/$shim not executable", f.canExecute())
        }

        // libc++ into prefix/lib
        assertTrue(File(prefix, "lib/libc++_shared.so").isFile)

        // readiness summary
        assertTrue(paths.isInstalled())
    }

    @Test
    fun `missing manifest fails loud`() {
        val zip = File(tmp.root, "bad.zip")
        ZipArchiveOutputStream(zip.outputStream()).use { zos ->
            val e = ZipArchiveEntry("random.txt")
            zos.putArchiveEntry(e)
            zos.write("nothing".toByteArray())
            zos.closeArchiveEntry()
        }
        val paths = ToolchainPaths(tmp.newFolder("files2"))
        try {
            ArtifactExtractor(paths).install(zip)
            throw AssertionError("expected failure")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("manifest"))
        }
    }
}
