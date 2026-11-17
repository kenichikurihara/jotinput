package com.example.myasr

import com.example.myasr.context.kanakanji.domain.ConnectionMatrix
import com.example.myasr.context.kanakanji.domain.KanaKanjiEngine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class FlickTypoAndMozcCorrectionEvaluationTest {

    private lateinit var engine: KanaKanjiEngine

    @Before
    fun setUp() {
        engine = KanaKanjiEngine(null)
        val tsvFile = listOf(
            File("src/test/resources/mozc/base_dictionary.tsv"),
            File("app/src/test/resources/mozc/base_dictionary.tsv"),
            File("tools/mozc_dict/base_dictionary.tsv")
        ).firstOrNull { it.exists() }
        requireNotNull(tsvFile) { "base_dictionary.tsv not found for unit test" }
        tsvFile.inputStream().use { stream ->
            engine.initializeWithStream(stream)
        }

        val binFile = listOf(
            File("/tmp/connection_data.bin"),
            File("app/src/main/assets/mozc/connection_data.bin"),
            File("src/main/assets/mozc/connection_data.bin"),
            File("../app/src/main/assets/mozc/connection_data.bin"),
            File("tools/mozc_dict/connection_data.bin"),
            File("../tools/mozc_dict/connection_data.bin")
        ).firstOrNull { it.exists() && it.length() > 1000 }
        if (binFile != null) {
            val matrix = ConnectionMatrix.loadFromBinaryFile(binFile)
            if (matrix != null) {
                engine.setConnectionMatrix(matrix)
            }
        }
    }

    @Test
    fun testFlickTypoWatasaSuggestsWatashi() {
        // User inputs "わたさ" (missed left flick on 'さ', intending 'わたし')
        val candidates = engine.getBaseCandidates("わたさ")
        val surfaces = candidates.map { it.surface }
        assertTrue("Candidates for 'わたさ' should include '私', got: $surfaces",
            surfaces.contains("私"))
        // Both Kanji '私' and corrected Hiragana 'わたし' should be present
        assertTrue("Candidates for 'わたさ' should include corrected Hiragana 'わたし', got: $surfaces",
            surfaces.contains("わたし"))
    }

    @Test
    fun testFlickTypoWatakiSuggestsWatashi() {
        // User inputs "わたき" (adjacent 12-key typo: pressed 'か' column key instead of 'さ' column key)
        val candidates = engine.getBaseCandidates("わたき")
        val surfaces = candidates.map { it.surface }
        assertTrue("Candidates for 'わたき' should include '私', got: $surfaces",
            surfaces.contains("私"))
        assertTrue("Candidates for 'わたき' should include corrected Hiragana 'わたし', got: $surfaces",
            surfaces.contains("わたし"))
    }

    @Test
    fun testMozcReadingCorrectionFuinkiSuggestsFuniki() {
        // Mozc reading_correction: "ふいんき" -> "雰囲気" (correct reading: ふんいき)
        val candidates = engine.getBaseCandidates("ふいんき")
        val surfaces = candidates.map { it.surface }
        assertTrue("Candidates for 'ふいんき' must include '雰囲気', got: $surfaces",
            surfaces.contains("雰囲気"))
        assertEquals("雰囲気", candidates.first().surface)
    }

    @Test
    fun testMozcReadingCorrectionOdosanSuggestsOmiyage() {
        // Mozc reading_correction: "おどさん" -> "お土産" (correct reading: おみやげ)
        val candidates = engine.getBaseCandidates("おどさん")
        val surfaces = candidates.map { it.surface }
        assertTrue("Candidates for 'おどさん' must include 'お土産', got: $surfaces",
            surfaces.contains("お土産"))
    }

    @Test
    fun testMozcReadingCorrectionIchiyouSuggestsIchiou() {
        // Mozc reading_correction: "いちよう" -> "一応" (correct reading: いちおう)
        val candidates = engine.getBaseCandidates("いちよう")
        val surfaces = candidates.map { it.surface }
        assertTrue("Candidates for 'いちよう' must include '一応', got: $surfaces",
            surfaces.contains("一応"))
    }

    @Test
    fun testShortPhraseIenoSuggestsIenoExact() {
        // "いえの" -> "家の" should be top 1 candidate, not pushed down by "家の外" etc.
        val candidates = engine.getBaseCandidates("いえの")
        assertTrue("Candidates for 'いえの' should not be empty", candidates.isNotEmpty())
        assertEquals("家の", candidates.first().surface)
    }

    @Test
    fun testFlickUnmodifiedToukiyoSuggestsTokyo() {
        // User inputs "とうきよ" (unmodified flick: typed uppercase 'よ' instead of small 'ょ', and typed 4 chars of 'とうきょう')
        val candidates = engine.getBaseCandidates("とうきよ")
        val surfaces = candidates.map { it.surface }
        assertTrue("Candidates for 'とうきよ' must include '東京', got: $surfaces",
            surfaces.contains("東京"))
        val tokyoIdx = surfaces.indexOf("東京")
        assertTrue("東京 should be ranked in Top 3 for 'とうきよ', got index: $tokyoIdx in $surfaces",
            tokyoIdx in 0..2)
    }

    @Test
    fun testFlickUnmodifiedChiyotsutoSuggestsChotto() {
        // User inputs "ちよつと" (unmodified flick: uppercase 'よ' and 'つ' instead of small 'ょ' and 'っ')
        val candidates = engine.getBaseCandidates("ちよつと")
        val surfaces = candidates.map { it.surface }
        assertTrue("Candidates for 'ちよつと' must include 'ちょっと', got: $surfaces",
            surfaces.contains("ちょっと"))
    }

    @Test
    fun testFlickUnmodifiedKaikiSuggestsKaigi() {
        // User inputs "かいき" (unmodified flick: unvoiced 'き' instead of voiced 'ぎ')
        val candidates = engine.getBaseCandidates("かいき")
        val surfaces = candidates.map { it.surface }
        assertTrue("Candidates for 'かいき' must include '会議', got: $surfaces",
            surfaces.contains("会議"))
    }
}
