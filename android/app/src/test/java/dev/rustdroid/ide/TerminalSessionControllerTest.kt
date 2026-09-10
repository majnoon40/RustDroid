package dev.rustdroid.ide

import dev.rustdroid.ide.runtime.ProcTree
import dev.rustdroid.ide.runtime.terminal.TerminalSessionController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Recorder-based ordering tests for TerminalSessionController.teardown
 * (plan §5.3 / §6.4) — the same discipline as ProcTreeTest.
 *
 * The pinned orderings (each a review condition):
 *  - reader stopped AND joined BEFORE the master close (P1-4);
 *  - master close precedes EVERY SIGSTOP — no freeze before the
 *    SIGHUP-equivalent (P1-3) — and the grace delay is observed
 *    between them;
 *  - SIGCONT fires for freeze survivors only after the sweep budget is
 *    exhausted (condition 8);
 *  - a signal/killpg lambda that throws (ESRCH race) does not crash the
 *    teardown (condition 8);
 *  - zero signals to non-session processes; bounded sweeps; early
 *    return on zombie-only remainder.
 */
class TerminalSessionControllerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Records the teardown's observable actions, in order. */
    private class Recorder {
        val events = ArrayList<String>()
        val sent = ArrayList<Pair<Long, Int>>() // pid to sig
        val groupSends = ArrayList<Pair<Long, Int>>() // pgid to sig
        var threwOnSignal = false
        var threwOnKillpg = false
        var joinResult = true

        fun record(event: String) { events.add(event) }

        fun signal(pid: Long, sig: Int) {
            if (threwOnSignal) throw RuntimeException("kill($pid, $sig): ESRCH race")
            sent.add(pid to sig)
            record("signal:$pid:$sig")
        }

        fun killpg(pgid: Long, sig: Int) {
            if (threwOnKillpg) throw RuntimeException("killpg($pgid, $sig): ESRCH race")
            groupSends.add(pgid to sig)
            record("killpg:$pgid:$sig")
        }

        fun indexOf(event: String) = events.indexOf(event)

        fun signals(pid: Long, sig: Int) = sent.count { it == pid to sig }
    }

    /** Builds a synthetic session: shell 100 (leader), children as given. */
    private fun session(
        vararg procs: ProcSpec,
    ) {
        for (p in procs) {
            val dir = File(tmp.root, p.pid.toString()).apply { mkdirs() }
            dir.resolve("status").writeText("Name:\tfake\nPPid:\t${p.ppid}\n")
            // stat: fields (state ppid pgrp session tty_nr ... starttime)
            val stat = listOf(
                p.pid.toString(), "(fake${p.pid})", p.state, p.ppid.toString(),
                p.session.toString(), p.session.toString(), p.ttyNr.toString(),
            ).plus(List(14) { "0" }).plus(p.start.toString()).plus(List(8) { "0" })
                .joinToString(" ")
            dir.resolve("stat").writeText(stat)
        }
    }

    private data class ProcSpec(
        val pid: Long, val ppid: Long, val session: Long,
        val ttyNr: Long, val start: Long, val state: String = "R",
    )

    private fun controller(
        rec: Recorder,
        graceMs: Long = 300L,
        maxSweeps: Int = 3,
        sleeper: (Long) -> Unit = { rec.record("sleep:$it") },
    ) = TerminalSessionController(
        procRoot = tmp.root,
        shellPid = 100L,
        ptsDevice = 34817L,
        signal = rec::signal,
        killpg = rec::killpg,
        closeMaster = { rec.record("closeMaster") },
        stopReader = { rec.record("stopReader") },
        joinReader = { ms -> rec.record("joinReader:$ms"); rec.joinResult },
        graceMs = graceMs,
        maxSweeps = maxSweeps,
        sweepDelayMs = 50L,
        sleeper = sleeper,
    )

    // ------------------------------------------------------------------

    @Test
    fun `reader is stopped and joined before the master close`() {
        val rec = Recorder()
        session(ProcSpec(100L, 1L, 100L, 34817L, 10L))
        controller(rec).teardown()
        val stop = rec.indexOf("stopReader")
        val join = rec.indexOf("joinReader:2000")
        val close = rec.indexOf("closeMaster")
        assertTrue("stopReader must be recorded", stop >= 0)
        assertTrue("joinReader must be recorded", join >= 0)
        assertTrue("closeMaster must be recorded", close >= 0)
        assertTrue("stop ($stop) must precede join ($join)", stop < join)
        assertTrue("join ($join) must precede close ($close)", join < close)
    }

    @Test
    fun `master close precedes every SIGSTOP and a grace delay is observed between them`() {
        val rec = Recorder()
        session(
            ProcSpec(100L, 1L, 100L, 34817L, 10L),
            ProcSpec(200L, 100L, 100L, 34817L, 20L),
        )
        controller(rec, graceMs = 300L).teardown()
        val close = rec.indexOf("closeMaster")
        val grace = rec.indexOf("sleep:300")
        val firstStop = rec.events.indexOfFirst { it.startsWith("signal:") && it.endsWith(":${ProcTree.SIGSTOP}") }
        assertTrue("closeMaster ($close) must precede the grace sleep ($grace)", close < grace)
        assertTrue("grace sleep ($grace) must precede the first SIGSTOP ($firstStop)", grace < firstStop)
        for (e in rec.events) {
            if (e.startsWith("signal:") && e.endsWith(":${ProcTree.SIGSTOP}")) {
                assertTrue("no SIGSTOP may precede closeMaster: $e", rec.indexOf(e) > close)
            }
        }
    }

    @Test
    fun `SIGCONT is sent to freeze survivors only after the sweep budget is exhausted`() {
        val rec = Recorder()
        // 200 refuses to die (still R after every sweep): it must be
        // SIGCONTed at the very end, after all sweeps ran.
        session(
            ProcSpec(100L, 1L, 100L, 34817L, 10L),
            ProcSpec(200L, 100L, 100L, 34817L, 20L),
        )
        controller(rec, maxSweeps = 3, graceMs = 5L).teardown()
        val conts = rec.events.filter { it == "signal:200:${TerminalSessionController.SIGCONT}" }
        assertEquals("the stubborn survivor is SIGCONTed exactly once", 1, conts.size)
        val cont = rec.indexOf(conts.first())
        // the sweeps: SIGKILL to 200 three times (initial + 2 re-sweeps)
        val kills = rec.events.count { it == "signal:200:${ProcTree.SIGKILL}" }
        assertEquals(3, kills)
        val lastKill = rec.events.indexOfLast { it == "signal:200:${ProcTree.SIGKILL}" }
        assertTrue("SIGCONT ($cont) must come after the last SIGKILL ($lastKill)", cont > lastKill)
    }

    @Test
    fun `a throwing signal lambda does not crash teardown`() {
        val rec = Recorder()
        rec.threwOnSignal = true
        session(
            ProcSpec(100L, 1L, 100L, 34817L, 10L),
            ProcSpec(200L, 100L, 100L, 34817L, 20L),
        )
        // runCatching guard (condition 8): ESRCH races must never crash
        controller(rec, graceMs = 1L).teardown()
        assertTrue(rec.indexOf("closeMaster") >= 0)
    }

    @Test
    fun `a throwing killpg lambda does not crash teardown`() {
        val rec = Recorder()
        rec.threwOnKillpg = true
        session(ProcSpec(100L, 1L, 100L, 34817L, 10L))
        controller(rec, graceMs = 1L).teardown()
        assertTrue(rec.indexOf("closeMaster") >= 0)
    }

    @Test
    fun `the shell process group gets killpg SIGKILL once`() {
        val rec = Recorder()
        session(
            ProcSpec(100L, 1L, 100L, 34817L, 10L),
            ProcSpec(200L, 100L, 100L, 34817L, 20L),
        )
        controller(rec, graceMs = 1L).teardown()
        assertEquals(listOf(100L to ProcTree.SIGKILL), rec.groupSends)
    }

    @Test
    fun `zero signals to non-session processes`() {
        val rec = Recorder()
        session(
            ProcSpec(100L, 1L, 100L, 34817L, 10L),
            ProcSpec(200L, 100L, 100L, 34817L, 20L),
            ProcSpec(999L, 1L, 999L, 34999L, 99L), // unrelated
        )
        controller(rec, graceMs = 1L).teardown()
        assertFalse(rec.sent.any { it.first == 999L })
        assertEquals(setOf(100L, 200L), rec.sent.map { it.first }.toSet())
    }

    @Test
    fun `union discovery catches a setsid-d escapee still holding the tty`() {
        val rec = Recorder()
        session(
            ProcSpec(100L, 1L, 100L, 34817L, 10L),
            // setsid'd, reparented to init, tty still held: invisible to
            // the session set, caught by tty_nr — must be frozen and killed
            ProcSpec(500L, 1L, 500L, 34817L, 50L),
        )
        controller(rec, graceMs = 1L).teardown()
        assertTrue(rec.sent.any { it.first == 500L && it.second == ProcTree.SIGSTOP })
        assertTrue(rec.sent.any { it.first == 500L && it.second == ProcTree.SIGKILL })
    }

    @Test
    fun `early return on zombie-only remainder - corpses are never re-signaled`() {
        val rec = Recorder()
        session(
            // Both the shell and its child were killed by the hangup and
            // are zombies now: identities persist (so both were frozen),
            // but the zombie exclusion empties "remaining" on the first
            // sweep — ZERO SIGKILLs to corpses, and the early return
            // fires (no sweep-delay sleeps burned re-signaling the dead).
            ProcSpec(100L, 1L, 100L, 34817L, 10L, state = "Z"),
            ProcSpec(200L, 100L, 100L, 34817L, 20L, state = "Z"),
        )
        controller(rec, graceMs = 1L, maxSweeps = 3).teardown()
        assertEquals(0, rec.signals(200L, ProcTree.SIGKILL))
        assertEquals(0, rec.signals(100L, ProcTree.SIGKILL))
        // both corpses still match identity, so both were frozen first
        assertEquals(1, rec.signals(200L, ProcTree.SIGSTOP))
        assertEquals(1, rec.signals(100L, ProcTree.SIGSTOP))
        // early return: no sweep-delay sleeps happened
        assertFalse(rec.events.contains("sleep:50"))
    }

    @Test
    fun `pid reuse inside the captured set is never signaled`() {
        val rec = Recorder()
        session(
            ProcSpec(100L, 1L, 100L, 34817L, 10L),
            ProcSpec(200L, 100L, 100L, 34817L, 20L),
        )
        controller(rec, graceMs = 1L).teardown()
        // now PID 200 is reused by an unrelated process (new start time)
        session(ProcSpec(200L, 1L, 999L, 34999L, 77L))
        val rec2 = Recorder()
        // a fresh controller over the reused state must not signal 200:
        // its identity (start time) no longer matches the captured one.
        // (Discovery of the reused occupant: session 999 != 100 and tty
        // 34999 != 34817, so it is never even captured.)
        session(ProcSpec(200L, 1L, 999L, 34999L, 77L), ProcSpec(100L, 1L, 100L, 34817L, 10L))
        controller(rec2, graceMs = 1L).teardown()
        assertFalse(rec2.sent.any { it.first == 200L })
    }

    @Test
    fun `missing proc data degrades to closeMaster and killpg only`() {
        val rec = Recorder()
        // no synthetic /proc entries at all: union is empty, teardown
        // still closes the master and group-kills the (unknowable) session
        File(tmp.root, "self").mkdirs()
        controller(rec, graceMs = 1L).teardown()
        assertTrue(rec.indexOf("closeMaster") >= 0)
        assertEquals(listOf(100L to ProcTree.SIGKILL), rec.groupSends)
        assertTrue(rec.sent.isEmpty())
    }
}
