package com.example.myasr

import com.example.myasr.context.english.domain.EnglishDictionaryUtils
import com.example.myasr.context.kanakanji.domain.KanaKanjiEngine
import com.example.myasr.context.vocabulary.domain.DictionaryEntry
import com.example.myasr.context.vocabulary.domain.Priority

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
        assertTrue(EnglishDictionaryUtils.isValidEnglishWord("Nakamura"))
        assertTrue(EnglishDictionaryUtils.isValidEnglishWord("ReazonSpeech"))
        assertTrue(EnglishDictionaryUtils.isValidEnglishWord("JotInput"))
        assertTrue(EnglishDictionaryUtils.isValidEnglishWord("Wi-Fi"))
        assertTrue(EnglishDictionaryUtils.isValidEnglishWord("I'm"))
        assertTrue(EnglishDictionaryUtils.isValidEnglishWord("kubectl"))
        assertTrue(EnglishDictionaryUtils.isValidEnglishWord("hello world"))

        // Invalid: Japanese Kana, Kanji, punctuation
        assertFalse(EnglishDictionaryUtils.isValidEnglishWord("なかむら"))
        assertFalse(EnglishDictionaryUtils.isValidEnglishWord("中村"))
        assertFalse(EnglishDictionaryUtils.isValidEnglishWord("！？"))
        assertFalse(EnglishDictionaryUtils.isValidEnglishWord(""))

        // Normalization
        assertEquals("reazonspeech", EnglishDictionaryUtils.normalizeEnglishReading("ReazonSpeech"))
        assertEquals("nakamura", EnglishDictionaryUtils.normalizeEnglishReading(" Nakamura "))

        // Trie key extraction (a-z only)
        assertEquals("reazonspeech", EnglishDictionaryUtils.toSwipeTrieKey("ReazonSpeech"))
        assertEquals("wifi", EnglishDictionaryUtils.toSwipeTrieKey("Wi-Fi"))
    }

    @Test
    fun testDictionaryEntryBilingualProperties() {
        // 1. Japanese entry
        val jaEntry = DictionaryEntry(
            word = "中村",
            reading = "なかむら",
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
            DictionaryEntry(word = "中村", reading = "なかむら", priority = Priority.NORMAL),
            DictionaryEntry(word = "ReazonSpeech", reading = "reazonspeech", priority = Priority.HIGH),
            DictionaryEntry(word = "Nakamura", reading = "nakamura", priority = Priority.NORMAL)
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
        assertEquals("中村", loadedEntries[0].word)
        assertTrue(loadedEntries[0].isJapaneseEntry)

        assertEquals("ReazonSpeech", loadedEntries[1].word)
        assertEquals("reazonspeech", loadedEntries[1].reading)
        assertTrue(loadedEntries[1].isEnglishOnlyEntry)

        assertEquals("Nakamura", loadedEntries[2].word)
        assertTrue(loadedEntries[2].isEnglishOnlyEntry)
    }

    @Test
    fun testUserDictionaryTypoAndMisspellingCorrection() {
        val dict = listOf(
            DictionaryEntry(word = "Nakamura", reading = "nakamura", priority = Priority.NORMAL),
            DictionaryEntry(word = "ReazonSpeech", reading = "reazonspeech", priority = Priority.HIGH),
            DictionaryEntry(word = "JotInput", reading = "jotinput", priority = Priority.HIGH),
            DictionaryEntry(word = "Antigravity", reading = "antigravity", priority = Priority.HIGH)
        )

        // 1. Exact case-insensitive match (typed lowercase, registered has CamelCase)
        val candsExact = EnglishDictionaryUtils.suggestFromUserDictionary("reazonspeech", dict)
        assertTrue("Must find exact case match", candsExact.isNotEmpty())
        assertEquals("ReazonSpeech", candsExact[0].surface)

        // 2. Adjacent key typo: 'r' mistyped as 't' (nakamuta -> Nakamura)
        val candsAdjacent = EnglishDictionaryUtils.suggestFromUserDictionary("nakamuta", dict)
        assertTrue("Must suggest Nakamura for nakamuta", candsAdjacent.any { it.surface == "Nakamura" })

        // 3. Transposition typo: 'am' mistyped as 'ma' (nakmaura -> Nakamura)
        val candsTransposed = EnglishDictionaryUtils.suggestFromUserDictionary("nakmaura", dict)
        assertTrue("Must suggest Nakamura for nakmaura", candsTransposed.any { it.surface == "Nakamura" })

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
    fun testRelativeObservationCostRejectsShortWordTypos() {
        val dict = listOf(
            DictionaryEntry(word = "UI", reading = "ui", priority = Priority.HIGH),
            DictionaryEntry(word = "OK", reading = "ok", priority = Priority.NORMAL),
            DictionaryEntry(word = "Nakamura", reading = "nakamura", priority = Priority.HIGH)
        )

        // Typing "wi" with non-adjacent keys must NOT suggest "UI" (relative error = 1.40 / 2 = 0.70 > 0.38)
        val candsWi = EnglishDictionaryUtils.suggestFromUserDictionary("wi", dict)
        assertFalse("Must NOT suggest UI when user types wi", candsWi.any { it.surface.equals("UI", ignoreCase = true) })

        // Typing "ui" must suggest "UI" (exact match, obsPenalty = 0.0)
        val candsUi = EnglishDictionaryUtils.suggestFromUserDictionary("ui", dict)
        assertTrue("Must suggest UI for ui", candsUi.any { it.surface == "UI" })
        assertEquals(0.0f, candsUi.first { it.surface == "UI" }.obsPenalty, 1e-4f)

        // Typing "ok" must suggest "OK"
        val candsOk = EnglishDictionaryUtils.suggestFromUserDictionary("ok", dict)
        assertTrue("Must suggest OK for ok", candsOk.any { it.surface == "OK" })

        // 2-letter words reject 1-char substitution typos (1.10 / 2 = 0.55 > 0.38)
        val candsIk = EnglishDictionaryUtils.suggestFromUserDictionary("ik", dict)
        assertFalse("2-letter words reject 50% character substitution", candsIk.any { it.surface == "OK" })

        // Typing "ko" (transposition typo: 0.60 / 2 = 0.30 <= 0.38)
        val candsKo = EnglishDictionaryUtils.suggestFromUserDictionary("ko", dict)
        assertTrue("Must suggest OK for transposition ko", candsKo.any { it.surface == "OK" })

        // Typing "nakamuta" for "Nakamura" (long word typo: 1.10 / 8 = 0.1375 <= 0.38)
        val candsNaka = EnglishDictionaryUtils.suggestFromUserDictionary("nakamuta", dict)
        assertTrue("Must suggest Nakamura for nakamuta", candsNaka.any { it.surface == "Nakamura" })
        assertTrue("Long word typo obsPenalty must be <= 0.2f", candsNaka.first { it.surface == "Nakamura" }.obsPenalty <= 0.2f)
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

    @Test
    fun testSpatialEditConstantsMatchCppImplementation() {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !java.io.File(dir, "app/src/main/cpp/spelling/english_spell_engine.cc").exists()) {
            dir = dir.parentFile
        }
        val cppFile = java.io.File(requireNotNull(dir), "app/src/main/cpp/spelling/english_spell_engine.cc")
        val cppSource = cppFile.readText()
        val expected = listOf(
            "kSpatialBoundaryGapCost = ${EnglishDictionaryUtils.SPATIAL_BOUNDARY_GAP_COST}f",
            "kSpatialIndelCost = ${EnglishDictionaryUtils.SPATIAL_INDEL_COST}f",
            "kSpatialSubBaseCost = ${EnglishDictionaryUtils.SPATIAL_SUB_BASE_COST}f",
            "kSpatialSubDistSlope = ${EnglishDictionaryUtils.SPATIAL_SUB_DIST_SLOPE}f",
            "kSpatialSubMaxDist = ${EnglishDictionaryUtils.SPATIAL_SUB_MAX_DIST}f",
            "kSpatialTranspositionCost = ${EnglishDictionaryUtils.SPATIAL_TRANSPOSITION_COST}f"
        )
        for (decl in expected) {
            assertTrue("Missing or drifted C++ constant: $decl", cppSource.contains(decl))
        }
    }
}
