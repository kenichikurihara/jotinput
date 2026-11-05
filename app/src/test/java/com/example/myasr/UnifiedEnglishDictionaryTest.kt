package com.example.myasr

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedEnglishDictionaryTest {

    @Test
    fun testEnglishWordValidationAndNormalization() {
        // Valid English words and tech terms
        assertTrue(EnglishDictionaryUtils.isValidEnglishWord("Antigravity"))
        assertTrue(EnglishDictionaryUtils.isValidEnglishWord("Kurihara"))
        assertTrue(EnglishDictionaryUtils.isValidEnglishWord("ReazonSpeech"))
        assertTrue(EnglishDictionaryUtils.isValidEnglishWord("JotInput"))
        assertTrue(EnglishDictionaryUtils.isValidEnglishWord("Wi-Fi"))
        assertTrue(EnglishDictionaryUtils.isValidEnglishWord("I'm"))
        assertTrue(EnglishDictionaryUtils.isValidEnglishWord("kubectl"))
        assertTrue(EnglishDictionaryUtils.isValidEnglishWord("hello world"))

        // Invalid: Japanese Kana, Kanji, punctuation
        assertFalse(EnglishDictionaryUtils.isValidEnglishWord("くりはら"))
        assertFalse(EnglishDictionaryUtils.isValidEnglishWord("栗原"))
        assertFalse(EnglishDictionaryUtils.isValidEnglishWord("！？"))
        assertFalse(EnglishDictionaryUtils.isValidEnglishWord(""))

        // Normalization
        assertEquals("reazonspeech", EnglishDictionaryUtils.normalizeEnglishReading("ReazonSpeech"))
        assertEquals("kurihara", EnglishDictionaryUtils.normalizeEnglishReading(" Kurihara "))

        // Trie key extraction (a-z only)
        assertEquals("reazonspeech", EnglishDictionaryUtils.toSwipeTrieKey("ReazonSpeech"))
        assertEquals("wifi", EnglishDictionaryUtils.toSwipeTrieKey("Wi-Fi"))
    }

    @Test
    fun testDictionaryEntryBilingualProperties() {
        // 1. Japanese entry
        val jaEntry = DictionaryEntry(
            word = "栗原",
            reading = "くりはら",
            priority = Priority.NORMAL
        )
        assertTrue(jaEntry.isJapaneseEntry)
        assertFalse(jaEntry.isEnglishOnlyEntry)
        assertFalse(jaEntry.hasEnglishSurface)

        // 2. English-only entry (reading only in form)
        val enEntry = DictionaryEntry(
            word = "Antigravity",
            reading = "antigravity",
            priority = Priority.HIGH
        )
        assertFalse(enEntry.isJapaneseEntry)
        assertTrue(enEntry.isEnglishOnlyEntry)
        assertTrue(enEntry.hasEnglishSurface)

        // 3. Mixed bilingual entry: Japanese reading with English surface word
        val mixedEntry = DictionaryEntry(
            word = "ReazonSpeech",
            reading = "れいぞんすぴーち",
            priority = Priority.HIGH
        )
        assertTrue(mixedEntry.isJapaneseEntry)
        assertFalse(mixedEntry.isEnglishOnlyEntry)
        assertTrue(mixedEntry.hasEnglishSurface)
    }

    @Test
    fun testJsonSerializationWithMixedEntries() {
        val entries = listOf(
            DictionaryEntry(word = "栗原", reading = "くりはら", priority = Priority.NORMAL),
            DictionaryEntry(word = "ReazonSpeech", reading = "reazonspeech", priority = Priority.HIGH),
            DictionaryEntry(word = "Kurihara", reading = "kurihara", priority = Priority.NORMAL)
        )

        val jsonArray = JSONArray()
        for (e in entries) {
            jsonArray.put(e.toJson())
        }

        val jsonStr = jsonArray.toString()

        // Deserialize back
        val loadedArray = JSONArray(jsonStr)
        val loadedEntries = mutableListOf<DictionaryEntry>()
        for (i in 0 until loadedArray.length()) {
            loadedEntries.add(DictionaryEntry.fromJson(loadedArray.getJSONObject(i)))
        }

        assertEquals(3, loadedEntries.size)
        assertEquals("栗原", loadedEntries[0].word)
        assertTrue(loadedEntries[0].isJapaneseEntry)

        assertEquals("ReazonSpeech", loadedEntries[1].word)
        assertEquals("reazonspeech", loadedEntries[1].reading)
        assertTrue(loadedEntries[1].isEnglishOnlyEntry)

        assertEquals("Kurihara", loadedEntries[2].word)
        assertTrue(loadedEntries[2].isEnglishOnlyEntry)
    }

    @Test
    fun testUserDictionaryTypoAndMisspellingCorrection() {
        val dict = listOf(
            DictionaryEntry(word = "Kurihara", reading = "kurihara", priority = Priority.NORMAL),
            DictionaryEntry(word = "ReazonSpeech", reading = "reazonspeech", priority = Priority.HIGH),
            DictionaryEntry(word = "JotInput", reading = "jotinput", priority = Priority.HIGH),
            DictionaryEntry(word = "Antigravity", reading = "antigravity", priority = Priority.HIGH)
        )

        // 1. Exact case-insensitive match (typed lowercase, registered has CamelCase)
        val candsExact = EnglishDictionaryUtils.suggestFromUserDictionary("reazonspeech", dict)
        assertTrue("Must find exact case match", candsExact.isNotEmpty())
        assertEquals("ReazonSpeech", candsExact[0].surface)

        // 2. Adjacent key typo: 'r' mistyped as 't' (kurihata -> Kurihara)
        val candsAdjacent = EnglishDictionaryUtils.suggestFromUserDictionary("kurihata", dict)
        assertTrue("Must suggest Kurihara for kurihata", candsAdjacent.any { it.surface == "Kurihara" })

        // 3. Transposition typo: 'ir' mistyped as 'ri' (kuirhara -> Kurihara)
        val candsTransposed = EnglishDictionaryUtils.suggestFromUserDictionary("kuirhara", dict)
        assertTrue("Must suggest Kurihara for kuirhara", candsTransposed.any { it.surface == "Kurihara" })

        // 4. Missing letter / deletion: 'e' omitted (reazonspech -> ReazonSpeech)
        val candsDeletion = EnglishDictionaryUtils.suggestFromUserDictionary("reazonspech", dict)
        assertTrue("Must suggest ReazonSpeech for reazonspech", candsDeletion.any { it.surface == "ReazonSpeech" })

        // 5. Extra letter / insertion: extra 't' (jotinputt -> JotInput)
        val candsInsertion = EnglishDictionaryUtils.suggestFromUserDictionary("jotinputt", dict)
        assertTrue("Must suggest JotInput for jotinputt", candsInsertion.any { it.surface == "JotInput" })

        // 6. Prefix completion: "antigrav" -> "Antigravity"
        val candsPrefix = EnglishDictionaryUtils.suggestFromUserDictionary("antigrav", dict)
        assertTrue("Must suggest Antigravity for antigrav", candsPrefix.any { it.surface == "Antigravity" })

        // 7. Unrelated word should not match
        val candsUnrelated = EnglishDictionaryUtils.suggestFromUserDictionary("banana", dict)
        assertTrue("Unrelated word should not match custom dictionary", candsUnrelated.isEmpty())
    }

    @Test
    fun testJapaneseKanaEngineIgnoresEnglishEntries() {
        val engine = KanaKanjiEngine()
        val mixedEntries = listOf(
            DictionaryEntry(word = "山田", reading = "やまだ", priority = Priority.NORMAL),
            DictionaryEntry(word = "Antigravity", reading = "antigravity", priority = Priority.HIGH)
        )

        engine.syncUserDictionary(mixedEntries)

        // Japanese reading "やまだ" should return "山田"
        val jaCands = engine.getBaseCandidates("やまだ")
        assertTrue("Japanese word must be found", jaCands.any { it.surface == "山田" })

        // English word "Antigravity" should not exist as a candidate in Japanese KanaKanjiEngine
        val enCands = engine.getBaseCandidates("antigravity")
        assertFalse("English word Antigravity should not be returned by Japanese KanaKanjiEngine", enCands.any { it.surface == "Antigravity" })
    }
}
