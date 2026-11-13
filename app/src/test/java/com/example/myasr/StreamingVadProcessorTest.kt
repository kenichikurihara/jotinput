package com.example.myasr

import com.example.myasr.context.speech.domain.StreamingVadProcessor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingVadProcessorTest {

    @Test
    fun testSilenceDoesNotTriggerSpeech() {
        var chunkCaptured = false
        var speechState = false
        val vad = StreamingVadProcessor(
            sampleRate = 16000,
            onSpeechChunkCaptured = { chunkCaptured = true },
            onSpeechStateChanged = { speechState = it }
        )

        // Feed 1 second of silence (zero amplitude)
        val silenceChunk = ShortArray(1024) { 0 }
        for (i in 0 until 16) {
            vad.processSamples(silenceChunk, silenceChunk.size)
        }

        assertFalse("Silence should not trigger speech chunk", chunkCaptured)
        assertFalse("State should remain silence", speechState)
        assertEquals(StreamingVadProcessor.State.SILENCE, vad.state)
    }

    @Test
    fun testSpeechOnsetAndPauseDetection() {
        val capturedChunks = mutableListOf<FloatArray>()
        val stateChanges = mutableListOf<Boolean>()

        val vad = StreamingVadProcessor(
            sampleRate = 16000,
            speechThreshold = 0.02f,
            silenceThreshold = 0.01f,
            silenceDurationMs = 500L,
            minSpeechDurationMs = 200L,
            onSpeechChunkCaptured = { capturedChunks.add(it) },
            onSpeechStateChanged = { stateChanges.add(it) }
        )

        val silenceBuffer = ShortArray(1024) { 0 }
        // Loud sine wave buffer (~0.2 amplitude: ~6500 short value)
        val speechBuffer = ShortArray(1024) { idx ->
            (Math.sin(2.0 * Math.PI * 440.0 * idx / 16000.0) * 8000).toInt().toShort()
        }

        // 1. Initial silence (300ms: ~5 chunks)
        for (i in 0 until 5) {
            vad.processSamples(silenceBuffer, silenceBuffer.size)
        }
        assertEquals(StreamingVadProcessor.State.SILENCE, vad.state)

        // 2. Speech onset: 1.5 seconds of loud speech (~24 chunks)
        for (i in 0 until 24) {
            vad.processSamples(speechBuffer, speechBuffer.size)
        }
        assertEquals(StreamingVadProcessor.State.SPEECH, vad.state)
        assertTrue("Speech state change to true should have been recorded", stateChanges.contains(true))
        assertTrue("Speech is still ongoing, so chunk should not be emitted yet", capturedChunks.isEmpty())

        // 3. Pause: 600ms of silence (~10 chunks, exceeds 500ms threshold)
        for (i in 0 until 10) {
            vad.processSamples(silenceBuffer, silenceBuffer.size)
        }

        // Speech chunk should now be captured!
        assertEquals("Exactly one speech chunk should be captured on pause", 1, capturedChunks.size)
        val captured = capturedChunks.first()
        assertTrue("Captured samples should contain speech audio (> 1.0s)", captured.size > 16000)
        assertEquals(StreamingVadProcessor.State.SILENCE, vad.state)
        assertTrue("Speech state change to false should have been recorded", stateChanges.contains(false))
    }

    @Test
    fun testTypingSuppressionPreventsKeystrokeAcousticFalseStart() {
        val capturedChunks = mutableListOf<FloatArray>()
        val vad = StreamingVadProcessor(
            sampleRate = 16000,
            speechThreshold = 0.02f,
            onSpeechChunkCaptured = { capturedChunks.add(it) }
        )

        val loudChunk = ShortArray(1024) { 8000 }

        // User taps a key -> notify typing
        vad.notifyUserTyping(durationMs = 500L)
        assertTrue(vad.isTypingSuppressed())

        // Loud typing impulse arrives while typing is suppressed
        vad.processSamples(loudChunk, loudChunk.size)
        vad.processSamples(loudChunk, loudChunk.size)

        // Should NOT transition to speech because typing is suppressed
        assertEquals(StreamingVadProcessor.State.SILENCE, vad.state)
        assertTrue(capturedChunks.isEmpty())
    }

    @Test
    fun testShortAcousticGlitchRejected() {
        val capturedChunks = mutableListOf<FloatArray>()
        val vad = StreamingVadProcessor(
            sampleRate = 16000,
            silenceDurationMs = 200L,
            minSpeechDurationMs = 500L, // require at least 500ms
            onSpeechChunkCaptured = { capturedChunks.add(it) }
        )

        val loudChunk = ShortArray(1024) { 8000 }
        val silenceChunk = ShortArray(1024) { 0 }

        // Feed only 2 loud chunks (~128ms)
        vad.processSamples(loudChunk, loudChunk.size)
        vad.processSamples(loudChunk, loudChunk.size)

        // Immediately followed by silence
        for (i in 0 until 5) {
            vad.processSamples(silenceChunk, silenceChunk.size)
        }

        // Because duration was < 500ms, it should be dropped as glitch noise
        assertTrue("Short glitch should be rejected", capturedChunks.isEmpty())
    }

    @Test
    fun testAmbientNoiseFloorTrackingAndPauseDetection() {
        val capturedChunks = mutableListOf<FloatArray>()
        val vad = StreamingVadProcessor(
            sampleRate = 16000,
            silenceDurationMs = 450L,
            onSpeechChunkCaptured = { capturedChunks.add(it) }
        )

        // Realistic room ambient noise ~0.022f (short value ~720)
        val ambientNoiseChunk = ShortArray(1024) { 720 }
        // Conversational speech ~0.15f (short value ~5000)
        val speechChunk = ShortArray(1024) { idx ->
            (Math.sin(2.0 * Math.PI * 300.0 * idx / 16000.0) * 5000).toInt().toShort()
        }

        // 1. Initial ambient noise for 500ms (~8 chunks)
        for (i in 0 until 8) {
            vad.processSamples(ambientNoiseChunk, ambientNoiseChunk.size)
        }
        // Ambient noise should NOT trigger speech!
        assertEquals("Ambient noise must not trigger speech", StreamingVadProcessor.State.SILENCE, vad.state)
        assertTrue("No chunks emitted during ambient noise", capturedChunks.isEmpty())
        assertTrue("Noise floor should adapt towards ambient", vad.noiseFloor >= 0.015f)

        // 2. Speech for 1000ms (~16 chunks)
        for (i in 0 until 16) {
            vad.processSamples(speechChunk, speechChunk.size)
        }
        assertEquals("Speech should transition to SPEECH state", StreamingVadProcessor.State.SPEECH, vad.state)

        // 3. User pauses: ambient noise continues for 500ms (~8 chunks, >= 450ms silenceDurationMs)
        for (i in 0 until 8) {
            vad.processSamples(ambientNoiseChunk, ambientNoiseChunk.size)
        }

        // Pause should be detected despite ambient noise!
        assertEquals("State should return to SILENCE on pause", StreamingVadProcessor.State.SILENCE, vad.state)
        assertEquals("Speech chunk should be captured on pause in presence of ambient noise", 1, capturedChunks.size)
    }

    @Test
    fun testMicroPauseDetectionAtSentenceBoundary() {
        val capturedChunks = mutableListOf<FloatArray>()
        val vad = StreamingVadProcessor(
            sampleRate = 16000,
            silenceDurationMs = 450L,
            microPauseDurationMs = 200L,
            minSentenceDurationMs = 800L,
            onSpeechChunkCaptured = { capturedChunks.add(it) }
        )

        val silenceChunk = ShortArray(1024) { 0 }
        val speechChunk = ShortArray(1024) { idx ->
            (Math.sin(2.0 * Math.PI * 300.0 * idx / 16000.0) * 6000).toInt().toShort()
        }

        // Speech for 1200ms (~19 chunks > minSentenceDurationMs of 800ms)
        for (i in 0 until 19) {
            vad.processSamples(speechChunk, speechChunk.size)
        }
        assertEquals(StreamingVadProcessor.State.SPEECH, vad.state)
        assertTrue(capturedChunks.isEmpty())

        // Micro-pause: 250ms of silence (~4 chunks, >= microPauseDurationMs of 200ms)
        for (i in 0 until 4) {
            vad.processSamples(silenceChunk, silenceChunk.size)
        }

        // Micro-pause should trigger flush immediately without waiting for full 450ms!
        assertEquals("Micro-pause must flush speech chunk", 1, capturedChunks.size)
        assertEquals(StreamingVadProcessor.State.SILENCE, vad.state)
    }

    @Test
    fun testReproductionConversationalSpeechWithAmbientNoiseAndMultiSentencePause() {
        val capturedChunks = mutableListOf<FloatArray>()
        val stateChanges = mutableListOf<Boolean>()
        val vad = StreamingVadProcessor(
            sampleRate = 16000,
            silenceDurationMs = 450L,
            microPauseDurationMs = 200L,
            minSentenceDurationMs = 800L,
            onSpeechChunkCaptured = { capturedChunks.add(it) },
            onSpeechStateChanged = { stateChanges.add(it) }
        )

        // Ambient noise on Pixel 10 Pro: ~0.022f (short amplitude ~720)
        val ambientChunk = ShortArray(1024) { 720 }
        // Conversational/soft speech at typing distance: ~0.048f (short amplitude ~2500)
        val speechChunk = ShortArray(1024) { idx ->
            (Math.sin(2.0 * Math.PI * 300.0 * idx / 16000.0) * 2500).toInt().toShort()
        }

        // 1. Initial ambient noise: 1.28 seconds (20 chunks)
        for (i in 0 until 20) {
            vad.processSamples(ambientChunk, ambientChunk.size)
        }
        assertEquals("Must remain SILENCE during ambient noise", StreamingVadProcessor.State.SILENCE, vad.state)
        assertTrue("No chunks emitted during initial ambient noise", capturedChunks.isEmpty())

        // 2. Utterance 1: 1.92 seconds (30 chunks)
        for (i in 0 until 30) {
            vad.processSamples(speechChunk, speechChunk.size)
        }
        assertEquals("Must detect speech onset for conversational voice", StreamingVadProcessor.State.SPEECH, vad.state)

        // 3. Pause 1 (ambient noise continues): 0.64 seconds (10 chunks >= 200ms micro-pause)
        for (i in 0 until 10) {
            vad.processSamples(ambientChunk, ambientChunk.size)
        }
        assertEquals("Must return to SILENCE on pause", StreamingVadProcessor.State.SILENCE, vad.state)
        assertEquals("Utterance 1 must be emitted on pause", 1, capturedChunks.size)

        // 4. Utterance 2: 1.92 seconds (30 chunks)
        for (i in 0 until 30) {
            vad.processSamples(speechChunk, speechChunk.size)
        }
        assertEquals("Must detect speech onset for utterance 2", StreamingVadProcessor.State.SPEECH, vad.state)

        // 5. Pause 2 (ambient noise continues): 0.64 seconds (10 chunks)
        for (i in 0 until 10) {
            vad.processSamples(ambientChunk, ambientChunk.size)
        }
        assertEquals("Must return to SILENCE on pause 2", StreamingVadProcessor.State.SILENCE, vad.state)
        assertEquals("Utterance 2 must be emitted on pause 2", 2, capturedChunks.size)
    }

    @Test
    fun testRealWorldWavFilesWithConversationalPauses() {
        val wav1 = java.io.File("../eval/audio/item_0001.wav").let { if (it.exists()) it else java.io.File("eval/audio/item_0001.wav") }
        val wav2 = java.io.File("../eval/audio/item_0002.wav").let { if (it.exists()) it else java.io.File("eval/audio/item_0002.wav") }
        if (!wav1.exists() || !wav2.exists()) {
            return // Skip if running in environment without eval dataset
        }

        fun readWavPcm(file: java.io.File): ShortArray {
            val bytes = file.readBytes()
            val sampleCount = (bytes.size - 44) / 2
            val shorts = ShortArray(sampleCount)
            val buffer = java.nio.ByteBuffer.wrap(bytes, 44, sampleCount * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until sampleCount) {
                shorts[i] = buffer.short
            }
            return shorts
        }

        val d1 = readWavPcm(wav1)
        val d2 = readWavPcm(wav2)

        val capturedChunks = mutableListOf<FloatArray>()
        val vad = StreamingVadProcessor(
            sampleRate = 16000,
            silenceDurationMs = 450L,
            microPauseDurationMs = 200L,
            minSentenceDurationMs = 800L,
            onSpeechChunkCaptured = { capturedChunks.add(it) }
        )

        // Feed pre-silence (1s)
        val silenceChunk = ShortArray(1024) { 0 }
        for (i in 0 until 16) {
            vad.processSamples(silenceChunk, 1024)
        }
        assertEquals("Pre-silence should remain in SILENCE", StreamingVadProcessor.State.SILENCE, vad.state)

        // Feed speech 1
        var offset = 0
        while (offset + 1024 <= d1.size) {
            val chunk = d1.copyOfRange(offset, offset + 1024)
            vad.processSamples(chunk, 1024)
            offset += 1024
        }

        // Feed pause between sentences (1s)
        for (i in 0 until 16) {
            vad.processSamples(silenceChunk, 1024)
        }
        assertEquals("First sentence must be captured on pause", 1, capturedChunks.size)
        assertEquals("State must return to SILENCE", StreamingVadProcessor.State.SILENCE, vad.state)

        // Feed speech 2
        offset = 0
        while (offset + 1024 <= d2.size) {
            val chunk = d2.copyOfRange(offset, offset + 1024)
            vad.processSamples(chunk, 1024)
            offset += 1024
        }

        // Feed post-pause (1s)
        for (i in 0 until 16) {
            vad.processSamples(silenceChunk, 1024)
        }
        assertEquals("Second sentence must be captured on pause", 2, capturedChunks.size)
        assertEquals("State must return to SILENCE", StreamingVadProcessor.State.SILENCE, vad.state)
    }

    @Test
    fun testContinuousSpeechWithAgcGainDriftDoesNotFreeze() {
        val capturedChunks = mutableListOf<FloatArray>()
        val vad = StreamingVadProcessor(
            sampleRate = 16000,
            silenceDurationMs = 450L,
            microPauseDurationMs = 200L,
            minSentenceDurationMs = 800L,
            onSpeechChunkCaptured = { capturedChunks.add(it) }
        )

        // Initial ambient noise ~0.018f (short 600)
        val ambientLow = ShortArray(1024) { 600 }
        // AGC drifted ambient noise ~0.026f (short 850)
        val ambientHigh = ShortArray(1024) { 850 }
        // Conversational speech ~0.065f (short 3400)
        val speechChunk = ShortArray(1024) { idx ->
            (Math.sin(2.0 * Math.PI * 300.0 * idx / 16000.0) * 3400).toInt().toShort()
        }

        // Simulate 5 continuous sentences
        for (sentence in 1..5) {
            val ambient = if (sentence <= 2) ambientLow else ambientHigh

            // 1. Pre-speech ambient pause (400ms = 6 chunks)
            for (i in 0 until 6) {
                vad.processSamples(ambient, ambient.size)
            }

            // 2. Speech (1.6s = 25 chunks)
            for (i in 0 until 25) {
                vad.processSamples(speechChunk, speechChunk.size)
            }
            assertEquals("Sentence $sentence must reach SPEECH state", StreamingVadProcessor.State.SPEECH, vad.state)

            // 3. Pause (600ms = 10 chunks)
            for (i in 0 until 10) {
                vad.processSamples(ambient, ambient.size)
            }
            assertEquals("Sentence $sentence must return to SILENCE on pause", StreamingVadProcessor.State.SILENCE, vad.state)
            assertEquals("Chunk count after sentence $sentence", sentence, capturedChunks.size)
        }

        assertEquals("All 5 continuous sentences must be delivered without freezing", 5, capturedChunks.size)
    }
}
