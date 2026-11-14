package com.example.myasr

import com.example.myasr.context.vocabulary.domain.DictionaryEntry
import com.example.myasr.context.vocabulary.domain.DictionaryInputLogic
import com.example.myasr.context.vocabulary.domain.Priority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DictionaryInputLogicTest {

    @Test
    fun `1-step English registration succeeds from word field alone`() {
        val result = DictionaryInputLogic.resolveWordAndReading("Antigravity", "")
        assertTrue(result is DictionaryInputLogic.ResolutionResult.Success)
        val success = result as DictionaryInputLogic.ResolutionResult.Success
        assertEquals("Antigravity", success.word)
        assertEquals("antigravity", success.reading)
        assertTrue(success.isEnglishOnly)
    }

    @Test
    fun `backward-compatible English registration succeeds from reading field alone`() {
        val result = DictionaryInputLogic.resolveWordAndReading("", "ReazonSpeech")
        assertTrue(result is DictionaryInputLogic.ResolutionResult.Success)
        val success = result as DictionaryInputLogic.ResolutionResult.Success
        assertEquals("ReazonSpeech", success.word)
        assertEquals("reazonspeech", success.reading)
        assertTrue(success.isEnglishOnly)
    }

    @Test
    fun `Japanese registration normalizes Katakana reading to Hiragana`() {
        val result = DictionaryInputLogic.resolveWordAndReading("中村", "ナカムラ")
        assertTrue(result is DictionaryInputLogic.ResolutionResult.Success)
        val success = result as DictionaryInputLogic.ResolutionResult.Success
        assertEquals("中村", success.word)
        assertEquals("なかむら", success.reading)
        assertFalse(success.isEnglishOnly)
    }

    @Test
    fun `Japanese registration rejects missing word, missing reading, and non-kana reading`() {
        val missingWord = DictionaryInputLogic.resolveWordAndReading("", "なかむら")
        assertEquals(
            DictionaryInputLogic.ErrorField.WORD,
            (missingWord as DictionaryInputLogic.ResolutionResult.Error).field
        )

        val missingReading = DictionaryInputLogic.resolveWordAndReading("中村", "")
        assertEquals(
            DictionaryInputLogic.ErrorField.READING,
            (missingReading as DictionaryInputLogic.ResolutionResult.Error).field
        )

        val invalidReading = DictionaryInputLogic.resolveWordAndReading("中村", "nakamura")
        assertEquals(
            DictionaryInputLogic.ErrorField.READING,
            (invalidReading as DictionaryInputLogic.ResolutionResult.Error).field
        )
    }

    @Test
    fun `suggestReadingIfKanaOnly suggests Hiragana for Katakana and null for Kanji or English`() {
        assertEquals("あてんしょん", DictionaryInputLogic.suggestReadingIfKanaOnly("アテンション"))
        assertEquals("なかむら", DictionaryInputLogic.suggestReadingIfKanaOnly("なかむら"))
        assertNull(DictionaryInputLogic.suggestReadingIfKanaOnly("中村"))
        assertNull(DictionaryInputLogic.suggestReadingIfKanaOnly("Antigravity"))
        assertNull(DictionaryInputLogic.suggestReadingIfKanaOnly(""))
    }

    @Test
    fun `filterEntries matches surface, hiragana, katakana, and English case-insensitively`() {
        val entries = listOf(
            DictionaryEntry(word = "中村", reading = "なかむら", priority = Priority.HIGH),
            DictionaryEntry(word = "Antigravity", reading = "antigravity", priority = Priority.NORMAL),
            DictionaryEntry(word = "アテンション", reading = "あてんしょん", priority = Priority.NORMAL)
        )

        assertEquals(3, DictionaryInputLogic.filterEntries(entries, "").size)
        assertEquals(listOf(entries[0]), DictionaryInputLogic.filterEntries(entries, "中"))
        assertEquals(listOf(entries[0]), DictionaryInputLogic.filterEntries(entries, "ナカ"))
        assertEquals(listOf(entries[1]), DictionaryInputLogic.filterEntries(entries, "anti"))
        assertEquals(listOf(entries[2]), DictionaryInputLogic.filterEntries(entries, "あてん"))
        assertTrue(DictionaryInputLogic.filterEntries(entries, "存在しない").isEmpty())
    }
}
