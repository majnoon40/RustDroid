package dev.rustdroid.ide.ui.editor

/**
 * IME open/closed tracking for the editor's console panel (v0.2.1).
 *
 * WHY DELTA, NOT A THRESHOLD (review P2): v0.2 decided "the keyboard is up"
 * by comparing the raw IME inset against a fixed 12%-of-screen-height
 * threshold. That is tuned to a full-height phone keyboard (~40-50% of the
 * screen). For any IME that occupies less than 12% while fully open — a
 * floating/split keyboard (Samsung, several Chinese IMEs) or a small
 * one-row keyboard — the comparison reads "closed" for the entire time the
 * keyboard is up, so the console panel reappears *while the user is
 * typing*: the original v0.2 complaint, inverted. A percentage threshold
 * simply cannot tell a small keyboard apart from no keyboard.
 *
 * The inset's DIRECTION can. It grows while the keyboard opens, holds while
 * it is up, and shrinks while it collapses:
 *
 *  - grows            -> opening / up
 *  - shrinks          -> collapsing / down, detected on the FIRST frame of
 *                        the collapse — which is the whole point of the
 *                        v0.2 latency fix (the console returns when the
 *                        keyboard starts leaving, not ~300ms later when its
 *                        inset finally reaches zero)
 *  - exactly 0        -> down
 *  - unchanged        -> state RETAINED, so a held-open floating keyboard is
 *                        never mistaken for a closed one merely because it
 *                        is short
 *
 * Deltas are measured CUMULATIVELY against the inset at the last state
 * change, not frame-to-frame. That matters twice: sub-pixel jitter cannot
 * flap a keyboard that is sitting still, and an unusually slow animation
 * still closes once it has travelled past the dead zone in small steps
 * (a frame-to-frame rule would never fire on 1px steps and would degrade to
 * the old "closes only at zero" behaviour).
 *
 * Deliberately NOT WindowInsetsAnimation (API 30+): minSdk is 24, and the
 * delta rule needs no new API level, so there is ONE code path on every
 * supported device instead of two that can disagree.
 *
 * Pure Kotlin — no Compose or Android types — so the decision is a plain
 * state machine the JVM tests drive directly (ImeDeltaTrackerTest).
 */
class ImeDeltaTracker(private val deadZonePx: Int = DEFAULT_DEAD_ZONE_PX) {

    /** IME inset (px) at the last state change; deltas accumulate from here. */
    private var referenceBottomPx = 0

    /** True while the keyboard is considered open. */
    var isOpen: Boolean = false
        private set

    /**
     * Feed the current IME inset height in pixels and return the resulting
     * open state. Call once per composition with
     * `WindowInsets.ime.getBottom(density)`.
     *
     * Reading the inset during composition is exactly what the old code did;
     * it is safe here because the read only feeds this tracker's state and
     * the boolean result, not a layout offset (the panel's visibility is a
     * discrete decision, not an animation).
     */
    fun update(bottomPx: Int): Boolean {
        if (bottomPx <= 0) {
            // Fully closed. Unconditional: no dead zone applies at zero, so
            // the panel can never be stuck hidden because the last few
            // pixels of the closing animation were small steps.
            isOpen = false
            referenceBottomPx = 0
            return isOpen
        }
        val delta = bottomPx - referenceBottomPx
        when {
            delta >= deadZonePx -> {
                isOpen = true
                referenceBottomPx = bottomPx
            }
            delta <= -deadZonePx -> {
                isOpen = false
                referenceBottomPx = bottomPx
            }
            // within the dead zone: retain the current state, keep the
            // reference so the movement keeps accumulating
        }
        return isOpen
    }

    companion object {
        /** Movement smaller than this (px) cannot flip the state. */
        const val DEFAULT_DEAD_ZONE_PX = 4
    }
}
