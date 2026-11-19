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

    @Test
    fun testSafeClipboardHandlingDmnRules() {
        val judgement = com.example.myasr.context.clipboard.domain.ClipboardSuggestionJudgement

        // Active sensitive clip: masked on strip & sheet, never persisted, never learned
        val activeSensitive = judgement.judgeSafeHandlingWithTrace(
            com.example.myasr.context.clipboard.domain.ClipboardSuggestionJudgement.SafeClipboardHandlingInput(
                isSensitiveClip = true,
                isPasswordInputField = true,
                isVolatileExpired = false,
                isDismissedFromStrip = false,
                isDismissedFromSheet = false
            )
        )
        assertEquals("SAFE_CLIP_R4", activeSensitive.selectedRuleId)
        assertFalse(activeSensitive.result.allowDiskPersistence)
        assertFalse(activeSensitive.result.allowLearningAndPrediction)
        assertEquals(com.example.myasr.context.clipboard.domain.ClipboardSuggestionJudgement.ClipboardDisplayMode.MASKED_SENSITIVE, activeSensitive.result.stripDisplayMode)
        assertEquals(com.example.myasr.context.clipboard.domain.ClipboardSuggestionJudgement.ClipboardDisplayMode.MASKED_SENSITIVE, activeSensitive.result.sheetDisplayMode)

        // Strip dismissed sensitive clip: hidden on strip, still masked in sheet
        val stripDismissed = judgement.judgeSafeHandlingWithTrace(
            com.example.myasr.context.clipboard.domain.ClipboardSuggestionJudgement.SafeClipboardHandlingInput(
                isSensitiveClip = true,
                isPasswordInputField = false,
                isVolatileExpired = false,
                isDismissedFromStrip = true,
                isDismissedFromSheet = false
            )
        )
        assertEquals("SAFE_CLIP_R3", stripDismissed.selectedRuleId)
        assertFalse(stripDismissed.result.allowDiskPersistence)
        assertEquals(com.example.myasr.context.clipboard.domain.ClipboardSuggestionJudgement.ClipboardDisplayMode.HIDDEN, stripDismissed.result.stripDisplayMode)
        assertEquals(com.example.myasr.context.clipboard.domain.ClipboardSuggestionJudgement.ClipboardDisplayMode.MASKED_SENSITIVE, stripDismissed.result.sheetDisplayMode)

        // Expired sensitive clip: completely hidden
        val expiredSensitive = judgement.judgeSafeHandlingWithTrace(
            com.example.myasr.context.clipboard.domain.ClipboardSuggestionJudgement.SafeClipboardHandlingInput(
                isSensitiveClip = true,
                isPasswordInputField = false,
                isVolatileExpired = true,
                isDismissedFromStrip = false,
                isDismissedFromSheet = false
            )
        )
        assertEquals("SAFE_CLIP_R1", expiredSensitive.selectedRuleId)
        assertEquals(com.example.myasr.context.clipboard.domain.ClipboardSuggestionJudgement.ClipboardDisplayMode.HIDDEN, expiredSensitive.result.stripDisplayMode)
        assertEquals(com.example.myasr.context.clipboard.domain.ClipboardSuggestionJudgement.ClipboardDisplayMode.HIDDEN, expiredSensitive.result.sheetDisplayMode)

        // Non-sensitive clip in password field: no learning
        val passwordFieldRegular = judgement.judgeSafeHandlingWithTrace(
            com.example.myasr.context.clipboard.domain.ClipboardSuggestionJudgement.SafeClipboardHandlingInput(
                isSensitiveClip = false,
                isPasswordInputField = true,
                isVolatileExpired = false,
                isDismissedFromStrip = false,
                isDismissedFromSheet = false
            )
        )
        assertEquals("SAFE_CLIP_R5", passwordFieldRegular.selectedRuleId)
        assertTrue(passwordFieldRegular.result.allowDiskPersistence)
        assertFalse(passwordFieldRegular.result.allowLearningAndPrediction)
        assertEquals(com.example.myasr.context.clipboard.domain.ClipboardSuggestionJudgement.ClipboardDisplayMode.PLAIN_TEXT, passwordFieldRegular.result.stripDisplayMode)
    }
}
