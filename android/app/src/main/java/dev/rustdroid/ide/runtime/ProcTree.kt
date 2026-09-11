package dev.rustdroid.ide.runtime

import java.io.File

/**
 * Maps and terminates process trees by scanning `<procRoot>/<pid>` (Linux
 * /proc layout — Android included). Pure file work, so the JVM tests build
 * synthetic /proc trees.
 *
 * Why this exists: `Process.destroy()` signals only the DIRECT child
 * (cargo). Java's ProcessBuilder does not create a process group, so
 * cancelling a build left cargo's rustc/ld.lld/build-script children
 * running as orphans — burning CPU and battery until they finished on
 * their own. [CargoRunner.kill] walks this tree and SIGKILLs every
 * descendant.
 *
 * PID REUSE: a PID is not an identity — Linux wraps around and reassigns
 * them. A "snapshot descendants -> kill parent -> kill snapshot" sequence
 * can signal a process that merely OCCUPIES a dead child's PID. Every
 * process here is therefore tracked as [ProcessId]: PID + the process
 * start time from `/proc/<pid>/stat` field 22 (kernel jiffies at process
 * birth — unique for the lifetime of the machine). Destructive signals
 * are only sent after re-reading the stat and confirming the current
 * occupant still has the captured start time; a mismatch means the
 * original process is gone and the PID was reused, so it is skipped.
 *
 * SINGLE-READ DISCIPLINE (v0.2.1): [readStat] parses a whole stat line
 * ONCE into a [Stat]. Callers that need identity AND state (liveness,
 * zombie-ness) must use it rather than the split [stillMatches] +
 * [isZombie] pair — see [Stat] for why two reads is a TOCTOU hole.
 */
object ProcTree {

    const val SIGSTOP = 19
    const val SIGKILL = 9

    /** PID + start time (stat field 22) — identity of a concrete process. */
    data class ProcessId(val pid: Long, val startTime: Long?)

    /**
     * One parse of `/proc/<pid>/stat`: identity (start time) AND the
     * fields a teardown needs to decide, read ATOMICALLY from a single
     * `readText()`.
     *
     * Why this type exists (v0.2.1): a teardown that asks [stillMatches]
     * ("is this still my process?") and then [isZombie] ("is it dead?")
     * performs TWO reads of the same file. Between them the process can
     * exit and its PID be reused, so the first question is answered about
     * the OLD occupant and the second about the NEW one — the identity
     * discipline that exists to prevent PID-reuse signalling, defeated by
     * splitting the read. One read, one parse, one decision.
     *
     * Fields per proc(5); note the comm field (2) may contain spaces and
     * ')' so parsing resumes after the LAST ')'.
     */
    data class Stat(
        val pid: Long,
        /** field 3: one of R/S/D/Z/T/... ('Z' = zombie/dead-pending-reap). */
        val state: Char,
        /** field 4 */
        val ppid: Long,
        /** field 5 */
        val pgrp: Long,
        /** field 6 — session id (pgid/sid semantics, see [sessionMembers]). */
        val session: Long,
        /** field 7 — controlling terminal device number. */
        val ttyNr: Long,
        /** field 22 — start time in kernel jiffies; the PID-reuse guard. */
        val startTime: Long,
    )

    /**
     * All descendants of [rootPid], breadth-first (children before
     * grandchildren), each with its captured identity. Empty when
     * [procRoot] is not a directory (non-Linux JVMs, or /proc hidden —
     * the kill then degrades to the direct child). Entries whose identity
     * could not be read (process vanished mid-walk) are skipped: they
     * cannot be signaled safely. [rootPid] itself is never included,
     * even if malformed /proc data claims a self-parenting loop.
     */
    fun descendants(procRoot: File, rootPid: Long): List<ProcessId> {
        val dirs = procRoot.listFiles() ?: return emptyList()
        // pid -> identity (null startTime = stat unreadable)
        val identity = HashMap<Long, ProcessId>()
        val children = HashMap<Long, MutableList<Long>>()
        for (d in dirs) {
            val pid = d.name.toLongOrNull() ?: continue
            val ppid = ppidOf(File(d, "status")) ?: continue
            // ppid 0/1 = kernel or init: no real parent in our tree, and
            // never a descendant of a user process
            if (ppid <= 1) continue
            // malformed self-parenting entry would loop the BFS forever
            if (pid == ppid) continue
            // identity MUST be readable to be safely signalable later;
            // an unreadable stat means the process is gone (or /proc is
            // hidden) — drop it instead of keeping an unvalidatable PID
            val start = startTimeOf(File(d, "stat")) ?: continue
            identity[pid] = ProcessId(pid, start)
            children.getOrPut(ppid) { mutableListOf() }.add(pid)
        }
        // listFiles order is filesystem-dependent (readdir); sort so the
        // breadth-first result is deterministic
        children.values.forEach { it.sort() }
        val out = ArrayList<ProcessId>()
        val queue = ArrayDeque<Long>()
        queue.add(rootPid)
        val seen = HashSet<Long>()
        seen.add(rootPid) // the root is never its own descendant
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            val kids = children[cur] ?: continue
            for (k in kids) {
                if (!seen.add(k)) continue // cycles / duplicate edges
                val id = identity[k] ?: continue // vanished mid-walk
                out.add(id)
                queue.add(k)
            }
        }
        return out
    }

    /**
     * All descendant pids of [rootPid] (identity-free view for callers
     * that only need the tree shape).
     */
    fun descendantPids(procRoot: File, rootPid: Long): List<Long> =
        descendants(procRoot, rootPid).map { it.pid }

    /**
     * Identity of the process currently occupying [pid]: PID + start
     * time. Null when `/proc/<pid>/stat` cannot be read — the process is
     * gone (or /proc is not visible); either way there is nothing safe to
     * signal.
     */
    fun identity(procRoot: File, pid: Long): ProcessId? {
        val startTime = startTimeOf(File(procRoot, "$pid/stat")) ?: return null
        return ProcessId(pid, startTime)
    }

    /**
     * Read + parse `/proc/<pid>/stat` ONCE into a [Stat] (identity AND
     * state together). Null when the file is missing/unreadable/malformed.
     *
     * This is the ONLY stat read a teardown should use to decide whether
     * to signal: it makes "still my process?" and "still alive?" a single
     * observation instead of two that a PID reuse can slip between.
     */
    fun readStat(procRoot: File, pid: Long): Stat? {
        val f = File(procRoot, "$pid/stat")
        if (!f.isFile) return null
        return runCatching {
            val text = f.readText()
            val close = text.lastIndexOf(')')
            if (close < 0) return@runCatching null
            // tokens[0] is field 3 (state); field N -> index N - 3
            val t = text.substring(close + 1).trim().split(' ')
            Stat(
                pid = pid,
                state = t.getOrNull(0)?.firstOrNull() ?: return@runCatching null,
                ppid = t.getOrNull(1)?.toLongOrNull() ?: return@runCatching null,
                pgrp = t.getOrNull(2)?.toLongOrNull() ?: return@runCatching null,
                session = t.getOrNull(3)?.toLongOrNull() ?: return@runCatching null,
                ttyNr = t.getOrNull(4)?.toLongOrNull() ?: return@runCatching null,
                startTime = t.getOrNull(19)?.toLongOrNull() ?: return@runCatching null,
            )
        }.getOrNull()
    }

    /**
     * True when the process currently occupying [id.pid] is still the
     * captured process: /proc entry exists AND the start time matches.
     * A null [ProcessId.startTime] can never be revalidated, so it
     * returns false (conservative — never signal an unvalidatable PID).
     *
     * NOTE: this reads the stat file on its own. A caller that also needs
     * zombie state must NOT follow this with [isZombie] (two reads = a
     * PID-reuse window between them) — use [readStat] once instead.
     */
    fun stillMatches(procRoot: File, id: ProcessId): Boolean {
        val startTime = id.startTime ?: return false
        return startTimeOf(File(procRoot, "${id.pid}/stat")) == startTime
    }

    /**
     * `/proc/<pid>/stat` field 22 (starttime, kernel jiffies since boot):
     * stable per-process identity for PID-reuse defense. The comm field
     * (2) may contain spaces and ')' characters, so parsing starts after
     * the LAST ')'. Null when the file is missing/unreadable/malformed.
     */
    private fun startTimeOf(stat: File): Long? = longFieldOf(stat, STARTTIME_FIELD)

    /**
     * `/proc/<pid>/stat` field 6 (session id), same last-')' parsing
     * discipline. Null when the file is missing/unreadable/malformed.
     */
    private fun sessionOf(stat: File): Long? = longFieldOf(stat, SESSION_FIELD)

    /**
     * `/proc/<pid>/stat` field 7 (tty_nr — the controlling terminal's
     * device number). Null when the file is missing/unreadable/malformed.
     */
    private fun ttyNrOf(stat: File): Long? = longFieldOf(stat, TTY_NR_FIELD)

    /**
     * Field [field] of a /proc stat file (field numbering per proc(5):
     * tokens[0] is field 3, so field N -> tokens[N - 3]; the comm field
     * (2) may contain spaces and ')' — parse after the LAST ')'). Null
     * when the file is missing/unreadable/malformed.
     */
    private fun longFieldOf(stat: File, field: Int): Long? {
        if (!stat.isFile) return null
        return runCatching {
            val text = stat.readText()
            val close = text.lastIndexOf(')')
            if (close < 0) return@runCatching null
            val tokens = text.substring(close + 1).trim().split(' ')
            tokens.getOrNull(field - 3)?.toLongOrNull()
        }.getOrNull()
    }

    /**
     * All processes whose /proc/<pid>/stat field 6 (session id) equals
     * [sessionId], each with captured identity — the session-keyed
     * discovery set for terminal teardown.
     *
     * TRUE KERNEL SEMANTICS (plan §5.2, review P0-1 — pinned by a
     * regression test so the misconception cannot be re-introduced):
     * `setsid(2)` creates a NEW session whose SID equals the caller's
     * own PID. A process that called setsid() therefore has
     * `sid == itsOwnPid`, NOT `sessionId`, and leaves this set
     * immediately and permanently. What IS true: reparenting to init
     * does not change the SID (an orphaned but attached process stays
     * visible), and tty_nr (field 7) survives setsid while the tty is
     * held — which is why the caller UNIONS this set with
     * [descendants] and [ttyMembers].
     */
    fun sessionMembers(procRoot: File, sessionId: Long): List<ProcessId> {
        return statEntries(procRoot) { dir ->
            val stat = File(dir, "stat")
            val start = startTimeOf(stat) ?: return@statEntries null
            val session = sessionOf(stat) ?: return@statEntries null
            if (session == sessionId) ProcessId(dir.name.toLong(), start) else null
        }
    }

    /**
     * All processes whose /proc/<pid>/stat field 7 (tty_nr) equals
     * [ttyDevice] (the st_rdev of the session's pts slave, computed in
     * the parent at PTY creation). The strongest discovery signal for a
     * terminal: a process keeps its controlling-terminal device number
     * even after `setsid()` unless it explicitly detached from the tty.
     */
    fun ttyMembers(procRoot: File, ttyDevice: Long): List<ProcessId> {
        return statEntries(procRoot) { dir ->
            val stat = File(dir, "stat")
            val start = startTimeOf(stat) ?: return@statEntries null
            val ttyNr = ttyNrOf(stat) ?: return@statEntries null
            if (ttyNr == ttyDevice) ProcessId(dir.name.toLong(), start) else null
        }
    }

    /**
     * Union discovery for terminal teardown (plan §5.2):
     * `sessionMembers(procRoot, shellPid) ∪ descendants(procRoot, shellPid)
     * ∪ ttyMembers(procRoot, ptsDevice)`, deduped by PID. The shell is
     * the session leader (the child path calls setsid() before exec), so
     * its SID equals its PID. Everything still ATTACHED to the terminal
     * is visible to at least one of the three sets; a deliberately
     * detached process (setsid + tty released) is invisible to all —
     * and survives BY DESIGN (that is what nohup/setsid are for).
     */
    fun unionMembers(procRoot: File, shellPid: Long, ptsDevice: Long): List<ProcessId> {
        val out = LinkedHashMap<Long, ProcessId>()
        for (id in sessionMembers(procRoot, shellPid)) out[id.pid] = id
        for (id in descendants(procRoot, shellPid)) out.putIfAbsent(id.pid, id)
        for (id in ttyMembers(procRoot, ptsDevice)) out.putIfAbsent(id.pid, id)
        return out.values.toList()
    }

    /** Lists all numeric /proc entries, mapping each through [mapper] (null drops it). */
    private inline fun statEntries(
        procRoot: File,
        mapper: (File) -> ProcessId?,
    ): List<ProcessId> {
        val dirs = procRoot.listFiles() ?: return emptyList()
        val out = ArrayList<ProcessId>()
        for (d in dirs) {
            val pid = d.name.toLongOrNull() ?: continue
            val id = mapper(d) ?: continue
            out.add(ProcessId(pid, id.startTime))
        }
        out.sortBy { it.pid } // deterministic (readdir order is fs-dependent)
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

    private const val STARTTIME_FIELD = 22
    private const val SESSION_FIELD = 6
    private const val TTY_NR_FIELD = 7

    /**
     * True when `/proc/<pid>` shows state 'Z' — the process TERMINATED
     * and is awaiting reap by its parent. A zombie holds its PID and
     * stat identity (same start time) until reaped, so it still
     * "matches" in the PID-reuse sense while being dead in every
     * operational sense: signals — SIGKILL included — do nothing to a
     * corpse; only reaping (or its parent dying and init inheriting it)
     * removes it from /proc.
     *
     * NOTE: like [stillMatches], this is a standalone read. A teardown
     * deciding whether to signal must use [readStat] (identity + state in
     * one observation) rather than [stillMatches] && ![isZombie], which
     * reads the file twice.
     */
    fun isZombie(procRoot: File, pid: Long): Boolean =
        stateOf(File(procRoot, "$pid/stat")) == 'Z'

    /**
     * `/proc/<pid>/stat` state character (field 3 — the first token
     * after the LAST ')'; the comm field may contain spaces and ')'). Null
     * when the file is missing/unreadable/malformed — treated as
     * "not a zombie": an unparseable stat also fails identity
     * revalidation, so such a process can never reach the sweep's
     * signal path anyway.
     */
    private fun stateOf(stat: File): Char? {
        if (!stat.isFile) return null
        return runCatching {
            val text = stat.readText()
            val close = text.lastIndexOf(')')
            if (close < 0) return@runCatching null
            val tokens = text.substring(close + 1).trim().split(' ')
            tokens.getOrNull(0)?.firstOrNull()
        }.getOrNull()
    }

    /**
     * Identity-preserving process-tree termination.
     *
     * Sequence:
     *  1. capture the root's identity (for re-discovery guarding later);
     *  2. snapshot descendants WITH identity;
     *  3. SIGSTOP every captured descendant that still matches its
     *     captured identity — freezing them so nothing new spawns and
     *     nothing escapes between discovery and the kill;
     *  4. terminate the root ([destroyRoot]);
     *  5. re-discover descendants ONLY while the root PID is still
     *     provably the ORIGINAL process — identity known AND still
     *     matching (the zombie window) — catching children spawned
     *     between snapshot and freeze; an UNKNOWN root identity (the
     *     /proc entry was already gone before we looked) authorizes NO
     *     late traversal at all, because that PID may have been reused
     *     by an unrelated process; every discovery is revalidated
     *     immediately before its signal;
     *  6. bounded final sweep: SIGKILL every captured process that STILL
     *     matches its identity AND is not a zombie, until none remain or
     *     the pass budget is exhausted. A PID that no longer matches was
     *     reused — it is never signaled. A zombie (state Z) is excluded
     *     from "remaining": a killed descendant reparented to init when
     *     the root died first sits in /proc with identity intact pending
     *     reap, so without the exclusion the sweep can never observe an
     *     empty `remaining` and every cancellation burns the full budget
     *     re-signaling corpses.
     *
     * All /proc reads are best-effort: processes vanishing mid-traversal
     * simply drop out of the sweep. No new dependencies.
     */
    fun terminateTree(
        procRoot: File,
        rootPid: Long,
        signal: (pid: Long, sig: Int) -> Unit,
        destroyRoot: () -> Unit,
        isRootAlive: () -> Boolean,
        rootGraceMs: Long = 2_000L,
        maxSweeps: Int = 3,
        sweepDelayMs: Long = 50L,
        sleeper: (ms: Long) -> Unit = { Thread.sleep(it) },
    ) {
        val rootId = identity(procRoot, rootPid)
        val captured = ArrayList<ProcessId>()
        val seen = HashSet<Long>()

        // 2-3) snapshot + freeze known descendants. Every injected-lambda
        // call is guarded (review condition 8): an ESRCH/EPERM race between
        // identity check and signal — or a throwing test lambda — must
        // never crash a teardown.
        for (id in descendants(procRoot, rootPid)) {
            if (seen.add(id.pid)) captured.add(id)
            if (stillMatches(procRoot, id)) runCatching { signal(id.pid, SIGSTOP) }
        }

        // 4) terminate the root — dead parents cannot spawn replacements
        runCatching { destroyRoot() }
        var waited = 0L
        while (isRootAlive() && waited < rootGraceMs) {
            sleeper(50L)
            waited += 50L
        }

        // 5) re-discover late spawns, but ONLY while the root PID is
        // still occupied by the ORIGINAL process — a reaped-and-reused
        // root PID belongs to someone else's tree now, and walking it
        // would target innocent processes. When the root's identity was
        // NEVER captured (rootId == null: /proc entry already gone),
        // the PID is UNTRUSTED for late rediscovery: an unknown
        // identity cannot prove the tree still belongs to us, and a
        // destructive traversal must not proceed on "cannot disprove".
        if (rootId != null && stillMatches(procRoot, rootId)) {
            for (id in descendants(procRoot, rootPid)) {
                if (seen.add(id.pid)) captured.add(id)
            }
        }

        // 6) bounded final sweep — kill only identity-matching LIVING
        // processes. Zombies (state Z) are dead pending reap: SIGKILL is
        // a no-op on a corpse, but its /proc entry and identity persist
        // until the reaper comes, so an unexcluded zombie keeps
        // `remaining` non-empty forever — the early-return never fires
        // and the caller pays the full sweep budget re-signaling the
        // dead. Excluded here: dead is dead.
        for (sweep in 0 until maxSweeps) {
            val remaining = captured.filter { id ->
                val st = readStat(procRoot, id.pid) ?: return@filter false
                id.startTime != null && st.startTime == id.startTime && st.state != 'Z'
            }
            if (remaining.isEmpty()) return
            for (id in remaining) runCatching { signal(id.pid, SIGKILL) }
            if (sweep < maxSweeps - 1) sleeper(sweepDelayMs)
        }
    }
}
