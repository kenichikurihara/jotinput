package com.example.myasr

import com.example.myasr.context.ime.domain.EditorBridge
import com.example.myasr.context.ime.domain.VoiceTouchInputArbitrator
import com.example.myasr.context.kanakanji.domain.ConnectionMatrix
import com.example.myasr.context.kanakanji.domain.KanaKanjiEngine
import com.example.myasr.context.kanakanji.domain.MmapDictionary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class KanaKanjiEngineDiversityTest {

    private lateinit var engine: KanaKanjiEngine

    @Before
    fun setUp() {
        engine = KanaKanjiEngine()
        val stream = javaClass.classLoader?.getResourceAsStream("mozc/base_dictionary.tsv")
        if (stream != null) {
            engine.initializeWithStream(stream)
        } else {
            engine.registerCanonicalVocabulary()
        }

        // Load binary system dictionary (with expanded vocabulary) if present
        val binDict = listOf(
            File("src/test/resources/mozc/system_dictionary.bin"),
            File("app/src/test/resources/mozc/system_dictionary.bin"),
            File("../tools/mozc_dict/system_dictionary.bin"),
            File("tools/mozc_dict/system_dictionary.bin")
        ).firstOrNull { it.exists() && it.length() > 1000 }
        if (binDict != null) {
            val mmap = MmapDictionary.loadFromFile(binDict)
            if (mmap != null) {
                engine.setMmapDictionary(mmap)
            }
        }

        // Load binary connection matrix
        val matrixFile = listOf(
            File("app/src/main/assets/mozc/connection_data.bin"),
            File("src/main/assets/mozc/connection_data.bin"),
            File("../app/src/main/assets/mozc/connection_data.bin"),
            File("tools/mozc_dict/connection_data.bin"),
            File("../tools/mozc_dict/connection_data.bin")
        ).firstOrNull { it.exists() && it.length() > 1000 }
        if (matrixFile != null) {
            val matrix = ConnectionMatrix.loadFromBinaryFile(matrixFile)
            if (matrix != null) {
                engine.setConnectionMatrix(matrix)
            }
        }
    }

    @Test
    fun testKouitekiDeshitaka_stemDiversityAndPrefixCandidates() {
        val candidates = engine.getBaseCandidates("こういてきでしたか")
        assertTrue("Candidates should not be empty for こういてきでしたか", candidates.isNotEmpty())

        val surfaces = candidates.map { it.surface }

        // 1. Stem Diversity: Both "好意的でしたか" and "行為的でしたか" should be present in top candidates
        assertTrue("Expected '好意的でしたか' in top candidates, but was: ${surfaces.take(6)}",
            surfaces.take(6).contains("好意的でしたか"))
        assertTrue("Expected '行為的でしたか' in top candidates, but was: ${surfaces.take(6)}",
            surfaces.take(6).contains("行為的でしたか"))

        // 2. Gboard-style short bunsetsu prefix candidates should be present
        val prefixCands = candidates.filter { it.reading == "こういてき" }
        assertTrue("Short prefix candidate '好意的' should be present",
            prefixCands.any { it.surface == "好意的" })
        assertTrue("Short prefix candidate '行為的' should be present",
            prefixCands.any { it.surface == "行為的" })

        // 3. Suffix noise eradication: No nonsensical suffix phonetic kanji substitution in top 6
        val noiseWords = listOf("でし高", "でした過", "でした可", "でした化")
        for (noise in noiseWords) {
            assertFalse("Noise pattern $noise must NOT be present in top 6, was: ${surfaces.take(6)}",
                surfaces.take(6).any { it.contains(noise) })
        }
    }

    @Test
    fun testSaitouKouki_multiLengthPrefixAnd2DDiversity() {
        val candidates = engine.getBaseCandidates("さいとうこうき")
        assertTrue("Candidates should not be empty for さいとうこうき", candidates.isNotEmpty())

        val surfaces = candidates.map { it.surface }
        val candidateDetails = candidates.map { "${it.surface}(${it.reading},${it.source})" }
        println("Candidates for さいとうこうき:\n" + candidateDetails.joinToString("\n"))

        // 1. Check multi-horizon prefix candidates are present:
        // m = 4 ("さいとう") should have 斎藤, 斉藤, 齋藤, 齊藤
        val m4Cands = candidates.filter { it.reading == "さいとう" }.map { it.surface }
        assertTrue("Expected '斎藤' in m=4 candidates, but was: $m4Cands", m4Cands.contains("斎藤"))
        assertTrue("Expected '斉藤' in m=4 candidates, but was: $m4Cands", m4Cands.contains("斉藤"))
        assertTrue("Expected '齋藤' in m=4 candidates, but was: $m4Cands", m4Cands.contains("齋藤"))
        assertTrue("Expected '齊藤' in m=4 candidates, but was: $m4Cands", m4Cands.contains("齊藤"))

        // m = 6 (k-1, "さいとうこう") should have 再投稿 or 再登校
        val m6Cands = candidates.filter { it.reading == "さいとうこう" }.map { it.surface }
        assertTrue("Expected '再投稿' in m=6 candidates, but was: $m6Cands", m6Cands.contains("再投稿"))

        // 2. Check candidate strip ordering:
        // Top 10 should contain multiple prefix candidates (斎藤, 斉藤, 齋藤, 再投稿)
        val top10Surfaces = surfaces.take(10)
        assertTrue("Expected '斎藤' in top 10, but was: $top10Surfaces", top10Surfaces.contains("斎藤"))
        assertTrue("Expected '斉藤' in top 10, but was: $top10Surfaces", top10Surfaces.contains("斉藤"))
        assertTrue("Expected '齋藤' in top 10, but was: $top10Surfaces", top10Surfaces.contains("齋藤"))
        assertTrue("Expected '再投稿' in top 10, but was: $top10Surfaces", top10Surfaces.contains("再投稿"))

        // 3. Check 2D diversity of full candidates:
        // Full candidates in top 10 should not all end with the same suffix word
        val fullInTop10 = candidates.take(10).filter { it.reading == "さいとうこうき" }.map { it.surface }
        if (fullInTop10.size >= 2) {
            val endsWithKouki = fullInTop10.count { it.endsWith("幸喜") }
            assertTrue("Top full candidates should be 2D-diverse and not all end with '幸喜', was: $fullInTop10",
                endsWithKouki < fullInTop10.size)
        }
    }

    @Test
    fun testPartialPrefixCommit_arbitratorStateTransition() {
        val arbitrator = VoiceTouchInputArbitrator()
        val mockBridge = MockEditorBridge()

        // 1. User types "こういてきでしたか"
        for (ch in "こういてきでしたか") {
            arbitrator.onTypeKana(mockBridge, ch.toString())
        }
        assertEquals("こういてきでしたか", arbitrator.composingText.toString())

        // 2. User taps prefix candidate "好意的" (reading: "こういてき", length 5)
        val isPartial = arbitrator.onCandidateSelected(
            editor = mockBridge,
            candidateSurface = "好意的",
            isPrediction = false,
            candidateReading = "こういてき"
        )

        // 3. Partial prefix commit must return true
        assertTrue("Should be recognized as partial commit", isPartial)

        // 4. Committed text in editor must be "好意的"
        assertEquals("好意的", mockBridge.committedText)

        // 5. Remaining pre-edit reading in composingText must be "でしたか"
        assertEquals("でしたか", arbitrator.composingText.toString())
        assertEquals("でしたか", mockBridge.currentComposing)
    }

    @Test
    fun testIketaPotentialVerbConversion_ranksIketaInTop3() {
        val candidates = engine.getBaseCandidates("いけた")
        assertTrue("Base candidates for いけた should not be empty", candidates.isNotEmpty())
        val surfaces = candidates.map { it.surface }
        assertTrue("Base candidates for いけた should contain '行けた', found: $surfaces", surfaces.contains("行けた"))

        // '行けた' must be within the top 3 base candidates so SLM can score and promote it under context (e.g. ジム)
        val top3 = surfaces.take(3)
        assertTrue("Top 3 base candidates for いけた must contain '行けた', found: $top3", top3.contains("行けた"))
    }

    @Test
    fun testJimuIketaColloquialCompound_ranksJimuIketaInTopCandidates() {
        val candidates = engine.getBaseCandidates("じむいけた")
        val surfaces = candidates.map { it.surface }
        assertTrue(
            "Base candidates for じむいけた must contain 'ジム行けた', found: $surfaces",
            surfaces.contains("ジム行けた")
        )
        // Check predicate metadata on 'ジム行けた'
        val jimuIketa = candidates.firstOrNull { it.surface == "ジム行けた" }
        assertNotNull("Candidate 'ジム行けた' must exist", jimuIketa)
        assertTrue("Candidate 'ジム行けた' should be marked as predicate", jimuIketa!!.isPredicate)
        assertEquals("Predicate tail should be '行けた'", "行けた", jimuIketa.predicateTailSurface)
        assertEquals("First bunsetsu should be 'ジム'", "ジム", jimuIketa.firstBunsetsuSurface)
    }

    @Test
    fun testGenitiveParticleIeno_doesNotRegressToVerb() {
        val candidates = engine.getBaseCandidates("いえの")
        val surfaces = candidates.map { it.surface }
        assertTrue("Candidates should contain nominal '家の', found: $surfaces", surfaces.contains("家の"))
        assertFalse(
            "Candidate for いえの must not rank verbal '言えの' ahead of nominal forms, found: $surfaces",
            surfaces.take(2).contains("言えの")
        )
    }

    private class MockEditorBridge : EditorBridge {
        var committedText: String = ""
        var currentComposing: String = ""

        override fun beginBatchEdit(): Boolean = true
        override fun endBatchEdit(): Boolean = true
        override fun setComposingText(text: String, newCursorPosition: Int): Boolean {
            currentComposing = text
            return true
        }
        override fun commitText(text: String, newCursorPosition: Int): Boolean {
            committedText += text
            currentComposing = ""
            return true
        }
        override fun finishComposingText(): Boolean {
            currentComposing = ""
            return true
        }
        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean = true
        override fun getTextBeforeCursor(length: Int, flags: Int): String = committedText
        override fun getSelectedText(flags: Int): String? = null
    }
}
