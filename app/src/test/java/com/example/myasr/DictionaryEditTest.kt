package com.example.myasr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DictionaryEditTest {

    @Test
    fun testDictionaryEntryCopyAndEdit() {
        val original = DictionaryEntry(
            id = "test-id-123",
            word = "栗原",
            reading = "くりはら",
            priority = Priority.NORMAL,
            isEnabled = true
        )

        // User edits reading using Katakana input
        val userEditedReading = "クリハラ"
        assertTrue(JapaneseKanaUtils.isValidKana(userEditedReading))
        val normalizedReading = JapaneseKanaUtils.normalizeReading(userEditedReading)
        assertEquals("くりはら", normalizedReading)

        val updated = original.copy(
            word = "栗原建",
            reading = normalizedReading,
            priority = Priority.HIGH
        )

        assertEquals("test-id-123", updated.id)
        assertEquals("栗原建", updated.word)
        assertEquals("くりはら", updated.reading)
        assertEquals(Priority.HIGH, updated.priority)
        assertTrue(updated.isEnabled)
    }

    @Test
    fun testKanaValidationDuringEdit() {
        assertTrue(JapaneseKanaUtils.isValidKana("くりはら"))
        assertTrue(JapaneseKanaUtils.isValidKana("クリハラ"))
        assertTrue(JapaneseKanaUtils.isValidKana("すぴーち"))
        assertTrue(JapaneseKanaUtils.isValidKana("スピーチ"))

        // Invalid readings (romaji, kanji, symbols)
        assertFalse(JapaneseKanaUtils.isValidKana("kurihara"))
        assertFalse(JapaneseKanaUtils.isValidKana("栗原"))
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
