package com.example.myasr.context.ime.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpacebarCursorSwipeTrackerTest {

    @Test
    fun `small jitter below activation threshold does not trigger swipe`() {
        val tracker = SpacebarCursorSwipeTracker(activationThresholdPx = 28f, stepThresholdPx = 24f)
        tracker.start(500f, 100f)

        assertEquals(0, tracker.move(510f, 102f))
        assertEquals(0, tracker.move(485f, 98f))
        assertFalse(tracker.isSwiping)
    }

    @Test
    fun `vertical drag dominant does not trigger horizontal spacebar swipe`() {
        val tracker = SpacebarCursorSwipeTracker(activationThresholdPx = 28f, stepThresholdPx = 24f)
        tracker.start(500f, 100f)

        // dx = 30 (>= 28), but dy = -60 (vertical is dominant)
        assertEquals(0, tracker.move(530f, 40f))
        assertFalse(tracker.isSwiping)
    }

    @Test
    fun `rightward swipe emits positive steps proportionally and suppresses tap`() {
        val tracker = SpacebarCursorSwipeTracker(activationThresholdPx = 28f, stepThresholdPx = 24f)
        tracker.start(500f, 100f)

        // Crosses +28px activation threshold -> 1 step right
        assertEquals(1, tracker.move(528f, 100f))
        assertTrue(tracker.isSwiping)

        // Moves another +24px -> 1 more step right
        assertEquals(1, tracker.move(552f, 100f))

        // Large jump of +48px -> 2 steps right
        assertEquals(2, tracker.move(600f, 105f))
    }

    @Test
    fun `leftward swipe and direction reversal emit signed steps`() {
        val tracker = SpacebarCursorSwipeTracker(activationThresholdPx = 28f, stepThresholdPx = 24f)
        tracker.start(500f, 100f)

        // Crosses -28px -> 1 step left (-1)
        assertEquals(-1, tracker.move(472f, 100f))
        assertTrue(tracker.isSwiping)

        // Moves another -24px -> 1 more step left (-1)
        assertEquals(-1, tracker.move(448f, 100f))

        // Reverses direction +48px -> 2 steps right (+2)
        assertEquals(2, tracker.move(496f, 100f))
    }

    @Test
    fun `reset clears swiping state`() {
        val tracker = SpacebarCursorSwipeTracker(activationThresholdPx = 28f, stepThresholdPx = 24f)
        tracker.start(500f, 100f)
        assertEquals(1, tracker.move(530f, 100f))
        assertTrue(tracker.isSwiping)

        tracker.reset()
        assertFalse(tracker.isSwiping)
        assertEquals(0, tracker.move(600f, 100f))
    }

    @Test
    fun `cursor control mode fromId defaults to ARROW_KEYS`() {
        assertEquals(
            KeyboardHeightManager.QwertyCursorControlMode.ARROW_KEYS,
            KeyboardHeightManager.QwertyCursorControlMode.fromId(null)
        )
        assertEquals(
            KeyboardHeightManager.QwertyCursorControlMode.ARROW_KEYS,
            KeyboardHeightManager.QwertyCursorControlMode.fromId("unknown")
        )
        assertEquals(
            KeyboardHeightManager.QwertyCursorControlMode.SPACEBAR_SWIPE,
            KeyboardHeightManager.QwertyCursorControlMode.fromId("spacebar_swipe")
        )
    }
}
