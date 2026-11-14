package com.example.myasr.context.ime.presentation

import kotlin.math.abs

/**
 * Tracks horizontal drag gestures on the spacebar and converts them into discrete
 * cursor movement steps (negative = left, positive = right).
 */
class SpacebarCursorSwipeTracker(
    private val activationThresholdPx: Float,
    private val stepThresholdPx: Float
) {
    private var isTracking: Boolean = false
    private var startX: Float = 0f
    private var startY: Float = 0f
    private var lastStepX: Float = 0f

    var isSwiping: Boolean = false
        private set

    fun start(x: Float, y: Float) {
        isTracking = true
        isSwiping = false
        startX = x
        startY = y
        lastStepX = x
    }

    /**
     * Updates the tracker with a new pointer position.
     * @return Signed number of cursor steps to perform (negative = left, positive = right, 0 = none).
     */
    fun move(x: Float, y: Float): Int {
        if (!isTracking) return 0

        var steps = 0
        if (!isSwiping) {
            val dx = x - startX
            val dy = y - startY
            if (abs(dx) >= activationThresholdPx && abs(dx) >= abs(dy)) {
                isSwiping = true
                if (dx > 0f) {
                    steps += 1
                    lastStepX = startX + activationThresholdPx
                } else {
                    steps -= 1
                    lastStepX = startX - activationThresholdPx
                }
            } else {
                return 0
            }
        }

        var delta = x - lastStepX
        while (delta >= stepThresholdPx) {
            steps += 1
            lastStepX += stepThresholdPx
            delta = x - lastStepX
        }
        while (delta <= -stepThresholdPx) {
            steps -= 1
            lastStepX -= stepThresholdPx
            delta = x - lastStepX
        }
        return steps
    }

    fun reset() {
        isTracking = false
        isSwiping = false
        startX = 0f
        startY = 0f
        lastStepX = 0f
    }
}
