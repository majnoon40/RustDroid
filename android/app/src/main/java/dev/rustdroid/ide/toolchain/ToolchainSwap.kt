package dev.rustdroid.ide.toolchain

import dev.rustdroid.ide.util.Fs
import java.io.File
import java.io.IOException

/**
 * Replaces a toolchain prefix with a freshly extracted staging directory
 * without ever leaving the system without one:
 *
 *   prefix -> aside   (old install kept on disk, not deleted)
 *   staging -> prefix (the new install takes its place)
 *   aside deleted     (only after the new one is in place)
 *
 * If the final move fails, the old install is restored from [aside]. All
 * three directories must live on the same filesystem (they do: all under
 * filesDir) so the moves are renames, not copies. Pure JVM — tested in
 * isolation from ToolchainManager.
 */
internal object ToolchainSwap {

    fun swap(prefix: File, staging: File, aside: File) {
        if (!staging.isDirectory) {
            throw IOException("staging directory ${staging.path} is missing")
        }
        Fs.deleteRecursively(aside)
        val hadOld = prefix.exists()
        if (hadOld && !prefix.renameTo(aside)) {
            throw IOException("cannot move the old toolchain aside to ${aside.path}")
        }
        try {
            if (!staging.renameTo(prefix)) {
                throw IOException("cannot move the new toolchain into ${prefix.path}")
            }
        } catch (e: Exception) {
            // restore the old install — never leave the prefix missing
            if (hadOld && !prefix.exists()) {
                aside.renameTo(prefix)
            }
            throw e
        }
        Fs.deleteRecursively(aside)
    }
}
