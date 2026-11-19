package com.example.myasr

import com.example.myasr.context.kanakanji.domain.ConversionCandidate
import com.example.myasr.context.reranking.domain.CandidateRerankJudgement
import com.example.myasr.context.reranking.domain.CandidateRerankJudgement.UiRenderStrategy
import com.example.myasr.context.reranking.domain.CandidateRerankJudgement.ViewportAwareOrderingInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for Zero-Jitter Candidate UI (UI-Idea 1 + UI-Idea 2 + UI-Idea 8)
 * DMN DecisionTable (`ViewportAwareCandidateOrdering`).
 */
class ViewportAwareRerankOrderingTest {

    private fun cand(surface: String, reading: String = "きしゃ", cost: Int = 5000): ConversionCandidate {
        return ConversionCandidate(surface = surface, reading = reading, dictCost = cost)
    }

    @Test
    fun `UI-Idea 2 - SLM completing within 50ms micro-wait uses single-pass without placeholder`() {
        val base = listOf(cand("記者"), cand("汽車"), cand("帰社"), cand("貴社"))
        val reranked = listOf(cand("貴社"), cand("記者"), cand("汽車"), cand("帰社"))

        val trace = CandidateRerankJudgement.judgeViewportAwareOrderingWithTrace(
            ViewportAwareOrderingInput(
                baseCandidates = base,
                rerankedCandidates = reranked,
                isPhase1AlreadyRendered = false,
                hasReservedHeroSlot = false,
                frozenVisibleCount = 0,
                isUserTouchingStrip = false
            )
        )

        assertEquals("VP_SAFE_R2", trace.selectedRuleId)
        assertEquals(UiRenderStrategy.DIRECT_SINGLE_PASS_SLM, trace.result.strategy)
        assertNull(trace.result.heroSlotCandidate)
        assertEquals(listOf("貴社", "記者", "汽車", "帰社"), trace.result.finalCandidates.map { it.surface })
    }

    @Test
    fun `UI-Idea 1 and 8 - Phase 2 populates Hero Slot, freezes Zone B, and reranks off-screen Zone C`() {
        val base = listOf(
            cand("記者"), cand("汽車"), cand("帰社"), // Zone B (visible: K_vis = 3)
            cand("喜捨"), cand("騎射"), cand("貴社"), cand("貴社名"), cand("記者会見") // Zone C (off-screen)
        )
        val reranked = listOf(
            cand("貴社"), cand("貴社名"), cand("記者"), cand("記者会見"), cand("帰社"), cand("汽車"), cand("喜捨"), cand("騎射")
        )

        val trace = CandidateRerankJudgement.judgeViewportAwareOrderingWithTrace(
            ViewportAwareOrderingInput(
                baseCandidates = base,
                rerankedCandidates = reranked,
                isPhase1AlreadyRendered = true,
                hasReservedHeroSlot = true,
                frozenVisibleCount = 3,
                isUserTouchingStrip = false
            )
        )

        assertEquals("VP_SAFE_R3", trace.selectedRuleId)
        val decision = trace.result
        assertEquals(UiRenderStrategy.PHASE2_POPULATE_HERO_AND_RERANK_OFFSCREEN_TAIL, decision.strategy)

        // Zone A (Hero Slot) gets SLM Top-1
        assertEquals("貴社", decision.heroSlotCandidate?.surface)
        assertNull(decision.spotlightIndexInViewport)

        // Zone B (Visible Viewport) is 100% preserved in exact order with no compaction
        assertEquals(listOf("記者", "汽車", "帰社"), decision.frozenZoneBCandidates.map { it.surface })

        // Zone C (Off-Screen Scrollable Tail) is deduplicated against Zone A+B and sorted by SLM rank
        assertEquals(listOf("貴社名", "記者会見", "喜捨", "騎射"), decision.offScreenZoneCCandidates.map { it.surface })

        // No duplicates across Zone A + B + C
        val allSurfaces = decision.finalCandidates.map { it.surface }
        assertEquals(allSurfaces.size, allSurfaces.toSet().size)
    }

    @Test
    fun `Zone B already containing SLM Top-1 gets in-place spotlight while Zone B order stays frozen`() {
        val base = listOf(
            cand("記者"), cand("貴社"), cand("汽車"), // Zone B (K_vis = 3, 貴社 is at index 1)
            cand("喜捨"), cand("貴社名"), cand("帰社") // Zone C
        )
        val reranked = listOf(
            cand("貴社"), cand("貴社名"), cand("記者"), cand("帰社"), cand("汽車"), cand("喜捨")
        )

        val decision = CandidateRerankJudgement.judgeViewportAwareOrdering(
            ViewportAwareOrderingInput(
                baseCandidates = base,
                rerankedCandidates = reranked,
                isPhase1AlreadyRendered = true,
                hasReservedHeroSlot = true,
                frozenVisibleCount = 3,
                isUserTouchingStrip = false
            )
        )

        // Spotlight points to index 1 ("貴社") inside Zone B without moving it
        assertEquals(1, decision.spotlightIndexInViewport)
        // Hero slot shows the top unseen SLM candidate ("貴社名")
        assertEquals("貴社名", decision.heroSlotCandidate?.surface)
        // Zone B order is strictly untouched
        assertEquals(listOf("記者", "貴社", "汽車"), decision.frozenZoneBCandidates.map { it.surface })
        // Zone C excludes Zone A ("貴社名") and Zone B ("記者", "貴社", "汽車")
        assertEquals(listOf("帰社", "喜捨"), decision.offScreenZoneCCandidates.map { it.surface })
    }

    @Test
    fun `Active touch or scroll after Phase 1 always defers DOM mutation`() {
        val base = listOf(cand("記者"), cand("汽車"), cand("貴社"))
        val reranked = listOf(cand("貴社"), cand("記者"), cand("汽車"))

        val trace = CandidateRerankJudgement.judgeViewportAwareOrderingWithTrace(
            ViewportAwareOrderingInput(
                baseCandidates = base,
                rerankedCandidates = reranked,
                isPhase1AlreadyRendered = true,
                hasReservedHeroSlot = true,
                frozenVisibleCount = 2,
                isUserTouchingStrip = true
            )
        )

        assertEquals("VP_SAFE_R1", trace.selectedRuleId)
        assertEquals(UiRenderStrategy.DEFER_WHILE_USER_TOUCHING, trace.result.strategy)
        assertEquals(base, trace.result.finalCandidates)
    }

    @Test
    fun `UI-Idea 8 standalone without Hero Slot freezes Zone B and reranks Zone C`() {
        val base = listOf(cand("記者"), cand("汽車"), cand("帰社"), cand("貴社"), cand("貴社名"))
        val reranked = listOf(cand("貴社"), cand("貴社名"), cand("記者"), cand("汽車"), cand("帰社"))

        val trace = CandidateRerankJudgement.judgeViewportAwareOrderingWithTrace(
            ViewportAwareOrderingInput(
                baseCandidates = base,
                rerankedCandidates = reranked,
                isPhase1AlreadyRendered = true,
                hasReservedHeroSlot = false,
                frozenVisibleCount = 2,
                isUserTouchingStrip = false
            )
        )

        assertEquals("VP_SAFE_DEFAULT", trace.selectedRuleId)
        val decision = trace.result
        assertNull(decision.heroSlotCandidate)
        assertEquals(listOf("記者", "汽車"), decision.frozenZoneBCandidates.map { it.surface })
        assertEquals(listOf("貴社", "貴社名", "帰社"), decision.offScreenZoneCCandidates.map { it.surface })
        assertTrue(decision.finalCandidates.size == 5)
    }
}
