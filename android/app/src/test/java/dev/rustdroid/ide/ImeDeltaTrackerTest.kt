package dev.rustdroid.ide

import dev.rustdroid.ide.ui.editor.ImeDeltaTracker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * IME open/closed tracking for the editor console panel (review P2).
 *
 * The first test is the review's exact case and is the DISCRIMINATOR: under
 * the v0.2 threshold rule an inset had to exceed 0.12 * screenHeightPx
 * (~96dp, e.g. ~384px on a 800dp 2.5x-density device) to count as open, so a
 * floating/split keyboard holding ~60px was reported CLOSED for the entire
 * time it was visible. The tracker must say open.
 *
 * The collapse test pins the other half: the panel must return on the first
 * frame the inset shrinks, not when it eventually reaches zero — that
 * ~300ms gap was the v0.2 complaint this whole area exists to fix.
 */
class ImeDeltaTrackerTest {

    @Test
    fun `a small floating keyboard that is fully open reads as open`() {
        val t = ImeDeltaTracker()
        t.update(0)
        assertTrue("a growing inset must read as open", t.update(60))
        assertTrue("a held-open small keyboard must stay open", t.update(60))
    }

    @Test
    fun `a full height keyboard opening reads as open`() {
        val t = ImeDeltaTracker()
        t.update(0)
        assertTrue(t.update(200))
        assertTrue(t.update(400))
    }

    @Test
    fun `the collapse is detected at the first decrease, not at zero`() {
        val t = ImeDeltaTracker()
        t.update(0)
        t.update(400)
        assertTrue(t.isOpen)
        assertFalse("the first frames of the collapse must close it", t.update(360))
    }

    @Test
    fun `a fully closed keyboard reads as closed`() {
        val t = ImeDeltaTracker()
        t.update(0)
        assertFalse(t.isOpen)
        t.update(400)
        assertTrue(t.isOpen)
        assertFalse(t.update(0))
    }

    @Test
    fun `jitter below the dead zone does not flap the state`() {
        val t = ImeDeltaTracker()
        t.update(0)
        t.update(400)
        assertTrue(t.isOpen)
        // a stationary keyboard plus/minus a few px of noise
        assertTrue(t.update(399))
        assertTrue(t.update(401))
        assertTrue(t.update(400))
        assertTrue("jitter must not be read as a collapse", t.isOpen)
    }

    @Test
    fun `a slow collapse still closes once it passes the dead zone`() {
        // Guards the accumulation rule: a frame-to-frame comparison would
        // see only -1px steps here, never trip, and fall back to the old
        // "closes only at zero" behaviour.
        val t = ImeDeltaTracker()
        t.update(0)
        t.update(400)
        assertTrue(t.isOpen)
        assertTrue("a 1px step is inside the dead zone", t.update(399))
        assertFalse("cumulative movement past the dead zone must close it", t.update(395))
    }

    @Test
    fun `a keyboard at rest while closed stays closed`() {
        val t = ImeDeltaTracker()
        assertFalse(t.update(0))
        assertFalse(t.update(0))
    }

    @Test
    fun `the console returns to the tall size only when the keyboard is gone`() {
        // End-to-end shape of the panel decision: open -> short panel,
        // collapsed -> tall panel, with no dependence on screen height.
        val t = ImeDeltaTracker()
        t.update(0)
        val whenOpen = t.update(300)
        val afterCollapse = t.update(80)
        assertTrue("keyboard up", whenOpen)
        assertFalse("keyboard on its way out", afterCollapse)
    }
}
