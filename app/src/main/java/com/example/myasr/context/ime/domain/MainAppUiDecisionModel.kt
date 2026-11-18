package com.example.myasr.context.ime.domain

import com.example.myasr.context.reranking.domain.RerankEngineType
import com.example.myasr.shared.domain.dmn.DecisionTable
import com.example.myasr.shared.domain.dmn.DecisionTrace
import com.example.myasr.shared.domain.dmn.HitPolicy
import com.example.myasr.shared.domain.dmn.decisionTable

/**
 * BC-7 (IME Session & Main App UI) におけるメイン画面状態決定のための
 * 純粋ドメイン DMN DecisionModel。
 */
object MainAppUiDecisionModel {

    enum class StatusTone {
        SUCCESS,
        INFO,
        WARNING,
        NEUTRAL
    }

    enum class HeroActionKind {
        ENABLE_IME,
        SELECT_IME,
        DOWNLOAD_JA_ASR,
        DOWNLOAD_DICTS,
        UPDATE_DICTS,
        READY
    }

    data class HomeHeroInput(
        val imeEnabled: Boolean,
        val imeSelected: Boolean,
        val japaneseAsrReady: Boolean,
        val totalDictWords: Int,
        val outdatedDictCount: Int
    )

    data class HomeHeroDecision(
        val actionKind: HeroActionKind,
        val badgeText: String,
        val title: String,
        val description: String,
        val ctaLabel: String?,
        val tone: StatusTone
    )

    val homeHeroDecisionTable: DecisionTable<HomeHeroInput, HomeHeroDecision> =
        decisionTable(
            name = "HomeHeroActionDecisionTable",
            hitPolicy = HitPolicy.FIRST
        ) {
            rule(id = "HERO_R1", description = "Step 1: Enable JotInput IME in Android Settings") {
                `when` { !it.imeEnabled }
                thenValue(
                    HomeHeroDecision(
                        actionKind = HeroActionKind.ENABLE_IME,
                        badgeText = "Step 1 / 2",
                        title = "キーボードを有効にする",
                        description = "まずAndroidの設定で「JotInput」をオンにしてください。",
                        ctaLabel = "キーボード設定を開く",
                        tone = StatusTone.WARNING
                    )
                )
            }
            rule(id = "HERO_R2", description = "Step 2: Switch active input method to JotInput") {
                `when` { it.imeEnabled && !it.imeSelected }
                thenValue(
                    HomeHeroDecision(
                        actionKind = HeroActionKind.SELECT_IME,
                        badgeText = "Step 2 / 2",
                        title = "入力方法を JotInput に切替",
                        description = "有効化されました。使用するキーボードとして選択してください。",
                        ctaLabel = "入力方法を切り替える",
                        tone = StatusTone.INFO
                    )
                )
            }
            rule(id = "HERO_R3", description = "Download Japanese ReazonSpeech ASR model") {
                `when` { !it.japaneseAsrReady }
                thenValue(
                    HomeHeroDecision(
                        actionKind = HeroActionKind.DOWNLOAD_JA_ASR,
                        badgeText = "音声モデル未取得",
                        title = "音声認識モデルを取得 (~160MB)",
                        description = "完全オフラインの日本語音声入力を有効にするためモデルを取得してください。",
                        ctaLabel = "音声モデルを取得",
                        tone = StatusTone.WARNING
                    )
                )
            }
            rule(id = "HERO_R4", description = "Download recommended Japanese dictionaries") {
                `when` { it.totalDictWords <= 0 }
                thenValue(
                    HomeHeroDecision(
                        actionKind = HeroActionKind.DOWNLOAD_DICTS,
                        badgeText = "辞書未取得",
                        title = "推奨変換辞書を取得",
                        description = "SudachiDict・Wikipedia等の拡張辞書を導入すると変換精度が大幅に向上します。",
                        ctaLabel = "推奨辞書を一括取得",
                        tone = StatusTone.INFO
                    )
                )
            }
            rule(id = "HERO_R5", description = "Update outdated dictionaries") {
                `when` { it.outdatedDictCount > 0 }
                then { input ->
                    HomeHeroDecision(
                        actionKind = HeroActionKind.UPDATE_DICTS,
                        badgeText = "更新あり (${input.outdatedDictCount}件)",
                        title = "新しい辞書データがあります",
                        description = "${input.outdatedDictCount}件の拡張辞書に最新版が公開されています。",
                        ctaLabel = "今すぐアップデート",
                        tone = StatusTone.WARNING
                    )
                }
            }
            defaultValue(
                id = "HERO_READY",
                description = "All prerequisites met; ready for voice and touch input",
                value = HomeHeroDecision(
                    actionKind = HeroActionKind.READY,
                    badgeText = "準備完了",
                    title = "すぐに入力できます",
                    description = "下のテスト欄をタップして、音声入力やフリック・スワイプをお試しください。",
                    ctaLabel = null,
                    tone = StatusTone.SUCCESS
                )
            )
        }

    fun evaluateHomeHero(input: HomeHeroInput): HomeHeroDecision =
        homeHeroDecisionTable.evaluate(input)

    fun evaluateHomeHeroWithTrace(input: HomeHeroInput): DecisionTrace<HomeHeroDecision> =
        homeHeroDecisionTable.evaluateWithTrace(input)

    // ------------------------------------------------------------------------

    data class ProvisioningBannerInput(
        val hasActiveTask: Boolean,
        val queuedCount: Int,
        val progress: Int,
        val statusText: String
    )

    data class ProvisioningBannerDecision(
        val isVisible: Boolean,
        val title: String,
        val progress: Int,
        val isIndeterminate: Boolean
    )

    val provisioningBannerDecisionTable: DecisionTable<ProvisioningBannerInput, ProvisioningBannerDecision> =
        decisionTable(
            name = "ProvisioningBannerDecisionTable",
            hitPolicy = HitPolicy.FIRST
        ) {
            rule(id = "PROV_R1", description = "Show determinate progress when task is actively running") {
                `when` { it.hasActiveTask }
                then { input ->
                    val suffix = if (input.queuedCount > 0) " (待機中: ${input.queuedCount}件)" else ""
                    ProvisioningBannerDecision(
                        isVisible = true,
                        title = input.statusText.ifBlank { "バックグラウンドで準備中..." } + suffix,
                        progress = input.progress.coerceIn(0, 100),
                        isIndeterminate = false
                    )
                }
            }
            rule(id = "PROV_R2", description = "Show indeterminate progress when tasks are queued") {
                `when` { it.queuedCount > 0 }
                then { input ->
                    ProvisioningBannerDecision(
                        isVisible = true,
                        title = "ダウンロード待機中 (${input.queuedCount}件)...",
                        progress = 0,
                        isIndeterminate = true
                    )
                }
            }
            defaultValue(
                id = "PROV_IDLE",
                description = "Hide global banner when no provisioning tasks are active or queued",
                value = ProvisioningBannerDecision(
                    isVisible = false,
                    title = "",
                    progress = 0,
                    isIndeterminate = false
                )
            )
        }

    fun evaluateProvisioningBanner(input: ProvisioningBannerInput): ProvisioningBannerDecision =
        provisioningBannerDecisionTable.evaluate(input)

    // ------------------------------------------------------------------------

    enum class AiRuntimeState {
        READY,
        INITIALIZING,
        IDLE_OR_ERROR
    }

    data class AiEngineStatusInput(
        val slmEnabled: Boolean,
        val engineType: RerankEngineType,
        val runtimeState: AiRuntimeState,
        val modelInstalled: Boolean
    )

    data class AiEngineStatusDecision(
        val showEngineSelector: Boolean,
        val badgeText: String,
        val descriptionText: String,
        val tone: StatusTone
    )

    val aiEngineStatusDecisionTable: DecisionTable<AiEngineStatusInput, AiEngineStatusDecision> =
        decisionTable(
            name = "AiEngineStatusDecisionTable",
            hitPolicy = HitPolicy.FIRST
        ) {
            rule(id = "AI_R1", description = "AI engine disabled or NONE") {
                `when` { !it.slmEnabled || it.engineType == RerankEngineType.NONE }
                thenValue(
                    AiEngineStatusDecision(
                        showEngineSelector = false,
                        badgeText = "オフ",
                        descriptionText = "AI 変換エンジンは無効です (Mozc辞書・Viterbiのみ使用)",
                        tone = StatusTone.NEUTRAL
                    )
                )
            }
            rule(id = "AI_R2", description = "Selected AI engine is ready") {
                `when` { it.runtimeState == AiRuntimeState.READY }
                then { input ->
                    val label = if (input.engineType == RerankEngineType.LOCAL_SLM_1_5B) "Qwen 1.5B" else "Qwen 0.5B"
                    val full = if (input.engineType == RerankEngineType.LOCAL_SLM_1_5B) "Qwen2.5-1.5B" else "Qwen2.5-0.5B"
                    AiEngineStatusDecision(
                        showEngineSelector = true,
                        badgeText = "$label (Ready)",
                        descriptionText = "$full 稼働中 (文脈補正・かな漢字変換)",
                        tone = StatusTone.SUCCESS
                    )
                }
            }
            rule(id = "AI_R3", description = "Selected AI engine is initializing") {
                `when` { it.runtimeState == AiRuntimeState.INITIALIZING }
                then { input ->
                    val full = if (input.engineType == RerankEngineType.LOCAL_SLM_1_5B) "Qwen2.5-1.5B" else "Qwen2.5-0.5B"
                    AiEngineStatusDecision(
                        showEngineSelector = true,
                        badgeText = "読込中...",
                        descriptionText = "$full モデルをメモリにロードしています...",
                        tone = StatusTone.INFO
                    )
                }
            }
            rule(id = "AI_R4", description = "Selected AI engine installed on disk and idle") {
                `when` { it.modelInstalled }
                then { input ->
                    val full = if (input.engineType == RerankEngineType.LOCAL_SLM_1_5B) "Qwen2.5-1.5B" else "Qwen2.5-0.5B"
                    AiEngineStatusDecision(
                        showEngineSelector = true,
                        badgeText = "待機中",
                        descriptionText = "$full 検出済み。選択で初期化します",
                        tone = StatusTone.NEUTRAL
                    )
                }
            }
            defaultValue(
                id = "AI_MISSING",
                description = "Selected AI model file is not installed",
                value = AiEngineStatusDecision(
                    showEngineSelector = true,
                    badgeText = "未導入",
                    descriptionText = "モデルファイルが端末に未配置です",
                    tone = StatusTone.NEUTRAL
                )
            )
        }

    fun evaluateAiEngineStatus(input: AiEngineStatusInput): AiEngineStatusDecision =
        aiEngineStatusDecisionTable.evaluate(input)

    // ------------------------------------------------------------------------

    data class RerankEngineSelectionInput(
        val rerankEnabled: Boolean,
        val currentEngine: RerankEngineType?
    )

    data class RerankEngineSelectionDecision(
        val effectiveRerankEnabled: Boolean,
        val selectedEngine: RerankEngineType,
        val shouldInitializeModel: Boolean,
        val reason: String
    )

    val rerankEngineSelectionDecisionTable: DecisionTable<RerankEngineSelectionInput, RerankEngineSelectionDecision> =
        decisionTable(
            name = "RerankEngineSelectionDecisionTable",
            hitPolicy = HitPolicy.FIRST
        ) {
            rule(id = "RERANK_SEL_R1", description = "Disable rerank engine when switch is turned off") {
                `when` { !it.rerankEnabled }
                thenValue(
                    RerankEngineSelectionDecision(
                        effectiveRerankEnabled = false,
                        selectedEngine = RerankEngineType.NONE,
                        shouldInitializeModel = false,
                        reason = "rerank_disabled"
                    )
                )
            }
            rule(id = "RERANK_SEL_R2", description = "Keep 1.5B model when rerank is enabled and 1.5B was active") {
                `when` { it.currentEngine == RerankEngineType.LOCAL_SLM_1_5B }
                thenValue(
                    RerankEngineSelectionDecision(
                        effectiveRerankEnabled = true,
                        selectedEngine = RerankEngineType.LOCAL_SLM_1_5B,
                        shouldInitializeModel = true,
                        reason = "keep_1_5b"
                    )
                )
            }
            rule(id = "RERANK_SEL_R3", description = "Keep 0.5B model when rerank is enabled and 0.5B was active") {
                `when` { it.currentEngine == RerankEngineType.LOCAL_SLM }
                thenValue(
                    RerankEngineSelectionDecision(
                        effectiveRerankEnabled = true,
                        selectedEngine = RerankEngineType.LOCAL_SLM,
                        shouldInitializeModel = true,
                        reason = "keep_0_5b"
                    )
                )
            }
            defaultValue(
                id = "RERANK_SEL_DEFAULT",
                description = "Default to 0.5B model when rerank is enabled without a valid active model (invariant: enabled rerank requires a selected model)",
                value = RerankEngineSelectionDecision(
                    effectiveRerankEnabled = true,
                    selectedEngine = RerankEngineType.LOCAL_SLM,
                    shouldInitializeModel = true,
                    reason = "default_to_0_5b_when_enabled"
                )
            )
        }

    fun evaluateRerankEngineSelection(input: RerankEngineSelectionInput): RerankEngineSelectionDecision =
        rerankEngineSelectionDecisionTable.evaluate(input)

    fun evaluateRerankEngineSelectionWithTrace(input: RerankEngineSelectionInput): DecisionTrace<RerankEngineSelectionDecision> =
        rerankEngineSelectionDecisionTable.evaluateWithTrace(input)

    enum class NavigationVisibility {
        VISIBLE,
        GONE
    }

    data class BottomNavVisibilityInput(
        val isImeVisible: Boolean
    )

    data class BottomNavVisibilityDecision(
        val visibility: NavigationVisibility,
        val reason: String
    )

    val bottomNavVisibilityDecisionTable: DecisionTable<BottomNavVisibilityInput, BottomNavVisibilityDecision> =
        decisionTable(
            name = "BottomNavVisibilityDecisionTable",
            hitPolicy = HitPolicy.FIRST
        ) {
            rule(id = "NAV_VIS_R1", description = "Hide bottom navigation bar when IME is visible to prevent upward scroll displacement and eliminate whitespace") {
                `when` { it.isImeVisible }
                thenValue(
                    BottomNavVisibilityDecision(
                        visibility = NavigationVisibility.GONE,
                        reason = "hide_on_ime_visible"
                    )
                )
            }
            defaultValue(
                id = "NAV_VIS_DEFAULT",
                description = "Show bottom navigation bar when IME is hidden",
                value = BottomNavVisibilityDecision(
                    visibility = NavigationVisibility.VISIBLE,
                    reason = "show_on_ime_hidden"
                )
            )
        }

    fun evaluateBottomNavVisibility(input: BottomNavVisibilityInput): BottomNavVisibilityDecision =
        bottomNavVisibilityDecisionTable.evaluate(input)

    fun evaluateBottomNavVisibilityWithTrace(input: BottomNavVisibilityInput): DecisionTrace<BottomNavVisibilityDecision> =
        bottomNavVisibilityDecisionTable.evaluateWithTrace(input)
}


