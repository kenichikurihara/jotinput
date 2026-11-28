package com.example.myasr

import com.example.myasr.context.provisioning.domain.ApkInstallCompatibilityStatus
import com.example.myasr.context.provisioning.domain.AppUpdateBadgeTone
import com.example.myasr.context.provisioning.domain.AppUpdateCardState
import com.example.myasr.context.provisioning.domain.AppUpdateChannel
import com.example.myasr.context.provisioning.domain.AppUpdateCheckErrorKind
import com.example.myasr.context.provisioning.domain.AppUpdateDecisionModel
import com.example.myasr.context.provisioning.domain.AppUpdateDecisionModel.ApkInstallCompatibilityInput
import com.example.myasr.context.provisioning.domain.AppUpdateDecisionModel.AppUpdateUiInput
import com.example.myasr.context.provisioning.domain.AppUpdateDecisionModel.BuildFreshnessInput
import com.example.myasr.context.provisioning.domain.AppUpdatePrimaryAction
import com.example.myasr.context.provisioning.domain.BuildFreshnessStatus
import com.example.myasr.context.provisioning.domain.InstalledAppBuildInfo
import com.example.myasr.context.provisioning.domain.RemoteBuildArtifact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateDecisionModelTest {

    private fun releaseArtifact(
        tagName: String = "v0.1.0",
        versionName: String = tagName.removePrefix("v"),
        versionCode: Int = 0,
        createdAtMs: Long = 9_000_000L,
        downloadUrl: String = "https://github.com/kenichikurihara/jotinput/releases/download/$tagName/jotinput-$versionName-universal-release.apk",
        expired: Boolean = false
    ) = RemoteBuildArtifact(
        channel = AppUpdateChannel.RELEASE,
        artifactId = 795693355L,
        tagName = tagName,
        versionName = versionName,
        versionCode = versionCode,
        name = "jotinput-$versionName-universal-release.apk",
        sizeInBytes = 29_895_313L,
        downloadUrl = downloadUrl,
        sha256 = "ed0bcff591008863fa9ab3e91bcbdfcd890f04ce58da76aee1929f3f8f41bbba",
        createdAtMs = createdAtMs,
        commitEpochMs = 0L,
        runId = 0L,
        headSha = "",
        headBranch = "main",
        isDebugVariant = false,
        expired = expired
    )

    private fun headArtifact(
        artifactId: Long = 100L,
        createdAtMs: Long = 2_000_000L,
        commitEpochMs: Long = createdAtMs,
        expired: Boolean = false,
        runId: Long = 200L,
        headSha: String = "abcdef1234567890",
        downloadUrl: String = "https://github.com/kenichikurihara/jotinput/releases/download/main-head/jotinput-main-head-debug.apk"
    ) = RemoteBuildArtifact(
        channel = AppUpdateChannel.REMOTE_HEAD,
        artifactId = artifactId,
        tagName = "main-head",
        versionName = "0.1.0",
        versionCode = 1,
        name = "jotinput-main-head-debug.apk",
        sizeInBytes = 37_000_000L,
        downloadUrl = downloadUrl,
        sha256 = "42b6c883180deddc2cf5930977d86001804f021d4756cf3f7f27afeba7c8cd71",
        createdAtMs = createdAtMs,
        commitEpochMs = commitEpochMs,
        runId = runId,
        headSha = headSha,
        headBranch = "main",
        isDebugVariant = true,
        expired = expired
    )

    private fun installed(
        versionName: String = "0.1.0",
        versionCode: Int = 1,
        gitSha: String = "11111112222222",
        commitEpochMs: Long = 1_000_000L,
        githubRunId: Long = 0L,
        isDebugBuild: Boolean = true
    ) = InstalledAppBuildInfo(
        versionName = versionName,
        versionCode = versionCode,
        gitSha = gitSha,
        commitEpochMs = commitEpochMs,
        githubRunId = githubRunId,
        isDebugBuild = isDebugBuild
    )

    // ------------------------------------------------------------------------
    // SemVer parser & comparator tests
    // ------------------------------------------------------------------------

    @Test
    fun `compareSemVer orders versions accurately including multi-digit and prerelease tags`() {
        assertTrue(AppUpdateDecisionModel.compareSemVer("v0.2.0", "0.1.0")!! > 0)
        assertTrue(AppUpdateDecisionModel.compareSemVer("v0.10.0", "0.2.0")!! > 0)
        assertTrue(AppUpdateDecisionModel.compareSemVer("0.2.0", "0.2.0-rc.1")!! > 0)
        assertTrue(AppUpdateDecisionModel.compareSemVer("0.2.0-rc.2", "0.2.0-rc.1")!! > 0)
        assertEquals(0, AppUpdateDecisionModel.compareSemVer("v1.2", "1.2.0"))
        assertEquals(0, AppUpdateDecisionModel.compareSemVer("v0.1.0+build42", "0.1.0"))
        assertNull(AppUpdateDecisionModel.parseSemVer(""))
        assertNull(AppUpdateDecisionModel.parseSemVer("1.0.0-"))
        assertNull(AppUpdateDecisionModel.parseSemVer("abc"))
    }

    // ------------------------------------------------------------------------
    // AppBuildFreshnessDecisionTable tests
    // ------------------------------------------------------------------------

    @Test
    fun `freshness returns NO_ARTIFACT when remote is null, expired, or missing URL`() {
        val nullTrace = AppUpdateDecisionModel.evaluateBuildFreshnessWithTrace(
            BuildFreshnessInput(installed = installed(), remoteArtifact = null)
        )
        assertEquals("BUILD_FRESH_R1", nullTrace.selectedRuleId)
        assertEquals(BuildFreshnessStatus.NO_ARTIFACT, nullTrace.result.status)

        val expiredTrace = AppUpdateDecisionModel.evaluateBuildFreshnessWithTrace(
            BuildFreshnessInput(installed = installed(), remoteArtifact = headArtifact(expired = true))
        )
        assertEquals("BUILD_FRESH_R1", expiredTrace.selectedRuleId)
        assertEquals(BuildFreshnessStatus.NO_ARTIFACT, expiredTrace.result.status)

        val blankUrlTrace = AppUpdateDecisionModel.evaluateBuildFreshnessWithTrace(
            BuildFreshnessInput(installed = installed(), remoteArtifact = releaseArtifact(downloadUrl = ""))
        )
        assertEquals("BUILD_FRESH_R1", blankUrlTrace.selectedRuleId)
        assertEquals(BuildFreshnessStatus.NO_ARTIFACT, blankUrlTrace.result.status)
    }

    @Test
    fun `freshness evaluates RELEASE channel by SemVer and versionCode without timestamp loops`() {
        val missingVer = AppUpdateDecisionModel.evaluateBuildFreshnessWithTrace(
            BuildFreshnessInput(installed = installed(), remoteArtifact = releaseArtifact(tagName = "", versionName = ""))
        )
        assertEquals("BUILD_FRESH_R2", missingVer.selectedRuleId)
        assertEquals(BuildFreshnessStatus.NO_ARTIFACT, missingVer.result.status)

        val newerSemVer = AppUpdateDecisionModel.evaluateBuildFreshnessWithTrace(
            BuildFreshnessInput(
                installed = installed(versionName = "0.1.0", isDebugBuild = false),
                remoteArtifact = releaseArtifact(tagName = "v0.2.0")
            )
        )
        assertEquals("BUILD_FRESH_R3", newerSemVer.selectedRuleId)
        assertEquals(BuildFreshnessStatus.UPDATE_AVAILABLE, newerSemVer.result.status)

        val newerCode = AppUpdateDecisionModel.evaluateBuildFreshnessWithTrace(
            BuildFreshnessInput(
                installed = installed(versionName = "0.1.0", versionCode = 1, isDebugBuild = false),
                remoteArtifact = releaseArtifact(tagName = "v0.1.0", versionCode = 2)
            )
        )
        assertEquals("BUILD_FRESH_R4", newerCode.selectedRuleId)
        assertEquals(BuildFreshnessStatus.UPDATE_AVAILABLE, newerCode.result.status)

        // Same version should be UP_TO_DATE even when release publishedAtMs > local commitEpochMs
        val sameRelease = AppUpdateDecisionModel.evaluateBuildFreshnessWithTrace(
            BuildFreshnessInput(
                installed = installed(versionName = "0.1.0", versionCode = 1, commitEpochMs = 1_000L, isDebugBuild = false),
                remoteArtifact = releaseArtifact(tagName = "v0.1.0", createdAtMs = 999_999_999L)
            )
        )
        assertEquals("BUILD_FRESH_R5", sameRelease.selectedRuleId)
        assertEquals(BuildFreshnessStatus.UP_TO_DATE, sameRelease.result.status)

        val newerLocalThanRelease = AppUpdateDecisionModel.evaluateBuildFreshnessWithTrace(
            BuildFreshnessInput(
                installed = installed(versionName = "0.2.0", isDebugBuild = false),
                remoteArtifact = releaseArtifact(tagName = "v0.1.0")
            )
        )
        assertEquals("BUILD_FRESH_R5", newerLocalThanRelease.selectedRuleId)
        assertEquals(BuildFreshnessStatus.UP_TO_DATE, newerLocalThanRelease.result.status)
    }

    @Test
    fun `freshness treats matching git SHA on REMOTE_HEAD as UP_TO_DATE even across CI reruns`() {
        val trace = AppUpdateDecisionModel.evaluateBuildFreshnessWithTrace(
            BuildFreshnessInput(
                installed = installed(gitSha = "ABCDEF1234567890", commitEpochMs = 1_000_000L, githubRunId = 100L),
                remoteArtifact = headArtifact(runId = 999L, createdAtMs = 9_000_000L, headSha = "abcdef1234567890")
            )
        )
        assertEquals("BUILD_FRESH_R6", trace.selectedRuleId)
        assertEquals(BuildFreshnessStatus.UP_TO_DATE, trace.result.status)
    }

    @Test
    fun `freshness compares CI runId accurately on REMOTE_HEAD when SHAs differ`() {
        val newerTrace = AppUpdateDecisionModel.evaluateBuildFreshnessWithTrace(
            BuildFreshnessInput(
                installed = installed(gitSha = "1111111", githubRunId = 200L, commitEpochMs = 5_000_000L),
                remoteArtifact = headArtifact(runId = 201L, createdAtMs = 1_000_000L, headSha = "2222222")
            )
        )
        assertEquals("BUILD_FRESH_R7", newerTrace.selectedRuleId)
        assertEquals(BuildFreshnessStatus.UPDATE_AVAILABLE, newerTrace.result.status)

        val olderRemoteTrace = AppUpdateDecisionModel.evaluateBuildFreshnessWithTrace(
            BuildFreshnessInput(
                installed = installed(gitSha = "3333333", githubRunId = 205L),
                remoteArtifact = headArtifact(runId = 200L, headSha = "2222222")
            )
        )
        assertEquals("BUILD_FRESH_R8", olderRemoteTrace.selectedRuleId)
        assertEquals(BuildFreshnessStatus.UP_TO_DATE, olderRemoteTrace.result.status)

        // Realistic 11-digit GitHub Actions run IDs
        val ciScaleNewerTrace = AppUpdateDecisionModel.evaluateBuildFreshnessWithTrace(
            BuildFreshnessInput(
                installed = installed(gitSha = "1111111", githubRunId = 46_090_445_392L),
                remoteArtifact = headArtifact(runId = 46_090_445_392L + 1000L, headSha = "2222222")
            )
        )
        assertEquals("BUILD_FRESH_R7", ciScaleNewerTrace.selectedRuleId)
        assertEquals(BuildFreshnessStatus.UPDATE_AVAILABLE, ciScaleNewerTrace.result.status)
    }

    @Test
    fun `freshness compares commit timestamps on REMOTE_HEAD for local deploy builds`() {
        val updateTrace = AppUpdateDecisionModel.evaluateBuildFreshnessWithTrace(
            BuildFreshnessInput(
                installed = installed(gitSha = "1111111", commitEpochMs = 1_000_000L, githubRunId = 0L),
                remoteArtifact = headArtifact(commitEpochMs = 2_000_000L, headSha = "2222222")
            )
        )
        assertEquals("BUILD_FRESH_R9", updateTrace.selectedRuleId)
        assertEquals(BuildFreshnessStatus.UPDATE_AVAILABLE, updateTrace.result.status)

        val localNewerTrace = AppUpdateDecisionModel.evaluateBuildFreshnessWithTrace(
            BuildFreshnessInput(
                installed = installed(gitSha = "3333333", commitEpochMs = 3_000_000L, githubRunId = 0L),
                remoteArtifact = headArtifact(commitEpochMs = 2_000_000L, headSha = "2222222")
            )
        )
        assertEquals("BUILD_FRESH_R10", localNewerTrace.selectedRuleId)
        assertEquals(BuildFreshnessStatus.UP_TO_DATE, localNewerTrace.result.status)
    }

    @Test
    fun `freshness handles missing REMOTE_HEAD timestamps safely`() {
        val missingRemoteTs = AppUpdateDecisionModel.evaluateBuildFreshnessWithTrace(
            BuildFreshnessInput(
                installed = installed(gitSha = "unknown", commitEpochMs = 0L, githubRunId = 0L),
                remoteArtifact = headArtifact(createdAtMs = 0L, commitEpochMs = 0L, headSha = "2222222")
            )
        )
        assertEquals("BUILD_FRESH_R11", missingRemoteTs.selectedRuleId)
        assertEquals(BuildFreshnessStatus.NO_ARTIFACT, missingRemoteTs.result.status)

        val unknownLocalValidRemote = AppUpdateDecisionModel.evaluateBuildFreshnessWithTrace(
            BuildFreshnessInput(
                installed = installed(gitSha = "unknown", commitEpochMs = 0L, githubRunId = 0L),
                remoteArtifact = headArtifact(commitEpochMs = 2_000_000L, headSha = "2222222")
            )
        )
        assertEquals("BUILD_FRESH_DEFAULT", unknownLocalValidRemote.selectedRuleId)
        assertEquals(BuildFreshnessStatus.UPDATE_AVAILABLE, unknownLocalValidRemote.result.status)
    }

    // ------------------------------------------------------------------------
    // AppUpdateUiDecisionTable tests
    // ------------------------------------------------------------------------

    @Test
    fun `ui table locks channel selector while downloading, queued, or checking`() {
        val downloading = AppUpdateDecisionModel.evaluateUiWithTrace(
            AppUpdateUiInput(
                selectedChannel = AppUpdateChannel.RELEASE,
                isChecking = false,
                isDownloadActive = true,
                isDownloadQueued = false,
                freshnessStatus = BuildFreshnessStatus.UPDATE_AVAILABLE,
                hasMatchingDownloadedApk = false,
                lastErrorKind = null
            )
        )
        assertEquals("APP_UPD_R1", downloading.selectedRuleId)
        assertEquals(AppUpdateCardState.DOWNLOADING, downloading.result.cardState)
        assertEquals(AppUpdatePrimaryAction.CANCEL_DOWNLOAD, downloading.result.primaryAction)
        assertFalse(downloading.result.channelSelectorEnabled)
        assertTrue(downloading.result.showProgressBar)

        val queued = AppUpdateDecisionModel.evaluateUiWithTrace(
            AppUpdateUiInput(
                selectedChannel = AppUpdateChannel.REMOTE_HEAD,
                isChecking = false,
                isDownloadActive = false,
                isDownloadQueued = true,
                freshnessStatus = BuildFreshnessStatus.UPDATE_AVAILABLE,
                hasMatchingDownloadedApk = false,
                lastErrorKind = null
            )
        )
        assertEquals("APP_UPD_R2", queued.selectedRuleId)
        assertEquals(AppUpdateCardState.QUEUED, queued.result.cardState)
        assertEquals(AppUpdatePrimaryAction.CANCEL_DOWNLOAD, queued.result.primaryAction)
        assertFalse(queued.result.channelSelectorEnabled)
        assertTrue(queued.result.showProgressBar)

        val checking = AppUpdateDecisionModel.evaluateUiWithTrace(
            AppUpdateUiInput(
                selectedChannel = AppUpdateChannel.RELEASE,
                isChecking = true,
                isDownloadActive = false,
                isDownloadQueued = false,
                freshnessStatus = null,
                hasMatchingDownloadedApk = false,
                lastErrorKind = null
            )
        )
        assertEquals("APP_UPD_R3", checking.selectedRuleId)
        assertEquals(AppUpdateCardState.CHECKING, checking.result.cardState)
        assertFalse(checking.result.primaryButtonEnabled)
        assertFalse(checking.result.channelSelectorEnabled)
    }

    @Test
    fun `ui table offers one-tap install tokenlessly when channel-matching APK exists without errors`() {
        val trace = AppUpdateDecisionModel.evaluateUiWithTrace(
            AppUpdateUiInput(
                selectedChannel = AppUpdateChannel.RELEASE,
                isChecking = false,
                isDownloadActive = false,
                isDownloadQueued = false,
                freshnessStatus = BuildFreshnessStatus.UPDATE_AVAILABLE,
                hasMatchingDownloadedApk = true,
                lastErrorKind = null
            )
        )
        assertEquals("APP_UPD_R4", trace.selectedRuleId)
        assertEquals(AppUpdateCardState.READY_TO_INSTALL, trace.result.cardState)
        assertEquals(AppUpdatePrimaryAction.INSTALL_APK, trace.result.primaryAction)
        assertEquals(AppUpdateBadgeTone.SUCCESS, trace.result.badgeTone)
        assertTrue(trace.result.channelSelectorEnabled)
        assertTrue(trace.result.showSecondaryCheckButton)
    }

    @Test
    fun `ui table fails safe on rate limit, integrity failure, and network errors`() {
        val rateLimited = AppUpdateDecisionModel.evaluateUiWithTrace(
            AppUpdateUiInput(
                selectedChannel = AppUpdateChannel.RELEASE,
                isChecking = false,
                isDownloadActive = false,
                isDownloadQueued = false,
                freshnessStatus = BuildFreshnessStatus.UPDATE_AVAILABLE,
                hasMatchingDownloadedApk = true,
                lastErrorKind = AppUpdateCheckErrorKind.RATE_LIMITED
            )
        )
        assertEquals("APP_UPD_R5", rateLimited.selectedRuleId)
        assertEquals(AppUpdateCardState.ERROR, rateLimited.result.cardState)
        assertEquals(AppUpdatePrimaryAction.CHECK_UPDATE, rateLimited.result.primaryAction)
        assertEquals("制限中", rateLimited.result.badgeText)
        assertTrue(rateLimited.result.channelSelectorEnabled)

        val integrityErr = AppUpdateDecisionModel.evaluateUiWithTrace(
            AppUpdateUiInput(
                selectedChannel = AppUpdateChannel.REMOTE_HEAD,
                isChecking = false,
                isDownloadActive = false,
                isDownloadQueued = false,
                freshnessStatus = BuildFreshnessStatus.UPDATE_AVAILABLE,
                hasMatchingDownloadedApk = true,
                lastErrorKind = AppUpdateCheckErrorKind.INTEGRITY_CHECK_FAILED
            )
        )
        assertEquals("APP_UPD_R6", integrityErr.selectedRuleId)
        assertEquals(AppUpdateCardState.ERROR, integrityErr.result.cardState)
        assertEquals("検証失敗", integrityErr.result.badgeText)

        val netErr = AppUpdateDecisionModel.evaluateUiWithTrace(
            AppUpdateUiInput(
                selectedChannel = AppUpdateChannel.RELEASE,
                isChecking = false,
                isDownloadActive = false,
                isDownloadQueued = false,
                freshnessStatus = BuildFreshnessStatus.UPDATE_AVAILABLE,
                hasMatchingDownloadedApk = false,
                lastErrorKind = AppUpdateCheckErrorKind.NETWORK_ERROR
            )
        )
        assertEquals("APP_UPD_R7", netErr.selectedRuleId)
        assertEquals(AppUpdateCardState.ERROR, netErr.result.cardState)
        assertEquals("接続不可", netErr.result.badgeText)
        assertEquals(AppUpdatePrimaryAction.CHECK_UPDATE, netErr.result.primaryAction)
    }

    @Test
    fun `ui table transitions cleanly across UPDATE_AVAILABLE, UP_TO_DATE, NO_ARTIFACT, and IDLE`() {
        val updateAvail = AppUpdateDecisionModel.evaluateUiWithTrace(
            AppUpdateUiInput(AppUpdateChannel.RELEASE, false, false, false, BuildFreshnessStatus.UPDATE_AVAILABLE, false, null)
        )
        assertEquals("APP_UPD_R8", updateAvail.selectedRuleId)
        assertEquals(AppUpdatePrimaryAction.DOWNLOAD_UPDATE, updateAvail.result.primaryAction)
        assertTrue(updateAvail.result.showSecondaryCheckButton)
        assertTrue(updateAvail.result.channelSelectorEnabled)

        val upToDate = AppUpdateDecisionModel.evaluateUiWithTrace(
            AppUpdateUiInput(AppUpdateChannel.REMOTE_HEAD, false, false, false, BuildFreshnessStatus.UP_TO_DATE, false, null)
        )
        assertEquals("APP_UPD_R9", upToDate.selectedRuleId)
        assertEquals(AppUpdateCardState.UP_TO_DATE, upToDate.result.cardState)
        assertEquals(AppUpdatePrimaryAction.CHECK_UPDATE, upToDate.result.primaryAction)
        assertEquals(AppUpdateBadgeTone.SUCCESS, upToDate.result.badgeTone)

        val noArtifact = AppUpdateDecisionModel.evaluateUiWithTrace(
            AppUpdateUiInput(AppUpdateChannel.REMOTE_HEAD, false, false, false, BuildFreshnessStatus.NO_ARTIFACT, false, null)
        )
        assertEquals("APP_UPD_R10", noArtifact.selectedRuleId)
        assertEquals(AppUpdateCardState.NO_ARTIFACT, noArtifact.result.cardState)

        val idle = AppUpdateDecisionModel.evaluateUiWithTrace(
            AppUpdateUiInput(AppUpdateChannel.RELEASE, false, false, false, null, false, null)
        )
        assertEquals("APP_UPD_DEFAULT", idle.selectedRuleId)
        assertEquals(AppUpdateCardState.IDLE, idle.result.cardState)
        assertEquals(AppUpdatePrimaryAction.CHECK_UPDATE, idle.result.primaryAction)
        assertTrue(idle.result.channelSelectorEnabled)
    }

    // ------------------------------------------------------------------------
    // ApkInstallCompatibilityDecisionTable tests
    // ------------------------------------------------------------------------

    @Test
    fun `apk compatibility covers all 7 rules from unreadable archive to compatible`() {
        val certA = "542f51685d6834c0e60f1975fd71064e8341944778e7a5d4d9d21118ebfe6e7b"
        val certB = "c83e6c9c772498c2b5ca4e7dce1bf2d7da7e3444028d6ff18856abee36afc217"
        val pkg = "io.github.kenichikurihara.jotinput"

        val r1Unreadable = AppUpdateDecisionModel.evaluateApkInstallCompatibilityWithTrace(
            ApkInstallCompatibilityInput(false, pkg, pkg, 1L, 1L, setOf(certA), setOf(certA))
        )
        assertEquals("APK_COMPAT_R1", r1Unreadable.selectedRuleId)
        assertEquals(ApkInstallCompatibilityStatus.UNREADABLE_ARCHIVE, r1Unreadable.result.status)
        assertFalse(r1Unreadable.result.canProceedToInstaller)

        val r1BlankPkg = AppUpdateDecisionModel.evaluateApkInstallCompatibilityWithTrace(
            ApkInstallCompatibilityInput(true, pkg, " ", 1L, 1L, setOf(certA), setOf(certA))
        )
        assertEquals("APK_COMPAT_R1", r1BlankPkg.selectedRuleId)
        assertFalse(r1BlankPkg.result.canProceedToInstaller)

        val r2PkgMismatch = AppUpdateDecisionModel.evaluateApkInstallCompatibilityWithTrace(
            ApkInstallCompatibilityInput(true, pkg, "com.example.other", 1L, 2L, setOf(certA), setOf(certA))
        )
        assertEquals("APK_COMPAT_R2", r2PkgMismatch.selectedRuleId)
        assertEquals(ApkInstallCompatibilityStatus.PACKAGE_NAME_MISMATCH, r2PkgMismatch.result.status)
        assertFalse(r2PkgMismatch.result.canProceedToInstaller)

        val r3Downgrade = AppUpdateDecisionModel.evaluateApkInstallCompatibilityWithTrace(
            ApkInstallCompatibilityInput(true, pkg, pkg, 5L, 4L, setOf(certA), setOf(certA))
        )
        assertEquals("APK_COMPAT_R3", r3Downgrade.selectedRuleId)
        assertEquals(ApkInstallCompatibilityStatus.VERSION_DOWNGRADE, r3Downgrade.result.status)
        assertFalse(r3Downgrade.result.canProceedToInstaller)

        val r4MissingArchiveSig = AppUpdateDecisionModel.evaluateApkInstallCompatibilityWithTrace(
            ApkInstallCompatibilityInput(true, pkg, pkg, 1L, 1L, setOf(certA), emptySet())
        )
        assertEquals("APK_COMPAT_R4", r4MissingArchiveSig.selectedRuleId)
        assertEquals(ApkInstallCompatibilityStatus.UNREADABLE_ARCHIVE, r4MissingArchiveSig.result.status)
        assertFalse(r4MissingArchiveSig.result.canProceedToInstaller)

        val r5SigMismatch = AppUpdateDecisionModel.evaluateApkInstallCompatibilityWithTrace(
            ApkInstallCompatibilityInput(true, pkg, pkg, 1L, 1L, setOf(certA), setOf(certB))
        )
        assertEquals("APK_COMPAT_R5", r5SigMismatch.selectedRuleId)
        assertEquals(ApkInstallCompatibilityStatus.SIGNATURE_MISMATCH, r5SigMismatch.result.status)
        assertFalse(r5SigMismatch.result.canProceedToInstaller)

        val r6InstalledSigUnavailable = AppUpdateDecisionModel.evaluateApkInstallCompatibilityWithTrace(
            ApkInstallCompatibilityInput(true, pkg, pkg, 1L, 1L, emptySet(), setOf(certA))
        )
        assertEquals("APK_COMPAT_R6", r6InstalledSigUnavailable.selectedRuleId)
        assertEquals(ApkInstallCompatibilityStatus.SIGNATURE_UNVERIFIABLE, r6InstalledSigUnavailable.result.status)
        assertTrue(r6InstalledSigUnavailable.result.canProceedToInstaller)

        val defaultCompat = AppUpdateDecisionModel.evaluateApkInstallCompatibilityWithTrace(
            ApkInstallCompatibilityInput(true, pkg, pkg, 1L, 1L, setOf(certA), setOf(certA))
        )
        assertEquals("APK_COMPAT_DEFAULT", defaultCompat.selectedRuleId)
        assertEquals(ApkInstallCompatibilityStatus.COMPATIBLE, defaultCompat.result.status)
        assertTrue(defaultCompat.result.canProceedToInstaller)
    }
}
