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
import java.nio.file.Paths

/**
 * Installs the app bundle zip into a prefix directory. The validated
 * Phase-1 recipe:
 *
 *   tarballs (rustc/cargo/rust-std) -> strip <tarball>/<component>/ dirs
 *                                       into the prefix (modes preserved)
 *   libc++_shared.so                 -> $PREFIX/lib/
 *   rustdroid-link contents          -> $PREFIX/lib/rustdroid-link/
 *   rustdroid-link/bin/{cc,clang,gcc}-> $PREFIX/bin/ (chmod 755)
 *
 * The destination is parameterized ([destRoot], default [ToolchainPaths.prefix])
 * so ToolchainManager can extract into a staging dir and swap it in — a
 * failed extraction must never leave a half-replaced prefix.
 *
 * Security envelope (the bundle zip is a trust boundary — it can arrive
 * via "Import zip" instead of the checksum-pinned download):
 *  - every entry path goes through [Fs.resolveChild] (zip-slip: no `..`,
 *    no absolute) AND [Fs.requireInside] (canonical containment — catches
 *    writes routed through an in-archive symlink out of the root);
 *  - tar symlink targets are validated to stay inside the prefix BEFORE
 *    the link is created — no absolute or `..` escapes;
 *  - hardlink/symlink entries are resolved in a SECOND pass after all
 *    regular files exist, and an unresolvable target is a hard failure —
 *    never a silently missing file, never an empty-file placeholder.
 *
 * Single streaming pass over the archive data (links deferred), fails loud
 * on layout drift. Pure JVM — unit-tested.
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

    /** A link entry deferred to the post-pass: (dest, linkName, mode, hard?). */
    private data class PendingLink(
        val dest: File,
        val linkName: String,
        val mode: Int,
        val isHard: Boolean,
    )

    /** Progress callback: (filesWritten, estimatedTotal or null). */
    fun install(
        zip: File,
        destRoot: File = paths.prefix,
        onProgress: (Int, Int?) -> Unit = { _, _ -> },
    ): BundleManifestData {
        val prefix = destRoot
        prefix.mkdirs()

        var manifest: dev.rustdroid.ide.model.BundleManifest? = null
        var libcxxDone = false
        var busyboxDone = false
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

                    // Manifest v2 (plan §7.3): the BusyBox executable —
                    // a STATIC aarch64 ELF, installed $PREFIX/bin/busybox
                    // with exec mode. The checksum in the manifest is the
                    // same per-entry discipline as the tarballs: verified
                    // by the verifier at Ready-gate time.
                    name == "busybox" -> {
                        val dest = Fs.resolveChild(prefix, "bin/busybox")
                        Fs.requireInside(prefix, dest)
                        dest.parentFile?.mkdirs()
                        zis.copyToFile(dest)
                        Fs.applyPosixMode(dest, 0b111_101_101)
                        busyboxDone = true
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
                            Fs.requireInside(prefix, dest)
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

        // ---- manifest v2: busybox + guarded symlinks (plan §7.3, P2-8) ----
        // Checked FIRST: a missing busybox entry is a bundle-generation
        // contract violation (the pinned tag carries -bb1.36.1), more
        // fundamental than payload completeness — the message must name
        // busybox even when libc++/kit are also absent.
        val busyboxRequired = ToolchainDistro.BUSYBOX_BUNDLED
        if (busyboxRequired) {
            if (m.busybox == null) {
                error("bundle manifest v2 missing the busybox entry (expected tag ${ToolchainDistro.RELEASE_TAG})")
            }
            if (!busyboxDone) error("bundle missing busybox executable (manifest declares it)")
            if (m.busybox.sha256.isNotEmpty()) {
                val actual = hashFile(File(prefix, "bin/busybox"))
                if (actual != m.busybox.sha256) {
                    error("busybox sha256 mismatch: manifest ${m.busybox.sha256}, extracted $actual")
                }
            }
            installManifestSymlinks(prefix, m.symlinks)
        } else if (busyboxDone || m.symlinks.isNotEmpty()) {
            // A v1-tag bundle that carries v2 payloads — reject: never
            // install bytes the pinned manifest does not describe.
            error("bundle carries busybox/symlinks but the pinned tag is pre-busybox — layout drift")
        }

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
     * Manifest-v2 symlink pass (plan §7.3 / review P2-8): every link is
     * resolved through [Fs.resolveChild] (lexical: no absolute, no `..`)
     * AND [Fs.requireInside] (canonical containment through existing
     * symlinked ancestors — the same guard the extractor applies to tar
     * entries) BEFORE creation. A traversal attempt fails LOUD and is
     * install-blocking — never warn-and-continue. (Zip entries can't
     * carry symlinks portably; that is why the manifest owns them.)
     */
    private fun installManifestSymlinks(prefix: File, symlinks: List<dev.rustdroid.ide.model.BundleManifest.SymlinkSpec>) {
        val prefixPath = prefix.canonicalFile.toPath()
        for (link in symlinks) {
            if (link.name.isBlank() || link.target.isBlank()) {
                error("manifest symlink entry with blank name/target — corrupt manifest")
            }
            val dest = Fs.resolveChild(prefix, link.name)
            Fs.requireInside(prefix, dest)
            // The target must also stay inside the prefix after lexical
            // resolution (relative to the link's own directory).
            val targetPath = if (link.target.startsWith("/")) {
                Paths.get(link.target).normalize()
            } else {
                dest.parentFile.toPath().resolve(link.target).normalize()
            }
            if (!targetPath.startsWith(prefixPath)) {
                error(
                    "manifest symlink '${link.name}' -> '${link.target}' escapes the install " +
                        "prefix — corrupt bundle, install blocked (plan §6.5)",
                )
            }
            try {
                Files.deleteIfExists(dest.toPath())
                Files.createSymbolicLink(dest.toPath(), Paths.get(link.target))
            } catch (e: java.io.IOException) {
                // NEVER an empty-file placeholder: a broken symlink fails
                // loud at install time (the same discipline as tar links).
                throw IOException(
                    "cannot create manifest symlink '${link.name}' -> '${link.target}': ${e.message}",
                    e,
                )
            }
        }
    }

    private fun hashFile(f: File): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(f.readBytes())
        .joinToString("") { "%02x".format(it) }

    /**
     * Streams one dist tarball: every payload entry is
     * `<topdir>/<component>/rest...`. We strip the two leading segments
     * (install.sh & friends at depth 1 are dropped automatically) and
     * preserve tar mode bits.
     *
     * Link entries (hard + symbolic) are DEFERRED to a second pass over
     * [links]: tar makes no ordering guarantee that a hardlink's target
     * has already been extracted, and resolving early silently produced
     * empty/missing files in the past. Deferred resolution is exact and
     * hard-fails on unresolvable targets.
     */
    private fun extractTarball(input: InputStream, prefix: File, label: String) {
        val tar = TarArchiveInputStream(
            XZCompressorInputStream(BufferedInputStream(input, 1 shl 16))
        )
        var top: String? = null
        var entries = 0
        val links = ArrayList<PendingLink>()
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
                Fs.requireInside(prefix, dest)
                dest.mkdirs()
            } else if (e.isLink) {
                dest.parentFile?.mkdirs()
                links += PendingLink(dest, e.linkName, e.mode, isHard = true)
            } else if (e.isSymbolicLink) {
                dest.parentFile?.mkdirs()
                links += PendingLink(dest, e.linkName, e.mode, isHard = false)
            } else {
                Fs.requireInside(prefix, dest)
                dest.parentFile?.mkdirs()
                tar.copyToFile(dest)
                Fs.applyPosixMode(dest, e.mode)
            }
            entries++
        }
        for (link in links) {
            resolveLink(prefix, link, label)
        }
        if (entries == 0) throw IOException("$label: no payload entries found")
    }

    /**
     * Resolves one deferred link entry. Hardlinks copy the target's bytes
     * (the app sandbox has no cross-file hardlink requirement and copies
     * sidestep ordering entirely); symlinks are created after their target
     * is proven to stay inside the prefix.
     */
    private fun resolveLink(prefix: File, link: PendingLink, label: String) {
        if (link.isHard) {
            val target = hardlinkTarget(prefix, link.linkName)
                ?: throw IOException(
                    "$label: hardlink '${link.dest.relativeTo(prefix).path}' -> " +
                        "'${link.linkName}': target not extracted — corrupt archive",
                )
            if (!target.isFile) {
                throw IOException(
                    "$label: hardlink target '${link.linkName}' is not a regular file",
                )
            }
            target.copyTo(link.dest, overwrite = true)
            Fs.applyPosixMode(link.dest, link.mode)
            return
        }
        // symbolic link: the target must resolve inside the prefix. Every
        // other symlink in the tree passes the same check, so chains of
        // in-prefix links cannot combine into an escape.
        val targetPath = if (link.linkName.startsWith("/")) {
            Paths.get(link.linkName).normalize()
        } else {
            link.dest.parentFile.toPath().resolve(link.linkName).normalize()
        }
        val prefixPath = prefix.canonicalFile.toPath()
        if (!targetPath.startsWith(prefixPath)) {
            throw IOException(
                "$label: symlink '${link.dest.relativeTo(prefix).path}' -> " +
                    "'${link.linkName}' escapes the install prefix — corrupt archive",
            )
        }
        try {
            Files.deleteIfExists(link.dest.toPath())
            Files.createSymbolicLink(link.dest.toPath(), Paths.get(link.linkName))
        } catch (e: java.io.IOException) {
            // NEVER fall back to an empty file: a broken symlink fails loud
            // at install time instead of as a baffling link error later.
            throw IOException(
                "$label: cannot create symlink '${link.dest.relativeTo(prefix).path}': ${e.message}",
                e,
            )
        }
    }

    /**
     * A tar hardlink's linkName is the TARGET ENTRY's full archive path
     * (`<top>/<component>/rest`); some writers emit the already-stripped
     * form instead. Try both interpretations, plus the leading-slash
     * variant; null when none names an existing file.
     */
    private fun hardlinkTarget(prefix: File, linkName: String): File? {
        val trimmed = linkName.trimStart('/')
        if (trimmed.isEmpty()) return null
        val candidates = ArrayList<File>(2)
        val segments = trimmed.split('/')
        if (segments.size > 2) {
            candidates += Fs.resolveChild(prefix, segments.drop(2).joinToString("/"))
        }
        candidates += Fs.resolveChild(prefix, trimmed)
        return candidates.firstOrNull { it.isFile }
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
