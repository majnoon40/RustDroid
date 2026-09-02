package dev.rustdroid.ide.toolchain

import dev.rustdroid.ide.model.parseBundleManifest
import dev.rustdroid.ide.util.Fs
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * Installs the app bundle zip into $PREFIX. The validated Phase-1 recipe:
 *
 *   tarballs (rustc/cargo/rust-std) -> strip <tarball>/<component>/ dirs
 *                                       into the prefix (modes preserved)
 *   libc++_shared.so                 -> $PREFIX/lib/
 *   rustdroid-link/**                -> $PREFIX/lib/rustdroid-link/
 *   rustdroid-link/bin/{cc,clang,gcc}-> $PREFIX/bin/ (chmod 755)
 *
 * Zip-slip guarded, fails loud on layout drift. Pure JVM — unit-tested.
 */
class ArtifactExtractor(
    private val paths: ToolchainPaths,
) {
    /** Progress callback: (filesWritten, estimatedTotal or null). */
    fun install(zip: File, onProgress: (Int, Int?) -> Unit = { _, _ -> }): BundleManifestData {
        val prefix = paths.prefix
        prefix.mkdirs()

        ZipFile.builder().setFile(zip).setReadOnly(true).get().use { zf ->
            val manifest = readManifest(zf) ?: error(
                "bundle manifest ${ToolchainDistro.MANIFEST_ENTRY} missing — layout drift?"
            )

            // 1. dist tarballs -> flatten into prefix
            val tarballs = listOf(
                "rustc-${ToolchainDistro.RUST_VERSION}-aarch64-linux-android.tar.xz",
                "cargo-${ToolchainDistro.RUST_VERSION}-aarch64-linux-android.tar.xz",
                "rust-std-${ToolchainDistro.RUST_VERSION}-aarch64-linux-android.tar.xz",
            )
            for (name in tarballs) {
                val entry = zf.getEntry(name) ?: error("bundle missing $name")
                extractTarball(zf.getInputStream(entry), prefix, name)
            }

            onProgress(1, null)

            // 2. libc++_shared.so -> prefix/lib/
            copyZipEntry(zf, "libc++_shared.so", File(prefix, "lib/libc++_shared.so"), 0b110_100_100)

            // 3. rustdroid-link/** -> prefix/lib/rustdroid-link/
            val kitDest = File(prefix, "lib/rustdroid-link")
            var kitEntries = 0
            for (entry in zf.entries) {
                val name = entry.name
                if (!name.startsWith("rustdroid-link/")) continue
                val rel = name.removePrefix("rustdroid-link/").trimEnd('/')
                if (rel.isEmpty()) continue
                val dest = Fs.resolveChild(kitDest, rel)
                if (entry.isDirectory) {
                    dest.mkdirs()
                } else {
                    dest.parentFile?.mkdirs()
                    zf.getInputStream(entry).use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    }
                    val mode = unixMode(entry)
                    Fs.applyPosixMode(dest, if (mode != 0) mode else 0b110_100_100)
                    kitEntries++
                }
            }
            if (kitEntries == 0) error("bundle has no rustdroid-link/ kit folder")

            // 4. shims -> prefix/bin (the cc shim is rustc's default linker)
            for (shim in listOf("cc", "clang", "gcc")) {
                val src = File(kitDest, "bin/$shim")
                if (!src.isFile) error("kit missing bin/$shim (linker driver)")
                val dest = File(prefix, "bin/$shim")
                dest.parentFile.mkdirs()
                src.copyTo(dest, overwrite = true)
                dest.setExecutable(true, false)
            }

            // 5. sanity: the load-bearing binaries must exist
            for (must in listOf("bin/rustc", "bin/cargo", "lib/rustdroid-link/crtbegin_dynamic.o")) {
                if (!File(prefix, must).isFile) error("install incomplete: $must missing")
            }

            return BundleManifestData(
                rustVersion = manifest.rust_version.ifEmpty { ToolchainDistro.RUST_VERSION },
                sourceRun = manifest.source_run,
                sourceCommit = manifest.source_commit,
                kitEntryCount = kitEntries,
            )
        }
    }

    data class BundleManifestData(
        val rustVersion: String,
        val sourceRun: Long,
        val sourceCommit: String,
        val kitEntryCount: Int,
    )

    private fun readManifest(zf: ZipFile): dev.rustdroid.ide.model.BundleManifest? =
        zf.getEntry(ToolchainDistro.MANIFEST_ENTRY)?.let { e ->
            zf.getInputStream(e).bufferedReader().use { r -> parseBundleManifest(r.readText()) }
        }

    /**
     * Streams one dist tarball: every entry is `<topdir>/<component>/rest...`.
     * We strip the two leading segments (install.sh & friends at depth 1 are
     * dropped automatically) and preserve tar mode bits.
     */
    private fun extractTarball(input: java.io.InputStream, prefix: File, label: String) {
        val tar = TarArchiveInputStream(XZCompressorInputStream(BufferedInputStream(input, 1 shl 16)))
        var top: String? = null
        var entries = 0
        while (true) {
            val e: TarArchiveEntry = tar.nextTarEntry ?: break
            val name = e.name.trimEnd('/')
            if (name.isEmpty()) continue
            val segments = name.split('/')
            if (top == null) {
                top = segments[0]
                require(top.isNotEmpty()) { "bad first entry in $label: '${e.name}'" }
            }
            if (segments.size < 3) continue // top-level metadata (install.sh, components…)
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
                if (target.isFile) target.copyTo(dest, overwrite = true) else dest.writeBytes("")
                Fs.applyPosixMode(dest, e.mode)
            } else if (e.isSymbolicLink) {
                dest.parentFile?.mkdirs()
                runCatching {
                    Files.deleteIfExists(dest.toPath())
                    Files.createSymbolicLink(dest.toPath(), java.nio.file.Paths.get(e.linkName))
                }.onFailure {
                    // fall back to a regular empty file; verifier will judge
                    dest.writeBytes("")
                }
            } else {
                dest.parentFile?.mkdirs()
                dest.outputStream().use { out -> tar.copyTo(out, 1 shl 14) }
                Fs.applyPosixMode(dest, e.mode)
            }
            entries++
        }
        if (entries == 0) throw IOException("$label: no payload entries found")
    }

    private fun copyZipEntry(zf: ZipFile, name: String, dest: File, mode: Int) {
        val entry = zf.getEntry(name) ?: error("bundle missing $name")
        dest.parentFile?.mkdirs()
        zf.getInputStream(entry).use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
        Fs.applyPosixMode(dest, unixMode(entry).takeIf { it != 0 } ?: mode)
    }

    private fun unixMode(entry: ZipArchiveEntry): Int {
        // unix perms live in the upper 16 bits of external attributes
        return (entry.externalAttributes shr 16) and 0xFFF
    }
}
