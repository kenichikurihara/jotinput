package com.example.myasr

import com.example.myasr.context.kanakanji.domain.JapaneseKanaUtils
import com.example.myasr.context.vocabulary.domain.DictionaryEntry
import com.example.myasr.context.vocabulary.domain.Priority

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DictionaryEditTest {

    @Test
    fun testDictionaryEntryCopyAndEdit() {
        val original = DictionaryEntry(
            id = "test-id-123",
            word = "中村",
            reading = "なかむら",
            priority = Priority.NORMAL,
            isEnabled = true
        )

        // User edits reading using Katakana input
        val userEditedReading = "ナカムラ"
        assertTrue(JapaneseKanaUtils.isValidKana(userEditedReading))
        val normalizedReading = JapaneseKanaUtils.normalizeReading(userEditedReading)
        assertEquals("なかむら", normalizedReading)

        val updated = original.copy(
            word = "中村健",
            reading = normalizedReading,
            priority = Priority.HIGH
        )

        assertEquals("test-id-123", updated.id)
        assertEquals("中村健", updated.word)
        assertEquals("なかむら", updated.reading)
        assertEquals(Priority.HIGH, updated.priority)
        assertTrue(updated.isEnabled)
    }

    @Test
    fun testKanaValidationDuringEdit() {
        assertTrue(JapaneseKanaUtils.isValidKana("なかむら"))
        assertTrue(JapaneseKanaUtils.isValidKana("ナカムラ"))
        assertTrue(JapaneseKanaUtils.isValidKana("すぴーち"))
        assertTrue(JapaneseKanaUtils.isValidKana("スピーチ"))

        // Invalid readings (romaji, kanji, symbols)
        assertFalse(JapaneseKanaUtils.isValidKana("nakamura"))
        assertFalse(JapaneseKanaUtils.isValidKana("中村"))
        assertFalse(JapaneseKanaUtils.isValidKana(""))
    }

    @Test
    fun testPriorityFromString() {
        assertEquals(Priority.NORMAL, Priority.fromString("NORMAL"))
        assertEquals(Priority.HIGH, Priority.fromString("HIGH"))
        assertEquals(Priority.LOW, Priority.fromString("LOW"))
        assertEquals(Priority.NORMAL, Priority.fromString("UNKNOWN"))
    }
}
