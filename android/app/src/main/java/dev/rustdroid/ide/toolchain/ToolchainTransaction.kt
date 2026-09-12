package dev.rustdroid.ide.toolchain

import dev.rustdroid.ide.util.Fs
import java.io.File
import java.io.IOException

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
 *
 * CRASH-SAFE MARKER INVARIANT (enforced by [rollbackFailedInstall] and
 * [runRecovery], the ONLY paths allowed to clear the marker on a
 * non-READY outcome): `install-pending.txt` may only be deleted after
 * the transaction has reached a KNOWN SAFE FINAL STATE. A rollback or
 * recovery that fails (e.g. a transient filesystem error) must leave
 * the marker in place — otherwise the next startup sees no pending
 * transaction, assumes everything is fine, and an unresolved half-
 * installed state is silently promoted to "clean". The marker is the
 * ONLY durable record that recovery work is still owed; deleting it
 * deletes the app's ability to retry.
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

    /**
     * Rollback for a FAILED verification/install with the crash-safe
     * marker invariant: the pending marker is cleared ONLY when the
     * previous known-good install was actually restored — or when there
     * was never anything to restore (fresh install: an unverified prefix
     * with no rollback source is a final, unrecoverable-but-known state
     * that startup recovery would DISCARD anyway).
     *
     * Returns the rollback failure (if any) so the caller can attach it
     * to the ORIGINAL install/verification exception — both must stay
     * visible. When a failure is returned the marker is DELIBERATELY
     * kept on disk: startup recovery must be able to retry the restore
     * on the next launch. Never convert an unresolved state into a
     * clean one by clearing the marker here.
     */
    fun rollbackFailedInstall(
        marker: File,
        prefix: File,
        aside: File,
        log: (String) -> Unit = {},
    ): IOException? {
        if (!aside.exists()) {
            // nothing retained to restore — a terminal (failed) state;
            // clearing the marker matches what startup recovery's
            // DISCARD action would conclude
            clearOrWarn(marker, log)
            return null
        }
        return try {
            ToolchainSwap.rollback(prefix, aside)
            log("previous toolchain restored from ${aside.path}")
            clearOrWarn(marker, log)
            null
        } catch (rb: IOException) {
            // DO NOT clear install-pending.txt: the restore did NOT
            // complete, so the transaction is unresolved — the marker
            // stays so the next startup retries recovery
            log("FAILED to restore the previous toolchain: ${rb.message}")
            log("keeping ${marker.name} — startup recovery will retry")
            rb
        }
    }

    /**
     * Executes startup recovery for an interrupted install, returning
     * true ONLY when the transaction reached a known safe final state
     * (in which case the pending marker is also cleared). False means
     * recovery could not complete — the marker REMAINS on disk and the
     * next startup must retry. Pure JVM (all real filesystem work),
     * unit-testable without Android.
     */
    fun runRecovery(
        marker: File,
        prefix: File,
        staging: File,
        aside: File,
        readyMarker: File,
        isInstalled: () -> Boolean,
        log: (String) -> Unit = {},
    ): Boolean {
        val pending = read(marker) ?: return true // nothing owed
        val action = recoveryAction(
            pending = pending,
            prefixInstalled = isInstalled(),
            readyMarkerPresent = readyMarker.isFile,
            asideExists = aside.exists(),
        )
        return when (action) {
            Action.NONE -> true
            Action.KEEP_VERIFIED -> {
                // crashed after verification passed but before
                // commit/clear: finish the job. A failed aside delete is
                // disk-garbage, not an unsafe state (the next swap
                // pre-cleans it) — the verified install IS the safe
                // final state, so the marker is cleared either way.
                //
                // External bug report #7 (partially already fixed by an
                // earlier pass — RESTORE_ASIDE/DISCARD below already clean
                // staging; this branch was the one remaining gap): a hard
                // crash (not a normal exception path) between the swap's
                // own `finally { Fs.deleteRecursively(staging) }` and that
                // block actually running can leave staging's ~500 MB on
                // disk indefinitely, since this recovery branch never
                // touched it. Note: the report ALSO suggested deleting
                // `prefix` in the DISCARD branch below when no ready
                // marker is present — that is NOT applied, because
                // GateScreen's "Verify files already on this device"
                // rescue path deliberately depends on exactly those files
                // surviving so re-verification can promote them to Ready
                // without a re-download; deleting them there would
                // silently remove a real, intentional feature.
                Fs.deleteRecursively(staging)
                if (!ToolchainSwap.commit(aside)) {
                    log("recovery: could not delete the old toolchain at ${aside.path} (retried next install)")
                }
                log("recovered interrupted install (stage=${pending.stage}): verified toolchain kept")
                clearOrWarn(marker, log)
                true
            }
            Action.RESTORE_ASIDE -> {
                try {
                    ToolchainSwap.rollback(prefix, aside)
                    Fs.deleteRecursively(staging)
                    log("recovered interrupted install (stage=${pending.stage}): previous toolchain restored")
                    clearOrWarn(marker, log)
                    true
                } catch (rb: IOException) {
                    // recovery INCOMPLETE: the marker is the durable
                    // record that this work is still owed — keep it so
                    // the NEXT startup retries the restore
                    log("recovery FAILED to restore the previous toolchain: ${rb.message}")
                    log("keeping ${marker.name} — the next startup will retry recovery")
                    false
                }
            }
            Action.DISCARD -> {
                // nothing usable anywhere: staging garbage is best-effort
                // cleanup (the next install pre-cleans it) — with no
                // aside there is nothing to restore, so this IS a final
                // (NotInstalled) state
                Fs.deleteRecursively(staging)
                log("recovered interrupted install (stage=${pending.stage}): nothing usable — cleaned up")
                clearOrWarn(marker, log)
                true
            }
        }
    }

    private fun clearOrWarn(marker: File, log: (String) -> Unit) {
        if (!clear(marker)) {
            log("WARN: could not clear ${marker.path} — startup recovery will re-run")
        }
    }

    private fun write(marker: File, dist: String, stage: String) {
        marker.parentFile?.mkdirs()
        Fs.writeAtomic(
            marker,
            "dist=$dist\nstarted=${System.currentTimeMillis()}\nstage=$stage\n",
        )
    }
}
