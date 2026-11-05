package com.example.myasr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Comprehensive Evaluation Test Suite for Japanese Punctuation Engine
 * and Continuous Sentence-Boundary Input Without Pauses.
 */
class JapanesePunctuationEvaluationTest {

    // =========================================================================
    // 1. Spoken Punctuation Commands (音声記号コマンド) Evaluation
    // =========================================================================

    @Test
    fun testSpokenPunctuationCommands() {
        val testCases = listOf(
            "お疲れ様でした まる" to "お疲れ様でした。",
            "お疲れ様でしたまる" to "お疲れ様でした。",
            "よろしくお願いします くてん" to "よろしくお願いします。",
            "よろしくお願いします句点" to "よろしくお願いします。",
            "さて てん 次の話題ですが" to "さて、次の話題ですが",
            "本日は晴天なり とうてん 風も穏やかです" to "本日は晴天なり、風も穏やかです。",
            "本日は晴天なり読点風も穏やかです" to "本日は晴天なり、風も穏やかです。",
            "明日の予定はどうですか はてな" to "明日の予定はどうですか？",
            "明日の予定はどうですか疑問符" to "明日の予定はどうですか？",
            "素晴らしい発表でした びっくりまーく" to "素晴らしい発表でした！",
            "素晴らしい発表でした感嘆符" to "素晴らしい発表でした！",
            "ここで一旦区切ります かいぎょう 新しい段落です" to "ここで一旦区切ります\n新しい段落です。",
            "第一項目改行第二項目" to "第一項目\n第二項目"
        )

        var passed = 0
        for ((input, expected) in testCases) {
            val result = JapanesePunctuationEngine.formatPunctuation(input)
            assertEquals("Failed on spoken command: '$input'", expected, result)
            passed++
        }
        assertEquals("All spoken punctuation commands must pass", testCases.size, passed)
    }

    // =========================================================================
    // 2. False-Positive Resistance Evaluation (語彙保護・誤変換防止)
    // =========================================================================

    @Test
    fun testFalsePositivePreservation() {
        val testCases = listOf(
            "明日の天気は晴れです" to "明日の天気は晴れです。",
            "天然水を飲む" to "天然水を飲む",
            "次の交差点を右折します" to "次の交差点を右折します。",
            "テストで満点を取りました" to "テストで満点を取りました。",
            "このプロジェクトの重点施策です" to "このプロジェクトの重点施策です。",
            "10点満点中8点でした" to "10点満点中8点でした。",
            "まるで夢のようです" to "まるで夢のようです。",
            "丸ごと美味しく食べる" to "丸ごと美味しく食べる",
            "丸いテーブルを囲む" to "丸いテーブルを囲む",
            "社員が一丸となって取り組みます" to "社員が一丸となって取り組みます。",
            "日の丸を掲げる" to "日の丸を掲げる",
            "ですから問題ありません" to "ですから、問題ありません。",
            "ですのでご安心ください" to "ですので、ご安心ください。",
            "ですが諦めません" to "ですが、諦めません。",
            "業績はますます向上しています" to "業績はますます向上しています。"
        )

        var passed = 0
        for ((input, expected) in testCases) {
            val result = JapanesePunctuationEngine.formatPunctuation(input)
            assertEquals("Failed on preserved word: '$input'", expected, result)
            passed++
        }
        assertEquals("All false-positive tests must pass", testCases.size, passed)
    }

    // =========================================================================
    // 3. Automatic Sentence-Ending Period Insertion (文末自動句点付与)
    // =========================================================================

    @Test
    fun testAutomaticSentenceEndingPeriods() {
        val testCases = listOf(
            // Copulas & Auxiliaries
            "今日は良い天気です" to "今日は良い天気です。",
            "確認が完了しました" to "確認が完了しました。",
            "特に問題はありません" to "特に問題はありません。",
            "明日は雨が降るでしょう" to "明日は雨が降るでしょう。",
            "これが事実である" to "これが事実である。",
            // Polite requests
            "至急ご連絡ください" to "至急ご連絡ください。",
            "確認をお願いします" to "確認をお願いします。",
            "ご検討のほどよろしくお願いいたします" to "ご検討のほどよろしくお願いいたします。",
            // Conversational closures
            "いつもありがとうございます" to "いつもありがとうございます。",
            "本日もお疲れ様でした" to "本日もお疲れ様でした。",
            "それでは失礼します" to "それでは失礼します。",
            // Sentence-final particles
            "とても楽しみですね" to "とても楽しみですね。",
            "絶対に成功させますよ" to "絶対に成功させますよ。",
            "これで準備完了だね" to "これで準備完了だね。"
        )

        var passed = 0
        for ((input, expected) in testCases) {
            val result = JapanesePunctuationEngine.formatPunctuation(input)
            assertEquals("Failed on automatic sentence ending: '$input'", expected, result)
            passed++
        }
        assertEquals("All sentence ending period tests must pass", testCases.size, passed)
    }

    // =========================================================================
    // 4. Automatic Clause Comma Insertion (節境界・接続詞自動読点付与)
    // =========================================================================

    @Test
    fun testAutomaticClauseCommas() {
        val testCases = listOf(
            "しかしこれは大変な作業です" to "しかし、これは大変な作業です。",
            "そして次のステップに進みます" to "そして、次のステップに進みます。",
            "また明日連絡します" to "また、明日連絡します。",
            "そのため対策が必要です" to "そのため、対策が必要です。",
            "まず結論から申し上げます" to "まず、結論から申し上げます。",
            "次に進捗状況を報告します" to "次に、進捗状況を報告します。",
            "最後に質問を受け付けます" to "最後に、質問を受け付けます。",
            "雨が降るので傘を持参します" to "雨が降るので、傘を持参します。",
            "時間があったら教えてください" to "時間があったら、教えてください。",
            "難しいけれど頑張ります" to "難しいけれど、頑張ります。"
        )

        var passed = 0
        for ((input, expected) in testCases) {
            val result = JapanesePunctuationEngine.formatPunctuation(input)
            assertEquals("Failed on clause comma: '$input'", expected, result)
            passed++
        }
        assertEquals("All clause comma tests must pass", testCases.size, passed)
    }

    // =========================================================================
    // 5. Continuous Multiple Sentences & Cleanup (連続文・重複記号クリーンアップ)
    // =========================================================================

    @Test
    fun testContinuousMultipleSentencesAndCleanup() {
        val input = "おはようございますまる本日は良い天気ですねまるよろしくおねがいします"
        val expected = "おはようございます。本日は良い天気ですね。よろしくおねがいします。"
        val result = JapanesePunctuationEngine.formatPunctuation(input)
        assertEquals(expected, result)

        // Redundant / duplicate punctuation cleanup
        assertEquals("完了です。", JapanesePunctuationEngine.formatPunctuation("完了です、、。。"))
        assertEquals("どうですか？", JapanesePunctuationEngine.formatPunctuation("どうですか？。"))
        assertEquals("すごい！", JapanesePunctuationEngine.formatPunctuation("すごい！。"))
        assertEquals("項目一\n項目二", JapanesePunctuationEngine.formatPunctuation("項目一、\n項目二"))
    }

    // =========================================================================
    // 6. Sentence Boundary Detection & Splitting (文末判定・分割)
    // =========================================================================

    @Test
    fun testSentenceBoundaryDetectionAndSplitting() {
        assertTrue(JapanesePunctuationEngine.isSentenceEnding("よろしくお願いします。"))
        assertTrue(JapanesePunctuationEngine.isSentenceEnding("どうでしょうか？"))
        assertTrue(JapanesePunctuationEngine.isSentenceEnding("素晴らしい！"))
        assertTrue(JapanesePunctuationEngine.isSentenceEnding("本日は晴天です"))
        assertTrue(JapanesePunctuationEngine.isSentenceEnding("確認しました"))
        assertTrue(JapanesePunctuationEngine.isSentenceEnding("ご協力ありがとうございます"))
        assertFalse(JapanesePunctuationEngine.isSentenceEnding("明日の会議について"))
        assertFalse(JapanesePunctuationEngine.isSentenceEnding("私の名前は"))

        // Progressively splitting completed sentences
        val (completed, inFlight) = JapanesePunctuationEngine.splitCompletedSentences("本日は晴天です。明日の天気について")
        assertEquals("本日は晴天です。", completed)
        assertEquals("明日の天気について", inFlight)
    }

    // =========================================================================
    // 7. Streaming VAD: Micro-Pause & Continuous Pacing (ポーズがなくても入力)
    // =========================================================================

    @Test
    fun testMicroPauseFlushesWithoutWaitingFullPause() {
        val capturedChunks = mutableListOf<FloatArray>()
        val vad = StreamingVadProcessor(
            sampleRate = 16000,
            speechThreshold = 0.02f,
            silenceThreshold = 0.01f,
            silenceDurationMs = 650L,
            microPauseDurationMs = 200L,
            minSentenceDurationMs = 800L,
            onSpeechChunkCaptured = { capturedChunks.add(it) }
        )

        val speechBuffer = ShortArray(1024) { 8000 }
        val silenceBuffer = ShortArray(1024) { 0 }

        // 1. Speak for 1000ms (~16 chunks of 1024 samples at 16kHz)
        for (i in 0 until 16) {
            vad.processSamples(speechBuffer, speechBuffer.size)
        }
        assertEquals(StreamingVadProcessor.State.SPEECH, vad.state)
        assertTrue("Speech ongoing, no chunk emitted yet", capturedChunks.isEmpty())

        // 2. Micro-breath / pause of only 250ms (~4 chunks of silence, far below 650ms full pause!)
        for (i in 0 until 4) {
            vad.processSamples(silenceBuffer, silenceBuffer.size)
        }

        // Must flush immediately without waiting for 650ms!
        assertEquals("Micro-pause at sentence boundary must flush immediately", 1, capturedChunks.size)
        assertEquals(StreamingVadProcessor.State.SILENCE, vad.state)
    }

    @Test
    fun testContinuousPacingFlushesWithoutAnyPause() {
        val capturedChunks = mutableListOf<FloatArray>()
        val vad = StreamingVadProcessor(
            sampleRate = 16000,
            speechThreshold = 0.02f,
            silenceThreshold = 0.01f,
            sentencePacingDurationMs = 2000L, // 2.0s continuous pacing
            onSpeechChunkCaptured = { capturedChunks.add(it) }
        )

        val speechBuffer = ShortArray(1024) { 8000 }

        // Continuous uninterrupted loud speech for 2500ms (~40 chunks) without ANY silence
        for (i in 0 until 40) {
            vad.processSamples(speechBuffer, speechBuffer.size)
        }

        // Must flush chunk at pacing threshold even without any pause!
        assertTrue("Continuous speech must flush chunk without requiring any pause", capturedChunks.size >= 1)
        assertEquals("VAD should remain in SPEECH state so following words are captured seamlessly",
            StreamingVadProcessor.State.SPEECH, vad.state)
    }
}
