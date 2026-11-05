package com.example.myasr

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ImeLatencyAndAsyncTest {

    @Test
    fun testCandidatePoolPruningLogic() {
        val candidates = (1..20).map { i ->
            ConversionCandidate(surface = "単語$i", reading = "たんご", isPrediction = false, source = "dict")
        }

        val topEligible = candidates.take(8)
        val restEligible = candidates.drop(8)

        assertEquals(8, topEligible.size)
        assertEquals(12, restEligible.size)
        assertEquals("単語1", topEligible.first().surface)
        assertEquals("単語8", topEligible.last().surface)
        assertEquals("単語9", restEligible.first().surface)
    }

    @Test
    fun testRapidTypingJobCancellation() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        var rerankJob: Job? = null
        val currentComposing = StringBuilder()
        val finishedJobCount = AtomicInteger(0)
        val cancelledJobCount = AtomicInteger(0)
        var latestUiResult: String = ""

        val typingInputs = listOf("こ", "こと", "ことな", "ことなり", "ことなります")

        for (input in typingInputs) {
            currentComposing.clear()
            currentComposing.append(input)
            val currentReading = currentComposing.toString()

            // Synchronous Fast-Path
            latestUiResult = currentReading

            // Cancel stale in-flight re-ranking
            rerankJob?.cancel()

            // Asynchronous re-ranking worker
            rerankJob = scope.launch {
                try {
                    delay(30) // simulate 30ms inference time
                    if (isActive && currentComposing.toString() == currentReading) {
                        latestUiResult = "reranked:$currentReading"
                        finishedJobCount.incrementAndGet()
                    }
                } catch (e: Exception) {
                    cancelledJobCount.incrementAndGet()
                }
            }

            // Rapid typing interval: 10ms between keystrokes
            delay(10)
        }

        // Wait for final keystroke inference to complete
        rerankJob?.join()

        // Verify only the final job finished and posted to UI
        assertEquals("reranked:ことなります", latestUiResult)
        assertEquals(1, finishedJobCount.get())
        assertTrue("Expected preceding jobs to be cancelled", cancelledJobCount.get() >= 1)
    }

    @Test
    fun testFastPathSynchronousLatency() {
        val reading = "ことなります"
        // JIT warm-up
        repeat(5) {
            JapaneseKanaUtils.normalizeReading(reading)
            JapaneseKanaUtils.hiraganaToKatakana(reading)
            JapaneseKanaUtils.toHalfWidthKatakana(reading)
        }

        // Measure execution time of pure in-memory normalization and list slicing
        val startTime = System.nanoTime()
        val normalized = JapaneseKanaUtils.normalizeReading(reading)
        val katakana = JapaneseKanaUtils.hiraganaToKatakana(normalized)
        val halfWidth = JapaneseKanaUtils.toHalfWidthKatakana(normalized)
        val elapsedMs = (System.nanoTime() - startTime) / 1_000_000.0

        assertEquals("ことなります", normalized)
        assertEquals("コトナリマス", katakana)
        assertEquals("ｺﾄﾅﾘﾏｽ", halfWidth)
        assertTrue("Fast-path string processing should take < 2ms (was ${elapsedMs}ms)", elapsedMs < 2.0)
    }
}
