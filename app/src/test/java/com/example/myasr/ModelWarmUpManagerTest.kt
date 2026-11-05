package com.example.myasr

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ModelWarmUpManagerTest {

    @Test
    fun testKanaKanjiEngineWarmUpExecution() {
        val tsvFile = listOf(
            File("src/test/resources/mozc/base_dictionary.tsv"),
            File("app/src/test/resources/mozc/base_dictionary.tsv"),
            File("tools/mozc_dict/base_dictionary.tsv"),
            File("../tools/mozc_dict/base_dictionary.tsv"),
            File("src/main/assets/mozc/base_dictionary.tsv"),
            File("app/src/main/assets/mozc/base_dictionary.tsv")
        ).firstOrNull { it.exists() }
        requireNotNull(tsvFile) { "base_dictionary.tsv not found for test" }

        val engine = KanaKanjiEngine(null)
        tsvFile.inputStream().use { stream ->
            engine.initializeWithStream(stream)
        }

        // Test warmUp invocation
        engine.warmUp()

        val cands = engine.getBaseCandidates("きょう")
        assertTrue("Base candidates for 'きょう' should not be empty after warm-up", cands.isNotEmpty())
    }

    @Test
    fun testModelWarmUpManagerStatusEnums() {
        val idleStatus = ModelWarmUpManager.Status.IDLE
        val warmingStatus = ModelWarmUpManager.Status.WARMING
        val readyStatus = ModelWarmUpManager.Status.READY
        val failedStatus = ModelWarmUpManager.Status.FAILED

        assertNotNull(idleStatus)
        assertNotNull(warmingStatus)
        assertNotNull(readyStatus)
        assertNotNull(failedStatus)
    }
}
