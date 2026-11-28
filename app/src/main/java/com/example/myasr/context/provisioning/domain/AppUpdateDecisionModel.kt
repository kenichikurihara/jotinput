package com.example.myasr.context.provisioning.domain

import com.example.myasr.shared.domain.dmn.DecisionTable
import com.example.myasr.shared.domain.dmn.DecisionTrace
import com.example.myasr.shared.domain.dmn.HitPolicy
import com.example.myasr.shared.domain.dmn.decisionTable

/**
 * BC-6 (Asset & Model Provisioning) pure-domain DMN DecisionModel for:
 * 1. Comparing the running JotInput build against either the latest public GitHub Release
 *    (`RELEASE`) or the rolling `main-head` prerelease (`REMOTE_HEAD`) via
 *    `AppBuildFreshnessDecisionTable`.
 * 2. Determining tokenless Home tab UI state, channel selector lock state, and primary CTA
 *    (`AppUpdateUiDecisionTable`).
 * 3. Pre-validating APK package ID, version code, and signing certificates before launching
 *    the system package installer (`ApkInstallCompatibilityDecisionTable`).
 */
object AppUpdateDecisionModel {

    data class SemVer(
        val coreParts: List<Int>,
        val preReleaseParts: List<String>
    )

    fun parseSemVer(raw: String): SemVer? {
        val cleaned = raw.trim().removePrefix("v").removePrefix("V")
        if (cleaned.isEmpty()) return null
        val withoutBuild = cleaned.substringBefore('+')
        val hasDash = '-' in withoutBuild
        val coreStr = withoutBuild.substringBefore('-')
        val preStr = if (hasDash) withoutBuild.substringAfter('-') else null
        if (hasDash && preStr.isNullOrEmpty()) return null
        if (coreStr.isEmpty()) return null

        val coreParts = coreStr.split('.').map { part ->
            if (part.isEmpty()) return null
            val num = part.toIntOrNull() ?: return null
            if (num < 0) return null
            num
        }
        val preParts = if (preStr != null) {
            preStr.split('.').also { parts ->
                if (parts.any { it.isEmpty() }) return null
            }
        } else {
            emptyList()
        }
        return SemVer(coreParts = coreParts, preReleaseParts = preParts)
    }

    fun compareSemVer(a: String, b: String): Int? {
        val va = parseSemVer(a) ?: return null
        val vb = parseSemVer(b) ?: return null
        val maxCore = maxOf(va.coreParts.size, vb.coreParts.size)
        for (i in 0 until maxCore) {
            val pa = va.coreParts.getOrElse(i) { 0 }
            val pb = vb.coreParts.getOrElse(i) { 0 }
            if (pa != pb) return pa.compareTo(pb)
        }
        val aHasPre = va.preReleaseParts.isNotEmpty()
        val bHasPre = vb.preReleaseParts.isNotEmpty()
        if (!aHasPre && !bHasPre) return 0
        if (!aHasPre) return 1
        if (!bHasPre) return -1

        val minPre = minOf(va.preReleaseParts.size, vb.preReleaseParts.size)
        for (i in 0 until minPre) {
            val ia = va.preReleaseParts[i]
            val ib = vb.preReleaseParts[i]
            if (ia == ib) continue
            val na = ia.toLongOrNull()
            val nb = ib.toLongOrNull()
            return when {
                na != null && nb != null -> na.compareTo(nb)
                na != null -> -1 // numeric identifiers always have lower precedence than non-numeric
                nb != null -> 1
                else -> ia.compareTo(ib)
            }
        }
        return va.preReleaseParts.size.compareTo(vb.preReleaseParts.size)
    }

    // ------------------------------------------------------------------------

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
                description = "No non-expired remote APK release or build available"
            ) {
                `when` {
                    val remote = it.remoteArtifact
                    remote == null || remote.expired || remote.downloadUrl.isBlank()
                }
                thenValue(
                    BuildFreshnessDecision(
                        status = BuildFreshnessStatus.NO_ARTIFACT,
                        reason = "no_valid_remote_artifact"
                    )
                )
            }
            rule(
                id = "BUILD_FRESH_R2",
                description = "Stable Release channel lacks version metadata"
            ) {
                `when` {
                    val remote = it.remoteArtifact ?: return@`when` false
                    remote.channel == AppUpdateChannel.RELEASE && remote.versionName.isBlank()
                }
                thenValue(
                    BuildFreshnessDecision(
                        status = BuildFreshnessStatus.NO_ARTIFACT,
                        reason = "missing_release_version"
                    )
                )
            }
            rule(
                id = "BUILD_FRESH_R3",
                description = "Stable Release SemVer is strictly newer than installed versionName"
            ) {
                `when` {
                    val remote = it.remoteArtifact ?: return@`when` false
                    remote.channel == AppUpdateChannel.RELEASE &&
                        (compareSemVer(remote.versionName, it.installed.versionName) ?: 0) > 0
                }
                thenValue(
                    BuildFreshnessDecision(
                        status = BuildFreshnessStatus.UPDATE_AVAILABLE,
                        reason = "newer_release_version"
                    )
                )
            }
            rule(
                id = "BUILD_FRESH_R4",
                description = "Stable Release versionCode is strictly newer than installed versionCode"
            ) {
                `when` {
                    val remote = it.remoteArtifact ?: return@`when` false
                    remote.channel == AppUpdateChannel.RELEASE &&
                        remote.versionCode > 0 &&
                        it.installed.versionCode > 0 &&
                        remote.versionCode > it.installed.versionCode
                }
                thenValue(
                    BuildFreshnessDecision(
                        status = BuildFreshnessStatus.UPDATE_AVAILABLE,
                        reason = "newer_release_version_code"
                    )
                )
            }
            rule(
                id = "BUILD_FRESH_R5",
                description = "Installed build is already at or newer than the latest Stable Release"
            ) {
                `when` {
                    val remote = it.remoteArtifact ?: return@`when` false
                    remote.channel == AppUpdateChannel.RELEASE
                }
                thenValue(
                    BuildFreshnessDecision(
                        status = BuildFreshnessStatus.UP_TO_DATE,
                        reason = "same_or_newer_release_version"
                    )
                )
            }
            rule(
                id = "BUILD_FRESH_R6",
                description = "Installed Git commit SHA matches the latest Remote HEAD build SHA"
            ) {
                `when` {
                    val remote = it.remoteArtifact ?: return@`when` false
                    val localSha = it.installed.gitSha.trim()
                    remote.channel == AppUpdateChannel.REMOTE_HEAD &&
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
                id = "BUILD_FRESH_R7",
                description = "Remote HEAD CI run ID is strictly newer than the running CI build"
            ) {
                `when` {
                    val remote = it.remoteArtifact ?: return@`when` false
                    remote.channel == AppUpdateChannel.REMOTE_HEAD &&
                        it.installed.githubRunId > 0L &&
                        remote.runId > 0L &&
                        remote.runId > it.installed.githubRunId
                }
                thenValue(
                    BuildFreshnessDecision(
                        status = BuildFreshnessStatus.UPDATE_AVAILABLE,
                        reason = "newer_ci_run_id"
                    )
                )
            }
            rule(
                id = "BUILD_FRESH_R8",
                description = "Running CI build run ID is already at or newer than Remote HEAD run ID"
            ) {
                `when` {
                    val remote = it.remoteArtifact ?: return@`when` false
                    remote.channel == AppUpdateChannel.REMOTE_HEAD &&
                        it.installed.githubRunId > 0L &&
                        remote.runId > 0L &&
                        remote.runId <= it.installed.githubRunId
                }
                thenValue(
                    BuildFreshnessDecision(
                        status = BuildFreshnessStatus.UP_TO_DATE,
                        reason = "same_or_newer_ci_run_id"
                    )
                )
            }
            rule(
                id = "BUILD_FRESH_R9",
                description = "Remote HEAD build commit timestamp is strictly newer than local build commit timestamp"
            ) {
                `when` {
                    val remote = it.remoteArtifact ?: return@`when` false
                    remote.channel == AppUpdateChannel.REMOTE_HEAD &&
                        it.installed.commitEpochMs > 0L &&
                        remote.effectiveTimestampMs > 0L &&
                        remote.effectiveTimestampMs > it.installed.commitEpochMs
                }
                thenValue(
                    BuildFreshnessDecision(
                        status = BuildFreshnessStatus.UPDATE_AVAILABLE,
                        reason = "newer_remote_commit_timestamp"
                    )
                )
            }
            rule(
                id = "BUILD_FRESH_R10",
                description = "Local build commit timestamp is at or newer than Remote HEAD commit timestamp"
            ) {
                `when` {
                    val remote = it.remoteArtifact ?: return@`when` false
                    remote.channel == AppUpdateChannel.REMOTE_HEAD &&
                        it.installed.commitEpochMs > 0L &&
                        remote.effectiveTimestampMs > 0L &&
                        remote.effectiveTimestampMs <= it.installed.commitEpochMs
                }
                thenValue(
                    BuildFreshnessDecision(
                        status = BuildFreshnessStatus.UP_TO_DATE,
                        reason = "local_commit_newer_or_equal"
                    )
                )
            }
            rule(
                id = "BUILD_FRESH_R11",
                description = "Remote HEAD metadata lacks valid commit/creation timestamp"
            ) {
                `when` {
                    val remote = it.remoteArtifact ?: return@`when` false
                    remote.channel == AppUpdateChannel.REMOTE_HEAD && remote.effectiveTimestampMs <= 0L
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
                description = "Fallback when local build metadata is unavailable but a valid Remote HEAD build exists",
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
        val selectedChannel: AppUpdateChannel,
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
        val primaryButtonEnabled: Boolean,
        val channelSelectorEnabled: Boolean,
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
                description = "Show active background download progress, lock channel switch, and allow per-task cancel"
            ) {
                `when` { it.isDownloadActive }
                thenValue(
                    AppUpdateUiDecision(
                        cardState = AppUpdateCardState.DOWNLOADING,
                        primaryAction = AppUpdatePrimaryAction.CANCEL_DOWNLOAD,
                        badgeTone = AppUpdateBadgeTone.INFO,
                        primaryButtonEnabled = true,
                        channelSelectorEnabled = false,
                        showProgressBar = true,
                        showSecondaryCheckButton = false,
                        badgeText = "DL中...",
                        primaryButtonLabel = "ダウンロードを中止"
                    )
                )
            }
            rule(
                id = "APP_UPD_R2",
                description = "Show queued download state and lock channel switch when another provisioning task is ahead"
            ) {
                `when` { it.isDownloadQueued }
                thenValue(
                    AppUpdateUiDecision(
                        cardState = AppUpdateCardState.QUEUED,
                        primaryAction = AppUpdatePrimaryAction.CANCEL_DOWNLOAD,
                        badgeTone = AppUpdateBadgeTone.INFO,
                        primaryButtonEnabled = true,
                        channelSelectorEnabled = false,
                        showProgressBar = true,
                        showSecondaryCheckButton = false,
                        badgeText = "待機中",
                        primaryButtonLabel = "待機をキャンセル"
                    )
                )
            }
            rule(
                id = "APP_UPD_R3",
                description = "Disable primary button and channel selector while update check is in flight"
            ) {
                `when` { it.isChecking }
                thenValue(
                    AppUpdateUiDecision(
                        cardState = AppUpdateCardState.CHECKING,
                        primaryAction = AppUpdatePrimaryAction.CHECK_UPDATE,
                        badgeTone = AppUpdateBadgeTone.INFO,
                        primaryButtonEnabled = false,
                        channelSelectorEnabled = false,
                        showProgressBar = false,
                        showSecondaryCheckButton = false,
                        badgeText = "確認中...",
                        primaryButtonLabel = "確認中..."
                    )
                )
            }
            rule(
                id = "APP_UPD_R4",
                description = "Offer one-tap install when a verified APK matching the selected channel is ready"
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
                        primaryButtonEnabled = true,
                        channelSelectorEnabled = true,
                        showProgressBar = false,
                        showSecondaryCheckButton = true,
                        badgeText = "DL済",
                        primaryButtonLabel = "インストール"
                    )
                )
            }
            rule(
                id = "APP_UPD_R5",
                description = "Fail-safe when GitHub unauthenticated API rate limit is exceeded"
            ) {
                `when` { it.lastErrorKind == AppUpdateCheckErrorKind.RATE_LIMITED }
                thenValue(
                    AppUpdateUiDecision(
                        cardState = AppUpdateCardState.ERROR,
                        primaryAction = AppUpdatePrimaryAction.CHECK_UPDATE,
                        badgeTone = AppUpdateBadgeTone.WARNING,
                        primaryButtonEnabled = true,
                        channelSelectorEnabled = true,
                        showProgressBar = false,
                        showSecondaryCheckButton = false,
                        badgeText = "制限中",
                        primaryButtonLabel = "更新を再確認"
                    )
                )
            }
            rule(
                id = "APP_UPD_R6",
                description = "Fail-safe when downloaded APK fails SHA-256 or archive header integrity verification"
            ) {
                `when` { it.lastErrorKind == AppUpdateCheckErrorKind.INTEGRITY_CHECK_FAILED }
                thenValue(
                    AppUpdateUiDecision(
                        cardState = AppUpdateCardState.ERROR,
                        primaryAction = AppUpdatePrimaryAction.CHECK_UPDATE,
                        badgeTone = AppUpdateBadgeTone.WARNING,
                        primaryButtonEnabled = true,
                        channelSelectorEnabled = true,
                        showProgressBar = false,
                        showSecondaryCheckButton = false,
                        badgeText = "検証失敗",
                        primaryButtonLabel = "更新を再確認"
                    )
                )
            }
            rule(
                id = "APP_UPD_R7",
                description = "Fail-safe on network or download failure and allow retry"
            ) {
                `when` {
                    it.lastErrorKind == AppUpdateCheckErrorKind.NETWORK_ERROR ||
                        it.lastErrorKind == AppUpdateCheckErrorKind.DOWNLOAD_FAILED
                }
                thenValue(
                    AppUpdateUiDecision(
                        cardState = AppUpdateCardState.ERROR,
                        primaryAction = AppUpdatePrimaryAction.CHECK_UPDATE,
                        badgeTone = AppUpdateBadgeTone.WARNING,
                        primaryButtonEnabled = true,
                        channelSelectorEnabled = true,
                        showProgressBar = false,
                        showSecondaryCheckButton = false,
                        badgeText = "接続不可",
                        primaryButtonLabel = "更新を再確認"
                    )
                )
            }
            rule(
                id = "APP_UPD_R8",
                description = "Newer APK available on the selected channel for background download"
            ) {
                `when` { it.freshnessStatus == BuildFreshnessStatus.UPDATE_AVAILABLE }
                thenValue(
                    AppUpdateUiDecision(
                        cardState = AppUpdateCardState.UPDATE_AVAILABLE,
                        primaryAction = AppUpdatePrimaryAction.DOWNLOAD_UPDATE,
                        badgeTone = AppUpdateBadgeTone.WARNING,
                        primaryButtonEnabled = true,
                        channelSelectorEnabled = true,
                        showProgressBar = false,
                        showSecondaryCheckButton = true,
                        badgeText = "更新あり",
                        primaryButtonLabel = "バックグラウンドでダウンロード"
                    )
                )
            }
            rule(
                id = "APP_UPD_R9",
                description = "Running build is already up to date on the selected channel"
            ) {
                `when` { it.freshnessStatus == BuildFreshnessStatus.UP_TO_DATE }
                thenValue(
                    AppUpdateUiDecision(
                        cardState = AppUpdateCardState.UP_TO_DATE,
                        primaryAction = AppUpdatePrimaryAction.CHECK_UPDATE,
                        badgeTone = AppUpdateBadgeTone.SUCCESS,
                        primaryButtonEnabled = true,
                        channelSelectorEnabled = true,
                        showProgressBar = false,
                        showSecondaryCheckButton = false,
                        badgeText = "最新",
                        primaryButtonLabel = "更新を確認"
                    )
                )
            }
            rule(
                id = "APP_UPD_R10",
                description = "No valid APK currently published on the selected channel"
            ) {
                `when` { it.freshnessStatus == BuildFreshnessStatus.NO_ARTIFACT }
                thenValue(
                    AppUpdateUiDecision(
                        cardState = AppUpdateCardState.NO_ARTIFACT,
                        primaryAction = AppUpdatePrimaryAction.CHECK_UPDATE,
                        badgeTone = AppUpdateBadgeTone.NEUTRAL,
                        primaryButtonEnabled = true,
                        channelSelectorEnabled = true,
                        showProgressBar = false,
                        showSecondaryCheckButton = false,
                        badgeText = "ビルドなし",
                        primaryButtonLabel = "更新を確認"
                    )
                )
            }
            defaultValue(
                id = "APP_UPD_DEFAULT",
                description = "Awaiting user-triggered update check on the selected channel (no token required)",
                value = AppUpdateUiDecision(
                    cardState = AppUpdateCardState.IDLE,
                    primaryAction = AppUpdatePrimaryAction.CHECK_UPDATE,
                    badgeTone = AppUpdateBadgeTone.NEUTRAL,
                    primaryButtonEnabled = true,
                    channelSelectorEnabled = true,
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

    // ------------------------------------------------------------------------

    data class ApkInstallCompatibilityInput(
        val apkReadable: Boolean,
        val installedPackageName: String,
        val archivePackageName: String?,
        val installedVersionCode: Long,
        val archiveVersionCode: Long?,
        val installedSigningCertSha256s: Set<String>,
        val archiveSigningCertSha256s: Set<String>
    )

    data class ApkInstallCompatibilityDecision(
        val status: ApkInstallCompatibilityStatus,
        val canProceedToInstaller: Boolean,
        val reason: String
    )

    val apkInstallCompatibilityDecisionTable: DecisionTable<ApkInstallCompatibilityInput, ApkInstallCompatibilityDecision> =
        decisionTable(
            name = "ApkInstallCompatibilityDecisionTable",
            hitPolicy = HitPolicy.FIRST
        ) {
            rule(
                id = "APK_COMPAT_R1",
                description = "Archive file cannot be parsed or lacks a package name"
            ) {
                `when` { !it.apkReadable || it.archivePackageName.isNullOrBlank() }
                thenValue(
                    ApkInstallCompatibilityDecision(
                        status = ApkInstallCompatibilityStatus.UNREADABLE_ARCHIVE,
                        canProceedToInstaller = false,
                        reason = "unreadable_apk_archive"
                    )
                )
            }
            rule(
                id = "APK_COMPAT_R2",
                description = "Downloaded APK applicationId differs from the installed package"
            ) {
                `when` { it.archivePackageName != it.installedPackageName }
                thenValue(
                    ApkInstallCompatibilityDecision(
                        status = ApkInstallCompatibilityStatus.PACKAGE_NAME_MISMATCH,
                        canProceedToInstaller = false,
                        reason = "package_name_mismatch"
                    )
                )
            }
            rule(
                id = "APK_COMPAT_R3",
                description = "Downloaded APK versionCode is strictly lower than the installed versionCode"
            ) {
                `when` {
                    it.archiveVersionCode != null &&
                        it.archiveVersionCode > 0L &&
                        it.installedVersionCode > 0L &&
                        it.archiveVersionCode < it.installedVersionCode
                }
                thenValue(
                    ApkInstallCompatibilityDecision(
                        status = ApkInstallCompatibilityStatus.VERSION_DOWNGRADE,
                        canProceedToInstaller = false,
                        reason = "version_code_downgrade"
                    )
                )
            }
            rule(
                id = "APK_COMPAT_R4",
                description = "Archive lacks readable signing certificates while installed package is signed"
            ) {
                `when` {
                    it.installedSigningCertSha256s.isNotEmpty() && it.archiveSigningCertSha256s.isEmpty()
                }
                thenValue(
                    ApkInstallCompatibilityDecision(
                        status = ApkInstallCompatibilityStatus.UNREADABLE_ARCHIVE,
                        canProceedToInstaller = false,
                        reason = "archive_missing_signing_certificates"
                    )
                )
            }
            rule(
                id = "APK_COMPAT_R5",
                description = "Signing certificate SHA-256 sets do not share any matching certificate"
            ) {
                `when` {
                    it.installedSigningCertSha256s.isNotEmpty() &&
                        it.archiveSigningCertSha256s.isNotEmpty() &&
                        it.installedSigningCertSha256s.intersect(it.archiveSigningCertSha256s).isEmpty()
                }
                thenValue(
                    ApkInstallCompatibilityDecision(
                        status = ApkInstallCompatibilityStatus.SIGNATURE_MISMATCH,
                        canProceedToInstaller = false,
                        reason = "signing_certificate_mismatch"
                    )
                )
            }
            rule(
                id = "APK_COMPAT_R6",
                description = "Installed package signatures unavailable; defer final verification to system installer"
            ) {
                `when` { it.installedSigningCertSha256s.isEmpty() }
                thenValue(
                    ApkInstallCompatibilityDecision(
                        status = ApkInstallCompatibilityStatus.SIGNATURE_UNVERIFIABLE,
                        canProceedToInstaller = true,
                        reason = "installed_signature_unavailable"
                    )
                )
            }
            defaultValue(
                id = "APK_COMPAT_DEFAULT",
                description = "Package ID, version code, and signing certificate match for in-place update",
                value = ApkInstallCompatibilityDecision(
                    status = ApkInstallCompatibilityStatus.COMPATIBLE,
                    canProceedToInstaller = true,
                    reason = "compatible_apk"
                )
            )
        }

    fun evaluateApkInstallCompatibility(input: ApkInstallCompatibilityInput): ApkInstallCompatibilityDecision =
        apkInstallCompatibilityDecisionTable.evaluate(input)

    fun evaluateApkInstallCompatibilityWithTrace(
        input: ApkInstallCompatibilityInput
    ): DecisionTrace<ApkInstallCompatibilityDecision> =
        apkInstallCompatibilityDecisionTable.evaluateWithTrace(input)
}
