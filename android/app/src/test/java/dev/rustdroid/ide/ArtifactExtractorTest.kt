package dev.rustdroid.ide

import dev.rustdroid.ide.toolchain.ArtifactExtractor
import dev.rustdroid.ide.toolchain.ToolchainPaths
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
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
import java.io.IOException
import java.nio.file.Files

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
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                val e = TarArchiveEntry(name)
                e.mode = if (name.endsWith("bin/rustc") || name.endsWith("bin/cargo")) {
                    0b111_101_101
                } else {
                    0b110_100_100
                }
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

    // ---- link entries: two-pass resolution, no silent gaps ----

    /** A tar with an explicit link entry (hard or symbolic). */
    private fun linkTarEntry(
        tar: TarArchiveOutputStream,
        name: String,
        linkName: String,
        flag: Byte,
        mode: Int = 0b110_100_100,
    ) {
        val e = TarArchiveEntry(name, flag)
        e.linkName = linkName
        e.mode = mode
        tar.putArchiveEntry(e)
        tar.closeArchiveEntry()
    }

    private fun minimalBundleWith(extraTarEntries: TarArchiveOutputStream.() -> Unit): File {
        val zip = File(tmp.root, "links-${System.nanoTime()}.zip")
        val rustcTar = java.io.ByteArrayOutputStream()
        TarArchiveOutputStream(XZCompressorOutputStream(rustcTar)).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            fun file(name: String, content: String) {
                val e = TarArchiveEntry(name)
                e.size = content.toByteArray().size.toLong()
                e.mode = 0b110_100_100
                tar.putArchiveEntry(e)
                tar.write(content.toByteArray())
                tar.closeArchiveEntry()
            }
            file("rustc-1.85.0-aarch64-linux-android/rustc/bin/rustc", "#!/bin/sh\nfake-rustc")
            file(
                "rustc-1.85.0-aarch64-linux-android/rustc/lib/rustlib/aarch64-linux-android/bin/gcc-ld/ld.lld",
                "fake-lld",
            )
            file(
                "rustc-1.85.0-aarch64-linux-android/rustc/lib/rustlib/aarch64-linux-android/bin/rust-lld",
                "fake-lld2",
            )
            extraTarEntries(tar)
        }
        ZipArchiveOutputStream(zip.outputStream()).use { zos ->
            val manifest = """
                {"format":1,"rust_version":"1.85.0","target":"aarch64-linux-android",
                "created":"2026-09-02T00:00:00Z","source_run":26,"source_commit":"2cc5296",
                "components":{}}
            """.trimIndent().toByteArray()
            zos.putArchiveEntry(ZipArchiveEntry("rustdroid-app-bundle.json"))
            zos.write(manifest)
            zos.closeArchiveEntry()

            zos.putArchiveEntry(
                ZipArchiveEntry("rustc-1.85.0-aarch64-linux-android.tar.xz")
            )
            zos.write(rustcTar.toByteArray())
            zos.closeArchiveEntry()

            // cargo tarball: the install() sanity check requires bin/cargo
            val cargoTar = java.io.ByteArrayOutputStream()
            TarArchiveOutputStream(XZCompressorOutputStream(cargoTar)).use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                val e = TarArchiveEntry("cargo-1.85.0-aarch64-linux-android/cargo/bin/cargo")
                val content = "#!/bin/sh\nfake-cargo"
                e.size = content.toByteArray().size.toLong()
                e.mode = 0b111_101_101
                tar.putArchiveEntry(e)
                tar.write(content.toByteArray())
                tar.closeArchiveEntry()
            }
            zos.putArchiveEntry(
                ZipArchiveEntry("cargo-1.85.0-aarch64-linux-android.tar.xz")
            )
            zos.write(cargoTar.toByteArray())
            zos.closeArchiveEntry()

            fun kit(name: String, content: String, mode: Int = 0b110_100_100) {
                val e = ZipArchiveEntry(name)
                e.externalAttributes = (mode shl 16).toLong()
                zos.putArchiveEntry(e)
                zos.write(content.toByteArray())
                zos.closeArchiveEntry()
            }
            kit("rustdroid-link/bin/cc", "#!/system/bin/sh\nexec lld", 0b111_101_101)
            kit("rustdroid-link/bin/clang", "#!/system/bin/sh\nexec lld", 0b111_101_101)
            kit("rustdroid-link/bin/gcc", "#!/system/bin/sh\nexec lld", 0b111_101_101)
            kit("rustdroid-link/crtbegin_dynamic.o", "crt-object")
            zos.putArchiveEntry(ZipArchiveEntry("libc++_shared.so"))
            zos.write("fake-libcxx".toByteArray())
            zos.closeArchiveEntry()
        }
        return zip
    }

    @Test
    fun `hardlink entry preceding its target is resolved after extraction`() {
        // link FIRST (target comes later in the stream) — order-independent
        val zip = minimalBundleWith {
            linkTarEntry(
                this,
                "rustc-1.85.0-aarch64-linux-android/rustc/lib/alias.rlib",
                "rustc-1.85.0-aarch64-linux-android/rustc/lib/real.rlib",
                TarConstants.LF_LINK,
            )
            val e = TarArchiveEntry("rustc-1.85.0-aarch64-linux-android/rustc/lib/real.rlib")
            e.size = 6L
            putArchiveEntry(e)
            write("shared".toByteArray())
            closeArchiveEntry()
        }
        val filesDir = tmp.newFolder("files-hardlink-first")
        val paths = ToolchainPaths(filesDir)
        ArtifactExtractor(paths).install(zip)

        // BOTH files exist with identical content — the old code silently
        // skipped the hardlink when its target was not yet on disk
        assertEquals("shared", File(paths.prefix, "lib/alias.rlib").readText())
        assertEquals("shared", File(paths.prefix, "lib/real.rlib").readText())
    }

    @Test
    fun `hardlink with stripped-form linkName also resolves`() {
        // some writers emit the target as the already-stripped rel path
        val zip = minimalBundleWith {
            val e = TarArchiveEntry("rustc-1.85.0-aarch64-linux-android/rustc/lib/hard.rlib")
            e.size = 6L
            putArchiveEntry(e)
            write("bytes!".toByteArray())
            closeArchiveEntry()
            linkTarEntry(
                this,
                "rustc-1.85.0-aarch64-linux-android/rustc/lib/hard2.rlib",
                "lib/hard.rlib",
                TarConstants.LF_LINK,
            )
        }
        val paths = ToolchainPaths(tmp.newFolder("files-hardlink-stripped"))
        ArtifactExtractor(paths).install(zip)
        assertEquals("bytes!", File(paths.prefix, "lib/hard2.rlib").readText())
    }

    @Test
    fun `hardlink with unresolvable target fails loud instead of vanishing`() {
        val zip = minimalBundleWith {
            linkTarEntry(
                this,
                "rustc-1.85.0-aarch64-linux-android/rustc/lib/ghost.rlib",
                "rustc-1.85.0-aarch64-linux-android/rustc/lib/does-not-exist.rlib",
                TarConstants.LF_LINK,
            )
        }
        val paths = ToolchainPaths(tmp.newFolder("files-hardlink-missing"))
        try {
            ArtifactExtractor(paths).install(zip)
            throw AssertionError("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("ghost.rlib"))
        }
        assertFalse(File(paths.prefix, "lib/ghost.rlib").exists())
    }

    @Test
    fun `in-prefix relative symlink is created as a real symlink`() {
        val zip = minimalBundleWith {
            linkTarEntry(
                this,
                "rustc-1.85.0-aarch64-linux-android/rustc/lib/alias.so",
                "real.so",
                TarConstants.LF_SYMLINK,
            )
            val e = TarArchiveEntry("rustc-1.85.0-aarch64-linux-android/rustc/lib/real.so")
            e.size = 3L
            putArchiveEntry(e)
            write("abc".toByteArray())
            closeArchiveEntry()
        }
        val paths = ToolchainPaths(tmp.newFolder("files-symlink-ok"))
        ArtifactExtractor(paths).install(zip)
        val link = File(paths.prefix, "lib/alias.so")
        assertTrue(Files.isSymbolicLink(link.toPath()))
        assertEquals("real.so", Files.readSymbolicLink(link.toPath()).toString())
        // and it resolves to real content, not an empty placeholder
        assertEquals("abc", link.readText())
    }

    @Test
    fun `symlink escaping the prefix is rejected, never an empty file`() {
        val zip = minimalBundleWith {
            linkTarEntry(
                this,
                "rustc-1.85.0-aarch64-linux-android/rustc/lib/evil.so",
                "../../../../../data/local/tmp/pwned.so",
                TarConstants.LF_SYMLINK,
            )
        }
        val paths = ToolchainPaths(tmp.newFolder("files-symlink-escape"))
        try {
            ArtifactExtractor(paths).install(zip)
            throw AssertionError("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("evil.so"))
        }
        // the empty-file fallback of the old code is gone
        assertFalse(File(paths.prefix, "lib/evil.so").exists())
    }

    @Test
    fun `absolute symlink inside the prefix is allowed`() {
        val paths = ToolchainPaths(tmp.newFolder("files-symlink-abs"))
        val zip = minimalBundleWith {
            linkTarEntry(
                this,
                "rustc-1.85.0-aarch64-linux-android/rustc/lib/abs.so",
                File(paths.prefix, "lib/real.so").absolutePath,
                TarConstants.LF_SYMLINK,
            )
            val e = TarArchiveEntry("rustc-1.85.0-aarch64-linux-android/rustc/lib/real.so")
            e.size = 3L
            putArchiveEntry(e)
            write("xyz".toByteArray())
            closeArchiveEntry()
        }
        ArtifactExtractor(paths).install(zip)
        val link = File(paths.prefix, "lib/abs.so")
        assertTrue(Files.isSymbolicLink(link.toPath()))
        assertEquals("xyz", link.readText())
    }
}
