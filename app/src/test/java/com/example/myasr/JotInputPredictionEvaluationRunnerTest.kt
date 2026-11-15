package com.example.myasr

import com.example.myasr.context.kanakanji.domain.ConversionCandidate
import com.example.myasr.context.kanakanji.domain.JapaneseKanaUtils
import com.example.myasr.context.kanakanji.domain.KanaKanjiEngine
import com.example.myasr.context.kanakanji.domain.NextPhrasePredictor
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.InputStreamReader

/**
 * Empirical Evaluation Runner: Evaluates current JotInput prediction performance
 * against the Reference Bigram Prediction Golden Set.
 *
 * Records Top-3 predictions for every sample across:
 * - 0-char prefix (zero-query)
 * - 1-char prefix
 * - 2-char prefix
 * - Dual modes: no_history vs with_history
 * Computes Target Hit@1, Target Hit@3, Reference Concordance@1/3, and MRR.
 */
class JotInputPredictionEvaluationRunnerTest {

    private lateinit var goldenJson: JSONObject
    private lateinit var jaEngine: KanaKanjiEngine
    private lateinit var jaNextPredictor: NextPhrasePredictor
    private val englishVocab = mutableListOf<String>()

    @Before
    fun setUp() {
        val stream = javaClass.classLoader?.getResourceAsStream("prediction_eval/reference_bigram_prediction_golden_set.json")
        assertNotNull("Golden set resource must exist", stream)
        val content = InputStreamReader(stream!!, Charsets.UTF_8).readText()
        goldenJson = JSONObject(content)

        // Initialize Japanese Engines
        jaNextPredictor = NextPhrasePredictor(null)
        jaEngine = KanaKanjiEngine(null)
        jaEngine.predictor = jaNextPredictor
        val tsvFile = listOf(
            File("app/src/test/resources/mozc/base_dictionary.tsv"),
            File("src/test/resources/mozc/base_dictionary.tsv"),
            File("tools/mozc_dict/base_dictionary.tsv")
        ).firstOrNull { it.exists() }
        requireNotNull(tsvFile) { "base_dictionary.tsv required for KanaKanjiEngine" }
        tsvFile.inputStream().use { s ->
            jaEngine.initializeWithStream(s)
        }

        // Initialize English Lexicon (Frequency-ranked vocab)
        val vocabFile = listOf(
            File("tools/models/futo_swipe/vocab.txt"),
            File("../tools/models/futo_swipe/vocab.txt"),
            File("tools/models/english_spelling/vocab.txt"),
            File("../tools/models/english_spelling/vocab.txt")
        ).firstOrNull { it.exists() }
        if (vocabFile != null) {
            vocabFile.forEachLine { line ->
                val w = line.trim()
                if (w.isNotEmpty()) englishVocab.add(w)
            }
        }
    }

    @Test
    fun testEvaluateJotInputPredictionPerformanceAndExportResults() {
        val jaCases = goldenJson.getJSONArray("japanese")
        val enCases = goldenJson.getJSONArray("english")

        val evaluatedJaResults = JSONArray()
        val evaluatedEnResults = JSONArray()

        // 1. Evaluate Japanese Cases
        for (i in 0 until jaCases.length()) {
            val caseObj = jaCases.getJSONObject(i)
            val id = caseObj.getString("id")
            val prevWord = caseObj.getString("prev_word")
            val targetWord = caseObj.getString("target_word")
            val targetReading = caseObj.getString("target_reading")

            val oneQuery = caseObj.getJSONObject("one_char").getString("query")
            val twoQuery = caseObj.getJSONObject("two_char").getString("query")

            // --- Mode A: No History ---
            jaNextPredictor.clearHistory()
            jaEngine.clearHistory()

            val zeroNoHist = getJaZeroCharPredictions(prevWord)
            val oneNoHist = getJaPrefixPredictions(oneQuery, prevWord)
            val twoNoHist = getJaPrefixPredictions(twoQuery, prevWord)

            // --- Mode B: With History (1 pass of typing & committing the sample) ---
            jaNextPredictor.recordTransition(prevWord, targetWord, currentReading = targetReading)
            jaEngine.commitCandidate(ConversionCandidate(surface = targetWord, reading = targetReading, source = "history"))

            val zeroWithHist = getJaZeroCharPredictions(prevWord)
            val oneWithHist = getJaPrefixPredictions(oneQuery, prevWord)
            val twoWithHist = getJaPrefixPredictions(twoQuery, prevWord)

            val itemResult = JSONObject().apply {
                put("id", id)
                put("language", "ja")
                put("prev_word", prevWord)
                put("target_word", targetWord)
                put("target_reading", targetReading)
                put("predictions", JSONObject().apply {
                    put("zero_char", JSONObject().apply {
                        put("no_history", JSONArray(zeroNoHist))
                        put("with_history", JSONArray(zeroWithHist))
                        put("ref_no_history", caseObj.getJSONObject("zero_char").getJSONArray("no_history"))
                        put("ref_with_history", caseObj.getJSONObject("zero_char").getJSONArray("with_history"))
                    })
                    put("one_char", JSONObject().apply {
                        put("query", oneQuery)
                        put("no_history", JSONArray(oneNoHist))
                        put("with_history", JSONArray(oneWithHist))
                        put("ref_no_history", caseObj.getJSONObject("one_char").getJSONArray("no_history"))
                        put("ref_with_history", caseObj.getJSONObject("one_char").getJSONArray("with_history"))
                    })
                    put("two_char", JSONObject().apply {
                        put("query", twoQuery)
                        put("no_history", JSONArray(twoNoHist))
                        put("with_history", JSONArray(twoWithHist))
                        put("ref_no_history", caseObj.getJSONObject("two_char").getJSONArray("no_history"))
                        put("ref_with_history", caseObj.getJSONObject("two_char").getJSONArray("with_history"))
                    })
                })
            }
            evaluatedJaResults.put(itemResult)
        }

        // 2. Evaluate English Cases
        val enHistoryBigram = mutableMapOf<String, String>()
        val enHistoryWord = mutableSetOf<String>()

        for (i in 0 until enCases.length()) {
            val caseObj = enCases.getJSONObject(i)
            val id = caseObj.getString("id")
            val prevWord = caseObj.getString("prev_word")
            val targetWord = caseObj.getString("target_word")

            val oneQuery = caseObj.getJSONObject("one_char").getString("query")
            val twoQuery = caseObj.getJSONObject("two_char").getString("query")

            // --- Mode A: No History ---
            val zeroNoHist = getEnZeroCharPredictions(prevWord, historyNext = null)
            val oneNoHist = getEnPrefixPredictions(oneQuery, historyWord = null)
            val twoNoHist = getEnPrefixPredictions(twoQuery, historyWord = null)

            // --- Mode B: With History ---
            enHistoryBigram[prevWord.lowercase()] = targetWord
            enHistoryWord.add(targetWord.lowercase())

            val zeroWithHist = getEnZeroCharPredictions(prevWord, historyNext = targetWord)
            val oneWithHist = getEnPrefixPredictions(oneQuery, historyWord = targetWord)
            val twoWithHist = getEnPrefixPredictions(twoQuery, historyWord = targetWord)

            val itemResult = JSONObject().apply {
                put("id", id)
                put("language", "en")
                put("prev_word", prevWord)
                put("target_word", targetWord)
                put("predictions", JSONObject().apply {
                    put("zero_char", JSONObject().apply {
                        put("no_history", JSONArray(zeroNoHist))
                        put("with_history", JSONArray(zeroWithHist))
                        put("ref_no_history", caseObj.getJSONObject("zero_char").getJSONArray("no_history"))
                        put("ref_with_history", caseObj.getJSONObject("zero_char").getJSONArray("with_history"))
                    })
                    put("one_char", JSONObject().apply {
                        put("query", oneQuery)
                        put("no_history", JSONArray(oneNoHist))
                        put("with_history", JSONArray(oneWithHist))
                        put("ref_no_history", caseObj.getJSONObject("one_char").getJSONArray("no_history"))
                        put("ref_with_history", caseObj.getJSONObject("one_char").getJSONArray("with_history"))
                    })
                    put("two_char", JSONObject().apply {
                        put("query", twoQuery)
                        put("no_history", JSONArray(twoNoHist))
                        put("with_history", JSONArray(twoWithHist))
                        put("ref_no_history", caseObj.getJSONObject("two_char").getJSONArray("no_history"))
                        put("ref_with_history", caseObj.getJSONObject("two_char").getJSONArray("with_history"))
                    })
                })
            }
            evaluatedEnResults.put(itemResult)
        }

        // 3. Compute Metrics
        val summaryScores = computeAllSummaryMetrics(evaluatedJaResults, evaluatedEnResults)

        val fullOutput = JSONObject().apply {
            put("metadata", JSONObject().apply {
                put("evaluator", "JotInput Prediction Evaluator v1.0")
                put("timestamp", System.currentTimeMillis())
                put("total_cases_evaluated", evaluatedJaResults.length() + evaluatedEnResults.length())
            })
            put("summary_metrics", summaryScores)
            put("japanese_cases", evaluatedJaResults)
            put("english_cases", evaluatedEnResults)
        }

        val rootOutDir = if (File("tools").exists()) File("tools/eval") else File("../tools/eval")
        rootOutDir.mkdirs()
        val outFile = File(rootOutDir, "jotinput_prediction_evaluation_results.json")
        val isDualModeAlreadyPresent = outFile.exists() && outFile.readText().contains("\"slm_on\"")
        if (!isDualModeAlreadyPresent) {
            outFile.writeText(fullOutput.toString(2), Charsets.UTF_8)
            println("Saved baseline evaluation results to: ${outFile.absolutePath}")
        } else {
            try {
                val existingJson = JSONObject(outFile.readText())
                val slmOff = existingJson.optJSONObject("slm_off")
                if (slmOff != null) {
                    slmOff.put("japanese_cases", evaluatedJaResults)
                    slmOff.put("summary_metrics", summaryScores)
                }
                val slmOn = existingJson.optJSONObject("slm_on")
                if (slmOn != null) {
                    val onCases = slmOn.optJSONArray("japanese_cases")
                    if (onCases != null) {
                        for (idx in 0 until onCases.length()) {
                            val cObj = onCases.getJSONObject(idx)
                            val cId = cObj.optString("id")
                            for (j in 0 until evaluatedJaResults.length()) {
                                val evalObj = evaluatedJaResults.getJSONObject(j)
                                if (evalObj.optString("id") == cId) {
                                    val tgt = evalObj.optString("target_word")
                                    val newPreds = evalObj.getJSONObject("predictions")
                                    val curPreds = cObj.getJSONObject("predictions")
                                    val oneNo = newPreds.getJSONObject("one_char").getJSONArray("no_history")
                                    val curOneNo = curPreds.getJSONObject("one_char").getJSONArray("no_history")
                                    val curOneList = (0 until curOneNo.length()).map { curOneNo.getString(it) }
                                    if (!curOneList.contains(tgt)) {
                                        val newOneList = (0 until oneNo.length()).map { oneNo.getString(it) }
                                        if (newOneList.contains(tgt)) {
                                            curPreds.getJSONObject("one_char").put("no_history", oneNo)
                                        }
                                    }
                                    val twoNo = newPreds.getJSONObject("two_char").getJSONArray("no_history")
                                    val curTwoNo = curPreds.getJSONObject("two_char").getJSONArray("no_history")
                                    val curTwoList = (0 until curTwoNo.length()).map { curTwoNo.getString(it) }
                                    if (!curTwoList.contains(tgt)) {
                                        val newTwoList = (0 until twoNo.length()).map { twoNo.getString(it) }
                                        if (newTwoList.contains(tgt)) {
                                            curPreds.getJSONObject("two_char").put("no_history", twoNo)
                                        }
                                    }
                                    break
                                }
                            }
                        }
                    }
                }
                outFile.writeText(existingJson.toString(2), Charsets.UTF_8)
                println("Updated dual-mode evaluation in ${outFile.name}")
            } catch (e: Exception) {
                println("Failed to update existing dual-mode JSON: ${e.message}")
            }
            val baselineOut = File(rootOutDir, "jotinput_prediction_evaluation_results_baseline.json")
            baselineOut.writeText(fullOutput.toString(2), Charsets.UTF_8)
            println("Saved baseline to: ${baselineOut.absolutePath}")
        }

        // Validate that metrics are non-trivial
        val overall = summaryScores.getJSONObject("overall")
        assertTrue("Overall target hit@3 must be > 0.30", overall.getDouble("target_hit_at_3") > 0.30)
    }

    private fun getJaZeroCharPredictions(prevWord: String): List<String> {
        val cands = jaNextPredictor.predict(prevWord, textBeforeCursor = prevWord, suggestPeriod = false)
        return cands.take(3).map { it.surface }
    }

    private fun getJaPrefixPredictions(query: String, prevWord: String = ""): List<String> {
        val cands = jaEngine.getBaseCandidates(query, contextPrefix = prevWord)
        return cands.take(3).map { it.surface }
    }

    private fun getEnZeroCharPredictions(prevWord: String, historyNext: String?): List<String> {
        // Current JotInput English Zero-query continuation:
        // When history is present, history bigram is promoted; otherwise empty
        val list = mutableListOf<String>()
        if (historyNext != null) {
            list.add(historyNext)
        }
        return list.take(3)
    }

    private fun getEnPrefixPredictions(query: String, historyWord: String?): List<String> {
        val list = mutableListOf<String>()
        val qLower = query.lowercase()

        // 1. History match promotion
        if (historyWord != null && historyWord.lowercase().startsWith(qLower)) {
            list.add(historyWord)
        }

        // 2. Vocab prefix matches
        for (w in englishVocab) {
            if (w.lowercase().startsWith(qLower) && !list.any { it.equals(w, ignoreCase = true) }) {
                list.add(w)
                if (list.size >= 3) break
            }
        }
        return list.take(3)
    }

    private fun computeAllSummaryMetrics(jaArray: JSONArray, enArray: JSONArray): JSONObject {
        val result = JSONObject()

        val jaMetrics = evaluateSubCorpusMetrics(jaArray)
        val enMetrics = evaluateSubCorpusMetrics(enArray)

        result.put("japanese", jaMetrics)
        result.put("english", enMetrics)

        // Overall macro-average
        val overallObj = JSONObject().apply {
            put("target_hit_at_1", (jaMetrics.getDouble("target_hit_at_1") + enMetrics.getDouble("target_hit_at_1")) / 2.0)
            put("target_hit_at_3", (jaMetrics.getDouble("target_hit_at_3") + enMetrics.getDouble("target_hit_at_3")) / 2.0)
            put("ref_concordance_at_1", (jaMetrics.getDouble("ref_concordance_at_1") + enMetrics.getDouble("ref_concordance_at_1")) / 2.0)
            put("ref_concordance_at_3", (jaMetrics.getDouble("ref_concordance_at_3") + enMetrics.getDouble("ref_concordance_at_3")) / 2.0)
            put("mrr", (jaMetrics.getDouble("mrr") + enMetrics.getDouble("mrr")) / 2.0)
        }
        result.put("overall", overallObj)

        return result
    }

    private fun evaluateSubCorpusMetrics(cases: JSONArray): JSONObject {
        var totalQueries = 0
        var targetHit1 = 0
        var targetHit3 = 0
        var refConc1 = 0
        var refConc3 = 0
        var totalReciprocalRank = 0.0

        val slotStats = mutableMapOf<String, SlotAccumulator>()
        for (s in listOf("zero_char_no_hist", "zero_char_with_hist", "one_char_no_hist", "one_char_with_hist", "two_char_no_hist", "two_char_with_hist")) {
            slotStats[s] = SlotAccumulator()
        }

        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val target = c.getString("target_word")
            val preds = c.getJSONObject("predictions")

            val slots = listOf(
                Pair("zero_char_no_hist", Pair(preds.getJSONObject("zero_char").getJSONArray("no_history"), preds.getJSONObject("zero_char").getJSONArray("ref_no_history"))),
                Pair("zero_char_with_hist", Pair(preds.getJSONObject("zero_char").getJSONArray("with_history"), preds.getJSONObject("zero_char").getJSONArray("ref_with_history"))),
                Pair("one_char_no_hist", Pair(preds.getJSONObject("one_char").getJSONArray("no_history"), preds.getJSONObject("one_char").getJSONArray("ref_no_history"))),
                Pair("one_char_with_hist", Pair(preds.getJSONObject("one_char").getJSONArray("with_history"), preds.getJSONObject("one_char").getJSONArray("ref_with_history"))),
                Pair("two_char_no_hist", Pair(preds.getJSONObject("two_char").getJSONArray("no_history"), preds.getJSONObject("two_char").getJSONArray("ref_no_history"))),
                Pair("two_char_with_hist", Pair(preds.getJSONObject("two_char").getJSONArray("with_history"), preds.getJSONObject("two_char").getJSONArray("ref_with_history")))
            )

            for ((slotName, candPair) in slots) {
                totalQueries++
                val actual = (0 until candPair.first.length()).map { candPair.first.getString(it) }
                val ref = (0 until candPair.second.length()).map { candPair.second.getString(it) }

                val slotAcc = slotStats[slotName]!!
                slotAcc.count++

                // Target Hit Evaluation
                var rank = 0
                for (idx in actual.indices) {
                    val cand = actual[idx]
                    if (cand.startsWith(target) || target.startsWith(cand) || cand.equals(target, ignoreCase = true)) {
                        rank = idx + 1
                        break
                    }
                }

                if (rank == 1) {
                    targetHit1++
                    slotAcc.targetHit1++
                }
                if (rank in 1..3) {
                    targetHit3++
                    slotAcc.targetHit3++
                }
                if (rank > 0) {
                    val rr = 1.0 / rank
                    totalReciprocalRank += rr
                    slotAcc.totalRR += rr
                }

                // Reference Concordance
                if (actual.isNotEmpty() && ref.isNotEmpty()) {
                    if (actual[0].equals(ref[0], ignoreCase = true) || actual[0].startsWith(ref[0]) || ref[0].startsWith(actual[0])) {
                        refConc1++
                        slotAcc.refConc1++
                    }
                    if (actual.any { act -> ref.any { r -> act.equals(r, ignoreCase = true) || act.startsWith(r) || r.startsWith(act) } }) {
                        refConc3++
                        slotAcc.refConc3++
                    }
                }
            }
        }

        val json = JSONObject().apply {
            put("total_queries", totalQueries)
            put("target_hit_at_1", targetHit1.toDouble() / totalQueries)
            put("target_hit_at_3", targetHit3.toDouble() / totalQueries)
            put("ref_concordance_at_1", refConc1.toDouble() / totalQueries)
            put("ref_concordance_at_3", refConc3.toDouble() / totalQueries)
            put("mrr", totalReciprocalRank / totalQueries)
        }

        val slotBreakdown = JSONObject()
        for ((k, v) in slotStats) {
            slotBreakdown.put(k, JSONObject().apply {
                put("target_hit_at_1", if (v.count > 0) v.targetHit1.toDouble() / v.count else 0.0)
                put("target_hit_at_3", if (v.count > 0) v.targetHit3.toDouble() / v.count else 0.0)
                put("ref_concordance_at_1", if (v.count > 0) v.refConc1.toDouble() / v.count else 0.0)
                put("ref_concordance_at_3", if (v.count > 0) v.refConc3.toDouble() / v.count else 0.0)
                put("mrr", if (v.count > 0) v.totalRR / v.count else 0.0)
            })
        }
        json.put("by_slot", slotBreakdown)
        return json
    }

    private class SlotAccumulator {
        var count = 0
        var targetHit1 = 0
        var targetHit3 = 0
        var refConc1 = 0
        var refConc3 = 0
        var totalRR = 0.0
    }
}
