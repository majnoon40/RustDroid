package dev.rustdroid.ide

import dev.rustdroid.ide.runtime.ProcTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProcTreeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Builds a synthetic /proc: pid -> (ppid, extra status lines). */
    private fun proc(vararg procs: Pair<Long, Long>) {
        for ((pid, ppid) in procs) {
            val dir = File(tmp.root, pid.toString()).apply { mkdirs() }
            File(dir, "status").writeText(
                "Name:\tfake$pid\nState:\tR\nPPid:\t$ppid\n"
            )
        }
        // non-numeric and non-process entries must be ignored
        File(tmp.root, "self").mkdirs()
        File(tmp.root, "acpi").mkdirs()
    }

    @Test
    fun `descendants are collected breadth-first, siblings included`() {
        // 100 -> 200 -> 300, and 100 -> 250
        proc(100L to 1L, 200L to 100L, 300L to 200L, 250L to 100L, 999L to 1L)
        val kids = ProcTree.descendantPids(tmp.root, 100L)
        // children before grandchildren; 999 (unrelated) excluded
        assertEquals(listOf(200L, 250L, 300L), kids)
    }

    @Test
    fun `root with no children yields empty list`() {
        proc(100L to 1L, 500L to 1L)
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
        // ppid 0/1 entries exist but can't be under a user-space root anyway;
        // a pid whose PARENT is root stays a child of the scan root only
        // when actually linked — here nothing links to 100
        proc(7L to 0L, 8L to 1L, 100L to 1L)
        assertTrue(ProcTree.descendantPids(tmp.root, 100L).isEmpty())
    }
}
