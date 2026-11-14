module jotinput/history_sync_settings

open util/ordering[State]
open util/boolean

/**
 * Formal Alloy 6 Specification for Google Drive History Sync Settings & Periodic Background Sync.
 */

abstract sig ScheduleAction {}
one sig
    SchedulePeriodic,
    CancelDisabled,
    CancelNoUri,
    CancelPermissionLost
extends ScheduleAction {}

abstract sig SyncCardState {}
one sig
    CardSyncing,
    CardError,
    CardPermissionLost,
    CardConnected,
    CardNotConnected
extends SyncCardState {}

sig State {
    autoSyncEnabled: one Bool,
    hasStoredUri: one Bool,
    hasValidPermission: one Bool,
    isSyncing: one Bool,
    hasLastError: one Bool,
    hasBaseSnapshot: one Bool,

    // Derived DMN outputs & JobScheduler state
    scheduleAction: one ScheduleAction,
    jobScheduled: one Bool,
    cardState: one SyncCardState
}

pred deriveDmn[s: State] {
    // 1. SyncScheduleDecisionTable (FIRST Hit Policy)
    (s.hasStoredUri = False) implies (s.scheduleAction = CancelNoUri) else
    (s.hasValidPermission = False) implies (s.scheduleAction = CancelPermissionLost) else
    (s.autoSyncEnabled = False) implies (s.scheduleAction = CancelDisabled) else
    (s.scheduleAction = SchedulePeriodic)

    (s.scheduleAction = SchedulePeriodic) implies (s.jobScheduled = True) else (s.jobScheduled = False)

    // 2. SettingsSyncCardDecisionTable (FIRST Hit Policy)
    (s.isSyncing = True) implies (s.cardState = CardSyncing) else
    (s.hasLastError = True) implies (s.cardState = CardError) else
    (s.hasStoredUri = True and s.hasValidPermission = False) implies (s.cardState = CardPermissionLost) else
    (s.hasStoredUri = True and s.hasValidPermission = True) implies (s.cardState = CardConnected) else
    (s.cardState = CardNotConnected)
}

pred initUnlinked[s: State] {
    s.autoSyncEnabled = True
    s.hasStoredUri = False
    s.hasValidPermission = False
    s.isSyncing = False
    s.hasLastError = False
    s.hasBaseSnapshot = False
    deriveDmn[s]
}

pred linkFileAndSync[s, sNext: State] {
    sNext.autoSyncEnabled = s.autoSyncEnabled
    sNext.hasStoredUri = True
    sNext.hasValidPermission = True
    sNext.isSyncing = False
    sNext.hasLastError = False
    sNext.hasBaseSnapshot = True
    deriveDmn[sNext]
}

pred switchSyncFile[s, sNext: State] {
    s.hasStoredUri = True
    sNext.autoSyncEnabled = s.autoSyncEnabled
    sNext.hasStoredUri = True
    sNext.hasValidPermission = True
    sNext.isSyncing = False
    sNext.hasLastError = False
    // Switching target file MUST clear old base snapshot before merging
    sNext.hasBaseSnapshot = False
    deriveDmn[sNext]
}

pred toggleAutoSync[s, sNext: State, enabled: Bool] {
    sNext.autoSyncEnabled = enabled
    sNext.hasStoredUri = s.hasStoredUri
    sNext.hasValidPermission = s.hasValidPermission
    sNext.isSyncing = s.isSyncing
    sNext.hasLastError = s.hasLastError
    sNext.hasBaseSnapshot = s.hasBaseSnapshot
    deriveDmn[sNext]
}

pred unlinkSync[s, sNext: State] {
    sNext.autoSyncEnabled = s.autoSyncEnabled
    sNext.hasStoredUri = False
    sNext.hasValidPermission = False
    sNext.isSyncing = False
    sNext.hasLastError = False
    sNext.hasBaseSnapshot = False
    deriveDmn[sNext]
}

// ============================================================================
// Safety Assertions (Must have ZERO counterexamples)
// ============================================================================

assert PeriodicJobOnlyWhenEnabledAndPermitted {
    all s: State |
        (deriveDmn[s] and s.jobScheduled = True)
        implies (s.autoSyncEnabled = True and s.hasStoredUri = True and s.hasValidPermission = True)
}

assert PermissionLostCancelsJobAndShowsWarning {
    all s: State |
        (deriveDmn[s] and s.isSyncing = False and s.hasLastError = False
         and s.hasStoredUri = True and s.hasValidPermission = False)
        implies (s.jobScheduled = False and s.cardState = CardPermissionLost)
}

assert ChangingSyncUriResetsSnapshot {
    all s, sNext: State |
        switchSyncFile[s, sNext] implies sNext.hasBaseSnapshot = False
}

check PeriodicJobOnlyWhenEnabledAndPermitted for 5
check PermissionLostCancelsJobAndShowsWarning for 5
check ChangingSyncUriResetsSnapshot for 4

// ============================================================================
// Reachability Predicates (Non-vacuity)
// ============================================================================

pred canLinkSwitchToggleAndUnlink {
    initUnlinked[first]
    some s1, s2, s3, s4: State |
        s1 = first.next and s2 = s1.next and s3 = s2.next and s4 = s3.next and
        linkFileAndSync[first, s1] and
        s1.jobScheduled = True and
        switchSyncFile[s1, s2] and
        s2.hasBaseSnapshot = False and
        toggleAutoSync[s2, s3, False] and
        s3.jobScheduled = False and
        unlinkSync[s3, s4] and
        s4.cardState = CardNotConnected
}

run canLinkSwitchToggleAndUnlink for 5 State
