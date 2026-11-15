package com.example.myasr.context.ime.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeleteRepeatAcceleratorTest {

    @Test
    fun `stage 1 returns 50ms and 1 char for initial repeat window`() {
        assertEquals(
            DeleteRepeatAccelerator.RepeatStep(delayMs = 50L, deleteCount = 1),
            DeleteRepeatAccelerator.stepForElapsedMs(0L)
        )
        assertEquals(
            DeleteRepeatAccelerator.RepeatStep(delayMs = 50L, deleteCount = 1),
            DeleteRepeatAccelerator.stepForElapsedMs(499L)
        )
    }

    @Test
    fun `negative elapsed time is clamped safely to stage 1`() {
        assertEquals(
            DeleteRepeatAccelerator.RepeatStep(delayMs = 50L, deleteCount = 1),
            DeleteRepeatAccelerator.stepForElapsedMs(-100L)
        )
    }

    @Test
    fun `stage 2 accelerates to 40ms and 2 chars at 500ms`() {
        assertEquals(
            DeleteRepeatAccelerator.RepeatStep(delayMs = 40L, deleteCount = 2),
            DeleteRepeatAccelerator.stepForElapsedMs(500L)
        )
        assertEquals(
            DeleteRepeatAccelerator.RepeatStep(delayMs = 40L, deleteCount = 2),
            DeleteRepeatAccelerator.stepForElapsedMs(1199L)
        )
    }

    @Test
    fun `stage 3 accelerates to 35ms and 4 chars at 1200ms`() {
        assertEquals(
            DeleteRepeatAccelerator.RepeatStep(delayMs = 35L, deleteCount = 4),
            DeleteRepeatAccelerator.stepForElapsedMs(1200L)
        )
        assertEquals(
            DeleteRepeatAccelerator.RepeatStep(delayMs = 35L, deleteCount = 4),
            DeleteRepeatAccelerator.stepForElapsedMs(2199L)
        )
    }

    @Test
    fun `stage 4 accelerates to 30ms and 8 chars at 2200ms and beyond`() {
        assertEquals(
            DeleteRepeatAccelerator.RepeatStep(delayMs = 30L, deleteCount = 8),
            DeleteRepeatAccelerator.stepForElapsedMs(2200L)
        )
        assertEquals(
            DeleteRepeatAccelerator.RepeatStep(delayMs = 30L, deleteCount = 8),
            DeleteRepeatAccelerator.stepForElapsedMs(10_000L)
        )
    }

    @Test
    fun `effective deletion rate increases monotonically across stages`() {
        val sampleTimes = listOf(0L, 500L, 1200L, 2200L)
        val ratesCharsPerSec = sampleTimes.map { t ->
            val step = DeleteRepeatAccelerator.stepForElapsedMs(t)
            step.deleteCount * 1000.0 / step.delayMs
        }
        for (i in 1 until ratesCharsPerSec.size) {
            assertTrue(
                "Expected rate at stage $i (${ratesCharsPerSec[i]}) > stage ${i - 1} (${ratesCharsPerSec[i - 1]})",
                ratesCharsPerSec[i] > ratesCharsPerSec[i - 1]
            )
        }
    }
}
