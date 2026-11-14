package com.example.myasr

import com.example.myasr.context.kanakanji.domain.HistorySyncDecisionModel
import com.example.myasr.context.kanakanji.domain.HistorySyncDecisionModel.ScheduleAction
import com.example.myasr.context.kanakanji.domain.HistorySyncDecisionModel.SettingsSyncCardInput
import com.example.myasr.context.kanakanji.domain.HistorySyncDecisionModel.SyncBadgeTone
import com.example.myasr.context.kanakanji.domain.HistorySyncDecisionModel.SyncCardState
import com.example.myasr.context.kanakanji.domain.HistorySyncDecisionModel.SyncScheduleInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistorySyncDecisionModelTest {

    @Test
    fun `schedule decision - schedules periodic job when enabled, URI present, and permission valid`() {
        val decision = HistorySyncDecisionModel.evaluateSyncSchedule(
            SyncScheduleInput(
                autoSyncEnabled = true,
                hasStoredUri = true,
                hasValidPermission = true,
                intervalMinutes = 60L
            )
        )
        assertEquals(ScheduleAction.SCHEDULE_PERIODIC, decision.action)
        assertTrue(decision.shouldBeScheduled)
        assertEquals(60L, decision.effectiveIntervalMinutes)
    }

    @Test
    fun `schedule decision - clamps interval below 15 minutes to Android JobScheduler minimum`() {
        val decision = HistorySyncDecisionModel.evaluateSyncSchedule(
            SyncScheduleInput(
                autoSyncEnabled = true,
                hasStoredUri = true,
                hasValidPermission = true,
                intervalMinutes = 5L
            )
        )
        assertTrue(decision.shouldBeScheduled)
        assertEquals(15L, decision.effectiveIntervalMinutes)
    }

    @Test
    fun `schedule decision - cancels periodic job when SAF permission lost`() {
        val decision = HistorySyncDecisionModel.evaluateSyncSchedule(
            SyncScheduleInput(
                autoSyncEnabled = true,
                hasStoredUri = true,
                hasValidPermission = false,
                intervalMinutes = 60L
            )
        )
        assertEquals(ScheduleAction.CANCEL_PERMISSION_LOST, decision.action)
        assertFalse(decision.shouldBeScheduled)
    }

    @Test
    fun `schedule decision - cancels periodic job when auto sync disabled`() {
        val decision = HistorySyncDecisionModel.evaluateSyncSchedule(
            SyncScheduleInput(
                autoSyncEnabled = false,
                hasStoredUri = true,
                hasValidPermission = true,
                intervalMinutes = 60L
            )
        )
        assertEquals(ScheduleAction.CANCEL_DISABLED, decision.action)
        assertFalse(decision.shouldBeScheduled)
    }

    @Test
    fun `settings card - permission lost hides auto sync controls and warns user`() {
        val decision = HistorySyncDecisionModel.evaluateSettingsSyncCard(
            SettingsSyncCardInput(
                isSyncing = false,
                hasStoredUri = true,
                hasValidPermission = false,
                autoSyncEnabled = true,
                lastSyncTimestamp = 0L,
                lastSyncError = null
            )
        )
        assertEquals(SyncCardState.PERMISSION_LOST, decision.cardState)
        assertEquals(SyncBadgeTone.WARNING, decision.tone)
        assertFalse(decision.showAutoSyncControls)
        assertTrue(decision.showChangeAndUnlinkButtons)
    }

    @Test
    fun `settings card - connected with auto sync shows SUCCESS badge and controls`() {
        val decision = HistorySyncDecisionModel.evaluateSettingsSyncCard(
            SettingsSyncCardInput(
                isSyncing = false,
                hasStoredUri = true,
                hasValidPermission = true,
                autoSyncEnabled = true,
                lastSyncTimestamp = 123456L,
                lastSyncError = null
            )
        )
        assertEquals(SyncCardState.CONNECTED, decision.cardState)
        assertEquals(SyncBadgeTone.SUCCESS, decision.tone)
        assertTrue(decision.showAutoSyncControls)
        assertTrue(decision.showChangeAndUnlinkButtons)
    }

    @Test
    fun `settings card - not connected hides change and unlink buttons`() {
        val decision = HistorySyncDecisionModel.evaluateSettingsSyncCard(
            SettingsSyncCardInput(
                isSyncing = false,
                hasStoredUri = false,
                hasValidPermission = false,
                autoSyncEnabled = true,
                lastSyncTimestamp = 0L,
                lastSyncError = null
            )
        )
        assertEquals(SyncCardState.NOT_CONNECTED, decision.cardState)
        assertEquals(SyncBadgeTone.NEUTRAL, decision.tone)
        assertFalse(decision.showAutoSyncControls)
        assertFalse(decision.showChangeAndUnlinkButtons)
    }
}
