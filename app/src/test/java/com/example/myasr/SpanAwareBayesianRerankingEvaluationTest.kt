package com.example.myasr

import org.junit.Assert.assertEquals
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
        assertEquals("最初", reranked[1].surface)
        assertEquals("才女の", reranked[2].surface)
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
}
