package com.example.myasr

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myasr.context.ime.domain.InputConnectionEditorBridge
import com.example.myasr.context.ime.domain.VoiceTouchInputArbitrator
import com.example.myasr.context.ime.presentation.FlickKeyboardView
import com.example.myasr.context.kanakanji.domain.JapaneseKanaUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DelLongPressAccelerationInstrumentedTest {

    @Test
    fun shortTapDeletesOneCharAndLongPressAcceleratesOnArcvm() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext

        lateinit var editText: EditText
        lateinit var keyboardView: FlickKeyboardView
        val arbitrator = VoiceTouchInputArbitrator()
        var delX = 0f
        var delY = 0f

        instrumentation.runOnMainSync {
            editText = EditText(context)
            val initialText = "A".repeat(250)
            editText.setText(initialText)
            editText.setSelection(initialText.length)

            val ic = editText.onCreateInputConnection(EditorInfo())
            val editorBridge = InputConnectionEditorBridge { ic }

            keyboardView = FlickKeyboardView(context).apply {
                listener = object : FlickKeyboardView.OnKeyboardActionListener {
                    override fun onTextChar(text: String) {}
                    override fun onModifierPressed(direction: JapaneseKanaUtils.FlickDirection) {}
                    override fun onDeletePressed(count: Int) {
                        arbitrator.onDeletePressed(editor = editorBridge, count = count)
                    }
                    override fun onSpacePressed() {}
                    override fun onEnterPressed() {}
                    override fun onGlobePressed() {}
                    override fun onDictPressed() {}
                    override fun onModeChanged(mode: JapaneseKanaUtils.KeyboardMode) {}
                }
            }

            keyboardView.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY)
            )
            keyboardView.layout(0, 0, 1080, 720)

            // KANA mode places DELETE on the top-right key (5th column, row 0)
            delX = 1080f * 0.90f
            delY = 720f * 0.125f
        }

        // 1. Short tap (120ms < 350ms): must delete exactly 1 char on ACTION_UP
        val tapDown = SystemClock.uptimeMillis()
        instrumentation.runOnMainSync {
            val downEvent = MotionEvent.obtain(tapDown, tapDown, MotionEvent.ACTION_DOWN, delX, delY, 0)
            keyboardView.onTouchEvent(downEvent)
            downEvent.recycle()
        }
        SystemClock.sleep(120)
        instrumentation.runOnMainSync {
            val upTime = SystemClock.uptimeMillis()
            val upEvent = MotionEvent.obtain(tapDown, upTime, MotionEvent.ACTION_UP, delX, delY, 0)
            keyboardView.onTouchEvent(upEvent)
            upEvent.recycle()
        }
        instrumentation.waitForIdleSync()

        var remainingAfterTap = -1
        instrumentation.runOnMainSync {
            remainingAfterTap = editText.text?.length ?: -1
        }
        assertEquals("Short tap must delete exactly 1 char", 249, remainingAfterTap)

        // 2. Long press (2800ms): must accelerate and delete >= 150 chars (vs ~36 chars at old 65ms/1-char rate)
        val holdDown = SystemClock.uptimeMillis()
        instrumentation.runOnMainSync {
            val downEvent = MotionEvent.obtain(holdDown, holdDown, MotionEvent.ACTION_DOWN, delX, delY, 0)
            keyboardView.onTouchEvent(downEvent)
            downEvent.recycle()
        }
        SystemClock.sleep(2800)
        instrumentation.runOnMainSync {
            val upTime = SystemClock.uptimeMillis()
            val upEvent = MotionEvent.obtain(holdDown, upTime, MotionEvent.ACTION_UP, delX, delY, 0)
            keyboardView.onTouchEvent(upEvent)
            upEvent.recycle()
        }
        instrumentation.waitForIdleSync()

        var remainingAfterHold = -1
        instrumentation.runOnMainSync {
            remainingAfterHold = editText.text?.length ?: -1
        }
        val deletedByHold = remainingAfterTap - remainingAfterHold
        android.util.Log.i(
            "DelAccelTest",
            "ARCVM 2800ms long-press deleted $deletedByHold chars (remaining=$remainingAfterHold)"
        )
        assertTrue(
            "Expected >= 150 chars deleted in 2800ms on ARCVM, but only deleted $deletedByHold",
            deletedByHold >= 150
        )

        // 3. Ensure no trailing deletes occur after ACTION_UP
        SystemClock.sleep(200)
        var remainingAfterRelease = -1
        instrumentation.runOnMainSync {
            remainingAfterRelease = editText.text?.length ?: -1
        }
        assertEquals("No extra chars should be deleted after ACTION_UP", remainingAfterHold, remainingAfterRelease)
    }
}
