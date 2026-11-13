package com.example.myasr

import com.example.myasr.context.speech.domain.ContextAwareProcessor
import com.example.myasr.context.vocabulary.domain.DictionaryEntry
import com.example.myasr.context.vocabulary.domain.Priority

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextAwareProcessorTest {

    private val processor = ContextAwareProcessor()

    @Test
    fun testKanjiVariantResolutionWithContext() {
        val entries = listOf(
            DictionaryEntry(word = "渡邊", reading = "わたなべ", priority = Priority.HIGH)
        )

        // Baseline ASR output with common variant
        val rawAsr = "来週から渡辺さんがプロジェクトリーダーを務めます"

        // Without relevant context, or with person cue in context
        val context = "お疲れ様です。以前ご一緒した渡邊さんの件ですが、"
        val resolved = processor.process(rawAsr, context, entries)

        assertEquals("来週から渡邊さんがプロジェクトリーダーを務めます。", resolved)
    }

    @Test
    fun testSemanticHomophoneDisambiguation() {
        val entries = listOf(
            DictionaryEntry(word = "貴社", reading = "きしゃ", priority = Priority.HIGH),
            DictionaryEntry(word = "記者", reading = "きしゃ", priority = Priority.NORMAL),
            DictionaryEntry(word = "化学", reading = "かがく", priority = Priority.HIGH)
        )

        // 1. Business context -> resolves to 貴社
        val businessContext = "平素は大変お世話になっております。今後のご発展をお祈り申し上げます。"
        val raw1 = "きしゃの皆様のご活躍を期待しております"
        val out1 = processor.process(raw1, businessContext, entries)
        assertTrue(out1.contains("貴社"))

        // 2. News/media context -> resolves to 記者
        val newsContext = "明日の新聞朝刊に掲載予定の記事について取材を行いました。"
        val raw2 = "きしゃクラブにて会見を開きます"
        val out2 = processor.process(raw2, newsContext, entries)
        assertTrue(out2.contains("記者"))

        // 3. Chemistry lab context -> resolves to 化学
        val labContext = "実験室にて新素材の高分子化合物を合成しました。"
        val raw3 = "かがく反応の測定結果です"
        val out3 = processor.process(raw3, labContext, entries)
        assertTrue(out3.contains("化学"))
    }

    @Test
    fun testSmartSpacingBetweenLatinWords() {
        val entries = listOf(
            DictionaryEntry(word = "ReazonSpeech", reading = "れいぞんすぴーち", priority = Priority.HIGH)
        )

        val context = "Android"
        val raw = "reazonspeech"
        val out = processor.process(raw, context, entries)

        // Should automatically insert a space between Latin words
        assertEquals(" ReazonSpeech", out)
    }
}
