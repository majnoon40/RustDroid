package dev.rustdroid.ide

import dev.rustdroid.ide.runtime.CrashRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Crash flight recorder contract (v0.1.8): the persistence that turns
 * "the app just closed" into an in-app, copyable report. Pure JVM —
 * no Android surface, so every property is testable here.
 */
class CrashRecorderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `crumbs accumulate newest last and cap at the ring size`() {
        val dir = tmp.newFolder()
        CrashRecorder.install(dir, chainTo = null)

        repeat(60) { CrashRecorder.crumb("tag$it") }

        val lines = CrashRecorder.breadcrumbs(dir)
        assertEquals(40, lines.size)
        assertTrue(lines.first().endsWith("tag20"))
        assertTrue(lines.last().endsWith("tag59"))

        CrashRecorder.clear(dir)
        assertTrue(CrashRecorder.breadcrumbs(dir).isEmpty())
    }

    @Test
    fun `record persists thread exception cause chain and breadcrumbs`() {
        val dir = tmp.newFolder()
        CrashRecorder.install(dir, chainTo = null)
        CrashRecorder.crumb("terminal:create:start")

        val thread = Thread.currentThread()
        val ex = RuntimeException("boom", IllegalStateException("root cause"))
        CrashRecorder.record(dir, thread, ex)

        val report = CrashRecorder.lastCrash(dir)
        assertNotNull(report)
        val r = report!!
        assertTrue(r.contains("thread=${thread.name}"))
        assertTrue(r.contains("java.lang.RuntimeException: boom"))
        assertTrue(r.contains("caused-by=java.lang.IllegalStateException: root cause"))
        assertTrue(r.contains("dev.rustdroid.ide.CrashRecorderTest"))
        assertTrue(r.contains("--- breadcrumbs"))
        assertTrue(r.contains("terminal:create:start"))

        CrashRecorder.clear(dir)
        assertEquals(null, CrashRecorder.lastCrash(dir))
    }

    @Test
    fun `record overwrites the previous report`() {
        val dir = tmp.newFolder()
        val thread = Thread.currentThread()

        CrashRecorder.record(dir, thread, RuntimeException("first"))
        CrashRecorder.record(dir, thread, RuntimeException("second"))

        val r = CrashRecorder.lastCrash(dir)!!
        assertTrue(r.contains("java.lang.RuntimeException: second"))
        assertTrue(!r.contains("java.lang.RuntimeException: first"))
    }

    @Test
    fun `installed handler records then chains to the previous handler`() {
        val dir = tmp.newFolder()
        val chained = AtomicBoolean(false)
        val sentinel = Thread.UncaughtExceptionHandler { _, _ -> chained.set(true) }

        val handler = CrashRecorder.install(dir, chainTo = sentinel)
        handler.uncaughtException(Thread.currentThread(), RuntimeException("during-test"))

        assertTrue(chained.get())
        val r = CrashRecorder.lastCrash(dir)!!
        assertTrue(r.contains("java.lang.RuntimeException: during-test"))
    }

    @Test
    fun `lastCrash is null when no crash was recorded`() {
        val dir = tmp.newFolder()
        assertEquals(null, CrashRecorder.lastCrash(dir))
    }
}
