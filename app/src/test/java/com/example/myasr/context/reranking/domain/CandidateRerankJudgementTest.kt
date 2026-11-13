package com.example.myasr.context.reranking.domain

import com.example.myasr.context.kanakanji.domain.ConversionCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidateRerankJudgementTest {

    @Test
    fun `executionJudgement rejects when disabled, not ready, or single candidate`() {
        val disabled = CandidateRerankJudgement.judgeExecution(
            CandidateRerankJudgement.ExecutionInput(
                isRerankEnabled = false,
                candidateCount = 5,
                isSlmReady = true,
                interKeyIntervalMs = 200L,
                readingLength = 3
            )
        )
        assertFalse(disabled.shouldRerank)
        assertEquals("slm_rerank_disabled", disabled.reason)

        val notReady = CandidateRerankJudgement.judgeExecution(
            CandidateRerankJudgement.ExecutionInput(
                isRerankEnabled = true,
                candidateCount = 5,
                isSlmReady = false,
                interKeyIntervalMs = 200L,
                readingLength = 3
            )
        )
        assertFalse(notReady.shouldRerank)
        assertEquals("slm_not_ready", notReady.reason)

        val single = CandidateRerankJudgement.judgeExecution(
            CandidateRerankJudgement.ExecutionInput(
                isRerankEnabled = true,
                candidateCount = 1,
                isSlmReady = true,
                interKeyIntervalMs = 200L,
                readingLength = 3
            )
        )
        assertFalse(single.shouldRerank)
        assertEquals("insufficient_candidates", single.reason)
    }

    @Test
    fun `executionJudgement applies 40ms debounce for rapid short typing and 4ms otherwise`() {
        val rapidShort = CandidateRerankJudgement.judgeExecution(
            CandidateRerankJudgement.ExecutionInput(
                isRerankEnabled = true,
                candidateCount = 4,
                isSlmReady = true,
                interKeyIntervalMs = 80L,
                readingLength = 2
            )
        )
        assertTrue(rapidShort.shouldRerank)
        assertEquals(40L, rapidShort.debounceMs)

        val normal = CandidateRerankJudgement.judgeExecution(
            CandidateRerankJudgement.ExecutionInput(
                isRerankEnabled = true,
                candidateCount = 4,
                isSlmReady = true,
                interKeyIntervalMs = 200L,
                readingLength = 5
            )
        )
        assertTrue(normal.shouldRerank)
        assertEquals(4L, normal.debounceMs)
    }

    @Test
    fun `tapSafeOrdering pins initial Top-1 after 100ms or while user is touching strip`() {
        val base = listOf(
            ConversionCandidate(surface = "今日", reading = "きょう", source = "dict"),
            ConversionCandidate(surface = "京", reading = "きょう", source = "dict")
        )
        val reranked = listOf(
            ConversionCandidate(surface = "京", reading = "きょう", source = "slm"),
            ConversionCandidate(surface = "今日", reading = "きょう", source = "dict")
        )

        val withinWindow = CandidateRerankJudgement.judgeTapSafeOrdering(
            CandidateRerankJudgement.TapSafeOrderingInput(
                baseCandidates = base,
                rerankedCandidates = reranked,
                initialRenderedTop1 = "今日",
                reading = "きょう",
                elapsedFromFirstRenderMs = 50L,
                isUserTouchingStrip = false
            )
        )
        assertFalse(withinWindow.pinnedInitialTop1)
        assertEquals("京", withinWindow.finalCandidates.first().surface)

        val afterWindow = CandidateRerankJudgement.judgeTapSafeOrdering(
            CandidateRerankJudgement.TapSafeOrderingInput(
                baseCandidates = base,
                rerankedCandidates = reranked,
                initialRenderedTop1 = "今日",
                reading = "きょう",
                elapsedFromFirstRenderMs = 150L,
                isUserTouchingStrip = false
            )
        )
        assertTrue(afterWindow.pinnedInitialTop1)
        assertEquals("今日", afterWindow.finalCandidates.first().surface)
        assertEquals("京", afterWindow.finalCandidates[1].surface)
    }

    @Test
    fun `tapSafeOrdering allows full-span candidate to promote over partial prefix`() {
        val base = listOf(
            ConversionCandidate(surface = "きょ", reading = "きょ", source = "viterbi_prefix")
        )
        val reranked = listOf(
            ConversionCandidate(surface = "今日", reading = "きょう", source = "dict")
        )

        val decision = CandidateRerankJudgement.judgeTapSafeOrdering(
            CandidateRerankJudgement.TapSafeOrderingInput(
                baseCandidates = base,
                rerankedCandidates = reranked,
                initialRenderedTop1 = "きょ",
                reading = "きょう",
                elapsedFromFirstRenderMs = 200L,
                isUserTouchingStrip = true
            )
        )
        assertFalse(decision.pinnedInitialTop1)
        assertEquals("promote_full_span_over_partial", decision.reason)
        assertEquals("今日", decision.finalCandidates.first().surface)
    }

    @Test
    fun `nextPhraseRerankJudgement requires enabled, ready, and non-blank context`() {
        assertFalse(
            CandidateRerankJudgement.judgeNextPhraseRerank(
                CandidateRerankJudgement.NextPhraseRerankInput(true, true, "   ")
            ).decision
        )
        assertTrue(
            CandidateRerankJudgement.judgeNextPhraseRerank(
                CandidateRerankJudgement.NextPhraseRerankInput(true, true, "今日は")
            ).decision
        )
    }

    @Test
    fun `DMN decision tables expose auditable rule IDs and hit policies`() {
        val rapidTrace = CandidateRerankJudgement.judgeExecutionWithTrace(
            CandidateRerankJudgement.ExecutionInput(
                isRerankEnabled = true,
                candidateCount = 3,
                isSlmReady = true,
                interKeyIntervalMs = 50L,
                readingLength = 2
            )
        )
        assertEquals("CandidateRerankExecution", rapidTrace.decisionName)
        assertEquals("RERANK_EXEC_R4", rapidTrace.selectedRuleId)
        assertFalse(rapidTrace.usedDefaultFallback)

        val normalTrace = CandidateRerankJudgement.judgeExecutionWithTrace(
            CandidateRerankJudgement.ExecutionInput(
                isRerankEnabled = true,
                candidateCount = 3,
                isSlmReady = true,
                interKeyIntervalMs = 300L,
                readingLength = 5
            )
        )
        assertEquals("RERANK_EXEC_DEFAULT", normalTrace.selectedRuleId)
        assertTrue(normalTrace.usedDefaultFallback)
    }
}
