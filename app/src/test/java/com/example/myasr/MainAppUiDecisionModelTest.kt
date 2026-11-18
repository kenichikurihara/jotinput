package com.example.myasr

import com.example.myasr.context.ime.domain.MainAppUiDecisionModel
import com.example.myasr.context.ime.domain.MainAppUiDecisionModel.AiEngineStatusInput
import com.example.myasr.context.ime.domain.MainAppUiDecisionModel.AiRuntimeState
import com.example.myasr.context.ime.domain.MainAppUiDecisionModel.HeroActionKind
import com.example.myasr.context.ime.domain.MainAppUiDecisionModel.HomeHeroInput
import com.example.myasr.context.ime.domain.MainAppUiDecisionModel.ProvisioningBannerInput
import com.example.myasr.context.ime.domain.MainAppUiDecisionModel.StatusTone
import com.example.myasr.context.reranking.domain.RerankEngineType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MainAppUiDecisionModelTest {

    @Test
    fun `homeHeroDecisionTable prioritizes ENABLE_IME over all other missing items`() {
        val trace = MainAppUiDecisionModel.evaluateHomeHeroWithTrace(
            HomeHeroInput(
                imeEnabled = false,
                imeSelected = false,
                japaneseAsrReady = false,
                totalDictWords = 0,
                outdatedDictCount = 3
            )
        )
        assertEquals("HERO_R1", trace.selectedRuleId)
        assertEquals(HeroActionKind.ENABLE_IME, trace.result.actionKind)
        assertEquals(StatusTone.WARNING, trace.result.tone)
    }

    @Test
    fun `homeHeroDecisionTable progresses through SELECT_IME, DOWNLOAD_JA_ASR, DOWNLOAD_DICTS, UPDATE_DICTS, and READY`() {
        val selectIme = MainAppUiDecisionModel.evaluateHomeHero(
            HomeHeroInput(true, false, false, 0, 0)
        )
        assertEquals(HeroActionKind.SELECT_IME, selectIme.actionKind)

        val jaAsr = MainAppUiDecisionModel.evaluateHomeHero(
            HomeHeroInput(true, true, false, 0, 0)
        )
        assertEquals(HeroActionKind.DOWNLOAD_JA_ASR, jaAsr.actionKind)

        val dicts = MainAppUiDecisionModel.evaluateHomeHero(
            HomeHeroInput(true, true, true, 0, 0)
        )
        assertEquals(HeroActionKind.DOWNLOAD_DICTS, dicts.actionKind)

        val update = MainAppUiDecisionModel.evaluateHomeHero(
            HomeHeroInput(true, true, true, 150000, 2)
        )
        assertEquals(HeroActionKind.UPDATE_DICTS, update.actionKind)
        assertTrue(update.badgeText.contains("2"))

        val ready = MainAppUiDecisionModel.evaluateHomeHero(
            HomeHeroInput(true, true, true, 150000, 0)
        )
        assertEquals(HeroActionKind.READY, ready.actionKind)
        assertEquals(StatusTone.SUCCESS, ready.tone)
        assertNull(ready.ctaLabel)
    }

    @Test
    fun `provisioningBannerDecisionTable handles active, queued, and idle states`() {
        val active = MainAppUiDecisionModel.evaluateProvisioningBanner(
            ProvisioningBannerInput(hasActiveTask = true, queuedCount = 1, progress = 42, statusText = "SudachiDict 取得中...")
        )
        assertTrue(active.isVisible)
        assertFalse(active.isIndeterminate)
        assertEquals(42, active.progress)
        assertTrue(active.title.contains("待機中: 1件"))

        val queued = MainAppUiDecisionModel.evaluateProvisioningBanner(
            ProvisioningBannerInput(hasActiveTask = false, queuedCount = 2, progress = 0, statusText = "")
        )
        assertTrue(queued.isVisible)
        assertTrue(queued.isIndeterminate)

        val idle = MainAppUiDecisionModel.evaluateProvisioningBanner(
            ProvisioningBannerInput(hasActiveTask = false, queuedCount = 0, progress = 0, statusText = "")
        )
        assertFalse(idle.isVisible)
    }

    @Test
    fun `aiEngineStatusDecisionTable hides selector when disabled and reflects runtime state when enabled`() {
        val disabled = MainAppUiDecisionModel.evaluateAiEngineStatus(
            AiEngineStatusInput(false, RerankEngineType.LOCAL_SLM, AiRuntimeState.READY, true)
        )
        assertFalse(disabled.showEngineSelector)
        assertEquals("オフ", disabled.badgeText)

        val ready15b = MainAppUiDecisionModel.evaluateAiEngineStatus(
            AiEngineStatusInput(true, RerankEngineType.LOCAL_SLM_1_5B, AiRuntimeState.READY, true)
        )
        assertTrue(ready15b.showEngineSelector)
        assertEquals("Qwen 1.5B (Ready)", ready15b.badgeText)
        assertEquals(StatusTone.SUCCESS, ready15b.tone)
    }

    @Test
    fun `rerankEngineSelectionDecisionTable defaults to 0_5B when enabled with NONE or null engine`() {
        val traceNone = MainAppUiDecisionModel.evaluateRerankEngineSelectionWithTrace(
            MainAppUiDecisionModel.RerankEngineSelectionInput(
                rerankEnabled = true,
                currentEngine = RerankEngineType.NONE
            )
        )
        assertEquals("RERANK_SEL_DEFAULT", traceNone.selectedRuleId)
        assertTrue(traceNone.result.effectiveRerankEnabled)
        assertEquals(RerankEngineType.LOCAL_SLM, traceNone.result.selectedEngine)
        assertTrue(traceNone.result.shouldInitializeModel)
        assertEquals("default_to_0_5b_when_enabled", traceNone.result.reason)

        val traceNull = MainAppUiDecisionModel.evaluateRerankEngineSelectionWithTrace(
            MainAppUiDecisionModel.RerankEngineSelectionInput(
                rerankEnabled = true,
                currentEngine = null
            )
        )
        assertEquals("RERANK_SEL_DEFAULT", traceNull.selectedRuleId)
        assertTrue(traceNull.result.effectiveRerankEnabled)
        assertEquals(RerankEngineType.LOCAL_SLM, traceNull.result.selectedEngine)
        assertTrue(traceNull.result.shouldInitializeModel)
    }

    @Test
    fun `rerankEngineSelectionDecisionTable preserves existing 1_5B and 0_5B when rerank is enabled`() {
        val trace15b = MainAppUiDecisionModel.evaluateRerankEngineSelectionWithTrace(
            MainAppUiDecisionModel.RerankEngineSelectionInput(
                rerankEnabled = true,
                currentEngine = RerankEngineType.LOCAL_SLM_1_5B
            )
        )
        assertEquals("RERANK_SEL_R2", trace15b.selectedRuleId)
        assertTrue(trace15b.result.effectiveRerankEnabled)
        assertEquals(RerankEngineType.LOCAL_SLM_1_5B, trace15b.result.selectedEngine)
        assertTrue(trace15b.result.shouldInitializeModel)

        val trace05b = MainAppUiDecisionModel.evaluateRerankEngineSelectionWithTrace(
            MainAppUiDecisionModel.RerankEngineSelectionInput(
                rerankEnabled = true,
                currentEngine = RerankEngineType.LOCAL_SLM
            )
        )
        assertEquals("RERANK_SEL_R3", trace05b.selectedRuleId)
        assertTrue(trace05b.result.effectiveRerankEnabled)
        assertEquals(RerankEngineType.LOCAL_SLM, trace05b.result.selectedEngine)
        assertTrue(trace05b.result.shouldInitializeModel)
    }

    @Test
    fun `rerankEngineSelectionDecisionTable enforces invariant that rerank ON requires a valid selected model`() {
        val testEngines = listOf(
            RerankEngineType.LOCAL_SLM,
            RerankEngineType.LOCAL_SLM_1_5B,
            RerankEngineType.NONE,
            null
        )
        for (engine in testEngines) {
            val decision = MainAppUiDecisionModel.evaluateRerankEngineSelection(
                MainAppUiDecisionModel.RerankEngineSelectionInput(
                    rerankEnabled = true,
                    currentEngine = engine
                )
            )
            assertTrue(
                "When rerank is enabled, effectiveRerankEnabled must be true for engine: $engine",
                decision.effectiveRerankEnabled
            )
            org.junit.Assert.assertNotEquals(
                "When rerank is enabled, selectedEngine must NEVER be NONE. Invariant violated for input: $engine",
                RerankEngineType.NONE,
                decision.selectedEngine
            )
            assertTrue(
                "When rerank is enabled, selected model must be either 0.5B or 1.5B",
                decision.selectedEngine == RerankEngineType.LOCAL_SLM ||
                    decision.selectedEngine == RerankEngineType.LOCAL_SLM_1_5B
            )
        }
    }

    @Test
    fun `rerankEngineSelectionDecisionTable selects NONE and disables rerank when switch is turned off`() {
        val from05b = MainAppUiDecisionModel.evaluateRerankEngineSelection(
            MainAppUiDecisionModel.RerankEngineSelectionInput(
                rerankEnabled = false,
                currentEngine = RerankEngineType.LOCAL_SLM
            )
        )
        assertFalse(from05b.effectiveRerankEnabled)
        assertEquals(RerankEngineType.NONE, from05b.selectedEngine)
        assertFalse(from05b.shouldInitializeModel)

        val from15b = MainAppUiDecisionModel.evaluateRerankEngineSelection(
            MainAppUiDecisionModel.RerankEngineSelectionInput(
                rerankEnabled = false,
                currentEngine = RerankEngineType.LOCAL_SLM_1_5B
            )
        )
        assertFalse(from15b.effectiveRerankEnabled)
        assertEquals(RerankEngineType.NONE, from15b.selectedEngine)
        assertFalse(from15b.shouldInitializeModel)
    }

    @Test
    fun `bottomNavVisibilityDecisionTable hides navigation when IME is visible to prevent upward scroll displacement`() {
        val visibleTrace = MainAppUiDecisionModel.evaluateBottomNavVisibilityWithTrace(
            MainAppUiDecisionModel.BottomNavVisibilityInput(isImeVisible = true)
        )
        assertEquals("NAV_VIS_R1", visibleTrace.selectedRuleId)
        assertEquals(MainAppUiDecisionModel.NavigationVisibility.GONE, visibleTrace.result.visibility)
        assertEquals("hide_on_ime_visible", visibleTrace.result.reason)

        val hiddenTrace = MainAppUiDecisionModel.evaluateBottomNavVisibilityWithTrace(
            MainAppUiDecisionModel.BottomNavVisibilityInput(isImeVisible = false)
        )
        assertEquals("NAV_VIS_DEFAULT", hiddenTrace.selectedRuleId)
        assertEquals(MainAppUiDecisionModel.NavigationVisibility.VISIBLE, hiddenTrace.result.visibility)
        assertEquals("show_on_ime_hidden", hiddenTrace.result.reason)
    }
}

