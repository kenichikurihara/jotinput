package com.example.myasr

import com.example.myasr.context.speech.infrastructure.AudioRecorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for voice input pause and stop lifecycle.
 * Prevents regressions where audio chunks already emitted during pauses
 * are re-emitted or duplicated when voice input is stopped.
 */
class VoiceInputPauseAndStopRegressionTest {

    private fun generateSineWaveChunk(sampleCount: Int = 1024, freq: Double = 440.0, amplitude: Double = 8000.0): ShortArray {
        return ShortArray(sampleCount) { idx ->
            (Math.sin(2.0 * Math.PI * freq * idx / 16000.0) * amplitude).toInt().toShort()
        }
    }

    private fun generateSilenceChunk(sampleCount: Int = 1024): ShortArray {
        return ShortArray(sampleCount) { 0 }
    }

    @Test
    fun testPauseChunkEmissionDoesNotDuplicateOnStop() {
        val capturedChunks = mutableListOf<FloatArray>()
        val recorder = AudioRecorder(
            sampleRate = 16000,
            onSpeechChunkCaptured = { capturedChunks.add(it) }
        )

        val speechBuffer = generateSineWaveChunk()
        val silenceBuffer = generateSilenceChunk()

        // 1. User speaks for ~1.5 seconds (24 chunks * 1024 = 24576 samples)
        for (i in 0 until 24) {
            recorder.feedAudioData(speechBuffer, speechBuffer.size)
        }
        // Speech is still ongoing, so no chunk captured yet
        assertTrue("Speech is ongoing, no chunk should be emitted yet", capturedChunks.isEmpty())

        // 2. User pauses: 700ms silence (12 chunks * 1024 = 12288 samples > 650ms threshold)
        for (i in 0 until 12) {
            recorder.feedAudioData(silenceBuffer, silenceBuffer.size)
        }

        // At this point, the streaming pause detection must have emitted exactly 1 chunk
        assertEquals("Exactly one speech chunk should be captured on conversational pause", 1, capturedChunks.size)
        assertEquals("capturedChunksCount should be 1", 1, recorder.capturedChunksCount)
        val firstChunk = capturedChunks.first()
        assertTrue("First chunk should contain audio (> 15000 samples)", firstChunk.size > 15000)

        // 3. User taps Stop button to finish voice input
        val stopAudio = recorder.stop()

        // CRITICAL REGRESSION ASSERTION:
        // stop() must NOT return the already-captured audio from recordedPcmData!
        // Under the bug, stop() returned the entire 1.5s audio, causing the ASR engine
        // to re-decode and commit the identical text a second time.
        assertTrue(
            "stop() must return empty audio when the speech chunk was already captured at pause, but got ${stopAudio.size} samples",
            stopAudio.isEmpty()
        )
    }

    @Test
    fun testMultiUtteranceSpeechWithActiveSpeechAtStop() {
        val capturedChunks = mutableListOf<FloatArray>()
        val recorder = AudioRecorder(
            sampleRate = 16000,
            onSpeechChunkCaptured = { capturedChunks.add(it) }
        )

        val speechBuffer = generateSineWaveChunk()
        val silenceBuffer = generateSilenceChunk()

        // 1. Utterance 1: 1.5 seconds speech
        for (i in 0 until 24) {
            recorder.feedAudioData(speechBuffer, speechBuffer.size)
        }

        // 2. Pause: 700ms silence -> Utterance 1 emitted
        for (i in 0 until 12) {
            recorder.feedAudioData(silenceBuffer, silenceBuffer.size)
        }
        assertEquals(1, capturedChunks.size)

        // 3. Utterance 2: 1.0 second speech (16 chunks)
        for (i in 0 until 16) {
            recorder.feedAudioData(speechBuffer, speechBuffer.size)
        }
        // Utterance 2 has not paused yet, so capturedChunks is still 1
        assertEquals(1, capturedChunks.size)

        // 4. User taps Stop while in the middle of Utterance 2
        val stopAudio = recorder.stop()

        // stop() should return ONLY Utterance 2 (in-flight chunk), NOT Utterance 1 + Utterance 2
        assertTrue("stop() should return in-flight Utterance 2", stopAudio.isNotEmpty())
        // Utterance 2 is ~1.0s (~16000 samples + pre-roll), definitely < 25000 samples.
        // If Utterance 1 were duplicated, it would be > 40000 samples.
        assertTrue(
            "stopAudio should contain only Utterance 2 (~16000 samples), but was ${stopAudio.size}",
            stopAudio.size in 10000..25000
        )
    }

    @Test
    fun testSingleUtteranceWithoutPauseReturnsFullSpeech() {
        val capturedChunks = mutableListOf<FloatArray>()
        val recorder = AudioRecorder(
            sampleRate = 16000,
            onSpeechChunkCaptured = { capturedChunks.add(it) }
        )

        val speechBuffer = generateSineWaveChunk()

        // User speaks 1.2s without pausing
        for (i in 0 until 20) {
            recorder.feedAudioData(speechBuffer, speechBuffer.size)
        }
        assertTrue(capturedChunks.isEmpty())

        // User taps stop
        val stopAudio = recorder.stop()
        assertTrue("Single utterance should be returned at stop", stopAudio.isNotEmpty())
        assertTrue("Audio duration should be ~1.2s (> 15000 samples)", stopAudio.size > 15000)
    }

    @Test
    fun testEndToEndNoDuplicateTextDeliveredOnPauseAndStop() {
        // Mock InputConnection
        val committedText = StringBuilder()
        val mockCommitText = { text: String ->
            committedText.append(text)
        }

        val capturedChunks = mutableListOf<FloatArray>()
        val recorder = AudioRecorder(
            sampleRate = 16000,
            onSpeechChunkCaptured = { chunk ->
                capturedChunks.add(chunk)
                // Simulate asynchronous chunk transcription & delivery
                mockCommitText("明日の天気は")
            }
        )

        val speechBuffer = generateSineWaveChunk()
        val silenceBuffer = generateSilenceChunk()

        // 1. Speak sentence 1
        for (i in 0 until 24) {
            recorder.feedAudioData(speechBuffer, speechBuffer.size)
        }
        // 2. Pause
        for (i in 0 until 12) {
            recorder.feedAudioData(silenceBuffer, silenceBuffer.size)
        }

        // Verify chunk 1 was committed
        assertEquals("明日の天気は", committedText.toString())

        // 3. User taps Stop button
        val remainingSamples = recorder.stop()
        if (remainingSamples.isNotEmpty()) {
            // If any remaining samples were returned, simulate decoding them
            mockCommitText("明日の天気は")
        }

        // Must still be committed exactly ONCE without duplicate repetition!
        assertEquals(
            "Text must not be duplicated when stopping after a pause",
            "明日の天気は",
            committedText.toString()
        )
    }
}
