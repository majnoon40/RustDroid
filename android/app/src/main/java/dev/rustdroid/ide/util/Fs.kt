package dev.rustdroid.ide.util

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Filesystem helpers shared across layers. Pure JVM — unit-testable. */
object Fs {

    /**
     * Atomic-ish write: write to sibling temp file, fsync, then atomically
     * rename over the target.
     *
     * The rename uses NIO `ATOMIC_MOVE | REPLACE_EXISTING`, which on POSIX
     * (Android included, same filesystem) is a single `rename(2)` — the
     * replace has no window in which the target is missing, so a crash
     * mid-write can never lose the previous content (user source files!).
     * The historical delete-then-rename had exactly that window. The temp
     * file is fsync'd BEFORE the rename so the renamed content is on stable
     * storage even across power loss — without it the rename can land
     * before the data blocks do. If the filesystem refuses atomic moves,
     * fall back to a plain move, then to the legacy two-step as a last
     * resort.
     */
    @Throws(IOException::class)
    fun writeAtomic(file: File, content: String) {
        val tmp = File(file.parentFile, file.name + ".rdtmp")
        try {
            tmp.writeText(content)
            try {
                java.nio.channels.FileChannel.open(
                    tmp.toPath(), java.nio.file.StandardOpenOption.WRITE
                ).use { it.force(true) }
            } catch (_: IOException) {
                // fsync unsupported on this filesystem: the rename still
                // guarantees no torn state, just weaker power-loss
                // durability — not worth failing the write over
            }
            try {
                Files.move(
                    tmp.toPath(), file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
                return
            } catch (_: AtomicMoveNotSupportedException) {
                // same-FS plain move still replaces without a missing window
                // on POSIX; try it before the legacy path
            }
            try {
                Files.move(
                    tmp.toPath(), file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
                return
            } catch (_: IOException) {
                // fall through to legacy two-step
            }
            if (file.exists() && !file.delete()) {
                throw IOException("cannot replace ${file.path}")
            }
            if (!tmp.renameTo(file)) {
                throw IOException("rename failed for ${file.path}")
            }
        } finally {
            // any path that did not consume the tmp file cleans it up
            if (tmp.exists()) tmp.delete()
        }
    }

    /** Recursively delete, tolerating partial failure; returns success. */
    fun deleteRecursively(file: File): Boolean {
        if (!file.exists()) return true
        if (file.isDirectory) {
            file.listFiles()?.forEach { deleteRecursively(it) }
        }
        return file.delete()
    }

    /** Directory size in bytes, 0 if absent. Follows symlinks shallowly. */
    fun sizeOf(file: File): Long {
        if (!file.exists()) return 0
        if (file.isFile) return file.length()
        var total = 0L
        file.listFiles()?.forEach { child ->
            total += if (child.isDirectory) sizeOf(child) else child.length()
        }
        return total
    }

    /**
     * chmod-style: apply execute bit set when [mode] owner-exec bit is set.
     *
     * Deliberate simplification: owner/group/other are not distinguished
     * (every bit is applied to "all users"), so a tar mode of 0640 lands as
     * effectively 0644. Harmless inside the app's single-UID sandbox — the
     * exec and read bits are what the toolchain actually needs. Do not use
     * where group/other fidelity matters.
     */
    fun applyPosixMode(file: File, mode: Int) {
        val exec = (mode and 0b001_000_000) != 0
        file.setReadable(mode and 0b100_000_000 != 0, false)
        file.setWritable(mode and 0b010_000_000 != 0, false)
        // keep exec readable/traversable by others when owner-exec (dirs need this)
        file.setExecutable(exec, false)
        if (exec) {
            file.setReadable(true, false)
        }
    }

    fun humanBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.1f MB".format(mb)
        return "%.2f GB".format(mb / 1024.0)
    }

    /** Safe entry-name resolution: rejects absolute paths and '..' traversal.
     *
     * LEXICAL check only — a symlink created inside the root can still route
     * a write outside it (see ArtifactExtractor's canonical containment
     * guard for the toolchain-import trust boundary; user projects are the
     * user's own trust domain). */
    @Throws(IOException::class)
    fun resolveChild(root: File, name: String): File {
        if (name.isEmpty() || name.startsWith("/") || name.startsWith("\\")) {
            throw IOException("illegal entry path: $name")
        }
        val parts = name.split('/', '\\')
        if (parts.any { it == ".." }) throw IOException("traversal in entry path: $name")
        var f = root
        for (p in parts) f = File(f, p)
        return f
    }

    /**
     * Canonical containment: [dest] (which may not exist yet) must resolve
     * inside [root] after following any symlinks in its EXISTING ancestors.
     * The guard that actually stops symlink-based escape during archive
     * extraction — [resolveChild] alone cannot see symlinks.
     */
    @Throws(IOException::class)
    fun requireInside(root: File, dest: File) {
        val rootPath = root.canonicalFile.toPath()
        val destPath = dest.canonicalFile.toPath()
        if (destPath != rootPath && !destPath.startsWith(rootPath)) {
            throw IOException("path escapes ${root.path}: ${dest.path}")
        }
    }
}
