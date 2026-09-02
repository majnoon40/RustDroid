package dev.rustdroid.ide.util

import java.io.File
import java.io.IOException

/** Filesystem helpers shared across layers. Pure JVM — unit-testable. */
object Fs {

    /** Atomic-ish write: write to sibling temp file, then rename over target. */
    @Throws(IOException::class)
    fun writeAtomic(file: File, content: String) {
        val tmp = File(file.parentFile, file.name + ".rdtmp")
        tmp.writeText(content)
        if (file.exists() && !file.delete()) {
            tmp.delete()
            throw IOException("cannot replace ${file.path}")
        }
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("rename failed for ${file.path}")
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

    /** chmod-style: apply execute bit set when [mode] owner-exec bit is set. */
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

    /** Safe entry-name resolution: rejects absolute paths and '..' traversal. */
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
}
