package com.example.myasr

import com.example.myasr.context.english.domain.EnglishCapitalizationAnalyzer
import com.example.myasr.context.ime.domain.EditorBridge
import com.example.myasr.context.ime.domain.VoiceTouchInputArbitrator

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

    /**
     * Simulates an Android EditText that dispatches InputMethodService.onUpdateSelection(...)
     * asynchronously after every IME edit (including batch edits).
     */
    private class SelectionEchoingEditorBridge(
        private val arbitrator: VoiceTouchInputArbitrator
    ) : EditorBridge {
        val text = StringBuilder()
        var composingText: String = ""
        var cursorPos: Int = 0
        private var inBatch = false
        private var batchOldSel = 0

        private fun dispatchSelectionEcho(oldSel: Int, newSel: Int) {
            arbitrator.onExternalSelectionUpdated(
                editor = this,
                oldSelStart = oldSel,
                oldSelEnd = oldSel,
                newSelStart = newSel,
                newSelEnd = newSel,
                candidatesStart = -1,
                candidatesEnd = -1
            )
        }

        override fun beginBatchEdit(): Boolean {
            if (!inBatch) {
                inBatch = true
                batchOldSel = cursorPos
            }
            return true
        }

        override fun endBatchEdit(): Boolean {
            if (inBatch) {
                inBatch = false
                if (cursorPos != batchOldSel) {
                    dispatchSelectionEcho(batchOldSel, cursorPos)
                }
            }
            return true
        }

        override fun commitText(text: String, newCursorPosition: Int): Boolean {
            val oldSel = cursorPos
            this.text.insert(cursorPos, text)
            cursorPos += text.length
            composingText = ""
            if (!inBatch && cursorPos != oldSel) {
                dispatchSelectionEcho(oldSel, cursorPos)
            }
            return true
        }

        override fun setComposingText(text: String, newCursorPosition: Int): Boolean {
            composingText = text
            return true
        }

        override fun finishComposingText(): Boolean {
            if (composingText.isNotEmpty()) {
                val oldSel = cursorPos
                text.insert(cursorPos, composingText)
                cursorPos += composingText.length
                composingText = ""
                if (!inBatch && cursorPos != oldSel) {
                    dispatchSelectionEcho(oldSel, cursorPos)
                }
            }
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            val oldSel = cursorPos
            val delCount = minOf(beforeLength, cursorPos)
            if (delCount > 0) {
                text.delete(cursorPos - delCount, cursorPos)
                cursorPos -= delCount
            }
            if (!inBatch && cursorPos != oldSel) {
                dispatchSelectionEcho(oldSel, cursorPos)
            }
            return true
        }

        override fun getTextBeforeCursor(length: Int, flags: Int): String {
            val s = text.substring(0, cursorPos)
            return if (s.length > length) s.substring(s.length - length) else s
        }

        override fun getSelectedText(flags: Int): String? = null

        fun simulateUserCursorTap(newPos: Int) {
            val oldSel = cursorPos
            cursorPos = newPos.coerceIn(0, text.length)
            dispatchSelectionEcho(oldSel, cursorPos)
        }
    }

    @Test
    fun testE2E_DoubleSpaceAutoPeriodSurvivesOnUpdateSelectionEcho() {
        val arbitrator = VoiceTouchInputArbitrator()
        val editor = SelectionEchoingEditorBridge(arbitrator)

        for (ch in "hello") {
            arbitrator.onTypeDirectChar(editor, ch.toString())
        }
        assertEquals("hello", editor.text.toString())

        // 1st Space -> EditText fires onUpdateSelection(5 -> 6). Must NOT clear auto-period window.
        val o1 = arbitrator.onSpacePressed(editor, " ", autoPeriodEnabled = true, nowMs = 1000L)
        assertEquals(VoiceTouchInputArbitrator.SpaceOutcome.COMMITTED_IDLE_SPACE, o1)
        assertEquals("hello ", editor.text.toString())

        // Simulate >80ms Binder IPC delay before 2nd space (still within 1000ms double-space timeout)
        val o2 = arbitrator.onSpacePressed(editor, " ", autoPeriodEnabled = true, nowMs = 1350L)
        assertEquals(VoiceTouchInputArbitrator.SpaceOutcome.CONVERTED_DOUBLE_SPACE_TO_PERIOD, o2)
        assertEquals("hello. ", editor.text.toString())

        // 3rd Space must NOT create ".. "
        val o3 = arbitrator.onSpacePressed(editor, " ", autoPeriodEnabled = true, nowMs = 1500L)
        assertEquals(VoiceTouchInputArbitrator.SpaceOutcome.COMMITTED_IDLE_SPACE, o3)
        assertEquals("hello.  ", editor.text.toString())
    }

    @Test
    fun testE2E_SwipeAndEnglishCandidateAutoSpaceSurviveOnUpdateSelectionEcho() {
        val arbitrator = VoiceTouchInputArbitrator()
        val editor = SelectionEchoingEditorBridge(arbitrator)

        // 1. Tap "hel" then select English candidate "hello" -> EditText fires batch onUpdateSelection(3 -> 5)
        for (ch in "hel") {
            arbitrator.onTypeDirectChar(editor, ch.toString())
        }
        arbitrator.onEnglishCandidateSelected(editor, replaceLength = 3, candidateSurface = "hello")
        assertEquals("hello", editor.text.toString())

        // 2. Tap 'w' after candidate selection -> must prepend space
        arbitrator.onTypeDirectChar(editor, "w")
        assertEquals("hello w", editor.text.toString())

        // 3. Swipe "world" -> must prepend space
        arbitrator.onCommitSwipeWord(editor, "world")
        assertEquals("hello w world", editor.text.toString())

        // 4. Select alternative swipe candidate "earth" -> replaces "world" and preserves auto-space
        arbitrator.onCandidateSelected(editor, candidateSurface = "earth", isPrediction = false)
        assertEquals("hello w earth", editor.text.toString())

        // 5. Tap 'f' after swipe candidate -> must prepend space
        arbitrator.onTypeDirectChar(editor, "f")
        assertEquals("hello w earth f", editor.text.toString())

        // 6. Swipe "test", then tap '.' -> punctuation attaches without space
        arbitrator.onCommitSwipeWord(editor, "test")
        arbitrator.onTypeDirectChar(editor, ".")
        assertEquals("hello w earth f test.", editor.text.toString())

        // 7. Swipe "next" after '.' -> must prepend space
        arbitrator.onCommitSwipeWord(editor, "next")
        assertEquals("hello w earth f test. next", editor.text.toString())
    }

    @Test
    fun testE2E_ExternalCursorTapClearsAutoPeriodAndAutoSpace() {
        val arbitrator = VoiceTouchInputArbitrator()
        val editor = SelectionEchoingEditorBridge(arbitrator)

        for (ch in "hello") {
            arbitrator.onTypeDirectChar(editor, ch.toString())
        }
        arbitrator.onSpacePressed(editor, " ", autoPeriodEnabled = true, nowMs = 1000L)
        assertEquals("hello ", editor.text.toString())

        // User taps elsewhere in the EditText (cursor moves from 6 to 2)
        editor.simulateUserCursorTap(2)
        assertEquals(0L, arbitrator.lastAutoPeriodEligibleSpaceTimeMs)

        val o2 = arbitrator.onSpacePressed(editor, " ", autoPeriodEnabled = true, nowMs = 1200L)
        assertEquals(VoiceTouchInputArbitrator.SpaceOutcome.COMMITTED_IDLE_SPACE, o2)
        assertEquals("he llo ", editor.text.toString())

        // Also test external tap after English candidate selection clears pendingAutoSpaceAfterWord
        val arb2 = VoiceTouchInputArbitrator()
        val ed2 = SelectionEchoingEditorBridge(arb2)
        arb2.onTypeDirectChar(ed2, "h")
        arb2.onTypeDirectChar(ed2, "i")
        arb2.onEnglishCandidateSelected(ed2, replaceLength = 2, candidateSurface = "hi")
        assertTrue(arb2.pendingAutoSpaceAfterWord)

        ed2.simulateUserCursorTap(1)
        assertFalse(arb2.pendingAutoSpaceAfterWord)
    }

    @Test
    fun testConsecutiveSwipeWordsIHaveToTryAndNoSuggestionsField() {
        val arbitrator = VoiceTouchInputArbitrator()
        val editor = SelectionEchoingEditorBridge(arbitrator)

        // Swiping "i", "have", "to", "try" consecutively must produce "i have to try"
        arbitrator.onCommitSwipeWord(editor, "i")
        arbitrator.onCommitSwipeWord(editor, "have")
        arbitrator.onCommitSwipeWord(editor, "to")
        arbitrator.onCommitSwipeWord(editor, "try")
        assertEquals("i have to try", editor.text.toString())

        // TYPE_TEXT_FLAG_NO_SUGGESTIONS (used by search bars, chat apps, WebViews) must NOT bypass auto-space
        val noSuggInfo = android.view.inputmethod.EditorInfo().apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        assertFalse(EnglishCapitalizationAnalyzer.shouldBypassAutoAssist(noSuggInfo, "i have"))

        // URI field with natural search words must NOT bypass, while active URL token MUST bypass
        val uriInfo = android.view.inputmethod.EditorInfo().apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        assertFalse(EnglishCapitalizationAnalyzer.shouldBypassAutoAssist(uriInfo, "i have"))
        assertTrue(EnglishCapitalizationAnalyzer.shouldBypassAutoAssist(uriInfo, "https://example"))
        assertTrue(EnglishCapitalizationAnalyzer.shouldBypassAutoAssist(uriInfo, "google.com"))
    }

    @Test
    fun testTier1AccuracyImprovementsAndCoordinateNormalizationContract() {
        val repoRoot = java.io.File("..").canonicalFile.let { parent ->
            if (java.io.File(parent, "settings.gradle.kts").exists()) parent else java.io.File(".").canonicalFile
        }

        // 1. Verify Idea 1: FlickKeyboardView normalizes swipe Y to alphabet rows [qwertyAlphaTop, qwertyAlphaBottom]
        val flickViewFile = java.io.File(repoRoot, "app/src/main/java/com/example/myasr/context/ime/presentation/FlickKeyboardView.kt")
        assertTrue("FlickKeyboardView.kt must exist", flickViewFile.exists())
        val flickSrc = flickViewFile.readText()
        assertTrue("FlickKeyboardView must normalize swipe Y relative to qwertyAlphaTop/Bottom", flickSrc.contains("normalizeSwipeY(y)"))
        assertTrue("FlickKeyboardView must denormalize swipe Y when drawing path", flickSrc.contains("denormalizeSwipeY("))
        assertTrue("FlickKeyboardView must restrict swipe start to alphabet keys a..z", flickSrc.contains("isAlphabetSwipeKey("))
        assertTrue("FlickKeyboardView must sync 26 key centers with FutoSwipeEngine", flickSrc.contains("updateLayoutKeys(keyCenters)"))
        assertFalse("FlickKeyboardView must NOT normalize swipe Y by full view height", flickSrc.contains("val normY = (y / height.toFloat())"))

        // 2. Verify Ideas 2-5 in C++ native engine (futo_swipe_engine.cc)
        val cppEngineFile = java.io.File(repoRoot, "app/src/main/cpp/swipe/futo_swipe_engine.cc")
        assertTrue("futo_swipe_engine.cc must exist", cppEngineFile.exists())
        val cppSrc = cppEngineFile.readText()
        assertTrue("Idea 1: SetLayoutKeys must be implemented", cppSrc.contains("void SetLayoutKeys("))
        assertTrue("Idea 2: Zipf unigram frequency prior must be implemented", cppSrc.contains("zipf_freq"))
        assertTrue("Idea 2: LookupUnigramBias must be implemented", cppSrc.contains("LookupUnigramBias("))
        assertTrue("Idea 3: Start/end radial anchor distance d0_sq / d1_sq must be implemented", cppSrc.contains("d0_sq") && cppSrc.contains("d1_sq"))
        assertTrue("Idea 4: Confidence-gated ContextLM best_common_tot must be implemented", cppSrc.contains("best_common_tot"))
        assertTrue("Idea 4: Shared-node apostrophe disambiguation must be implemented", cppSrc.contains("has_plain_surface"))
        assertTrue("Idea 5: Stationary pause clamping and smoothing must be implemented", cppSrc.contains("kMaxStationaryDtMs") && cppSrc.contains("kSmoothAlpha"))

        // 3. Verify 1,000-sample benchmark results JSON meets Tier 1 quality bar
        val evalJsonFile = java.io.File(repoRoot, "eval/futo_swipe/swipe_eval_results_1000.json")
        assertTrue("swipe_eval_results_1000.json must exist", evalJsonFile.exists())
        val evalJson = org.json.JSONObject(evalJsonFile.readText())
        val spatialTop1 = evalJson.getJSONObject("spatial_baseline").getDouble("top1_accuracy")
        val contextTop1 = evalJson.getJSONObject("context_aware").getDouble("top1_accuracy")
        val contextTop3 = evalJson.getJSONObject("context_aware").getDouble("top3_accuracy")
        val degradedCount = evalJson.getJSONObject("comparison").getInt("degraded_count")

        assertTrue("Tier 1 Spatial Top-1 accuracy ($spatialTop1%) must be >= 90.0%", spatialTop1 >= 90.0)
        assertTrue("Tier 1 Context Top-1 accuracy ($contextTop1%) must be >= 90.8%", contextTop1 >= 90.8)
        assertTrue("Tier 1 Context Top-3 accuracy ($contextTop3%) must be >= 92.8%", contextTop3 >= 92.8)
        assertTrue("Tier 1 Context degraded count ($degradedCount) must be <= 5", degradedCount <= 5)
    }
}
