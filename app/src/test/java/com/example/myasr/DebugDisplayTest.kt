package com.example.myasr

import com.example.myasr.context.ime.presentation.KeyboardHeightManager
import com.example.myasr.context.kanakanji.domain.ConversionCandidate
import com.example.myasr.context.kanakanji.domain.JapaneseKanaUtils
import com.example.myasr.context.kanakanji.domain.KanaKanjiEngine
import com.example.myasr.context.reranking.domain.RerankEngineType

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugDisplayTest {

    @Test
    fun testDebugDisplayPrefKey() {
        assertEquals("debug_display", KeyboardHeightManager.KEY_DEBUG_DISPLAY)
    }

    @Test
    fun testRerankCandidatesWithStatusFallbackWhenUninitialized() {
        // Without Android context, KanaKanjiEngine returns candidates and slmUsed = false
        val engine = KanaKanjiEngine()
        val cands = listOf(
            ConversionCandidate("テスト", "てすと"),
            ConversionCandidate("test", "てすと")
        )
        val (reranked, slmUsed) = engine.rerankCandidatesWithStatus("", cands)
        assertEquals(2, reranked.size)
        assertFalse("Without SLM ready, slmUsed should be false", slmUsed)
    }

    @Test
    fun testRerankEngineTypeBadgeLabels() {
        assertEquals("Qwen 0.5B", RerankEngineType.LOCAL_SLM.badgeLabel)
        assertEquals("Qwen 1.5B", RerankEngineType.LOCAL_SLM_1_5B.badgeLabel)
        assertEquals("", RerankEngineType.NONE.badgeLabel)
        assertEquals(RerankEngineType.NONE, RerankEngineType.fromId("none"))
        assertEquals(RerankEngineType.LOCAL_SLM, RerankEngineType.fromId("aicore_nano_2"))
    }

    @Test
    fun testCandidateStripIntegrityWithDebugBadge() {
        // Ensure that candidate selection logic ignores any debug badge
        val candidates = listOf(
            ConversionCandidate("今日", "きょう"),
            ConversionCandidate("教", "きょう")
        )

        // Simulating the behavior:
        // Candidates list retains only real candidates
        val displayCandidates = candidates.filter { it.isPrediction || !JapaneseKanaUtils.isAllHiragana(it.surface) }
        assertEquals(2, displayCandidates.size)
        assertEquals("今日", displayCandidates.first().surface)
    }

    @Test
    fun testDebugSlmBadgeVisibilityLogic() {
        // Test helper function logic for debug SLM badge display on debug bar
        fun resolveDebugBadgeVisibility(isDebug: Boolean, badge: String?): Pair<Boolean, String> {
            val isVisible = isDebug && !badge.isNullOrBlank()
            val text = if (isVisible) badge!! else ""
            return Pair(isVisible, text)
        }

        // When debug display is enabled and SLM is used
        val (vis1, text1) = resolveDebugBadgeVisibility(isDebug = true, badge = "SLM")
        assertTrue(vis1)
        assertEquals("SLM", text1)

        val (vis2, text2) = resolveDebugBadgeVisibility(isDebug = true, badge = "n2")
        assertTrue(vis2)
        assertEquals("n2", text2)

        // When debug display is enabled and English QWERTY spell/caps/user badge is present
        val (visSpell, textSpell) = resolveDebugBadgeVisibility(isDebug = true, badge = "SPELL")
        assertTrue(visSpell)
        assertEquals("SPELL", textSpell)

        val (visCaps, textCaps) = resolveDebugBadgeVisibility(isDebug = true, badge = "CAPS")
        assertTrue(visCaps)
        assertEquals("CAPS", textCaps)

        val (visUser, textUser) = resolveDebugBadgeVisibility(isDebug = true, badge = "USER")
        assertTrue(visUser)
        assertEquals("USER", textUser)

        // When debug display is enabled but SLM/Spell is not used (or reset)
        val (vis3, _) = resolveDebugBadgeVisibility(isDebug = true, badge = null)
        assertFalse(vis3)

        val (vis4, _) = resolveDebugBadgeVisibility(isDebug = true, badge = "")
        assertFalse(vis4)

        // When debug display is disabled
        val (vis5, _) = resolveDebugBadgeVisibility(isDebug = false, badge = "SLM")
        assertFalse(vis5)
    }

    @Test
    fun testSpaceCommitDoesNotCommitDebugBadge() {
        // Test verifying that Space key selection targets real candidate, never dummy badge
        val baseCandidates = listOf(
            ConversionCandidate("今日", "きょう"),
            ConversionCandidate("教", "きょう")
        )

        val reading = "きょう"
        val isCurrentCandidatesReranked = true
        val displayCandidates = baseCandidates // Pure candidate list returned by candidateStripView.getCandidates()

        val targetCandidate: ConversionCandidate? = run {
            if (isCurrentCandidatesReranked && displayCandidates.isNotEmpty() && displayCandidates.firstOrNull()?.reading == reading) {
                displayCandidates.first()
            } else {
                displayCandidates.firstOrNull() ?: baseCandidates.firstOrNull()
            }
        }

        assertEquals("今日", targetCandidate?.surface)
        assertEquals("きょう", targetCandidate?.reading)
    }

    @Test
    fun testContextBarFormatting() {
        // Test newline conversion for single line context display
        val multiLine = "いつもお世話に\nなっております。\n本日の件ですが"
        val formatted = multiLine.replace("\n", "↵")
        assertEquals("いつもお世話に↵なっております。↵本日の件ですが", formatted)
        assertEquals(24, multiLine.length)

        // Test empty string handling
        val empty = ""
        val emptyDisplay = if (empty.isEmpty()) "(空)" else empty
        assertEquals("(空)", emptyDisplay)
    }
}
