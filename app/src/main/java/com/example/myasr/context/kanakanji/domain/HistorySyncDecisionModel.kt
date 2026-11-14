package com.example.myasr.context.kanakanji.domain

import com.example.myasr.shared.domain.dmn.DecisionTable
import com.example.myasr.shared.domain.dmn.DecisionTrace
import com.example.myasr.shared.domain.dmn.HitPolicy
import com.example.myasr.shared.domain.dmn.decisionTable

/**
 * BC-1 (Kana-Kanji & User History Sync) における
 * 定期バックグラウンド同期スケジューリングと設定画面同期カードの純粋ドメイン DMN モデル。
 */
object HistorySyncDecisionModel {

    enum class SyncBadgeTone {
        SUCCESS,
        INFO,
        WARNING,
        NEUTRAL
    }

    // ------------------------------------------------------------------------
    // 1. SyncScheduleDecisionTable
    // ------------------------------------------------------------------------

    enum class ScheduleAction {
        SCHEDULE_PERIODIC,
        CANCEL_NO_URI,
        CANCEL_PERMISSION_LOST,
        CANCEL_DISABLED
    }

    data class SyncScheduleInput(
        val autoSyncEnabled: Boolean,
        val hasStoredUri: Boolean,
        val hasValidPermission: Boolean,
        val intervalMinutes: Long
    )

    data class SyncScheduleDecision(
        val action: ScheduleAction,
        val shouldBeScheduled: Boolean,
        val effectiveIntervalMinutes: Long
    )

    const val MIN_PERIODIC_INTERVAL_MINUTES = 15L
    const val DEFAULT_PERIODIC_INTERVAL_MINUTES = 60L

    val syncScheduleDecisionTable: DecisionTable<SyncScheduleInput, SyncScheduleDecision> =
        decisionTable(
            name = "SyncScheduleDecisionTable",
            hitPolicy = HitPolicy.FIRST
        ) {
            rule(id = "SYNC_SCHED_NO_URI", description = "No sync URI configured") {
                `when` { !it.hasStoredUri }
                thenValue(
                    SyncScheduleDecision(
                        action = ScheduleAction.CANCEL_NO_URI,
                        shouldBeScheduled = false,
                        effectiveIntervalMinutes = 0L
                    )
                )
            }
            rule(id = "SYNC_SCHED_PERM_LOST", description = "SAF permission revoked or missing") {
                `when` { !it.hasValidPermission }
                thenValue(
                    SyncScheduleDecision(
                        action = ScheduleAction.CANCEL_PERMISSION_LOST,
                        shouldBeScheduled = false,
                        effectiveIntervalMinutes = 0L
                    )
                )
            }
            rule(id = "SYNC_SCHED_DISABLED", description = "Auto/periodic sync disabled by user") {
                `when` { !it.autoSyncEnabled }
                thenValue(
                    SyncScheduleDecision(
                        action = ScheduleAction.CANCEL_DISABLED,
                        shouldBeScheduled = false,
                        effectiveIntervalMinutes = 0L
                    )
                )
            }
            defaultValue(
                id = "SYNC_SCHED_PERIODIC",
                description = "Schedule OS periodic background sync",
                value = SyncScheduleDecision(
                    action = ScheduleAction.SCHEDULE_PERIODIC,
                    shouldBeScheduled = true,
                    effectiveIntervalMinutes = DEFAULT_PERIODIC_INTERVAL_MINUTES
                )
            )
        }

    fun evaluateSyncSchedule(input: SyncScheduleInput): SyncScheduleDecision {
        val base = syncScheduleDecisionTable.evaluate(input)
        return if (base.shouldBeScheduled) {
            base.copy(effectiveIntervalMinutes = input.intervalMinutes.coerceAtLeast(MIN_PERIODIC_INTERVAL_MINUTES))
        } else {
            base
        }
    }

    fun evaluateSyncScheduleWithTrace(input: SyncScheduleInput): DecisionTrace<SyncScheduleDecision> =
        syncScheduleDecisionTable.evaluateWithTrace(input)

    // ------------------------------------------------------------------------
    // 2. SettingsSyncCardDecisionTable
    // ------------------------------------------------------------------------

    enum class SyncCardState {
        SYNCING,
        ERROR,
        PERMISSION_LOST,
        CONNECTED,
        NOT_CONNECTED
    }

    data class SettingsSyncCardInput(
        val isSyncing: Boolean,
        val hasStoredUri: Boolean,
        val hasValidPermission: Boolean,
        val autoSyncEnabled: Boolean,
        val lastSyncTimestamp: Long,
        val lastSyncError: String?
    )

    data class SettingsSyncCardDecision(
        val cardState: SyncCardState,
        val badgeText: String,
        val primaryButtonLabel: String,
        val primaryButtonEnabled: Boolean,
        val showAutoSyncControls: Boolean,
        val showChangeAndUnlinkButtons: Boolean,
        val tone: SyncBadgeTone
    )

    val settingsSyncCardDecisionTable: DecisionTable<SettingsSyncCardInput, SettingsSyncCardDecision> =
        decisionTable(
            name = "SettingsSyncCardDecisionTable",
            hitPolicy = HitPolicy.FIRST
        ) {
            rule(id = "CARD_SYNCING", description = "Sync currently running") {
                `when` { it.isSyncing }
                then { input ->
                    SettingsSyncCardDecision(
                        cardState = SyncCardState.SYNCING,
                        badgeText = "同期中...",
                        primaryButtonLabel = "同期処理中...",
                        primaryButtonEnabled = false,
                        showAutoSyncControls = input.hasStoredUri,
                        showChangeAndUnlinkButtons = input.hasStoredUri,
                        tone = SyncBadgeTone.INFO
                    )
                }
            }
            rule(id = "CARD_ERROR", description = "Last sync attempt failed") {
                `when` { !it.lastSyncError.isNullOrBlank() }
                then { input ->
                    SettingsSyncCardDecision(
                        cardState = SyncCardState.ERROR,
                        badgeText = "同期エラー",
                        primaryButtonLabel = if (input.hasStoredUri && input.hasValidPermission) {
                            "再同期する"
                        } else {
                            "ファイルを選択して再連携"
                        },
                        primaryButtonEnabled = true,
                        showAutoSyncControls = input.hasStoredUri && input.hasValidPermission,
                        showChangeAndUnlinkButtons = input.hasStoredUri,
                        tone = SyncBadgeTone.WARNING
                    )
                }
            }
            rule(id = "CARD_PERM_LOST", description = "URI exists but SAF permission lost") {
                `when` { it.hasStoredUri && !it.hasValidPermission }
                thenValue(
                    SettingsSyncCardDecision(
                        cardState = SyncCardState.PERMISSION_LOST,
                        badgeText = "要再連携",
                        primaryButtonLabel = "ファイルを選択して権限を復旧",
                        primaryButtonEnabled = true,
                        showAutoSyncControls = false,
                        showChangeAndUnlinkButtons = true,
                        tone = SyncBadgeTone.WARNING
                    )
                )
            }
            rule(id = "CARD_CONNECTED", description = "Connected and ready for manual & periodic sync") {
                `when` { it.hasStoredUri && it.hasValidPermission }
                then { input ->
                    SettingsSyncCardDecision(
                        cardState = SyncCardState.CONNECTED,
                        badgeText = if (input.autoSyncEnabled) "定期同期 ON" else "手動のみ",
                        primaryButtonLabel = "今すぐ同期 (安全マージ)",
                        primaryButtonEnabled = true,
                        showAutoSyncControls = true,
                        showChangeAndUnlinkButtons = true,
                        tone = if (input.autoSyncEnabled) SyncBadgeTone.SUCCESS else SyncBadgeTone.INFO
                    )
                }
            }
            defaultValue(
                id = "CARD_NOT_CONNECTED",
                description = "No Google Drive sync file linked yet",
                value = SettingsSyncCardDecision(
                    cardState = SyncCardState.NOT_CONNECTED,
                    badgeText = "未連携",
                    primaryButtonLabel = "Google Drive と連携する",
                    primaryButtonEnabled = true,
                    showAutoSyncControls = false,
                    showChangeAndUnlinkButtons = false,
                    tone = SyncBadgeTone.NEUTRAL
                )
            )
        }

    fun evaluateSettingsSyncCard(input: SettingsSyncCardInput): SettingsSyncCardDecision =
        settingsSyncCardDecisionTable.evaluate(input)
}
