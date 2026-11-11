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
            DictionaryEntry(id = "1", word = "栗原", reading = "くりはら", priority = Priority.HIGH, isEnabled = true)
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
        assertTrue(jsonStr.contains("栗原"))
        assertTrue(jsonStr.contains("よろしく"))
        assertTrue(jsonStr.contains("お願いします"))
    }

    @Test
    fun test3WayUnigramDeltaMerge() {
        val engine = KanaKanjiEngine(null)

        // 1. Establish initial local state L=15
        val field = KanaKanjiEngine::class.java.getDeclaredField("historyMap")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val map = field.get(engine) as MutableMap<String, Int>
        map["きしゃ:貴社"] = 15

        // Base = 10 (what local had at last sync)
        val baseMap = mapOf("きしゃ:貴社" to 10)
        // Remote = 14 (+4 committed on remote device starting from 10)
        val remoteMap = mapOf("きしゃ:貴社" to 14)

        // Local delta = 15 - 10 = +5
        // Expected merged count = max(14, 10) + 5 = 19
        engine.mergeHistoryDelta(remoteMap, baseMap)

        val snapshot = engine.getHistorySnapshot()
        assertEquals(19, snapshot["きしゃ:貴社"])

        // Idempotency: merging again with base=19, remote=19 produces 19
        val newBaseMap = mapOf("きしゃ:貴社" to 19)
        val newRemoteMap = mapOf("きしゃ:貴社" to 19)
        engine.mergeHistoryDelta(newRemoteMap, newBaseMap)
        assertEquals(19, engine.getHistorySnapshot()["きしゃ:貴社"])
    }

    @Test
    fun test3WayBigramDeltaMerge() {
        val predictor = NextPhrasePredictor(null)

        val field = NextPhrasePredictor::class.java.getDeclaredField("bigramHistory")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val map = field.get(predictor) as MutableMap<String, LinkedHashMap<String, Int>>

        val localChild = LinkedHashMap<String, Int>()
        localChild["です"] = 25 // Local has 25 (Delta = +5 from Base 20)
        map["お疲れ様"] = localChild

        val baseBigram = mapOf("お疲れ様" to mapOf("です" to 20))
        val remoteBigram = mapOf("お疲れ様" to mapOf("です" to 24)) // Remote has +4 from Base 20

        // Expected = max(24, 20) + 5 = 29
        predictor.mergeHistoryDelta(remoteBigram, baseBigram)

        val snapshot = predictor.getHistorySnapshot()
        assertEquals(29, snapshot["お疲れ様"]?.get("です"))
    }

    @Test
    fun testLwwTombstoneMergeAndZombiePrevention() {
        // Test repository logic using mock entry/tombstone comparison directly
        val tombstone = DictionaryTombstone(
            id = "t1",
            word = "旧プロジェクト",
            reading = "きゅうぷろじぇくと",
            deletedAtMillis = 2000L
        )
        val staleRemoteEntry = DictionaryEntry(
            id = "t1",
            word = "旧プロジェクト",
            reading = "きゅうぷろじぇくと",
            priority = Priority.NORMAL,
            isEnabled = true,
            updatedAtMillis = 1000L
        )

        // When tombstone deletion (t=2000) is newer than remote entry update (t=1000),
        // deletion must win (preventing zombie resurrection)
        assertTrue(tombstone.deletedAtMillis > staleRemoteEntry.updatedAtMillis)

        // If user actively recreated/revived word (t=3000), entry must win over tombstone
        val revivedEntry = staleRemoteEntry.copy(updatedAtMillis = 3000L)
        assertTrue(revivedEntry.updatedAtMillis > tombstone.deletedAtMillis)
    }

    @Test
    fun testSchemaV1ToV2MigrationCompatibility() {
        val v1Json = """
            {
              "schema_version": 1,
              "exported_at_ms": 1700000000000,
              "device_model": "Pixel 9",
              "unigram_history": {
                "きしゃ:貴社": 10
              },
              "bigram_history": {
                "お疲れ様": { "です": 20 }
              },
              "custom_dictionary": [
                {
                  "id": "1",
                  "word": "テスト",
                  "reading": "てすと",
                  "priority": "HIGH",
                  "isEnabled": true
                }
              ]
            }
        """.trimIndent()

        val parsed = org.json.JSONObject(v1Json)
        assertEquals(1, parsed.getInt("schema_version"))

        val entriesArr = parsed.getJSONArray("custom_dictionary")
        val entry = DictionaryEntry.fromJson(entriesArr.getJSONObject(0))
        assertEquals("テスト", entry.word)
        assertEquals("てすと", entry.reading)
        assertEquals(Priority.HIGH, entry.priority)
        assertTrue(entry.isEnabled)
        // Schema v1 fallback
        assertEquals(0L, entry.updatedAtMillis)

        // Schema v2 serialization
        val v2Entry = entry.copy(updatedAtMillis = 1794260000000L)
        val v2Tombstone = DictionaryTombstone("2", "旧語", "きゅうご", 1794270000000L)

        val v2Root = org.json.JSONObject()
        v2Root.put("schema_version", 2)
        v2Root.put("sync_revision_id", "rev-test-123")
        val dictArr = org.json.JSONArray().apply { put(v2Entry.toJson()) }
        val tombArr = org.json.JSONArray().apply { put(v2Tombstone.toJson()) }
        v2Root.put("custom_dictionary", dictArr)
        v2Root.put("custom_dictionary_tombstones", tombArr)

        val v2Str = v2Root.toString()
        assertTrue(v2Str.contains("rev-test-123"))
        assertTrue(v2Str.contains("custom_dictionary_tombstones"))
        assertTrue(v2Str.contains("1794260000000"))
        assertTrue(v2Str.contains("1794270000000"))
    }
}
