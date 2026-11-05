package com.example.myasr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingPrefixRevisionEvaluationTest {

    @Test
    fun testStreamingModeBypassesPauseChunking() {
        val capturedChunks = mutableListOf<FloatArray>()
        var speechStateChanges = 0

        val vad = StreamingVadProcessor(
            sampleRate = 16000,
            speechThreshold = 0.02f,
            silenceThreshold = 0.01f,
            silenceDurationMs = 450L,
            microPauseDurationMs = 200L,
            onSpeechChunkCaptured = { capturedChunks.add(it) },
            onSpeechStateChanged = { speechStateChanges++ }
        )

        // Enable streaming mode: Pause chunking must be completely disabled!
        vad.isStreamingMode = true

        val speechBuffer = ShortArray(1024) { (Math.sin(it.toDouble() * 0.1) * 8000).toInt().toShort() }
        val silenceBuffer = ShortArray(1024) { 0 }

        // 1. Feed speech (1 second: ~16 chunks)
        for (i in 0 until 16) {
            vad.processSamples(speechBuffer, speechBuffer.size)
        }
        assertEquals("State should be SPEECH", StreamingVadProcessor.State.SPEECH, vad.state)
        assertEquals("No chunks should be captured during speech", 0, capturedChunks.size)

        // 2. Feed silence for 500ms (~8 chunks).
        // In legacy mode, this would trigger pause chunking and reset to SILENCE.
        // In streaming mode, pause chunking is bypassed!
        for (i in 0 until 8) {
            vad.processSamples(silenceBuffer, silenceBuffer.size)
        }

        assertEquals("In streaming mode, pause must NOT flush chunk", 0, capturedChunks.size)
        assertEquals("In streaming mode, state must remain SPEECH across pause", StreamingVadProcessor.State.SPEECH, vad.state)

        // 3. Continue speaking for another 1 second
        for (i in 0 until 16) {
            vad.processSamples(speechBuffer, speechBuffer.size)
        }

        assertEquals("Audio must remain contiguous without pause fragmentation", 0, capturedChunks.size)
    }

    @Test
    fun testLegacyModeFlushesOnMicroPauseWhenStreamingDisabled() {
        val capturedChunks = mutableListOf<FloatArray>()

        val vad = StreamingVadProcessor(
            sampleRate = 16000,
            speechThreshold = 0.02f,
            silenceThreshold = 0.01f,
            silenceDurationMs = 450L,
            microPauseDurationMs = 200L,
            minSentenceDurationMs = 800L,
            onSpeechChunkCaptured = { capturedChunks.add(it) }
        )

        // Streaming mode is OFF (Legacy Pause Input Mode)
        vad.isStreamingMode = false

        val speechBuffer = ShortArray(1024) { (Math.sin(it.toDouble() * 0.1) * 8000).toInt().toShort() }
        val silenceBuffer = ShortArray(1024) { 0 }

        // 1. Feed 1000ms speech (~16 chunks)
        for (i in 0 until 16) {
            vad.processSamples(speechBuffer, speechBuffer.size)
        }
        assertEquals(0, capturedChunks.size)

        // 2. Feed 250ms silence (~4 chunks) -> Should trigger micro-pause flush
        for (i in 0 until 4) {
            vad.processSamples(silenceBuffer, silenceBuffer.size)
        }

        assertEquals("Micro-pause must flush chunk when streaming mode is OFF", 1, capturedChunks.size)
        assertEquals("VAD must return to SILENCE on pause", StreamingVadProcessor.State.SILENCE, vad.state)
    }

    @Test
    fun testDynamicPrefixRevisionOnRightContext() {
        // Step 1: User says initial ambiguous morpheme ("今日はてん" -> "今日は、")
        val step1Raw = "今日はてん"
        val step1Formatted = JapanesePunctuationEngine.formatPunctuation(step1Raw)
        assertEquals("今日は、", step1Formatted)

        // Step 2: Right-context arrives, revising the ambiguous prefix
        val step2Raw = "今日は天気が良いです"
        val step2Formatted = JapanesePunctuationEngine.formatPunctuation(step2Raw)
        assertEquals("今日は天気が良いです。", step2Formatted)

        // The prefix "今日は、" was dynamically revised to "今日は天気が良いです。" without punctuation collision
        assertTrue("Prefix was dynamically corrected", step2Formatted.startsWith("今日は天気"))
    }

    @Test
    fun testRetroactivePunctuationRevisionOnRightContext() {
        // Step 1: User completes terminal copula "です", period is tentatively appended
        val step1Raw = "本日は晴れです"
        val step1Formatted = JapanesePunctuationEngine.formatPunctuation(step1Raw)
        assertEquals("本日は晴れです。", step1Formatted)

        // Step 2: User seamlessly continues without breath pause with conjunctive particle "から"
        // The tentative period '。' must be retroactively revised into a comma '、' as part of 'ですから、'
        val step2Raw = "本日は晴れですから洗濯物を干しましょう"
        val step2Formatted = JapanesePunctuationEngine.formatPunctuation(step2Raw)
        assertEquals("本日は晴れですから、洗濯物を干しましょう。", step2Formatted)

        assertFalse("Period after です must be revoked", step2Formatted.contains("です。"))
        assertTrue("Conjunctive comma must be retroactively inserted", step2Formatted.contains("ですから、"))
    }

    @Test
    fun testMultiSentenceHorizonSplitThreeSentences() {
        val threeSentences = "お疲れ様です。明日の打ち合わせは十時からです。よろしくお願いします。"

        // keepTrailingSentences = 1: Keep 1 trailing sentence in active Composing horizon, freeze 2 older sentences
        val (frozen, active) = JapanesePunctuationEngine.splitHorizonSentences(threeSentences, keepTrailingSentences = 1)

        assertEquals("お疲れ様です。明日の打ち合わせは十時からです。", frozen)
        assertEquals("よろしくお願いします。", active)

        // Recombined output exactly matches original multi-sentence input without duplication or loss
        assertEquals(threeSentences, frozen + active)
    }

    @Test
    fun testMultiSentenceHorizonSplitWithQuestionAndAnswer() {
        val qa = "これで大丈夫でしょうか。問題ありません。"

        // With 2 completed sentences and keepTrailingSentences = 1:
        // First sentence is frozen for commit, second sentence remains in active horizon
        val (frozen, active) = JapanesePunctuationEngine.splitHorizonSentences(qa, keepTrailingSentences = 1)

        assertEquals("これで大丈夫でしょうか。", frozen)
        assertEquals("問題ありません。", active)
    }

    @Test
    fun testSpokenCommandsInStreamingMultiSentence() {
        val rawInput = "東京に出張しますまる新幹線の予約をお願いしますまる"
        val formatted = JapanesePunctuationEngine.formatPunctuation(rawInput)

        assertEquals("東京に出張します。新幹線の予約をお願いします。", formatted)

        val (frozen, active) = JapanesePunctuationEngine.splitHorizonSentences(formatted, keepTrailingSentences = 1)
        assertEquals("東京に出張します。", frozen)
        assertEquals("新幹線の予約をお願いします。", active)
    }

    @Test
    fun testAudioRecorderStreamingModeToggle() {
        val recorder = AudioRecorder()
        assertFalse(recorder.isStreamingMode)

        recorder.isStreamingMode = true
        assertTrue(recorder.isStreamingMode)
        assertTrue(recorder.vadProcessor.isStreamingMode)

        recorder.isStreamingMode = false
        assertFalse(recorder.isStreamingMode)
        assertFalse(recorder.vadProcessor.isStreamingMode)
    }
}
