package dev.rustdroid.ide.runtime

import java.io.File

/**
 * Maps a process tree by scanning `<procRoot>/<pid>/status` for "PPid:"
lines (Linux /proc layout — Android included). Pure file work, so the JVM
tests build synthetic /proc trees.
 *
 * Why this exists: `Process.destroy()` signals only the DIRECT child
 * (cargo). Java's ProcessBuilder does not create a process group, so
 * cancelling a build left cargo's rustc/ld.lld/build-script children
 * running as orphans — burning CPU and battery until they finished on
 * their own. [CargoRunner.kill] walks this tree and SIGKILLs every
 * descendant.
 */
object ProcTree {

    /**
     * All descendant pids of [rootPid], breadth-first (children before
     * grandchildren). Empty when [procRoot] is not a directory (non-Linux
     * JVMs, or /proc hidden — the kill then degrades to the direct child).
     */
    fun descendantPids(procRoot: File, rootPid: Long): List<Long> {
        val dirs = procRoot.listFiles() ?: return emptyList()
        val children = HashMap<Long, MutableList<Long>>()
        for (d in dirs) {
            val pid = d.name.toLongOrNull() ?: continue
            val ppid = ppidOf(File(d, "status")) ?: continue
            // ppid 0/1 = kernel or init: no real parent in our tree, and
            // never a descendant of a user process
            if (ppid <= 1) continue
            children.getOrPut(ppid) { mutableListOf() }.add(pid)
        }
        val out = ArrayList<Long>()
        val queue = ArrayDeque<Long>()
        queue.add(rootPid)
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            val kids = children[cur] ?: continue
            for (k in kids) {
                out.add(k)
                queue.add(k)
            }
        }
        return out
    }

    /** "PPid:\t<n>" from a /proc status file, or null. */
    private fun ppidOf(status: File): Long? {
        if (!status.isFile) return null
        return runCatching {
            status.useLines { lines ->
                lines.firstOrNull { it.startsWith("PPid:") }
                    ?.substringAfter("PPid:")
                    ?.trim()
                    ?.toLongOrNull()
            }
        }.getOrNull()
    }
}
