package com.example.myasr

import com.example.myasr.context.kanakanji.domain.ConversionCandidate
import com.example.myasr.context.kanakanji.domain.KanaKanjiEngine
import com.example.myasr.context.kanakanji.domain.NextPhrasePredictor
import com.example.myasr.context.vocabulary.domain.DictionaryEntry
import com.example.myasr.context.vocabulary.domain.Priority
import com.example.myasr.context.reranking.infrastructure.LlmReranker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Phase 3 Unit Tests: Full Context SLM Zero/1-Mora Prediction & Context-Gated Penalty Attenuation (B4 + B6).
 *
 * Verifies that:
 * 1. Zero-query prediction leverages full context (`textBeforeCursor`) when committed phrase is empty or trailing.
 * 2. Trailing case particles in committed phrases resolve to canonical continuations.
 * 3. 1-mora typing under preceding context safely promotes high-utility context collocations.
 * 4. Context-gated observation penalty attenuation reduces penalty for coherent unvoiced/typo candidates.
 * 5. User custom dictionary entities dynamically roll out multi-bunsetsu noun tails without breaking Stratum 0 priority.
 */
class Phase3SlmAndContextualPredictionTest {

    private lateinit var engine: KanaKanjiEngine
    private lateinit var predictor: NextPhrasePredictor

    @Before
    fun setUp() {
        predictor = NextPhrasePredictor(null)
        engine = KanaKanjiEngine(null)
        engine.predictor = predictor

        val tsvFile = listOf(
            File("app/src/test/resources/mozc/base_dictionary.tsv"),
            File("src/test/resources/mozc/base_dictionary.tsv"),
            File("tools/mozc_dict/base_dictionary.tsv"),
            File("../tools/mozc_dict/base_dictionary.tsv")
        ).firstOrNull { it.exists() }
        requireNotNull(tsvFile) { "base_dictionary.tsv required for test" }
        tsvFile.inputStream().use { s ->
            engine.initializeWithStream(s)
        }
    }

    @Test
    fun testZeroQueryPredictionWithTrailingContextFallback() {
        // When lastPhrase is empty, predict falls back to extracting trailing noun from textBeforeCursor
        val preds = predictor.predict(
            lastPhrase = "",
            textBeforeCursor = "明日の資料",
            suggestPeriod = false
        )
        val surfaces = preds.take(3).map { it.surface }
        assertTrue("Expected particle 'を' in top-3 suggestions for '明日の資料', was: $surfaces", surfaces.contains("を"))
    }

    @Test
    fun testZeroQueryTrailingParticleCollocationResolution() {
        // "資料を" -> trailing particle "を" resolves to CANONICAL_COLLOCATIONS["を"]
        val predsWo = predictor.predict(
            lastPhrase = "資料を",
            textBeforeCursor = "資料を",
            suggestPeriod = false
        )
        val surfacesWo = predsWo.map { it.surface }
        assertTrue(
            "Expected '送信' or '確認' in suggestions for '資料を', was: ${surfacesWo.take(6)}",
            surfacesWo.contains("送信") || surfacesWo.contains("確認")
        )

        // "会議に" -> trailing particle "に" resolves to CANONICAL_COLLOCATIONS["に"]
        val predsNi = predictor.predict(
            lastPhrase = "会議に",
            textBeforeCursor = "会議に",
            suggestPeriod = false
        )
        val surfacesNi = predsNi.map { it.surface }
        assertTrue(
            "Expected '参加' in suggestions for '会議に', was: ${surfacesNi.take(6)}",
            surfacesNi.contains("参加")
        )

        // "準備が" -> trailing particle "が" resolves to CANONICAL_COLLOCATIONS["が"]
        val predsGa = predictor.predict(
            lastPhrase = "準備が",
            textBeforeCursor = "準備が",
            suggestPeriod = false
        )
        val surfacesGa = predsGa.map { it.surface }
        assertTrue(
            "Expected '完了' in suggestions for '準備が', was: ${surfacesGa.take(6)}",
            surfacesGa.contains("完了")
        )
    }

    @Test
    fun testOneMoraContextCollocationPromotion() {
        // 1-mora with context: "資料を" + "そ" -> "送信"
        val candsSo = engine.getBaseCandidates("そ", contextPrefix = "資料を")
        assertTrue(candsSo.isNotEmpty())
        assertEquals("Collocation '送信' must be Rank 1 under context '資料を'", "送信", candsSo.first().surface)

        // 1-mora with context: "会議に" + "さ" -> "参加"
        val candsSa = engine.getBaseCandidates("さ", contextPrefix = "会議に")
        assertTrue(candsSa.isNotEmpty())
        assertEquals("Collocation '参加' must be Rank 1 under context '会議に'", "参加", candsSa.first().surface)
    }

    @Test
    fun testContextGatedObservationPenaltyAttenuation() {
        // Candidate 0: Typo / unvoiced candidate with obsPenalty = 1.2f, but highly coherent with context (NLL = 1.0)
        // Candidate 1: Exact candidate with obsPenalty = 0.0f, but incoherent with context (NLL = 7.0)
        val coherentTypo = ConversionCandidate(
            surface = "会議",
            reading = "かいき",
            isPrediction = false,
            source = "dict",
            obsPenalty = 1.2f
        )
        val incoherentExact = ConversionCandidate(
            surface = "怪異",
            reading = "かいき",
            isPrediction = false,
            source = "dict",
            obsPenalty = 0.0f
        )
        val pool = listOf(incoherentExact, coherentTypo)
        val scores = floatArrayOf(7.0f, 1.0f)

        val ranked = LlmReranker.Companion.scoreAndRankPool(
            topPool = pool,
            scores = scores,
            currentShownTop1 = null,
            weightAlpha = 0.0f, // Pure SLM + utility scoring
            lambdaObs = 1.0f,
            gammaUser = 0.5f,
            targetInputLength = 3
        )

        assertTrue(ranked.isNotEmpty())
        // Due to B6 context-gated discount (deltaNll = 0.0 <= 1.5, discounted down to 0.2x),
        // the highly coherent "会議" beats "怪異"
        assertEquals("Coherent candidate '会議' must rank #1 due to context-gated obs attenuation", "会議", ranked.first().surface)
    }

    @Test
    fun testUserDictionaryEntityMultiBunsetsuTailRollout() {
        // Register custom user dictionary entry: "中村" (reading: "なかむら")
        val customEntry = DictionaryEntry(
            word = "中村",
            reading = "なかむら",
            priority = Priority.HIGH
        )
        engine.syncUserDictionary(listOf(customEntry))

        val cands = engine.getBaseCandidates("なかむら")
        val surfaces = cands.map { it.surface }

        // 1. Exact user entry must remain Top-1 (Stratum 0 purity)
        assertTrue("Candidates must not be empty", cands.isNotEmpty())
        assertEquals("User dictionary exact entry '中村' must be Rank 1", "中村", cands.first().surface)
        assertEquals("Top candidate must have source 'user'", "user", cands.first().source)

        // 2. Dynamic multi-bunsetsu tail candidate (e.g. '中村です' or '中村でした') must be synthesized
        val hasTailRollout = surfaces.any { it.startsWith("中村") && it != "中村" }
        assertTrue("User dictionary entity '中村' should synthesize multi-bunsetsu tail (e.g. 中村です), was: ${surfaces.take(6)}", hasTailRollout)
    }

    @Test
    fun testEnglishPoolDoesNotApplyObsPenaltyDiscount() {
        // In English QWERTY pool, raw candidate must NOT be overwritten by typo candidate
        // despite lower typo NLL, because obsDiscount is guarded with !isEnglishPool.
        val rawCandidate = ConversionCandidate(
            surface = "thr",
            reading = "thr",
            isPrediction = false,
            source = "raw",
            obsPenalty = 0.0f
        )
        val typoCandidate = ConversionCandidate(
            surface = "the",
            reading = "thr",
            isPrediction = false,
            source = "english_typo",
            obsPenalty = 1.0f
        )
        val pool = listOf(rawCandidate, typoCandidate)
        // typoCandidate has lower NLL (1.0f vs 5.0f)
        val scores = floatArrayOf(5.0f, 1.0f)

        val ranked = LlmReranker.Companion.scoreAndRankPool(
            topPool = pool,
            scores = scores,
            currentShownTop1 = null,
            weightAlpha = 0.0f,
            lambdaObs = 1.0f,
            gammaUser = 0.5f,
            targetInputLength = 3
        )

        assertTrue(ranked.isNotEmpty())
        assertEquals(
            "Raw English candidate must rank #1 over typo candidate because obsPenalty is not discounted in English pool",
            "thr",
            ranked.first().surface
        )
    }

    @Test
    fun testOneMoraHighPriorityCollocationNotShadowedByHistory() {
        // At n = 1:
        // - A full candidate (exact 1-char match, e.g. "そ")
        // - An ordinary history prediction (historyCount = 3, priority = 0.0f, e.g. "そうですね")
        // - A high-priority context collocation (priority = 5.0f, source = "context_collocation", e.g. "送信")
        val fullCand = ConversionCandidate(
            surface = "そ",
            reading = "そ",
            isPrediction = false,
            source = "dict"
        )
        val histPred = ConversionCandidate(
            surface = "そうですね",
            reading = "そうですね",
            isPrediction = true,
            historyCount = 3,
            priority = 0.0f,
            source = "history"
        )
        val collocPred = ConversionCandidate(
            surface = "送信",
            reading = "そうしん",
            isPrediction = true,
            priority = 5.0f,
            source = "context_collocation"
        )
        val pool = listOf(fullCand, histPred, collocPred)
        val scores = floatArrayOf(4.0f, 3.5f, 2.0f)

        val ranked = LlmReranker.Companion.scoreAndRankPool(
            topPool = pool,
            scores = scores,
            currentShownTop1 = null,
            weightAlpha = 0.0f,
            lambdaObs = 1.0f,
            gammaUser = 0.5f,
            targetInputLength = 1
        )

        assertTrue("Ranked candidates must not be empty", ranked.size >= 2)
        assertEquals(
            "High priority context collocation must promote to Slot 1 at n=1, not shadowed by history",
            "送信",
            ranked[0].surface
        )
        assertEquals(
            "Slot 2 must be the exact full candidate",
            "そ",
            ranked[1].surface
        )
    }

    @Test
    fun testZeroQuerySlmUtilityRanking() {
        // Zero-query prediction (n = 0): all candidates are predictive continuations
        val candA = ConversionCandidate(
            surface = "確認",
            reading = "かくにん",
            isPrediction = true,
            source = "slm"
        )
        val candB = ConversionCandidate(
            surface = "お願いいたします",
            reading = "おねがいいたします",
            isPrediction = true,
            source = "slm"
        )
        val candC = ConversionCandidate(
            surface = "ありがとうございます",
            reading = "ありがとうございます",
            isPrediction = true,
            source = "slm"
        )
        val pool = listOf(candA, candB, candC)
        val scores = floatArrayOf(3.0f, 1.2f, 2.5f)

        val ranked = LlmReranker.Companion.scoreAndRankPool(
            topPool = pool,
            scores = scores,
            currentShownTop1 = null,
            weightAlpha = 0.0f,
            lambdaObs = 1.0f,
            gammaUser = 0.5f,
            targetInputLength = 0
        )

        // All candidates must be preserved and ranked by utility (no dropped predictions)
        assertEquals("All zero-query candidates must be preserved", pool.size, ranked.size)
        // candB has the lowest NLL (1.2f) and high character length saving, so it should rank #1
        assertEquals("Highest expected utility candidate must rank #1", "お願いいたします", ranked[0].surface)
    }

    @Test
    fun testZeroQueryDualHorizonPairingWithCompoundChunk() {
        val pool = listOf(
            ConversionCandidate(surface = "お願いします", reading = "おねがいします", isPrediction = true, source = "canonical_collocation"),
            ConversionCandidate(surface = "お願いいたします", reading = "おねがいいたします", isPrediction = true, source = "canonical_collocation"),
            ConversionCandidate(surface = "お願い", reading = "おねがい", isPrediction = true, source = "canonical_collocation"),
            ConversionCandidate(surface = "は", reading = "は", isPrediction = true, source = "particle")
        )
        // Make "お願いします" (CHUNK_EXT) clearly win Slot 1, and "お願い" (SHORT_EXT) have better NLL than "お願いいたします"
        val scores = floatArrayOf(1.1f, 3.8f, 1.8f, 4.5f)
        val ranked = LlmReranker.Companion.scoreAndRankPool(
            topPool = pool,
            scores = scores,
            currentShownTop1 = null,
            weightAlpha = 0.0f,
            lambdaObs = 1.0f,
            gammaUser = 0.5f,
            targetInputLength = 0
        )
        assertEquals(pool.size, ranked.size)
        assertEquals("お願いします", ranked[0].surface)
        assertEquals("Idea D2 should pair short stem anchor 'お願い' in Slot 2", "お願い", ranked[1].surface)
    }
}
