package com.example.myasr.context.provisioning.domain

import com.example.myasr.shared.domain.dmn.DecisionTable
import com.example.myasr.shared.domain.dmn.DecisionTrace
import com.example.myasr.shared.domain.dmn.HitPolicy
import com.example.myasr.shared.domain.dmn.decisionTable

/**
 * BC-6 (Asset & Model Provisioning) pure-domain DMN DecisionModel for:
 * 1. Comparing the running JotInput build against the latest GitHub Actions artifact (`AppBuildFreshnessDecisionTable`).
 * 2. Determining fail-safe Home tab UI state & primary CTA (`AppUpdateUiDecisionTable`).
 */
object AppUpdateDecisionModel {

    data class BuildFreshnessInput(
        val installed: InstalledAppBuildInfo,
        val remoteArtifact: RemoteBuildArtifact?
    )

    data class BuildFreshnessDecision(
        val status: BuildFreshnessStatus,
        val reason: String
    )

    val appBuildFreshnessDecisionTable: DecisionTable<BuildFreshnessInput, BuildFreshnessDecision> =
        decisionTable(
            name = "AppBuildFreshnessDecisionTable",
            hitPolicy = HitPolicy.FIRST
        ) {
            rule(
                id = "BUILD_FRESH_R1",
                description = "No non-expired remote artifact available"
            ) {
                `when` { it.remoteArtifact == null || it.remoteArtifact.expired }
                thenValue(
                    BuildFreshnessDecision(
                        status = BuildFreshnessStatus.NO_ARTIFACT,
                        reason = "no_valid_remote_artifact"
                    )
                )
            }
            rule(
                id = "BUILD_FRESH_R2",
                description = "CI run ID is strictly newer than the running CI build"
            ) {
                `when` {
                    val remote = it.remoteArtifact ?: return@`when` false
                    it.installed.githubRunId > 0L && remote.runId > 0L && remote.runId > it.installed.githubRunId
                }
                thenValue(
                    BuildFreshnessDecision(
                        status = BuildFreshnessStatus.UPDATE_AVAILABLE,
                        reason = "newer_ci_run_id"
                    )
                )
            }
            rule(
                id = "BUILD_FRESH_R3",
                description = "Running CI build run ID is already at or newer than remote artifact run ID"
            ) {
                `when` {
                    val remote = it.remoteArtifact ?: return@`when` false
                    it.installed.githubRunId > 0L && remote.runId > 0L && remote.runId <= it.installed.githubRunId
                }
                thenValue(
                    BuildFreshnessDecision(
                        status = BuildFreshnessStatus.UP_TO_DATE,
                        reason = "same_or_newer_ci_run_id"
                    )
                )
            }
            rule(
                id = "BUILD_FRESH_R4",
                description = "Installed Git commit SHA matches the latest remote artifact head SHA"
            ) {
                `when` {
                    val remote = it.remoteArtifact ?: return@`when` false
                    val localSha = it.installed.gitSha.trim()
                    localSha.isNotEmpty() &&
                        !localSha.equals("unknown", ignoreCase = true) &&
                        remote.headSha.trim().equals(localSha, ignoreCase = true)
                }
                thenValue(
                    BuildFreshnessDecision(
                        status = BuildFreshnessStatus.UP_TO_DATE,
                        reason = "matching_git_sha"
                    )
                )
            }
            rule(
                id = "BUILD_FRESH_R5",
                description = "Remote artifact was created after the local build commit timestamp"
            ) {
                `when` {
                    val remote = it.remoteArtifact ?: return@`when` false
                    it.installed.commitEpochMs > 0L &&
                        remote.createdAtMs > 0L &&
                        remote.createdAtMs > it.installed.commitEpochMs
                }
                thenValue(
                    BuildFreshnessDecision(
                        status = BuildFreshnessStatus.UPDATE_AVAILABLE,
                        reason = "newer_remote_artifact_timestamp"
                    )
                )
            }
            rule(
                id = "BUILD_FRESH_R6",
                description = "Local build commit timestamp is at or newer than remote artifact creation timestamp"
            ) {
                `when` {
                    val remote = it.remoteArtifact ?: return@`when` false
                    it.installed.commitEpochMs > 0L &&
                        remote.createdAtMs > 0L &&
                        remote.createdAtMs <= it.installed.commitEpochMs
                }
                thenValue(
                    BuildFreshnessDecision(
                        status = BuildFreshnessStatus.UP_TO_DATE,
                        reason = "local_commit_newer_or_equal"
                    )
                )
            }
            rule(
                id = "BUILD_FRESH_R7",
                description = "Remote artifact lacks valid creation timestamp metadata"
            ) {
                `when` {
                    val remote = it.remoteArtifact ?: return@`when` false
                    remote.createdAtMs <= 0L
                }
                thenValue(
                    BuildFreshnessDecision(
                        status = BuildFreshnessStatus.NO_ARTIFACT,
                        reason = "missing_remote_timestamp"
                    )
                )
            }
            defaultValue(
                id = "BUILD_FRESH_DEFAULT",
                description = "Fallback when local build metadata is unavailable but a valid remote artifact exists",
                value = BuildFreshnessDecision(
                    status = BuildFreshnessStatus.UPDATE_AVAILABLE,
                    reason = "unknown_local_build_with_valid_remote"
                )
            )
        }

    fun evaluateBuildFreshness(input: BuildFreshnessInput): BuildFreshnessDecision =
        appBuildFreshnessDecisionTable.evaluate(input)

    fun evaluateBuildFreshnessWithTrace(input: BuildFreshnessInput): DecisionTrace<BuildFreshnessDecision> =
        appBuildFreshnessDecisionTable.evaluateWithTrace(input)

    // ------------------------------------------------------------------------

    data class AppUpdateUiInput(
        val isDebugBuild: Boolean,
        val hasGitHubToken: Boolean,
        val isChecking: Boolean,
        val isDownloadActive: Boolean,
        val isDownloadQueued: Boolean,
        val freshnessStatus: BuildFreshnessStatus?,
        val hasMatchingDownloadedApk: Boolean,
        val lastErrorKind: AppUpdateCheckErrorKind?
    )

    data class AppUpdateUiDecision(
        val cardState: AppUpdateCardState,
        val primaryAction: AppUpdatePrimaryAction,
        val badgeTone: AppUpdateBadgeTone,
        val isCardVisible: Boolean,
        val primaryButtonEnabled: Boolean,
        val showProgressBar: Boolean,
        val showSecondaryCheckButton: Boolean,
        val badgeText: String,
        val primaryButtonLabel: String
    )

    val appUpdateUiDecisionTable: DecisionTable<AppUpdateUiInput, AppUpdateUiDecision> =
        decisionTable(
            name = "AppUpdateUiDecisionTable",
            hitPolicy = HitPolicy.FIRST
        ) {
            rule(
                id = "APP_UPD_R1",
                description = "Hide developer GitHub update card completely in non-debug (release) builds"
            ) {
                `when` { !it.isDebugBuild }
                thenValue(
                    AppUpdateUiDecision(
                        cardState = AppUpdateCardState.HIDDEN,
                        primaryAction = AppUpdatePrimaryAction.NONE,
                        badgeTone = AppUpdateBadgeTone.NEUTRAL,
                        isCardVisible = false,
                        primaryButtonEnabled = false,
                        showProgressBar = false,
                        showSecondaryCheckButton = false,
                        badgeText = "",
                        primaryButtonLabel = ""
                    )
                )
            }
            rule(
                id = "APP_UPD_R2",
                description = "Show active background download progress and allow per-task cancel"
            ) {
                `when` { it.isDownloadActive }
                thenValue(
                    AppUpdateUiDecision(
                        cardState = AppUpdateCardState.DOWNLOADING,
                        primaryAction = AppUpdatePrimaryAction.CANCEL_DOWNLOAD,
                        badgeTone = AppUpdateBadgeTone.INFO,
                        isCardVisible = true,
                        primaryButtonEnabled = true,
                        showProgressBar = true,
                        showSecondaryCheckButton = false,
                        badgeText = "DL中...",
                        primaryButtonLabel = "ダウンロードを中止"
                    )
                )
            }
            rule(
                id = "APP_UPD_R3",
                description = "Show queued download state when another background provisioning task is ahead"
            ) {
                `when` { it.isDownloadQueued }
                thenValue(
                    AppUpdateUiDecision(
                        cardState = AppUpdateCardState.QUEUED,
                        primaryAction = AppUpdatePrimaryAction.CANCEL_DOWNLOAD,
                        badgeTone = AppUpdateBadgeTone.INFO,
                        isCardVisible = true,
                        primaryButtonEnabled = true,
                        showProgressBar = true,
                        showSecondaryCheckButton = false,
                        badgeText = "待機中",
                        primaryButtonLabel = "待機をキャンセル"
                    )
                )
            }
            rule(
                id = "APP_UPD_R4",
                description = "Disable primary button while GitHub API update check is in flight"
            ) {
                `when` { it.isChecking }
                thenValue(
                    AppUpdateUiDecision(
                        cardState = AppUpdateCardState.CHECKING,
                        primaryAction = AppUpdatePrimaryAction.CHECK_UPDATE,
                        badgeTone = AppUpdateBadgeTone.INFO,
                        isCardVisible = true,
                        primaryButtonEnabled = false,
                        showProgressBar = false,
                        showSecondaryCheckButton = false,
                        badgeText = "確認中...",
                        primaryButtonLabel = "確認中..."
                    )
                )
            }
            rule(
                id = "APP_UPD_R5",
                description = "Offer one-tap install when matching newer APK is downloaded and no error is pending"
            ) {
                `when` {
                    it.hasMatchingDownloadedApk &&
                        it.lastErrorKind == null &&
                        (it.freshnessStatus == null || it.freshnessStatus == BuildFreshnessStatus.UPDATE_AVAILABLE)
                }
                thenValue(
                    AppUpdateUiDecision(
                        cardState = AppUpdateCardState.READY_TO_INSTALL,
                        primaryAction = AppUpdatePrimaryAction.INSTALL_APK,
                        badgeTone = AppUpdateBadgeTone.SUCCESS,
                        isCardVisible = true,
                        primaryButtonEnabled = true,
                        showProgressBar = false,
                        showSecondaryCheckButton = true,
                        badgeText = "DL済",
                        primaryButtonLabel = "インストール"
                    )
                )
            }
            rule(
                id = "APP_UPD_R6",
                description = "Fail-safe when GitHub token is not configured"
            ) {
                `when` { !it.hasGitHubToken }
                thenValue(
                    AppUpdateUiDecision(
                        cardState = AppUpdateCardState.TOKEN_MISSING,
                        primaryAction = AppUpdatePrimaryAction.CONFIGURE_TOKEN,
                        badgeTone = AppUpdateBadgeTone.NEUTRAL,
                        isCardVisible = true,
                        primaryButtonEnabled = true,
                        showProgressBar = false,
                        showSecondaryCheckButton = false,
                        badgeText = "未連携",
                        primaryButtonLabel = "トークンを設定"
                    )
                )
            }
            rule(
                id = "APP_UPD_R7",
                description = "Fail-safe on GitHub authentication or permission error (401/403)"
            ) {
                `when` {
                    it.lastErrorKind == AppUpdateCheckErrorKind.AUTH_FAILED ||
                        it.lastErrorKind == AppUpdateCheckErrorKind.TOKEN_REQUIRED
                }
                thenValue(
                    AppUpdateUiDecision(
                        cardState = AppUpdateCardState.AUTH_ERROR,
                        primaryAction = AppUpdatePrimaryAction.CONFIGURE_TOKEN,
                        badgeTone = AppUpdateBadgeTone.WARNING,
                        isCardVisible = true,
                        primaryButtonEnabled = true,
                        showProgressBar = false,
                        showSecondaryCheckButton = true,
                        badgeText = "認証エラー",
                        primaryButtonLabel = "トークンを再設定"
                    )
                )
            }
            rule(
                id = "APP_UPD_R8",
                description = "Fail-safe on network or download failure and allow retry"
            ) {
                `when` {
                    it.lastErrorKind == AppUpdateCheckErrorKind.NETWORK_ERROR ||
                        it.lastErrorKind == AppUpdateCheckErrorKind.DOWNLOAD_FAILED
                }
                thenValue(
                    AppUpdateUiDecision(
                        cardState = AppUpdateCardState.NETWORK_ERROR,
                        primaryAction = AppUpdatePrimaryAction.CHECK_UPDATE,
                        badgeTone = AppUpdateBadgeTone.WARNING,
                        isCardVisible = true,
                        primaryButtonEnabled = true,
                        showProgressBar = false,
                        showSecondaryCheckButton = false,
                        badgeText = "接続不可",
                        primaryButtonLabel = "更新を再確認"
                    )
                )
            }
            rule(
                id = "APP_UPD_R9",
                description = "Newer CI artifact available for background download"
            ) {
                `when` { it.freshnessStatus == BuildFreshnessStatus.UPDATE_AVAILABLE }
                thenValue(
                    AppUpdateUiDecision(
                        cardState = AppUpdateCardState.UPDATE_AVAILABLE,
                        primaryAction = AppUpdatePrimaryAction.DOWNLOAD_UPDATE,
                        badgeTone = AppUpdateBadgeTone.WARNING,
                        isCardVisible = true,
                        primaryButtonEnabled = true,
                        showProgressBar = false,
                        showSecondaryCheckButton = true,
                        badgeText = "更新あり",
                        primaryButtonLabel = "バックグラウンドでダウンロード"
                    )
                )
            }
            rule(
                id = "APP_UPD_R10",
                description = "Running build is already up to date with latest CI artifact"
            ) {
                `when` { it.freshnessStatus == BuildFreshnessStatus.UP_TO_DATE }
                thenValue(
                    AppUpdateUiDecision(
                        cardState = AppUpdateCardState.UP_TO_DATE,
                        primaryAction = AppUpdatePrimaryAction.CHECK_UPDATE,
                        badgeTone = AppUpdateBadgeTone.SUCCESS,
                        isCardVisible = true,
                        primaryButtonEnabled = true,
                        showProgressBar = false,
                        showSecondaryCheckButton = false,
                        badgeText = "最新",
                        primaryButtonLabel = "更新を確認"
                    )
                )
            }
            rule(
                id = "APP_UPD_R11",
                description = "No valid non-expired artifact currently exists on GitHub"
            ) {
                `when` { it.freshnessStatus == BuildFreshnessStatus.NO_ARTIFACT }
                thenValue(
                    AppUpdateUiDecision(
                        cardState = AppUpdateCardState.NO_ARTIFACT,
                        primaryAction = AppUpdatePrimaryAction.CHECK_UPDATE,
                        badgeTone = AppUpdateBadgeTone.NEUTRAL,
                        isCardVisible = true,
                        primaryButtonEnabled = true,
                        showProgressBar = false,
                        showSecondaryCheckButton = false,
                        badgeText = "成果物なし",
                        primaryButtonLabel = "更新を確認"
                    )
                )
            }
            defaultValue(
                id = "APP_UPD_DEFAULT",
                description = "Token configured and awaiting user-triggered update check",
                value = AppUpdateUiDecision(
                    cardState = AppUpdateCardState.IDLE,
                    primaryAction = AppUpdatePrimaryAction.CHECK_UPDATE,
                    badgeTone = AppUpdateBadgeTone.NEUTRAL,
                    isCardVisible = true,
                    primaryButtonEnabled = true,
                    showProgressBar = false,
                    showSecondaryCheckButton = false,
                    badgeText = "未確認",
                    primaryButtonLabel = "更新を確認"
                )
            )
        }

    fun evaluateUi(input: AppUpdateUiInput): AppUpdateUiDecision =
        appUpdateUiDecisionTable.evaluate(input)

    fun evaluateUiWithTrace(input: AppUpdateUiInput): DecisionTrace<AppUpdateUiDecision> =
        appUpdateUiDecisionTable.evaluateWithTrace(input)
}
