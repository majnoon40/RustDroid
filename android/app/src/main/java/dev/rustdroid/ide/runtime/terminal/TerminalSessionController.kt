package dev.rustdroid.ide.runtime.terminal

import dev.rustdroid.ide.runtime.ProcTree
import java.io.File

/**
 * Session-aware teardown for a terminal session (plan §5.3, v2 sequence
 * post-review: close-master → grace → freeze → kill, never freeze-then-hangup).
 *
 * The controller is a pure-Kotlin, /proc-driven object with injected
 * lambdas — unit-tested with a Recorder exactly like `ProcTreeTest`
 * (ordering assertions: the reader is stopped and joined BEFORE the
 * master close; no SIGSTOP precedes the close; the grace delay is
 * observed; SIGCONT fires after the sweep budget).
 *
 * WHO OWNS WHAT:
 *  - The SHELL is the session AND process-group leader (the vendored
 *    child path calls `setsid()` before exec), so `sid == pgid ==
 *    shellPid` and the shell's process group can only ever contain
 *    members of this session (a pgrp cannot span sessions).
 *  - [ptsDevice] is the st_rdev of the session's pts slave, computed in
 *    the parent at PTY creation (JNI createSubprocess out-param, plan
 *    §5.2) — NOT read from /proc after the fact (racy: the shell's
 *    tty_nr is 0 until it opens its slave).
 *  - [stopReader]/[joinReader] own the output-reader lifecycle: the
 *    reader thread polls {master fd, wakeup pipe}; teardown writes the
 *    wakeup pipe, the reader exits, and the controller JOINS it with a
 *    bounded timeout BEFORE any master-fd close (review P1-4: closing
 *    the master under a blocked read is the classic use-after-close —
 *    the fd number becomes reusable and an unrelated socket/file can
 *    steal it).
 *  - [closeMaster] performs the close on the session's I/O-owner thread
 *    (the lambda encapsulates that discipline). Closing the master is
 *    the GRACEFUL phase: the kernel itself delivers SIGHUP to the
 *    terminal's foreground process group — correct semantics, no signal
 *    the shell must be running to forward (review P1-3).
 *
 * EIO on the master-fd read after the child side is gone is normal
 * session end, never an error (handled by the session's reader; noted
 * here because it is part of the same contract).
 *
 * GUARANTEE, scoped honestly (plan §5.4): everything still ATTACHED to
 * the terminal dies on teardown; a deliberately detached process
 * (nohup/setsid + tty released) SURVIVES BY DESIGN — that is what those
 * tools are for, and a terminal that killed them would be wrong Unix.
 * Beyond the guarantee, discovery is best-effort through the union of
 * session members, ppid-descendants, and tty_nr matches.
 *
 * Every injected [signal]/[killpg]/[closeMaster] call is wrapped in
 * `runCatching` (review condition 8): an ESRCH/EPERM race between
 * identity check and signal — or a throwing lambda — must never crash a
 * teardown. We SIGSTOPped members ourselves, so after the sweep budget
 * is exhausted any freeze SURVIVOR is SIGCONTed: never leave a process
 * we stopped stopped forever (SIGKILL wins over SIGCONT for dying
 * members; a survivor that ignored SIGKILL is resumed and left to the
 * OS).
 */
class TerminalSessionController(
    private val procRoot: File,
    /** The setsid'd shell: sid == pgid == this pid. */
    private val shellPid: Long,
    /** st_rdev of the pts slave (from JNI createSubprocess, plan §5.2). */
    private val ptsDevice: Long,
    /** Identity-checked per-PID signaling. */
    private val signal: (pid: Long, sig: Int) -> Unit,
    /** Process-group signaling (JNI sendSignalToProcessGroup / killpg(2)). */
    private val killpg: (pgid: Long, sig: Int) -> Unit,
    /** Closes the master fd on the session's I/O-owner thread. */
    private val closeMaster: () -> Unit,
    /** Signals the output reader to stop (wakeup pipe write). */
    private val stopReader: () -> Unit,
    /** Joins the reader with a bounded timeout; true if it exited. */
    private val joinReader: (timeoutMs: Long) -> Boolean,
    /** Bounded grace between master close and the freeze (200–500 ms range). */
    private val graceMs: Long = 300L,
    private val maxSweeps: Int = 3,
    private val sweepDelayMs: Long = 50L,
    private val readerJoinTimeoutMs: Long = 2_000L,
    private val sleeper: (ms: Long) -> Unit = { Thread.sleep(it) },
) {
    /** Full teardown per plan §5.3 steps 1–8. Idempotent by contract of the injected lambdas. */
    fun teardown() {
        // 1. Quiesce input: the CALLER stops writing to the master before
        //    calling teardown (the session's input path is runCatching-
        //    guarded; a post-close write surfaces EBADF and is ignored).
        //
        // 2. Stop the output reader BEFORE any close (P1-4): the reader
        //    polls {master, wakeup pipe}; the wakeup write makes it exit
        //    its loop; the join is bounded. The master fd is only ever
        //    closed after the reader is joined.
        runCatching { stopReader() }
        runCatching { joinReader(readerJoinTimeoutMs) }

        // 3. Close the master fd: the kernel delivers SIGHUP to the
        //    terminal's foreground process group — the graceful phase
        //    (P1-3). No manual hangup signal exists in this sequence.
        runCatching { closeMaster() }

        // 4. Bounded grace: an interactive shell may run its EXIT/trap
        //    cleanup and forward the hangup to its jobs while it still
        //    can. (A STOPPED process never runs handlers — which is why
        //    the freeze must come after this, not before.)
        sleeper(graceMs)

        // 5. Snapshot via union discovery — the identity map is cached
        //    for the whole teardown (sweeps re-validate only captured
        //    PIDs, never a full /proc re-scan).
        val captured = ArrayList<ProcTree.ProcessId>()
        val seen = HashSet<Long>()
        for (id in ProcTree.unionMembers(procRoot, shellPid, ptsDevice)) {
            if (seen.add(id.pid)) captured.add(id)
        }

        // 6. Freeze: SIGSTOP every captured member that still matches its
        //    captured identity — nothing new spawns, nothing escapes
        //    between discovery and the kill.
        for (id in captured) {
            if (ProcTree.stillMatches(procRoot, id)) runCatching { signal(id.pid, ProcTree.SIGSTOP) }
        }

        // 6b. Re-discover late members, but ONLY while the shell PID is
        //    still provably the ORIGINAL process — a reaped-and-reused
        //    shell PID belongs to someone else's tree now (the same
        //    discipline as ProcTree.terminateTree step 5).
        val shellId = ProcTree.identity(procRoot, shellPid)
        if (shellId != null && ProcTree.stillMatches(procRoot, shellId)) {
            for (id in ProcTree.unionMembers(procRoot, shellPid, ptsDevice)) {
                if (seen.add(id.pid)) captured.add(id)
            }
        }

        // 7. Kill: the shell's own process group first (it can only ever
        //    contain members of this session — a pgrp cannot span
        //    sessions), then bounded identity-checked, zombie-excluded
        //    SIGKILL sweeps with early return (the first sweep IS the
        //    kill pass — same shape as ProcTree.terminateTree step 6).
        runCatching { killpg(shellPid, ProcTree.SIGKILL) }
        for (sweep in 0 until maxSweeps) {
            val remaining = captured.filter {
                ProcTree.stillMatches(procRoot, it) && !ProcTree.isZombie(procRoot, it.pid)
            }
            if (remaining.isEmpty()) return
            for (id in remaining) runCatching { signal(id.pid, ProcTree.SIGKILL) }
            if (sweep < maxSweeps - 1) sleeper(sweepDelayMs)
        }

        // 8. Budget exhausted: SIGCONT every freeze survivor still alive —
        //    we stopped it, so we must never leave it stopped forever.
        //    (SIGKILL wins over SIGCONT for dying members; a survivor that
        //    ignored SIGKILL is resumed and left to the OS.)
        for (id in captured) {
            if (ProcTree.stillMatches(procRoot, id) && !ProcTree.isZombie(procRoot, id.pid)) {
                runCatching { signal(id.pid, SIGCONT) }
            }
        }
    }

    companion object {
        const val SIGCONT = 18
    }
}
