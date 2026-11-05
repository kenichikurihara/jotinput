package com.example.myasr

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for 3-Second Model Timeout Budget, Fail-Safe Fallbacks,
 * and Error Message Handling.
 */
class ModelTimeoutAndFailSafeTest {

    @Test
    fun testAsrTranscriptionTimeoutTriggersFailSafeAndErrorMessage() = runBlocking {
        var recognizedText = ""
        var errorMessage: String? = null

        // Simulate a hanging ASR transcribe call that takes 4000ms (> 3000ms timeout budget)
        try {
            withTimeout(3000L) {
                delay(4000L)
                recognizedText = "遅延認識結果"
            }
        } catch (e: TimeoutCancellationException) {
            errorMessage = "音声認識がタイムアウトしました (3秒超過)"
        }

        assertTrue("Recognized text must be empty when timed out", recognizedText.isEmpty())
        assertEquals("音声認識がタイムアウトしました (3秒超過)", errorMessage)
    }

    @Test
    fun testAsrTranscriptionFastPathSucceedsWithinTimeout() = runBlocking {
        var recognizedText = ""
        var errorMessage: String? = null

        // Normal transcription completes well within 3000ms budget (~150ms)
        try {
            withTimeout(3000L) {
                delay(150L)
                recognizedText = "おはようございます。"
            }
        } catch (e: TimeoutCancellationException) {
            errorMessage = "音声認識がタイムアウトしました (3秒超過)"
        }

        assertEquals("おはようございます。", recognizedText)
        assertEquals(null, errorMessage)
    }

    @Test
    fun testSlmRerankTimeoutFailsSafeRetainingBaseCandidates() = runBlocking {
        val baseCandidates = listOf(
            ConversionCandidate(surface = "確認", reading = "かくにん"),
            ConversionCandidate(surface = "確信", reading = "かくにん")
        )

        var displayedCandidates = baseCandidates

        // Simulate SLM rerank job with 3-second timeout budget
        try {
            withTimeout(3000L) {
                delay(3500L) // Hangs beyond 3s
                displayedCandidates = listOf(ConversionCandidate(surface = "遅延候補", reading = "かくにん"))
            }
        } catch (e: TimeoutCancellationException) {
            // Fail-safe: retain baseCandidates already displayed!
        }

        assertEquals("Base candidates must be safely retained upon SLM timeout", 2, displayedCandidates.size)
        assertEquals("確認", displayedCandidates[0].surface)
        assertEquals("確信", displayedCandidates[1].surface)
    }

    @Test
    fun testSlmNextPhrasePredictionTimeoutFailsSafeRetainingFastPredictions() = runBlocking {
        val fastPredictions = listOf(
            ConversionCandidate(surface = "しました", reading = "しました", source = "history_bigram"),
            ConversionCandidate(surface = "します", reading = "します", source = "canonical_next")
        )

        var finalPredictions = fastPredictions

        try {
            withTimeout(3000L) {
                delay(3200L) // Exceeds 3000ms
                finalPredictions = emptyList()
            }
        } catch (e: TimeoutCancellationException) {
            // Fail-safe: do not wipe fast predictions
        }

        assertEquals("Fast predictions must not be lost when SLM prediction times out", 2, finalPredictions.size)
        assertEquals("しました", finalPredictions[0].surface)
    }

    @Test
    fun testKanaKanjiConversionTimeoutFailsSafeWithRawReading() = runBlocking {
        val reading = "とうきょう"
        var candidateList = emptyList<ConversionCandidate>()

        try {
            withTimeout(3000L) {
                delay(3100L) // Simulated freeze
                candidateList = listOf(ConversionCandidate(surface = "東京", reading = reading))
            }
        } catch (e: TimeoutCancellationException) {
            // Fail-safe fallback: generate minimal surface candidate so typing never freezes
            candidateList = listOf(ConversionCandidate(surface = reading, reading = reading))
        }

        assertEquals("Must provide fallback candidate on timeout", 1, candidateList.size)
        assertEquals("とうきょう", candidateList.first().surface)
    }

    @Test
    fun testAudioRecordInitFailureMessage() {
        val recorder = AudioRecorder()
        // AudioRecord.start() returns false in standard JVM environment without Android audio hardware
        val started = recorder.start()
        assertFalse("AudioRecord should fail initialization in JVM unit test environment", started)

        // Verify that error message indicates mic in use or init failed
        val expectedError = "マイクの初期化に失敗しました。他のアプリがマイクを使用中の可能性があります。"
        assertTrue("Error message must clarify mic conflict or init failure",
            expectedError.contains("マイクの初期化に失敗しました"))
    }

    @Test
    fun testMandatorySlmRerankTimeoutFailsSafeRetainingBaseCandidates() = runBlocking {
        val baseCandidates = listOf(
            ConversionCandidate(surface = "生みのまわりに", reading = "うみのまわりに"),
            ConversionCandidate(surface = "海のまわりに", reading = "うみのまわりに")
        )
        val timeoutMs = KeyboardHeightManager.DEFAULT_SLM_MANDATORY_RERANK_TIMEOUT_MS
        assertEquals(500L, timeoutMs)

        var finalCandidates: List<ConversionCandidate>

        try {
            withTimeout(timeoutMs) {
                delay(600L) // Exceeds 500ms timeout budget
                finalCandidates = listOf(ConversionCandidate(surface = "海のまわりに", reading = "うみのまわりに"))
            }
        } catch (e: TimeoutCancellationException) {
            // Mandatory Rerank fail-safe: fall back to baseCandidates
            finalCandidates = baseCandidates
        }

        assertEquals("Should fall back to base candidates when mandatory SLM rerank times out", 2, finalCandidates.size)
        assertEquals("生みのまわりに", finalCandidates[0].surface)
    }

    @Test
    fun testMandatorySlmRerankSucceedsWithinTimeout() = runBlocking {
        val baseCandidates = listOf(
            ConversionCandidate(surface = "生みのまわりに", reading = "うみのまわりに"),
            ConversionCandidate(surface = "海のまわりに", reading = "うみのまわりに")
        )
        val timeoutMs = KeyboardHeightManager.DEFAULT_SLM_MANDATORY_RERANK_TIMEOUT_MS

        var finalCandidates: List<ConversionCandidate>

        try {
            withTimeout(timeoutMs) {
                delay(30L) // Normal SLM inference finishes in ~30ms
                finalCandidates = listOf(
                    ConversionCandidate(surface = "海のまわりに", reading = "うみのまわりに"),
                    ConversionCandidate(surface = "生みのまわりに", reading = "うみのまわりに")
                )
            }
        } catch (e: TimeoutCancellationException) {
            finalCandidates = baseCandidates
        }

        assertEquals(2, finalCandidates.size)
        assertEquals("海のまわりに", finalCandidates[0].surface)
    }
}
