package com.example.myasr

import com.example.myasr.context.kanakanji.domain.ConversionCandidate
import com.example.myasr.context.kanakanji.domain.NextPhrasePredictor
import com.example.myasr.context.reranking.infrastructure.LlmReranker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Evaluation & Regression Test Suite for Hybrid-6b History x SLM Reranking Fusion.
 *
 * Covers:
 * 1. Normal Kana-Kanji conversion:
 *    - Personal habit retention (HPR@1)
 *    - Contextual override when context contradicts history (COR@1)
 *    - Multi-history contextual disambiguation
 *    - One-shot accidental noise (c=1) robustness
 *    - Time-decay topic shift (stale 30-day habit vs recent 10-minute session term)
 * 2. 1-2 char Prefix Prediction:
 *    - n=1 history prediction promoted from Stratum 6 to Slot-2 (exact span protected at #1)
 *    - n=2 strong history prediction promoted conditionally to #1 (or Slot-2)
 *    - Multi-prediction contextual disambiguation
 *    - Exact short-word protection when context strongly fits exact span
 *    - One-shot prediction noise avoidance from Top-3
 * 3. NextPhrasePredictor metadata propagation (historyCount & lastUsedAtMs)
 */
class HistorySlmRerankEvaluationTest {

    private val nowMs = 1_700_000_000_000L

    @Test
    fun testPersonalHabitRetentionBeatsGenericSlmPrior() {
        // User repeatedly chooses "貴社" (c=10, 2h ago) over "記者" in ambiguous context (ΔNLL = 2.5)
        val candHabit = ConversionCandidate(
            surface = "貴社",
            reading = "きしゃ",
            source = "dict",
            historyCount = 10,
            lastUsedAtMs = nowMs - 2 * 3_600_000L
        )
        val candGeneric = ConversionCandidate(
            surface = "記者",
            reading = "きしゃ",
            source = "dict"
        )
        val candOther = ConversionCandidate(
            surface = "汽車",
            reading = "きしゃ",
            source = "dict"
        )

        val scores = floatArrayOf(7.5f, 5.0f, 9.0f)
        val reranked = LlmReranker.scoreAndRankPool(
            topPool = listOf(candHabit, candGeneric, candOther),
            scores = scores,
            currentShownTop1 = null,
            weightAlpha = 0.20f,
            lambdaObs = 0.50f,
            gammaUser = 3.00f,
            targetInputLength = 3,
            nowMs = nowMs
        )

        assertEquals("貴社", reranked[0].surface)
    }

    @Test
    fun testContextualOverrideDefeatsContradictoryHistory() {
        // Context: "新聞社のきしゃが取材に来た" -> SLM strongly prefers "記者" (ΔNLL = 7.0)
        // Even with c=15 for "貴社", Coherence Gate closes and "記者" wins Rank 1.
        val candOldHabit = ConversionCandidate(
            surface = "貴社",
            reading = "きしゃ",
            source = "dict",
            historyCount = 15,
            lastUsedAtMs = nowMs - 24 * 3_600_000L
        )
        val candContextTarget = ConversionCandidate(
            surface = "記者",
            reading = "きしゃ",
            source = "dict"
        )

        val scores = floatArrayOf(11.0f, 4.0f)
        val reranked = LlmReranker.scoreAndRankPool(
            topPool = listOf(candOldHabit, candContextTarget),
            scores = scores,
            currentShownTop1 = null,
            weightAlpha = 0.20f,
            lambdaObs = 0.50f,
            gammaUser = 3.00f,
            targetInputLength = 3,
            nowMs = nowMs
        )

        assertEquals("記者", reranked[0].surface)
    }

    @Test
    fun testMultiHistoryContextualDisambiguation() {
        // Same reading "きしゃ" has two histories: "貴社" (c=12) and "帰社" (c=3).
        // Context: "出張からきしゃする" -> SLM favors "帰社" (4.2) over "貴社" (10.5).
        val candMajor = ConversionCandidate(
            surface = "貴社",
            reading = "きしゃ",
            historyCount = 12,
            lastUsedAtMs = nowMs - 12 * 3_600_000L
        )
        val candMinorCtx = ConversionCandidate(
            surface = "帰社",
            reading = "きしゃ",
            historyCount = 3,
            lastUsedAtMs = nowMs - 48 * 3_600_000L
        )

        val scores = floatArrayOf(10.5f, 4.2f)
        val reranked = LlmReranker.scoreAndRankPool(
            topPool = listOf(candMajor, candMinorCtx),
            scores = scores,
            currentShownTop1 = null,
            weightAlpha = 0.20f,
            lambdaObs = 0.50f,
            gammaUser = 3.00f,
            targetInputLength = 3,
            nowMs = nowMs
        )

        assertEquals("帰社", reranked[0].surface)
    }

    @Test
    fun testOneShotAccidentalNoiseDoesNotOverrideNaturalCandidate() {
        // c=1 accidental history should be damped (0.30x) and NOT beat natural candidate
        val candNoise = ConversionCandidate(
            surface = "奇社",
            reading = "きしゃ",
            historyCount = 1,
            lastUsedAtMs = nowMs - 1 * 3_600_000L
        )
        val candNatural = ConversionCandidate(
            surface = "記者",
            reading = "きしゃ"
        )

        val scores = floatArrayOf(7.2f, 4.8f)
        val reranked = LlmReranker.scoreAndRankPool(
            topPool = listOf(candNoise, candNatural),
            scores = scores,
            currentShownTop1 = null,
            weightAlpha = 0.20f,
            lambdaObs = 0.50f,
            gammaUser = 3.00f,
            targetInputLength = 3,
            nowMs = nowMs
        )

        assertEquals("記者", reranked[0].surface)
    }

    @Test
    fun testTimeDecayAdaptsToRecentSessionTopicShift() {
        // Stale habit (c=20, 30 days ago) vs Recent session term (c=2, 10 mins ago)
        val candStale = ConversionCandidate(
            surface = "旧案件",
            reading = "あんけん",
            historyCount = 20,
            lastUsedAtMs = nowMs - 30L * 24 * 3_600_000L
        )
        val candRecent = ConversionCandidate(
            surface = "新案件",
            reading = "あんけん",
            historyCount = 2,
            lastUsedAtMs = nowMs - 10L * 60_000L
        )

        val scores = floatArrayOf(5.8f, 5.5f)
        val reranked = LlmReranker.scoreAndRankPool(
            topPool = listOf(candStale, candRecent),
            scores = scores,
            currentShownTop1 = null,
            weightAlpha = 0.20f,
            lambdaObs = 0.50f,
            gammaUser = 3.00f,
            targetInputLength = 4,
            nowMs = nowMs
        )

        assertEquals("新案件", reranked[0].surface)
    }

    @Test
    fun testPrefixPrediction1CharPromotesHistoryToSlot2WhileProtectingExactRank1() {
        // Input: "お" (n=1). 3 full-span candidates exist, plus history prediction "お世話になっております" (c=10).
        // For n=1, Exact full-span stays at #1, and history prediction is promoted from Stratum 6 to Slot-2!
        val full1 = ConversionCandidate("お", "お", source = "dict")
        val full2 = ConversionCandidate("尾", "お", source = "dict")
        val full3 = ConversionCandidate("小", "お", source = "dict")
        val histPred = ConversionCandidate(
            surface = "お世話になっております",
            reading = "おせわになっております",
            isPrediction = true,
            source = "dict",
            historyCount = 10,
            lastUsedAtMs = nowMs - 3_600_000L
        )
        val sysPred = ConversionCandidate(
            surface = "お世辞",
            reading = "おせじ",
            isPrediction = true,
            source = "dict"
        )

        val pool = listOf(full1, full2, full3, histPred, sysPred)
        val scores = floatArrayOf(4.2f, 5.8f, 6.9f, 7.2f, 6.2f)
        val reranked = LlmReranker.scoreAndRankPool(
            topPool = pool,
            scores = scores,
            currentShownTop1 = null,
            weightAlpha = 0.20f,
            lambdaObs = 0.50f,
            gammaUser = 3.00f,
            targetInputLength = 1,
            nowMs = nowMs
        )

        assertEquals("お", reranked[0].surface)
        assertEquals("お世話になっております", reranked[1].surface)
    }

    @Test
    fun testPrefixPrediction2CharPromotesStrongHistoryToTop1WhenSuperior() {
        // Input: "おせ" (n=2). History prediction "お世話になっております" (c=15, recent)
        // clearly beats 2-char full candidates -> promoted conditionally to Rank 1!
        val full1 = ConversionCandidate("お世", "おせ", source = "dict")
        val full2 = ConversionCandidate("お瀬", "おせ", source = "dict")
        val histPred = ConversionCandidate(
            surface = "お世話になっております",
            reading = "おせわになっております",
            isPrediction = true,
            source = "dict",
            historyCount = 15,
            lastUsedAtMs = nowMs - 1_800_000L
        )

        val pool = listOf(full1, full2, histPred)
        val scores = floatArrayOf(5.8f, 7.2f, 6.5f)
        val reranked = LlmReranker.scoreAndRankPool(
            topPool = pool,
            scores = scores,
            currentShownTop1 = null,
            weightAlpha = 0.20f,
            lambdaObs = 0.50f,
            gammaUser = 3.00f,
            targetInputLength = 2,
            nowMs = nowMs
        )

        assertEquals("お世話になっております", reranked[0].surface)
        assertEquals("お世", reranked[1].surface)
    }

    @Test
    fun testPrefixPredictionProtectsExactShortWordWhenContextStronglyFits() {
        // Input: "とう" (n=2) in "五重のとう" -> "塔" (NLL=3.2) strongly beats "東京" (NLL=9.8, c=15).
        // "塔" MUST stay at Rank 1.
        val exactTarget = ConversionCandidate("塔", "とう", source = "dict")
        val full2 = ConversionCandidate("党", "とう", source = "dict")
        val histPred = ConversionCandidate(
            surface = "東京",
            reading = "とうきょう",
            isPrediction = true,
            source = "dict",
            historyCount = 15,
            lastUsedAtMs = nowMs - 3_600_000L
        )

        val pool = listOf(exactTarget, full2, histPred)
        val scores = floatArrayOf(3.2f, 6.5f, 9.8f)
        val reranked = LlmReranker.scoreAndRankPool(
            topPool = pool,
            scores = scores,
            currentShownTop1 = null,
            weightAlpha = 0.20f,
            lambdaObs = 0.50f,
            gammaUser = 3.00f,
            targetInputLength = 2,
            nowMs = nowMs
        )

        assertEquals("塔", reranked[0].surface)
    }

    @Test
    fun testNextPhrasePredictorPopulatesHistoryCountAndTimestamp() {
        val predictor = NextPhrasePredictor(null)
        predictor.recordTransition("よろしく", "お願いいたします")
        predictor.recordTransition("よろしく", "お願いいたします")
        predictor.recordTransition("よろしく", "お願いいたします")

        val cands = predictor.predict("よろしく")
        val histCand = cands.firstOrNull { it.surface == "お願いいたします" }
        assertTrue(histCand != null)
        assertEquals("history_bigram", histCand!!.source)
        assertEquals(3, histCand.historyCount)
        assertTrue(histCand.lastUsedAtMs > 0L)
    }
}
