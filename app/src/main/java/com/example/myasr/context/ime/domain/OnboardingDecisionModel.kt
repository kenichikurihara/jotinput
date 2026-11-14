package com.example.myasr.context.ime.domain

import com.example.myasr.context.ime.domain.MainAppUiDecisionModel.StatusTone
import com.example.myasr.shared.domain.dmn.DecisionTable
import com.example.myasr.shared.domain.dmn.DecisionTrace
import com.example.myasr.shared.domain.dmn.HitPolicy
import com.example.myasr.shared.domain.dmn.decisionTable

/**
 * BC-7 (IME Session & UI) におけるオンボーディングフローの純粋ドメイン DMN 決定モデル。
 *
 * - OnboardingLaunchDecisionTable: 起動時のオンボーディング要否・再インストール/Auto Backup 検知
 * - DriveRestoreStatusDecisionTable: Google Drive 履歴復元・SAF URI 権限失効検知
 * - OnboardingStepGuidanceDecisionTable: 各ステップの非ブロッキング進行・CTA・スキップ制御
 */
object OnboardingDecisionModel {

    enum class OnboardingMode {
        FRESH_INSTALL,
        REINSTALL,
        DRIVE_RESTORE
    }

    enum class OnboardingStep {
        WELCOME,
        DATA_PREP,
        DRIVE_RESTORE,
        PERMISSIONS_AND_IME,
        COMPLETE_AND_TEST
    }

    // ------------------------------------------------------------------------
    // 1. OnboardingLaunchDecisionTable
    // ------------------------------------------------------------------------

    enum class LaunchAction {
        LAUNCH_EXPLICIT,
        AUTO_MIGRATE_COMPLETE,
        LAUNCH_REINSTALL_DRIVE_HINT,
        LAUNCH_REINSTALL,
        LAUNCH_FRESH,
        SKIP_TO_MAIN
    }

    data class OnboardingLaunchInput(
        val forceLaunch: Boolean,
        val onboardingCompleted: Boolean,
        val imeEnabled: Boolean,
        val japaneseAsrReady: Boolean,
        val totalDictWords: Int,
        val hasLocalCustomDict: Boolean,
        val hasStoredDriveUri: Boolean
    )

    data class OnboardingLaunchDecision(
        val action: LaunchAction,
        val shouldShowOnboarding: Boolean,
        val shouldMarkCompletedSilently: Boolean,
        val suggestedMode: OnboardingMode
    )

    val onboardingLaunchDecisionTable: DecisionTable<OnboardingLaunchInput, OnboardingLaunchDecision> =
        decisionTable(
            name = "OnboardingLaunchDecisionTable",
            hitPolicy = HitPolicy.FIRST
        ) {
            rule(id = "OB_LAUNCH_R1", description = "Explicit launch requested from Settings or Home") {
                `when` { it.forceLaunch }
                then { input ->
                    val mode = if (input.hasStoredDriveUri || input.hasLocalCustomDict) {
                        OnboardingMode.DRIVE_RESTORE
                    } else {
                        OnboardingMode.FRESH_INSTALL
                    }
                    OnboardingLaunchDecision(
                        action = LaunchAction.LAUNCH_EXPLICIT,
                        shouldShowOnboarding = true,
                        shouldMarkCompletedSilently = false,
                        suggestedMode = mode
                    )
                }
            }
            rule(
                id = "OB_LAUNCH_R2",
                description = "Existing user upgrade: IME already enabled and model or dicts present"
            ) {
                `when` {
                    !it.onboardingCompleted &&
                        it.imeEnabled &&
                        (it.japaneseAsrReady || it.totalDictWords > 0)
                }
                thenValue(
                    OnboardingLaunchDecision(
                        action = LaunchAction.AUTO_MIGRATE_COMPLETE,
                        shouldShowOnboarding = false,
                        shouldMarkCompletedSilently = true,
                        suggestedMode = OnboardingMode.FRESH_INSTALL
                    )
                )
            }
            rule(
                id = "OB_LAUNCH_R3",
                description = "Auto-Backup reinstall detected: flag was backed up, but IME & binaries are missing"
            ) {
                `when` {
                    it.onboardingCompleted &&
                        !it.imeEnabled &&
                        !it.japaneseAsrReady &&
                        it.totalDictWords <= 0
                }
                then { input ->
                    val hasPriorUserData = input.hasStoredDriveUri || input.hasLocalCustomDict
                    OnboardingLaunchDecision(
                        action = if (hasPriorUserData) {
                            LaunchAction.LAUNCH_REINSTALL_DRIVE_HINT
                        } else {
                            LaunchAction.LAUNCH_REINSTALL
                        },
                        shouldShowOnboarding = true,
                        shouldMarkCompletedSilently = false,
                        suggestedMode = if (hasPriorUserData) {
                            OnboardingMode.DRIVE_RESTORE
                        } else {
                            OnboardingMode.REINSTALL
                        }
                    )
                }
            }
            rule(id = "OB_LAUNCH_R4", description = "Onboarding already completed and environment intact") {
                `when` { it.onboardingCompleted }
                thenValue(
                    OnboardingLaunchDecision(
                        action = LaunchAction.SKIP_TO_MAIN,
                        shouldShowOnboarding = false,
                        shouldMarkCompletedSilently = false,
                        suggestedMode = OnboardingMode.FRESH_INSTALL
                    )
                )
            }
            rule(
                id = "OB_LAUNCH_R5",
                description = "Fresh install with Auto-Backup custom dictionary or Drive URI present"
            ) {
                `when` { it.hasStoredDriveUri || it.hasLocalCustomDict }
                thenValue(
                    OnboardingLaunchDecision(
                        action = LaunchAction.LAUNCH_REINSTALL_DRIVE_HINT,
                        shouldShowOnboarding = true,
                        shouldMarkCompletedSilently = false,
                        suggestedMode = OnboardingMode.DRIVE_RESTORE
                    )
                )
            }
            defaultValue(
                id = "OB_LAUNCH_FRESH",
                description = "Clean fresh install",
                value = OnboardingLaunchDecision(
                    action = LaunchAction.LAUNCH_FRESH,
                    shouldShowOnboarding = true,
                    shouldMarkCompletedSilently = false,
                    suggestedMode = OnboardingMode.FRESH_INSTALL
                )
            )
        }

    fun evaluateLaunch(input: OnboardingLaunchInput): OnboardingLaunchDecision =
        onboardingLaunchDecisionTable.evaluate(input)

    fun evaluateLaunchWithTrace(input: OnboardingLaunchInput): DecisionTrace<OnboardingLaunchDecision> =
        onboardingLaunchDecisionTable.evaluateWithTrace(input)

    // ------------------------------------------------------------------------
    // 2. DriveRestoreStatusDecisionTable
    // ------------------------------------------------------------------------

    enum class DriveRestoreUiState {
        SYNCING,
        RESTORED_OK,
        ERROR,
        PERMISSION_LOST,
        CONNECTED_IDLE,
        NOT_CONNECTED
    }

    data class DriveRestoreInput(
        val isSyncing: Boolean,
        val hasStoredUri: Boolean,
        val hasValidPermission: Boolean,
        val lastSyncSuccess: Boolean,
        val lastRestoredCount: Int,
        val lastErrorMessage: String?
    )

    data class DriveRestoreDecision(
        val uiState: DriveRestoreUiState,
        val badgeText: String,
        val descriptionText: String,
        val primaryButtonLabel: String,
        val tone: StatusTone
    )

    val driveRestoreStatusDecisionTable: DecisionTable<DriveRestoreInput, DriveRestoreDecision> =
        decisionTable(
            name = "DriveRestoreStatusDecisionTable",
            hitPolicy = HitPolicy.FIRST
        ) {
            rule(id = "DRIVE_R1", description = "Sync/restore in progress") {
                `when` { it.isSyncing }
                thenValue(
                    DriveRestoreDecision(
                        uiState = DriveRestoreUiState.SYNCING,
                        badgeText = "復元中...",
                        descriptionText = "Google Drive の履歴ファイルと安全にマージしています...",
                        primaryButtonLabel = "同期処理中...",
                        tone = StatusTone.INFO
                    )
                )
            }
            rule(id = "DRIVE_R2", description = "Successfully restored from Google Drive") {
                `when` { it.lastSyncSuccess }
                then { input ->
                    val countDesc = if (input.lastRestoredCount > 0) {
                        "（単語・学習履歴 ${input.lastRestoredCount}件を反映済み）"
                    } else {
                        "（最新の履歴と同期済み）"
                    }
                    DriveRestoreDecision(
                        uiState = DriveRestoreUiState.RESTORED_OK,
                        badgeText = "復元完了 ✓",
                        descriptionText = "Google Drive との履歴マージが完了しました$countDesc。",
                        primaryButtonLabel = "もう一度同期する",
                        tone = StatusTone.SUCCESS
                    )
                }
            }
            rule(id = "DRIVE_R3", description = "Sync failed (corrupt file, offline, or access error)") {
                `when` { !it.lastErrorMessage.isNullOrBlank() }
                then { input ->
                    DriveRestoreDecision(
                        uiState = DriveRestoreUiState.ERROR,
                        badgeText = "復元エラー",
                        descriptionText = "${input.lastErrorMessage}（ローカルデータは保護されています。再試行または別のファイルを選択できます）",
                        primaryButtonLabel = "ファイルを再選択して復元",
                        tone = StatusTone.WARNING
                    )
                }
            }
            rule(
                id = "DRIVE_R4",
                description = "Auto-Backup restored URI string, but SAF persistable permission expired"
            ) {
                `when` { it.hasStoredUri && !it.hasValidPermission }
                thenValue(
                    DriveRestoreDecision(
                        uiState = DriveRestoreUiState.PERMISSION_LOST,
                        badgeText = "権限の再許可が必要",
                        descriptionText = "以前の同期先設定が見つかりましたが、再インストールによりアクセス権限がリセットされています。Google Drive 上のファイル（jotinput_history_sync.json）を再選択してください。",
                        primaryButtonLabel = "Google Drive ファイルを選択して再連携",
                        tone = StatusTone.WARNING
                    )
                )
            }
            rule(id = "DRIVE_R5", description = "Drive URI connected with valid permission") {
                `when` { it.hasStoredUri && it.hasValidPermission }
                thenValue(
                    DriveRestoreDecision(
                        uiState = DriveRestoreUiState.CONNECTED_IDLE,
                        badgeText = "連携済み",
                        descriptionText = "設定済みの Google Drive ファイルから履歴と単語を安全にマージ復元できます。",
                        primaryButtonLabel = "今すぐ履歴を復元・同期",
                        tone = StatusTone.INFO
                    )
                )
            }
            defaultValue(
                id = "DRIVE_NOT_CONNECTED",
                description = "No Google Drive sync file linked yet",
                value = DriveRestoreDecision(
                    uiState = DriveRestoreUiState.NOT_CONNECTED,
                    badgeText = "未連携",
                    descriptionText = "別端末や以前保存した Google Drive の履歴ファイル（jotinput_history_sync.json）があれば、ここから復元できます。新規作成も可能です。",
                    primaryButtonLabel = "Google Drive のファイルを選択",
                    tone = StatusTone.NEUTRAL
                )
            )
        }

    fun evaluateDriveRestoreStatus(input: DriveRestoreInput): DriveRestoreDecision =
        driveRestoreStatusDecisionTable.evaluate(input)

    // ------------------------------------------------------------------------
    // 3. OnboardingStepGuidanceDecisionTable
    // ------------------------------------------------------------------------

    data class StepGuidanceInput(
        val step: OnboardingStep,
        val mode: OnboardingMode,
        val provisioningActive: Boolean,
        val provisioningProgress: Int,
        val provisioningFailed: Boolean,
        val japaneseAsrReady: Boolean,
        val totalDictWords: Int,
        val imeEnabled: Boolean,
        val imeSelected: Boolean,
        val micGranted: Boolean,
        val driveStatus: DriveRestoreUiState
    )

    data class StepGuidanceDecision(
        val stepTitle: String,
        val stepSubtitle: String,
        val nextButtonLabel: String,
        val nextButtonEnabled: Boolean,
        val showSkipButton: Boolean,
        val skipButtonLabel: String,
        val statusHintText: String
    )

    val stepGuidanceDecisionTable: DecisionTable<StepGuidanceInput, StepGuidanceDecision> =
        decisionTable(
            name = "OnboardingStepGuidanceDecisionTable",
            hitPolicy = HitPolicy.FIRST
        ) {
            rule(id = "STEP_WELCOME", description = "Step 0: Welcome & mode selection") {
                `when` { it.step == OnboardingStep.WELCOME }
                thenValue(
                    StepGuidanceDecision(
                        stepTitle = "JotInput へようこそ",
                        stepSubtitle = "完全オフラインの高速音声入力と、賢い日本語・英語キーボードをセットアップします。",
                        nextButtonLabel = "はじめる",
                        nextButtonEnabled = true,
                        showSkipButton = false,
                        skipButtonLabel = "",
                        statusHintText = "セットアップ方法を選択してください"
                    )
                )
            }
            rule(id = "STEP_DATA_READY", description = "Step 1: ASR and dictionaries already ready") {
                `when` {
                    it.step == OnboardingStep.DATA_PREP &&
                        it.japaneseAsrReady &&
                        it.totalDictWords > 0
                }
                thenValue(
                    StepGuidanceDecision(
                        stepTitle = "音声モデル・辞書の準備",
                        stepSubtitle = "必要なデータ（日本語音声認識・推奨拡張辞書）はすでに端末に揃っています。",
                        nextButtonLabel = "次へ進む",
                        nextButtonEnabled = true,
                        showSkipButton = false,
                        skipButtonLabel = "",
                        statusHintText = "データ準備完了 ✓"
                    )
                )
            }
            rule(id = "STEP_DATA_RUNNING", description = "Step 1: Background download running (Non-blocking)") {
                `when` { it.step == OnboardingStep.DATA_PREP && it.provisioningActive }
                then { input ->
                    StepGuidanceDecision(
                        stepTitle = "音声モデル・辞書の準備",
                        stepSubtitle = "バックグラウンドでダウンロード中です。完了を待たずに次の設定へ進めます。",
                        nextButtonLabel = "ダウンロードを続けながら次へ",
                        nextButtonEnabled = true,
                        showSkipButton = false,
                        skipButtonLabel = "",
                        statusHintText = "バックグラウンドで取得中 (${input.provisioningProgress.coerceIn(0, 100)}%)"
                    )
                }
            }
            rule(id = "STEP_DATA_FAILED", description = "Step 1: Download failed (Non-blocking fallback)") {
                `when` { it.step == OnboardingStep.DATA_PREP && it.provisioningFailed }
                thenValue(
                    StepGuidanceDecision(
                        stepTitle = "音声モデル・辞書の準備",
                        stepSubtitle = "通信エラー等で取得が中断されました。再試行するか、そのまま先へ進んで後から「データ」タブで取得できます。",
                        nextButtonLabel = "このまま次へ進む",
                        nextButtonEnabled = true,
                        showSkipButton = false,
                        skipButtonLabel = "",
                        statusHintText = "取得中断（後で再試行可能）"
                    )
                )
            }
            rule(id = "STEP_DATA_IDLE", description = "Step 1: Data not started yet") {
                `when` { it.step == OnboardingStep.DATA_PREP }
                thenValue(
                    StepGuidanceDecision(
                        stepTitle = "音声モデル・辞書の準備",
                        stepSubtitle = "オフライン音声認識 (~160MB) と拡張辞書を取得すると精度が最大化します。バックグラウンドで継続するため待機不要です。",
                        nextButtonLabel = "あとで取得する（スキップ）",
                        nextButtonEnabled = true,
                        showSkipButton = false,
                        skipButtonLabel = "",
                        statusHintText = "未取得（スキップ可能）"
                    )
                )
            }
            rule(id = "STEP_DRIVE_DONE", description = "Step 2: Drive restore completed") {
                `when` {
                    it.step == OnboardingStep.DRIVE_RESTORE &&
                        it.driveStatus == DriveRestoreUiState.RESTORED_OK
                }
                thenValue(
                    StepGuidanceDecision(
                        stepTitle = "Google Drive から履歴を復元",
                        stepSubtitle = "登録単語と変換学習履歴が安全に復元されました。",
                        nextButtonLabel = "次へ進む",
                        nextButtonEnabled = true,
                        showSkipButton = false,
                        skipButtonLabel = "",
                        statusHintText = "復元完了 ✓"
                    )
                )
            }
            rule(id = "STEP_DRIVE_OTHER", description = "Step 2: Drive restore optional or in progress") {
                `when` { it.step == OnboardingStep.DRIVE_RESTORE }
                then { input ->
                    val skipLabel = if (input.mode == OnboardingMode.DRIVE_RESTORE) {
                        "復元せずに次へ"
                    } else {
                        "あとで設定する"
                    }
                    StepGuidanceDecision(
                        stepTitle = "Google Drive から履歴を復元",
                        stepSubtitle = "以前の端末や再インストール前に保存した単語・学習履歴を引き継げます。",
                        nextButtonLabel = skipLabel,
                        nextButtonEnabled = input.driveStatus != DriveRestoreUiState.SYNCING,
                        showSkipButton = false,
                        skipButtonLabel = "",
                        statusHintText = if (input.driveStatus == DriveRestoreUiState.PERMISSION_LOST) {
                            "再インストールによりアクセス権限の再選択が必要です"
                        } else {
                            "任意設定（スキップ可能）"
                        }
                    )
                }
            }
            rule(id = "STEP_PERM_IME_READY", description = "Step 3: IME enabled & selected + mic granted") {
                `when` {
                    it.step == OnboardingStep.PERMISSIONS_AND_IME &&
                        it.imeEnabled &&
                        it.imeSelected &&
                        it.micGranted
                }
                thenValue(
                    StepGuidanceDecision(
                        stepTitle = "権限とキーボードの有効化",
                        stepSubtitle = "マイク権限・キーボード有効化・入力方法の切替がすべて完了しました。",
                        nextButtonLabel = "テスト入力へ進む",
                        nextButtonEnabled = true,
                        showSkipButton = false,
                        skipButtonLabel = "",
                        statusHintText = "すべての準備が整いました ✓"
                    )
                )
            }
            rule(id = "STEP_PERM_IME_PARTIAL", description = "Step 3: Still configuring permissions or IME") {
                `when` { it.step == OnboardingStep.PERMISSIONS_AND_IME }
                thenValue(
                    StepGuidanceDecision(
                        stepTitle = "権限とキーボードの有効化",
                        stepSubtitle = "JotInput を使うための権限許可と、Android キーボード設定を行います。",
                        nextButtonLabel = "あとで設定してテストへ",
                        nextButtonEnabled = true,
                        showSkipButton = false,
                        skipButtonLabel = "",
                        statusHintText = "各項目をタップして設定してください"
                    )
                )
            }
            defaultValue(
                id = "STEP_COMPLETE",
                description = "Step 4: Final test & finish",
                value = StepGuidanceDecision(
                    stepTitle = "セットアップ完了！",
                    stepSubtitle = "下のテスト欄でフリック・QWERTY・音声入力を試せます。",
                    nextButtonLabel = "JotInput を始める",
                    nextButtonEnabled = true,
                    showSkipButton = false,
                    skipButtonLabel = "",
                    statusHintText = "準備完了"
                )
            )
        }

    fun evaluateStepGuidance(input: StepGuidanceInput): StepGuidanceDecision =
        stepGuidanceDecisionTable.evaluate(input)

    /**
     * Returns the ordered step sequence tailored for the selected OnboardingMode.
     * In DRIVE_RESTORE mode, Drive restore comes immediately after Welcome so the user
     * can confirm their personal dictionary/history recovery first.
     */
    fun stepsForMode(mode: OnboardingMode): List<OnboardingStep> = when (mode) {
        OnboardingMode.DRIVE_RESTORE -> listOf(
            OnboardingStep.WELCOME,
            OnboardingStep.DRIVE_RESTORE,
            OnboardingStep.DATA_PREP,
            OnboardingStep.PERMISSIONS_AND_IME,
            OnboardingStep.COMPLETE_AND_TEST
        )
        OnboardingMode.FRESH_INSTALL,
        OnboardingMode.REINSTALL -> listOf(
            OnboardingStep.WELCOME,
            OnboardingStep.DATA_PREP,
            OnboardingStep.DRIVE_RESTORE,
            OnboardingStep.PERMISSIONS_AND_IME,
            OnboardingStep.COMPLETE_AND_TEST
        )
    }
}
