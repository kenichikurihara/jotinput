package com.example.myasr

import com.example.myasr.context.clipboard.domain.ClipboardSuggestionPolicy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ClipboardSuggestionPolicy] ensuring fresh clipboard chips
 * appear only at idle entry or idle new-copy moments and never intrude during typing.
 */
class ClipboardSuggestionPolicyTest {

    private fun ClipboardSuggestionPolicy.shouldShowWhenIdle(): Boolean {
        return shouldShowSuggestion(
            isVoiceIdle = true,
            isClipboardHistoryShowing = false,
            isKeyboardComposing = false,
            isStreamingComposing = false,
            hasActiveNonClipboardCandidates = false
        )
    }

    @Test
    fun testBeforeInputViewStarts_DoesNotShow() {
        val policy = ClipboardSuggestionPolicy()
        assertFalse(policy.shouldShowWhenIdle())
    }

    @Test
    fun testOnStartInputView_ShowsWhenIdle() {
        val policy = ClipboardSuggestionPolicy()
        policy.onStartInputView()
        assertTrue(policy.shouldShowWhenIdle())
    }

    @Test
    fun testAfterUserStartsTyping_SuppressedEvenAfterComposingCommits() {
        val policy = ClipboardSuggestionPolicy()
        policy.onStartInputView()
        assertTrue(policy.shouldShowWhenIdle())

        // User starts typing (e.g. Kana flick, QWERTY key, Space, Enter, etc.)
        policy.onUserTypingOrVoiceAction(nowUptimeMs = 10_000L)

        // Even when composing text is later committed (isKeyboardComposing == false)
        // and no candidates are showing, clipboard chip must NOT intrude during typing session.
        assertFalse(policy.shouldShowWhenIdle())
    }

    @Test
    fun testNewInputViewSession_ResetsTypingSuppression() {
        val policy = ClipboardSuggestionPolicy()
        policy.onStartInputView()
        policy.onUserTypingOrVoiceAction(nowUptimeMs = 10_000L)
        assertFalse(policy.shouldShowWhenIdle())

        policy.onFinishInputView()
        assertFalse(policy.shouldShowWhenIdle())

        // User opens keyboard in a new field / after switching back from another app
        policy.onStartInputView()
        assertTrue(policy.shouldShowWhenIdle())
    }

    @Test
    fun testNewClipDiscovered_WithinTypingCooldown_RemainsSuppressed() {
        val policy = ClipboardSuggestionPolicy()
        policy.onStartInputView()
        policy.onUserTypingOrVoiceAction(nowUptimeMs = 10_000L)

        // New clip event arrives 500ms after last keystroke (< 1500ms cooldown)
        policy.onNewClipDiscovered(nowUptimeMs = 10_500L)
        assertFalse(policy.shouldShowWhenIdle())
    }

    @Test
    fun testNewClipDiscovered_AfterTypingCooldown_AllowedOnlyWhenCompletelyIdle() {
        val policy = ClipboardSuggestionPolicy()
        policy.onStartInputView()
        policy.onUserTypingOrVoiceAction(nowUptimeMs = 10_000L)

        // New clip discovered 2000ms after last keystroke (>= 1500ms cooldown)
        policy.onNewClipDiscovered(nowUptimeMs = 12_000L)
        assertTrue(policy.shouldShowWhenIdle())

        // Still blocked if non-clipboard candidates (e.g., English spell / next-phrase) are showing
        assertFalse(
            policy.shouldShowSuggestion(
                isVoiceIdle = true,
                isClipboardHistoryShowing = false,
                isKeyboardComposing = false,
                isStreamingComposing = false,
                hasActiveNonClipboardCandidates = true
            )
        )
    }

    @Test
    fun testGuardFlags_BlockSuggestion() {
        val policy = ClipboardSuggestionPolicy()
        policy.onStartInputView()

        assertFalse(
            policy.shouldShowSuggestion(
                isVoiceIdle = false,
                isClipboardHistoryShowing = false,
                isKeyboardComposing = false,
                isStreamingComposing = false,
                hasActiveNonClipboardCandidates = false
            )
        )
        assertFalse(
            policy.shouldShowSuggestion(
                isVoiceIdle = true,
                isClipboardHistoryShowing = true,
                isKeyboardComposing = false,
                isStreamingComposing = false,
                hasActiveNonClipboardCandidates = false
            )
        )
        assertFalse(
            policy.shouldShowSuggestion(
                isVoiceIdle = true,
                isClipboardHistoryShowing = false,
                isKeyboardComposing = true,
                isStreamingComposing = false,
                hasActiveNonClipboardCandidates = false
            )
        )
        assertFalse(
            policy.shouldShowSuggestion(
                isVoiceIdle = true,
                isClipboardHistoryShowing = false,
                isKeyboardComposing = false,
                isStreamingComposing = true,
                hasActiveNonClipboardCandidates = false
            )
        )
    }

    @Test
    fun testClipboardSuggestionJudgementDmnTrace() {
        val blocked = com.example.myasr.context.clipboard.domain.ClipboardSuggestionJudgement.judgeEligibilityWithTrace(
            com.example.myasr.context.clipboard.domain.ClipboardSuggestionJudgement.SuggestionEligibilityInput(
                isInputViewActive = true,
                isVoiceIdle = true,
                isClipboardHistoryShowing = false,
                isKeyboardComposing = true,
                isStreamingComposing = false,
                isTypingSessionActive = false,
                hasActiveNonClipboardCandidates = false
            )
        )
        assertFalse(blocked.result.decision)
        assertEquals("CLIP_R4", blocked.selectedRuleId)
        assertEquals("keyboard_composing_active", blocked.result.reason)
    }
}
