package dev.rustdroid.ide

import dev.rustdroid.ide.model.Stream
import dev.rustdroid.ide.runtime.ConsoleBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsoleBufferTest {

    private fun line(i: Int) =
        dev.rustdroid.ide.model.ConsoleLine(Stream.STDOUT, "line $i")

    /**
     * A buffer whose rate-limit window is OPEN: the very first append
     * always publishes (instant first output — desirable), so tests that
     * verify holding behavior start after one publish+clear cycle.
     */
    private fun warmedBuffer(capacity: Int = 2000, intervalMs: Long = 10_000): ConsoleBuffer {
        val buf = ConsoleBuffer(capacity = capacity, publishIntervalMs = intervalMs)
        buf.append(line(-1)) // publishes -> window opens
        buf.clear()          // publishes (reset) -> window stays open
        return buf
    }

    @Test
    fun `appends are visible after flush`() {
        val buf = ConsoleBuffer(publishIntervalMs = 0)
        buf.append(line(1))
        buf.append(line(2))
        assertEquals(2, buf.lines.value.size)
        assertEquals("line 2", buf.lines.value.last().text)
    }

    @Test
    fun `capacity trims the oldest lines and counts drops`() {
        val buf = ConsoleBuffer(capacity = 3, publishIntervalMs = 0)
        repeat(5) { buf.append(line(it)) }
        assertEquals(3, buf.lines.value.size)
        // the two oldest were dropped
        assertEquals("line 2", buf.lines.value.first().text)
        assertEquals("line 4", buf.lines.value.last().text)
        assertEquals(2, buf.droppedOverflow)
    }

    @Test
    fun `system lines publish immediately even inside a rate-limit window`() {
        // 10s window: nothing else would publish without an explicit flush
        val buf = warmedBuffer()
        buf.append(line(1))
        assertEquals(0, buf.lines.value.size) // rate-limited burst
        buf.system("status")
        // system messages bypass the window — they are rare and important.
        // The held burst line is published together with it (nothing is
        // lost, everything is ordered).
        assertEquals(2, buf.lines.value.size)
        assertEquals("line 1", buf.lines.value.first().text)
        assertEquals("status", buf.lines.value.last().text)
    }

    @Test
    fun `rate-limited bursts publish on flush with order preserved`() {
        val buf = warmedBuffer()
        repeat(50) { buf.append(line(it)) }
        assertEquals(0, buf.lines.value.size)
        buf.flush()
        assertEquals(50, buf.lines.value.size)
        assertEquals("line 0", buf.lines.value.first().text)
        assertEquals("line 49", buf.lines.value.last().text)
    }

    @Test
    fun `clear resets the buffer and the drop counter`() {
        val buf = ConsoleBuffer(capacity = 2, publishIntervalMs = 0)
        repeat(4) { buf.append(line(it)) }
        assertTrue(buf.droppedOverflow > 0)
        buf.clear()
        assertEquals(0, buf.lines.value.size)
        assertEquals(0, buf.droppedOverflow)
    }
}
