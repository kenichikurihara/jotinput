module jotinput/onboarding_flow

open util/ordering[State]
open util/boolean

/**
 * Formal Alloy 6 Specification for Onboarding Flow
 * (Fresh Install, Reinstall / Auto Backup, Google Drive History Restore, Non-blocking Provisioning).
 */

abstract sig OnboardingMode {}
one sig ModeFresh, ModeReinstall, ModeDriveRestore extends OnboardingMode {}

abstract sig OnboardingStep {}
one sig StepWelcome, StepDataPrep, StepDriveRestore, StepPermissionsIme, StepComplete extends OnboardingStep {}

abstract sig LaunchAction {}
one sig
    LaunchExplicit,
    LaunchAutoMigrate,
    LaunchReinstallDriveHint,
    LaunchReinstall,
    LaunchFresh,
    LaunchSkipToMain
extends LaunchAction {}

abstract sig DriveUiStatus {}
one sig
    DriveSyncing,
    DriveRestoredOk,
    DriveError,
    DrivePermissionLost,
    DriveConnectedIdle,
    DriveNotConnected
extends DriveUiStatus {}

abstract sig ProvisioningState {}
one sig ProvIdle, ProvRunning, ProvDone, ProvFailed extends ProvisioningState {}

sig State {
    // Launch & environment inputs
    forceLaunch: one Bool,
    onboardingCompleted: one Bool,
    imeEnabled: one Bool,
    jaAsrReady: one Bool,
    hasDict: one Bool,
    hasLocalCustomDict: one Bool,
    hasStoredDriveUri: one Bool,
    drivePermValid: one Bool,
    isSyncingDrive: one Bool,
    driveSyncFailed: one Bool,
    driveRestored: one Bool,

    // Flow state
    mode: one OnboardingMode,
    step: one OnboardingStep,
    provState: one ProvisioningState,

    // Derived DMN outputs
    launchAction: one LaunchAction,
    driveStatus: one DriveUiStatus
}

pred deriveDmn[s: State] {
    // 1. OnboardingLaunchDecisionTable (FIRST Hit Policy)
    (s.forceLaunch = True) implies (s.launchAction = LaunchExplicit) else
    (s.onboardingCompleted = False and s.imeEnabled = True and (s.jaAsrReady = True or s.hasDict = True))
        implies (s.launchAction = LaunchAutoMigrate) else
    (s.onboardingCompleted = True and s.imeEnabled = False and s.jaAsrReady = False and s.hasDict = False)
        implies (
            (s.hasStoredDriveUri = True or s.hasLocalCustomDict = True)
                implies (s.launchAction = LaunchReinstallDriveHint)
                else (s.launchAction = LaunchReinstall)
        ) else
    (s.onboardingCompleted = True) implies (s.launchAction = LaunchSkipToMain) else
    (s.hasStoredDriveUri = True or s.hasLocalCustomDict = True) implies (s.launchAction = LaunchReinstallDriveHint) else
    (s.launchAction = LaunchFresh)

    // 2. DriveRestoreStatusDecisionTable (FIRST Hit Policy)
    (s.isSyncingDrive = True) implies (s.driveStatus = DriveSyncing) else
    (s.driveRestored = True) implies (s.driveStatus = DriveRestoredOk) else
    (s.driveSyncFailed = True) implies (s.driveStatus = DriveError) else
    (s.hasStoredDriveUri = True and s.drivePermValid = False) implies (s.driveStatus = DrivePermissionLost) else
    (s.hasStoredDriveUri = True and s.drivePermValid = True) implies (s.driveStatus = DriveConnectedIdle) else
    (s.driveStatus = DriveNotConnected)
}

pred initFresh[s: State] {
    s.forceLaunch = False
    s.onboardingCompleted = False
    s.imeEnabled = False
    s.jaAsrReady = False
    s.hasDict = False
    s.hasLocalCustomDict = False
    s.hasStoredDriveUri = False
    s.drivePermValid = False
    s.isSyncingDrive = False
    s.driveSyncFailed = False
    s.driveRestored = False
    s.mode = ModeFresh
    s.step = StepWelcome
    s.provState = ProvIdle
    deriveDmn[s]
}

pred initAutoBackupReinstall[s: State] {
    s.forceLaunch = False
    s.onboardingCompleted = True
    s.imeEnabled = False
    s.jaAsrReady = False
    s.hasDict = False
    s.hasLocalCustomDict = True
    s.hasStoredDriveUri = True
    s.drivePermValid = False // Auto Backup restores URI string without SAF permission!
    s.isSyncingDrive = False
    s.driveSyncFailed = False
    s.driveRestored = False
    s.mode = ModeReinstall
    s.step = StepWelcome
    s.provState = ProvIdle
    deriveDmn[s]
}

pred copyEnv[s, sNext: State] {
    sNext.forceLaunch = s.forceLaunch
    sNext.imeEnabled = s.imeEnabled
    sNext.jaAsrReady = s.jaAsrReady
    sNext.hasDict = s.hasDict
    sNext.hasLocalCustomDict = s.hasLocalCustomDict
    sNext.hasStoredDriveUri = s.hasStoredDriveUri
    sNext.drivePermValid = s.drivePermValid
    sNext.isSyncingDrive = s.isSyncingDrive
    sNext.driveSyncFailed = s.driveSyncFailed
    sNext.driveRestored = s.driveRestored
}

pred startBackgroundDownload[s, sNext: State] {
    copyEnv[s, sNext]
    sNext.onboardingCompleted = s.onboardingCompleted
    sNext.mode = s.mode
    sNext.step = s.step
    sNext.provState = ProvRunning
    deriveDmn[sNext]
}

pred failBackgroundDownload[s, sNext: State] {
    s.provState = ProvRunning
    copyEnv[s, sNext]
    sNext.onboardingCompleted = s.onboardingCompleted
    sNext.mode = s.mode
    sNext.step = s.step
    sNext.provState = ProvFailed
    deriveDmn[sNext]
}

pred advanceStepNonBlocking[s, sNext: State, nextStep: OnboardingStep] {
    copyEnv[s, sNext]
    sNext.mode = s.mode
    sNext.provState = s.provState // Background download continues untouched
    sNext.step = nextStep
    (nextStep = StepComplete) implies (sNext.onboardingCompleted = True) else (sNext.onboardingCompleted = s.onboardingCompleted)
    deriveDmn[sNext]
}

pred selectModeAndEnter[s, sNext: State, m: OnboardingMode] {
    s.step = StepWelcome
    copyEnv[s, sNext]
    sNext.onboardingCompleted = s.onboardingCompleted
    sNext.mode = m
    sNext.provState = s.provState
    (m = ModeDriveRestore) implies (sNext.step = StepDriveRestore) else (sNext.step = StepDataPrep)
    deriveDmn[sNext]
}

pred stepTransition[s, sNext: State] {
    startBackgroundDownload[s, sNext]
    or failBackgroundDownload[s, sNext]
    or (some m: OnboardingMode | selectModeAndEnter[s, sNext, m])
    or (some ns: OnboardingStep | advanceStepNonBlocking[s, sNext, ns])
}

// ============================================================================
// Safety Assertions (Must have ZERO counterexamples)
// ============================================================================

assert ExistingUserNeverForcedIntoOnboarding {
    all s: State |
        (deriveDmn[s] and s.forceLaunch = False and s.imeEnabled = True and (s.jaAsrReady = True or s.hasDict = True))
        implies (s.launchAction = LaunchSkipToMain or s.launchAction = LaunchAutoMigrate)
}

assert RevokedDrivePermissionNeverReportedConnected {
    all s: State |
        (deriveDmn[s] and s.isSyncingDrive = False and s.driveRestored = False and s.driveSyncFailed = False
         and s.hasStoredDriveUri = True and s.drivePermValid = False)
        implies (s.driveStatus = DrivePermissionLost)
}

assert AutoBackupReinstallDetectedEvenIfCompletedFlagRestored {
    all s: State |
        (deriveDmn[s] and s.forceLaunch = False and s.onboardingCompleted = True
         and s.imeEnabled = False and s.jaAsrReady = False and s.hasDict = False)
        implies (s.launchAction = LaunchReinstallDriveHint or s.launchAction = LaunchReinstall)
}

check ExistingUserNeverForcedIntoOnboarding for 5
check RevokedDrivePermissionNeverReportedConnected for 5
check AutoBackupReinstallDetectedEvenIfCompletedFlagRestored for 5

// ============================================================================
// Reachability Predicates (Non-vacuity & Non-blocking Proofs)
// ============================================================================

pred canCompleteWhileDownloadingRunning {
    initFresh[first]
    some s1, s2, s3: State |
        s1 = first.next and s2 = s1.next and s3 = s2.next and
        selectModeAndEnter[first, s1, ModeFresh] and
        startBackgroundDownload[s1, s2] and
        advanceStepNonBlocking[s2, s3, StepComplete] and
        s3.provState = ProvRunning and
        s3.step = StepComplete and
        s3.onboardingCompleted = True
}

pred canCompleteAfterDownloadFailed {
    initFresh[first]
    some s1, s2, s3, s4: State |
        s1 = first.next and s2 = s1.next and s3 = s2.next and s4 = s3.next and
        selectModeAndEnter[first, s1, ModeFresh] and
        startBackgroundDownload[s1, s2] and
        failBackgroundDownload[s2, s3] and
        advanceStepNonBlocking[s3, s4, StepComplete] and
        s4.provState = ProvFailed and
        s4.step = StepComplete
}

pred canRecoverAutoBackupDriveRestore {
    initAutoBackupReinstall[first]
    first.launchAction = LaunchReinstallDriveHint and
    first.driveStatus = DrivePermissionLost and
    some s1: State |
        s1 = first.next and
        selectModeAndEnter[first, s1, ModeDriveRestore] and
        s1.step = StepDriveRestore
}

run canCompleteWhileDownloadingRunning for 5 State
run canCompleteAfterDownloadFailed for 6 State
run canRecoverAutoBackupDriveRestore for 4 State
