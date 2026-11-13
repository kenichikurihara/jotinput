package com.example.myasr.context.english.domain

import com.example.myasr.context.kanakanji.domain.JapaneseKanaUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnglishAssistJudgementTest {

    @Test
    fun `judgeAutoAssistBypass bypasses non-QWERTY modes and sensitive fields`() {
        assertTrue(
            EnglishAssistJudgement.judgeAutoAssistBypass(
                JapaneseKanaUtils.KeyboardMode.KANA,
                isFieldBypassed = false
            ).decision
        )
        assertTrue(
            EnglishAssistJudgement.judgeAutoAssistBypass(
                JapaneseKanaUtils.KeyboardMode.QWERTY,
                isFieldBypassed = true
            ).decision
        )
        assertFalse(
            EnglishAssistJudgement.judgeAutoAssistBypass(
                JapaneseKanaUtils.KeyboardMode.QWERTY,
                isFieldBypassed = false
            ).decision
        )
        assertFalse(
            EnglishAssistJudgement.judgeAutoAssistBypass(
                JapaneseKanaUtils.KeyboardMode.QWERTY_SYMBOLS,
                isFieldBypassed = false
            ).decision
        )
    }

    @Test
    fun `extractTrailingWord and judgeWordCandidateEligibility handle short words and contractions`() {
        val extracted = EnglishAssistJudgement.extractTrailingWord("Hello don't")
        assertEquals("don't", extracted.rawWord)
        assertEquals("Hello ", extracted.precedingContext)

        assertFalse(
            EnglishAssistJudgement.judgeWordCandidateEligibility(
                rawWord = "a",
                hasPrimaryCapitalizationCandidate = false
            ).decision
        )
        assertTrue(
            EnglishAssistJudgement.judgeWordCandidateEligibility(
                rawWord = "i",
                hasPrimaryCapitalizationCandidate = true
            ).decision
        )
        assertTrue(
            EnglishAssistJudgement.judgeWordCandidateEligibility(
                rawWord = "th",
                hasPrimaryCapitalizationCandidate = false
            ).decision
        )
    }

    @Test
    fun `judgeGecClause extracts trailing clause and requires multi-word length at least 3`() {
        val singleWord = EnglishAssistJudgement.judgeGecClause("Hello. world")
        assertFalse(singleWord.shouldCorrect)
        assertEquals("world", singleWord.clause)

        val multiWord = EnglishAssistJudgement.judgeGecClause("First sentence. i goes home")
        assertTrue(multiWord.shouldCorrect)
        assertEquals("i goes home", multiWord.clause)
    }

    @Test
    fun `judgeCandidateBadge follows priority SLM over USER over CAPS over SPELL`() {
        assertEquals("SLM", EnglishAssistJudgement.judgeCandidateBadge(true, true, true))
        assertEquals("USER", EnglishAssistJudgement.judgeCandidateBadge(false, true, true))
        assertEquals("CAPS", EnglishAssistJudgement.judgeCandidateBadge(false, false, true))
        assertEquals("SPELL", EnglishAssistJudgement.judgeCandidateBadge(false, false, false))
    }

    @Test
    fun `DMN candidateBadgeDecisionTable uses PRIORITY hit policy with auditable trace`() {
        val trace = EnglishAssistJudgement.judgeCandidateBadgeWithTrace(
            slmUsed = true,
            hasUserCandidates = true,
            hasPrimaryCapitalization = true
        )
        assertEquals("EnglishCandidateBadge", trace.decisionName)
        assertEquals("SLM", trace.result)
        assertEquals("EN_BADGE_SLM", trace.selectedRuleId)
        assertEquals(listOf("EN_BADGE_SLM", "EN_BADGE_USER", "EN_BADGE_CAPS"), trace.matchedRuleIds)
    }
}
