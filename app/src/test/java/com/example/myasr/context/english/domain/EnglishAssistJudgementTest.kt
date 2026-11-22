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

    @Test
    fun `DMN prefixCompletionPolicyDecisionTable protects known exact words and promotes OOV prefixes`() {
        // 1. Short input (< 2) -> NONE
        val tooShort = EnglishAssistJudgement.judgePrefixCompletionPolicyWithTrace(
            rawWordLength = 1,
            isKnownExactWord = false,
            isOovAcronym = false,
            hasPrefixCompletions = true
        )
        assertEquals(EnglishAssistJudgement.PrefixCompletionStrategy.NONE, tooShort.result.decision)
        assertEquals("EN_PREFIX_R1", tooShort.selectedRuleId)

        // 2. Known exact word (e.g. "an", "tail") -> PROTECT_EXACT_WORD
        val knownExact = EnglishAssistJudgement.judgePrefixCompletionPolicyWithTrace(
            rawWordLength = 2,
            isKnownExactWord = true,
            isOovAcronym = false,
            hasPrefixCompletions = true
        )
        assertEquals(EnglishAssistJudgement.PrefixCompletionStrategy.PROTECT_EXACT_WORD, knownExact.result.decision)
        assertEquals("EN_PREFIX_R2", knownExact.selectedRuleId)

        // 3. OOV acronym (e.g. "sdk") -> PROTECT_EXACT_WORD
        val oovAcro = EnglishAssistJudgement.judgePrefixCompletionPolicyWithTrace(
            rawWordLength = 3,
            isKnownExactWord = false,
            isOovAcronym = true,
            hasPrefixCompletions = true
        )
        assertEquals(EnglishAssistJudgement.PrefixCompletionStrategy.PROTECT_EXACT_WORD, oovAcro.result.decision)
        assertEquals("EN_PREFIX_R2", oovAcro.selectedRuleId)

        // 4. Incomplete OOV prefix with completions (e.g. "tailsc") -> PROMOTE_PREFIX_COMPLETION
        val oovPrefix = EnglishAssistJudgement.judgePrefixCompletionPolicyWithTrace(
            rawWordLength = 6,
            isKnownExactWord = false,
            isOovAcronym = false,
            hasPrefixCompletions = true
        )
        assertEquals(EnglishAssistJudgement.PrefixCompletionStrategy.PROMOTE_PREFIX_COMPLETION, oovPrefix.result.decision)
        assertEquals("EN_PREFIX_R3", oovPrefix.selectedRuleId)

        // 5. Unknown word with no prefix completions -> RAW_AND_TYPO_ONLY
        val unknownNoPrefix = EnglishAssistJudgement.judgePrefixCompletionPolicyWithTrace(
            rawWordLength = 5,
            isKnownExactWord = false,
            isOovAcronym = false,
            hasPrefixCompletions = false
        )
        assertEquals(EnglishAssistJudgement.PrefixCompletionStrategy.RAW_AND_TYPO_ONLY, unknownNoPrefix.result.decision)
        assertEquals("EN_PREFIX_DEFAULT", unknownNoPrefix.selectedRuleId)
    }
}
