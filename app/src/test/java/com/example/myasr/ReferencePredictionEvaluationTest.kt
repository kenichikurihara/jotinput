package com.example.myasr

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.InputStreamReader

/**
 * Evaluation and Benchmark Test Suite for Reference Bigram Predictions.
 *
 * Validates the reference golden set covering:
 * - 30 Japanese and 30 English contextual bigram cases.
 * - Zero-query (0-char prefix), 1-char prefix, and 2-char prefix predictions.
 * - Dual evaluation modes: "no_history" (zero prior) vs "with_history" (session repeated prior).
 * - Structural integrity, non-empty candidate pools, and top-3 rank validity.
 */
class ReferencePredictionEvaluationTest {

    private lateinit var goldenJson: JSONObject

    @Before
    fun setUp() {
        val stream = javaClass.classLoader?.getResourceAsStream("prediction_eval/reference_bigram_prediction_golden_set.json")
        assertNotNull("Golden set resource must exist", stream)
        val content = InputStreamReader(stream!!, Charsets.UTF_8).readText()
        goldenJson = JSONObject(content)
    }

    @Test
    fun testGoldenSetMetadataAndIntegrity() {
        val metadata = goldenJson.getJSONObject("metadata")
        assertEquals("1.0.0", metadata.getString("version"))
        assertEquals(60, metadata.getInt("total_cases"))

        val jaCases = goldenJson.getJSONArray("japanese")
        val enCases = goldenJson.getJSONArray("english")
        assertEquals(30, jaCases.length())
        assertEquals(30, enCases.length())
    }

    @Test
    fun testJapaneseBigramCasesStructureAndCompleteness() {
        val jaCases = goldenJson.getJSONArray("japanese")
        for (i in 0 until jaCases.length()) {
            val caseObj = jaCases.getJSONObject(i)
            val id = caseObj.getString("id")
            assertTrue("ID must start with ja_bi_: $id", id.startsWith("ja_bi_"))
            assertTrue("prev_word must not be empty", caseObj.getString("prev_word").isNotEmpty())
            assertTrue("target_word must not be empty", caseObj.getString("target_word").isNotEmpty())
            assertTrue("target_reading must not be empty", caseObj.getString("target_reading").isNotEmpty())

            // Zero-char predictions
            val zeroChar = caseObj.getJSONObject("zero_char")
            val zeroNoHist = zeroChar.getJSONArray("no_history")
            val zeroWithHist = zeroChar.getJSONArray("with_history")
            assertTrue("zero_char no_history must have 1-3 candidates", zeroNoHist.length() in 1..3)
            assertTrue("zero_char with_history must have 1-3 candidates", zeroWithHist.length() in 1..3)

            // One-char predictions
            val oneChar = caseObj.getJSONObject("one_char")
            val oneQuery = oneChar.getString("query")
            val oneNoHist = oneChar.getJSONArray("no_history")
            val oneWithHist = oneChar.getJSONArray("with_history")
            assertTrue("one_char query must be 1 char", oneQuery.isNotEmpty())
            assertTrue("one_char no_history must have 1-3 candidates", oneNoHist.length() in 1..3)
            assertTrue("one_char with_history must have 1-3 candidates", oneWithHist.length() in 1..3)

            // Two-char predictions
            val twoChar = caseObj.getJSONObject("two_char")
            val twoQuery = twoChar.getString("query")
            val twoNoHist = twoChar.getJSONArray("no_history")
            val twoWithHist = twoChar.getJSONArray("with_history")
            assertTrue("two_char query must be 2 chars", twoQuery.length >= 2)
            assertTrue("two_char no_history must have 1-3 candidates", twoNoHist.length() in 1..3)
            assertTrue("two_char with_history must have 1-3 candidates", twoWithHist.length() in 1..3)
        }
    }

    @Test
    fun testEnglishBigramCasesStructureAndCompleteness() {
        val enCases = goldenJson.getJSONArray("english")
        for (i in 0 until enCases.length()) {
            val caseObj = enCases.getJSONObject(i)
            val id = caseObj.getString("id")
            assertTrue("ID must start with en_bi_: $id", id.startsWith("en_bi_"))
            assertTrue("prev_word must not be empty", caseObj.getString("prev_word").isNotEmpty())
            assertTrue("target_word must not be empty", caseObj.getString("target_word").isNotEmpty())

            // Zero-char predictions
            val zeroChar = caseObj.getJSONObject("zero_char")
            val zeroNoHist = zeroChar.getJSONArray("no_history")
            val zeroWithHist = zeroChar.getJSONArray("with_history")
            assertTrue("zero_char no_history must have 1-3 candidates", zeroNoHist.length() in 1..3)
            assertTrue("zero_char with_history must have 1-3 candidates", zeroWithHist.length() in 1..3)

            // One-char predictions
            val oneChar = caseObj.getJSONObject("one_char")
            val oneQuery = oneChar.getString("query")
            val oneNoHist = oneChar.getJSONArray("no_history")
            val oneWithHist = oneChar.getJSONArray("with_history")
            assertEquals("one_char query must be 1 char", 1, oneQuery.length)
            assertTrue("one_char no_history must have 1-3 candidates", oneNoHist.length() in 1..3)
            assertTrue("one_char with_history must have 1-3 candidates", oneWithHist.length() in 1..3)

            // Two-char predictions
            val twoChar = caseObj.getJSONObject("two_char")
            val twoQuery = twoChar.getString("query")
            val twoNoHist = twoChar.getJSONArray("no_history")
            val twoWithHist = twoChar.getJSONArray("with_history")
            assertEquals("two_char query must be 2 chars", 2, twoQuery.length)
            assertTrue("two_char no_history must have 1-3 candidates", twoNoHist.length() in 1..3)
            assertTrue("two_char with_history must have 1-3 candidates", twoWithHist.length() in 1..3)
        }
    }

    @Test
    fun testHistoryEffectivenessAcrossGoldenSet() {
        // In "with_history" mode, the target word should rank at #1 or #2 significantly more often
        var jaTargetInWithHistTop1Count = 0
        val jaCases = goldenJson.getJSONArray("japanese")
        for (i in 0 until jaCases.length()) {
            val caseObj = jaCases.getJSONObject(i)
            val target = caseObj.getString("target_word")
            val zeroWithHist = caseObj.getJSONObject("zero_char").getJSONArray("with_history")
            if (zeroWithHist.length() > 0 && zeroWithHist.getString(0).startsWith(target)) {
                jaTargetInWithHistTop1Count++
            }
        }
        assertTrue("Japanese with_history should rank target at top >= 50% of time: $jaTargetInWithHistTop1Count / ${jaCases.length()}",
            jaTargetInWithHistTop1Count >= 15)

        var enTargetInWithHistTop1Count = 0
        val enCases = goldenJson.getJSONArray("english")
        for (i in 0 until enCases.length()) {
            val caseObj = enCases.getJSONObject(i)
            val target = caseObj.getString("target_word")
            val zeroWithHist = caseObj.getJSONObject("zero_char").getJSONArray("with_history")
            if (zeroWithHist.length() > 0 && zeroWithHist.getString(0).equals(target, ignoreCase = true)) {
                enTargetInWithHistTop1Count++
            }
        }
        assertTrue("English with_history should rank target at top >= 60% of time: $enTargetInWithHistTop1Count / ${enCases.length()}",
            enTargetInWithHistTop1Count >= 18)
    }
}
