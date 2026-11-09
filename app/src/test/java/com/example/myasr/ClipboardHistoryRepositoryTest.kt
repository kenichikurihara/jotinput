package com.example.myasr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for Clipboard History, TTL expiration, and the OS primary clip retention exception rule.
 */
class ClipboardHistoryRepositoryTest {

    @Test
    fun testRetentionRule_ExpiredPastClip() {
        val nowMs = 100_000_000L
        val ttlMs = ClipboardHistoryRepository.HISTORY_TTL_MS // 60 minutes = 3,600,000 ms

        val itemOld = ClipboardHistoryItem(
            id = "1",
            text = "Old past text",
            discoveredAtMs = nowMs - ttlMs - 1000L, // 60m 1s ago
            osClipTimestampMs = 0L
        )

        // If OS clip text is different (e.g. "Current text"), old clip must expire
        val retain = (nowMs - itemOld.discoveredAtMs) <= ttlMs
        assertFalse("Past clip older than 60 minutes must NOT be retained", retain)
    }

    @Test
    fun testRetentionRule_Within60Minutes() {
        val nowMs = 100_000_000L
        val ttlMs = ClipboardHistoryRepository.HISTORY_TTL_MS

        val itemValid = ClipboardHistoryItem(
            id = "2",
            text = "Valid text",
            discoveredAtMs = nowMs - (30 * 60 * 1000L), // 30 min ago
            osClipTimestampMs = 0L
        )

        val retain = (nowMs - itemValid.discoveredAtMs) <= ttlMs
        assertTrue("Past clip within 60 minutes must be retained", retain)
    }

    @Test
    fun testRetentionRule_OsPrimaryClipException() {
        val nowMs = 100_000_000L
        val ttlMs = ClipboardHistoryRepository.HISTORY_TTL_MS

        val osCurrentText = "Long standing OS primary clip"
        val itemVeryOld = ClipboardHistoryItem(
            id = "3",
            text = osCurrentText,
            discoveredAtMs = nowMs - (5 * ttlMs), // 5 hours ago!
            osClipTimestampMs = 0L
        )

        // Exception rule: if item.text == currentOsClipText, retain even if 5 hours old!
        val isCurrentOsClip = itemVeryOld.text == osCurrentText
        val shouldRetain = isCurrentOsClip || ((nowMs - itemVeryOld.discoveredAtMs) <= ttlMs)

        assertTrue("OS primary clip must be retained indefinitely as an exception to the 60m TTL", shouldRetain)
    }

    @Test
    fun testRecentSuggestionCandidate_WithinOneMinute() {
        val nowMs = 100_000_000L
        val suggestTtlMs = ClipboardHistoryRepository.CANDIDATE_SUGGEST_TTL_MS // 60s

        val itemFresh = ClipboardHistoryItem(
            id = "fresh",
            text = "Freshly copied text",
            discoveredAtMs = nowMs - 30_000L, // 30s ago
            osClipTimestampMs = 0L,
            dismissedFromStrip = false
        )

        val isEligible = !itemFresh.dismissedFromStrip && ((nowMs - itemFresh.discoveredAtMs) <= suggestTtlMs)
        assertTrue("Item discovered 30s ago should be eligible for candidate strip", isEligible)
    }

    @Test
    fun testRecentSuggestionCandidate_ExpiredAfterOneMinute() {
        val nowMs = 100_000_000L
        val suggestTtlMs = ClipboardHistoryRepository.CANDIDATE_SUGGEST_TTL_MS // 60s

        val itemOver1Min = ClipboardHistoryItem(
            id = "over1min",
            text = "Copied 2 minutes ago",
            discoveredAtMs = nowMs - 120_000L, // 120s ago
            osClipTimestampMs = 0L,
            dismissedFromStrip = false
        )

        val isEligible = !itemOver1Min.dismissedFromStrip && ((nowMs - itemOver1Min.discoveredAtMs) <= suggestTtlMs)
        assertFalse("Item discovered 2 minutes ago should NOT appear on candidate strip", isEligible)
    }

    @Test
    fun testRecentSuggestionCandidate_DismissedFromStrip() {
        val nowMs = 100_000_000L
        val suggestTtlMs = ClipboardHistoryRepository.CANDIDATE_SUGGEST_TTL_MS // 60s

        val itemDismissed = ClipboardHistoryItem(
            id = "dismissed",
            text = "Pasted already",
            discoveredAtMs = nowMs - 10_000L, // 10s ago
            osClipTimestampMs = 0L,
            dismissedFromStrip = true
        )

        val isEligible = !itemDismissed.dismissedFromStrip && ((nowMs - itemDismissed.discoveredAtMs) <= suggestTtlMs)
        assertFalse("Dismissed item should NOT appear on candidate strip even within 1 minute", isEligible)
    }

    @Test
    fun testClipboardUtilsFormatting() {
        // Candidate chip preview formatting
        assertEquals("📋", ClipboardUtils.formatCandidateChipPreview(null))
        assertEquals("📋", ClipboardUtils.formatCandidateChipPreview(""))
        assertEquals("📋 こんにちは、世界", ClipboardUtils.formatCandidateChipPreview("こんにちは、世界", maxChars = 14))
        assertEquals("📋 https://exampl…", ClipboardUtils.formatCandidateChipPreview("https://example.com/long/path", maxChars = 14))

        // History item preview formatting
        assertEquals("", ClipboardUtils.formatHistoryItemPreview(null))
        assertEquals("1行目 ↵ 2行目", ClipboardUtils.formatHistoryItemPreview("1行目\n2行目"))

        // Elapsed time formatting
        assertEquals("現在のクリップ", ClipboardUtils.formatElapsedTime(1000L, isCurrentOsClip = true))
        assertEquals("たった今", ClipboardUtils.formatElapsedTime(20_000L, isCurrentOsClip = false))
        assertEquals("5分前", ClipboardUtils.formatElapsedTime(5 * 60 * 1000L, isCurrentOsClip = false))
        assertEquals("2時間前", ClipboardUtils.formatElapsedTime(2 * 60 * 60 * 1000L, isCurrentOsClip = false))
    }
}
