package com.example.myasr.context.ime.presentation

/**
 * Pure Kotlin policy calculating multi-stage repeat intervals and batch character counts
 * for long-pressing the Delete (Backspace) key.
 *
 * Accelerates both tick rate and per-tick batch size so that long deletions remain fast
 * even when target-app InputConnection IPC or UI layout has per-call latency (e.g. ARCVM).
 */
object DeleteRepeatAccelerator {

    const val INITIAL_DELAY_MS: Long = 350L
    const val MIN_HAPTIC_INTERVAL_MS: Long = 50L

    data class RepeatStep(
        val delayMs: Long,
        val deleteCount: Int
    )

    private data class Stage(
        val minElapsedMs: Long,
        val delayMs: Long,
        val deleteCount: Int
    )

    private val STAGES = listOf(
        Stage(minElapsedMs = 2200L, delayMs = 30L, deleteCount = 8),
        Stage(minElapsedMs = 1200L, delayMs = 35L, deleteCount = 4),
        Stage(minElapsedMs = 500L, delayMs = 40L, deleteCount = 2),
        Stage(minElapsedMs = 0L, delayMs = 50L, deleteCount = 1)
    )

    /**
     * Returns the repeat delay and character batch size for the given [elapsedRepeatMs]
     * (time elapsed since the first repeat tick fired after [INITIAL_DELAY_MS]).
     */
    fun stepForElapsedMs(elapsedRepeatMs: Long): RepeatStep {
        val clamped = elapsedRepeatMs.coerceAtLeast(0L)
        val stage = STAGES.first { clamped >= it.minElapsedMs }
        return RepeatStep(delayMs = stage.delayMs, deleteCount = stage.deleteCount)
    }
}
