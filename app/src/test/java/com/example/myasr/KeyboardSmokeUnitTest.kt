package com.example.myasr

import com.example.myasr.context.english.infrastructure.EnglishSpellEngine
import com.example.myasr.context.english.infrastructure.FutoSwipeEngine
import com.example.myasr.context.ime.domain.EditorBridge
import com.example.myasr.context.ime.domain.InputConnectionEditorBridge
import com.example.myasr.context.ime.domain.VoiceTouchInputArbitrator
import com.example.myasr.context.ime.presentation.DeleteRepeatAccelerator
import com.example.myasr.context.ime.presentation.FlickPopupGeometry
import com.example.myasr.context.kanakanji.domain.JapaneseKanaUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * Minimum functional smoke test suite verifying core keyboard stability,
 * InputConnection exception resilience, voice/touch arbitration, and uninitialized
 * engine safety before git commits.
 */
class KeyboardSmokeUnitTest {

    /**
     * In-memory mock implementation of [EditorBridge] simulating Android editor behavior.
     */
    private class MockEditorBridge : EditorBridge {
        var committed = StringBuilder()
        var composing = ""
        var selectedText: String? = null
        var isBatchEditing = false

        override fun beginBatchEdit(): Boolean {
            isBatchEditing = true
            return true
        }

        override fun endBatchEdit(): Boolean {
            isBatchEditing = false
            return true
        }

        override fun setComposingText(text: String, newCursorPosition: Int): Boolean {
            composing = text
            return true
        }

        override fun commitText(text: String, newCursorPosition: Int): Boolean {
            committed.append(text)
            composing = ""
            selectedText = null
            return true
        }

        override fun finishComposingText(): Boolean {
            committed.append(composing)
            composing = ""
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            val count = beforeLength.coerceAtLeast(0)
            if (committed.length >= count) {
                committed.delete(committed.length - count, committed.length)
            } else {
                committed.clear()
            }
            return true
        }

        override fun getTextBeforeCursor(length: Int, flags: Int): String {
            val total = committed.toString() + composing
            return total.takeLast(length.coerceAtLeast(0))
        }

        override fun getSelectedText(flags: Int): String? = selectedText
    }

    @Test
    fun testInputConnectionEditorBridgeNullSafety() {
        val nullBridge = InputConnectionEditorBridge { null }
        assertFalse(nullBridge.beginBatchEdit())
        assertFalse(nullBridge.endBatchEdit())
        assertFalse(nullBridge.setComposingText("test", 1))
        assertFalse(nullBridge.commitText("test", 1))
        assertFalse(nullBridge.finishComposingText())
        assertFalse(nullBridge.deleteSurroundingText(1, 0))
        assertEquals("", nullBridge.getTextBeforeCursor(10, 0))
        assertNull(nullBridge.getSelectedText(0))
    }

    @Test
    fun testInputConnectionEditorBridgeExceptionResilience() {
        // Create an InputConnection proxy that throws RuntimeException/DeadObjectException on all calls
        val throwingProxy = Proxy.newProxyInstance(
            android.view.inputmethod.InputConnection::class.java.classLoader,
            arrayOf(android.view.inputmethod.InputConnection::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getTextBeforeCursor" -> throw RuntimeException("Dead IPC connection")
                "deleteSurroundingText" -> throw IndexOutOfBoundsException("Host app cursor corrupted")
                "commitText" -> throw SecurityException("Target window permission revoked")
                else -> throw RuntimeException("Simulated host IPC error")
            }
        } as android.view.inputmethod.InputConnection

        val bridge = InputConnectionEditorBridge { throwingProxy }

        // Must never throw unhandled exceptions or crash the IME process
        assertFalse(bridge.beginBatchEdit())
        assertFalse(bridge.endBatchEdit())
        assertFalse(bridge.setComposingText("test", 1))
        assertFalse(bridge.commitText("test", 1))
        assertFalse(bridge.finishComposingText())
        assertFalse(bridge.deleteSurroundingText(5, 0))
        assertEquals("", bridge.getTextBeforeCursor(10, 0))
        assertNull(bridge.getSelectedText(0))
    }

    @Test
    fun testVoiceTouchInputArbitratorMinimumTypingFlow() {
        val editor = MockEditorBridge()
        val arbitrator = VoiceTouchInputArbitrator()

        // 1. Type Japanese Kana characters
        arbitrator.onTypeKana(editor, "あ")
        arbitrator.onTypeKana(editor, "り")
        arbitrator.onTypeKana(editor, "か")
        assertEquals("ありか", arbitrator.composingText.toString())
        assertTrue(arbitrator.isKeyboardComposing())

        // 2. Modify trailing char with dakuten: "か" -> "が"
        val modified = arbitrator.onModifierPressed(editor, JapaneseKanaUtils.FlickDirection.CENTER)
        assertTrue(modified)
        assertEquals("ありが", arbitrator.composingText.toString())

        // 3. Delete single char
        val outcomeSingleDel = arbitrator.onDeletePressed(editor, count = 1)
        assertEquals(VoiceTouchInputArbitrator.DeleteOutcome.DELETED_FROM_KEYBOARD_COMPOSING_STILL_ACTIVE, outcomeSingleDel)
        assertEquals("あり", arbitrator.composingText.toString())

        // 4. Batch delete remaining chars
        val outcomeBatchDel = arbitrator.onDeletePressed(editor, count = 5)
        assertEquals(VoiceTouchInputArbitrator.DeleteOutcome.DELETED_FROM_KEYBOARD_COMPOSING_NOW_EMPTY, outcomeBatchDel)
        assertEquals("", arbitrator.composingText.toString())
        assertFalse(arbitrator.isKeyboardComposing())

        // 5. Direct char typing (QWERTY English mode)
        arbitrator.onTypeDirectChar(editor, "h")
        arbitrator.onTypeDirectChar(editor, "i")
        assertEquals("hi", editor.committed.toString())

        // 6. Swipe word input with auto-spacing
        arbitrator.onCommitSwipeWord(editor, "there", autoSpaceEnabled = true)
        assertEquals("hi there", editor.committed.toString())

        // 7. DEL after swipe word deletes entire swipe word in 1 tap
        val swipeDelOutcome = arbitrator.onDeletePressed(editor, count = 1)
        assertEquals(VoiceTouchInputArbitrator.DeleteOutcome.DELETED_ENTIRE_SWIPE_WORD, swipeDelOutcome)
        assertEquals("hi ", editor.committed.toString())
    }

    @Test
    fun testDeleteRepeatAcceleratorStages() {
        // Initial tap/repeat (0ms): stage 1 (1 char / tick, 50ms)
        val step0 = DeleteRepeatAccelerator.stepForElapsedMs(0L)
        assertEquals(1, step0.deleteCount)
        assertEquals(50L, step0.delayMs)

        // Stage 2 (500ms): 2 chars / tick, 40ms
        val step500 = DeleteRepeatAccelerator.stepForElapsedMs(500L)
        assertEquals(2, step500.deleteCount)
        assertEquals(40L, step500.delayMs)

        // Stage 3 (1200ms): 4 chars / tick, 35ms
        val step1200 = DeleteRepeatAccelerator.stepForElapsedMs(1200L)
        assertEquals(4, step1200.deleteCount)
        assertEquals(35L, step1200.delayMs)

        // Stage 4 (2200ms+): 8 chars / tick, 30ms
        val step3000 = DeleteRepeatAccelerator.stepForElapsedMs(3000L)
        assertEquals(8, step3000.deleteCount)
        assertEquals(30L, step3000.delayMs)
    }

    @Test
    fun testFlickPopupGeometryCalculations() {
        // Center of screen
        val specCenter = FlickPopupGeometry.compute(
            keyCenterX = 540f,
            keyCenterY = 400f,
            rowHeight = 120f,
            density = 2.75f,
            containerWidthPx = 1080
        )
        assertNotNull(specCenter)
        assertTrue(specCenter.popupSizePx > 0)
        assertTrue(specCenter.popupX >= 0)
        assertEquals(5, specCenter.petals.size)

        // Left edge of screen (popupX clamped to 0)
        val specLeft = FlickPopupGeometry.compute(
            keyCenterX = 20f,
            keyCenterY = 100f,
            rowHeight = 100f,
            density = 2.0f,
            containerWidthPx = 1080
        )
        assertEquals(0, specLeft.popupX)

        // Right edge of screen (popupX clamped to containerWidthPx - popupSizePx)
        val specRight = FlickPopupGeometry.compute(
            keyCenterX = 1060f,
            keyCenterY = 100f,
            rowHeight = 100f,
            density = 2.0f,
            containerWidthPx = 1080
        )
        assertTrue(specRight.popupX <= 1080 - specRight.popupSizePx)
    }

    @Test
    fun testFlickStringMappings() {
        // 'あ'行
        assertEquals("あ", JapaneseKanaUtils.getFlickString(0, JapaneseKanaUtils.FlickDirection.CENTER, JapaneseKanaUtils.KeyboardMode.KANA))
        assertEquals("い", JapaneseKanaUtils.getFlickString(0, JapaneseKanaUtils.FlickDirection.LEFT, JapaneseKanaUtils.KeyboardMode.KANA))
        assertEquals("う", JapaneseKanaUtils.getFlickString(0, JapaneseKanaUtils.FlickDirection.UP, JapaneseKanaUtils.KeyboardMode.KANA))
        assertEquals("え", JapaneseKanaUtils.getFlickString(0, JapaneseKanaUtils.FlickDirection.RIGHT, JapaneseKanaUtils.KeyboardMode.KANA))
        assertEquals("お", JapaneseKanaUtils.getFlickString(0, JapaneseKanaUtils.FlickDirection.DOWN, JapaneseKanaUtils.KeyboardMode.KANA))

        // Punctuation key (index 11)
        assertEquals("、", JapaneseKanaUtils.getFlickString(11, JapaneseKanaUtils.FlickDirection.CENTER, JapaneseKanaUtils.KeyboardMode.KANA))
        assertEquals("。", JapaneseKanaUtils.getFlickString(11, JapaneseKanaUtils.FlickDirection.LEFT, JapaneseKanaUtils.KeyboardMode.KANA))
        assertEquals("？", JapaneseKanaUtils.getFlickString(11, JapaneseKanaUtils.FlickDirection.UP, JapaneseKanaUtils.KeyboardMode.KANA))
        assertEquals("！", JapaneseKanaUtils.getFlickString(11, JapaneseKanaUtils.FlickDirection.RIGHT, JapaneseKanaUtils.KeyboardMode.KANA))
        assertEquals("…", JapaneseKanaUtils.getFlickString(11, JapaneseKanaUtils.FlickDirection.DOWN, JapaneseKanaUtils.KeyboardMode.KANA))

        // Negative index returns empty string safely without IndexOutOfBoundsException
        assertEquals("", JapaneseKanaUtils.getFlickString(-1, JapaneseKanaUtils.FlickDirection.CENTER, JapaneseKanaUtils.KeyboardMode.KANA))
        assertEquals("", JapaneseKanaUtils.getFlickString(99, JapaneseKanaUtils.FlickDirection.CENTER, JapaneseKanaUtils.KeyboardMode.KANA))
    }

    @Test
    fun testAsrAppInstanceOrNullSafeAccess() {
        // When running in local JVM unit tests where AsrApp.onCreate() hasn't run,
        // instanceOrNull must return null and never throw UninitializedPropertyAccessException.
        val app = AsrApp.instanceOrNull
        assertNull(app)
    }
}
