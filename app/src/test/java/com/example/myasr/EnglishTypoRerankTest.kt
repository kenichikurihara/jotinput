package com.example.myasr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnglishTypoRerankTest {

    @Test
    fun testObservationCostRejectsDistortedTyposForShortWords() {
        val dict = listOf(
            DictionaryEntry(word = "UI", reading = "ui", priority = Priority.HIGH),
            DictionaryEntry(word = "ReazonSpeech", reading = "reazonspeech", priority = Priority.HIGH)
        )

        // User typed "wi" (intend "will" or "with")
        val cands = EnglishDictionaryUtils.suggestFromUserDictionary("wi", dict)
        // Must NOT suggest "UI" for "wi"
        assertFalse("Candidate list for 'wi' must not suggest 'UI'", cands.any { it.surface.equals("UI", ignoreCase = true) })

        // User typed "ui"
        val candsUi = EnglishDictionaryUtils.suggestFromUserDictionary("ui", dict)
        assertTrue("Candidate list for 'ui' must suggest 'UI'", candsUi.any { it.surface == "UI" })
        assertEquals(0.0f, candsUi.first { it.surface == "UI" }.obsPenalty, 1e-4f)
    }

    @Test
    fun testJointBayesianEnergyRanksWillAheadOfUiUnderContextI() {
        // Simulating candidate pool under context "I " and input "wi"
        val candWill = ConversionCandidate(
            surface = "will",
            reading = "wi",
            isPrediction = true,
            source = "english_typo",
            obsPenalty = 0.0f
        )
        val candWith = ConversionCandidate(
            surface = "with",
            reading = "wi",
            isPrediction = true,
            source = "english_typo",
            obsPenalty = 0.0f
        )
        val candWiRaw = ConversionCandidate(
            surface = "wi",
            reading = "wi",
            isPrediction = false,
            source = "raw",
            obsPenalty = 0.0f
        )
        val candUiFuzzy = ConversionCandidate(
            surface = "UI",
            reading = "wi",
            isPrediction = false,
            source = "user_english",
            priority = 22.0f,
            obsPenalty = 0.70f // normalized spatial distance
        )

        val candidates = listOf(candWill, candWith, candWiRaw, candUiFuzzy)

        // Measured negative log-likelihoods (-log P(W | "I ")) on Qwen2.5-0.5B
        val nllMap = mapOf(
            "will" to 6.58f,
            "with" to 7.48f,
            "wi" to 12.50f,
            "UI" to 13.05f
        )

        val alpha = KeyboardHeightManager.DEFAULT_SLM_WEIGHT_ALPHA // 0.15f
        val lambdaObs = KeyboardHeightManager.DEFAULT_SLM_LAMBDA_OBS // 1.0f
        val gammaUser = KeyboardHeightManager.DEFAULT_SLM_GAMMA_USER // 0.65f

        val sysNlls = candidates.filter { it.source != "user" && it.source != "user_english" }.map { nllMap[it.surface] ?: 15.0f }
        val sysBestNll = sysNlls.minOrNull() ?: 15.0f

        val scored = candidates.mapIndexed { index, cand ->
            val rawNll = nllMap[cand.surface] ?: 15.0f
            val isUser = cand.source == "user" || cand.source == "user_english"
            val isExactUser = isUser && cand.obsPenalty == 0.0f

            val effectiveNll = if (isExactUser) {
                val priority = if (cand.priority > 0f) cand.priority else 3.0f
                val clampedNll = minOf(sysBestNll, rawNll)
                val userBoost = gammaUser * priority
                val tieBreaker = 0.05f * rawNll
                clampedNll - userBoost + tieBreaker
            } else {
                rawNll
            }

            val effectiveObs = lambdaObs * cand.obsPenalty * 5.0f
            val rankCost = (index * 1000.0f) / 1000.0f
            val finalScore = (1.0f - alpha) * (effectiveNll + effectiveObs) + alpha * rankCost
            Pair(cand, finalScore)
        }

        val ranked = scored.sortedBy { it.second }.map { it.first }

        assertEquals("Top candidate under 'I wi' must be 'will'", "will", ranked[0].surface)
        assertEquals("Second candidate under 'I wi' must be 'with'", "with", ranked[1].surface)
        assertEquals("UI must be ranked last due to high NLL and high obsPenalty", "UI", ranked.last().surface)
    }
}
