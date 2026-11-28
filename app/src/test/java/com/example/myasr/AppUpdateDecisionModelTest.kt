package com.example.myasr

import com.example.myasr.context.provisioning.domain.ApkInstallCompatibilityStatus
import com.example.myasr.context.provisioning.domain.AppUpdateBadgeTone
import com.example.myasr.context.provisioning.domain.AppUpdateCardState
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
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateDecisionModelTest {

    private fun artifact(
        artifactId: Long = 100L,
        createdAtMs: Long = 2_000_000L,
        expired: Boolean = false,
        runId: Long = 200L,
        headSha: String = "abcdef1234567890"
    ) = RemoteBuildArtifact(
        artifactId = artifactId,
        name = "app-debug",
        sizeInBytes = 37_000_000L,
        archiveDownloadUrl = "https://api.github.com/repos/kenichikurihara/jotinput/actions/artifacts/$artifactId/zip",
        createdAtMs = createdAtMs,
        expiresAtMs = createdAtMs + 86_400_000L,
        expired = expired,
        runId = runId,
        headSha = headSha,
        headBranch = "main"
    )

    private fun installed(
        gitSha: String = "11111112222222",
        commitEpochMs: Long = 1_000_000L,
        githubRunId: Long = 0L,
        isDebugBuild: Boolean = true
    ) = InstalledAppBuildInfo(
        versionName = "1.1",
        versionCode = 2,
        gitSha = gitSha,
        commitEpochMs = commitEpochMs,
        githubRunId = githubRunId,
        isDebugBuild = isDebugBuild
    )

    // ------------------------------------------------------------------------
    // AppBuildFreshnessDecisionTable tests
    // ------------------------------------------------------------------------

    @Test
    fun `freshness returns NO_ARTIFACT when remote artifact is null or expired`() {
        val nullTrace = AppUpdateDecisionModel.evaluateBuildFreshnessWithTrace(
            BuildFreshnessInput(installed = installed(), remoteArtifact = null)
        )
        assertEquals("BUILD_FRESH_R1", nullTrace.selectedRuleId)
        assertEquals(BuildFreshnessStatus.NO_ARTIFACT, nullTrace.result.status)

        val expiredTrace = AppUpdateDecisionModel.evaluateBuildFreshnessWithTrace(
            BuildFreshnessInput(installed = installed(), remoteArtifact = artifact(expired = true))
        )
        assertEquals("BUILD_FRESH_R1", expiredTrace.selectedRuleId)
        assertEquals(BuildFreshnessStatus.NO_ARTIFACT, expiredTrace.result.status)
    }

    @Test
    fun `freshness compares CI runId accurately when both builds originate from CI`() {
        val newerTrace = AppUpdateDecisionModel.evaluateBuildFreshnessWithTrace(
            BuildFreshnessInput(
                installed = installed(githubRunId = 200L, commitEpochMs = 5_000_000L),
                remoteArtifact = artifact(runId = 201L, createdAtMs = 1_000_000L)
            )
        )
        assertEquals("BUILD_FRESH_R2", newerTrace.selectedRuleId)
        assertEquals(BuildFreshnessStatus.UPDATE_AVAILABLE, newerTrace.result.status)

        val sameTrace = AppUpdateDecisionModel.evaluateBuildFreshnessWithTrace(
            BuildFreshnessInput(
                installed = installed(githubRunId = 200L),
                remoteArtifact = artifact(runId = 200L)
            )
        )
        assertEquals("BUILD_FRESH_R3", sameTrace.selectedRuleId)
        assertEquals(BuildFreshnessStatus.UP_TO_DATE, sameTrace.result.status)

        val olderRemoteTrace = AppUpdateDecisionModel.evaluateBuildFreshnessWithTrace(
            BuildFreshnessInput(
                installed = installed(githubRunId = 205L),
                remoteArtifact = artifact(runId = 200L)
            )
        )
        assertEquals("BUILD_FRESH_R3", olderRemoteTrace.selectedRuleId)
        assertEquals(BuildFreshnessStatus.UP_TO_DATE, olderRemoteTrace.result.status)

        // Realistic 11-digit GitHub Actions run IDs (regression guard against small synthetic run IDs)
        val ciScaleNewerTrace = AppUpdateDecisionModel.evaluateBuildFreshnessWithTrace(
            BuildFreshnessInput(
                installed = installed(githubRunId = 46_090_445_392L),
                remoteArtifact = artifact(runId = 46_090_445_392L + 1000L)
            )
        )
        assertEquals("BUILD_FRESH_R2", ciScaleNewerTrace.selectedRuleId)
        assertEquals(BuildFreshnessStatus.UPDATE_AVAILABLE, ciScaleNewerTrace.result.status)

        val ciScaleSmallerRemoteTrace = AppUpdateDecisionModel.evaluateBuildFreshnessWithTrace(
            BuildFreshnessInput(
                installed = installed(githubRunId = 46_090_445_392L),
                remoteArtifact = artifact(runId = 999_999L)
            )
        )
        assertEquals("BUILD_FRESH_R3", ciScaleSmallerRemoteTrace.selectedRuleId)
        assertEquals(BuildFreshnessStatus.UP_TO_DATE, ciScaleSmallerRemoteTrace.result.status)
    }

    @Test
    fun `freshness treats matching git SHA on local deploy build as UP_TO_DATE`() {
        val trace = AppUpdateDecisionModel.evaluateBuildFreshnessWithTrace(
            BuildFreshnessInput(
                installed = installed(gitSha = "ABCDEF1234567890", commitEpochMs = 1_000_000L, githubRunId = 0L),
                remoteArtifact = artifact(createdAtMs = 9_000_000L, headSha = "abcdef1234567890")
            )
        )
        assertEquals("BUILD_FRESH_R4", trace.selectedRuleId)
        assertEquals(BuildFreshnessStatus.UP_TO_DATE, trace.result.status)
    }

    @Test
    fun `freshness compares timestamps between local build and CI artifact`() {
        val updateTrace = AppUpdateDecisionModel.evaluateBuildFreshnessWithTrace(
            BuildFreshnessInput(
                installed = installed(gitSha = "1111111", commitEpochMs = 1_000_000L, githubRunId = 0L),
                remoteArtifact = artifact(createdAtMs = 2_000_000L, headSha = "2222222")
            )
        )
        assertEquals("BUILD_FRESH_R5", updateTrace.selectedRuleId)
        assertEquals(BuildFreshnessStatus.UPDATE_AVAILABLE, updateTrace.result.status)

        val localNewerTrace = AppUpdateDecisionModel.evaluateBuildFreshnessWithTrace(
            BuildFreshnessInput(
                installed = installed(gitSha = "3333333", commitEpochMs = 3_000_000L, githubRunId = 0L),
                remoteArtifact = artifact(createdAtMs = 2_000_000L, headSha = "2222222")
            )
        )
        assertEquals("BUILD_FRESH_R6", localNewerTrace.selectedRuleId)
        assertEquals(BuildFreshnessStatus.UP_TO_DATE, localNewerTrace.result.status)
    }

    @Test
    fun `freshness handles missing timestamps safely`() {
        val missingRemoteTs = AppUpdateDecisionModel.evaluateBuildFreshnessWithTrace(
            BuildFreshnessInput(
                installed = installed(gitSha = "unknown", commitEpochMs = 0L, githubRunId = 0L),
                remoteArtifact = artifact(createdAtMs = 0L, headSha = "2222222")
            )
        )
        assertEquals("BUILD_FRESH_R7", missingRemoteTs.selectedRuleId)
        assertEquals(BuildFreshnessStatus.NO_ARTIFACT, missingRemoteTs.result.status)

        val unknownLocalValidRemote = AppUpdateDecisionModel.evaluateBuildFreshnessWithTrace(
            BuildFreshnessInput(
                installed = installed(gitSha = "unknown", commitEpochMs = 0L, githubRunId = 0L),
                remoteArtifact = artifact(createdAtMs = 2_000_000L, headSha = "2222222")
            )
        )
        assertEquals("BUILD_FRESH_DEFAULT", unknownLocalValidRemote.selectedRuleId)
        assertEquals(BuildFreshnessStatus.UPDATE_AVAILABLE, unknownLocalValidRemote.result.status)
    }

    // ------------------------------------------------------------------------
    // AppUpdateUiDecisionTable tests
    // ------------------------------------------------------------------------

    @Test
    fun `ui table hides card completely on non-debug builds even if update or download flags are set`() {
        val trace = AppUpdateDecisionModel.evaluateUiWithTrace(
            AppUpdateUiInput(
                isDebugBuild = false,
                hasGitHubToken = true,
                isChecking = false,
                isDownloadActive = true,
                isDownloadQueued = true,
                freshnessStatus = BuildFreshnessStatus.UPDATE_AVAILABLE,
                hasMatchingDownloadedApk = true,
                lastErrorKind = null
            )
        )
        assertEquals("APP_UPD_R1", trace.selectedRuleId)
        assertFalse(trace.result.isCardVisible)
        assertEquals(AppUpdateCardState.HIDDEN, trace.result.cardState)
        assertEquals(AppUpdatePrimaryAction.NONE, trace.result.primaryAction)
    }

    @Test
    fun `ui table fails safe when GitHub token is missing`() {
        val trace = AppUpdateDecisionModel.evaluateUiWithTrace(
            AppUpdateUiInput(
                isDebugBuild = true,
                hasGitHubToken = false,
                isChecking = false,
                isDownloadActive = false,
                isDownloadQueued = false,
                freshnessStatus = BuildFreshnessStatus.UPDATE_AVAILABLE,
                hasMatchingDownloadedApk = false,
                lastErrorKind = null
            )
        )
        assertEquals("APP_UPD_R6", trace.selectedRuleId)
        assertTrue(trace.result.isCardVisible)
        assertEquals(AppUpdateCardState.TOKEN_MISSING, trace.result.cardState)
        assertEquals(AppUpdatePrimaryAction.CONFIGURE_TOKEN, trace.result.primaryAction)
        assertEquals(AppUpdateBadgeTone.NEUTRAL, trace.result.badgeTone)
        assertFalse(trace.result.showProgressBar)
    }

    @Test
    fun `ui table prioritizes downloading, queued, and checking states`() {
        val downloading = AppUpdateDecisionModel.evaluateUiWithTrace(
            AppUpdateUiInput(
                isDebugBuild = true,
                hasGitHubToken = true,
                isChecking = false,
                isDownloadActive = true,
                isDownloadQueued = false,
                freshnessStatus = BuildFreshnessStatus.UPDATE_AVAILABLE,
                hasMatchingDownloadedApk = false,
                lastErrorKind = null
            )
        )
        assertEquals("APP_UPD_R2", downloading.selectedRuleId)
        assertEquals(AppUpdateCardState.DOWNLOADING, downloading.result.cardState)
        assertEquals(AppUpdatePrimaryAction.CANCEL_DOWNLOAD, downloading.result.primaryAction)
        assertTrue(downloading.result.showProgressBar)

        val queued = AppUpdateDecisionModel.evaluateUiWithTrace(
            AppUpdateUiInput(
                isDebugBuild = true,
                hasGitHubToken = true,
                isChecking = false,
                isDownloadActive = false,
                isDownloadQueued = true,
                freshnessStatus = BuildFreshnessStatus.UPDATE_AVAILABLE,
                hasMatchingDownloadedApk = false,
                lastErrorKind = null
            )
        )
        assertEquals("APP_UPD_R3", queued.selectedRuleId)
        assertEquals(AppUpdateCardState.QUEUED, queued.result.cardState)
        assertEquals(AppUpdatePrimaryAction.CANCEL_DOWNLOAD, queued.result.primaryAction)
        assertTrue(queued.result.showProgressBar)

        val checking = AppUpdateDecisionModel.evaluateUiWithTrace(
            AppUpdateUiInput(
                isDebugBuild = true,
                hasGitHubToken = true,
                isChecking = true,
                isDownloadActive = false,
                isDownloadQueued = false,
                freshnessStatus = null,
                hasMatchingDownloadedApk = false,
                lastErrorKind = null
            )
        )
        assertEquals("APP_UPD_R4", checking.selectedRuleId)
        assertEquals(AppUpdateCardState.CHECKING, checking.result.cardState)
        assertFalse(checking.result.primaryButtonEnabled)
    }

    @Test
    fun `ui table offers one-tap install when matching downloaded APK exists without errors`() {
        val trace = AppUpdateDecisionModel.evaluateUiWithTrace(
            AppUpdateUiInput(
                isDebugBuild = true,
                hasGitHubToken = true,
                isChecking = false,
                isDownloadActive = false,
                isDownloadQueued = false,
                freshnessStatus = BuildFreshnessStatus.UPDATE_AVAILABLE,
                hasMatchingDownloadedApk = true,
                lastErrorKind = null
            )
        )
        assertEquals("APP_UPD_R5", trace.selectedRuleId)
        assertEquals(AppUpdateCardState.READY_TO_INSTALL, trace.result.cardState)
        assertEquals(AppUpdatePrimaryAction.INSTALL_APK, trace.result.primaryAction)
        assertEquals(AppUpdateBadgeTone.SUCCESS, trace.result.badgeTone)
        assertTrue(trace.result.showSecondaryCheckButton)
    }

    @Test
    fun `ui table fails safe on auth and network errors even when matching APK or update status exists`() {
        val authErr = AppUpdateDecisionModel.evaluateUiWithTrace(
            AppUpdateUiInput(
                isDebugBuild = true,
                hasGitHubToken = true,
                isChecking = false,
                isDownloadActive = false,
                isDownloadQueued = false,
                freshnessStatus = BuildFreshnessStatus.UPDATE_AVAILABLE,
                hasMatchingDownloadedApk = true,
                lastErrorKind = AppUpdateCheckErrorKind.AUTH_FAILED
            )
        )
        assertEquals("APP_UPD_R7", authErr.selectedRuleId)
        assertEquals(AppUpdateCardState.AUTH_ERROR, authErr.result.cardState)
        assertEquals(AppUpdatePrimaryAction.CONFIGURE_TOKEN, authErr.result.primaryAction)
        assertTrue(authErr.result.showSecondaryCheckButton)

        val netErr = AppUpdateDecisionModel.evaluateUiWithTrace(
            AppUpdateUiInput(
                isDebugBuild = true,
                hasGitHubToken = true,
                isChecking = false,
                isDownloadActive = false,
                isDownloadQueued = false,
                freshnessStatus = BuildFreshnessStatus.UPDATE_AVAILABLE,
                hasMatchingDownloadedApk = false,
                lastErrorKind = AppUpdateCheckErrorKind.NETWORK_ERROR
            )
        )
        assertEquals("APP_UPD_R8", netErr.selectedRuleId)
        assertEquals(AppUpdateCardState.NETWORK_ERROR, netErr.result.cardState)
        assertEquals(AppUpdatePrimaryAction.CHECK_UPDATE, netErr.result.primaryAction)
    }

    @Test
    fun `ui table transitions cleanly across UPDATE_AVAILABLE, UP_TO_DATE, NO_ARTIFACT, and IDLE`() {
        val updateAvail = AppUpdateDecisionModel.evaluateUiWithTrace(
            AppUpdateUiInput(true, true, false, false, false, BuildFreshnessStatus.UPDATE_AVAILABLE, false, null)
        )
        assertEquals("APP_UPD_R9", updateAvail.selectedRuleId)
        assertEquals(AppUpdatePrimaryAction.DOWNLOAD_UPDATE, updateAvail.result.primaryAction)
        assertTrue(updateAvail.result.showSecondaryCheckButton)

        val upToDate = AppUpdateDecisionModel.evaluateUiWithTrace(
            AppUpdateUiInput(true, true, false, false, false, BuildFreshnessStatus.UP_TO_DATE, false, null)
        )
        assertEquals("APP_UPD_R10", upToDate.selectedRuleId)
        assertEquals(AppUpdateCardState.UP_TO_DATE, upToDate.result.cardState)
        assertEquals(AppUpdatePrimaryAction.CHECK_UPDATE, upToDate.result.primaryAction)
        assertEquals(AppUpdateBadgeTone.SUCCESS, upToDate.result.badgeTone)

        val noArtifact = AppUpdateDecisionModel.evaluateUiWithTrace(
            AppUpdateUiInput(true, true, false, false, false, BuildFreshnessStatus.NO_ARTIFACT, false, null)
        )
        assertEquals("APP_UPD_R11", noArtifact.selectedRuleId)
        assertEquals(AppUpdateCardState.NO_ARTIFACT, noArtifact.result.cardState)

        val idle = AppUpdateDecisionModel.evaluateUiWithTrace(
            AppUpdateUiInput(true, true, false, false, false, null, false, null)
        )
        assertEquals("APP_UPD_DEFAULT", idle.selectedRuleId)
        assertEquals(AppUpdateCardState.IDLE, idle.result.cardState)
        assertEquals(AppUpdatePrimaryAction.CHECK_UPDATE, idle.result.primaryAction)
    }

    // ------------------------------------------------------------------------
    // ApkInstallCompatibilityDecisionTable tests
    // ------------------------------------------------------------------------

    @Test
    fun `apk compatibility covers all 7 rules from unreadable archive to compatible`() {
        val certA = "542f51685d6834c0e60f1975fd71064e8341944778e7a5d4d9d21118ebfe6e7b"
        val certB = "c83e6c9c772498c2b5ca4e7dce1bf2d7da7e3444028d6ff18856abee36afc217"
        val pkg = "io.github.kenichikurihara.jotinput"

        // APK_COMPAT_R1: unreadable or blank package name
        val r1Unreadable = AppUpdateDecisionModel.evaluateApkInstallCompatibilityWithTrace(
            ApkInstallCompatibilityInput(
                apkReadable = false,
                installedPackageName = pkg,
                archivePackageName = pkg,
                installedVersionCode = 1L,
                archiveVersionCode = 1L,
                installedSigningCertSha256s = setOf(certA),
                archiveSigningCertSha256s = setOf(certA)
            )
        )
        assertEquals("APK_COMPAT_R1", r1Unreadable.selectedRuleId)
        assertEquals(ApkInstallCompatibilityStatus.UNREADABLE_ARCHIVE, r1Unreadable.result.status)
        assertFalse(r1Unreadable.result.canProceedToInstaller)

        val r1BlankPkg = AppUpdateDecisionModel.evaluateApkInstallCompatibilityWithTrace(
            ApkInstallCompatibilityInput(
                apkReadable = true,
                installedPackageName = pkg,
                archivePackageName = " ",
                installedVersionCode = 1L,
                archiveVersionCode = 1L,
                installedSigningCertSha256s = setOf(certA),
                archiveSigningCertSha256s = setOf(certA)
            )
        )
        assertEquals("APK_COMPAT_R1", r1BlankPkg.selectedRuleId)
        assertFalse(r1BlankPkg.result.canProceedToInstaller)

        // APK_COMPAT_R2: package ID mismatch
        val r2PkgMismatch = AppUpdateDecisionModel.evaluateApkInstallCompatibilityWithTrace(
            ApkInstallCompatibilityInput(
                apkReadable = true,
                installedPackageName = pkg,
                archivePackageName = "com.example.other",
                installedVersionCode = 1L,
                archiveVersionCode = 2L,
                installedSigningCertSha256s = setOf(certA),
                archiveSigningCertSha256s = setOf(certA)
            )
        )
        assertEquals("APK_COMPAT_R2", r2PkgMismatch.selectedRuleId)
        assertEquals(ApkInstallCompatibilityStatus.PACKAGE_NAME_MISMATCH, r2PkgMismatch.result.status)
        assertFalse(r2PkgMismatch.result.canProceedToInstaller)

        // APK_COMPAT_R3: versionCode downgrade
        val r3Downgrade = AppUpdateDecisionModel.evaluateApkInstallCompatibilityWithTrace(
            ApkInstallCompatibilityInput(
                apkReadable = true,
                installedPackageName = pkg,
                archivePackageName = pkg,
                installedVersionCode = 5L,
                archiveVersionCode = 4L,
                installedSigningCertSha256s = setOf(certA),
                archiveSigningCertSha256s = setOf(certA)
            )
        )
        assertEquals("APK_COMPAT_R3", r3Downgrade.selectedRuleId)
        assertEquals(ApkInstallCompatibilityStatus.VERSION_DOWNGRADE, r3Downgrade.result.status)
        assertFalse(r3Downgrade.result.canProceedToInstaller)

        // APK_COMPAT_R4: archive missing signing certificates while installed is signed
        val r4MissingArchiveSig = AppUpdateDecisionModel.evaluateApkInstallCompatibilityWithTrace(
            ApkInstallCompatibilityInput(
                apkReadable = true,
                installedPackageName = pkg,
                archivePackageName = pkg,
                installedVersionCode = 1L,
                archiveVersionCode = 1L,
                installedSigningCertSha256s = setOf(certA),
                archiveSigningCertSha256s = emptySet()
            )
        )
        assertEquals("APK_COMPAT_R4", r4MissingArchiveSig.selectedRuleId)
        assertEquals(ApkInstallCompatibilityStatus.UNREADABLE_ARCHIVE, r4MissingArchiveSig.result.status)
        assertFalse(r4MissingArchiveSig.result.canProceedToInstaller)

        // APK_COMPAT_R5: signing certificate mismatch (ephemeral CI key vs local key)
        val r5SigMismatch = AppUpdateDecisionModel.evaluateApkInstallCompatibilityWithTrace(
            ApkInstallCompatibilityInput(
                apkReadable = true,
                installedPackageName = pkg,
                archivePackageName = pkg,
                installedVersionCode = 1L,
                archiveVersionCode = 1L,
                installedSigningCertSha256s = setOf(certA),
                archiveSigningCertSha256s = setOf(certB)
            )
        )
        assertEquals("APK_COMPAT_R5", r5SigMismatch.selectedRuleId)
        assertEquals(ApkInstallCompatibilityStatus.SIGNATURE_MISMATCH, r5SigMismatch.result.status)
        assertFalse(r5SigMismatch.result.canProceedToInstaller)

        // APK_COMPAT_R6: installed signature unavailable -> allow system installer fallback
        val r6InstalledSigUnavailable = AppUpdateDecisionModel.evaluateApkInstallCompatibilityWithTrace(
            ApkInstallCompatibilityInput(
                apkReadable = true,
                installedPackageName = pkg,
                archivePackageName = pkg,
                installedVersionCode = 1L,
                archiveVersionCode = 1L,
                installedSigningCertSha256s = emptySet(),
                archiveSigningCertSha256s = setOf(certA)
            )
        )
        assertEquals("APK_COMPAT_R6", r6InstalledSigUnavailable.selectedRuleId)
        assertEquals(ApkInstallCompatibilityStatus.SIGNATURE_UNVERIFIABLE, r6InstalledSigUnavailable.result.status)
        assertTrue(r6InstalledSigUnavailable.result.canProceedToInstaller)

        // APK_COMPAT_DEFAULT: compatible APK with matching certificate
        val defaultCompat = AppUpdateDecisionModel.evaluateApkInstallCompatibilityWithTrace(
            ApkInstallCompatibilityInput(
                apkReadable = true,
                installedPackageName = pkg,
                archivePackageName = pkg,
                installedVersionCode = 1L,
                archiveVersionCode = 1L,
                installedSigningCertSha256s = setOf(certA),
                archiveSigningCertSha256s = setOf(certA)
            )
        )
        assertEquals("APK_COMPAT_DEFAULT", defaultCompat.selectedRuleId)
        assertEquals(ApkInstallCompatibilityStatus.COMPATIBLE, defaultCompat.result.status)
        assertTrue(defaultCompat.result.canProceedToInstaller)
    }
}
