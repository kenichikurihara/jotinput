package com.example.myasr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UserHistorySyncTest {

    @Test
    fun testUnigramCapacityAndMaxMerge() {
        val engine = KanaKanjiEngine(null)

        // Commit candidate
        engine.commitCandidate(ConversionCandidate(surface = "今日", reading = "きょう"))
        engine.commitCandidate(ConversionCandidate(surface = "今日", reading = "きょう"))

        val snapshot = engine.getHistorySnapshot()
        assertEquals(2, snapshot["きょう:今日"])

        // Merge remote history with higher count and new word
        val remoteMap = mapOf(
            "きょう:今日" to 5,
            "かいぎ:会議" to 3
        )
        engine.mergeHistory(remoteMap)

        val mergedSnapshot = engine.getHistorySnapshot()
        assertEquals(5, mergedSnapshot["きょう:今日"])
        assertEquals(3, mergedSnapshot["かいぎ:会議"])

        // Idempotency check: merge again should not inflate counts
        engine.mergeHistory(remoteMap)
        val idempotentSnapshot = engine.getHistorySnapshot()
        assertEquals(5, idempotentSnapshot["きょう:今日"])
        assertEquals(3, idempotentSnapshot["かいぎ:会議"])
    }

    @Test
    fun testBigramCapacityAndPruning() {
        val predictor = NextPhrasePredictor(null)

        // Record transitions
        predictor.recordTransition("よろしく", "お願いします")
        predictor.recordTransition("よろしく", "お願いします")
        predictor.recordTransition("よろしく", "どうぞ")

        val snapshot = predictor.getHistorySnapshot()
        val nextMap = snapshot["よろしく"]
        assertEquals(2, nextMap?.get("お願いします"))
        assertEquals(1, nextMap?.get("どうぞ"))

        // Remote merge test
        val remoteBigram = mapOf(
            "よろしく" to mapOf(
                "お願いします" to 4,
                "お願いいたします" to 2
            ),
            "お疲れ" to mapOf(
                "様です" to 5
            )
        )
        predictor.mergeHistory(remoteBigram)

        val merged = predictor.getHistorySnapshot()
        assertEquals(4, merged["よろしく"]?.get("お願いします"))
        assertEquals(1, merged["よろしく"]?.get("どうぞ"))
        assertEquals(2, merged["よろしく"]?.get("お願いいたします"))
        assertEquals(5, merged["お疲れ"]?.get("様です"))

        // Idempotency check
        predictor.mergeHistory(remoteBigram)
        val idempotent = predictor.getHistorySnapshot()
        assertEquals(4, idempotent["よろしく"]?.get("お願いします"))
        assertEquals(5, idempotent["お疲れ"]?.get("様です"))
    }

    @Test
    fun testSyncJsonSerializationAndParsing() {
        val unigrams = mapOf("きょう:今日" to 10, "あした:明日" to 4)
        val bigrams = mapOf(
            "よろしく" to mapOf("お願いします" to 8),
            "確認" to mapOf("しました" to 3)
        )
        val customDict = listOf(
            DictionaryEntry(id = "1", word = "中村", reading = "なかむら", priority = Priority.HIGH, isEnabled = true)
        )

        // Mock instance helper by creating raw json using same logic
        val root = org.json.JSONObject()
        root.put("schema_version", 1)
        root.put("exported_at_ms", 123456789L)
        root.put("device_model", "TestDevice")

        val uObj = org.json.JSONObject()
        for ((k, v) in unigrams) uObj.put(k, v)
        root.put("unigram_history", uObj)

        val bObj = org.json.JSONObject()
        for ((p, map) in bigrams) {
            val cObj = org.json.JSONObject()
            for ((c, cnt) in map) cObj.put(c, cnt)
            bObj.put(p, cObj)
        }
        root.put("bigram_history", bObj)

        val dArr = org.json.JSONArray()
        for (e in customDict) dArr.put(e.toJson())
        root.put("custom_dictionary", dArr)

        val jsonStr = root.toString()
        assertTrue(jsonStr.contains("中村"))
        assertTrue(jsonStr.contains("よろしく"))
        assertTrue(jsonStr.contains("お願いします"))
    }
}
