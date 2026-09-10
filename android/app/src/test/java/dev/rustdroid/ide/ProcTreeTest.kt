package dev.rustdroid.ide

import dev.rustdroid.ide.runtime.ProcTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProcTreeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /**
     * Builds a synthetic /proc stat line with [start] at field 22
     * (starttime) — the field count matches the real kernel layout so
     * the parser is exercised faithfully.
     */
    private fun statLine(pid: Long, comm: String, state: String, ppid: Long, start: Long): String =
        (listOf(pid.toString(), "($comm)", state, ppid.toString()) +
            List(17) { "0" } +            // fields 5..21
            listOf(start.toString()) +     // field 22: starttime
            List(8) { "0" }).joinToString(" ")

    /** Builds a synthetic /proc: pid -> (ppid, starttime). */
    private fun proc(vararg procs: Triple<Long, Long, Long>) {
        for ((pid, ppid, start) in procs) {
            val dir = File(tmp.root, pid.toString()).apply { mkdirs() }
            File(dir, "status").writeText(
                "Name:\tfake$pid\nState:\tR\nPPid:\t$ppid\n"
            )
            File(dir, "stat").writeText(statLine(pid, "fake$pid", "R", ppid, start))
        }
        // non-numeric and non-process entries must be ignored
        File(tmp.root, "self").mkdirs()
        File(tmp.root, "acpi").mkdirs()
    }

    private fun rewriteStat(pid: Long, start: Long, ppid: Long = 1L, state: String = "R") {
        File(tmp.root, "$pid/stat").writeText(statLine(pid, "fake$pid", state, ppid, start))
    }

    // ------------------------------------------------------------------
    // Tree discovery
    // ------------------------------------------------------------------

    @Test
    fun `descendants are collected breadth-first, siblings included`() {
        // 100 -> 200 -> 300, and 100 -> 250
        proc(
            Triple(100L, 1L, 10L), Triple(200L, 100L, 20L),
            Triple(300L, 200L, 30L), Triple(250L, 100L, 25L),
            Triple(999L, 1L, 99L),
        )
        val kids = ProcTree.descendantPids(tmp.root, 100L)
        // children before grandchildren; 999 (unrelated) excluded
        assertEquals(listOf(200L, 250L, 300L), kids)
    }

    @Test
    fun `root with no children yields empty list`() {
        proc(Triple(100L, 1L, 10L), Triple(500L, 1L, 50L))
        assertEquals(emptyList<Long>(), ProcTree.descendantPids(tmp.root, 100L))
    }

    @Test
    fun `missing proc root degrades to empty`() {
        assertEquals(
            emptyList<Long>(),
            ProcTree.descendantPids(File("/nonexistent-proc"), 100L),
        )
    }

    @Test
    fun `kernel and init children are never descendants`() {
        proc(Triple(7L, 0L, 1L), Triple(8L, 1L, 2L), Triple(100L, 1L, 10L))
        assertTrue(ProcTree.descendantPids(tmp.root, 100L).isEmpty())
    }

    @Test
    fun `descendants carry their start time as identity`() {
        proc(
            Triple(100L, 1L, 10L),
            Triple(200L, 100L, 777L),
            Triple(300L, 200L, 888L),
        )
        val ids = ProcTree.descendants(tmp.root, 100L)
        assertEquals(
            listOf(ProcTree.ProcessId(200L, 777L), ProcTree.ProcessId(300L, 888L)),
            ids,
        )
    }

    @Test
    fun `process vanishing mid-walk is dropped, not kept pid-only`() {
        // 200 has a status file (parent link) but NO stat file: unreadable
        // identity -> unsafe to signal -> excluded entirely
        val dir = File(tmp.root, "100").apply { mkdirs() }
        File(dir, "status").writeText("Name:\ta\nPPid:\t1\n")
        File(dir, "stat").writeText(statLine(100L, "a", "R", 1L, 10L))
        val child = File(tmp.root, "200").apply { mkdirs() }
        File(child, "status").writeText("Name:\tb\nPPid:\t100\n")
        // no stat file for 200

        assertEquals(emptyList<ProcTree.ProcessId>(), ProcTree.descendants(tmp.root, 100L))
    }

    // ------------------------------------------------------------------
    // Identity
    // ------------------------------------------------------------------

    @Test
    fun `identity reads pid plus start time`() {
        proc(Triple(100L, 1L, 4242L))
        assertEquals(ProcTree.ProcessId(100L, 4242L), ProcTree.identity(tmp.root, 100L))
    }

    @Test
    fun `identity of a vanished process is null`() {
        assertNull(ProcTree.identity(tmp.root, 404L))
    }

    @Test
    fun `identity of a malformed stat file is null`() {
        val dir = File(tmp.root, "500").apply { mkdirs() }
        File(dir, "stat").writeText("garbage without parens")
        assertNull(ProcTree.identity(tmp.root, 500L))
    }

    @Test
    fun `comm field containing spaces and parens does not break stat parsing`() {
        val dir = File(tmp.root, "600").apply { mkdirs() }
        // comm may legitimately contain ')' and spaces (e.g. threads)
        File(dir, "stat").writeText(statLine(600L, "rustc (work) thread", "S", 1L, 555L))
        assertEquals(ProcTree.ProcessId(600L, 555L), ProcTree.identity(tmp.root, 600L))
    }

    @Test
    fun `stillMatches is true only while the same process occupies the pid`() {
        proc(Triple(100L, 1L, 10L))
        val id = ProcTree.identity(tmp.root, 100L)!!
        assertTrue(ProcTree.stillMatches(tmp.root, id))
    }

    @Test
    fun `stillMatches is false when start time drifts (pid reused)`() {
        proc(Triple(100L, 1L, 10L))
        val id = ProcTree.identity(tmp.root, 100L)!!
        // the original died; a NEW process took pid 100 later (bigger start)
        rewriteStat(100L, 99999L)
        assertFalse(ProcTree.stillMatches(tmp.root, id))
    }

    @Test
    fun `stillMatches is false when the process is gone`() {
        proc(Triple(100L, 1L, 10L))
        val id = ProcTree.identity(tmp.root, 100L)!!
        File(tmp.root, "100").deleteRecursively()
        assertFalse(ProcTree.stillMatches(tmp.root, id))
    }

    @Test
    fun `stillMatches is false for a pid-only identity (unvalidatable)`() {
        proc(Triple(100L, 1L, 10L))
        assertFalse(ProcTree.stillMatches(tmp.root, ProcTree.ProcessId(100L, null)))
    }

    // ------------------------------------------------------------------
    // terminateTree — the kill algorithm
    // ------------------------------------------------------------------

    /** Records (pid, sig) pairs sent during a terminateTree run. */
    private inner class Recorder {
        val sent = mutableListOf<Pair<Long, Int>>()
        var rootDestroyed = false
        var rootAlive = true
        val signal: (Long, Int) -> Unit = { pid, sig -> sent += pid to sig }
        var onRootDestroyed: () -> Unit = {}
        val destroyRoot: () -> Unit = {
            rootDestroyed = true
            onRootDestroyed()
            rootAlive = false
        }
    }

    @Test
    fun `normal cleanup stops then kills every descendant`() {
        proc(
            Triple(100L, 1L, 10L),
            Triple(200L, 100L, 20L),
            Triple(300L, 200L, 30L),
        )
        val rec = Recorder()
        rec.onRootDestroyed = { File(tmp.root, "100").deleteRecursively() }

        ProcTree.terminateTree(
            tmp.root, 100L, rec.signal, rec.destroyRoot, { rec.rootAlive },
            sweepDelayMs = 0,
        )

        assertTrue(rec.rootDestroyed)
        // freeze (SIGSTOP) + kill (SIGKILL) for each descendant
        assertTrue(rec.sent.any { it == 200L to ProcTree.SIGSTOP })
        assertTrue(rec.sent.any { it == 200L to ProcTree.SIGKILL })
        assertTrue(rec.sent.any { it == 300L to ProcTree.SIGKILL })
    }

    @Test
    fun `vanished descendant is simply skipped`() {
        proc(Triple(100L, 1L, 10L), Triple(200L, 100L, 20L))
        val rec = Recorder()
        // 200 exits on its own between discovery and the sweep
        rec.onRootDestroyed = {
            File(tmp.root, "100").deleteRecursively()
            File(tmp.root, "200").deleteRecursively()
        }
        ProcTree.terminateTree(
            tmp.root, 100L, rec.signal, rec.destroyRoot, { rec.rootAlive },
            sweepDelayMs = 0,
        )
        assertTrue(rec.rootDestroyed)
        // STOP may or may not have been sent pre-vanish, but no KILL for 200
        assertFalse(rec.sent.any { it == 200L to ProcTree.SIGKILL })
    }

    @Test
    fun `pid reuse between discovery and kill is never signaled`() {
        proc(Triple(100L, 1L, 10L), Triple(200L, 100L, 20L))
        val rec = Recorder()
        // the original 200 dies; an UNRELATED process reuses pid 200
        rec.onRootDestroyed = {
            File(tmp.root, "100").deleteRecursively()
            rewriteStat(200L, 987654L) // different start time = different process
        }
        ProcTree.terminateTree(
            tmp.root, 100L, rec.signal, rec.destroyRoot, { rec.rootAlive },
            sweepDelayMs = 0,
        )
        // STOP happened while identity still matched (pre-reuse)
        assertTrue(rec.sent.any { it == 200L to ProcTree.SIGSTOP })
        // but the reused occupant must NOT be SIGKILLed
        assertFalse(rec.sent.any { it == 200L to ProcTree.SIGKILL })
    }

    @Test
    fun `root pid is never treated as its own descendant`() {
        // malformed entry claims pid 100 parents itself
        val dir = File(tmp.root, "100").apply { mkdirs() }
        File(dir, "status").writeText("Name:\ta\nPPid:\t100\n")
        File(dir, "stat").writeText(statLine(100L, "a", "R", 100L, 10L))

        val rec = Recorder()
        ProcTree.terminateTree(
            tmp.root, 100L, rec.signal, rec.destroyRoot, { rec.rootAlive },
            sweepDelayMs = 0,
        )
        // the root itself must never appear in the signal stream
        assertFalse(rec.sent.any { it.first == 100L })
    }

    @Test
    fun `missing proc data degrades to destroying only the root`() {
        val rec = Recorder()
        ProcTree.terminateTree(
            File("/nonexistent-proc"), 100L, rec.signal, rec.destroyRoot, { rec.rootAlive },
            sweepDelayMs = 0,
        )
        assertTrue(rec.rootDestroyed)
        assertTrue(rec.sent.isEmpty())
    }

    @Test
    fun `pid-reused root is not walked for late descendants`() {
        proc(Triple(100L, 1L, 10L), Triple(200L, 100L, 20L))
        val rec = Recorder()
        // root 100 dies AND its pid is instantly reused by an unrelated
        // process that has its own child 250 — walking from the reused
        // root would target 250; identity validation must prevent that
        rec.onRootDestroyed = {
            File(tmp.root, "200").deleteRecursively()
            rewriteStat(100L, 555555L)
            proc(Triple(250L, 100L, 999L))
        }
        ProcTree.terminateTree(
            tmp.root, 100L, rec.signal, rec.destroyRoot, { rec.rootAlive },
            sweepDelayMs = 0,
        )
        // 250 is a child of the REUSED root process — never ours
        assertFalse(rec.sent.any { it.first == 250L })
    }

    @Test
    fun `unknown root identity never authorizes late descendant rediscovery`() {
        // The original root is ALREADY GONE before terminateTree even
        // started: /proc/100 exists as an entry, but its stat file is
        // unreadable, so the root's identity can NEVER be established.
        // Later the pid exists again, now with fresh children (a reused
        // pid owned by an unrelated process). A destructive late
        // traversal from that pid would signal innocent processes — an
        // UNKNOWN identity must not be treated as permission.
        val dir = File(tmp.root, "100").apply { mkdirs() }
        File(dir, "status").writeText("Name:\ta\nPPid:\t1\n")
        // deliberately NO stat file: identity() -> null
        val rec = Recorder()
        rec.onRootDestroyed = {
            // the pid is reused by a new occupant that spawns children
            File(dir, "stat").writeText(statLine(100L, "reused", "R", 1L, 424242L))
            proc(Triple(150L, 100L, 1500L), Triple(151L, 100L, 1510L))
        }
        ProcTree.terminateTree(
            tmp.root, 100L, rec.signal, rec.destroyRoot, { rec.rootAlive },
            sweepDelayMs = 0,
        )
        assertTrue(rec.rootDestroyed)
        // the initial snapshot found no children; the late rediscovery
        // must be REFUSED (root identity unknown), so nothing is ever
        // captured and no signal of any kind is sent to 150/151
        assertTrue(
            "no destructive rediscovery/signaling from an untrusted root pid, got ${rec.sent}",
            rec.sent.isEmpty(),
        )
    }

    @Test
    fun `late descendant spawned before root death is caught by re-discovery`() {
        proc(Triple(100L, 1L, 10L), Triple(200L, 100L, 20L))
        val rec = Recorder()
        // a new grandchild 350 appears while the root is still dying;
        // the root stays a zombie (pid + identity intact)
        rec.onRootDestroyed = { proc(Triple(350L, 200L, 3500L)) }
        ProcTree.terminateTree(
            tmp.root, 100L, rec.signal, rec.destroyRoot, { rec.rootAlive },
            sweepDelayMs = 0,
        )
        // 350 spawned under 200 before the kill: still ours, still matching
        assertTrue(rec.sent.any { it == 350L to ProcTree.SIGKILL })
    }

    @Test
    fun `sweep is bounded when a descendant refuses to die`() {
        proc(Triple(100L, 1L, 10L), Triple(200L, 100L, 20L))
        val rec = Recorder()
        rec.onRootDestroyed = { File(tmp.root, "100").deleteRecursively() }
        ProcTree.terminateTree(
            tmp.root, 100L, rec.signal, rec.destroyRoot, { rec.rootAlive },
            maxSweeps = 3, sweepDelayMs = 0,
        )
        // 3 sweeps -> at most 3 SIGKILLs for the stubborn process
        val kills = rec.sent.count { it == 200L to ProcTree.SIGKILL }
        assertTrue("expected <= 3 kills, got $kills", kills <= 3)
        assertTrue(kills >= 1)
    }

    // ------------------------------------------------------------------
    // Zombie exclusion in the final sweep (regression: the sweep used to
    // count state-Z corpses as "remaining", so the early-return never
    // fired and every cancellation paid the full maxSweeps budget
    // re-signaling processes that were already dead and pending reap)
    // ------------------------------------------------------------------

    @Test
    fun `zombie state is read from the first token after the comm field`() {
        proc(Triple(100L, 1L, 10L))
        assertFalse(ProcTree.isZombie(tmp.root, 100L)) // R state, alive
        rewriteStat(100L, 10L, state = "Z")
        assertTrue(ProcTree.isZombie(tmp.root, 100L))
        // a zombie KEEPS its identity — same start time, state Z: the
        // PID-reuse defense must still see the original process
        assertEquals(ProcTree.ProcessId(100L, 10L), ProcTree.identity(tmp.root, 100L))
        rewriteStat(100L, 10L, state = "R")
        assertFalse(ProcTree.isZombie(tmp.root, 100L))
        // vanished /proc entry: not a zombie, just gone
        File(tmp.root, "100").deleteRecursively()
        assertFalse(ProcTree.isZombie(tmp.root, 100L))
    }

    @Test
    fun `killed descendant that becomes a zombie is not re-signaled and unblocks the early return`() {
        // The live scenario this mirrors: root (cargo) dies first, its
        // child (rustc) is reparented to init; the sweep SIGKILLs the
        // child; the child becomes a state-Z corpse pending reap by its
        // NEW parent (init) — /proc entry and identity INTACT. The old
        // code kept counting it as "remaining", never early-returned,
        // and burned the full sweep budget signaling the corpse.
        proc(Triple(100L, 1L, 10L), Triple(200L, 100L, 20L))
        val rec = Recorder()
        var slept = 0
        rec.onRootDestroyed = {
            // root dead AND reaped: /proc/100 gone; 200 now hangs off init
            File(tmp.root, "100").deleteRecursively()
            rewriteStat(200L, 20L, ppid = 1L) // reparented, still alive
        }
        val killMakesZombie: (Long, Int) -> Unit = { pid, sig ->
            rec.signal(pid, sig)
            if (pid == 200L && sig == ProcTree.SIGKILL) {
                // SIGKILL lands: 200 terminates but is NOT reaped by its
                // new parent yet — state flips to Z, identity unchanged
                rewriteStat(200L, 20L, ppid = 1L, state = "Z")
            }
        }
        ProcTree.terminateTree(
            tmp.root, 100L, killMakesZombie, rec.destroyRoot, { rec.rootAlive },
            maxSweeps = 5, sweepDelayMs = 0, sleeper = { slept += 1 },
        )
        assertTrue(rec.rootDestroyed)
        // the kill happened exactly once — sweeps after the kill see a
        // zombie-only "remaining", which is EMPTY by the exclusion
        val kills = rec.sent.count { it == 200L to ProcTree.SIGKILL }
        assertEquals("zombie corpse must not be re-signaled", 1, kills)
        // sweep 0 signals+sleeps, sweep 1 sees only the zombie and
        // returns early: with maxSweeps=5 the old code would have slept
        // 4 times; the fixed code sleeps exactly once
        assertEquals("early return must fire on the sweep after the kill", 1, slept)
    }

    @Test
    fun `a live stubborn descendant still gets the full bounded sweep`() {
        // guard against over-exclusion: a process that stays RUNNING (not
        // zombie, identity intact, refuses to die) must still receive the
        // full bounded re-signaling budget — the zombie exclusion may
        // only skip the DEAD
        proc(Triple(100L, 1L, 10L), Triple(200L, 100L, 20L))
        val rec = Recorder()
        var slept = 0
        rec.onRootDestroyed = { File(tmp.root, "100").deleteRecursively() }
        ProcTree.terminateTree(
            tmp.root, 100L, rec.signal, rec.destroyRoot, { rec.rootAlive },
            maxSweeps = 3, sweepDelayMs = 0, sleeper = { slept += 1 },
        )
        val kills = rec.sent.count { it == 200L to ProcTree.SIGKILL }
        assertEquals(3, kills)
        assertEquals(2, slept)
    }

    // ------------------------------------------------------------------
    // Session / tty discovery + union (Phase 4, plan §5.2 / §6.4)
    // ------------------------------------------------------------------

    /**
     * Builds a full synthetic stat line with pgrp (5), session (6) and
     * tty_nr (7) populated — the field count matches the real kernel
     * layout so the parser is exercised faithfully.
     */
    private fun statLineFull(
        pid: Long, comm: String, state: String, ppid: Long,
        pgrp: Long, session: Long, ttyNr: Long, start: Long,
    ): String = (
        listOf(pid.toString(), "($comm)", state, ppid.toString(),
            pgrp.toString(), session.toString(), ttyNr.toString()) +
            List(14) { "0" } +           // fields 8..21
            listOf(start.toString()) +    // field 22: starttime
            List(8) { "0" }).joinToString(" ")

    /** Synthetic /proc entry with full session/tty identity. */
    private fun procEntry(
        pid: Long, ppid: Long, session: Long, ttyNr: Long, start: Long,
        state: String = "R",
    ) {
        val dir = File(tmp.root, pid.toString()).apply { mkdirs() }
        dir.resolve("status").writeText("Name:\tfake$pid\nState:\tR\nPPid:\t$ppid\n")
        dir.resolve("stat").writeText(
            statLineFull(pid, "fake$pid", state, ppid, session, session, ttyNr, start)
        )
    }

    @Test
    fun `sessionMembers returns only processes whose session id matches`() {
        // shell 100 is the session leader (sid == 100): children 200/300
        // share the session; 999 is in another session.
        procEntry(100L, 1L, 100L, 0L, 10L)
        procEntry(200L, 100L, 100L, 0L, 20L)
        procEntry(300L, 200L, 100L, 0L, 30L)
        procEntry(999L, 1L, 999L, 0L, 99L)
        val members = ProcTree.sessionMembers(tmp.root, 100L)
        // the session leader (the shell itself) is a member of its own
        // session — teardown freezes and kills it too
        assertEquals(listOf(100L, 200L, 300L), members.map { it.pid })
        assertEquals(listOf(10L, 20L, 30L), members.map { it.startTime })
    }

    @Test
    fun `a setsid-d process is NOT a session member - it is its own session leader`() {
        // THE kernel-semantics pin (plan §5.2, review P0-1): setsid(2)
        // creates a NEW session whose SID equals the CALLER'S OWN PID.
        // The v1 plan believed a setsid'd daemon keeps `sid == shellPid`
        // forever — that inverts the syscall, and this test exists so
        // the misconception cannot be re-introduced: a process with
        // `sid == ownPid` must be invisible to sessionMembers.
        procEntry(100L, 1L, 100L, 0L, 10L)                     // shell
        procEntry(400L, 100L, 100L, 0L, 40L)                   // plain child
        procEntry(500L, 1L, 500L, 34817L, 50L)                 // setsid'd: sid == own pid
        val members = ProcTree.sessionMembers(tmp.root, 100L)
        assertEquals(listOf(100L, 400L), members.map { it.pid })
        assertFalse(members.any { it.pid == 500L })
    }

    @Test
    fun `ttyMembers matches the pts device number even after setsid`() {
        // tty_nr (field 7) survives setsid while the tty is still held —
        // the strongest discovery signal for a terminal.
        procEntry(100L, 1L, 100L, 34817L, 10L)
        procEntry(500L, 1L, 500L, 34817L, 50L)    // setsid'd but tty held
        procEntry(600L, 1L, 600L, 0L, 60L)        // detached: no tty
        val members = ProcTree.ttyMembers(tmp.root, 34817L)
        assertEquals(listOf(100L, 500L), members.map { it.pid })
    }

    @Test
    fun `union discovery catches a setsid-d escapee still holding the tty`() {
        // The union of the three keyed sets: plain child via session,
        // grandchild via session, setsid'd-tty-held via tty_nr.
        procEntry(100L, 1L, 100L, 34817L, 10L)    // shell (leader)
        procEntry(200L, 100L, 100L, 34817L, 20L)  // plain child
        procEntry(300L, 200L, 100L, 34817L, 30L)  // grandchild
        procEntry(500L, 1L, 500L, 34817L, 50L)    // setsid'd, tty still held (ppid 1)
        val union = ProcTree.unionMembers(tmp.root, 100L, 34817L)
        assertEquals(listOf(100L, 200L, 300L, 500L), union.map { it.pid })
    }

    @Test
    fun `a fully detached escapee is invisible to all three sets - and survives by design`() {
        // setsid + tty released: invisible to sessionMembers (new
        // session), to descendants (ppid 1, no parent link), and to
        // ttyMembers (tty_nr 0). SURVIVING IS THE DESIGNED BEHAVIOR —
        // nohup/setsid exist precisely so a job outlives the terminal.
        // This test asserts the invisibility so nobody "fixes" it.
        procEntry(100L, 1L, 100L, 34817L, 10L)
        procEntry(200L, 100L, 100L, 34817L, 20L)
        procEntry(700L, 1L, 700L, 0L, 70L)        // fully detached
        assertEquals(listOf(100L, 200L), ProcTree.sessionMembers(tmp.root, 100L).map { it.pid })
        // tty set sees the shell and the plain child — never the escapee
        assertEquals(listOf(100L, 200L), ProcTree.ttyMembers(tmp.root, 34817L).map { it.pid })
        val union = ProcTree.unionMembers(tmp.root, 100L, 34817L)
        assertEquals(listOf(100L, 200L), union.map { it.pid })
        assertFalse(union.any { it.pid == 700L })
    }

    @Test
    fun `unrelated processes with other sessions and ttys are excluded from the union`() {
        procEntry(100L, 1L, 100L, 34817L, 10L)
        procEntry(200L, 100L, 100L, 34817L, 20L)
        procEntry(800L, 1L, 888L, 34999L, 80L)    // different session AND tty
        val union = ProcTree.unionMembers(tmp.root, 100L, 34817L)
        assertEquals(listOf(100L, 200L), union.map { it.pid })
    }

    @Test
    fun `process vanishing mid-walk is dropped from session and tty discovery`() {
        // 200 has no stat file (vanished between listFiles and the read):
        // unreadable identity -> unsafe to signal -> dropped.
        val dir = File(tmp.root, "100").apply { mkdirs() }
        dir.resolve("status").writeText("Name:\ta\nPPid:\t1\n")
        dir.resolve("stat").writeText(statLineFull(100L, "a", "R", 1L, 100L, 100L, 34817L, 10L))
        val gone = File(tmp.root, "200").apply { mkdirs() }
        gone.resolve("status").writeText("Name:\tb\nPPid:\t100\n")
        // no stat file for 200
        assertEquals(listOf(100L), ProcTree.sessionMembers(tmp.root, 100L).map { it.pid })
        assertEquals(
            listOf(100L),
            ProcTree.ttyMembers(tmp.root, 34817L).map { it.pid },
        )
    }

    @Test
    fun `sessionMembers is empty for a missing proc root`() {
        assertEquals(
            emptyList<ProcTree.ProcessId>(),
            ProcTree.sessionMembers(File("/nonexistent-proc"), 100L),
        )
    }
}
