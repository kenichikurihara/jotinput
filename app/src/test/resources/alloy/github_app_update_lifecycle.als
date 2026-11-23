module jotinput/github_app_update_lifecycle

open util/ordering[State]
open util/boolean

/**
 * Formal Alloy 6 Specification for Developer-Only GitHub Actions Build Update Checker
 * and Background Downloader (BC-6: AppUpdateDecisionModel).
 *
 * Verifies:
 * 1. ReleaseBuildNeverShowsCard: Release builds always hide the update card.
 * 2. NoTokenFailSafeNeverOffersDownload: Without a configured token, download is never offered
 *    (and install is never offered when no matching APK exists on disk).
 * 3. ErrorStateNeverOffersDownloadOrInstall: Auth, network, or download errors fail safe and
 *    never offer download or install.
 * 4. DownloadOnlyWhenTokenAndUpdateAvailable: Download CTA is shown only when debug, token
 *    configured, freshness is UPDATE_AVAILABLE, and no download/check/error is active.
 * 5. InstallOnlyWhenMatchingApkExistsAndDebug: Install CTA is shown only when debug and a
 *    matching verified APK exists on disk without errors.
 * 6. UpToDateNeverOffersDownloadOrInstall: Up-to-date state never offers download or install.
 * 7. ActiveDownloadAlwaysShowsProgressAndCancel: Active/queued background downloads always show
 *    progress and a cancel action.
 */

abstract sig FreshnessStatus {}
one sig FreshNone, FreshUpdateAvailable, FreshUpToDate, FreshNoArtifact extends FreshnessStatus {}

abstract sig ErrorState {}
one sig ErrNone, ErrTokenRequired, ErrAuthFailed, ErrNetworkError, ErrDownloadFailed extends ErrorState {}

abstract sig CardState {}
one sig
    CardHidden,
    CardDownloading,
    CardQueued,
    CardChecking,
    CardReadyToInstall,
    CardTokenMissing,
    CardAuthError,
    CardNetworkError,
    CardUpdateAvailable,
    CardUpToDate,
    CardNoArtifact,
    CardIdle
extends CardState {}

abstract sig PrimaryAction {}
one sig
    ActNone,
    ActCancel,
    ActCheck,
    ActInstall,
    ActConfigureToken,
    ActDownload
extends PrimaryAction {}

sig State {
    isDebugBuild: one Bool,
    hasToken: one Bool,
    checking: one Bool,
    downloadActive: one Bool,
    downloadQueued: one Bool,
    freshness: one FreshnessStatus,
    hasMatchingDownloadedApk: one Bool,
    errorState: one ErrorState,
    // Derived UI projections
    cardState: one CardState,
    primaryAction: one PrimaryAction,
    showProgress: one Bool,
    isCardVisible: one Bool
}

pred deriveUi[s: State] {
    (s.isDebugBuild = False) implies {
        s.cardState = CardHidden
        s.primaryAction = ActNone
        s.showProgress = False
        s.isCardVisible = False
    } else (s.downloadActive = True) implies {
        s.cardState = CardDownloading
        s.primaryAction = ActCancel
        s.showProgress = True
        s.isCardVisible = True
    } else (s.downloadQueued = True) implies {
        s.cardState = CardQueued
        s.primaryAction = ActCancel
        s.showProgress = True
        s.isCardVisible = True
    } else (s.checking = True) implies {
        s.cardState = CardChecking
        s.primaryAction = ActCheck
        s.showProgress = False
        s.isCardVisible = True
    } else (s.hasMatchingDownloadedApk = True and s.errorState = ErrNone and (s.freshness = FreshNone or s.freshness = FreshUpdateAvailable)) implies {
        s.cardState = CardReadyToInstall
        s.primaryAction = ActInstall
        s.showProgress = False
        s.isCardVisible = True
    } else (s.hasToken = False) implies {
        s.cardState = CardTokenMissing
        s.primaryAction = ActConfigureToken
        s.showProgress = False
        s.isCardVisible = True
    } else (s.errorState = ErrAuthFailed or s.errorState = ErrTokenRequired) implies {
        s.cardState = CardAuthError
        s.primaryAction = ActConfigureToken
        s.showProgress = False
        s.isCardVisible = True
    } else (s.errorState = ErrNetworkError or s.errorState = ErrDownloadFailed) implies {
        s.cardState = CardNetworkError
        s.primaryAction = ActCheck
        s.showProgress = False
        s.isCardVisible = True
    } else (s.freshness = FreshUpdateAvailable) implies {
        s.cardState = CardUpdateAvailable
        s.primaryAction = ActDownload
        s.showProgress = False
        s.isCardVisible = True
    } else (s.freshness = FreshUpToDate) implies {
        s.cardState = CardUpToDate
        s.primaryAction = ActCheck
        s.showProgress = False
        s.isCardVisible = True
    } else (s.freshness = FreshNoArtifact) implies {
        s.cardState = CardNoArtifact
        s.primaryAction = ActCheck
        s.showProgress = False
        s.isCardVisible = True
    } else {
        s.cardState = CardIdle
        s.primaryAction = ActCheck
        s.showProgress = False
        s.isCardVisible = True
    }
}

pred init[s: State] {
    s.hasToken = False
    s.checking = False
    s.downloadActive = False
    s.downloadQueued = False
    s.freshness = FreshNone
    s.hasMatchingDownloadedApk = False
    s.errorState = ErrNone
    deriveUi[s]
}

pred configureToken[s, sNext: State] {
    s.isDebugBuild = True
    sNext.isDebugBuild = s.isDebugBuild
    sNext.hasToken = True
    sNext.checking = s.checking
    sNext.downloadActive = s.downloadActive
    sNext.downloadQueued = s.downloadQueued
    sNext.freshness = s.freshness
    sNext.hasMatchingDownloadedApk = s.hasMatchingDownloadedApk
    sNext.errorState = ErrNone
    deriveUi[sNext]
}

pred clearToken[s, sNext: State] {
    s.isDebugBuild = True
    s.downloadActive = False
    s.downloadQueued = False
    s.checking = False
    sNext.isDebugBuild = s.isDebugBuild
    sNext.hasToken = False
    sNext.checking = False
    sNext.downloadActive = False
    sNext.downloadQueued = False
    sNext.freshness = FreshNone
    sNext.hasMatchingDownloadedApk = s.hasMatchingDownloadedApk
    sNext.errorState = ErrNone
    deriveUi[sNext]
}

pred startCheck[s, sNext: State] {
    s.isDebugBuild = True
    s.hasToken = True
    s.checking = False
    s.downloadActive = False
    s.downloadQueued = False
    sNext.isDebugBuild = s.isDebugBuild
    sNext.hasToken = True
    sNext.checking = True
    sNext.downloadActive = False
    sNext.downloadQueued = False
    sNext.freshness = s.freshness
    sNext.hasMatchingDownloadedApk = s.hasMatchingDownloadedApk
    sNext.errorState = ErrNone
    deriveUi[sNext]
}

pred finishCheckUpdateAvailable[s, sNext: State] {
    s.isDebugBuild = True
    s.checking = True
    sNext.isDebugBuild = s.isDebugBuild
    sNext.hasToken = s.hasToken
    sNext.checking = False
    sNext.downloadActive = s.downloadActive
    sNext.downloadQueued = s.downloadQueued
    sNext.freshness = FreshUpdateAvailable
    sNext.hasMatchingDownloadedApk = s.hasMatchingDownloadedApk
    sNext.errorState = ErrNone
    deriveUi[sNext]
}

pred finishCheckUpToDate[s, sNext: State] {
    s.isDebugBuild = True
    s.checking = True
    sNext.isDebugBuild = s.isDebugBuild
    sNext.hasToken = s.hasToken
    sNext.checking = False
    sNext.downloadActive = s.downloadActive
    sNext.downloadQueued = s.downloadQueued
    sNext.freshness = FreshUpToDate
    sNext.hasMatchingDownloadedApk = False
    sNext.errorState = ErrNone
    deriveUi[sNext]
}

pred finishCheckNoArtifact[s, sNext: State] {
    s.isDebugBuild = True
    s.checking = True
    sNext.isDebugBuild = s.isDebugBuild
    sNext.hasToken = s.hasToken
    sNext.checking = False
    sNext.downloadActive = s.downloadActive
    sNext.downloadQueued = s.downloadQueued
    sNext.freshness = FreshNoArtifact
    sNext.hasMatchingDownloadedApk = False
    sNext.errorState = ErrNone
    deriveUi[sNext]
}

pred finishCheckError[s, sNext: State, err: ErrorState] {
    s.isDebugBuild = True
    s.checking = True
    err != ErrNone
    sNext.isDebugBuild = s.isDebugBuild
    sNext.hasToken = s.hasToken
    sNext.checking = False
    sNext.downloadActive = s.downloadActive
    sNext.downloadQueued = s.downloadQueued
    sNext.freshness = s.freshness
    sNext.hasMatchingDownloadedApk = s.hasMatchingDownloadedApk
    sNext.errorState = err
    deriveUi[sNext]
}

pred enqueueDownload[s, sNext: State, queued: Bool] {
    s.isDebugBuild = True
    s.hasToken = True
    s.checking = False
    s.downloadActive = False
    s.downloadQueued = False
    s.freshness = FreshUpdateAvailable
    s.errorState = ErrNone
    sNext.isDebugBuild = s.isDebugBuild
    sNext.hasToken = True
    sNext.checking = False
    (queued = True) implies {
        sNext.downloadActive = False
        sNext.downloadQueued = True
    } else {
        sNext.downloadActive = True
        sNext.downloadQueued = False
    }
    sNext.freshness = s.freshness
    sNext.hasMatchingDownloadedApk = s.hasMatchingDownloadedApk
    sNext.errorState = ErrNone
    deriveUi[sNext]
}

pred startQueuedDownload[s, sNext: State] {
    s.isDebugBuild = True
    s.downloadQueued = True
    sNext.isDebugBuild = s.isDebugBuild
    sNext.hasToken = s.hasToken
    sNext.checking = s.checking
    sNext.downloadActive = True
    sNext.downloadQueued = False
    sNext.freshness = s.freshness
    sNext.hasMatchingDownloadedApk = s.hasMatchingDownloadedApk
    sNext.errorState = ErrNone
    deriveUi[sNext]
}

pred finishDownloadSuccess[s, sNext: State] {
    s.isDebugBuild = True
    s.downloadActive = True
    sNext.isDebugBuild = s.isDebugBuild
    sNext.hasToken = s.hasToken
    sNext.checking = False
    sNext.downloadActive = False
    sNext.downloadQueued = False
    sNext.freshness = FreshUpdateAvailable
    sNext.hasMatchingDownloadedApk = True
    sNext.errorState = ErrNone
    deriveUi[sNext]
}

pred finishDownloadFailure[s, sNext: State, err: ErrorState] {
    s.isDebugBuild = True
    s.downloadActive = True
    (err = ErrAuthFailed or err = ErrDownloadFailed or err = ErrNetworkError)
    sNext.isDebugBuild = s.isDebugBuild
    sNext.hasToken = s.hasToken
    sNext.checking = False
    sNext.downloadActive = False
    sNext.downloadQueued = False
    sNext.freshness = s.freshness
    sNext.hasMatchingDownloadedApk = s.hasMatchingDownloadedApk
    sNext.errorState = err
    deriveUi[sNext]
}

pred cancelDownload[s, sNext: State] {
    s.isDebugBuild = True
    (s.downloadActive = True or s.downloadQueued = True)
    sNext.isDebugBuild = s.isDebugBuild
    sNext.hasToken = s.hasToken
    sNext.checking = s.checking
    sNext.downloadActive = False
    sNext.downloadQueued = False
    sNext.freshness = s.freshness
    sNext.hasMatchingDownloadedApk = s.hasMatchingDownloadedApk
    sNext.errorState = ErrNone
    deriveUi[sNext]
}

pred installAndUpgradeToLatest[s, sNext: State] {
    s.isDebugBuild = True
    s.hasMatchingDownloadedApk = True
    s.downloadActive = False
    s.downloadQueued = False
    sNext.isDebugBuild = s.isDebugBuild
    sNext.hasToken = s.hasToken
    sNext.checking = False
    sNext.downloadActive = False
    sNext.downloadQueued = False
    sNext.freshness = FreshUpToDate
    sNext.hasMatchingDownloadedApk = False
    sNext.errorState = ErrNone
    deriveUi[sNext]
}

pred stutterRelease[s, sNext: State] {
    s.isDebugBuild = False
    sNext.isDebugBuild = False
    sNext.hasToken = s.hasToken
    sNext.checking = s.checking
    sNext.downloadActive = s.downloadActive
    sNext.downloadQueued = s.downloadQueued
    sNext.freshness = s.freshness
    sNext.hasMatchingDownloadedApk = s.hasMatchingDownloadedApk
    sNext.errorState = s.errorState
    deriveUi[sNext]
}

fact Traces {
    init[first]
    all s: State - last | let sNext = s.next |
        configureToken[s, sNext] or
        clearToken[s, sNext] or
        startCheck[s, sNext] or
        finishCheckUpdateAvailable[s, sNext] or
        finishCheckUpToDate[s, sNext] or
        finishCheckNoArtifact[s, sNext] or
        (some err: ErrorState | finishCheckError[s, sNext, err]) or
        (some q: Bool | enqueueDownload[s, sNext, q]) or
        startQueuedDownload[s, sNext] or
        finishDownloadSuccess[s, sNext] or
        (some err: ErrorState | finishDownloadFailure[s, sNext, err]) or
        cancelDownload[s, sNext] or
        installAndUpgradeToLatest[s, sNext] or
        stutterRelease[s, sNext]
}

// ============================================================================
// Safety Invariants
// ============================================================================

assert ReleaseBuildNeverShowsCard {
    all s: State | s.isDebugBuild = False implies (
        s.isCardVisible = False and
        s.cardState = CardHidden and
        s.primaryAction = ActNone
    )
}

assert NoTokenFailSafeNeverOffersDownload {
    all s: State | s.hasToken = False implies (
        s.primaryAction != ActDownload and
        (s.hasMatchingDownloadedApk = False implies s.primaryAction != ActInstall)
    )
}

assert ErrorStateNeverOffersDownloadOrInstall {
    all s: State | s.errorState != ErrNone implies (
        s.primaryAction != ActDownload and
        s.primaryAction != ActInstall
    )
}

assert DownloadOnlyWhenTokenAndUpdateAvailable {
    all s: State | s.primaryAction = ActDownload implies (
        s.isDebugBuild = True and
        s.hasToken = True and
        s.freshness = FreshUpdateAvailable and
        s.errorState = ErrNone and
        s.downloadActive = False and
        s.downloadQueued = False and
        s.checking = False
    )
}

assert InstallOnlyWhenMatchingApkExistsAndDebug {
    all s: State | s.primaryAction = ActInstall implies (
        s.isDebugBuild = True and
        s.hasMatchingDownloadedApk = True and
        s.errorState = ErrNone and
        s.downloadActive = False and
        s.downloadQueued = False and
        s.checking = False
    )
}

assert UpToDateNeverOffersDownloadOrInstall {
    all s: State | s.freshness = FreshUpToDate implies (
        s.primaryAction != ActDownload and
        s.primaryAction != ActInstall
    )
}

assert ActiveDownloadAlwaysShowsProgressAndCancel {
    all s: State | (s.isDebugBuild = True and (s.downloadActive = True or s.downloadQueued = True)) implies (
        s.showProgress = True and
        s.primaryAction = ActCancel
    )
}

check ReleaseBuildNeverShowsCard for 6 State
check NoTokenFailSafeNeverOffersDownload for 6 State
check ErrorStateNeverOffersDownloadOrInstall for 6 State
check DownloadOnlyWhenTokenAndUpdateAvailable for 6 State
check InstallOnlyWhenMatchingApkExistsAndDebug for 6 State
check UpToDateNeverOffersDownloadOrInstall for 6 State
check ActiveDownloadAlwaysShowsProgressAndCancel for 6 State

// ============================================================================
// Reachability (Non-Vacuity)
// ============================================================================

pred canConfigureTokenCheckDownloadAndInstall {
    some s0, s1, s2, s3: State |
        s0.cardState = CardTokenMissing and
        s1.primaryAction = ActDownload and
        s2.cardState = CardDownloading and
        s2.showProgress = True and
        s3.primaryAction = ActInstall and
        s3.cardState = CardReadyToInstall
}

pred canFailSafeOnNetworkErrorAndRecover {
    some s1, s2: State |
        s1.cardState = CardNetworkError and
        s1.primaryAction = ActCheck and
        s2.cardState = CardUpToDate
}

pred canCancelActiveDownload {
    some s1, s2: State |
        s2 = s1.next and
        s1.cardState = CardDownloading and
        s2.cardState = CardUpdateAvailable and
        s2.showProgress = False
}

run canConfigureTokenCheckDownloadAndInstall for 6 State
run canFailSafeOnNetworkErrorAndRecover for 6 State
run canCancelActiveDownload for 6 State
