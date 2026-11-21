package com.example.myasr

import com.example.myasr.context.kanakanji.domain.ConnectionMatrix
import com.example.myasr.context.kanakanji.domain.KanaKanjiEngine
import com.example.myasr.context.kanakanji.domain.MmapDictionary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class KanaKanjiEngineAsanoTest {

    private lateinit var engine: KanaKanjiEngine

    @Before
    fun setUp() {
        engine = KanaKanjiEngine(null)

        val tsvFile = listOf(
            File("tools/mozc_dict/base_dictionary.tsv"),
            File("../tools/mozc_dict/base_dictionary.tsv"),
            File("app/src/test/resources/mozc/base_dictionary.tsv"),
            File("src/test/resources/mozc/base_dictionary.tsv")
        ).firstOrNull { it.exists() }
        if (tsvFile != null) {
            tsvFile.inputStream().use { stream ->
                engine.initializeWithStream(stream)
            }
        }

        val dictFile = listOf(
            File("tools/mozc_dict/system_dictionary.bin"),
            File("app/src/test/resources/mozc/system_dictionary.bin"),
            File("src/test/resources/mozc/system_dictionary.bin"),
            File("../app/src/test/resources/mozc/system_dictionary.bin"),
            File("../tools/mozc_dict/system_dictionary.bin")
        ).firstOrNull { it.exists() && it.length() > 64 }
        requireNotNull(dictFile) { "system_dictionary.bin not found for test" }
        val mmapDict = MmapDictionary.loadFromFile(dictFile)
        requireNotNull(mmapDict) { "Failed to load system_dictionary.bin" }
        engine.attachMmapDictionaryForTesting("mozc_general", mmapDict)

        val connFile = listOf(
            File("/tmp/connection_data.bin"),
            File("app/src/main/assets/mozc/connection_data.bin"),
            File("src/main/assets/mozc/connection_data.bin"),
            File("../app/src/main/assets/mozc/connection_data.bin"),
            File("tools/mozc_dict/connection_data.bin"),
            File("../tools/mozc_dict/connection_data.bin")
        ).firstOrNull { it.exists() && it.length() > 1000 }
        requireNotNull(connFile) { "connection_data.bin not found for test" }
        val matrix = ConnectionMatrix.loadFromBinaryFile(connFile)
        requireNotNull(matrix) { "Failed to load connection_data.bin" }
        engine.setConnectionMatrix(matrix)
    }

    @Test
    fun testAsanoRanksAsaNoFirstAtSentenceStart() {
        val candidates = engine.getBaseCandidates("あさの", "")
        val surfaces = candidates.map { it.surface }
        assertTrue("Candidates for 'あさの' must include '朝の': $surfaces", "朝の" in surfaces)

        val asaNoIdx = surfaces.indexOf("朝の")
        assertEquals("Expected '朝の' at Rank 1 for sentence-start 'あさの', got: ${surfaces.take(10)}", 0, asaNoIdx)

        val asanogawaIdx = surfaces.indexOf("浅野川")
        if (asanogawaIdx >= 0) {
            assertTrue("'朝の' ($asaNoIdx) must rank above '浅野川' ($asanogawaIdx)", asaNoIdx < asanogawaIdx)
        }
        val manoIdx = surfaces.indexOf("麻野")
        if (manoIdx >= 0) {
            assertTrue("'朝の' ($asaNoIdx) must rank above '麻野' ($manoIdx)", asaNoIdx < manoIdx)
        }
    }

    @Test
    fun testIenoRanksIeNoFirst() {
        val candidates = engine.getBaseCandidates("いえの", "")
        val surfaces = candidates.map { it.surface }
        assertEquals("Expected '家の' at Rank 1 for 'いえの', got: ${surfaces.take(10)}", "家の", surfaces.firstOrNull())
    }

    @Test
    fun testKyouhaRanksKyouHaFirst() {
        val candidates = engine.getBaseCandidates("きょうは", "")
        val surfaces = candidates.map { it.surface }
        assertEquals("Expected '今日は' at Rank 1 for 'きょうは', got: ${surfaces.take(10)}", "今日は", surfaces.firstOrNull())
    }
}
