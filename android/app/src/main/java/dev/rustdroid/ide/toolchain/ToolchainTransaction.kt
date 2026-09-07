package dev.rustdroid.ide.toolchain

import dev.rustdroid.ide.util.Fs
import java.io.File

/**
 * EXTERNAL install-transaction marker — the durable record that an
 * install was in flight, living OUTSIDE the toolchain prefix (which is
 * being swapped underneath us).
 *
 * The install is a small transaction:
 *
 *   PREPARE  extract to staging (old install untouched, no marker)
 *   PENDING  [begin] — marker written BEFORE any destructive step
 *   SWAP     prefix -> aside, staging -> prefix
 *   VERIFY   full health check; ready marker written only on success
 *   READY    [commit]: aside deleted; [clear]: marker deleted
 *
 * Crash boundaries covered by startup recovery ([recoveryAction]):
 *  - crash after PENDING, before SWAP: prefix still the old (ready)
 *    install, staging garbage -> KEEP_VERIFIED (commit no aside, clear)
 *  - crash mid-SWAP (after prefix -> aside): prefix missing, aside = old
 *    -> RESTORE_ASIDE (rollback)
 *  - crash after SWAP, before VERIFY concluded: prefix = new-unverified,
 *    aside = old known-good -> RESTORE_ASIDE
 *  - crash after VERIFY passed (ready marker exists), before READY/CLEAR:
 *    prefix = new-VERIFIED (ready marker is the proof) -> KEEP_VERIFIED
 *
 * The marker is written atomically (temp file + rename, fsync'd), so a
 * crash mid-write leaves either the old or the new marker — never a
 * torn one. Pure JVM — unit-tested in isolation.
 */
internal object ToolchainTransaction {

    data class PendingInstall(
        /** Which distribution was being installed (e.g. the release tag). */
        val dist: String,
        /** Wall-clock ms when the transaction began (diagnostics). */
        val startedMs: Long,
        /** Last recorded phase: pending | swap | verify. */
        val stage: String,
    )

    /** What startup recovery should do for a given on-disk state. */
    enum class Action { NONE, KEEP_VERIFIED, RESTORE_ASIDE, DISCARD }

    fun markerFile(filesDir: File): File = File(filesDir, "install-pending.txt")

    fun begin(marker: File, dist: String) {
        write(marker, dist, "pending")
    }

    /** Records the current phase (crash forensics + recovery hints). */
    fun advance(marker: File, stage: String) {
        val current = read(marker) ?: return
        write(marker, current.dist, stage)
    }

    /** Removes the marker; false when it could not be deleted. */
    fun clear(marker: File): Boolean = !marker.exists() || marker.delete()

    fun read(marker: File): PendingInstall? {
        if (!marker.isFile) return null
        val lines = runCatching { marker.readLines() }.getOrNull() ?: return null
        fun value(key: String) =
            lines.firstOrNull { it.startsWith("$key=") }?.removePrefix("$key=")
        val dist = value("dist") ?: return null
        if (dist.isEmpty()) return null
        return PendingInstall(
            dist = dist,
            startedMs = value("started")?.toLongOrNull() ?: 0L,
            stage = value("stage") ?: "pending",
        )
    }

    /**
     * Pure decision table for startup recovery — testable without any
     * filesystem effects.
     */
    fun recoveryAction(
        pending: PendingInstall?,
        prefixInstalled: Boolean,
        readyMarkerPresent: Boolean,
        asideExists: Boolean,
    ): Action = when {
        pending == null -> Action.NONE
        // verified (ready marker written before commit/clear): the new
        // install is good — finish the transaction
        prefixInstalled && readyMarkerPresent -> Action.KEEP_VERIFIED
        // an aside exists: the old install is the known-good one
        asideExists -> Action.RESTORE_ASIDE
        // no aside, no usable prefix: nothing to keep — clean up
        else -> Action.DISCARD
    }

    private fun write(marker: File, dist: String, stage: String) {
        marker.parentFile?.mkdirs()
        Fs.writeAtomic(
            marker,
            "dist=$dist\nstarted=${System.currentTimeMillis()}\nstage=$stage\n",
        )
    }
}
