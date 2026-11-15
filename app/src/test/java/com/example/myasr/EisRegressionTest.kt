package com.example.myasr

import com.example.myasr.context.kanakanji.domain.ConversionCandidate
import com.example.myasr.context.kanakanji.domain.KanaKanjiEngine
import com.example.myasr.context.kanakanji.domain.NextPhrasePredictor
import com.example.myasr.context.vocabulary.domain.DictionaryEntry
import com.example.myasr.context.vocabulary.domain.Priority
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.InputStreamReader

/**
 * Phase 2 EIS (Effective Intended-text Speed) JVM Engine Regression Test.
 *
 * Formally evaluates the live JVM KanaKanjiEngine and NextPhrasePredictor
 * against the top-level North Star metric (docs/prd_top_level.md):
 * 1. Predictive candidate keystroke reduction and EIS acceleration (CUJ-2).
 * 2. Zero-query next-phrase continuation acceleration (CUJ-2).
 * 3. User dictionary priority and break-even amortisation (CUJ-3).
 * 4. Mathematical harmonic mean and invariant consistency.
 */
class EisRegressionTest {

    private lateinit var goldenJson: JSONObject
    private lateinit var engine: KanaKanjiEngine
    private lateinit var predictor: NextPhrasePredictor

    // Official KLM-v1 parameters (eval/eis/klm.py & docs/prd_top_level.md §5.3)
    private val kFlick = 0.40       // seconds per flick keystroke
    private val c1 = 0.40           // seconds to select rank 1
    private val cStep = 0.30        // seconds for each subsequent rank in candidate strip (2..5)
    private val cNoneBase = 1.50    // seconds penalty when absent from candidates
    private val registerCost = 12.0 // seconds to register a custom dictionary entry

    @Before
    fun setUp() {
        val stream = javaClass.classLoader?.getResourceAsStream("prediction_eval/reference_bigram_prediction_golden_set.json")
        assertNotNull("Golden set resource must exist", stream)
        val content = InputStreamReader(stream!!, Charsets.UTF_8).readText()
        goldenJson = JSONObject(content)

        predictor = NextPhrasePredictor(null)
        engine = KanaKanjiEngine(null)

        val tsvFile = listOf(
            File("app/src/test/resources/mozc/base_dictionary.tsv"),
            File("src/test/resources/mozc/base_dictionary.tsv"),
            File("tools/mozc_dict/base_dictionary.tsv"),
            File("../tools/mozc_dict/base_dictionary.tsv")
        ).firstOrNull { it.exists() }
        requireNotNull(tsvFile) { "base_dictionary.tsv required for KanaKanjiEngine" }
        tsvFile.inputStream().use { s ->
            engine.initializeWithStream(s)
        }
    }

    private fun estKanaKeystrokes(reading: String): Double {
        var strokes = 0.0
        val voicedChars = "がぎぐげござじずぜぞだぢづでどばびぶべぼぱぴぷぺぽガギグゲゴザジズゼゾダヂヅデドバビブベボパピプペポ"
        for (ch in reading) {
            strokes += if (ch in voicedChars) 1.3 else 1.0
        }
        return strokes
    }

    private fun selectCost(rank: Int?): Double {
        if (rank == null || rank <= 0) return cNoneBase + 2.0 * c1
        return if (rank == 1) c1 else c1 + cStep * (rank - 1)
    }

    @Test
    fun testPredictiveFlickTypingReducesKeystrokesAndBoostsEis() {
        val jaCases = goldenJson.getJSONArray("japanese")
        val numCases = jaCases.length()
        assertTrue("Must have at least 20 test cases", numCases >= 20)

        var totalChars = 0
        var totalFullKeys = 0.0
        var totalFullTime = 0.0

        var totalPredKeys = 0.0
        var totalPredTime = 0.0
        var predictiveHits = 0

        for (i in 0 until numCases) {
            val caseObj = jaCases.getJSONObject(i)
            val prevWord = caseObj.getString("prev_word")
            val targetWord = caseObj.getString("target_word")
            val targetReading = caseObj.getString("target_reading")

            val oneQuery = caseObj.getJSONObject("one_char").getString("query")
            val twoQuery = caseObj.getJSONObject("two_char").getString("query")

            val chars = targetWord.length
            totalChars += chars

            // Baseline: Full typing without predictions
            val fullKeys = estKanaKeystrokes(targetReading)
            totalFullKeys += fullKeys
            totalFullTime += (kFlick * fullKeys) + c1 // full keystrokes + 1-tap commit

            // Predictive Strategy (Simulate active user interaction):
            // 1. Check 0-char prediction (after prevWord)
            val zeroPreds = predictor.predict(prevWord, textBeforeCursor = prevWord, suggestPeriod = false)
            val zeroRank = zeroPreds.take(3).indexOfFirst { it.surface == targetWord }

            if (zeroRank >= 0) {
                totalPredTime += selectCost(zeroRank + 1)
                // 0 keystrokes used!
                predictiveHits++
                continue
            }

            // 2. Check 1-char prefix query
            val onePreds = engine.getBaseCandidates(oneQuery)
            val oneRank = onePreds.take(3).indexOfFirst { it.surface == targetWord }

            if (oneRank >= 0) {
                val qKeys = estKanaKeystrokes(oneQuery)
                totalPredKeys += qKeys
                totalPredTime += (kFlick * qKeys) + selectCost(oneRank + 1)
                predictiveHits++
                continue
            }

            // 3. Check 2-char prefix query
            val twoPreds = engine.getBaseCandidates(twoQuery)
            val twoRank = twoPreds.take(3).indexOfFirst { it.surface == targetWord }

            if (twoRank >= 0) {
                val qKeys = estKanaKeystrokes(twoQuery)
                totalPredKeys += qKeys
                totalPredTime += (kFlick * qKeys) + selectCost(twoRank + 1)
                predictiveHits++
                continue
            }

            // 4. Fallback to full typing
            totalPredKeys += fullKeys
            totalPredTime += (kFlick * fullKeys) + selectCost(2)
        }

        val eisBase = 60.0 * totalChars / totalFullTime
        val eisPred = 60.0 * totalChars / totalPredTime
        val keyReductionPct = 100.0 * (totalFullKeys - totalPredKeys) / totalFullKeys
        val hitRatePct = 100.0 * predictiveHits / numCases

        println("[EIS Phase 2 Benchmark] CUJ-2 (Flick Japanese):")
        println("  Total Cases: $numCases | Total Intended Chars: $totalChars")
        println("  Baseline (Full Typing): EIS = ${String.format("%.1f", eisBase)} chars/min (Keys: ${String.format("%.1f", totalFullKeys)})")
        println("  Predictive Strategy   : EIS = ${String.format("%.1f", eisPred)} chars/min (Keys: ${String.format("%.1f", totalPredKeys)})")
        println("  Keystroke Reduction   : ${String.format("%.1f", keyReductionPct)}% (Predictive Hit Rate: ${String.format("%.1f", hitRatePct)}%)")

        // Invariant and Non-Regression Assertions
        assertTrue("Predictive EIS ($eisPred) must be strictly greater than baseline ($eisBase)", eisPred > eisBase)
        assertTrue("Keystroke reduction ($keyReductionPct%) must exceed 35%", keyReductionPct >= 35.0)
        assertTrue("Predictive hit rate ($hitRatePct%) must exceed 50%", hitRatePct >= 50.0)
        assertTrue("Predictive EIS must exceed 110 chars/min", eisPred >= 110.0)
    }

    @Test
    fun testZeroQueryNextPhrasePredictionAcceleratesWarmTyping() {
        // Record frequent conversational bigrams
        predictor.recordTransition("よろしく", "お願いします")
        predictor.recordTransition("お疲れ様", "でした")
        predictor.recordTransition("確認", "いたしました")

        val testCases = listOf(
            Triple("よろしく", "お願いします", 5),
            Triple("お疲れ様", "でした", 3),
            Triple("確認", "いたしました", 6)
        )

        var totalChars = 0
        var totalTime = 0.0

        for ((prev, target, chars) in testCases) {
            val preds = predictor.predict(prev, textBeforeCursor = prev, suggestPeriod = false)
            val rank = preds.take(3).indexOfFirst { it.surface == target }
            assertTrue("Target '$target' must be predicted after '$prev'", rank >= 0)

            totalChars += chars
            // Zero-query: 0 keystrokes! Time is solely candidate selection cost
            totalTime += selectCost(rank + 1)
        }

        val zeroQueryEis = 60.0 * totalChars / totalTime
        println("  Warm Session Zero-Query EIS: ${String.format("%.1f", zeroQueryEis)} chars/min across $totalChars characters in ${String.format("%.2f", totalTime)}s")

        // Zero keystroke typing achieves > 300 chars/min
        assertTrue("Zero-query EIS must exceed 250 chars/min", zeroQueryEis >= 250.0)
    }

    @Test
    fun testUserDictionaryPriorityAndBreakEvenAmortisation() {
        // Custom dictionary term registration (CUJ-3)
        val customWord = "高輪ゲートウェイ"
        val customReading = "たかなわげーとうぇい"
        engine.syncUserDictionary(listOf(DictionaryEntry(word = customWord, reading = customReading, priority = Priority.HIGH)))

        // User types prefix 'たかな' (3 keys)
        val candidates = engine.getBaseCandidates("たかな")
        val rank = candidates.indexOfFirst { it.surface == customWord }
        assertTrue("Custom word '$customWord' must appear in candidates for 'たかな'", rank >= 0)

        // Time to input without dictionary (user typing 10 kana + multiple candidate conversions)
        val fullKeys = estKanaKeystrokes(customReading)
        val unregTime = (kFlick * fullKeys) + selectCost(null) + (kFlick * customWord.length)

        // Time to input with dictionary (prefix typing 3 keys + rank selection)
        val regTime = (kFlick * 3.0) + selectCost(rank + 1)

        val timeSavedPerUse = unregTime - regTime
        val breakEvenUses = registerCost / timeSavedPerUse

        println("  CUJ-3 Dictionary Break-Even Analysis:")
        println("    Unregistered Time: ${String.format("%.2f", unregTime)}s | Registered Time: ${String.format("%.2f", regTime)}s")
        println("    Time Saved Per Use: ${String.format("%.2f", timeSavedPerUse)}s -> Break-even Reuses: ${String.format("%.1f", breakEvenUses)}")

        assertTrue("Time saved per use must be positive", timeSavedPerUse > 1.0)
        assertTrue("Break-even uses must be under 10 uses", breakEvenUses < 10.0)
    }

    @Test
    fun testHarmonicMeanPurity() {
        // Mathematically verify harmonic mean calculation identity
        val eis1 = 204.4
        val eis2 = 140.0
        val eis3 = 139.0
        val eis4 = 337.7

        val w1 = 0.40
        val w2 = 0.35
        val w3 = 0.15
        val w4 = 0.10

        val globalEis = 1.0 / ((w1 / eis1) + (w2 / eis2) + (w3 / eis3) + (w4 / eis4))

        // Direct total characters / total time equivalence
        val totalChars = 10000.0
        val totalTime = (totalChars * w1 / eis1) +
                        (totalChars * w2 / eis2) +
                        (totalChars * w3 / eis3) +
                        (totalChars * w4 / eis4)
        val expectedEis = 60.0 * (totalChars / (totalTime * 60.0))

        assertEquals("Harmonic mean must strictly equal total chars / total time", expectedEis, globalEis, 0.0001)
    }

    @Test
    fun testPosAwareParticleProtectionAndZeroQueryPredictions() {
        // Step 1: Verify 1-character case particles are preserved at Rank 1 in getBaseCandidates
        val testParticles = listOf("に", "を", "が", "は", "の", "で", "と", "も")
        for (p in testParticles) {
            val cands = engine.getBaseCandidates(p)
            assertTrue("Base candidates for particle '$p' must not be empty", cands.isNotEmpty())
            assertEquals("Particle '$p' must be ranked #1 in candidate list", p, cands.first().surface)
        }

        // Verify POS-aware 0-char prediction for case particles after nouns
        val nounParticlePairs = listOf(
            Pair("資料", "を"),
            Pair("会議", "に"),
            Pair("準備", "が"),
            Pair("今日", "は"),
            Pair("明日", "の"),
            Pair("作業", "を"),
            Pair("質問", "は")
        )
        for ((noun, expectedParticle) in nounParticlePairs) {
            val preds = predictor.predict(noun, textBeforeCursor = noun, suggestPeriod = false)
            val surfaces = preds.take(3).map { it.surface }
            assertTrue(
                "Expected particle '$expectedParticle' in top-3 suggestions for noun '$noun', was: $surfaces",
                surfaces.contains(expectedParticle)
            )
        }
    }
}
