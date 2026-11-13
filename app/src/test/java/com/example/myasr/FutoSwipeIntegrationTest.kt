package com.example.myasr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FutoSwipeIntegrationTest {

    private class MockEditorBridge : EditorBridge {
        val text = StringBuilder()
        var composingText: String = ""

        override fun beginBatchEdit(): Boolean = true
        override fun endBatchEdit(): Boolean = true

        override fun commitText(text: String, newCursorPosition: Int): Boolean {
            this.text.append(text)
            composingText = ""
            return true
        }

        override fun setComposingText(text: String, newCursorPosition: Int): Boolean {
            composingText = text
            return true
        }

        override fun finishComposingText(): Boolean {
            if (composingText.isNotEmpty()) {
                text.append(composingText)
                composingText = ""
            }
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            if (beforeLength > 0 && text.length >= beforeLength) {
                text.delete(text.length - beforeLength, text.length)
            }
            return true
        }

        override fun getTextBeforeCursor(length: Int, flags: Int): String {
            val s = text.toString()
            return if (s.length > length) s.substring(s.length - length) else s
        }

        override fun getSelectedText(flags: Int): String? = null
    }

    @Test
    fun testSwipeWordCommitAndTrailingSpaceAbsorption() {
        val arbitrator = VoiceTouchInputArbitrator()
        val editor = MockEditorBridge()

        // 1. Commit a single swipe word "hello" (no trailing space)
        arbitrator.onCommitSwipeWord(editor, "hello")
        assertEquals("hello", editor.text.toString())
        assertTrue(arbitrator.lastCommittedWasSwipe)

        // 2. Typing punctuation immediately attaches to the word without trailing space
        arbitrator.onTypeDirectChar(editor, ".")
        assertEquals("hello.", editor.text.toString())
        assertFalse(arbitrator.lastCommittedWasSwipe)

        // 3. Commit another swipe word "world" after '.' -> holistic auto-space inserts " world"
        arbitrator.onCommitSwipeWord(editor, "world")
        assertEquals("hello. world", editor.text.toString())
        assertTrue(arbitrator.lastCommittedWasSwipe)

        // 4. Consecutive swipe word "again": automatically inserts space between words
        arbitrator.onCommitSwipeWord(editor, "again")
        assertEquals("hello. world again", editor.text.toString())
        assertTrue(arbitrator.lastCommittedWasSwipe)

        // 5. Candidate selection replaces the recent swipe word
        arbitrator.onCandidateSelected(editor, candidateSurface = "ahead", isPrediction = false)
        assertEquals("hello. world ahead", editor.text.toString())
        assertTrue(arbitrator.lastCommittedWasSwipe)

        // 6. Delete key deletes the ENTIRE swipe word rather than just one letter
        val outcome = arbitrator.onDeletePressed(editor)
        assertEquals(VoiceTouchInputArbitrator.DeleteOutcome.DELETED_ENTIRE_SWIPE_WORD, outcome)
        assertEquals("hello. world ", editor.text.toString())
        assertFalse(arbitrator.lastCommittedWasSwipe)

        // 7. Typing key tap (letter/digit) after swipe word inserts a leading space
        arbitrator.onCommitSwipeWord(editor, "foo")
        assertEquals("hello. world foo", editor.text.toString())
        assertTrue(arbitrator.lastCommittedWasSwipe)

        arbitrator.onTypeDirectChar(editor, "b")
        assertEquals("hello. world foo b", editor.text.toString())
        assertFalse(arbitrator.lastCommittedWasSwipe)
    }

    @Test
    fun testTapThenSwipeAndEnglishCandidateAutoSpace() {
        val arbitrator = VoiceTouchInputArbitrator()
        val editor = MockEditorBridge()

        // Tap-typing "hel" then selecting English candidate "hello"
        arbitrator.onTypeDirectChar(editor, "h")
        arbitrator.onTypeDirectChar(editor, "e")
        arbitrator.onTypeDirectChar(editor, "l")
        assertEquals("hel", editor.text.toString())

        arbitrator.onEnglishCandidateSelected(editor, replaceLength = 3, candidateSurface = "hello")
        assertEquals("hello", editor.text.toString())

        // Next letter tap auto-inserts a space
        arbitrator.onTypeDirectChar(editor, "w")
        assertEquals("hello w", editor.text.toString())

        // Swiping a word after tap-typed letters also auto-inserts a space
        arbitrator.onCommitSwipeWord(editor, "world")
        assertEquals("hello w world", editor.text.toString())

        // Punctuation attaches immediately after candidate selection without space
        arbitrator.onEnglishCandidateSelected(editor, replaceLength = 5, candidateSurface = "earth")
        arbitrator.onTypeDirectChar(editor, ",")
        assertEquals("hello w earth,", editor.text.toString())

        // Tap-typing URLs/decimals without swipe or candidate tap does NOT auto-space after '.'
        val urlEditor = MockEditorBridge()
        val urlArb = VoiceTouchInputArbitrator()
        for (ch in "google.com") {
            urlArb.onTypeDirectChar(urlEditor, ch.toString())
        }
        assertEquals("google.com", urlEditor.text.toString())
    }

    @Test
    fun testGboardStyleDoubleSpaceAutoPeriod() {
        val arbitrator = VoiceTouchInputArbitrator()
        val editor = MockEditorBridge()

        // Type "hello"
        for (ch in "hello") {
            arbitrator.onTypeDirectChar(editor, ch.toString())
        }

        // First space at t=1000ms -> commits " "
        val o1 = arbitrator.onSpacePressed(editor, " ", autoPeriodEnabled = true, nowMs = 1000L)
        assertEquals(VoiceTouchInputArbitrator.SpaceOutcome.COMMITTED_IDLE_SPACE, o1)
        assertEquals("hello ", editor.text.toString())

        // Second space at t=1300ms (within 1000ms) -> converts to ". "
        val o2 = arbitrator.onSpacePressed(editor, " ", autoPeriodEnabled = true, nowMs = 1300L)
        assertEquals(VoiceTouchInputArbitrator.SpaceOutcome.CONVERTED_DOUBLE_SPACE_TO_PERIOD, o2)
        assertEquals("hello. ", editor.text.toString())

        // Third space at t=1500ms -> should NOT become ".. ", just adds a space
        val o3 = arbitrator.onSpacePressed(editor, " ", autoPeriodEnabled = true, nowMs = 1500L)
        assertEquals(VoiceTouchInputArbitrator.SpaceOutcome.COMMITTED_IDLE_SPACE, o3)
        assertEquals("hello.  ", editor.text.toString())

        // Sentence-start capitalization after auto-period
        val cap = EnglishCapitalizationAnalyzer.analyze("world", editor.text.toString(), isShiftActive = false)
        assertEquals(EnglishCapitalizationAnalyzer.CapitalizationType.SENTENCE_START, cap.type)
        assertEquals("World", cap.primaryCandidate)
    }

    @Test
    fun testAutoPeriodTimeoutAndSuppressionCases() {
        val arbitrator = VoiceTouchInputArbitrator()
        val editor = MockEditorBridge()

        // 1. Timeout exceeded (> 1000ms): does NOT insert period
        arbitrator.onTypeDirectChar(editor, "hi")
        arbitrator.onSpacePressed(editor, " ", autoPeriodEnabled = true, nowMs = 1000L)
        val slow = arbitrator.onSpacePressed(editor, " ", autoPeriodEnabled = true, nowMs = 2500L)
        assertEquals(VoiceTouchInputArbitrator.SpaceOutcome.COMMITTED_IDLE_SPACE, slow)
        assertEquals("hi  ", editor.text.toString())

        // 2. After existing punctuation (e.g. "ok."): double-space does NOT insert another period
        val puncEditor = MockEditorBridge()
        val puncArb = VoiceTouchInputArbitrator()
        puncArb.onTypeDirectChar(puncEditor, "ok.")
        puncArb.onSpacePressed(puncEditor, " ", autoPeriodEnabled = true, nowMs = 1000L)
        val afterPunc = puncArb.onSpacePressed(puncEditor, " ", autoPeriodEnabled = true, nowMs = 1200L)
        assertEquals(VoiceTouchInputArbitrator.SpaceOutcome.COMMITTED_IDLE_SPACE, afterPunc)
        assertEquals("ok.  ", puncEditor.text.toString())

        // 3. Bypass mode (password/URI/email): neither auto-space nor auto-period triggers
        val bypassEditor = MockEditorBridge()
        val bypassArb = VoiceTouchInputArbitrator()
        bypassArb.onCommitSwipeWord(bypassEditor, "user", autoSpaceEnabled = true, bypassAutoAssist = true)
        bypassArb.onCommitSwipeWord(bypassEditor, "name", autoSpaceEnabled = true, bypassAutoAssist = true)
        assertEquals("username", bypassEditor.text.toString())
        bypassArb.onSpacePressed(bypassEditor, " ", autoPeriodEnabled = true, bypassAutoAssist = true, nowMs = 1000L)
        bypassArb.onSpacePressed(bypassEditor, " ", autoPeriodEnabled = true, bypassAutoAssist = true, nowMs = 1100L)
        assertEquals("username  ", bypassEditor.text.toString())
    }

    @Test
    fun testAsyncOnUpdateSelectionDoesNotClearAutoPeriodOrAutoSpace() {
        val arbitrator = VoiceTouchInputArbitrator()
        val editor = MockEditorBridge()

        // 1. Type "hello" and press Space 1
        arbitrator.onTypeDirectChar(editor, "hello")
        val now = System.currentTimeMillis()
        arbitrator.onSpacePressed(editor, " ", autoPeriodEnabled = true, nowMs = now)
        assertEquals("hello ", editor.text.toString())

        // Simulate Android EditText asynchronously firing onUpdateSelection after commitText(" ", 1)
        arbitrator.onExternalSelectionUpdated(
            editor = editor,
            oldSelStart = 5,
            oldSelEnd = 5,
            newSelStart = 6,
            newSelEnd = 6,
            candidatesStart = -1,
            candidatesEnd = -1
        )

        // Pressing Space 2 within timeout must still trigger Auto-Period!
        val o2 = arbitrator.onSpacePressed(editor, " ", autoPeriodEnabled = true, nowMs = now + 150L)
        assertEquals(VoiceTouchInputArbitrator.SpaceOutcome.CONVERTED_DOUBLE_SPACE_TO_PERIOD, o2)
        assertEquals("hello. ", editor.text.toString())

        // 2. Swipe word "world", simulate async onUpdateSelection, then tap "a" -> must auto-space
        arbitrator.onCommitSwipeWord(editor, "world")
        arbitrator.onExternalSelectionUpdated(
            editor = editor,
            oldSelStart = 7,
            oldSelEnd = 7,
            newSelStart = 12,
            newSelEnd = 12,
            candidatesStart = -1,
            candidatesEnd = -1
        )
        arbitrator.onTypeDirectChar(editor, "a")
        assertEquals("hello. world a", editor.text.toString())
    }
}
