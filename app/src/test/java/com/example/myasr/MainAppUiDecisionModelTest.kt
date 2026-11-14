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
}
