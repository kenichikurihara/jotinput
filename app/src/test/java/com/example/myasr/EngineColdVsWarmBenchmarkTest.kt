package com.example.myasr

import com.example.myasr.context.kanakanji.domain.KanaKanjiEngine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EngineColdVsWarmBenchmarkTest {

    @Test
    fun benchmarkKanaKanjiEngineColdVsWarm() {
        val tsvFile = listOf(
            File("src/test/resources/mozc/base_dictionary.tsv"),
            File("app/src/test/resources/mozc/base_dictionary.tsv"),
            File("tools/mozc_dict/base_dictionary.tsv"),
            File("../tools/mozc_dict/base_dictionary.tsv"),
            File("src/main/assets/mozc/base_dictionary.tsv"),
            File("app/src/main/assets/mozc/base_dictionary.tsv")
        ).firstOrNull { it.exists() }
        requireNotNull(tsvFile) { "base_dictionary.tsv not found for benchmark" }

        val testReadings = listOf(
            "きょうはいいてんき",
            "さぎょうちゅうですので",
            "つとめております",
            "おもっているのに",
            "ちゅうがたばすです"
        )

        // 1. COLD START MEASUREMENT
        // Creates a new uninitialized engine, measures time to load TSV dictionary,
        // construct the entire in-memory Trie, and perform the first conversion query.
        val coldStartNs = System.nanoTime()
        val coldEngine = KanaKanjiEngine(null)
        tsvFile.inputStream().use { stream ->
            coldEngine.initializeWithStream(stream)
        }
        val firstColdResults = coldEngine.getBaseCandidates(testReadings[0])
        val coldTotalMs = (System.nanoTime() - coldStartNs) / 1_000_000.0
        assertTrue("Cold results should not be empty", firstColdResults.isNotEmpty())

        // 2. WARM START MEASUREMENT
        // Using the already initialized and warmed engine, measures the exact same query
        // and subsequent distinct queries.
        val warmLatencies = mutableListOf<Double>()
        for (reading in testReadings) {
            val warmStartNs = System.nanoTime()
            val warmResults = coldEngine.getBaseCandidates(reading)
            val warmMs = (System.nanoTime() - warmStartNs) / 1_000_000.0
            assertTrue(warmResults.isNotEmpty())
            warmLatencies.add(warmMs)
        }
        val avgWarmMs = warmLatencies.average()

        println("=========================================================")
        println("=== KANA-KANJI ENGINE BENCHMARK RESULTS ===")
        println("  Cold Start (Init + 50k TSV Parse + Trie Build + First Query): ${String.format("%.2f", coldTotalMs)} ms")
        println("  Warm Start Query Latency (Average over 5 queries): ${String.format("%.3f", avgWarmMs)} ms")
        for (i in testReadings.indices) {
            println("    Query '${testReadings[i]}': ${String.format("%.3f", warmLatencies[i])} ms")
        }
        val speedup = coldTotalMs / avgWarmMs
        println("  Speedup Factor: ${String.format("%.1f", speedup)}x")
        println("=========================================================")

        assertTrue("Cold start should take longer than warm query", coldTotalMs > avgWarmMs)
    }
}
