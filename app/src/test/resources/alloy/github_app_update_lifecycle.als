module jotinput/github_app_update_lifecycle

open util/ordering[State]
open util/boolean

/**
 * Formal Alloy 6 Specification for Tokenless Multi-Channel GitHub App Updater
 * (`RELEASE` / `REMOTE_HEAD`) and Background Downloader (BC-6: AppUpdateDecisionModel).
 *
 * Verifies:
 * 1. BusyStateLocksChannelSelector: Channel selector is locked while checking, queued, or downloading.
 * 2. ChannelNeverChangesWhileBusy: Selected update channel never mutates while checking, queued, or downloading.
 * 3. ErrorStateNeverOffersDownloadOrInstall: Rate-limit, integrity, network, or download errors fail safe
 *    and never offer download or install.
 * 4. DownloadOnlyWhenUpdateAvailableAndNotBusy: Download CTA is shown only when freshness is
 *    UPDATE_AVAILABLE and no error/check/download is active.
 * 5. InstallOnlyWhenChannelMatchingApkExists: Install CTA is shown only when a verified APK matching
 *    the currently selected channel exists on disk without errors (strict cross-channel isolation).
 * 6. UpToDateNeverOffersDownloadOrInstall: Up-to-date state never offers download or install.
 * 7. ActiveDownloadAlwaysShowsProgressAndCancel: Active/queued downloads always show progress and cancel.
 */

abstract sig UpdateChannel {}
one sig ChannelRelease, ChannelRemoteHead extends UpdateChannel {}

abstract sig FreshnessStatus {}
one sig FreshNone, FreshUpdateAvailable, FreshUpToDate, FreshNoArtifact extends FreshnessStatus {}

abstract sig ErrorState {}
one sig ErrNone, ErrNetworkError, ErrRateLimited, ErrDownloadFailed, ErrIntegrityFailed extends ErrorState {}

abstract sig CardState {}
one sig
    CardDownloading,
    CardQueued,
    CardChecking,
    CardReadyToInstall,
    CardError,
    CardUpdateAvailable,
    CardUpToDate,
    CardNoArtifact,
    CardIdle
extends CardState {}

abstract sig PrimaryAction {}
one sig
    ActCancel,
    ActCheck,
    ActInstall,
    ActDownload
extends PrimaryAction {}

sig State {
    selectedChannel: one UpdateChannel,
    checking: one Bool,
    downloadActive: one Bool,
    downloadQueued: one Bool,
    freshness: one FreshnessStatus,
    hasDownloadedApk: one Bool,
    downloadedApkChannel: one UpdateChannel,
    errorState: one ErrorState,
    // Derived UI projections
    cardState: one CardState,
    primaryAction: one PrimaryAction,
    showProgress: one Bool,
    channelSelectorEnabled: one Bool
}

pred hasMatchingDownloadedApk[s: State] {
    s.hasDownloadedApk = True
    s.downloadedApkChannel = s.selectedChannel
}

pred deriveUi[s: State] {
    (s.downloadActive = True) implies {
        s.cardState = CardDownloading
        s.primaryAction = ActCancel
        s.showProgress = True
        s.channelSelectorEnabled = False
    } else (s.downloadQueued = True) implies {
        s.cardState = CardQueued
        s.primaryAction = ActCancel
        s.showProgress = True
        s.channelSelectorEnabled = False
    } else (s.checking = True) implies {
        s.cardState = CardChecking
        s.primaryAction = ActCheck
        s.showProgress = False
        s.channelSelectorEnabled = False
    } else (hasMatchingDownloadedApk[s] and s.errorState = ErrNone and (s.freshness = FreshNone or s.freshness = FreshUpdateAvailable)) implies {
        s.cardState = CardReadyToInstall
        s.primaryAction = ActInstall
        s.showProgress = False
        s.channelSelectorEnabled = True
    } else (s.errorState != ErrNone) implies {
        s.cardState = CardError
        s.primaryAction = ActCheck
        s.showProgress = False
        s.channelSelectorEnabled = True
    } else (s.freshness = FreshUpdateAvailable) implies {
        s.cardState = CardUpdateAvailable
        s.primaryAction = ActDownload
        s.showProgress = False
        s.channelSelectorEnabled = True
    } else (s.freshness = FreshUpToDate) implies {
        s.cardState = CardUpToDate
        s.primaryAction = ActCheck
        s.showProgress = False
        s.channelSelectorEnabled = True
    } else (s.freshness = FreshNoArtifact) implies {
        s.cardState = CardNoArtifact
        s.primaryAction = ActCheck
        s.showProgress = False
        s.channelSelectorEnabled = True
    } else {
        s.cardState = CardIdle
        s.primaryAction = ActCheck
        s.showProgress = False
        s.channelSelectorEnabled = True
    }
}

pred init[s: State] {
    s.checking = False
    s.downloadActive = False
    s.downloadQueued = False
    s.freshness = FreshNone
    s.hasDownloadedApk = False
    s.errorState = ErrNone
    deriveUi[s]
}

pred switchChannel[s, sNext: State, target: UpdateChannel] {
    s.channelSelectorEnabled = True
    target != s.selectedChannel
    sNext.selectedChannel = target
    sNext.checking = False
    sNext.downloadActive = False
    sNext.downloadQueued = False
    sNext.freshness = FreshNone
    sNext.hasDownloadedApk = s.hasDownloadedApk
    sNext.downloadedApkChannel = s.downloadedApkChannel
    sNext.errorState = ErrNone
    deriveUi[sNext]
}

pred startCheck[s, sNext: State] {
    s.checking = False
    s.downloadActive = False
    s.downloadQueued = False
    sNext.selectedChannel = s.selectedChannel
    sNext.checking = True
    sNext.downloadActive = False
    sNext.downloadQueued = False
    sNext.freshness = s.freshness
    sNext.hasDownloadedApk = s.hasDownloadedApk
    sNext.downloadedApkChannel = s.downloadedApkChannel
    sNext.errorState = ErrNone
    deriveUi[sNext]
}

pred finishCheckUpdateAvailable[s, sNext: State] {
    s.checking = True
    sNext.selectedChannel = s.selectedChannel
    sNext.checking = False
    sNext.downloadActive = s.downloadActive
    sNext.downloadQueued = s.downloadQueued
    sNext.freshness = FreshUpdateAvailable
    sNext.hasDownloadedApk = s.hasDownloadedApk
    sNext.downloadedApkChannel = s.downloadedApkChannel
    sNext.errorState = ErrNone
    deriveUi[sNext]
}

pred finishCheckUpToDate[s, sNext: State] {
    s.checking = True
    sNext.selectedChannel = s.selectedChannel
    sNext.checking = False
    sNext.downloadActive = s.downloadActive
    sNext.downloadQueued = s.downloadQueued
    sNext.freshness = FreshUpToDate
    (s.downloadedApkChannel = s.selectedChannel) implies {
        sNext.hasDownloadedApk = False
        sNext.downloadedApkChannel = s.downloadedApkChannel
    } else {
        sNext.hasDownloadedApk = s.hasDownloadedApk
        sNext.downloadedApkChannel = s.downloadedApkChannel
    }
    sNext.errorState = ErrNone
    deriveUi[sNext]
}

pred finishCheckNoArtifact[s, sNext: State] {
    s.checking = True
    sNext.selectedChannel = s.selectedChannel
    sNext.checking = False
    sNext.downloadActive = s.downloadActive
    sNext.downloadQueued = s.downloadQueued
    sNext.freshness = FreshNoArtifact
    (s.downloadedApkChannel = s.selectedChannel) implies {
        sNext.hasDownloadedApk = False
        sNext.downloadedApkChannel = s.downloadedApkChannel
    } else {
        sNext.hasDownloadedApk = s.hasDownloadedApk
        sNext.downloadedApkChannel = s.downloadedApkChannel
    }
    sNext.errorState = ErrNone
    deriveUi[sNext]
}

pred finishCheckError[s, sNext: State, err: ErrorState] {
    s.checking = True
    (err = ErrNetworkError or err = ErrRateLimited)
    sNext.selectedChannel = s.selectedChannel
    sNext.checking = False
    sNext.downloadActive = s.downloadActive
    sNext.downloadQueued = s.downloadQueued
    sNext.freshness = s.freshness
    sNext.hasDownloadedApk = s.hasDownloadedApk
    sNext.downloadedApkChannel = s.downloadedApkChannel
    sNext.errorState = err
    deriveUi[sNext]
}

pred enqueueDownload[s, sNext: State, queued: Bool] {
    s.checking = False
    s.downloadActive = False
    s.downloadQueued = False
    s.freshness = FreshUpdateAvailable
    s.errorState = ErrNone
    sNext.selectedChannel = s.selectedChannel
    sNext.checking = False
    (queued = True) implies {
        sNext.downloadActive = False
        sNext.downloadQueued = True
    } else {
        sNext.downloadActive = True
        sNext.downloadQueued = False
    }
    sNext.freshness = s.freshness
    sNext.hasDownloadedApk = s.hasDownloadedApk
    sNext.downloadedApkChannel = s.downloadedApkChannel
    sNext.errorState = ErrNone
    deriveUi[sNext]
}

pred startQueuedDownload[s, sNext: State] {
    s.downloadQueued = True
    sNext.selectedChannel = s.selectedChannel
    sNext.checking = s.checking
    sNext.downloadActive = True
    sNext.downloadQueued = False
    sNext.freshness = s.freshness
    sNext.hasDownloadedApk = s.hasDownloadedApk
    sNext.downloadedApkChannel = s.downloadedApkChannel
    sNext.errorState = ErrNone
    deriveUi[sNext]
}

pred finishDownloadSuccess[s, sNext: State] {
    s.downloadActive = True
    sNext.selectedChannel = s.selectedChannel
    sNext.checking = False
    sNext.downloadActive = False
    sNext.downloadQueued = False
    sNext.freshness = FreshUpdateAvailable
    sNext.hasDownloadedApk = True
    sNext.downloadedApkChannel = s.selectedChannel
    sNext.errorState = ErrNone
    deriveUi[sNext]
}

pred finishDownloadFailure[s, sNext: State, err: ErrorState] {
    s.downloadActive = True
    (err = ErrNetworkError or err = ErrRateLimited or err = ErrDownloadFailed or err = ErrIntegrityFailed)
    sNext.selectedChannel = s.selectedChannel
    sNext.checking = False
    sNext.downloadActive = False
    sNext.downloadQueued = False
    sNext.freshness = s.freshness
    sNext.hasDownloadedApk = s.hasDownloadedApk
    sNext.downloadedApkChannel = s.downloadedApkChannel
    sNext.errorState = err
    deriveUi[sNext]
}

pred cancelDownload[s, sNext: State] {
    (s.downloadActive = True or s.downloadQueued = True)
    sNext.selectedChannel = s.selectedChannel
    sNext.checking = s.checking
    sNext.downloadActive = False
    sNext.downloadQueued = False
    sNext.freshness = s.freshness
    sNext.hasDownloadedApk = s.hasDownloadedApk
    sNext.downloadedApkChannel = s.downloadedApkChannel
    sNext.errorState = ErrNone
    deriveUi[sNext]
}

pred installAndUpgradeToLatest[s, sNext: State] {
    hasMatchingDownloadedApk[s]
    s.errorState = ErrNone
    s.downloadActive = False
    s.downloadQueued = False
    s.checking = False
    sNext.selectedChannel = s.selectedChannel
    sNext.checking = False
    sNext.downloadActive = False
    sNext.downloadQueued = False
    sNext.freshness = FreshUpToDate
    sNext.hasDownloadedApk = False
    sNext.downloadedApkChannel = s.downloadedApkChannel
    sNext.errorState = ErrNone
    deriveUi[sNext]
}

fact Traces {
    init[first]
    all s: State - last | let sNext = s.next |
        (some ch: UpdateChannel | switchChannel[s, sNext, ch]) or
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
        installAndUpgradeToLatest[s, sNext]
}

// ============================================================================
// Safety Invariants
// ============================================================================

assert BusyStateLocksChannelSelector {
    all s: State | (s.checking = True or s.downloadActive = True or s.downloadQueued = True) implies
        s.channelSelectorEnabled = False
}

assert ChannelNeverChangesWhileBusy {
    all s: State - last | (s.checking = True or s.downloadActive = True or s.downloadQueued = True) implies
        s.next.selectedChannel = s.selectedChannel
}

assert ErrorStateNeverOffersDownloadOrInstall {
    all s: State | s.errorState != ErrNone implies (
        s.primaryAction != ActDownload and
        s.primaryAction != ActInstall
    )
}

assert DownloadOnlyWhenUpdateAvailableAndNotBusy {
    all s: State | s.primaryAction = ActDownload implies (
        s.freshness = FreshUpdateAvailable and
        s.errorState = ErrNone and
        s.downloadActive = False and
        s.downloadQueued = False and
        s.checking = False
    )
}

assert InstallOnlyWhenChannelMatchingApkExists {
    all s: State | s.primaryAction = ActInstall implies (
        s.hasDownloadedApk = True and
        s.downloadedApkChannel = s.selectedChannel and
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
    all s: State | (s.downloadActive = True or s.downloadQueued = True) implies (
        s.showProgress = True and
        s.primaryAction = ActCancel
    )
}

check BusyStateLocksChannelSelector for 6 State
check ChannelNeverChangesWhileBusy for 6 State
check ErrorStateNeverOffersDownloadOrInstall for 6 State
check DownloadOnlyWhenUpdateAvailableAndNotBusy for 6 State
check InstallOnlyWhenChannelMatchingApkExists for 6 State
check UpToDateNeverOffersDownloadOrInstall for 6 State
check ActiveDownloadAlwaysShowsProgressAndCancel for 6 State

// ============================================================================
// Reachability (Non-Vacuity)
// ============================================================================

pred canCheckDownloadAndInstallReleaseTokenless {
    some s0, s1, s2, s3, s4, s5: State |
        s0.selectedChannel = ChannelRelease and
        s0.cardState = CardIdle and
        s1.cardState = CardChecking and
        s2.primaryAction = ActDownload and
        s3.cardState = CardDownloading and
        s3.showProgress = True and
        s4.primaryAction = ActInstall and
        s4.cardState = CardReadyToInstall and
        s5.cardState = CardUpToDate
}

pred canSwitchToRemoteHeadAndInstallTokenless {
    some s0, s1, s2, s3, s4: State |
        s0.selectedChannel = ChannelRelease and
        s1.selectedChannel = ChannelRemoteHead and
        s2.selectedChannel = ChannelRemoteHead and
        s2.primaryAction = ActDownload and
        s3.cardState = CardDownloading and
        s4.selectedChannel = ChannelRemoteHead and
        s4.primaryAction = ActInstall
}

pred canSwitchChannelWithoutCrossChannelInstallLeak {
    some s1, s2: State |
        s2 = s1.next and
        s1.selectedChannel = ChannelRemoteHead and
        s1.primaryAction = ActInstall and
        s2.selectedChannel = ChannelRelease and
        s2.hasDownloadedApk = True and
        s2.primaryAction != ActInstall
}

pred canFailSafeOnRateLimitOrIntegrityErrorAndRecover {
    some s1, s2: State |
        (s1.errorState = ErrRateLimited or s1.errorState = ErrIntegrityFailed) and
        s1.cardState = CardError and
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

run canCheckDownloadAndInstallReleaseTokenless for 6 State
run canSwitchToRemoteHeadAndInstallTokenless for 6 State
run canSwitchChannelWithoutCrossChannelInstallLeak for 6 State
run canFailSafeOnRateLimitOrIntegrityErrorAndRecover for 6 State
run canCancelActiveDownload for 6 State
