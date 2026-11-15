package com.example.myasr.context.speech.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceCorrectionDetectorTest {

    @Test
    fun `detects Japanese surname variant with CJK Compatibility Ideograph and expands boundary`() {
        val result = VoiceCorrectionDetector.detect(
            beforeText = "明日の会議は山崎さんが担当です。",
            afterText = "明日の会議は山﨑さんが担当です。",
            language = AsrLanguage.JAPANESE,
            elapsedSinceVoiceCommitMs = 3_000L,
            typedReadingHint = "やまざき",
            readingResolver = { "やまざき" }
        )
        assertNotNull(result)
        assertEquals("山崎", result?.originalSurface)
        assertEquals("山﨑", result?.correctedSurface)
        assertEquals("やまざき", result?.normalizedReading)
        assertEquals(CorrectionCategory.VALID_EXACT_HOMOPHONE, result?.category)
    }

    @Test
    fun `detects Japanese homophone with honorific suffix trimming`() {
        val result = VoiceCorrectionDetector.detect(
            beforeText = "斎藤先生に確認します。",
            afterText = "斉藤先生に確認します。",
            language = AsrLanguage.JAPANESE,
            elapsedSinceVoiceCommitMs = 2_500L,
            typedReadingHint = "さいとう"
        )
        assertNotNull(result)
        assertEquals("斎藤", result?.originalSurface)
        assertEquals("斉藤", result?.correctedSurface)
    }

    @Test
    fun `detects Japanese mixed script GraphQL phonetic near miss without stripping trailing L`() {
        val result = VoiceCorrectionDetector.detect(
            beforeText = "グラフ九Lで実装しました。",
            afterText = "GraphQLで実装しました。",
            language = AsrLanguage.JAPANESE,
            elapsedSinceVoiceCommitMs = 4_000L,
            readingResolver = { if (it == "グラフ九L") "ぐらふきゅうえる" else "ぐらふきゅーえる" }
        )
        assertNotNull(result)
        assertEquals("グラフ九L", result?.originalSurface)
        assertEquals("GraphQL", result?.correctedSurface)
    }

    @Test
    fun `detects English phonetic near miss and abbreviation subsequence`() {
        val rightWrite = VoiceCorrectionDetector.detect(
            beforeText = "Please right the report now.",
            afterText = "Please write the report now.",
            language = AsrLanguage.ENGLISH,
            elapsedSinceVoiceCommitMs = 2_000L
        )
        assertNotNull(rightWrite)
        assertEquals("right", rightWrite?.originalSurface)
        assertEquals("write", rightWrite?.correctedSurface)

        val kubectl = VoiceCorrectionDetector.detect(
            beforeText = "Run kuber control apply now.",
            afterText = "Run kubectl apply now.",
            language = AsrLanguage.ENGLISH,
            elapsedSinceVoiceCommitMs = 2_500L
        )
        assertNotNull(kubectl)
        assertEquals("kuber control", kubectl?.originalSurface)
        assertEquals("kubectl", kubectl?.correctedSurface)
    }

    @Test
    fun `rejects pure continuation and semantic rewrites`() {
        val continuation = VoiceCorrectionDetector.detect(
            beforeText = "明日の会議",
            afterText = "明日の会議は午後三時からです。",
            language = AsrLanguage.JAPANESE,
            elapsedSinceVoiceCommitMs = 1_000L
        )
        assertNull(continuation)

        val semanticJp = VoiceCorrectionDetector.detect(
            beforeText = "今日はとても暑いですね。",
            afterText = "今日はかなり涼しいですね。",
            language = AsrLanguage.JAPANESE,
            elapsedSinceVoiceCommitMs = 3_000L,
            readingResolver = { if (it.contains("暑")) "とてもあつい" else "かなりすずしい" }
        )
        assertNull(semanticJp)

        val semanticEn = VoiceCorrectionDetector.detect(
            beforeText = "The meeting is tomorrow.",
            afterText = "The conference is cancelled.",
            language = AsrLanguage.ENGLISH,
            elapsedSinceVoiceCommitMs = 3_000L
        )
        assertNull(semanticEn)
    }

    @Test
    fun `ignores edits outside 15s edit window`() {
        val late = VoiceCorrectionDetector.detect(
            beforeText = "山崎さん",
            afterText = "山﨑さん",
            language = AsrLanguage.JAPANESE,
            elapsedSinceVoiceCommitMs = 20_000L,
            typedReadingHint = "やまざき"
        )
        assertNull(late)
    }

    @Test
    fun `detects inflected kun-yomi verb correction 描いて to 書いて even without readingResolver`() {
        val result = VoiceCorrectionDetector.detect(
            beforeText = "デザインドキュメントを描いて",
            afterText = "デザインドキュメントを書いて",
            language = AsrLanguage.JAPANESE,
            elapsedSinceVoiceCommitMs = 3_500L,
            typedReadingHint = "かいて"
        )
        assertNotNull(result)
        assertEquals("描いて", result?.originalSurface)
        assertEquals("書いて", result?.correctedSurface)
        assertEquals("かいて", result?.normalizedReading)
    }
}
