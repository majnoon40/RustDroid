package dev.rustdroid.ide

import dev.rustdroid.ide.runtime.ProcTree
import dev.rustdroid.ide.runtime.terminal.TerminalSessionController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
 *
 * v0.2.1 additions (review P1 + the maintainer's fallback condition).
 * teardown() is now a `suspend fun`, so every call site is wrapped in
 * runBlocking. Four tests are marked with how they behave against the
 * PRE-FIX code, since a test that cannot fail on the bug it names is not
 * evidence:
 *  - [non-positive pgid] / [zero pgid] / [identity-guarded shell fallback]
 *    FAIL on the pre-fix code (which called killpg unconditionally and had
 *    no fallback at all).
 *  - [reused shell pid] / [zombie shell] pass on pre-fix code, because
 *    pre-fix had no fallback to get wrong — they discriminate a NAIVE
 *    fallback (one that omits identity revalidation or the zombie
 *    exclusion) from the correct one, and are labelled as such rather
 *    than claimed as bug-catchers.
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
        shellPid: Long = 100L,
        graceMs: Long = 300L,
        maxSweeps: Int = 3,
        // suspend type: the controller's sleeper defaults to delay(), and a
        // non-suspend function value cannot be passed where a suspend type
        // is expected. Tests inject a recording lambda.
        sleeper: suspend (Long) -> Unit = { rec.record("sleep:$it") },
    ) = TerminalSessionController(
        procRoot = tmp.root,
        shellPid = shellPid,
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
    fun `reader is stopped and joined before the master close`() = runBlocking {
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
    fun `master close precedes every SIGSTOP and a grace delay is observed between them`() = runBlocking {
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
    fun `SIGCONT is sent to freeze survivors only after the sweep budget is exhausted`() = runBlocking {
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
    fun `a throwing signal lambda does not crash teardown`() = runBlocking {
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
    fun `a throwing killpg lambda does not crash teardown`() = runBlocking {
        val rec = Recorder()
        rec.threwOnKillpg = true
        session(ProcSpec(100L, 1L, 100L, 34817L, 10L))
        controller(rec, graceMs = 1L).teardown()
        assertTrue(rec.indexOf("closeMaster") >= 0)
    }

    @Test
    fun `the shell process group gets killpg SIGKILL once`() = runBlocking {
        val rec = Recorder()
        session(
            ProcSpec(100L, 1L, 100L, 34817L, 10L),
            ProcSpec(200L, 100L, 100L, 34817L, 20L),
        )
        controller(rec, graceMs = 1L).teardown()
        assertEquals(listOf(100L to ProcTree.SIGKILL), rec.groupSends)
    }

    @Test
    fun `zero signals to non-session processes`() = runBlocking {
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
    fun `union discovery catches a setsid-d escapee still holding the tty`() = runBlocking {
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
    fun `early return on zombie-only remainder - corpses are never re-signaled`() = runBlocking {
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
    fun `pid reuse inside the captured set is never signaled`() = runBlocking {
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
    fun `missing proc data degrades to closeMaster and killpg only`() = runBlocking {
        val rec = Recorder()
        // no synthetic /proc entries at all: union is empty, teardown
        // still closes the master and group-kills the (unknowable) session
        File(tmp.root, "self").mkdirs()
        controller(rec, graceMs = 1L).teardown()
        assertTrue(rec.indexOf("closeMaster") >= 0)
        assertEquals(listOf(100L to ProcTree.SIGKILL), rec.groupSends)
        assertTrue(rec.sent.isEmpty())
    }

    // ------------------------------------------------------------------
    // v0.2.1 — the non-positive-pgid guard and the identity-checked
    // fallback (review P1 + the maintainer's condition).
    // ------------------------------------------------------------------

    /**
     * FAILS ON PRE-FIX CODE. `killpg(-1, SIGKILL)` is `kill(1, SIGKILL)` —
     * it targets init — and upstream's mShellPid is -1 once the shell has
     * exited, so an already-finished session can hand us exactly this
     * value. The pre-fix controller called killpg unconditionally.
     */
    @Test
    fun `killpg is never called with a negative pgid`() = runBlocking {
        val rec = Recorder()
        session(ProcSpec(100L, 1L, 100L, 34817L, 10L))
        controller(rec, shellPid = -1L, graceMs = 1L).teardown()
        assertTrue("killpg(-1) must never be issued", rec.groupSends.isEmpty())
        assertFalse("no signal may target pid -1", rec.sent.any { it.first == -1L })
    }

    /**
     * FAILS ON PRE-FIX CODE. `killpg(0, sig)` is `kill(0, sig)` — the
     * CALLER's own process group, i.e. the app signalling itself. A shell
     * that has not started yet reports pid 0.
     */
    @Test
    fun `killpg is never called with a zero pgid`() = runBlocking {
        val rec = Recorder()
        session(ProcSpec(100L, 1L, 100L, 34817L, 10L))
        controller(rec, shellPid = 0L, graceMs = 1L).teardown()
        assertTrue("killpg(0) must never be issued", rec.groupSends.isEmpty())
        assertFalse("no signal may target pid 0", rec.sent.any { it.first == 0L })
    }

    /**
     * FAILS ON PRE-FIX CODE (which had no fallback at all: the only
     * SIGKILL to the shell came from the group signal or the member sweep).
     *
     * The shell here is deliberately NOT discoverable as a member — its
     * session id is not the shell pid, its tty_nr is 0, and its ppid is 1 —
     * so `captured` is empty and the sweeps can never reach it. The only
     * thing that can kill it is the identity-guarded direct signal, which
     * is precisely what this test isolates.
     *
     * Why the fallback exists: the master close's SIGHUP reaches only the
     * pty's CURRENT foreground process group, so a shell that backgrounded
     * itself or changed its own pgid can outlive the hangup indefinitely.
     */
    @Test
    fun `the shell gets an identity-guarded SIGKILL even when it is not a discovered member`() = runBlocking {
        val rec = Recorder()
        // not a session member (sid 999), not a descendant (ppid 1),
        // not on the tty (0) — invisible to union discovery
        session(ProcSpec(100L, 1L, 999L, 0L, 10L, state = "R"))
        controller(rec, graceMs = 1L).teardown()
        assertEquals(
            "the shell must still receive exactly one direct SIGKILL",
            1, rec.signals(100L, ProcTree.SIGKILL),
        )
        // ...and it is a SIGNAL, never a group send (the group path is
        // still exercised, but the assertion above is the fallback's)
        assertTrue(rec.groupSends.isNotEmpty())
    }

    /**
     * Guard pin, NOT a pre-fix discriminator (pre-fix had no fallback, so
     * it passes there too). It fails against a NAIVE fallback that omits
     * the zombie exclusion and would SIGKILL a corpse — burning a syscall
     * on a process that is already dead pending reap.
     */
    @Test
    fun `a zombie shell is not signalled by the fallback`() = runBlocking {
        val rec = Recorder()
        session(ProcSpec(100L, 1L, 999L, 0L, 10L, state = "Z"))
        controller(rec, graceMs = 1L).teardown()
        assertEquals(0, rec.signals(100L, ProcTree.SIGKILL))
    }

    /**
     * Guard pin, NOT a pre-fix discriminator — and the load-bearing one.
     * It fails against a NAIVE fallback that signals a bare
     * `signal(shellPid, SIGKILL)` without revalidating identity, which
     * would reintroduce the exact PID-reuse hazard the whole
     * ProcTree/controller architecture exists to prevent.
     *
     * The stat file is rewritten from INSIDE the closeMaster lambda — i.e.
     * after the controller captured the shell's identity at teardown start
     * and before it reads it again at the fallback — simulating the PID
     * being reaped and reused by an unrelated process mid-teardown.
     */
    @Test
    fun `a shell pid reused mid-teardown is never signalled by the fallback`() = runBlocking {
        val rec = Recorder()
        // shell 100 exists with start time 10 (the captured identity)
        session(ProcSpec(100L, 1L, 999L, 0L, 10L, state = "R"))
        val ctrl = TerminalSessionController(
            procRoot = tmp.root,
            shellPid = 100L,
            ptsDevice = 34817L,
            signal = rec::signal,
            killpg = rec::killpg,
            closeMaster = {
                rec.record("closeMaster")
                // PID 100 is now someone else (reaped and reused): same pid,
                // DIFFERENT start time. Runs between the step-0 capture and
                // the step-7 revalidation.
                session(ProcSpec(100L, 1L, 4242L, 0L, 4242L, state = "R"))
            },
            stopReader = { rec.record("stopReader") },
            joinReader = { ms -> rec.record("joinReader:$ms"); rec.joinResult },
            graceMs = 1L,
            sleeper = { rec.record("sleep:$it") },
        )
        ctrl.teardown()
        assertFalse(
            "a reused PID must never be signalled — identity revalidation failed",
            rec.sent.any { it.first == 100L && it.second == ProcTree.SIGKILL },
        )
    }

    /**
     * FAILS ON PRE-FIX CODE, which blocked the calling thread in
     * Thread.sleep and therefore ignored cancellation entirely. With the
     * default (delay-based) sleeper the grace wait is a real suspension
     * point, so cancelling the scope aborts teardown at the grace step —
     * before any SIGSTOP/SIGKILL sweep runs.
     */
    @Test
    fun `teardown is cancellable at the grace suspension point`() = runBlocking {
        val rec = Recorder()
        session(ProcSpec(100L, 1L, 100L, 34817L, 10L))
        // default sleeper => delay(graceMs): a real suspension point
        val ctrl = TerminalSessionController(
            procRoot = tmp.root,
            shellPid = 100L,
            ptsDevice = 34817L,
            signal = rec::signal,
            killpg = rec::killpg,
            closeMaster = { rec.record("closeMaster") },
            stopReader = { rec.record("stopReader") },
            joinReader = { ms -> rec.record("joinReader:$ms"); rec.joinResult },
            graceMs = 2_000L,
        )
        val job = launch { runCatching { ctrl.teardown() } }
        delay(100) // let it reach the grace sleep
        job.cancel()
        job.join()
        assertTrue("the master must already be closed", rec.indexOf("closeMaster") >= 0)
        assertFalse(
            "cancelled teardown must not have run the freeze/kill sweeps",
            rec.events.any { it.startsWith("signal:") },
        )
    }
}
