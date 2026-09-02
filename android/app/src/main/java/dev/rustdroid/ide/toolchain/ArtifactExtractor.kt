package dev.rustdroid.ide.toolchain

import dev.rustdroid.ide.model.parseBundleManifest
import dev.rustdroid.ide.util.Fs
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files

/**
 * Installs the app bundle zip into $PREFIX. The validated Phase-1 recipe:
 *
 *   tarballs (rustc/cargo/rust-std) -> strip <tarball>/<component>/ dirs
 *                                       into the prefix (modes preserved)
 *   libc++_shared.so                 -> $PREFIX/lib/
 *   rustdroid-link contents          -> $PREFIX/lib/rustdroid-link/
 *   rustdroid-link/bin/{cc,clang,gcc}-> $PREFIX/bin/ (chmod 755)
 *
 * Single streaming pass (zip -> tar.xz -> files), zip-slip guarded,
 * fails loud on layout drift. Pure JVM — unit-tested.
 */
class ArtifactExtractor(
    private val paths: ToolchainPaths,
) {
    data class BundleManifestData(
        val rustVersion: String,
        val sourceRun: Long,
        val sourceCommit: String,
        val kitEntryCount: Int,
    )

    /** Progress callback: (filesWritten, estimatedTotal or null). */
    fun install(zip: File, onProgress: (Int, Int?) -> Unit = { _, _ -> }): BundleManifestData {
        val prefix = paths.prefix
        prefix.mkdirs()

        var manifest: dev.rustdroid.ide.model.BundleManifest? = null
        var libcxxDone = false
        var kitEntries = 0
        val kitDest = File(prefix, "lib/rustdroid-link")

        ZipArchiveInputStream(BufferedInputStream(zip.inputStream(), 1 shl 16)).use { zis ->
            while (true) {
                val entry: ZipArchiveEntry = zis.nextZipEntry ?: break
                val name = entry.name
                when {
                    name == ToolchainDistro.MANIFEST_ENTRY -> {
                        manifest = parseBundleManifest(zis.readBytes().decodeToString())
                    }

                    name == "libc++_shared.so" -> {
                        val dest = File(prefix, "lib/libc++_shared.so")
                        dest.parentFile?.mkdirs()
                        zis.copyToFile(dest)
                        Fs.applyPosixMode(dest, 0b110_100_100)
                        libcxxDone = true
                    }

                    name.endsWith(".tar.xz") -> {
                        val top = name.substringBefore('/')
                        if (top in DIST_TARBALLS) {
                            extractTarball(zis, prefix, name)
                        } else {
                            // unexpected tarball (e.g. full-dist docs) — skip
                            zis.skipFully()
                        }
                    }

                    name.startsWith("rustdroid-link/") -> {
                        val rel = name.removePrefix("rustdroid-link/").trimEnd('/')
                        if (rel.isNotEmpty()) {
                            val dest = Fs.resolveChild(kitDest, rel)
                            if (entry.isDirectory) {
                                dest.mkdirs()
                            } else {
                                dest.parentFile?.mkdirs()
                                zis.copyToFile(dest)
                                // kit bin/ members are the linker-driver shims:
                                // executable, exactly as CI packs them
                                val mode = if (rel.startsWith("bin/")) 0b111_101_101 else 0b110_100_100
                                Fs.applyPosixMode(dest, mode)
                                kitEntries++
                            }
                        }
                    }

                    else -> {
                        // unknown entry (README etc.) — skip data
                        zis.skipFully()
                    }
                }
            }
        }

        val m = manifest ?: error(
            "bundle manifest ${ToolchainDistro.MANIFEST_ENTRY} missing — layout drift?"
        )
        if (!libcxxDone) error("bundle missing libc++_shared.so (runtime dep of rustc/cargo)")
        if (kitEntries == 0) error("bundle has no rustdroid-link/ kit folder")

        // The cc shim is rustc's default linker: copy kit shims into prefix/bin
        for (shim in listOf("cc", "clang", "gcc")) {
            val src = File(kitDest, "bin/$shim")
            if (!src.isFile) error("kit missing bin/$shim (linker driver)")
            val dest = File(prefix, "bin/$shim")
            dest.parentFile?.mkdirs()
            src.copyTo(dest, overwrite = true)
            dest.setExecutable(true, false)
        }

        // Load-bearing sanity: fail loud before the verifier even runs
        for (must in listOf("bin/rustc", "bin/cargo", "lib/rustdroid-link/crtbegin_dynamic.o")) {
            if (!File(prefix, must).isFile) error("install incomplete: $must missing")
        }
        onProgress(1, null)

        return BundleManifestData(
            rustVersion = m.rust_version.ifEmpty { ToolchainDistro.RUST_VERSION },
            sourceRun = m.source_run,
            sourceCommit = m.source_commit,
            kitEntryCount = kitEntries,
        )
    }

    private val DIST_TARBALLS = setOf(
        "rustc-${ToolchainDistro.RUST_VERSION}-aarch64-linux-android.tar.xz",
        "cargo-${ToolchainDistro.RUST_VERSION}-aarch64-linux-android.tar.xz",
        "rust-std-${ToolchainDistro.RUST_VERSION}-aarch64-linux-android.tar.xz",
    )

    /**
     * Streams one dist tarball: every payload entry is
     * `<topdir>/<component>/rest...`. We strip the two leading segments
     * (install.sh & friends at depth 1 are dropped automatically) and
     * preserve tar mode bits.
     */
    private fun extractTarball(input: InputStream, prefix: File, label: String) {
        val tar = TarArchiveInputStream(
            XZCompressorInputStream(BufferedInputStream(input, 1 shl 16))
        )
        var top: String? = null
        var entries = 0
        while (true) {
            val e: TarArchiveEntry = tar.nextTarEntry ?: break
            val name = e.name.trimEnd('/')
            if (name.isEmpty()) continue
            val segments = name.split('/')
            if (top == null) {
                top = segments[0]
            }
            if (segments.size < 3) continue // top-level metadata (install.sh…)
            if (segments[0] != top) {
                throw IOException("$label: unexpected second root '${segments[0]}'")
            }
            val rel = segments.drop(2).joinToString("/")
            val dest = Fs.resolveChild(prefix, rel)
            if (e.isDirectory) {
                dest.mkdirs()
            } else if (e.isLink) {
                dest.parentFile?.mkdirs()
                val target = Fs.resolveChild(prefix, e.linkName.trimStart('/'))
                if (target.isFile) target.copyTo(dest, overwrite = true)
                Fs.applyPosixMode(dest, e.mode)
            } else if (e.isSymbolicLink) {
                dest.parentFile?.mkdirs()
                val ok = runCatching {
                    Files.deleteIfExists(dest.toPath())
                    Files.createSymbolicLink(dest.toPath(), java.nio.file.Paths.get(e.linkName))
                }
                if (ok.isFailure) dest.writeText("")
            } else {
                dest.parentFile?.mkdirs()
                tar.copyToFile(dest)
                Fs.applyPosixMode(dest, e.mode)
            }
            entries++
        }
        if (entries == 0) throw IOException("$label: no payload entries found")
    }

    /** Copies the CURRENT zip entry's data to [dest]. */
    private fun java.io.InputStream.copyToFile(dest: File) {
        dest.outputStream().use { out -> this.copyTo(out, 1 shl 14) }
    }

    /** Drains the current zip entry. */
    private fun ZipArchiveInputStream.skipFully() {
        val buf = ByteArray(1 shl 14)
        while (true) {
            val n = this.read(buf)
            if (n < 0) break
        }
    }
}
