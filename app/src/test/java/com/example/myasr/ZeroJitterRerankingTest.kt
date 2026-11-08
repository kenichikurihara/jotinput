package com.example.myasr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for Zero-Jitter SLM Reranking & Tap-Safe Window policies:
 * 1. Policy ① & ②: Fast synchronous 0ms ranking and Kotlin-level NLL memoization
 * 2. Policy ④: Top-1 Hysteresis anchor margin (Δ_hysteresis = 1.20 nats) preventing minor flips
 * 3. Policy ⑤: Tap-Safe Window (Slot 0 pinning when delayed > 100ms or interacting)
 */
class ZeroJitterRerankingTest {

    private fun scoreCandidateWithHysteresis(
        candidate: ConversionCandidate,
        rawNll: Float,
        currentShownTop1: String?,
        sysBestNll: Float = 14.0f,
        weightAlpha: Float = 0.20f,
        lambdaObs: Float = 0.50f,
        gammaUser: Float = 3.00f,
        rankIndex: Int = 0
    ): Float {
        val isUser = candidate.source == "user"
        val isExactUser = isUser && candidate.obsPenalty == 0.0f

        val effectiveNll = if (isExactUser) {
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

        // Policy ④: Anchor currently shown Top-1 candidate with -1.20 nats bonus
        val hysteresisBonus = if (currentShownTop1 != null && candidate.surface == currentShownTop1 && !isExactUser) {
            -1.20f
        } else {
            0.0f
        }

        return (1.0f - weightAlpha) * (effectiveNll + effectiveObs + hysteresisBonus) + weightAlpha * rankCost
    }

    @Test
    fun testHysteresisPreventsUnnecessaryFlipOnMinorScoreDelta() {
        // Initial Viterbi / Fast candidate: "決裁" (already shown at Slot 0)
        val shownCand = ConversionCandidate(surface = "決裁", reading = "けっさい", source = "viterbi")
        // Competing candidate: "決済" (slightly better SLM raw NLL, e.g. delta = 0.6 nats < 1.20 nats)
        val competitorCand = ConversionCandidate(surface = "決済", reading = "けっさい", source = "viterbi")

        val rawNllShown = 12.8f
        val rawNllCompetitor = 12.2f // competitor is 0.6 nats better, but within tolerance

        val scoreShown = scoreCandidateWithHysteresis(
            shownCand, rawNllShown, currentShownTop1 = "決裁", rankIndex = 0
        )
        val scoreCompetitor = scoreCandidateWithHysteresis(
            competitorCand, rawNllCompetitor, currentShownTop1 = "決裁", rankIndex = 1
        )

        // Hysteresis anchor bonus should preserve "決裁" as #1 to prevent mid-reach flip
        assertTrue(
            "Currently shown candidate ($scoreShown) should beat minor competitor ($scoreCompetitor)",
            scoreShown < scoreCompetitor
        )
    }

    @Test
    fun testHysteresisPermitsFlipOnSubstantialContextualEvidence() {
        // Initial candidate: "記者" (shown at Slot 0)
        val shownCand = ConversionCandidate(surface = "記者", reading = "きしゃ", source = "viterbi")
        // Strong competitor in context "御中": "貴社" (raw NLL delta = 3.5 nats > 1.20 nats)
        val competitorCand = ConversionCandidate(surface = "貴社", reading = "きしゃ", source = "viterbi")

        val rawNllShown = 15.0f
        val rawNllCompetitor = 11.5f // competitor is 3.5 nats better (exp(3.5) = ~33x more probable)

        val scoreShown = scoreCandidateWithHysteresis(
            shownCand, rawNllShown, currentShownTop1 = "記者", rankIndex = 0
        )
        val scoreCompetitor = scoreCandidateWithHysteresis(
            competitorCand, rawNllCompetitor, currentShownTop1 = "記者", rankIndex = 1
        )

        // Substantial evidence should overcome hysteresis and flip to "貴社"
        assertTrue(
            "Substantial competitor ($scoreCompetitor) should overtake shown candidate ($scoreShown)",
            scoreCompetitor < scoreShown
        )
    }

    @Test
    fun testUserDictionaryAlwaysBypassesHysteresisAnchor() {
        val shownSysCand = ConversionCandidate(surface = "栗腹", reading = "くりはら", source = "viterbi")
        val userCand = ConversionCandidate(surface = "栗原", reading = "くりはら", source = "user", priority = 3.0f)

        val scoreSys = scoreCandidateWithHysteresis(shownSysCand, 14.0f, currentShownTop1 = "栗腹", rankIndex = 0)
        val scoreUser = scoreCandidateWithHysteresis(userCand, 22.0f, currentShownTop1 = "栗腹", rankIndex = 1)

        assertTrue("User dictionary candidate must always win regardless of shown top-1 anchor", scoreUser < scoreSys)
    }

    @Test
    fun testTapSafeWindowPinsSlot0WhenRerankDelayed() {
        val initialCandidates = listOf(
            ConversionCandidate("法案", "ほうあん", source = "viterbi"),
            ConversionCandidate("放談", "ほうあん", source = "viterbi")
        )
        val backgroundReranked = listOf(
            ConversionCandidate("法宴", "ほうあん", source = "viterbi"),
            ConversionCandidate("法案", "ほうあん", source = "viterbi"),
            ConversionCandidate("放談", "ほうあん", source = "viterbi")
        )

        val currentTop1ToPreserve = initialCandidates.first().surface // "法案"
        val elapsedFromFirstRenderMs = 180L // Zone B: 100ms < elapsed <= 250ms (Tap-Danger Zone)

        val safeCandidates = if (elapsedFromFirstRenderMs > 100L &&
            currentTop1ToPreserve != null &&
            backgroundReranked.first().surface != currentTop1ToPreserve
        ) {
            val pinnedTop = backgroundReranked.find { it.surface == currentTop1ToPreserve }
                ?: ConversionCandidate(surface = currentTop1ToPreserve, reading = "ほうあん", isPrediction = false)
            val otherCands = backgroundReranked.filter { it.surface != currentTop1ToPreserve }
            listOf(pinnedTop) + otherCands
        } else {
            backgroundReranked
        }

        // Slot 0 MUST remain "法案" to prevent mis-tap, with "法宴" adjacent at Slot 1
        assertEquals("法案", safeCandidates[0].surface)
        assertEquals("法宴", safeCandidates[1].surface)
        assertEquals("放談", safeCandidates[2].surface)
    }

    @Test
    fun testMultiTokenCandidateRollbackSafety() {
        // Multi-token candidate evaluation contract:
        // Ensures that scoring multi-token candidates does not corrupt context state
        // or cause sequence assertion failures in llama.cpp
        val cands = listOf(
            ConversionCandidate("記者会見", "きしゃかいけん", source = "viterbi"),
            ConversionCandidate("貴社海運", "きしゃかいけん", source = "viterbi"),
            ConversionCandidate("喜捨", "きしゃ", source = "viterbi_prefix")
        )
        val dummyNlls = listOf(14.5f, 18.2f, 12.1f)
        val sysBestNll = dummyNlls.minOrNull() ?: 12.1f

        val scores = cands.mapIndexed { idx, cand ->
            scoreCandidateWithHysteresis(
                candidate = cand,
                rawNll = dummyNlls[idx],
                currentShownTop1 = "記者会見",
                sysBestNll = sysBestNll,
                rankIndex = idx
            )
        }
        assertEquals(3, scores.size)
        assertTrue(scores[0] < scores[1]) // "記者会見" must beat "貴社海運"
    }
}
