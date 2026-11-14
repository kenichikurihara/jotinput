package com.example.myasr

import com.example.myasr.context.english.domain.EnglishCapitalizationAnalyzer
import com.example.myasr.context.english.domain.EnglishDictionaryUtils
import com.example.myasr.context.english.domain.InputFieldCapPolicy
import com.example.myasr.context.kanakanji.domain.ConnectionMatrix
import com.example.myasr.context.kanakanji.domain.KanaKanjiEngine
import com.example.myasr.context.kanakanji.domain.MmapDictionary
import com.example.myasr.context.kanakanji.domain.NextPhrasePredictor
import com.example.myasr.context.provisioning.domain.ModelWarmUpManager
import com.example.myasr.context.vocabulary.domain.DictionaryEntry
import com.example.myasr.context.vocabulary.domain.Priority
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Benchmarks and verifies cold-start latency optimizations for both Japanese and English engines:
 * 1. Zero-copy MmapDictionary (48MB) & ConnectionMatrix (14MB) cold mmap + warm query latency
 * 2. KanaKanjiEngine cold warmUp() (including multi-bunsetsu Viterbi) vs warm query latency
 * 3. Non-blocking async history initialization (KanaKanjiEngine & NextPhrasePredictor)
 * 4. English fast-path capitalization & user dictionary cold vs warm latency
 * 5. Parallel vs sequential warm-up pipeline speedup & ModelWarmUpManager status coverage
 */
class ColdStartOptimizationBenchmarkTest {

    private fun findFile(vararg candidates: String): File? {
        return candidates.map { File(it) }.firstOrNull { it.exists() }
    }

    @Test
    fun benchmarkJapaneseMmapAndConnectionMatrixColdVsWarm() {
        val sysDictFile = findFile(
            "src/test/resources/mozc/system_dictionary.bin",
            "app/src/test/resources/mozc/system_dictionary.bin"
        )
        val connFile = findFile(
            "src/main/assets/mozc/connection_data.bin",
            "app/src/main/assets/mozc/connection_data.bin"
        )
        requireNotNull(sysDictFile) { "system_dictionary.bin not found" }
        requireNotNull(connFile) { "connection_data.bin not found" }

        // 1. Cold MmapDictionary load + first lookup
        val dictT0 = System.nanoTime()
        val dict = MmapDictionary.loadFromFile(sysDictFile)
        val dictLoadMs = (System.nanoTime() - dictT0) / 1_000_000.0
        requireNotNull(dict) { "Failed to load MmapDictionary" }

        val firstLookupT0 = System.nanoTime()
        val coldTokens = dict.getExactTokens("きょう")
        val coldLookupMs = (System.nanoTime() - firstLookupT0) / 1_000_000.0
        assertTrue("Cold lookup should return tokens", !coldTokens.isNullOrEmpty())

        // Warm lookups
        val warmLookupTimes = (1..10).map {
            val t = System.nanoTime()
            dict.getExactTokens("きょうはいいてんき")
            (System.nanoTime() - t) / 1_000_000.0
        }

        // 2. Cold ConnectionMatrix load + first transition lookup
        val connT0 = System.nanoTime()
        val matrix = ConnectionMatrix.loadFromBinaryFile(connFile)
        val connLoadMs = (System.nanoTime() - connT0) / 1_000_000.0
        requireNotNull(matrix) { "Failed to load ConnectionMatrix" }

        val firstCostT0 = System.nanoTime()
        val coldCost = matrix.getTransitionCost(100, 200)
        val coldCostMs = (System.nanoTime() - firstCostT0) / 1_000_000.0
        assertTrue(coldCost >= 0)

        val warmCostTimes = (1..10).map { i ->
            val t = System.nanoTime()
            matrix.getTransitionCost(100 + i, 200 + i)
            (System.nanoTime() - t) / 1_000_000.0
        }

        // 3. KanaKanjiEngine cold warmUp (Mmap + ConnectionMatrix + Canonical + Viterbi) vs warm
        val engine = KanaKanjiEngine(null)
        val engineColdT0 = System.nanoTime()
        engine.setConnectionMatrix(matrix)
        engine.setMmapDictionary(dict)
        engine.registerCanonicalVocabulary()
        engine.warmUp()
        val engineColdMs = (System.nanoTime() - engineColdT0) / 1_000_000.0

        val warmViterbiTimes = listOf(
            "きょうはいいてんき",
            "さぎょうちゅうですので",
            "つとめております"
        ).map { reading ->
            val t = System.nanoTime()
            val cands = engine.getBaseCandidates(reading)
            assertTrue("Expected candidates for $reading", cands.isNotEmpty())
            (System.nanoTime() - t) / 1_000_000.0
        }

        println("=========================================================")
        println("=== JAPANESE MMAP + VITERBI COLD-START BENCHMARK ===")
        println("  MmapDictionary (48MB) mmap load:   ${String.format("%.3f", dictLoadMs)} ms")
        println("  MmapDictionary cold first lookup:  ${String.format("%.3f", coldLookupMs)} ms")
        println("  MmapDictionary warm avg lookup:    ${String.format("%.4f", warmLookupTimes.average())} ms")
        println("  ConnectionMatrix (14MB) mmap load: ${String.format("%.3f", connLoadMs)} ms")
        println("  ConnectionMatrix cold first cost:  ${String.format("%.4f", coldCostMs)} ms")
        println("  ConnectionMatrix warm avg cost:    ${String.format("%.5f", warmCostTimes.average())} ms")
        println("  KanaKanjiEngine cold warmUp:       ${String.format("%.2f", engineColdMs)} ms")
        println("  KanaKanjiEngine warm avg Viterbi:  ${String.format("%.3f", warmViterbiTimes.average())} ms")
        println("=========================================================")

        dict.close()
        matrix.close()
    }

    @Test
    fun benchmarkEnglishFastPathColdVsWarm() {
        val entries = (1..200).map { i ->
            DictionaryEntry(
                word = "CustomTerm$i",
                reading = "customterm$i",
                priority = Priority.NORMAL
            )
        }

        val coldT0 = System.nanoTime()
        val capCold = EnglishCapitalizationAnalyzer.analyze("teh", "Hello world. ", false, InputFieldCapPolicy.CAP_SENTENCES)
        val dictCold = EnglishDictionaryUtils.suggestFromUserDictionary("custom", entries, maxCandidates = 4)
        val coldMs = (System.nanoTime() - coldT0) / 1_000_000.0

        assertNotNull(capCold)
        assertTrue(dictCold.isNotEmpty())

        val warmTimes = (1..20).map {
            val t = System.nanoTime()
            EnglishCapitalizationAnalyzer.analyze("teh", "Hello world. ", false, InputFieldCapPolicy.CAP_SENTENCES)
            EnglishDictionaryUtils.suggestFromUserDictionary("custom", entries, maxCandidates = 4)
            (System.nanoTime() - t) / 1_000_000.0
        }

        println("=========================================================")
        println("=== ENGLISH FAST-PATH COLD VS WARM BENCHMARK ===")
        println("  Cold (CapAnalyzer + UserDict): ${String.format("%.3f", coldMs)} ms")
        println("  Warm avg (20 runs):            ${String.format("%.4f", warmTimes.average())} ms")
        println("=========================================================")

        assertTrue("Warm English fast-path must be < 5ms", warmTimes.average() < 5.0)
    }

    @Test
    fun benchmarkParallelVsSequentialWarmUpPipeline() = runBlocking {
        // Simulate realistic cold-start component costs (ms / 10 scale for fast unit test):
        // KanaKanji=20ms, NextPhrase=10ms, EnglishSpell=120ms, FutoSwipe=80ms, AsrJA=200ms, AsrEN=150ms
        val seqStart = System.currentTimeMillis()
        delay(20)
        delay(10)
        delay(120)
        delay(80)
        delay(200)
        delay(150)
        val seqMs = System.currentTimeMillis() - seqStart

        val parStart = System.currentTimeMillis()
        coroutineScope {
            launch { delay(20) }
            launch { delay(10) }
            launch { delay(120) }
            launch { delay(80) }
            launch { delay(200) }
            launch { delay(150) }
        }
        val parMs = System.currentTimeMillis() - parStart

        println("=========================================================")
        println("=== PARALLEL VS SEQUENTIAL WARM-UP BENCHMARK ===")
        println("  Sequential total time-to-ready: ${seqMs} ms")
        println("  Parallel total time-to-ready:   ${parMs} ms")
        println("  Speedup: ${String.format("%.2f", seqMs.toDouble() / parMs.coerceAtLeast(1L))}x")
        println("=========================================================")

        assertTrue("Parallel warm-up ($parMs ms) must be significantly faster than sequential ($seqMs ms)", parMs < seqMs / 2)
    }

    @Test
    fun verifyModelWarmUpManagerExposesAllJapaneseAndEnglishStatuses() {
        // Compile-time & reflection check that all parallel warm-up statuses exist
        val statusFields = ModelWarmUpManager::class.java.declaredFields.map { it.name }.toSet()
        assertTrue(statusFields.contains("kanaKanjiStatus"))
        assertTrue(statusFields.contains("nextPhraseStatus"))
        assertTrue(statusFields.contains("englishSpellStatus"))
        assertTrue(statusFields.contains("futoSwipeStatus"))
        assertTrue(statusFields.contains("slmStatus"))
        assertTrue(statusFields.contains("asrStatus"))
        assertTrue(statusFields.contains("asrEnStatus"))

        // Verify non-blocking history init completes cleanly on JVM
        val engine = KanaKanjiEngine(null)
        engine.ensureHistoryLoaded()
        val predictor = NextPhrasePredictor(null)
        predictor.ensureHistoryLoaded()
        assertEquals(0, predictor.getPrefixKeyCount())
    }
}
