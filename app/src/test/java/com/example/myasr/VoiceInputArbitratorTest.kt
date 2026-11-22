package com.example.myasr

import com.example.myasr.context.ime.domain.EditorBridge
import com.example.myasr.context.ime.domain.VoiceTouchInputArbitrator
import com.example.myasr.context.kanakanji.domain.ConversionCandidate
import com.example.myasr.context.kanakanji.domain.JapaneseKanaUtils
import com.example.myasr.context.kanakanji.domain.NextPhrasePredictor
import com.example.myasr.context.reranking.domain.CandidateRerankJudgement
import com.example.myasr.context.speech.domain.StreamingVadProcessor
import com.example.myasr.context.speech.infrastructure.AudioRecorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Comprehensive test suite verifying all multimodal Voice + Touch interaction corner cases
 * governed by [VoiceTouchInputArbitrator], [AudioRecorder], and [StreamingVadProcessor].
 */
class VoiceInputArbitratorTest {

    /**
     * Faithful in-memory implementation of Android's InputConnection composing & surrounding text semantics:
     * - `commitText` replaces any active composing span with committed text.
     * - `setComposingText` replaces any active composing span with a new composing span.
     * - `finishComposingText` converts the active composing span into committed text in-place.
     * - `deleteSurroundingText` deletes characters immediately before the composing span (or cursor if no composing span).
     * - `getTextBeforeCursor` returns text before the current cursor (which sits at the end of the composing span).
     */
    private class RealisticMockEditorBridge : EditorBridge {
        var committedText: String = ""
        var composingSpan: String = ""
        var selectedText: String? = null
        var batchDepth: Int = 0

        val fullVisibleText: String
            get() = committedText + composingSpan

        override fun beginBatchEdit(): Boolean {
            batchDepth++
            return true
        }

        override fun endBatchEdit(): Boolean {
            batchDepth = (batchDepth - 1).coerceAtLeast(0)
            return true
        }

        override fun setComposingText(text: String, newCursorPosition: Int): Boolean {
            composingSpan = text
            return true
        }

        override fun commitText(text: String, newCursorPosition: Int): Boolean {
            committedText += text
            composingSpan = ""
            selectedText = null
            return true
        }

        override fun finishComposingText(): Boolean {
            committedText += composingSpan
            composingSpan = ""
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            if (beforeLength > 0 && committedText.isNotEmpty()) {
                val keep = (committedText.length - beforeLength).coerceAtLeast(0)
                committedText = committedText.substring(0, keep)
            }
            return true
        }

        override fun getTextBeforeCursor(length: Int, flags: Int): String {
            val combined = committedText + composingSpan
            return if (combined.length <= length) combined else combined.takeLast(length)
        }

        override fun getSelectedText(flags: Int): String? = selectedText
    }

    // =========================================================================
    // Category A: Confirm (確定 / Enter) & Convert (変換 / Space) Corner Cases
    // =========================================================================

    @Test
    fun testCaseA1_ConfirmEnterDuringStreamingVoiceComposingCommitsWithoutNewlineAndResetsStream() {
        val editor = RealisticMockEditorBridge()
        val arbitrator = VoiceTouchInputArbitrator()
        var audioResetCount = 0

        // 1. Streaming voice displays underlined composing text "今日は天気が"
        val updated = arbitrator.onStreamingStepResult(
            editor = editor,
            formattedText = "今日は天気が",
            generationId = arbitrator.streamingGenerationId,
            frozenPrefix = "",
            activeSuffix = "今日は天気が"
        )
        assertTrue(updated)
        assertEquals("今日は天気が", editor.composingSpan)
        assertEquals("", editor.committedText)
        assertTrue(arbitrator.hasVoiceComposingForUi(isSpeaking = true, hasUnflushedSpeech = true))

        val genBeforeConfirm = arbitrator.streamingGenerationId

        // 2. User presses 確定 (Enter) while streaming voice text is underlined
        val outcome = arbitrator.onEnterPressed(
            editor = editor,
            hasUnflushedSpeech = true,
            onResetStreamingUtterance = { audioResetCount++ }
        )

        // Must commit streaming text, NOT trigger editor return/newline, and increment generation ID
        assertEquals(VoiceTouchInputArbitrator.EnterOutcome.COMMITTED_STREAMING_COMPOSING, outcome)
        assertEquals("今日は天気が", editor.committedText)
        assertEquals("", editor.composingSpan)
        assertEquals(1, audioResetCount)
        assertTrue(arbitrator.streamingGenerationId > genBeforeConfirm)

        // 3. Stale in-flight streaming ASR result from before Confirm arrives 50ms later -> must be ignored!
        val staleApplied = arbitrator.onStreamingStepResult(
            editor = editor,
            formattedText = "今日は天気が",
            generationId = genBeforeConfirm,
            frozenPrefix = "",
            activeSuffix = "今日は天気が"
        )
        assertFalse("Stale streaming generation must not re-insert confirmed text", staleApplied)
        assertEquals("今日は天気が", editor.fullVisibleText)
        assertEquals("", editor.composingSpan)
    }

    @Test
    fun testCaseA2_ConfirmEnterInVadModeFlushesUnflushedSpeechInsteadOfSendingPrematureNewline() {
        val editor = RealisticMockEditorBridge()
        val arbitrator = VoiceTouchInputArbitrator()
        var flushedSpeech = false
        var decodeToken = -1L

        // User just finished speaking in VAD mode (< 200ms ago, so speech is still unflushed in VAD)
        // and immediately presses Enter (確定)
        val outcome = arbitrator.onEnterPressed(
            editor = editor,
            hasUnflushedSpeech = true,
            onFlushPreTypingSpeech = {
                flushedSpeech = true
                decodeToken = arbitrator.onSpeechChunkCapturedForDecode()
            }
        )

        assertEquals(VoiceTouchInputArbitrator.EnterOutcome.FLUSHED_OR_CONFIRMED_PENDING_SPEECH, outcome)
        assertTrue("Unflushed speech must be immediately flushed on Enter", flushedSpeech)

        // Speech finishes decoding and commits
        arbitrator.deliverRecognizedVoiceChunk(editor, "了解しました。", decodeToken)
        assertEquals("了解しました。", editor.committedText)

        // Now pressing Enter a second time (when idle) performs the actual return action
        val secondOutcome = arbitrator.onEnterPressed(
            editor = editor,
            hasUnflushedSpeech = false
        )
        assertEquals(VoiceTouchInputArbitrator.EnterOutcome.PERFORM_EDITOR_RETURN_ACTION, secondOutcome)
    }

    @Test
    fun testCaseA4_SpacePressedDuringStreamingVoiceComposingCommitsVoiceThenAppendsSpace() {
        val editor = RealisticMockEditorBridge()
        val arbitrator = VoiceTouchInputArbitrator()
        var audioReset = false

        // Streaming voice is showing "こんにちは"
        arbitrator.onStreamingStepResult(
            editor = editor,
            formattedText = "こんにちは",
            generationId = arbitrator.streamingGenerationId,
            frozenPrefix = "",
            activeSuffix = "こんにちは"
        )
        assertEquals("こんにちは", editor.composingSpan)

        // User presses Space (␣)
        val outcome = arbitrator.onSpacePressed(
            editor = editor,
            spaceCharWhenIdle = "　",
            onResetStreamingUtterance = { audioReset = true }
        )

        assertEquals(VoiceTouchInputArbitrator.SpaceOutcome.COMMITTED_STREAMING_AND_SPACE, outcome)
        assertTrue(audioReset)
        // Must NOT overwrite "こんにちは" with "　"; must commit "こんにちは" followed by "　"!
        assertEquals("こんにちは　", editor.committedText)
        assertEquals("", editor.composingSpan)
    }

    // =========================================================================
    // Category B: Flick / Character Input During Voice Input
    // =========================================================================

    @Test
    fun testCaseB1_FlickKanaDuringStreamingVoiceAutoCommitsVoiceAndSuspendsStreamingOverwrites() {
        val editor = RealisticMockEditorBridge()
        val arbitrator = VoiceTouchInputArbitrator()
        var audioResetCount = 0

        // 1. Streaming voice displays "今日は"
        arbitrator.onStreamingStepResult(
            editor = editor,
            formattedText = "今日は",
            generationId = arbitrator.streamingGenerationId,
            frozenPrefix = "",
            activeSuffix = "今日は"
        )
        assertEquals("今日は", editor.composingSpan)

        // 2. User immediately flicks "て", "ん", "き" on the keyboard without tapping Confirm first
        arbitrator.onTypeKana(editor, "て", onResetStreamingUtterance = { audioResetCount++ })
        arbitrator.onTypeKana(editor, "ん", onResetStreamingUtterance = { audioResetCount++ })
        arbitrator.onTypeKana(editor, "き", onResetStreamingUtterance = { audioResetCount++ })

        // "今日は" must be safely committed, and "てんき" must be the active composing span!
        assertEquals("今日は", editor.committedText)
        assertEquals("てんき", editor.composingSpan)
        assertEquals("今日はてんき", editor.fullVisibleText)
        assertEquals(1, audioResetCount)

        // 3. While user is still composing "てんき", a background streaming step tries to write -> must be blocked!
        val blocked = arbitrator.onStreamingStepResult(
            editor = editor,
            formattedText = "ノイズ",
            generationId = arbitrator.streamingGenerationId,
            frozenPrefix = "",
            activeSuffix = "ノイズ"
        )
        assertFalse("Streaming loop must never overwrite active keyboard composing text", blocked)
        assertEquals("てんき", editor.composingSpan)

        // 4. User presses Space -> commits composing reading as-is and enters a space
        arbitrator.onSpacePressed(
            editor = editor,
            spaceCharWhenIdle = "　"
        )
        assertEquals("今日はてんき　", editor.committedText)
        assertEquals("", editor.composingSpan)
    }

    @Test
    fun testCaseB2_DirectCharInQwertyOrNumpadDuringStreamingCommitsVoiceFirst() {
        val editor = RealisticMockEditorBridge()
        val arbitrator = VoiceTouchInputArbitrator()

        arbitrator.onStreamingStepResult(
            editor = editor,
            formattedText = "会議は",
            generationId = arbitrator.streamingGenerationId,
            frozenPrefix = "",
            activeSuffix = "会議は"
        )

        // User types "1", "0" in Numpad/QWERTY mode
        arbitrator.onTypeDirectChar(editor, "1")
        arbitrator.onTypeDirectChar(editor, "0")

        assertEquals("会議は10", editor.committedText)
        assertEquals("", editor.composingSpan)
    }

    @Test
    fun testCaseB4_PreTypingSpeechChronologicalPreservationWhenFlickingBeforeAsrCompletes() {
        val editor = RealisticMockEditorBridge()
        val arbitrator = VoiceTouchInputArbitrator()
        var flushedDecodeId = -1L

        // 1. User speaks "明日の会議は" and immediately flicks "じゅうじ" BEFORE the 450ms VAD silence timeout
        arbitrator.onTypeKana(
            editor = editor,
            text = "じ",
            onFlushPreTypingSpeech = {
                // Flushing pre-typing speech happens while activeComposingSessionId is still 0
                flushedDecodeId = arbitrator.onSpeechChunkCapturedForDecode()
            }
        )
        arbitrator.onTypeKana(editor, "ゅ")
        arbitrator.onTypeKana(editor, "う")
        arbitrator.onTypeKana(editor, "じ")

        assertEquals("", editor.committedText)
        assertEquals("じゅうじ", editor.composingSpan)

        // 2. While user is still viewing candidates for "じゅうじ", ASR finishes decoding "明日の会議は"
        val deliveredImmediately = arbitrator.deliverRecognizedVoiceChunk(
            editor = editor,
            text = "明日の会議は",
            decodeId = flushedDecodeId
        )

        // "明日の会議は" was spoken BEFORE "じゅうじ", so it must be prepended BEFORE composing "じゅうじ"!
        assertTrue(deliveredImmediately)
        assertEquals("明日の会議は", editor.committedText)
        assertEquals("じゅうじ", editor.composingSpan)
        assertEquals("明日の会議はじゅうじ", editor.fullVisibleText)

        // 3. User selects candidate "10時"
        arbitrator.onCandidateSelected(
            editor = editor,
            candidateSurface = "10時",
            isPrediction = false
        )
        assertEquals("明日の会議は10時", editor.committedText)
        assertEquals("", editor.composingSpan)
    }

    @Test
    fun testCaseB4b_PreTypingSpeechChronologicalPreservationWhenUserAlreadyCommittedTypedWordBeforeAsrCompletes() {
        val editor = RealisticMockEditorBridge()
        val arbitrator = VoiceTouchInputArbitrator()
        var flushedDecodeId = -1L

        // 1. User speaks "明日の会議は", immediately types "1", "0" in Numpad and flicks "じ" -> commits "時"
        // ALL before the 150ms background ASR decode of "明日の会議は" finishes!
        arbitrator.onTypeDirectChar(
            editor = editor,
            text = "1",
            onFlushPreTypingSpeech = {
                flushedDecodeId = arbitrator.onSpeechChunkCapturedForDecode()
            }
        )
        arbitrator.onTypeDirectChar(editor, "0")
        arbitrator.onTypeKana(editor, "じ")
        arbitrator.onCandidateSelected(editor, candidateSurface = "時", isPrediction = false)
        // And user even starts typing the next word "から"
        arbitrator.onTypeKana(editor, "か")
        arbitrator.onTypeKana(editor, "ら")

        assertEquals("10時", editor.committedText)
        assertEquals("から", editor.composingSpan)

        // 2. Background ASR finally completes for "明日の会議は"
        arbitrator.deliverRecognizedVoiceChunk(
            editor = editor,
            text = "明日の会議は",
            decodeId = flushedDecodeId
        )

        // "明日の会議は" must be placed BEFORE "10時", while preserving active composing "から"!
        assertEquals("明日の会議は10時", editor.committedText)
        assertEquals("から", editor.composingSpan)
        assertEquals("明日の会議は10時から", editor.fullVisibleText)
    }

    @Test
    fun testCaseB5_PostTypingSpeechDuringActiveComposingQueuesAndFlushesAfterCommit() {
        val editor = RealisticMockEditorBridge()
        val arbitrator = VoiceTouchInputArbitrator()

        // 1. First speech chunk completes when idle
        val id1 = arbitrator.onSpeechChunkCapturedForDecode()
        arbitrator.deliverRecognizedVoiceChunk(editor, "明日の会議は", id1)
        assertEquals("明日の会議は", editor.committedText)

        // 2. User starts typing "じゅうじ" on keyboard (Session 1)
        arbitrator.onTypeKana(editor, "じ")
        arbitrator.onTypeKana(editor, "ゅ")
        arbitrator.onTypeKana(editor, "う")
        arbitrator.onTypeKana(editor, "じ")
        assertEquals("じゅうじ", editor.composingSpan)

        // 3. While Session 1 ("じゅうじ") is still open on screen, user speaks "お願いします"
        val id2 = arbitrator.onSpeechChunkCapturedForDecode()
        val deliveredImmediately = arbitrator.deliverRecognizedVoiceChunk(editor, "お願いします", id2)

        // Because "お願いします" was captured DURING Session 1, it must be queued after Session 1
        assertFalse(deliveredImmediately)
        assertEquals("じゅうじ", editor.composingSpan)
        assertEquals("明日の会議は", editor.committedText)
        assertEquals(listOf("お願いします"), arbitrator.pendingVoiceQueue)

        // 4. User commits "じゅうじ" as "10時" -> "お願いします" automatically flushes right after "10時"!
        arbitrator.onCandidateSelected(editor, candidateSurface = "10時", isPrediction = false)
        assertEquals("明日の会議は10時お願いします", editor.committedText)
        assertEquals("", editor.composingSpan)
        assertTrue(arbitrator.pendingVoiceQueue.isEmpty())
    }

    @Test
    fun testRapidThreeTurnAlternation_Flick1_Speak2_Commit1_Flick3_BeforeSpeak2FinishesDecoding() {
        val editor = RealisticMockEditorBridge()
        val arbitrator = VoiceTouchInputArbitrator()

        // Turn 1: User flicks "あした" (Session 1)
        arbitrator.onTypeKana(editor, "あ")
        arbitrator.onTypeKana(editor, "し")
        arbitrator.onTypeKana(editor, "た")

        // Turn 2: While Session 1 is open, user speaks "10時から" (captured during Session 1)
        val speech2DecodeId = arbitrator.onSpeechChunkCapturedForDecode()

        // User commits Session 1 as "明日" while Speech 2 is still decoding in Sherpa-ONNX
        arbitrator.onCandidateSelected(editor, candidateSurface = "明日", isPrediction = false)
        assertEquals("明日", editor.committedText)

        // Turn 3: User immediately starts flicking "よろしく" (Session 2) while Speech 2 is STILL decoding!
        arbitrator.onTypeKana(editor, "よ")
        arbitrator.onTypeKana(editor, "ろ")
        arbitrator.onTypeKana(editor, "し")
        arbitrator.onTypeKana(editor, "く")
        assertEquals("明日", editor.committedText)
        assertEquals("よろしく", editor.composingSpan)

        // Now Speech 2 ("10時から") finishes decoding!
        // Chronological order: Session 1 ("明日") < Speech 2 ("10時から") < Session 2 ("よろしく")
        arbitrator.deliverRecognizedVoiceChunk(editor, "10時から", speech2DecodeId)

        assertEquals("明日10時から", editor.committedText)
        assertEquals("よろしく", editor.composingSpan)
        assertEquals("明日10時からよろしく", editor.fullVisibleText)
    }

    // =========================================================================
    // Category C: Backspace / Delete (⌫) & Modifier (゛゜) Corner Cases
    // =========================================================================

    @Test
    fun testCaseC1_BackspaceDuringStreamingVoiceDeletesLastCharOfStreamingTextNotPrecedingSentence() {
        val editor = RealisticMockEditorBridge()
        val arbitrator = VoiceTouchInputArbitrator()
        var audioReset = false

        // Existing committed sentence: "お疲れ様です。"
        editor.commitText("お疲れ様です。", 1)

        // Streaming voice adds underlined "今日は天気が"
        arbitrator.onStreamingStepResult(
            editor = editor,
            formattedText = "今日は天気が",
            generationId = arbitrator.streamingGenerationId,
            frozenPrefix = "",
            activeSuffix = "今日は天気が"
        )
        assertEquals("お疲れ様です。", editor.committedText)
        assertEquals("今日は天気が", editor.composingSpan)

        // User presses Backspace (⌫) once
        val outcome = arbitrator.onDeletePressed(
            editor = editor,
            onResetStreamingUtterance = { audioReset = true }
        )

        // Must delete "が" (the last char of streaming text), NOT "。" from "お疲れ様です。"!
        assertEquals(VoiceTouchInputArbitrator.DeleteOutcome.DELETED_FROM_STREAMING_COMPOSING, outcome)
        assertTrue(audioReset)
        assertEquals("お疲れ様です。今日は天気", editor.committedText)
        assertEquals("", editor.composingSpan)

        // Pressing Backspace a second time deletes "気"
        arbitrator.onDeletePressed(editor = editor)
        assertEquals("お疲れ様です。今日は天", editor.committedText)
    }

    @Test
    fun testCaseC3_ModifierKeyDuringStreamingVoiceModifiesLastCharAndCommits() {
        val editor = RealisticMockEditorBridge()
        val arbitrator = VoiceTouchInputArbitrator()
        var audioReset = false

        arbitrator.onStreamingStepResult(
            editor = editor,
            formattedText = "今日はてんきか",
            generationId = arbitrator.streamingGenerationId,
            frozenPrefix = "",
            activeSuffix = "今日はてんきか"
        )

        // User flicks Left (Dakuten ゛) on the ゛゜ modifier key to turn trailing "か" into "が"
        val modifiedKeyboard = arbitrator.onModifierPressed(
            editor = editor,
            direction = JapaneseKanaUtils.FlickDirection.LEFT,
            onResetStreamingUtterance = { audioReset = true }
        )

        assertFalse(modifiedKeyboard)
        assertTrue(audioReset)
        assertEquals("今日はてんきが", editor.committedText)
        assertEquals("", editor.composingSpan)
    }

    // =========================================================================
    // Category D: Undo (↶) Safety After Voice Input
    // =========================================================================

    @Test
    fun testCaseD1_VoiceCommitInvalidatesStaleKeyboardUndoStatePreventingCorruption() {
        val editor = RealisticMockEditorBridge()
        val arbitrator = VoiceTouchInputArbitrator()

        // 1. User types "かいぎ" and converts to "会議"
        arbitrator.onTypeKana(editor, "か")
        arbitrator.onTypeKana(editor, "い")
        arbitrator.onTypeKana(editor, "ぎ")
        arbitrator.onCandidateSelected(editor, candidateSurface = "会議", isPrediction = false)
        assertEquals("会議", editor.committedText)
        assertTrue("Undo should be available immediately after keyboard conversion", arbitrator.canUndo())

        // 2. User speaks "を始めます" via voice input
        val decodeId = arbitrator.onSpeechChunkCapturedForDecode()
        arbitrator.deliverRecognizedVoiceChunk(editor, "を始めます", decodeId)
        assertEquals("会議を始めます", editor.committedText)

        // Undo MUST now be disabled so pressing ↶ cannot delete "ます" and replace it with "かいぎ"!
        assertFalse("Voice commit must invalidate stale keyboard Undo state", arbitrator.canUndo())
        val undoExecuted = arbitrator.onUndoPressed(editor)
        assertFalse(undoExecuted)
        assertEquals("会議を始めます", editor.committedText)
    }

    @Test
    fun testCaseD2_UndoWorksNormallyWhenNoVoiceCommittedAfterKeyboardCommit() {
        val editor = RealisticMockEditorBridge()
        val arbitrator = VoiceTouchInputArbitrator()

        arbitrator.onTypeKana(editor, "か")
        arbitrator.onTypeKana(editor, "い")
        arbitrator.onTypeKana(editor, "ぎ")
        arbitrator.onCandidateSelected(editor, candidateSurface = "会議", isPrediction = false)
        assertEquals("会議", editor.committedText)

        // User immediately presses Undo (↶)
        assertTrue(arbitrator.onUndoPressed(editor))
        assertEquals("", editor.committedText)
        assertEquals("かいぎ", editor.composingSpan)
    }

    // =========================================================================
    // Category E & G: Navigation, Clipboard Paste & External Selection Updates
    // =========================================================================

    @Test
    fun testCaseE3_ClipboardPasteDuringStreamingVoiceCommitsVoiceFirstThenPastes() {
        val editor = RealisticMockEditorBridge()
        val arbitrator = VoiceTouchInputArbitrator()
        var audioReset = false

        arbitrator.onStreamingStepResult(
            editor = editor,
            formattedText = "URLは",
            generationId = arbitrator.streamingGenerationId,
            frozenPrefix = "",
            activeSuffix = "URLは"
        )
        assertEquals("URLは", editor.composingSpan)

        // User taps Clipboard Paste ("https://example.com")
        arbitrator.onClipboardPaste(
            editor = editor,
            clipText = "https://example.com",
            onResetStreamingUtterance = { audioReset = true }
        )

        assertTrue(audioReset)
        assertEquals("URLはhttps://example.com", editor.committedText)
        assertEquals("", editor.composingSpan)
    }

    @Test
    fun testSensitiveClipboardPasteClearsPreviousCommittedPhrase() {
        val editor = RealisticMockEditorBridge()
        val arbitrator = VoiceTouchInputArbitrator()
        arbitrator.previousCommittedPhrase = "前の文節"

        arbitrator.onClipboardPaste(
            editor = editor,
            clipText = "My\$ecretP@ssw0rd!",
            isSensitiveOrPasswordContext = true
        )

        assertEquals("My\$ecretP@ssw0rd!", editor.committedText)
        assertEquals("", arbitrator.previousCommittedPhrase)
    }

    @Test
    fun testCaseG1_ExternalCursorTapFinalizesComposingAndPreventsDuplication() {
        val editor = RealisticMockEditorBridge()
        val arbitrator = VoiceTouchInputArbitrator()
        var audioReset = false

        arbitrator.onStreamingStepResult(
            editor = editor,
            formattedText = "今日は天気",
            generationId = arbitrator.streamingGenerationId,
            frozenPrefix = "",
            activeSuffix = "今日は天気"
        )
        val oldGen = arbitrator.streamingGenerationId

        // User taps at index 0 of the EditText (moving cursor from 5 to 0, outside composing span [0, 5])
        val resetTriggered = arbitrator.onExternalSelectionUpdated(
            editor = editor,
            oldSelStart = 5,
            oldSelEnd = 5,
            newSelStart = 0,
            newSelEnd = 0,
            candidatesStart = 0,
            candidatesEnd = 5,
            onResetStreamingUtterance = { audioReset = true }
        )

        assertTrue(resetTriggered)
        assertTrue(audioReset)
        assertEquals("今日は天気", editor.committedText)
        assertEquals("", editor.composingSpan)
        assertNull(arbitrator.commitActiveStreamingComposing(editor))
        assertTrue(arbitrator.streamingGenerationId > oldGen)
    }

    // =========================================================================
    // AudioRecorder & StreamingVadProcessor Multimodal Helper Tests
    // =========================================================================

    @Test
    fun testAudioRecorderFlushPendingSpeechChunkAndResetStreamingUtterance() {
        val capturedChunks = mutableListOf<FloatArray>()
        val recorder = AudioRecorder(
            sampleRate = 16000,
            onSpeechChunkCaptured = { capturedChunks.add(it) }
        )

        val speechBuffer = ShortArray(1024) { idx ->
            (Math.sin(2.0 * Math.PI * 440.0 * idx / 16000.0) * 8000).toInt().toShort()
        }

        // Feed ~500ms of speech (8 chunks) without any trailing silence
        for (i in 0 until 8) {
            recorder.feedAudioData(speechBuffer, speechBuffer.size)
        }
        assertTrue("Recorder must report unflushed speech while user is speaking", recorder.hasUnflushedSpeech())
        assertEquals(0, capturedChunks.size)

        // User touches keyboard -> flushPendingSpeechChunk() immediately flushes the chunk without waiting for silence!
        val flushed = recorder.flushPendingSpeechChunk(invokeCallback = true)
        assertTrue(flushed != null && flushed.isNotEmpty())
        assertEquals(1, capturedChunks.size)
        assertFalse("Unflushed speech must be false after flush", recorder.hasUnflushedSpeech())

        // Subsequent stop() must return empty array (no duplication)
        val stopSamples = recorder.stop()
        assertTrue(stopSamples.isEmpty())
    }

    @Test
    fun testBatchDeleteStopsAtKeyboardComposingBoundaryAndDeletesCommittedInBatch() {
        val editor = RealisticMockEditorBridge()
        val arbitrator = VoiceTouchInputArbitrator()
        editor.commitText("Hello ", 1)
        arbitrator.onTypeKana(editor, "あ")
        arbitrator.onTypeKana(editor, "い")
        arbitrator.onTypeKana(editor, "う")
        assertEquals("Hello ", editor.committedText)
        assertEquals("あいう", editor.composingSpan)

        // Batch delete of 8 while composing only 3 chars must stop at composing boundary
        val outcome1 = arbitrator.onDeletePressed(editor = editor, count = 8)
        assertEquals(VoiceTouchInputArbitrator.DeleteOutcome.DELETED_FROM_KEYBOARD_COMPOSING_NOW_EMPTY, outcome1)
        assertEquals("", editor.composingSpan)
        assertEquals("Hello ", editor.committedText)

        // Next batch delete of 4 deletes 4 committed chars at once
        val outcome2 = arbitrator.onDeletePressed(editor = editor, count = 4)
        assertEquals(VoiceTouchInputArbitrator.DeleteOutcome.DELETED_SURROUNDING_TEXT, outcome2)
        assertEquals("He", editor.committedText)
    }

    @Test
    fun testPredictionCheckpointRestoredAfterSelectingAndDeletingPredictionCandidate() {
        val editor = RealisticMockEditorBridge()
        val arbitrator = VoiceTouchInputArbitrator()
        val predictor = NextPhrasePredictor(null)

        // 1. User types "あしたの" and commits "明日の"
        arbitrator.onTypeKana(editor, "あ")
        arbitrator.onTypeKana(editor, "し")
        arbitrator.onTypeKana(editor, "た")
        arbitrator.onTypeKana(editor, "の")
        arbitrator.onCandidateSelected(editor, candidateSurface = "明日の", isPrediction = false, candidateReading = "あしたの")
        assertEquals("明日の", editor.committedText)

        val predictionsAfterAshitaNo = predictor.predict("明日の", textBeforeCursor = "明日の", suggestPeriod = false)
        assertTrue(predictionsAfterAshitaNo.any { it.surface == "会議" })
        arbitrator.recordOrUpdatePredictionCheckpoint(
            contextBeforeCursor = "明日の",
            committedPhrase = "明日の",
            candidates = predictionsAfterAshitaNo,
            isFullyReranked = true,
            debugBadge = "SLM",
            highlightFirstAsAi = true
        )

        // 2. User taps prediction candidate "会議"
        val kaigiCand = predictionsAfterAshitaNo.first { it.surface == "会議" }
        val prevPhrase = arbitrator.previousCommittedPhrase
        arbitrator.onCandidateSelected(
            editor = editor,
            candidateSurface = kaigiCand.surface,
            isPrediction = true,
            candidateReading = kaigiCand.reading
        )
        assertEquals("明日の会議", editor.committedText)
        val kaigiToken = predictor.recordTransitionWithToken(prevPhrase, "会議", kaigiCand.reading)
        val predictionsAfterKaigi = predictor.predict("会議", textBeforeCursor = "明日の会議", suggestPeriod = false)
        arbitrator.recordOrUpdatePredictionCheckpoint(
            contextBeforeCursor = "明日の会議",
            committedPhrase = "会議",
            candidates = predictionsAfterKaigi,
            isFullyReranked = true,
            rollbackToken = kaigiToken
        )

        // 3. First Backspace: "明日の会議" -> "明日の会"
        val delOutcome1 = arbitrator.onDeletePressed(editor = editor, count = 1)
        assertEquals(VoiceTouchInputArbitrator.DeleteOutcome.DELETED_SURROUNDING_TEXT, delOutcome1)
        assertEquals("明日の会", editor.committedText)
        val match1 = arbitrator.reconcilePredictionCheckpointsAfterDelete(editor.committedText) {
            predictor.rollbackTransition(it)
        }
        assertNull("Intermediate partial string should not match checkpoint", match1)
        assertNull("Speculative '会議' transition must already be rolled back", predictor.getHistorySnapshot()["明日の"]?.get("会議"))
        assertNull(predictor.getHistorySnapshot()["明日|の"]?.get("会議"))

        // 4. Second Backspace: "明日の会" -> "明日の"
        val delOutcome2 = arbitrator.onDeletePressed(editor = editor, count = 1)
        assertEquals(VoiceTouchInputArbitrator.DeleteOutcome.DELETED_SURROUNDING_TEXT, delOutcome2)
        assertEquals("明日の", editor.committedText)
        val match2 = arbitrator.reconcilePredictionCheckpointsAfterDelete(editor.committedText) {
            predictor.rollbackTransition(it)
        }
        assertNotNull("Deleting '会議' back to '明日の' must match '明日の' checkpoint", match2)
        assertEquals(predictionsAfterAshitaNo.map { it.surface }, match2!!.candidates.map { it.surface })
        assertEquals("明日の", arbitrator.previousCommittedPhrase)
        assertTrue(match2.isFullyReranked)
        assertEquals("SLM", match2.debugBadge)
        assertTrue(match2.highlightFirstAsAi)
    }

    @Test
    fun testMultiStepPredictionChainDeleteRestoresEachIntermediateCheckpoint() {
        val editor = RealisticMockEditorBridge()
        val arbitrator = VoiceTouchInputArbitrator()
        val predictor = NextPhrasePredictor(null)

        // Step 1: Commit "明日"
        arbitrator.onTypeKana(editor, "あ")
        arbitrator.onCandidateSelected(editor, candidateSurface = "明日", isPrediction = false, candidateReading = "あした")
        val candsAshita = predictor.predict("明日", textBeforeCursor = "明日", suggestPeriod = false)
        arbitrator.recordOrUpdatePredictionCheckpoint("明日", "明日", candsAshita, isFullyReranked = true)

        // Step 2: Select "の" -> "明日の"
        arbitrator.onCandidateSelected(editor, candidateSurface = "の", isPrediction = true, candidateReading = "の")
        val tNo = predictor.recordTransitionWithToken("明日", "の", "の")
        val candsAshitaNo = predictor.predict("の", textBeforeCursor = "明日の", suggestPeriod = false)
        arbitrator.recordOrUpdatePredictionCheckpoint("明日の", "の", candsAshitaNo, isFullyReranked = true, rollbackToken = tNo)

        // Step 3: Select "会議" -> "明日の会議"
        arbitrator.onCandidateSelected(editor, candidateSurface = "会議", isPrediction = true, candidateReading = "かいぎ")
        val tKaigi = predictor.recordTransitionWithToken("の", "会議", "かいぎ")
        val candsKaigi = predictor.predict("会議", textBeforeCursor = "明日の会議", suggestPeriod = false)
        arbitrator.recordOrUpdatePredictionCheckpoint("明日の会議", "会議", candsKaigi, isFullyReranked = true, rollbackToken = tKaigi)

        // Step 4: Select "に" -> "明日の会議に"
        arbitrator.onCandidateSelected(editor, candidateSurface = "に", isPrediction = true, candidateReading = "に")
        val tNi = predictor.recordTransitionWithToken("会議", "に", "に")
        val candsNi = predictor.predict("に", textBeforeCursor = "明日の会議に", suggestPeriod = false)
        arbitrator.recordOrUpdatePredictionCheckpoint("明日の会議に", "に", candsNi, isFullyReranked = true, rollbackToken = tNi)
        assertEquals("明日の会議に", editor.committedText)

        // Delete "に" -> restores "明日の会議"
        arbitrator.onDeletePressed(editor, count = 1)
        assertEquals("明日の会議", editor.committedText)
        val restoredKaigi = arbitrator.reconcilePredictionCheckpointsAfterDelete(editor.committedText) { predictor.rollbackTransition(it) }
        assertNotNull(restoredKaigi)
        assertEquals(candsKaigi.map { it.surface }, restoredKaigi!!.candidates.map { it.surface })
        assertNull(predictor.getHistorySnapshot()["会議"]?.get("に"))

        // Delete "会議" (2 chars) -> restores "明日の"
        arbitrator.onDeletePressed(editor, count = 2)
        assertEquals("明日の", editor.committedText)
        val restoredAshitaNo = arbitrator.reconcilePredictionCheckpointsAfterDelete(editor.committedText) { predictor.rollbackTransition(it) }
        assertNotNull(restoredAshitaNo)
        assertEquals(candsAshitaNo.map { it.surface }, restoredAshitaNo!!.candidates.map { it.surface })
        assertNull(predictor.getHistorySnapshot()["明日|の"]?.get("会議"))

        // Delete "の" (1 char) -> restores "明日"
        arbitrator.onDeletePressed(editor, count = 1)
        assertEquals("明日", editor.committedText)
        val restoredAshita = arbitrator.reconcilePredictionCheckpointsAfterDelete(editor.committedText) { predictor.rollbackTransition(it) }
        assertNotNull(restoredAshita)
        assertEquals(candsAshita.map { it.surface }, restoredAshita!!.candidates.map { it.surface })
        assertNull(predictor.getHistorySnapshot()["明日"]?.get("の"))
    }

    @Test
    fun testComposingCancelBackToCommittedPhraseRestoresPredictionCheckpoint() {
        val editor = RealisticMockEditorBridge()
        val arbitrator = VoiceTouchInputArbitrator()
        val predictor = NextPhrasePredictor(null)

        // Commit "明日の" and save checkpoint
        arbitrator.onTypeKana(editor, "あ")
        arbitrator.onCandidateSelected(editor, candidateSurface = "明日の", isPrediction = false, candidateReading = "あしたの")
        val cands = predictor.predict("明日の", textBeforeCursor = "明日の", suggestPeriod = false)
        arbitrator.recordOrUpdatePredictionCheckpoint("明日の", "明日の", cands, isFullyReranked = true)

        // Type "か" into composing buffer, then immediately cancel with Backspace
        arbitrator.onTypeKana(editor, "か")
        assertEquals("明日の", editor.committedText)
        assertEquals("か", editor.composingSpan)

        val outcome = arbitrator.onDeletePressed(editor, count = 1)
        assertEquals(VoiceTouchInputArbitrator.DeleteOutcome.DELETED_FROM_KEYBOARD_COMPOSING_NOW_EMPTY, outcome)
        assertEquals("", editor.composingSpan)
        assertEquals("明日の", editor.getTextBeforeCursor(256, 0))

        val restored = arbitrator.reconcilePredictionCheckpointsAfterDelete(editor.getTextBeforeCursor(256, 0))
        assertNotNull(restored)
        assertEquals(cands.map { it.surface }, restored!!.candidates.map { it.surface })
        assertEquals("明日の", arbitrator.previousCommittedPhrase)
    }

    @Test
    fun testPredictionRestorationDmnDecisionTableRules() {
        // PRED_RESTORE_R1: Non-Kana mode -> SHOW_EMPTY_STATUS
        val r1 = CandidateRerankJudgement.judgePredictionRestorationWithTrace(
            CandidateRerankJudgement.PredictionRestorationInput(
                isKanaMode = false,
                isComposingOrStreaming = false,
                isPasswordOrBypassedField = false,
                textBeforeCursor = "明日の",
                hasMatchingCheckpoint = true,
                checkpointIsFullyReranked = true,
                hasTrailingPredictablePhrase = true
            )
        )
        assertEquals("PRED_RESTORE_R1", r1.selectedRuleId)
        assertEquals(CandidateRerankJudgement.PredictionRestorationAction.SHOW_EMPTY_STATUS, r1.result.action)

        // PRED_RESTORE_R2: Composing/streaming active -> SHOW_EMPTY_STATUS
        val r2 = CandidateRerankJudgement.judgePredictionRestorationWithTrace(
            CandidateRerankJudgement.PredictionRestorationInput(
                isKanaMode = true,
                isComposingOrStreaming = true,
                isPasswordOrBypassedField = false,
                textBeforeCursor = "明日の",
                hasMatchingCheckpoint = true,
                checkpointIsFullyReranked = true,
                hasTrailingPredictablePhrase = true
            )
        )
        assertEquals("PRED_RESTORE_R2", r2.selectedRuleId)
        assertEquals(CandidateRerankJudgement.PredictionRestorationAction.SHOW_EMPTY_STATUS, r2.result.action)

        // PRED_RESTORE_R3: Password field -> SHOW_EMPTY_STATUS
        val r3 = CandidateRerankJudgement.judgePredictionRestorationWithTrace(
            CandidateRerankJudgement.PredictionRestorationInput(
                isKanaMode = true,
                isComposingOrStreaming = false,
                isPasswordOrBypassedField = true,
                textBeforeCursor = "明日の",
                hasMatchingCheckpoint = true,
                checkpointIsFullyReranked = true,
                hasTrailingPredictablePhrase = true
            )
        )
        assertEquals("PRED_RESTORE_R3", r3.selectedRuleId)
        assertEquals(CandidateRerankJudgement.PredictionRestorationAction.SHOW_EMPTY_STATUS, r3.result.action)

        // PRED_RESTORE_R4: Exact fully reranked checkpoint match -> RESTORE_CHECKPOINT_SINGLE_PASS
        val r4 = CandidateRerankJudgement.judgePredictionRestorationWithTrace(
            CandidateRerankJudgement.PredictionRestorationInput(
                isKanaMode = true,
                isComposingOrStreaming = false,
                isPasswordOrBypassedField = false,
                textBeforeCursor = "明日の",
                hasMatchingCheckpoint = true,
                checkpointIsFullyReranked = true,
                hasTrailingPredictablePhrase = true
            )
        )
        assertEquals("PRED_RESTORE_R4", r4.selectedRuleId)
        assertEquals(CandidateRerankJudgement.PredictionRestorationAction.RESTORE_CHECKPOINT_SINGLE_PASS, r4.result.action)

        // PRED_RESTORE_R5: Partial fast-path checkpoint match -> RESTORE_CHECKPOINT_AND_COMPLETE_SLM
        val r5 = CandidateRerankJudgement.judgePredictionRestorationWithTrace(
            CandidateRerankJudgement.PredictionRestorationInput(
                isKanaMode = true,
                isComposingOrStreaming = false,
                isPasswordOrBypassedField = false,
                textBeforeCursor = "明日の",
                hasMatchingCheckpoint = true,
                checkpointIsFullyReranked = false,
                hasTrailingPredictablePhrase = true
            )
        )
        assertEquals("PRED_RESTORE_R5", r5.selectedRuleId)
        assertEquals(CandidateRerankJudgement.PredictionRestorationAction.RESTORE_CHECKPOINT_AND_COMPLETE_SLM, r5.result.action)

        // PRED_RESTORE_R6: Blank / whitespace / punctuation trailing without checkpoint -> SHOW_EMPTY_STATUS
        val r6 = CandidateRerankJudgement.judgePredictionRestorationWithTrace(
            CandidateRerankJudgement.PredictionRestorationInput(
                isKanaMode = true,
                isComposingOrStreaming = false,
                isPasswordOrBypassedField = false,
                textBeforeCursor = "   ",
                hasMatchingCheckpoint = false,
                checkpointIsFullyReranked = false,
                hasTrailingPredictablePhrase = false
            )
        )
        assertEquals("PRED_RESTORE_R6", r6.selectedRuleId)
        assertEquals(CandidateRerankJudgement.PredictionRestorationAction.SHOW_EMPTY_STATUS, r6.result.action)

        // PRED_RESTORE_DEFAULT: Non-checkpoint predictable trailing phrase -> RECOMPUTE_FROM_SURROUNDING_CONTEXT
        val rDef = CandidateRerankJudgement.judgePredictionRestorationWithTrace(
            CandidateRerankJudgement.PredictionRestorationInput(
                isKanaMode = true,
                isComposingOrStreaming = false,
                isPasswordOrBypassedField = false,
                textBeforeCursor = "明日の会",
                hasMatchingCheckpoint = false,
                checkpointIsFullyReranked = false,
                hasTrailingPredictablePhrase = true
            )
        )
        assertEquals("PRED_RESTORE_DEFAULT", rDef.selectedRuleId)
        assertEquals(CandidateRerankJudgement.PredictionRestorationAction.RECOMPUTE_FROM_SURROUNDING_CONTEXT, rDef.result.action)
        assertTrue(JapaneseKanaUtils.hasTrailingPredictablePhrase("明日の"))
        assertFalse(JapaneseKanaUtils.hasTrailingPredictablePhrase("明日の "))
        assertFalse(JapaneseKanaUtils.hasTrailingPredictablePhrase("完了しました。"))
    }
}
