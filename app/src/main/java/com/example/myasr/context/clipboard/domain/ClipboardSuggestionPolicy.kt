package com.example.myasr.context.clipboard.domain

import com.example.myasr.context.clipboard.infrastructure.ClipboardHistoryRepository

/**
 * Holistic state machine governing when a fresh (<60s) clipboard suggestion chip
 * (`clipboard_recent`) is allowed to appear on `CandidateStripView`.
 *
 * Core principles:
 * 1. Show proactively at idle entry (`onStartInputView`) before the user starts typing.
 * 2. Never intrude during an active typing/dictation session (Kana flick, QWERTY, Swipe,
 *    Voice, Number/Symbol, or post-commit next-phrase predictions).
 * 3. If a brand-new clip is copied while the keyboard is already open, only show it when
 *    the IME is completely idle and past the typing cooldown.
 */
class ClipboardSuggestionPolicy {

    companion object {
        const val TYPING_COOLDOWN_MS = 1500L
    }

    var isInputViewActive: Boolean = false
        private set

    var isTypingSessionActive: Boolean = false
        private set

    private var lastTypingActionUptimeMs: Long = 0L

    fun onStartInputView() {
        isInputViewActive = true
        isTypingSessionActive = false
    }

    fun onFinishInputView() {
        isInputViewActive = false
    }

    /**
     * Marks that the user has begun or continued an active typing, editing, or voice session.
     * Once called, fresh clipboard chips will not be shown in the candidate strip until
     * a new input view session starts or a brand-new clip arrives after the cooldown.
     */
    fun onUserTypingOrVoiceAction(nowUptimeMs: Long) {
        isTypingSessionActive = true
        lastTypingActionUptimeMs = nowUptimeMs
    }

    /**
     * Called when a genuinely new or re-copied clip is discovered by [ClipboardHistoryRepository].
     * Re-enables suggestion eligibility only if the user has not typed within [TYPING_COOLDOWN_MS].
     */
    fun onNewClipDiscovered(nowUptimeMs: Long) {
        if (lastTypingActionUptimeMs == 0L || (nowUptimeMs - lastTypingActionUptimeMs) >= TYPING_COOLDOWN_MS) {
            isTypingSessionActive = false
        }
    }

    fun shouldShowSuggestion(
        isVoiceIdle: Boolean,
        isClipboardHistoryShowing: Boolean,
        isKeyboardComposing: Boolean,
        isStreamingComposing: Boolean,
        hasActiveNonClipboardCandidates: Boolean
    ): Boolean {
        return isInputViewActive &&
            isVoiceIdle &&
            !isClipboardHistoryShowing &&
            !isKeyboardComposing &&
            !isStreamingComposing &&
            !isTypingSessionActive &&
            !hasActiveNonClipboardCandidates
    }
}
