package com.example.myasr

import com.example.myasr.context.ime.domain.MainAppUiDecisionModel.StatusTone
import com.example.myasr.context.ime.domain.OnboardingDecisionModel
import com.example.myasr.context.ime.domain.OnboardingDecisionModel.DriveRestoreInput
import com.example.myasr.context.ime.domain.OnboardingDecisionModel.DriveRestoreUiState
import com.example.myasr.context.ime.domain.OnboardingDecisionModel.LaunchAction
import com.example.myasr.context.ime.domain.OnboardingDecisionModel.OnboardingLaunchInput
import com.example.myasr.context.ime.domain.OnboardingDecisionModel.OnboardingMode
import com.example.myasr.context.ime.domain.OnboardingDecisionModel.OnboardingStep
import com.example.myasr.context.ime.domain.OnboardingDecisionModel.StepGuidanceInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingDecisionModelTest {

    @Test
    fun `launch decision - clean fresh install launches onboarding`() {
        val trace = OnboardingDecisionModel.evaluateLaunchWithTrace(
            OnboardingLaunchInput(
                forceLaunch = false,
                onboardingCompleted = false,
                imeEnabled = false,
                japaneseAsrReady = false,
                totalDictWords = 0,
                hasLocalCustomDict = false,
                hasStoredDriveUri = false
            )
        )
        assertEquals(LaunchAction.LAUNCH_FRESH, trace.result.action)
        assertTrue(trace.result.shouldShowOnboarding)
        assertFalse(trace.result.shouldMarkCompletedSilently)
        assertEquals(OnboardingMode.FRESH_INSTALL, trace.result.suggestedMode)
    }

    @Test
    fun `launch decision - existing user upgrade auto migrates and skips onboarding`() {
        val decision = OnboardingDecisionModel.evaluateLaunch(
            OnboardingLaunchInput(
                forceLaunch = false,
                onboardingCompleted = false,
                imeEnabled = true,
                japaneseAsrReady = true,
                totalDictWords = 150000,
                hasLocalCustomDict = true,
                hasStoredDriveUri = false
            )
        )
        assertEquals(LaunchAction.AUTO_MIGRATE_COMPLETE, decision.action)
        assertFalse(decision.shouldShowOnboarding)
        assertTrue(decision.shouldMarkCompletedSilently)
    }

    @Test
    fun `launch decision - auto backup reinstall with restored completed flag but missing binaries triggers onboarding`() {
        val decision = OnboardingDecisionModel.evaluateLaunch(
            OnboardingLaunchInput(
                forceLaunch = false,
                onboardingCompleted = true,
                imeEnabled = false,
                japaneseAsrReady = false,
                totalDictWords = 0,
                hasLocalCustomDict = true,
                hasStoredDriveUri = true
            )
        )
        assertEquals(LaunchAction.LAUNCH_REINSTALL_DRIVE_HINT, decision.action)
        assertTrue(decision.shouldShowOnboarding)
        assertEquals(OnboardingMode.DRIVE_RESTORE, decision.suggestedMode)
    }

    @Test
    fun `launch decision - completed user with IME enabled skips to main`() {
        val decision = OnboardingDecisionModel.evaluateLaunch(
            OnboardingLaunchInput(
                forceLaunch = false,
                onboardingCompleted = true,
                imeEnabled = true,
                japaneseAsrReady = false,
                totalDictWords = 0,
                hasLocalCustomDict = false,
                hasStoredDriveUri = false
            )
        )
        assertEquals(LaunchAction.SKIP_TO_MAIN, decision.action)
        assertFalse(decision.shouldShowOnboarding)
    }

    @Test
    fun `launch decision - explicit force launch always opens onboarding`() {
        val decision = OnboardingDecisionModel.evaluateLaunch(
            OnboardingLaunchInput(
                forceLaunch = true,
                onboardingCompleted = true,
                imeEnabled = true,
                japaneseAsrReady = true,
                totalDictWords = 100000,
                hasLocalCustomDict = false,
                hasStoredDriveUri = true
            )
        )
        assertEquals(LaunchAction.LAUNCH_EXPLICIT, decision.action)
        assertTrue(decision.shouldShowOnboarding)
        assertEquals(OnboardingMode.DRIVE_RESTORE, decision.suggestedMode)
    }

    @Test
    fun `drive restore status - detects SAF permission loss after auto backup reinstall`() {
        val decision = OnboardingDecisionModel.evaluateDriveRestoreStatus(
            DriveRestoreInput(
                isSyncing = false,
                hasStoredUri = true,
                hasValidPermission = false,
                lastSyncSuccess = false,
                lastRestoredCount = 0,
                lastErrorMessage = null
            )
        )
        assertEquals(DriveRestoreUiState.PERMISSION_LOST, decision.uiState)
        assertEquals(StatusTone.WARNING, decision.tone)
    }

    @Test
    fun `drive restore status - reports restored ok with count`() {
        val decision = OnboardingDecisionModel.evaluateDriveRestoreStatus(
            DriveRestoreInput(
                isSyncing = false,
                hasStoredUri = true,
                hasValidPermission = true,
                lastSyncSuccess = true,
                lastRestoredCount = 42,
                lastErrorMessage = null
            )
        )
        assertEquals(DriveRestoreUiState.RESTORED_OK, decision.uiState)
        assertEquals(StatusTone.SUCCESS, decision.tone)
        assertTrue(decision.descriptionText.contains("42件"))
    }

    @Test
    fun `step guidance - background download running allows non-blocking next step`() {
        val decision = OnboardingDecisionModel.evaluateStepGuidance(
            StepGuidanceInput(
                step = OnboardingStep.DATA_PREP,
                mode = OnboardingMode.FRESH_INSTALL,
                provisioningActive = true,
                provisioningProgress = 37,
                provisioningFailed = false,
                japaneseAsrReady = false,
                totalDictWords = 0,
                imeEnabled = false,
                imeSelected = false,
                micGranted = false,
                driveStatus = DriveRestoreUiState.NOT_CONNECTED
            )
        )
        assertTrue(decision.nextButtonEnabled)
        assertTrue(decision.nextButtonLabel.contains("続けながら次へ"))
        assertTrue(decision.statusHintText.contains("37%"))
    }

    @Test
    fun `step guidance - download failed still allows advancing without deadlock`() {
        val decision = OnboardingDecisionModel.evaluateStepGuidance(
            StepGuidanceInput(
                step = OnboardingStep.DATA_PREP,
                mode = OnboardingMode.REINSTALL,
                provisioningActive = false,
                provisioningProgress = 0,
                provisioningFailed = true,
                japaneseAsrReady = false,
                totalDictWords = 0,
                imeEnabled = false,
                imeSelected = false,
                micGranted = false,
                driveStatus = DriveRestoreUiState.NOT_CONNECTED
            )
        )
        assertTrue(decision.nextButtonEnabled)
        assertTrue(decision.nextButtonLabel.contains("このまま次へ"))
    }

    @Test
    fun `stepsForMode places DRIVE_RESTORE before DATA_PREP in DRIVE_RESTORE mode`() {
        val driveSteps = OnboardingDecisionModel.stepsForMode(OnboardingMode.DRIVE_RESTORE)
        assertEquals(
            listOf(
                OnboardingStep.WELCOME,
                OnboardingStep.DRIVE_RESTORE,
                OnboardingStep.DATA_PREP,
                OnboardingStep.PERMISSIONS_AND_IME,
                OnboardingStep.COMPLETE_AND_TEST
            ),
            driveSteps
        )

        val freshSteps = OnboardingDecisionModel.stepsForMode(OnboardingMode.FRESH_INSTALL)
        assertEquals(OnboardingStep.DATA_PREP, freshSteps[1])
        assertEquals(OnboardingStep.DRIVE_RESTORE, freshSteps[2])
    }

    @Test
    fun `onboardingSlmProposalDecisionTable recommends missing Qwen35 and respects installed or opted-out states`() {
        val propose = OnboardingDecisionModel.evaluateSlmProposal(
            OnboardingDecisionModel.OnboardingSlmProposalInput(
                selectedEngine = com.example.myasr.context.reranking.domain.RerankEngineType.LOCAL_SLM_3_5_0_8B,
                modelInstalled = false,
                isDownloading = false
            )
        )
        assertTrue(propose.shouldOfferDownload)
        assertEquals("推奨・未取得", propose.badgeText)

        val downloading = OnboardingDecisionModel.evaluateSlmProposal(
            OnboardingDecisionModel.OnboardingSlmProposalInput(
                selectedEngine = com.example.myasr.context.reranking.domain.RerankEngineType.LOCAL_SLM_3_5_0_8B,
                modelInstalled = false,
                isDownloading = true
            )
        )
        assertFalse(downloading.shouldOfferDownload)
        assertEquals("取得中...", downloading.badgeText)

        val installed = OnboardingDecisionModel.evaluateSlmProposal(
            OnboardingDecisionModel.OnboardingSlmProposalInput(
                selectedEngine = com.example.myasr.context.reranking.domain.RerankEngineType.LOCAL_SLM_3_5_2B,
                modelInstalled = true,
                isDownloading = false
            )
        )
        assertFalse(installed.shouldOfferDownload)
        assertEquals("導入済 ✓", installed.badgeText)

        val optOut = OnboardingDecisionModel.evaluateSlmProposal(
            OnboardingDecisionModel.OnboardingSlmProposalInput(
                selectedEngine = com.example.myasr.context.reranking.domain.RerankEngineType.NONE,
                modelInstalled = false,
                isDownloading = false
            )
        )
        assertFalse(optOut.shouldOfferDownload)
        assertEquals("スキップ", optOut.badgeText)
    }
}
