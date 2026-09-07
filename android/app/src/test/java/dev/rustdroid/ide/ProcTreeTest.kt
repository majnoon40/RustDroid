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

    private fun rewriteStat(pid: Long, start: Long, ppid: Long = 1L) {
        File(tmp.root, "$pid/stat").writeText(statLine(pid, "fake$pid", "R", ppid, start))
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
}
