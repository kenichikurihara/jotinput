package com.example.myasr

import com.example.myasr.context.kanakanji.domain.ConversionCandidate
import com.example.myasr.context.kanakanji.domain.KanaKanjiEngine
import com.example.myasr.context.kanakanji.domain.NextPhrasePredictor
import com.example.myasr.context.reranking.infrastructure.LlmReranker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Unit & Evaluation Test Suite for P0 Flick Candidate Diversity Improvements:
 * - Idea D1: Submodular Set-Utility Reranking (prevents same-stem monopoly in predictions)
 * - Idea D2: Dual-Horizon Pairing (pairs short anchor stem alongside promoted long chunk)
 * - Idea D3: B3 Breadth-First Stem Round-Robin Rollout in KanaKanjiEngine
 */
class FlickDiversityToplineEvaluationTest {

    private lateinit var engine: KanaKanjiEngine

    @Before
    fun setUp() {
        val predictor = NextPhrasePredictor(null)
        engine = KanaKanjiEngine(null)
        engine.predictor = predictor

        val tsvFile = listOf(
            File("app/src/test/resources/mozc/base_dictionary.tsv"),
            File("src/test/resources/mozc/base_dictionary.tsv"),
            File("tools/mozc_dict/base_dictionary.tsv"),
            File("../tools/mozc_dict/base_dictionary.tsv")
        ).firstOrNull { it.exists() }
        if (tsvFile != null) {
            tsvFile.inputStream().use { s ->
                engine.initializeWithStream(s)
            }
        } else {
            engine.registerCanonicalVocabulary()
        }
    }

    @Test
    fun testIdeaD1_submodularSetUtilityPreventsSameStemMonopolizingPredictions() {
        val pool = listOf(
            ConversionCandidate("詐欺", "さぎ", isPrediction = false, source = "dict", dictCost = 3000),
            ConversionCandidate("作業中ですので", "さぎょうちゅうですので", isPrediction = true, source = "phrase_chunk", priority = 3.5f),
            ConversionCandidate("作業中です", "さぎょうちゅうです", isPrediction = true, source = "phrase_chunk", priority = 3.3f),
            ConversionCandidate("作業します", "さぎょうします", isPrediction = true, source = "phrase_chunk", priority = 3.3f),
            ConversionCandidate("先ほど送付した", "さきほどそうふした", isPrediction = true, source = "phrase_chunk", priority = 3.3f)
        )
        // Simulated NLLs where all "作業*" variants are slightly lower NLL than "先ほど送付した"
        val scores = floatArrayOf(12.0f, 6.0f, 6.2f, 6.3f, 6.8f)

        val reranked = LlmReranker.scoreAndRankPool(
            topPool = pool,
            scores = scores,
            currentShownTop1 = null,
            weightAlpha = 0.20f,
            lambdaObs = 0.50f,
            gammaUser = 3.00f,
            targetInputLength = 2
        )

        val top4Surfaces = reranked.take(4).map { it.surface }
        // Slot 1 should be the highest-utility chunk "作業中ですので"
        assertEquals("作業中ですので", reranked[0].surface)
        // Because of submodular overlap kernel kappa=0.80 among same-stem CHUNK_EXT candidates,
        // the distinct-stem prediction "先ほど送付した" must surface into Top-4 ahead of redundant "作業中です"/"作業します"
        assertTrue(
            "Expected distinct-stem prediction '先ほど送付した' in top 4, but was: $top4Surfaces",
            top4Surfaces.contains("先ほど送付した")
        )
    }

    @Test
    fun testIdeaD2_dualHorizonPairingPlacesAnchorStemInVisibleSlots() {
        val pool = listOf(
            ConversionCandidate("書く", "かく", isPrediction = false, source = "dict", dictCost = 2000),
            ConversionCandidate("確認させていただきます", "かくにんさせていただきます", isPrediction = true, source = "phrase_chunk", priority = 3.5f),
            ConversionCandidate("確認しました", "かくにんしました", isPrediction = true, source = "phrase_chunk", priority = 3.3f),
            ConversionCandidate("確認", "かくにん", isPrediction = true, source = "dict", dictCost = 200)
        )
        val scores = floatArrayOf(9.5f, 5.5f, 6.0f, 5.8f)

        val reranked = LlmReranker.scoreAndRankPool(
            topPool = pool,
            scores = scores,
            currentShownTop1 = null,
            weightAlpha = 0.20f,
            lambdaObs = 0.50f,
            gammaUser = 3.00f,
            targetInputLength = 2
        )

        val top3Surfaces = reranked.take(3).map { it.surface }
        assertEquals("確認させていただきます", reranked[0].surface)
        assertTrue(
            "Expected short anchor stem '確認' in Top-3 visible slots alongside long chunk, but was: $top3Surfaces",
            top3Surfaces.contains("確認")
        )
    }

    @Test
    fun testIdeaD3_roundRobinMultiBunsetsuRolloutDiversifiesStems() {
        val cands = engine.getBaseCandidates("かい", contextPrefix = "明日の")
        val phraseChunks = cands.filter { it.source == "phrase_chunk" }
        if (phraseChunks.size >= 3) {
            val topChunkStems = phraseChunks.take(4).map { cand ->
                LlmReranker.extractCanonicalStem(cand, cands, isEnglishPool = false)
            }.distinct()
            assertTrue(
                "Expected at least 2 distinct stems in top 4 phrase_chunk candidates for 'かい', but was: $topChunkStems (chunks=${phraseChunks.take(4).map { it.surface }})",
                topChunkStems.size >= 2
            )
        }
    }

    @Test
    fun testEnglishPoolPreservesRawTop1AndDiversifiesCompletions() {
        val pool = listOf(
            ConversionCandidate("imp", "imp", isPrediction = false, source = "raw"),
            ConversionCandidate("implementation", "implementation", isPrediction = true, source = "english_typo"),
            ConversionCandidate("implementations", "implementations", isPrediction = true, source = "english_typo"),
            ConversionCandidate("important", "important", isPrediction = true, source = "english_typo")
        )
        val scores = floatArrayOf(7.0f, 6.5f, 6.7f, 7.2f)

        val reranked = LlmReranker.scoreAndRankPool(
            topPool = pool,
            scores = scores,
            currentShownTop1 = null,
            weightAlpha = 0.20f,
            lambdaObs = 0.50f,
            gammaUser = 3.00f,
            targetInputLength = 3
        )

        // EN_QWERTY must protect raw "imp" at Rank 1
        assertEquals("imp", reranked[0].surface)
        // Top-3 should contain both "implementation" and "important" rather than "implementation" + "implementations"
        val top3 = reranked.take(3).map { it.surface }
        assertTrue("Expected 'implementation' in top 3: $top3", top3.contains("implementation"))
        assertTrue("Expected distinct-stem 'important' in top 3: $top3", top3.contains("important"))
    }

    // ---- Review fixes R1-R5 ----

    @Test
    fun testR2R3_diversifyByFirstStemKeepsOneChunkPerStemFirst() {
        fun c(surface: String, stem: String) = ConversionCandidate(
            surface, surface, isPrediction = true, source = "phrase_chunk", priority = 3.3f, firstBunsetsuSurface = stem
        )
        val dict = ConversionCandidate("x", "x", isPrediction = false, source = "dict")
        val input = listOf(c("作業中です", "作業"), c("作業します", "作業"), dict, c("先ほど送付した", "先ほど"))
        val out = com.example.myasr.context.kanakanji.domain.diversifyByFirstStem(input)
        assertEquals(listOf("作業中です", "先ほど送付した", "x", "作業します"), out.map { it.surface })
    }

    @Test
    fun testR4_clauseStartContextHelper() {
        val f = com.example.myasr.context.kanakanji.domain.isClauseStartOrTopicContext("")
        assertTrue(f)
        assertTrue(com.example.myasr.context.kanakanji.domain.isClauseStartOrTopicContext("昨日、"))
        assertTrue(!com.example.myasr.context.kanakanji.domain.isClauseStartOrTopicContext("資料を"))
    }

    @Test
    fun testR1_slot1PromotionUsesIndependentUtilityAndNoDuplicates() {
        // rankedFull[0] shares stem "作業" with the highest-utility priority-3.5 chunk "作業中ですので".
        // Pre-R1 bug: kappa=0.35 decay from rankedFull[0] made greedy rank "先ほど送付した" (NLL 6.2) above "作業中ですので" (NLL 6.0).
        // Post-R1: Slot 1 promotion uses INDEPENDENT utility ("作業中ですので"), and Slot 2+ greedy promotes "先ほど送付した" without duplicates.
        val pool = listOf(
            ConversionCandidate("作業", "さぎ", isPrediction = false, source = "dict", dictCost = 2000, firstBunsetsuSurface = "作業"),
            ConversionCandidate("作業中ですので", "さぎょうちゅうですので", isPrediction = true, source = "phrase_chunk", priority = 3.5f, firstBunsetsuSurface = "作業"),
            ConversionCandidate("作業します", "さぎょうします", isPrediction = true, source = "phrase_chunk", priority = 3.5f, firstBunsetsuSurface = "作業"),
            ConversionCandidate("先ほど送付した", "さきほどそうふした", isPrediction = true, source = "phrase_chunk", priority = 3.5f, firstBunsetsuSurface = "先ほど")
        )
        val scores = floatArrayOf(12.0f, 6.0f, 6.1f, 6.2f)
        val reranked = LlmReranker.scoreAndRankPool(
            topPool = pool, scores = scores, currentShownTop1 = null,
            weightAlpha = 0.20f, lambdaObs = 0.50f, gammaUser = 3.00f, targetInputLength = 2
        )
        assertEquals("作業中ですので", reranked[0].surface)
        assertEquals(reranked.size, reranked.map { it.surface }.distinct().size)
        assertTrue(reranked.take(4).map { it.surface }.contains("先ほど送付した"))
    }

    @Test
    fun testR2_dynamicChunksCarryFirstBunsetsuSurface() {
        val cands = engine.getBaseCandidates("こう")
        val chunks = cands.filter { it.source == "phrase_chunk" }
        assertTrue("Expected phrase_chunk candidates for 'こう'", chunks.isNotEmpty())
        assertTrue(
            "All generated phrase_chunk candidates must set firstBunsetsuSurface: $chunks",
            chunks.all { it.firstBunsetsuSurface.isNotEmpty() }
        )
    }
}
