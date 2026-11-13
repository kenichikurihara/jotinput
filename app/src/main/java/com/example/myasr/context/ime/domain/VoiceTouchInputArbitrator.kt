package com.example.myasr.context.ime.domain

import com.example.myasr.context.ime.presentation.CandidateStripView
import com.example.myasr.context.kanakanji.domain.JapaneseKanaUtils

import android.view.inputmethod.InputConnection

/**
 * Abstraction over Android's InputConnection to enable deterministic JVM unit testing
 * of all multimodal voice + touch corner cases alongside production IME usage.
 */
interface EditorBridge {
    fun beginBatchEdit(): Boolean
    fun endBatchEdit(): Boolean
    fun setComposingText(text: String, newCursorPosition: Int): Boolean
    fun commitText(text: String, newCursorPosition: Int): Boolean
    fun finishComposingText(): Boolean
    fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean
    fun getTextBeforeCursor(length: Int, flags: Int): String
    fun getSelectedText(flags: Int): String?
}

class InputConnectionEditorBridge(private val provider: () -> InputConnection?) : EditorBridge {
    override fun beginBatchEdit(): Boolean = provider()?.beginBatchEdit() ?: false
    override fun endBatchEdit(): Boolean = provider()?.endBatchEdit() ?: false
    override fun setComposingText(text: String, newCursorPosition: Int): Boolean =
        provider()?.setComposingText(text, newCursorPosition) ?: false
    override fun commitText(text: String, newCursorPosition: Int): Boolean =
        provider()?.commitText(text, newCursorPosition) ?: false
    override fun finishComposingText(): Boolean =
        provider()?.finishComposingText() ?: false
    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean =
        provider()?.deleteSurroundingText(beforeLength, afterLength) ?: false
    override fun getTextBeforeCursor(length: Int, flags: Int): String =
        provider()?.getTextBeforeCursor(length, flags)?.toString() ?: ""
    override fun getSelectedText(flags: Int): String? =
        provider()?.getSelectedText(flags)?.toString()
}

/**
 * Multimodal state coordinator and arbitrator between Voice Input (both Continuous Streaming Mode
 * and VAD Pause-Chunk Mode) and Touch Keyboard Input (12-key Flick, QWERTY, Symbols, Numpad,
 * Candidate selection, Confirm/Convert, Backspace, Modifier, Undo, Cursor navigation, Clipboard).
 *
 * Enforces the Fundamental Multimodal Invariant:
 * 1. Single-Composing Ownership: Keyboard `composingText` and Streaming Voice `streamingLastComposingText`
 *    never overwrite each other on the single Android InputConnection composing span.
 * 2. Chronological Ordering:
 *    ComposingSession(S) < SpeechCapturedDuring(S) < CommitsAfterSpeech(S) < ComposingSession(S + 1)
 * 3. Instant Confirmation: Pressing Confirm (`確定` / Enter) while streaming voice text is displayed
 *    (or while speech is unflushed/decoding) immediately confirms the voice text and resets the
 *    utterance buffer without emitting a premature newline or re-decoding already-confirmed audio.
 * 4. Undo Safety: Any voice commitment immediately invalidates stale keyboard Undo state so `↶`
 *    never corrupts newly spoken text.
 */
class VoiceTouchInputArbitrator {

    enum class DeleteOutcome {
        DELETED_FROM_KEYBOARD_COMPOSING_NOW_EMPTY,
        DELETED_FROM_KEYBOARD_COMPOSING_STILL_ACTIVE,
        DELETED_FROM_STREAMING_COMPOSING,
        DELETED_ENTIRE_SWIPE_WORD,
        DELETED_SURROUNDING_TEXT
    }

    enum class EnterOutcome {
        COMMITTED_KEYBOARD_COMPOSING,
        COMMITTED_STREAMING_COMPOSING,
        FLUSHED_OR_CONFIRMED_PENDING_SPEECH,
        PERFORM_EDITOR_RETURN_ACTION
    }

    enum class SpaceOutcome {
        COMMITTED_KEYBOARD_AND_SPACE,
        COMMITTED_STREAMING_AND_SPACE,
        COMMITTED_IDLE_SPACE,
        CONVERTED_DOUBLE_SPACE_TO_PERIOD
    }

    companion object {
        const val DOUBLE_SPACE_TIMEOUT_MS = 1000L
    }

    data class InFlightDecode(
        val decodeId: Long,
        val capturedSessionId: Long,
        val committedAfterThisDecode: StringBuilder = StringBuilder()
    )

    // Keyboard composing state (Pre-edit reading)
    val composingText = StringBuilder()
    var activeComposingSessionId: Long = 0L
        private set
    private var composingSessionCounter: Long = 0L

    // Streaming voice input state
    var streamingCommittedPrefix: String = ""
        private set
    var streamingLastComposingText: String = ""
        private set
    val streamingInferenceHistory = mutableListOf<String>()
    @Volatile
    var streamingGenerationId: Long = 0L
        private set

    // Undo state (for Gboard-style ↶ Undo button in Flick mode)
    var lastCommittedReading: String = ""
        private set
    var lastCommittedSurface: String = ""
        private set

    // Holistic English Auto-Space & Auto-Period state
    var pendingAutoSpaceAfterWord: Boolean = false
        private set
    var lastAutoPeriodEligibleSpaceTimeMs: Long = 0L
        private set

    // Bigram phrase tracking for next-phrase continuation prediction
    var previousCommittedPhrase: String = ""

    // Queue for post-typing voice chunks delivered while keyboard composing of the SAME session is still open
    val pendingVoiceQueue = mutableListOf<String>()

    // Active background ASR decodes and any text committed after each chunk was captured
    private val inFlightLock = Any()
    private var decodeCounter: Long = 0L
    private val inFlightDecodes = mutableListOf<InFlightDecode>()

    // Timestamp of last IME-initiated composing edit to distinguish external cursor taps in onUpdateSelection
    var lastImeComposingEditTimeMs: Long = 0L
        private set
    // Timestamp of last IME-initiated direct commit/delete to avoid clearing auto-assist on async onUpdateSelection
    var lastImeDirectEditTimeMs: Long = 0L
        private set

    fun isKeyboardComposing(): Boolean = composingText.isNotEmpty()

    fun isStreamingComposing(): Boolean = streamingLastComposingText.isNotEmpty()

    fun canUndo(): Boolean =
        lastCommittedReading.isNotEmpty() &&
            lastCommittedSurface.isNotEmpty() &&
            composingText.isEmpty() &&
            streamingLastComposingText.isEmpty()

    fun hasInFlightDecodes(): Boolean = synchronized(inFlightLock) {
        inFlightDecodes.isNotEmpty()
    }

    fun hasVoiceComposingForUi(isSpeaking: Boolean, hasUnflushedSpeech: Boolean): Boolean {
        return streamingLastComposingText.isNotEmpty() ||
            isSpeaking ||
            hasUnflushedSpeech ||
            hasInFlightDecodes()
    }

    fun clearUndoState() {
        lastCommittedReading = ""
        lastCommittedSurface = ""
        lastCommittedWasSwipe = false
    }

    private fun clearAutoAssistState() {
        pendingAutoSpaceAfterWord = false
        lastAutoPeriodEligibleSpaceTimeMs = 0L
    }

    fun resetAll() {
        composingText.clear()
        activeComposingSessionId = 0L
        streamingGenerationId++
        streamingCommittedPrefix = ""
        streamingLastComposingText = ""
        streamingInferenceHistory.clear()
        clearUndoState()
        clearAutoAssistState()
        previousCommittedPhrase = ""
        pendingVoiceQueue.clear()
        synchronized(inFlightLock) {
            inFlightDecodes.clear()
        }
    }

    fun resetStreamingSessionState() {
        streamingGenerationId++
        streamingCommittedPrefix = ""
        streamingLastComposingText = ""
        streamingInferenceHistory.clear()
    }

    /**
     * Immediately commits any active streaming voice composing text and resets the streaming
     * utterance state so subsequent streaming ticks start fresh and never duplicate confirmed speech.
     */
    fun commitActiveStreamingComposing(
        editor: EditorBridge?,
        onResetAudioUtterance: () -> Unit = {},
        formatFn: (String) -> String = { it }
    ): String? {
        streamingGenerationId++
        streamingInferenceHistory.clear()
        onResetAudioUtterance()

        if (streamingLastComposingText.isEmpty()) {
            streamingCommittedPrefix = ""
            return null
        }

        val finalized = formatFn(streamingLastComposingText)
        streamingCommittedPrefix = ""
        streamingLastComposingText = ""
        clearUndoState()

        if (finalized.isNotEmpty()) {
            editor?.commitText(finalized, 1)
            recordDirectCommitForInFlightDecodes(finalized)
            previousCommittedPhrase = finalized.takeLast(6)
        } else {
            editor?.finishComposingText()
        }
        return finalized
    }

    /**
     * Commits active keyboard composing text as raw reading (e.g., on Enter, mode switch, cursor move, or mic start).
     */
    fun commitKeyboardComposing(editor: EditorBridge?): String? {
        if (composingText.isEmpty()) return null
        val reading = composingText.toString()
        commitKeyboardSurface(editor, reading, reading)
        return reading
    }

    /**
     * Commits a resolved surface string for the current keyboard composing session.
     */
    fun commitKeyboardSurface(editor: EditorBridge?, reading: String, committedSurface: String) {
        val committedSessionId = activeComposingSessionId
        lastCommittedReading = reading
        lastCommittedSurface = committedSurface
        editor?.commitText(committedSurface, 1)
        recordSessionCommitForInFlightDecodes(committedSessionId, committedSurface)
        previousCommittedPhrase = committedSurface
        composingText.clear()
        activeComposingSessionId = 0L
        flushPendingVoice(editor)
    }

    /**
     * Handles a Kana character typed in 12-key Flick mode.
     * Auto-commits any active streaming voice composing text first, and flushes any unflushed
     * pre-typing VAD speech before opening a new keyboard composing session.
     */
    fun onTypeKana(
        editor: EditorBridge?,
        text: String,
        onFlushPreTypingSpeech: () -> Unit = {},
        onResetStreamingUtterance: () -> Unit = {}
    ) {
        if (streamingLastComposingText.isNotEmpty()) {
            commitActiveStreamingComposing(editor, onResetStreamingUtterance)
        }
        if (composingText.isEmpty()) {
            onFlushPreTypingSpeech()
            activeComposingSessionId = ++composingSessionCounter
        }
        clearUndoState()
        clearAutoAssistState()
        composingText.append(text)
        lastImeComposingEditTimeMs = System.currentTimeMillis()
        editor?.setComposingText(composingText.toString(), 1)
    }

    private fun isAutoSpacePrecedingChar(ch: Char): Boolean {
        return Character.isLetterOrDigit(ch) ||
            ch == '.' || ch == ',' || ch == '!' || ch == '?' ||
            ch == ';' || ch == ':' || ch == ')' || ch == ']' ||
            ch == '}' || ch == '%' || ch == '"' || ch == '\''
    }

    private fun isAutoPeriodEligiblePrecedingChar(ch: Char): Boolean {
        return Character.isLetterOrDigit(ch) ||
            ch == ')' || ch == ']' || ch == '}' ||
            ch == '"' || ch == '\'' || ch == '%'
    }

    private fun isSentenceEndPunctuation(text: String): Boolean {
        val trimmed = text.trimEnd()
        if (trimmed.isEmpty()) return false
        val last = trimmed.last()
        return last == '.' || last == '!' || last == '?'
    }

    /**
     * Handles a direct-commit character (QWERTY, Symbols, or Numpad mode).
     * If the preceding input was a swipe-typed word or an accepted English candidate
     * (`pendingAutoSpaceAfterWord`), automatically inserts a space (" ") before typing
     * alphanumeric or word characters, while allowing punctuation / symbols
     * (e.g. '.', ',', '!', '?', etc.) to attach immediately without a space.
     */
    fun onTypeDirectChar(
        editor: EditorBridge?,
        text: String,
        autoSpaceEnabled: Boolean = true,
        bypassAutoAssist: Boolean = false,
        onFlushPreTypingSpeech: () -> Unit = {},
        onResetStreamingUtterance: () -> Unit = {}
    ) {
        if (streamingLastComposingText.isNotEmpty()) {
            commitActiveStreamingComposing(editor, onResetStreamingUtterance)
        }
        if (composingText.isNotEmpty()) {
            commitKeyboardComposing(editor)
        }
        onFlushPreTypingSpeech()

        val textBefore = editor?.getTextBeforeCursor(1, 0) ?: ""
        val alreadyEndsWithWhitespace = textBefore.lastOrNull()?.isWhitespace() == true
        val shouldPrependSpace = autoSpaceEnabled &&
            !bypassAutoAssist &&
            (pendingAutoSpaceAfterWord || lastCommittedWasSwipe) &&
            !alreadyEndsWithWhitespace &&
            text.isNotEmpty() &&
            Character.isLetterOrDigit(text.codePointAt(0))
        val textToCommit = if (shouldPrependSpace) " $text" else text
        clearUndoState()
        clearAutoAssistState()
        lastImeDirectEditTimeMs = System.currentTimeMillis()
        editor?.commitText(textToCommit, 1)
        recordDirectCommitForInFlightDecodes(textToCommit)
        flushPendingVoice(editor)
    }

    var lastCommittedWasSwipe: Boolean = false
        private set

    /**
     * Handles commit of a swipe-typed English word.
     * Holistically inspects the preceding text (letters, digits, sentence/clause punctuation)
     * to prepend a space (" ") when appropriate, without adding a trailing space prematurely.
     * Records the word in recent commit history and provides full-word deletion/replace capabilities.
     */
    fun onCommitSwipeWord(
        editor: EditorBridge?,
        word: String,
        autoSpaceEnabled: Boolean = true,
        bypassAutoAssist: Boolean = false,
        onFlushPreTypingSpeech: () -> Unit = {},
        onResetStreamingUtterance: () -> Unit = {}
    ) {
        if (streamingLastComposingText.isNotEmpty()) {
            commitActiveStreamingComposing(editor, onResetStreamingUtterance)
        }
        if (composingText.isNotEmpty()) {
            commitKeyboardComposing(editor)
        }
        onFlushPreTypingSpeech()

        val textBefore = editor?.getTextBeforeCursor(2, 0) ?: ""
        val shouldPrependSpace = if (autoSpaceEnabled && !bypassAutoAssist) {
            if (textBefore.isNotEmpty()) {
                val lastChar = textBefore.last()
                !lastChar.isWhitespace() && isAutoSpacePrecedingChar(lastChar)
            } else {
                lastCommittedWasSwipe
            }
        } else {
            false
        }

        val textToCommit = if (shouldPrependSpace) " $word" else word
        clearUndoState()
        lastAutoPeriodEligibleSpaceTimeMs = 0L
        lastImeDirectEditTimeMs = System.currentTimeMillis()

        editor?.commitText(textToCommit, 1)
        recordDirectCommitForInFlightDecodes(textToCommit)
        previousCommittedPhrase = word
        lastCommittedSurface = word
        lastCommittedWasSwipe = true
        pendingAutoSpaceAfterWord = autoSpaceEnabled && !bypassAutoAssist && word.isNotEmpty() && !isSentenceEndPunctuation(word)
        flushPendingVoice(editor)
    }

    /**
     * Handles selection of an English candidate chip (typo correction, capitalization, GEC, etc.)
     * in QWERTY mode while keeping Arbitrator state and pending auto-space synchronized.
     */
    fun onEnglishCandidateSelected(
        editor: EditorBridge?,
        replaceLength: Int,
        candidateSurface: String,
        autoSpaceEnabled: Boolean = true,
        bypassAutoAssist: Boolean = false,
        onFlushPreTypingSpeech: () -> Unit = {},
        onResetStreamingUtterance: () -> Unit = {}
    ) {
        if (streamingLastComposingText.isNotEmpty()) {
            commitActiveStreamingComposing(editor, onResetStreamingUtterance)
        }
        if (composingText.isNotEmpty()) {
            commitKeyboardComposing(editor)
        }
        onFlushPreTypingSpeech()

        clearUndoState()
        lastAutoPeriodEligibleSpaceTimeMs = 0L
        lastImeDirectEditTimeMs = System.currentTimeMillis()

        editor?.beginBatchEdit()
        try {
            if (replaceLength > 0) {
                editor?.deleteSurroundingText(replaceLength, 0)
                recordDeleteCharsForInFlightDecodes(replaceLength)
            }
            editor?.commitText(candidateSurface, 1)
            recordDirectCommitForInFlightDecodes(candidateSurface)
        } finally {
            editor?.endBatchEdit()
        }

        previousCommittedPhrase = candidateSurface
        pendingAutoSpaceAfterWord = autoSpaceEnabled &&
            !bypassAutoAssist &&
            candidateSurface.isNotEmpty() &&
            !isSentenceEndPunctuation(candidateSurface)
        flushPendingVoice(editor)
    }

    /**
     * Handles directional modifier key (`゛゜ / 大⇔小`).
     * Modifies active keyboard composing text, or if streaming voice composing text is active,
     * modifies its trailing character and commits the streaming utterance.
     * @return true if keyboard `composingText` was modified (caller should refresh conversion candidates).
     */
    fun onModifierPressed(
        editor: EditorBridge?,
        direction: JapaneseKanaUtils.FlickDirection,
        onResetStreamingUtterance: () -> Unit = {}
    ): Boolean {
        if (composingText.isNotEmpty()) {
            val lastChar = composingText.last()
            val modified = JapaneseKanaUtils.applyDirectionalModifier(lastChar, direction)
            composingText.setCharAt(composingText.length - 1, modified)
            lastImeComposingEditTimeMs = System.currentTimeMillis()
            editor?.setComposingText(composingText.toString(), 1)
            return true
        }
        if (streamingLastComposingText.isNotEmpty()) {
            val lastChar = streamingLastComposingText.last()
            val modified = JapaneseKanaUtils.applyDirectionalModifier(lastChar, direction)
            if (modified != lastChar) {
                streamingLastComposingText = streamingLastComposingText.dropLast(1) + modified
                commitActiveStreamingComposing(editor, onResetStreamingUtterance)
            }
        }
        return false
    }

    /**
     * Handles Backspace / Delete (`⌫`) across keyboard composing, streaming voice composing,
     * and committed surrounding text.
     */
    fun onDeletePressed(
        editor: EditorBridge?,
        onDiscardUnflushedSpeech: () -> Unit = {},
        onResetStreamingUtterance: () -> Unit = {}
    ): DeleteOutcome {
        clearAutoAssistState()
        if (composingText.isNotEmpty()) {
            clearUndoState()
            previousCommittedPhrase = ""
            composingText.deleteCharAt(composingText.length - 1)
            lastImeComposingEditTimeMs = System.currentTimeMillis()
            return if (composingText.isEmpty()) {
                activeComposingSessionId = 0L
                editor?.setComposingText("", 0)
                flushPendingVoice(editor)
                DeleteOutcome.DELETED_FROM_KEYBOARD_COMPOSING_NOW_EMPTY
            } else {
                editor?.setComposingText(composingText.toString(), 1)
                DeleteOutcome.DELETED_FROM_KEYBOARD_COMPOSING_STILL_ACTIVE
            }
        }

        if (streamingLastComposingText.isNotEmpty()) {
            clearUndoState()
            previousCommittedPhrase = ""
            val shortened = streamingLastComposingText.dropLast(1)
            streamingGenerationId++
            streamingCommittedPrefix = ""
            streamingLastComposingText = ""
            streamingInferenceHistory.clear()
            onResetStreamingUtterance()

            if (shortened.isNotEmpty()) {
                editor?.commitText(shortened, 1)
                recordDirectCommitForInFlightDecodes(shortened)
                previousCommittedPhrase = shortened.takeLast(6)
            } else {
                editor?.setComposingText("", 0)
            }
            return DeleteOutcome.DELETED_FROM_STREAMING_COMPOSING
        }

        // Neither keyboard nor streaming composing is active: discard any mid-speech mumble
        onDiscardUnflushedSpeech()
        pendingVoiceQueue.clear()

        // If the immediate preceding edit was a swipe-typed word, delete the entire word!
        if (lastCommittedWasSwipe && lastCommittedSurface.isNotEmpty()) {
            val wordLen = lastCommittedSurface.length
            clearUndoState()
            previousCommittedPhrase = ""
            editor?.deleteSurroundingText(wordLen, 0)
            recordDeleteCharsForInFlightDecodes(wordLen)
            return DeleteOutcome.DELETED_ENTIRE_SWIPE_WORD
        }

        clearUndoState()
        previousCommittedPhrase = ""

        val selected = editor?.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            editor.commitText("", 1)
            clearInFlightCommittedTracking()
        } else {
            editor?.deleteSurroundingText(1, 0)
            recordDeleteCharsForInFlightDecodes(1)
        }
        return DeleteOutcome.DELETED_SURROUNDING_TEXT
    }

    /**
     * Handles Enter / Confirm (`確定` / `↵`) button press.
     */
    fun onEnterPressed(
        editor: EditorBridge?,
        hasUnflushedSpeech: Boolean,
        onFlushPreTypingSpeech: () -> Unit = {},
        onResetStreamingUtterance: () -> Unit = {},
        formatStreamingFn: (String) -> String = { it }
    ): EnterOutcome {
        clearAutoAssistState()
        if (composingText.isNotEmpty()) {
            if (hasUnflushedSpeech) {
                onFlushPreTypingSpeech()
            }
            commitKeyboardComposing(editor)
            return EnterOutcome.COMMITTED_KEYBOARD_COMPOSING
        }

        if (streamingLastComposingText.isNotEmpty()) {
            commitActiveStreamingComposing(editor, onResetStreamingUtterance, formatStreamingFn)
            return EnterOutcome.COMMITTED_STREAMING_COMPOSING
        }

        if (hasUnflushedSpeech || hasInFlightDecodes()) {
            if (hasUnflushedSpeech) {
                onFlushPreTypingSpeech()
            }
            return EnterOutcome.FLUSHED_OR_CONFIRMED_PENDING_SPEECH
        }

        clearUndoState()
        previousCommittedPhrase = ""
        flushPendingVoice(editor)
        return EnterOutcome.PERFORM_EDITOR_RETURN_ACTION
    }

    /**
     * Handles Space (`␣`) button press. Always commits text and enters a space character,
     * or converts a rapid double-space into `". "` (Gboard-style Auto-Period) when eligible.
     */
    fun onSpacePressed(
        editor: EditorBridge?,
        spaceCharWhenIdle: String,
        hasUnflushedSpeech: Boolean = false,
        autoPeriodEnabled: Boolean = false,
        bypassAutoAssist: Boolean = false,
        nowMs: Long = System.currentTimeMillis(),
        onFlushPreTypingSpeech: () -> Unit = {},
        onResetStreamingUtterance: () -> Unit = {},
        formatStreamingFn: (String) -> String = { it }
    ): SpaceOutcome {
        pendingAutoSpaceAfterWord = false

        if (composingText.isNotEmpty()) {
            val reading = composingText.toString()
            if (hasUnflushedSpeech) {
                onFlushPreTypingSpeech()
            }
            // Commit composing reading as-is without Kana-Kanji conversion, then enter space
            commitKeyboardSurface(editor, reading, reading)
            clearUndoState()
            previousCommittedPhrase = ""
            lastImeDirectEditTimeMs = System.currentTimeMillis()
            editor?.commitText(spaceCharWhenIdle, 1)
            recordDirectCommitForInFlightDecodes(spaceCharWhenIdle)
            flushPendingVoice(editor)
            val prevChar = reading.lastOrNull()
            lastAutoPeriodEligibleSpaceTimeMs = if (
                autoPeriodEnabled && !bypassAutoAssist && spaceCharWhenIdle == " " &&
                prevChar != null && isAutoPeriodEligiblePrecedingChar(prevChar)
            ) nowMs else 0L
            return SpaceOutcome.COMMITTED_KEYBOARD_AND_SPACE
        }

        if (streamingLastComposingText.isNotEmpty()) {
            val finalized = commitActiveStreamingComposing(editor, onResetStreamingUtterance, formatStreamingFn)
            clearUndoState()
            previousCommittedPhrase = ""
            lastImeDirectEditTimeMs = System.currentTimeMillis()
            editor?.commitText(spaceCharWhenIdle, 1)
            recordDirectCommitForInFlightDecodes(spaceCharWhenIdle)
            flushPendingVoice(editor)
            val prevChar = finalized?.lastOrNull()
            lastAutoPeriodEligibleSpaceTimeMs = if (
                autoPeriodEnabled && !bypassAutoAssist && spaceCharWhenIdle == " " &&
                prevChar != null && isAutoPeriodEligiblePrecedingChar(prevChar)
            ) nowMs else 0L
            return SpaceOutcome.COMMITTED_STREAMING_AND_SPACE
        }

        // Check Gboard-style Double-Space Auto-Period when idle
        if (!hasUnflushedSpeech &&
            autoPeriodEnabled &&
            !bypassAutoAssist &&
            spaceCharWhenIdle == " " &&
            lastAutoPeriodEligibleSpaceTimeMs > 0L &&
            (nowMs - lastAutoPeriodEligibleSpaceTimeMs) in 0L..DOUBLE_SPACE_TIMEOUT_MS
        ) {
            val before = editor?.getTextBeforeCursor(2, 0)
            val editorEligible = before == null ||
                (before.length >= 2 && before.last() == ' ' && isAutoPeriodEligiblePrecedingChar(before[before.length - 2]))
            if (editorEligible) {
                clearUndoState()
                lastAutoPeriodEligibleSpaceTimeMs = 0L
                previousCommittedPhrase = "."
                lastImeDirectEditTimeMs = System.currentTimeMillis()
                editor?.beginBatchEdit()
                try {
                    editor?.deleteSurroundingText(1, 0)
                    editor?.commitText(". ", 1)
                } finally {
                    editor?.endBatchEdit()
                }
                recordDeleteCharsForInFlightDecodes(1)
                recordDirectCommitForInFlightDecodes(". ")
                flushPendingVoice(editor)
                return SpaceOutcome.CONVERTED_DOUBLE_SPACE_TO_PERIOD
            }
        }

        if (hasUnflushedSpeech) {
            onFlushPreTypingSpeech()
        }
        val prevCharBeforeSpace = editor?.getTextBeforeCursor(1, 0)?.lastOrNull()
        clearUndoState()
        previousCommittedPhrase = ""
        lastImeDirectEditTimeMs = System.currentTimeMillis()
        editor?.commitText(spaceCharWhenIdle, 1)
        recordDirectCommitForInFlightDecodes(spaceCharWhenIdle)
        flushPendingVoice(editor)
        lastAutoPeriodEligibleSpaceTimeMs = if (
            autoPeriodEnabled && !bypassAutoAssist && spaceCharWhenIdle == " " &&
            prevCharBeforeSpace != null && isAutoPeriodEligiblePrecedingChar(prevCharBeforeSpace)
        ) nowMs else 0L
        return SpaceOutcome.COMMITTED_IDLE_SPACE
    }

    /**
     * Handles candidate chip selection in CandidateStripView (either a Kana-Kanji conversion candidate,
     * a next-phrase continuation prediction, or a multi-granularity partial prefix candidate).
     * @return true if partial prefix commit occurred (caller should refresh candidates for remaining composingText).
     */
    fun onCandidateSelected(
        editor: EditorBridge?,
        candidateSurface: String,
        isPrediction: Boolean,
        hasUnflushedSpeech: Boolean = false,
        candidateReading: String = "",
        onFlushPreTypingSpeech: () -> Unit = {},
        onResetStreamingUtterance: () -> Unit = {},
        formatStreamingFn: (String) -> String = { it }
    ): Boolean {
        if (streamingLastComposingText.isNotEmpty() && composingText.isEmpty()) {
            commitActiveStreamingComposing(editor, onResetStreamingUtterance, formatStreamingFn)
        }
        if (hasUnflushedSpeech) {
            onFlushPreTypingSpeech()
        }

        if (isPrediction) {
            clearUndoState()
            composingText.clear()
            activeComposingSessionId = 0L
            editor?.commitText(candidateSurface, 1)
            recordDirectCommitForInFlightDecodes(candidateSurface)
            previousCommittedPhrase = candidateSurface
            flushPendingVoice(editor)
            return false
        } else if (candidateSurface.isNotEmpty() && composingText.isEmpty() && lastCommittedWasSwipe) {
            // User selected an alternative candidate for the immediately preceding swipe word!
            // Replace the previous swipe word with the new candidate word
            val prevLen = lastCommittedSurface.length
            editor?.deleteSurroundingText(prevLen, 0)
            recordDeleteCharsForInFlightDecodes(prevLen)

            editor?.commitText(candidateSurface, 1)
            recordDirectCommitForInFlightDecodes(candidateSurface)
            previousCommittedPhrase = candidateSurface
            lastCommittedSurface = candidateSurface
            flushPendingVoice(editor)
            return false
        } else {
            val fullReading = composingText.toString()
            val normFull = JapaneseKanaUtils.normalizeReading(fullReading)
            val normCand = JapaneseKanaUtils.normalizeReading(candidateReading)

            // Partial Prefix Commit:
            // When candidateReading is a proper prefix of the active composing reading (k < n)
            if (normCand.isNotEmpty() && normCand.length < normFull.length && normFull.startsWith(normCand)) {
                val prefixLen = candidateReading.length
                val committedSessionId = activeComposingSessionId
                lastCommittedReading = candidateReading
                lastCommittedSurface = candidateSurface
                editor?.commitText(candidateSurface, 1)
                recordSessionCommitForInFlightDecodes(committedSessionId, candidateSurface)
                previousCommittedPhrase = candidateSurface

                // Keep remainder in composingText
                composingText.delete(0, prefixLen)
                activeComposingSessionId = ++composingSessionCounter
                lastImeComposingEditTimeMs = System.currentTimeMillis()
                editor?.setComposingText(composingText.toString(), 1)
                return true
            } else {
                commitKeyboardSurface(editor, fullReading, candidateSurface)
                return false
            }
        }
    }

    /**
     * Handles Undo (`↶`) button press in 12-key Flick mode.
     * Only active when the most recent edit was a keyboard commit and no subsequent voice text
     * has been committed or started streaming.
     */
    fun onUndoPressed(editor: EditorBridge?): Boolean {
        if (!canUndo()) return false
        val surfaceLen = lastCommittedSurface.length
        val readingToRestore = lastCommittedReading

        editor?.deleteSurroundingText(surfaceLen, 0)
        recordDeleteCharsForInFlightDecodes(surfaceLen)

        activeComposingSessionId = ++composingSessionCounter
        composingText.append(readingToRestore)
        clearUndoState()
        previousCommittedPhrase = ""
        lastImeComposingEditTimeMs = System.currentTimeMillis()
        editor?.setComposingText(composingText.toString(), 1)
        return true
    }

    /**
     * Handles Cursor Left/Right (`◀`/`▶`), Mode Switch (`あa`/`?123`), or Clipboard Paste preparation.
     */
    fun onBeforeNavigationOrAction(
        editor: EditorBridge?,
        isCursorMove: Boolean,
        hasUnflushedSpeech: Boolean = false,
        onFlushPreTypingSpeech: () -> Unit = {},
        onResetStreamingUtterance: () -> Unit = {},
        formatStreamingFn: (String) -> String = { it }
    ) {
        if (streamingLastComposingText.isNotEmpty()) {
            commitActiveStreamingComposing(editor, onResetStreamingUtterance, formatStreamingFn)
        }
        if (composingText.isNotEmpty()) {
            commitKeyboardComposing(editor)
        }
        if (hasUnflushedSpeech) {
            onFlushPreTypingSpeech()
        }
        flushPendingVoice(editor)
        clearUndoState()
        clearAutoAssistState()
        previousCommittedPhrase = ""
        if (isCursorMove) {
            clearInFlightCommittedTracking()
        }
    }

    /**
     * Handles Clipboard paste (`📋`).
     */
    fun onClipboardPaste(
        editor: EditorBridge?,
        clipText: String,
        hasUnflushedSpeech: Boolean = false,
        onFlushPreTypingSpeech: () -> Unit = {},
        onResetStreamingUtterance: () -> Unit = {},
        formatStreamingFn: (String) -> String = { it }
    ) {
        if (clipText.isEmpty()) return
        onBeforeNavigationOrAction(
            editor = editor,
            isCursorMove = false,
            hasUnflushedSpeech = hasUnflushedSpeech,
            onFlushPreTypingSpeech = onFlushPreTypingSpeech,
            onResetStreamingUtterance = onResetStreamingUtterance,
            formatStreamingFn = formatStreamingFn
        )
        editor?.commitText(clipText, 1)
        recordDirectCommitForInFlightDecodes(clipText)
        previousCommittedPhrase = clipText.takeLast(6)
    }

    /**
     * Applies an incremental streaming ASR update on the Main thread.
     * Strictly no-ops if the generation ID is stale (e.g., user confirmed or typed while ASR was running)
     * or if keyboard composing is currently active.
     */
    fun onStreamingStepResult(
        editor: EditorBridge?,
        formattedText: String,
        generationId: Long,
        frozenPrefix: String,
        activeSuffix: String
    ): Boolean {
        if (generationId != streamingGenerationId || composingText.isNotEmpty()) {
            return false
        }
        clearUndoState()

        if (frozenPrefix.isNotEmpty() && frozenPrefix.length > streamingCommittedPrefix.length) {
            val newlyFrozen = frozenPrefix.substring(streamingCommittedPrefix.length)
            editor?.commitText(newlyFrozen, 1)
            streamingCommittedPrefix = frozenPrefix
            streamingLastComposingText = activeSuffix
            lastImeComposingEditTimeMs = System.currentTimeMillis()
            if (activeSuffix.isNotEmpty()) {
                editor?.setComposingText(activeSuffix, 1)
            } else {
                editor?.setComposingText("", 0)
            }
            previousCommittedPhrase = newlyFrozen.takeLast(6)
            return true
        } else {
            val activeText = if (streamingCommittedPrefix.isNotEmpty() && formattedText.startsWith(streamingCommittedPrefix)) {
                formattedText.substring(streamingCommittedPrefix.length).trimStart()
            } else {
                formattedText
            }
            if (activeText != streamingLastComposingText) {
                streamingLastComposingText = activeText
                lastImeComposingEditTimeMs = System.currentTimeMillis()
                editor?.setComposingText(activeText, 1)
                return true
            }
            return false
        }
    }

    /**
     * Registers a newly captured speech chunk before background ASR decoding begins.
     * Captures the current keyboard composing session ID to enforce exact chronological ordering.
     * @return Unique decodeId token to pass to [deliverRecognizedVoiceChunk] or [onVoiceDecodeCancelledOrFailed].
     */
    fun onSpeechChunkCapturedForDecode(): Long = synchronized(inFlightLock) {
        val id = ++decodeCounter
        inFlightDecodes.add(
            InFlightDecode(
                decodeId = id,
                capturedSessionId = activeComposingSessionId
            )
        )
        id
    }

    fun onVoiceDecodeCancelledOrFailed(decodeId: Long) = synchronized(inFlightLock) {
        inFlightDecodes.removeAll { it.decodeId == decodeId }
    }

    /**
     * Delivers a decoded speech chunk to the editor while preserving exact chronological order
     * relative to any concurrent or subsequent keyboard composing/commits.
     *
     * @return true if the voice text was immediately committed to the editor (or prepended before composing),
     *         false if it was queued in [pendingVoiceQueue] awaiting completion of the active composing session.
     */
    fun deliverRecognizedVoiceChunk(
        editor: EditorBridge?,
        text: String,
        decodeId: Long,
        deletePrecedingChars: Int = 0
    ): Boolean {
        val descriptor: InFlightDecode? = synchronized(inFlightLock) {
            val idx = inFlightDecodes.indexOfFirst { it.decodeId == decodeId }
            if (idx >= 0) inFlightDecodes.removeAt(idx) else null
        }

        if (text.isBlank()) return false

        // Any voice delivery invalidates previous keyboard Undo state
        clearUndoState()

        val capturedSessionId = descriptor?.capturedSessionId ?: activeComposingSessionId
        val typedAfterSpeech = descriptor?.committedAfterThisDecode?.toString() ?: ""

        // Case 1: Speech was captured DURING the currently active keyboard composing session
        // (ComposingSession(S) < Speech(S)). Queue it so the user can finish converting Session S first.
        if (composingText.isNotEmpty() && capturedSessionId == activeComposingSessionId) {
            pendingVoiceQueue.add(text)
            return false
        }

        // Case 2: Speech was captured BEFORE the current keyboard composing session started
        // (Speech(S) < CommitsAfterSpeech(S) < ComposingSession(S + 1)), OR the user committed
        // text via direct keys / completed sessions while this speech chunk was decoding.
        if (composingText.isNotEmpty() || typedAfterSpeech.isNotEmpty()) {
            editor?.beginBatchEdit()
            try {
                if (composingText.isNotEmpty()) {
                    editor?.setComposingText("", 0)
                }

                if (typedAfterSpeech.isNotEmpty()) {
                    val before = editor?.getTextBeforeCursor(typedAfterSpeech.length + deletePrecedingChars, 0) ?: ""
                    if (before.endsWith(typedAfterSpeech)) {
                        editor?.deleteSurroundingText(typedAfterSpeech.length + deletePrecedingChars, 0)
                        editor?.commitText(text + typedAfterSpeech, 1)
                    } else {
                        if (deletePrecedingChars > 0) {
                            editor?.deleteSurroundingText(deletePrecedingChars, 0)
                        }
                        editor?.commitText(text, 1)
                    }
                } else {
                    if (deletePrecedingChars > 0) {
                        editor?.deleteSurroundingText(deletePrecedingChars, 0)
                    }
                    editor?.commitText(text, 1)
                }

                if (composingText.isNotEmpty()) {
                    lastImeComposingEditTimeMs = System.currentTimeMillis()
                    editor?.setComposingText(composingText.toString(), 1)
                }
            } finally {
                editor?.endBatchEdit()
            }

            // Record this voice commit for any even-older in-flight decode that hasn't finished yet
            synchronized(inFlightLock) {
                for (d in inFlightDecodes) {
                    if (descriptor != null && d.decodeId < descriptor.decodeId) {
                        d.committedAfterThisDecode.insert(0, text)
                    }
                }
            }

            previousCommittedPhrase = (if (typedAfterSpeech.isNotEmpty()) typedAfterSpeech else text).takeLast(6)
            return true
        }

        // Case 3: Idle delivery (no active keyboard composing and no commits occurred during decode)
        if (deletePrecedingChars > 0) {
            editor?.deleteSurroundingText(deletePrecedingChars, 0)
        }
        editor?.commitText(text, 1)
        recordDirectCommitForInFlightDecodes(text)
        previousCommittedPhrase = text.takeLast(6)
        return true
    }

    /**
     * Flushes any post-typing voice chunks queued while a keyboard composing session was open.
     * Only flushes when `composingText` is empty so active keyboard composing is never overwritten.
     */
    fun flushPendingVoice(editor: EditorBridge?): String? {
        if (composingText.isNotEmpty() || pendingVoiceQueue.isEmpty()) return null
        val queued = pendingVoiceQueue.joinToString("")
        pendingVoiceQueue.clear()
        clearUndoState()
        editor?.commitText(queued, 1)
        recordDirectCommitForInFlightDecodes(queued)
        previousCommittedPhrase = queued.takeLast(6)
        return queued
    }

    /**
     * Handles external cursor/selection movements reported by InputMethodService.onUpdateSelection.
     * If the user tapped elsewhere in the text field (moving the cursor outside the composing span),
     * finalizes the old composing span and resets internal composing state to prevent text duplication.
     */
    fun onExternalSelectionUpdated(
        editor: EditorBridge?,
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
        onResetStreamingUtterance: () -> Unit = {}
    ): Boolean {
        val selectionChanged = (oldSelStart != newSelStart || oldSelEnd != newSelEnd)
        if (composingText.isEmpty() && streamingLastComposingText.isEmpty()) {
            val now = System.currentTimeMillis()
            val recentImeEdit = (now - lastImeComposingEditTimeMs <= 80L) || (now - lastImeDirectEditTimeMs <= 80L)
            if (selectionChanged && !recentImeEdit) {
                clearAutoAssistState()
            }
            return false
        }
        if (!selectionChanged) return false

        val cursorOutsideComposingSpan = if (candidatesStart != -1 && candidatesEnd != -1) {
            newSelStart < candidatesStart || newSelEnd > candidatesEnd || newSelStart != candidatesEnd
        } else {
            // Host editor cleared composing span externally (ignore within 80ms of our own setComposingText IPC)
            System.currentTimeMillis() - lastImeComposingEditTimeMs > 80L
        }

        if (!cursorOutsideComposingSpan) return false

        editor?.finishComposingText()
        composingText.clear()
        activeComposingSessionId = 0L

        if (streamingLastComposingText.isNotEmpty() || streamingCommittedPrefix.isNotEmpty()) {
            resetStreamingSessionState()
            onResetStreamingUtterance()
        }

        pendingVoiceQueue.clear()
        clearInFlightCommittedTracking()
        clearUndoState()
        clearAutoAssistState()
        previousCommittedPhrase = ""
        return true
    }

    private fun recordSessionCommitForInFlightDecodes(committedSessionId: Long, committedSurface: String) =
        synchronized(inFlightLock) {
            for (d in inFlightDecodes) {
                if (d.capturedSessionId < committedSessionId) {
                    d.committedAfterThisDecode.append(committedSurface)
                }
            }
        }

    private fun recordDirectCommitForInFlightDecodes(committedText: String) = synchronized(inFlightLock) {
        for (d in inFlightDecodes) {
            d.committedAfterThisDecode.append(committedText)
        }
    }

    private fun recordDeleteCharsForInFlightDecodes(count: Int) = synchronized(inFlightLock) {
        for (d in inFlightDecodes) {
            val sb = d.committedAfterThisDecode
            if (sb.isNotEmpty()) {
                val newLen = (sb.length - count).coerceAtLeast(0)
                sb.setLength(newLen)
            }
        }
    }

    private fun clearInFlightCommittedTracking() = synchronized(inFlightLock) {
        for (d in inFlightDecodes) {
            d.committedAfterThisDecode.clear()
        }
    }
}
