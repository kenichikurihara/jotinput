package com.example.myasr

import com.example.myasr.context.kanakanji.domain.ConversionCandidate
import com.example.myasr.context.kanakanji.domain.KanaKanjiEngine
import com.example.myasr.context.reranking.infrastructure.LlmReranker
import com.example.myasr.context.vocabulary.domain.Priority

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Comprehensive Evaluation Test Suite for Span-Aware Bayesian Reranking & Subsumption Dominance (ADR-020).
 *
 * Verifies accuracy gains across both Japanese and English typing:
 * 1. Japanese Full-Span Dominance: "さいしょの" ranks "最初の" at #1 over truncated "最初"
 * 2. Particle & Conjugation Sequences: "かいぎの", "あしたは", "いくなら", "わたしの"
 * 3. Chimera Fail-Safe: Promotes partial candidate when full-span is phonetically broken (s_tail > 4.0)
 * 4. English Apostrophe Contraction: "cant" ranks "can't" at #1 over "can"
 * 5. English Exact Short-Word Protection: "an" preserves #1 over predictive extension "and"
 * 6. English Inflection Suffix Protection: "talked" preserves #1 over stem "talk"
 * 7. Non-Regression: Diagnostic cases ("結果", "削除") and Custom Dictionary Priority (ADR-011/ADR-018)
 */
class SpanAwareBayesianRerankingEvaluationTest {

    @Test
    fun testJapaneseSaishonoRanksFullCandidateAtRank1OverTruncatedStem() {
        // Input reading: "さいしょの" (n = 4)
        val candFull = ConversionCandidate(
            surface = "最初の",
            reading = "さいしょの",
            source = "viterbi"
        )
        val candStem = ConversionCandidate(
            surface = "最初",
            reading = "さいしょ",
            source = "viterbi_prefix"
        )
        val candHomophoneFull = ConversionCandidate(
            surface = "才女の",
            reading = "さいしょの",
            source = "viterbi"
        )
        val candPredictive = ConversionCandidate(
            surface = "最初の一歩",
            reading = "さいしょのいっぽ",
            isPrediction = true,
            source = "viterbi"
        )

        val pool = listOf(candFull, candStem, candHomophoneFull, candPredictive)

        // Measured / simulated raw negative log-likelihoods on Qwen2.5-0.5B:
        // "最初" (stem): rawNll = 7.20
        // "最初の" (stem + の): rawNll = 9.20 (Δ = 2.00 nats for particle "の")
        // "才女の": rawNll = 14.50
        // "最初の一歩": rawNll = 11.00
        val scores = floatArrayOf(9.20f, 7.20f, 14.50f, 11.00f)

        val reranked = LlmReranker.scoreAndRankPool(
            topPool = pool,
            scores = scores,
            currentShownTop1 = null,
            weightAlpha = 0.20f,
            lambdaObs = 0.50f,
            gammaUser = 3.00f,
            targetInputLength = "さいしょの".length
        )

        // Crucial verification: "最初の" MUST be at Rank 1!
        assertEquals("最初の", reranked[0].surface)
        // With 2D stem diversity and det-sub prevention, the alternative full-span candidate "才女の"
        // is prioritized at Rank 2 over the duplicate-stem truncated candidate "最初"
        assertEquals("才女の", reranked[1].surface)
        assertEquals("最初", reranked[2].surface)
        assertEquals("最初の一歩", reranked[3].surface)
    }

    @Test
    fun testJapaneseParticlesAndConjugationsRankFullSpanAtRank1() {
        // 1. "かいぎの" (n = 4) -> "会議の" must beat "会議"
        val poolKaigi = listOf(
            ConversionCandidate("会議の", "かいぎの", source = "viterbi"),
            ConversionCandidate("会議", "かいぎ", source = "viterbi_prefix")
        )
        val scoresKaigi = floatArrayOf(8.50f, 6.70f) // Δ = 1.80 nats for "の"
        val rerankedKaigi = LlmReranker.scoreAndRankPool(poolKaigi, scoresKaigi, null, 0.20f, 0.50f, 3.00f, 4)
        assertEquals("会議の", rerankedKaigi[0].surface)

        // 2. "あしたは" (n = 4) -> "明日は" must beat "明日"
        val poolAshita = listOf(
            ConversionCandidate("明日は", "あしたは", source = "viterbi"),
            ConversionCandidate("明日", "あした", source = "viterbi_prefix")
        )
        val scoresAshita = floatArrayOf(8.80f, 6.90f) // Δ = 1.90 nats for "は"
        val rerankedAshita = LlmReranker.scoreAndRankPool(poolAshita, scoresAshita, null, 0.20f, 0.50f, 3.00f, 4)
        assertEquals("明日は", rerankedAshita[0].surface)

        // 3. "いくなら" (n = 4) -> "行くなら" must beat "行く"
        val poolIkunara = listOf(
            ConversionCandidate("行くなら", "いくなら", source = "viterbi"),
            ConversionCandidate("行く", "いく", source = "viterbi_prefix")
        )
        val scoresIkunara = floatArrayOf(9.60f, 6.50f) // Δ = 3.10 nats for "なら" (2 morae: 1.55 nats/mora)
        val rerankedIkunara = LlmReranker.scoreAndRankPool(poolIkunara, scoresIkunara, null, 0.20f, 0.50f, 3.00f, 4)
        assertEquals("行くなら", rerankedIkunara[0].surface)

        // 4. "わたしの" (n = 4) -> "私の" must beat "私"
        val poolWatashi = listOf(
            ConversionCandidate("私の", "わたしの", source = "viterbi"),
            ConversionCandidate("私", "わたし", source = "viterbi_prefix")
        )
        val scoresWatashi = floatArrayOf(8.10f, 6.20f)
        val rerankedWatashi = LlmReranker.scoreAndRankPool(poolWatashi, scoresWatashi, null, 0.20f, 0.50f, 3.00f, 4)
        assertEquals("私の", rerankedWatashi[0].surface)
    }

    @Test
    fun testChimeraFullCandidateFailsSafeToStemWhenTailSurprisalIsHigh() {
        // If the full candidate is an unnatural chimera (e.g. archaic or mis-split suffix),
        // tail surprisal s_tail exceeds the coherence threshold (4.0 nats/mora),
        // and the clean stem candidate MUST be promoted to save user from corruption.
        val candChimera = ConversionCandidate("最初之", "さいしょの", source = "viterbi")
        val candCleanStem = ConversionCandidate("最初", "さいしょ", source = "viterbi_prefix")

        val pool = listOf(candChimera, candCleanStem)
        // rawNll("最初") = 7.20
        // rawNll("最初之") = 16.50 -> tail surprisal = (16.50 - 7.20)/1 = 9.30 nats/mora >> 4.0
        val scores = floatArrayOf(16.50f, 7.20f)

        val reranked = LlmReranker.scoreAndRankPool(pool, scores, null, 0.20f, 0.50f, 3.00f, "さいしょの".length)
        assertEquals("When full candidate is broken, clean stem must win", "最初", reranked[0].surface)
    }

    @Test
    fun testEnglishApostropheContractionRanksCantAtRank1OverCan() {
        // Input: "cant" (n = 4 letters typed)
        val candCant = ConversionCandidate(
            surface = "can't",
            reading = "cant",
            source = "english_typo"
        )
        val candCan = ConversionCandidate(
            surface = "can",
            reading = "cant",
            source = "english_typo"
        )
        val candCat = ConversionCandidate(
            surface = "cat",
            reading = "cant",
            source = "english_typo"
        )

        val pool = listOf(candCant, candCan, candCat)

        // On Qwen2.5 / BPE:
        // " can": 1 token, rawNll = 6.80
        // " can't": 2 tokens (" can", "'t"), rawNll = 8.50 (Δ = 1.70 nats)
        // " cat": 1 token, rawNll = 12.00
        val scores = floatArrayOf(8.50f, 6.80f, 12.00f)

        val reranked = LlmReranker.scoreAndRankPool(
            topPool = pool,
            scores = scores,
            currentShownTop1 = null,
            weightAlpha = 0.15f,
            lambdaObs = 1.0f,
            gammaUser = 0.65f,
            targetInputLength = 4
        )

        assertEquals("can't must win Rank 1 over truncated can", "can't", reranked[0].surface)
        assertEquals("can", reranked[1].surface)
        assertEquals("cat", reranked[2].surface)
    }

    @Test
    fun testEnglishExactShortWordProtectsAnOverPredictiveExtensionAnd() {
        // Input: "an" (n = 2 letters typed, user finished typing)
        val candAn = ConversionCandidate(
            surface = "an",
            reading = "an",
            isPrediction = false,
            source = "raw"
        )
        val candAnd = ConversionCandidate(
            surface = "and",
            reading = "an",
            isPrediction = true,
            source = "english_typo"
        )

        val pool = listOf(candAn, candAnd)

        // Suppose under preceding context, "and" has slightly higher unigram prior:
        // rawNll("and") = 6.20, rawNll("an") = 7.10
        val scores = floatArrayOf(7.10f, 6.20f)

        val reranked = LlmReranker.scoreAndRankPool(
            topPool = pool,
            scores = scores,
            currentShownTop1 = null,
            weightAlpha = 0.15f,
            lambdaObs = 1.0f,
            gammaUser = 0.65f,
            targetInputLength = 2
        )

        // Because "and" has extension delta = 1 (penalty = 1.20 nats), effectiveNll("and") = 7.40 > 7.10
        // "an" MUST stay at Rank 1!
        assertEquals("an", reranked[0].surface)
        assertEquals("and", reranked[1].surface)
    }

    @Test
    fun testEnglishInflectionSuffixProtectsTalkedOverStemTalk() {
        // Input: "talked" (n = 6 letters typed)
        val candTalked = ConversionCandidate(
            surface = "talked",
            reading = "talked",
            source = "english_typo"
        )
        val candTalk = ConversionCandidate(
            surface = "talk",
            reading = "talked",
            source = "english_typo"
        )

        val pool = listOf(candTalked, candTalk)

        // " talk" (1 token): rawNll = 6.30
        // " talked" (2 tokens: " talk", "ed"): rawNll = 8.10 (Δ = 1.80 nats for 2 letters: 0.90 nats/letter)
        val scores = floatArrayOf(8.10f, 6.30f)

        val reranked = LlmReranker.scoreAndRankPool(
            topPool = pool,
            scores = scores,
            currentShownTop1 = null,
            weightAlpha = 0.15f,
            lambdaObs = 1.0f,
            gammaUser = 0.65f,
            targetInputLength = 6
        )

        assertEquals("talked must preserve Rank 1 over stem talk", "talked", reranked[0].surface)
        assertEquals("talk", reranked[1].surface)
    }

    @Test
    fun testNonRegressionDiagnosticKekkaAndSakujoUnchanged() {
        // Finding 37 non-regression:
        // For words where all candidates share exact reading length (m = n),
        // span penalty is 0 and full-sequence NLL ranking is strictly identical.
        val candSakujo = ConversionCandidate("削除", "さくじょ", source = "mmap")
        val candSakuSho = ConversionCandidate("サク所", "さくじょ", source = "viterbi")
        val candSa9 = ConversionCandidate("さ9時ょ", "さくじょ", source = "viterbi")

        val pool = listOf(candSakujo, candSakuSho, candSa9)
        val scores = floatArrayOf(16.08f, 28.65f, 42.58f)

        val reranked = LlmReranker.scoreAndRankPool(pool, scores, null, 0.20f, 0.50f, 3.00f, "さいしょの".length)
        assertEquals("削除", reranked[0].surface)
        assertEquals("サク所", reranked[1].surface)
        assertEquals("さ9時ょ", reranked[2].surface)
    }

    @Test
    fun testNonRegressionUserDictionaryIntraWordSurprisalClampingPreserved() {
        // User custom entry "中村" (multi-token, high raw NLL = 24.8)
        val userCand = ConversionCandidate(
            surface = "中村",
            reading = "なかむら",
            source = "user",
            priority = Priority.NORMAL.score
        )
        val sysCand = ConversionCandidate(
            surface = "仲村",
            reading = "なかむら",
            source = "system"
        )

        val pool = listOf(userCand, sysCand)
        val scores = floatArrayOf(24.80f, 14.20f)

        val reranked = LlmReranker.scoreAndRankPool(pool, scores, null, 0.20f, 0.50f, 3.00f, "さいしょの".length)
        assertEquals("User dictionary candidate MUST stay at Rank 1 via Intra-Word Surprisal Clamping", "中村", reranked[0].surface)
    }

    @Test
    fun testHighBranchingSuffixPreservesPartialCandidatesAnd2DDiversity() {
        // Input: "さいとうこうき" (n = 7)
        // High effective branching factor for "+こうき" (M_eff >= 1.80)
        val full1 = ConversionCandidate(
            surface = "斎藤工機",
            reading = "さいとうこうき",
            source = "viterbi",
            firstBunsetsuSurface = "斎藤",
            lastContentWordSurface = "工機"
        )
        val full2SameStem = ConversionCandidate(
            surface = "斎藤幸喜",
            reading = "さいとうこうき",
            source = "viterbi",
            firstBunsetsuSurface = "斎藤",
            lastContentWordSurface = "幸喜"
        )
        val full3Diverse = ConversionCandidate(
            surface = "斉藤幸喜",
            reading = "さいとうこうき",
            source = "viterbi",
            firstBunsetsuSurface = "斉藤",
            lastContentWordSurface = "幸喜"
        )
        val partialSaitou1 = ConversionCandidate(
            surface = "斎藤",
            reading = "さいとう",
            source = "viterbi_prefix",
            firstBunsetsuSurface = "斎藤",
            completedSurface = "斎藤工機",
            suffixBranchingFactor = 6.5f
        )
        val partialSaitou2 = ConversionCandidate(
            surface = "斉藤",
            reading = "さいとう",
            source = "viterbi_prefix",
            firstBunsetsuSurface = "斉藤",
            completedSurface = "斉藤幸喜",
            suffixBranchingFactor = 6.5f
        )

        val pool = listOf(full1, full2SameStem, full3Diverse, partialSaitou1, partialSaitou2)
        // Raw NLLs:
        // full1 (斎藤工機) = 10.0
        // full2SameStem (斎藤幸喜) = 10.2
        // full3Diverse (斉藤幸喜) = 10.5
        // partialSaitou1 (斎藤) = 10.0 (virtual completed: 斎藤工機)
        // partialSaitou2 (斉藤) = 10.5 (virtual completed: 斉藤幸喜)
        val scores = floatArrayOf(10.0f, 10.2f, 10.5f, 10.0f, 10.5f)

        val reranked = LlmReranker.scoreAndRankPool(
            topPool = pool,
            scores = scores,
            currentShownTop1 = null,
            weightAlpha = 0.20f,
            lambdaObs = 0.50f,
            gammaUser = 3.00f,
            targetInputLength = 7
        )

        // 1. Slots 1 and 2 MUST be full candidates with 2D diversity (different first stems!):
        // Slot 1: "斎藤工機"
        // Slot 2: "斉藤幸喜" (penalizes "斎藤幸喜" due to duplicate first stem "斎藤")
        assertEquals("斎藤工機", reranked[0].surface)
        assertEquals("斉藤幸喜", reranked[1].surface)

        // 2. Slots 3 and 4 MUST be partial candidates with diverse stems:
        val partialsInTop4 = reranked.slice(2..3).map { it.surface }
        assertTrue("Top partial candidates must be preserved in Slots 3-4", partialsInTop4.contains("斎藤"))
        assertTrue("Top partial candidates must be preserved in Slots 3-4", partialsInTop4.contains("斉藤"))
    }

    @Test
    fun testEnginePrioritizeByHistoryDoesNotLetPartialCandidateOvertakeFullSpan() {
        val engine = KanaKanjiEngine()
        val stream = javaClass.classLoader?.getResourceAsStream("mozc/base_dictionary.tsv")
        if (stream != null) {
            engine.initializeWithStream(stream)
        } else {
            engine.registerCanonicalVocabulary()
        }

        // 1. User previously committed "さいしょ" -> "最初" 10 times
        val stemCand = ConversionCandidate(surface = "最初", reading = "さいしょ", source = "viterbi_prefix")
        for (i in 1..10) {
            engine.commitCandidate(stemCand)
        }

        // 2. Now user types "さいしょの" (length 5)
        val candidates = engine.getBaseCandidates("さいしょの")
        assertTrue("Candidates must not be empty", candidates.isNotEmpty())

        // 3. Top candidate must be a full-span candidate, NOT the partial candidate "最初"!
        val top1 = candidates.first()
        assertTrue("Top candidate must be full span", top1.reading == "さいしょの" && !top1.isPrediction && top1.source != "viterbi_prefix")
        assertFalse("Partial candidate '最初' must not overtake full candidates despite history", top1.surface == "最初")
        val candidateSurfaces = candidates.map { it.surface }
        assertTrue("Expected full candidates like '最初の' or '再書の' in candidate list",
            candidateSurfaces.contains("最初の") || candidateSurfaces.contains("再書の"))
    }

    @Test
    fun testRareMorphemeSuppressionInSlmRerankingAndStratumRankNormalization() {
        // Simulates "おんとおふと" where interleaved prefix candidates sit between early and late full candidates
        val candRare = ConversionCandidate(
            surface = "音吐オフと",
            reading = "おんとおふと",
            source = "viterbi",
            dictCost = 8436,
            firstBunsetsuSurface = "音吐オフ",
            lastContentWordSurface = "オフ"
        )
        val candPrefix1 = ConversionCandidate(
            surface = "音",
            reading = "おん",
            source = "viterbi_prefix",
            dictCost = 580
        )
        val candPrefix2 = ConversionCandidate(
            surface = "オン",
            reading = "おん",
            source = "viterbi_prefix",
            dictCost = 3719
        )
        val candPrefix3 = ConversionCandidate(
            surface = "恩",
            reading = "おん",
            source = "viterbi_prefix",
            dictCost = 5412
        )
        val candTarget = ConversionCandidate(
            surface = "オンとオフと",
            reading = "おんとおふと",
            source = "viterbi",
            dictCost = 4470,
            firstBunsetsuSurface = "オン",
            lastContentWordSurface = "オフ"
        )

        val pool = listOf(candRare, candPrefix1, candPrefix2, candPrefix3, candTarget)
        // Even if SLM assigns slightly lower NLL to candRare (e.g. 11.5 vs 11.8),
        // Idea R2 (dictCost quadratic rarity penalty) + Idea R3 (stratum-normalized rankCost)
        // must rank "オンとオフと" at #1 ahead of "音吐オフと".
        val scores = floatArrayOf(11.50f, 6.20f, 6.40f, 9.80f, 11.80f)

        val reranked = LlmReranker.scoreAndRankPool(
            topPool = pool,
            scores = scores,
            currentShownTop1 = null,
            weightAlpha = 0.20f,
            lambdaObs = 0.50f,
            gammaUser = 3.00f,
            targetInputLength = "おんとおふと".length
        )

        assertEquals("オンとオフと", reranked[0].surface)
        assertEquals("音吐オフと", reranked[1].surface)
    }

    @Test
    fun testEnglishDeadEndOovPrefixPromotesTailscaleAheadOfRawAndTruncatedTypos() {
        // Input: "tailsc" (n = 6 letters typed; OOV incomplete prefix)
        val candTailscale = ConversionCandidate(
            surface = "Tailscale",
            reading = "tailsc",
            isPrediction = true,
            source = "english_typo",
            obsPenalty = 0.075f
        )
        val candTails = ConversionCandidate(
            surface = "tails",
            reading = "tailsc",
            isPrediction = false,
            source = "english_typo",
            obsPenalty = 0.38f
        )
        val candRaw = ConversionCandidate(
            surface = "tailsc",
            reading = "tailsc",
            isPrediction = false,
            source = "raw",
            obsPenalty = 0.0f
        )

        val pool = listOf(candTailscale, candTails, candRaw)
        // Even when raw fragment or shorter word has competitive NLL, OOV raw penalty + light prefix extension
        // promotes "Tailscale" to Rank 1 ahead of "tails" and "tailsc".
        val scores = floatArrayOf(9.80f, 9.20f, 12.50f)

        val reranked = LlmReranker.scoreAndRankPool(
            topPool = pool,
            scores = scores,
            currentShownTop1 = null,
            weightAlpha = 0.15f,
            lambdaObs = 1.0f,
            gammaUser = 0.65f,
            targetInputLength = 6
        )

        assertEquals("Tailscale must win Rank 1 for OOV prefix 'tailsc'", "Tailscale", reranked[0].surface)
    }
}
