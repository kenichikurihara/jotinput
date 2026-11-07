package com.example.myasr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the LM-First Joint Evaluation Algorithm and Parameter Tuning:
 * 1. Intra-Word Surprisal Clamping for Custom Dictionary (ADR-011 & Section 9.3)
 * 2. Competing user dictionary entries tie-breaking by context (貴社 vs 記者)
 * 3. Diagnostic cases non-regression (結果 and 削除) without length-normalization bias
 * 4. Observation likelihood typo and voicing fuzzy recovery (天気が良い vs 会議がある)
 */
class LmFirstJointEvaluationTest {

    private fun scoreLmFirstCandidate(
        candidate: ConversionCandidate,
        rawNll: Float,
        sysBestNll: Float,
        weightAlpha: Float = 0.20f,
        lambdaObs: Float = 0.50f,
        gammaUser: Float = 3.00f,
        rankIndex: Int = 0
    ): Float {
        val isUser = candidate.source == "user"

        val effectiveNll = if (isUser) {
            val priority = if (candidate.priority > 0f) candidate.priority else 3.0f
            val clampedNll = minOf(sysBestNll, rawNll)
            val userBoost = gammaUser * priority
            val tieBreaker = 0.05f * rawNll
            clampedNll - userBoost + tieBreaker
        } else {
            rawNll
        }

        val effectiveObs = lambdaObs * candidate.obsPenalty * 5.0f
        val rankCost = (rankIndex * 1000.0f) / 1000.0f
        return (1.0f - weightAlpha) * (effectiveNll + effectiveObs) + weightAlpha * rankCost
    }

    @Test
    fun testUserDictionaryIntraWordSurprisalClampingExactMatch() {
        // User registered "中村" (multi-token, low prior in general corpus, e.g. rawNll = 24.8)
        val userCand = ConversionCandidate(
            surface = "中村",
            reading = "なかむら",
            source = "user",
            priority = Priority.NORMAL.score
        )
        // Generic system homophone with higher unigram probability (e.g. rawNll = 14.2)
        val sysCand1 = ConversionCandidate(
            surface = "仲村",
            reading = "なかむら",
            source = "system"
        )
        val sysCand2 = ConversionCandidate(
            surface = "クリア",
            reading = "なかむら",
            source = "system"
        )

        val sysNlls = listOf(14.2f, 16.5f)
        val sysBestNll = sysNlls.minOrNull() ?: 15.0f

        val scoreUser = scoreLmFirstCandidate(userCand, rawNll = 24.8f, sysBestNll = sysBestNll, rankIndex = 0)
        val scoreSys1 = scoreLmFirstCandidate(sysCand1, rawNll = 14.2f, sysBestNll = sysBestNll, rankIndex = 1)
        val scoreSys2 = scoreLmFirstCandidate(sysCand2, rawNll = 16.5f, sysBestNll = sysBestNll, rankIndex = 2)

        // User candidate MUST beat all system competitors despite lower prior probability
        assertTrue("User candidate score ($scoreUser) must be strictly better than sys1 ($scoreSys1)", scoreUser < scoreSys1)
        assertTrue("User candidate score ($scoreUser) must be strictly better than sys2 ($scoreSys2)", scoreUser < scoreSys2)
    }

    @Test
    fun testUserDictionaryContextualTieBreakingBetweenCompetingUserEntries() {
        // User registered BOTH "貴社" (priority=3.0) and "記者" (priority=3.0)
        val userKisha1 = ConversionCandidate(
            surface = "貴社",
            reading = "きしゃ",
            source = "user",
            priority = Priority.NORMAL.score
        )
        val userKisha2 = ConversionCandidate(
            surface = "記者",
            reading = "きしゃ",
            source = "user",
            priority = Priority.NORMAL.score
        )
        val sysCand = ConversionCandidate(
            surface = "汽車",
            reading = "きしゃ",
            source = "system"
        )

        // Context A: Business email ("ますますご清栄のこととお慶び申し上げます。")
        // In this context, 貴社 has lower NLL (e.g. 5.1) than 記者 (e.g. 11.4)
        val sysBestNllA = 12.0f
        val scoreA_Kisha1 = scoreLmFirstCandidate(userKisha1, rawNll = 5.1f, sysBestNll = sysBestNllA, rankIndex = 0)
        val scoreA_Kisha2 = scoreLmFirstCandidate(userKisha2, rawNll = 11.4f, sysBestNll = sysBestNllA, rankIndex = 1)
        val scoreA_Sys = scoreLmFirstCandidate(sysCand, rawNll = 14.0f, sysBestNll = sysBestNllA, rankIndex = 2)

        assertTrue("In business email context, 貴社 ($scoreA_Kisha1) must beat 記者 ($scoreA_Kisha2)", scoreA_Kisha1 < scoreA_Kisha2)
        assertTrue("In business email context, both user entries must beat system ($scoreA_Sys)", scoreA_Kisha2 < scoreA_Sys)

        // Context B: Newspaper interview ("日経新聞の敏腕")
        // In this context, 記者 has lower NLL (e.g. 4.8) than 貴社 (e.g. 12.2)
        val sysBestNllB = 11.5f
        val scoreB_Kisha1 = scoreLmFirstCandidate(userKisha1, rawNll = 12.2f, sysBestNll = sysBestNllB, rankIndex = 0)
        val scoreB_Kisha2 = scoreLmFirstCandidate(userKisha2, rawNll = 4.8f, sysBestNll = sysBestNllB, rankIndex = 1)
        val scoreB_Sys = scoreLmFirstCandidate(sysCand, rawNll = 13.5f, sysBestNll = sysBestNllB, rankIndex = 2)

        assertTrue("In newspaper context, 記者 ($scoreB_Kisha2) must beat 貴社 ($scoreB_Kisha1)", scoreB_Kisha2 < scoreB_Kisha1)
        assertTrue("In newspaper context, both user entries must beat system ($scoreB_Sys)", scoreB_Kisha1 < scoreB_Sys)
    }

    @Test
    fun testDiagnosticKekkaAndSakujoFullSequenceNllScoring() {
        // "さくじょ" diagnostic:
        // "削除" is 2 tokens ('削', '除') with sum NLL = 16.08
        // "サク所" is 3 tokens with sum NLL = 28.65
        // "さ9時ょ" is 4 tokens with sum NLL = 42.58
        val candSakujo = ConversionCandidate(surface = "削除", reading = "さくじょ", source = "mmap")
        val candSakuSho = ConversionCandidate(surface = "サク所", reading = "さくじょ", source = "viterbi")
        val candSa9 = ConversionCandidate(surface = "さ9時ょ", reading = "さくじょ", source = "viterbi")

        val sysBest = 16.08f
        val scoreSakujo = scoreLmFirstCandidate(candSakujo, rawNll = 16.08f, sysBestNll = sysBest, rankIndex = 0)
        val scoreSakuSho = scoreLmFirstCandidate(candSakuSho, rawNll = 28.65f, sysBestNll = sysBest, rankIndex = 1)
        val scoreSa9 = scoreLmFirstCandidate(candSa9, rawNll = 42.58f, sysBestNll = sysBest, rankIndex = 2)

        assertTrue("削除 ($scoreSakujo) must win over chimeric サク所 ($scoreSakuSho)", scoreSakujo < scoreSakuSho)
        assertTrue("削除 ($scoreSakujo) must win over chimeric さ9時ょ ($scoreSa9)", scoreSakujo < scoreSa9)

        // "けっか" diagnostic:
        // "結果" is 1 token with NLL = 12.20
        // "欠課" is 2 tokens with sum NLL = 23.14
        val candKekka = ConversionCandidate(surface = "結果", reading = "けっか", source = "mmap")
        val candKekkaAlt = ConversionCandidate(surface = "欠課", reading = "けっか", source = "sqlite")

        val scoreKekka = scoreLmFirstCandidate(candKekka, rawNll = 12.20f, sysBestNll = 12.20f, rankIndex = 0)
        val scoreKekkaAlt = scoreLmFirstCandidate(candKekkaAlt, rawNll = 23.14f, sysBestNll = 12.20f, rankIndex = 1)

        assertTrue("結果 ($scoreKekka) must strictly beat rare 欠課 ($scoreKekkaAlt)", scoreKekka < scoreKekkaAlt)
    }

    @Test
    fun testObservationLikelihoodTypoRecovery() {
        // "てんくがよい" -> Intended: "天気が良い" (obsPenalty = 1.5 due to flick typo, but highly natural LM prior)
        // Literal candidate: "点区が良い" (obsPenalty = 0.0, but absurd LM prior)
        val candTenki = ConversionCandidate(
            surface = "天気が良い",
            reading = "てんきがよい",
            source = "flick_correction",
            obsPenalty = 1.5f
        )
        val candTenku = ConversionCandidate(
            surface = "点区が良い",
            reading = "てんくがよい",
            source = "viterbi",
            obsPenalty = 0.0f
        )

        // In context "明日は全国的に":
        // P_LM("天気が良い") is very high (NLL = 6.2)
        // P_LM("点区が良い") is very low (NLL = 21.5)
        val sysBest = 6.2f
        val scoreTenki = scoreLmFirstCandidate(candTenki, rawNll = 6.2f, sysBestNll = sysBest, lambdaObs = 0.5f, rankIndex = 0)
        val scoreTenku = scoreLmFirstCandidate(candTenku, rawNll = 21.5f, sysBestNll = sysBest, lambdaObs = 0.5f, rankIndex = 1)

        assertTrue("天気が良い ($scoreTenki) must win over 点区が良い ($scoreTenku) despite typo penalty", scoreTenki < scoreTenku)
    }
}
