package com.example.myasr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class KanaKanjiEngineInakaTest {

    private lateinit var engine: KanaKanjiEngine

    @Before
    fun setUp() {
        val tsvFile = listOf(
            File("tools/mozc_dict/base_dictionary.tsv"),
            File("../tools/mozc_dict/base_dictionary.tsv")
        ).firstOrNull { it.exists() }
        requireNotNull(tsvFile) { "base_dictionary.tsv not found for test" }

        engine = KanaKanjiEngine(null)
        tsvFile.inputStream().use { stream ->
            engine.initializeWithStream(stream)
        }

        val binFile = listOf(
            File("/tmp/connection_data.bin"),
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
    fun testInakaConversion() {
        val candidates = engine.getBaseCandidates("いなか")
        assertTrue("Candidates for 'いなか' must not be empty", candidates.isNotEmpty())
        val inakaCandidate = candidates.firstOrNull { it.surface == "田舎" }
        requireNotNull(inakaCandidate) { "Candidates must contain '田舎': ${candidates.map { it.surface }}" }
        assertEquals("田舎", candidates[0].surface)
    }

    @Test
    fun testLongPhraseInakaConversion() {
        val candidates = engine.getBaseCandidates("いなかだよ")
        assertTrue("Candidates must not be empty", candidates.isNotEmpty())
        val inakaCandidate = candidates.firstOrNull { it.surface.contains("田舎") }
        requireNotNull(inakaCandidate) { "Candidates must contain '田舎': ${candidates.map { it.surface }}" }
    }
}
