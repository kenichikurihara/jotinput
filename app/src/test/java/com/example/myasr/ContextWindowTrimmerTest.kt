package com.example.myasr

import com.example.myasr.context.reranking.domain.ContextWindowTrimmer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextWindowTrimmerTest {

    @Test
    fun testEstimateTokenCountReflectsLanguageDensity() {
        // English ASCII text: 42 characters should estimate ~10 tokens
        val enText = "Thank you for your email regarding this."
        val enTokens = ContextWindowTrimmer.estimateTokenCount(enText)
        assertTrue("English text should estimate ~10 tokens, got $enTokens", enTokens in 8.0f..12.0f)

        // Japanese CJK text: 13 characters should estimate ~10 tokens
        val jaText = "昨日の会議の議事録を確認する。"
        val jaTokens = ContextWindowTrimmer.estimateTokenCount(jaText)
        assertTrue("Japanese text should estimate ~10 tokens, got $jaTokens", jaTokens in 9.0f..13.0f)
    }

    @Test
    fun testEnglishSentenceTerminalAlignment() {
        // Tier 1: Snap to sentence boundary after period
        val text = "Good morning! The weather is very nice today. Regarding the upcoming project, I "
        val trimmed = ContextWindowTrimmer.trimToOptimalContext(text, targetTokens = 12)

        // Must snap cleanly to sentence start
        assertEquals("Regarding the upcoming project, I ", trimmed)
        assertFalse("Must not start with whitespace", trimmed.startsWith(" "))
        assertTrue("Must preserve trailing space for space-adaptive BPE", trimmed.endsWith(" "))
    }

    @Test
    fun testEnglishAvoidsAbbreviationAsSentenceTerminal() {
        // "Mr." and "Dr." must not be treated as sentence terminals
        val text = "We received a detailed proposal from Dr. Smith regarding the timeline. I "
        val trimmed = ContextWindowTrimmer.trimToOptimalContext(text, targetTokens = 12)

        // Must not cut after "Dr."
        assertFalse("Must not cut immediately after 'Dr.'", trimmed.startsWith("Smith regarding"))
        // It should either keep from start or from a safe word boundary
        assertTrue(trimmed.endsWith("I "))
    }

    @Test
    fun testEnglishNeverCutsInsideWord() {
        // Long continuous English text without punctuation
        val text = "we received an important communication from human resources regarding the upcoming conference and I "
        val trimmed = ContextWindowTrimmer.trimToOptimalContext(text, targetTokens = 10)

        // Must never start with a fragment like "garding" or "ference"
        assertFalse(trimmed.startsWith("garding"))
        assertFalse(trimmed.startsWith("rding"))
        assertFalse(trimmed.startsWith("ference"))
        // First word must be a complete word
        val firstWord = trimmed.split(" ").first()
        assertTrue("First word must be complete, got '$firstWord'", firstWord in listOf("regarding", "resources", "human", "from", "the", "upcoming", "conference"))
    }

    @Test
    fun testEnglishNeverCutsInsideClitics() {
        val text = "I told them that they shouldn't worry about the results because I "
        val trimmed = ContextWindowTrimmer.trimToOptimalContext(text, targetTokens = 10)

        assertFalse("Must not start with orphan clitic 't", trimmed.startsWith("'t"))
        assertFalse("Must not start with orphan clitic n't", trimmed.startsWith("n't"))
    }

    @Test
    fun testJapaneseSentenceTerminalAlignment() {
        // Tier 1: Snap to Japanese period
        val text = "お疲れ様です。本日のチームミーティングの議事録をまとめました。明日の会議の"
        val trimmed = ContextWindowTrimmer.trimToOptimalContext(text, targetTokens = 10)

        assertEquals("明日の会議の", trimmed)
    }

    @Test
    fun testJapaneseClauseDelimiterAlignment() {
        // Tier 2: Snap to Japanese comma
        val text = "四半期の業績報告書を役員会に提出し、来期に向けた新規事業の"
        val trimmed = ContextWindowTrimmer.trimToOptimalContext(text, targetTokens = 12)

        assertEquals("来期に向けた新規事業の", trimmed)
    }

    @Test
    fun testJapaneseBunsetsuOnsetNeverCutsInsideKanjiCompound() {
        // Tier 3: Bunsetsu boundary from Hiragana particle to Kanji/Katakana content word
        val text = "昨日チーム全体で詳細に議論された今後の新しい販売戦略の"
        val trimmed = ContextWindowTrimmer.trimToOptimalContext(text, targetTokens = 10)

        // Must NOT start with compound fragment "売戦略の"
        assertFalse("Must not cut inside compound '販売戦略'", trimmed.startsWith("売戦略"))
        // Must NOT start with orphan auxiliary "された"
        assertFalse("Must not start with orphan auxiliary 'された'", trimmed.startsWith("された"))

        // Should cleanly onset at "新しい販売戦略の" or "販売戦略の" or "今後の"
        assertTrue(
            "Should onset at content word boundary, got '$trimmed'",
            trimmed.startsWith("新しい販売戦略の") || trimmed.startsWith("販売戦略の") || trimmed.startsWith("今後の")
        )
    }

    @Test
    fun testJapaneseKatakanaRunNeverCut() {
        val text = "大手自動車メーカー各社が共同で推進する大規模なプロジェクトの"
        val trimmed = ContextWindowTrimmer.trimToOptimalContext(text, targetTokens = 10)

        assertFalse("Must not cut inside Katakana word 'プロジェクト'", trimmed.startsWith("ジェクト"))
        assertFalse("Must not cut inside Katakana word 'プロジェクト'", trimmed.startsWith("ロジェクト"))
    }

    @Test
    fun testBilingualCodeSwitching() {
        val text = "GitHubの新しいレポジトリに最新の変更をpushしました。チームメンバー全員でプルリクエストのreviewを"
        val trimmed = ContextWindowTrimmer.trimToOptimalContext(text, targetTokens = 14)

        // Must snap cleanly to sentence terminal or clause
        assertTrue(
            "Must preserve bilingual integrity, got '$trimmed'",
            trimmed.startsWith("チームメンバー全員で") || trimmed.startsWith("プルリクエストの")
        )
        assertTrue(trimmed.endsWith("reviewを"))
    }

    @Test
    fun testSurrogatePairSafety() {
        val text = "お疲れ様です！😀🎉本日の進捗状況の報告ですが、"
        val trimmed = ContextWindowTrimmer.trimToOptimalContext(text, targetTokens = 10)

        // Should not crash and should produce valid string
        assertTrue(trimmed.isNotEmpty())
        assertTrue(trimmed.endsWith("報告ですが、"))
    }
}
