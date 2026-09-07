package dev.rustdroid.ide.toolchain

import dev.rustdroid.ide.util.Fs
import java.io.File
import java.io.IOException

/**
 * Replaces a toolchain prefix with a freshly extracted staging directory,
 * as ONE STEP of a transaction owned by [ToolchainManager]:
 *
 *   PREPARE (extract to staging, old install untouched)
 *   -> PENDING (transaction marker written, see [ToolchainTransaction])
 *   -> SWAP (this class: prefix -> aside, staging -> prefix; the OLD
 *      install is KEPT in aside until verification passes)
 *   -> VERIFY (ToolchainVerifier; ready marker only written on success)
 *   -> READY ([commit] deletes aside, marker cleared)
 *
 * Rollback ([rollback]) restores the previous known-good install from
 * aside when verification fails. All directories live on the same
 * filesystem (all under filesDir) so every move is a rename, not a copy.
 *
 * Every critical rename/delete is checked. When a failed swap cannot be
 * rolled back, the rollback failure is attached to the original exception
 * via [Throwable.addSuppressed] so BOTH are visible to the caller — a
 * silently ignored restore failure is how a system ends up with no
 * toolchain and no explanation.
 *
 * Pure JVM — tested in isolation from ToolchainManager.
 */
internal object ToolchainSwap {

    /**
     * Atomically-ish moves the new install into place: prefix -> aside,
     * staging -> prefix. The old install is RETAINED in [aside] until
     * [commit] — a crash anywhere before READY is recoverable by
     * [rollback] or startup recovery.
     */
    fun swap(prefix: File, staging: File, aside: File) {
        if (!staging.isDirectory) {
            throw IOException("staging directory ${staging.path} is missing")
        }
        // pre-clean: a stale aside blocks the rollback slot
        if (aside.exists() && !Fs.deleteRecursively(aside)) {
            throw IOException("cannot clear stale ${aside.path} for the swap")
        }
        val hadOld = prefix.exists()
        if (hadOld && !prefix.renameTo(aside)) {
            throw IOException("cannot move the old toolchain aside to ${aside.path}")
        }
        try {
            if (!staging.renameTo(prefix)) {
                throw IOException("cannot move the new toolchain into ${prefix.path}")
            }
        } catch (e: Exception) {
            // restore the old install — never leave the prefix missing;
            // a FAILED restore must not be swallowed
            if (hadOld && !prefix.exists()) {
                if (!aside.renameTo(prefix)) {
                    e.addSuppressed(
                        IOException("ROLLBACK FAILED: cannot restore the old toolchain from ${aside.path} to ${prefix.path}")
                    )
                }
            }
            throw e
        }
    }

    /**
     * Final, irrevocable step after verification passed: delete the old
     * install. Failure is NOT fatal (the new install is verified and in
     * place) but IS reported so the caller can log it; the next swap
     * retries the delete via its pre-clean.
     */
    fun commit(aside: File): Boolean = !aside.exists() || Fs.deleteRecursively(aside)

    /**
     * Restores the previous known-good install from [aside] after a
     * failed verification. Throws when the restore is impossible —
     * callers must surface that (the new prefix is unusable AND the old
     * one could not be brought back).
     */
    fun rollback(prefix: File, aside: File) {
        if (!aside.exists()) return // nothing to restore
        if (prefix.exists() && !Fs.deleteRecursively(prefix)) {
            throw IOException(
                "rollback: cannot remove the unverified install at ${prefix.path} " +
                    "to make room for the previous one"
            )
        }
        if (!aside.renameTo(prefix)) {
            throw IOException(
                "rollback: cannot restore the previous toolchain from ${aside.path} to ${prefix.path}"
            )
        }
    }
}
