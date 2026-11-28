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
    fun `homeHeroDecisionTable always exposes SELECT_IME iff imeEnabled and not imeSelected`() {
        val asrFlags = listOf(false, true)
        val wordCounts = listOf(0, 150000)
        val outdatedCounts = listOf(0, 2)

        for (jaAsrReady in asrFlags) {
            for (dictWords in wordCounts) {
                for (outdated in outdatedCounts) {
                    val enabledUnselected = MainAppUiDecisionModel.evaluateHomeHeroWithTrace(
                        HomeHeroInput(
                            imeEnabled = true,
                            imeSelected = false,
                            japaneseAsrReady = jaAsrReady,
                            totalDictWords = dictWords,
                            outdatedDictCount = outdated
                        )
                    )
                    assertEquals("HERO_R2", enabledUnselected.selectedRuleId)
                    assertEquals(HeroActionKind.SELECT_IME, enabledUnselected.result.actionKind)
                    assertEquals("入力方法を切り替える", enabledUnselected.result.ctaLabel)

                    val disabled = MainAppUiDecisionModel.evaluateHomeHero(
                        HomeHeroInput(
                            imeEnabled = false,
                            imeSelected = false,
                            japaneseAsrReady = jaAsrReady,
                            totalDictWords = dictWords,
                            outdatedDictCount = outdated
                        )
                    )
                    assertTrue(disabled.actionKind != HeroActionKind.SELECT_IME)

                    val selected = MainAppUiDecisionModel.evaluateHomeHero(
                        HomeHeroInput(
                            imeEnabled = true,
                            imeSelected = true,
                            japaneseAsrReady = jaAsrReady,
                            totalDictWords = dictWords,
                            outdatedDictCount = outdated
                        )
                    )
                    assertTrue(selected.actionKind != HeroActionKind.SELECT_IME)
                }
            }
        }
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
            AiEngineStatusInput(false, RerankEngineType.LOCAL_SLM_3_5_0_8B, AiRuntimeState.READY, true)
        )
        assertFalse(disabled.showEngineSelector)
        assertEquals("オフ", disabled.badgeText)

        val ready35_08b = MainAppUiDecisionModel.evaluateAiEngineStatus(
            AiEngineStatusInput(true, RerankEngineType.LOCAL_SLM_3_5_0_8B, AiRuntimeState.READY, true)
        )
        assertTrue(ready35_08b.showEngineSelector)
        assertEquals("Qwen3.5 0.8B (Ready)", ready35_08b.badgeText)
        assertEquals(StatusTone.SUCCESS, ready35_08b.tone)

        val ready35_2b = MainAppUiDecisionModel.evaluateAiEngineStatus(
            AiEngineStatusInput(true, RerankEngineType.LOCAL_SLM_3_5_2B, AiRuntimeState.READY, true)
        )
        assertTrue(ready35_2b.showEngineSelector)
        assertEquals("Qwen3.5 2B (Ready)", ready35_2b.badgeText)
        assertEquals(StatusTone.SUCCESS, ready35_2b.tone)
    }

    @Test
    fun `rerankEngineSelectionDecisionTable defaults to Qwen3_5_0_8B when enabled with NONE or null engine`() {
        val traceNone = MainAppUiDecisionModel.evaluateRerankEngineSelectionWithTrace(
            MainAppUiDecisionModel.RerankEngineSelectionInput(
                rerankEnabled = true,
                currentEngine = RerankEngineType.NONE
            )
        )
        assertEquals("RERANK_SEL_DEFAULT", traceNone.selectedRuleId)
        assertTrue(traceNone.result.effectiveRerankEnabled)
        assertEquals(RerankEngineType.LOCAL_SLM_3_5_0_8B, traceNone.result.selectedEngine)
        assertTrue(traceNone.result.shouldInitializeModel)
        assertEquals("default_to_3_5_0_8b_when_enabled", traceNone.result.reason)

        val traceNull = MainAppUiDecisionModel.evaluateRerankEngineSelectionWithTrace(
            MainAppUiDecisionModel.RerankEngineSelectionInput(
                rerankEnabled = true,
                currentEngine = null
            )
        )
        assertEquals("RERANK_SEL_DEFAULT", traceNull.selectedRuleId)
        assertTrue(traceNull.result.effectiveRerankEnabled)
        assertEquals(RerankEngineType.LOCAL_SLM_3_5_0_8B, traceNull.result.selectedEngine)
        assertTrue(traceNull.result.shouldInitializeModel)
    }

    @Test
    fun `rerankEngineSelectionDecisionTable preserves existing engines when rerank is enabled`() {
        val trace35_2b = MainAppUiDecisionModel.evaluateRerankEngineSelectionWithTrace(
            MainAppUiDecisionModel.RerankEngineSelectionInput(
                rerankEnabled = true,
                currentEngine = RerankEngineType.LOCAL_SLM_3_5_2B
            )
        )
        assertEquals("RERANK_SEL_R2", trace35_2b.selectedRuleId)
        assertTrue(trace35_2b.result.effectiveRerankEnabled)
        assertEquals(RerankEngineType.LOCAL_SLM_3_5_2B, trace35_2b.result.selectedEngine)
        assertTrue(trace35_2b.result.shouldInitializeModel)

        val trace35_08b = MainAppUiDecisionModel.evaluateRerankEngineSelectionWithTrace(
            MainAppUiDecisionModel.RerankEngineSelectionInput(
                rerankEnabled = true,
                currentEngine = RerankEngineType.LOCAL_SLM_3_5_0_8B
            )
        )
        assertEquals("RERANK_SEL_R3", trace35_08b.selectedRuleId)
        assertTrue(trace35_08b.result.effectiveRerankEnabled)
        assertEquals(RerankEngineType.LOCAL_SLM_3_5_0_8B, trace35_08b.result.selectedEngine)
        assertTrue(trace35_08b.result.shouldInitializeModel)
    }

    @Test
    fun `rerankEngineSelectionDecisionTable enforces invariant that rerank ON requires a valid selected model`() {
        val testEngines = listOf(
            RerankEngineType.LOCAL_SLM_3_5_0_8B,
            RerankEngineType.LOCAL_SLM_3_5_2B,
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
                "When rerank is enabled, selected model must be a valid Qwen3.5 SLM engine (0.8B or 2B)",
                decision.selectedEngine == RerankEngineType.LOCAL_SLM_3_5_0_8B ||
                    decision.selectedEngine == RerankEngineType.LOCAL_SLM_3_5_2B
            )
        }
    }

    @Test
    fun `rerankEngineSelectionDecisionTable selects NONE and disables rerank when switch is turned off`() {
        val from35_08b = MainAppUiDecisionModel.evaluateRerankEngineSelection(
            MainAppUiDecisionModel.RerankEngineSelectionInput(
                rerankEnabled = false,
                currentEngine = RerankEngineType.LOCAL_SLM_3_5_0_8B
            )
        )
        assertFalse(from35_08b.effectiveRerankEnabled)
        assertEquals(RerankEngineType.NONE, from35_08b.selectedEngine)
        assertFalse(from35_08b.shouldInitializeModel)

        val from35_2b = MainAppUiDecisionModel.evaluateRerankEngineSelection(
            MainAppUiDecisionModel.RerankEngineSelectionInput(
                rerankEnabled = false,
                currentEngine = RerankEngineType.LOCAL_SLM_3_5_2B
            )
        )
        assertFalse(from35_2b.effectiveRerankEnabled)
        assertEquals(RerankEngineType.NONE, from35_2b.selectedEngine)
        assertFalse(from35_2b.shouldInitializeModel)
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

    @Test
    fun `aiEngineStatusDecisionTable shows download CTA when missing and hides while downloading`() {
        val missing = MainAppUiDecisionModel.evaluateAiEngineStatus(
            MainAppUiDecisionModel.AiEngineStatusInput(
                slmEnabled = true,
                engineType = RerankEngineType.LOCAL_SLM_3_5_0_8B,
                runtimeState = MainAppUiDecisionModel.AiRuntimeState.IDLE_OR_ERROR,
                modelInstalled = false,
                isDownloading = false
            )
        )
        assertEquals("未導入", missing.badgeText)
        assertTrue(missing.showDownloadCta)

        val downloading = MainAppUiDecisionModel.evaluateAiEngineStatus(
            MainAppUiDecisionModel.AiEngineStatusInput(
                slmEnabled = true,
                engineType = RerankEngineType.LOCAL_SLM_3_5_0_8B,
                runtimeState = MainAppUiDecisionModel.AiRuntimeState.IDLE_OR_ERROR,
                modelInstalled = false,
                isDownloading = true
            )
        )
        assertEquals("取得中...", downloading.badgeText)
        assertFalse(downloading.showDownloadCta)
    }

    @Test
    fun `slmDownloadPromptDecisionTable prompts only on user selection of missing idle model`() {
        val prompt = MainAppUiDecisionModel.evaluateSlmDownloadPromptWithTrace(
            MainAppUiDecisionModel.SlmDownloadPromptInput(
                isUserSelection = true,
                slmEnabled = true,
                selectedEngine = RerankEngineType.LOCAL_SLM_3_5_0_8B,
                modelInstalled = false,
                isDownloading = false
            )
        )
        assertEquals("SLM_PROMPT_ASK", prompt.selectedRuleId)
        assertTrue(prompt.result.shouldPromptDownload)
        assertEquals(RerankEngineType.LOCAL_SLM_3_5_0_8B, prompt.result.targetEngine)

        val installed = MainAppUiDecisionModel.evaluateSlmDownloadPromptWithTrace(
            MainAppUiDecisionModel.SlmDownloadPromptInput(
                isUserSelection = true,
                slmEnabled = true,
                selectedEngine = RerankEngineType.LOCAL_SLM_3_5_2B,
                modelInstalled = true,
                isDownloading = false
            )
        )
        assertEquals("SLM_PROMPT_R3", installed.selectedRuleId)
        assertFalse(installed.result.shouldPromptDownload)

        val downloading = MainAppUiDecisionModel.evaluateSlmDownloadPromptWithTrace(
            MainAppUiDecisionModel.SlmDownloadPromptInput(
                isUserSelection = true,
                slmEnabled = true,
                selectedEngine = RerankEngineType.LOCAL_SLM_3_5_0_8B,
                modelInstalled = false,
                isDownloading = true
            )
        )
        assertEquals("SLM_PROMPT_R4", downloading.selectedRuleId)
        assertFalse(downloading.result.shouldPromptDownload)

        val nonUser = MainAppUiDecisionModel.evaluateSlmDownloadPromptWithTrace(
            MainAppUiDecisionModel.SlmDownloadPromptInput(
                isUserSelection = false,
                slmEnabled = true,
                selectedEngine = RerankEngineType.LOCAL_SLM_3_5_0_8B,
                modelInstalled = false,
                isDownloading = false
            )
        )
        assertEquals("SLM_PROMPT_R1", nonUser.selectedRuleId)
        assertFalse(nonUser.result.shouldPromptDownload)
    }
}

