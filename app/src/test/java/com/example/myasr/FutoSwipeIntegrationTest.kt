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

        // 3. Commit another swipe word "world"
        arbitrator.onCommitSwipeWord(editor, "world")
        assertEquals("hello.world", editor.text.toString())
        assertTrue(arbitrator.lastCommittedWasSwipe)

        // 4. Consecutive swipe word "again": automatically inserts space between words
        arbitrator.onCommitSwipeWord(editor, "again")
        assertEquals("hello.world again", editor.text.toString())
        assertTrue(arbitrator.lastCommittedWasSwipe)

        // 5. Candidate selection replaces the recent swipe word
        arbitrator.onCandidateSelected(editor, candidateSurface = "ahead", isPrediction = false)
        assertEquals("hello.world ahead", editor.text.toString())
        assertTrue(arbitrator.lastCommittedWasSwipe)

        // 6. Delete key deletes the ENTIRE swipe word rather than just one letter
        val outcome = arbitrator.onDeletePressed(editor)
        assertEquals(VoiceTouchInputArbitrator.DeleteOutcome.DELETED_ENTIRE_SWIPE_WORD, outcome)
        assertEquals("hello.world ", editor.text.toString())
        assertFalse(arbitrator.lastCommittedWasSwipe)

        // 7. Typing key tap (letter/digit) after swipe word inserts a leading space
        arbitrator.onCommitSwipeWord(editor, "foo")
        assertEquals("hello.world foo", editor.text.toString())
        assertTrue(arbitrator.lastCommittedWasSwipe)

        arbitrator.onTypeDirectChar(editor, "b")
        assertEquals("hello.world foo b", editor.text.toString())
        assertFalse(arbitrator.lastCommittedWasSwipe)
    }
}
