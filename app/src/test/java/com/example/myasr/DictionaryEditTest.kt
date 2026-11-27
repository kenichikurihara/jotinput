package com.example.myasr

import android.content.Context
import android.content.ContextWrapper
import com.example.myasr.context.kanakanji.domain.JapaneseKanaUtils
import com.example.myasr.context.vocabulary.domain.DictionaryEntry
import com.example.myasr.context.vocabulary.domain.DictionaryInputLogic
import com.example.myasr.context.vocabulary.domain.Priority
import com.example.myasr.context.vocabulary.infrastructure.DictionaryRepository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

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

    @Test
    fun testUpgradeEnglishOnlyEntryToBilingualAndPreserveOrClearReading() {
        val englishOnly = DictionaryEntry(
            id = "en-1",
            word = "Antigravity",
            reading = "antigravity",
            priority = Priority.HIGH
        )
        assertTrue(englishOnly.isEnglishOnlyEntry)
        assertEquals("", DictionaryInputLogic.initialEditReading(englishOnly))

        // Add Japanese reading to existing English-only entry
        val upgradedRes = DictionaryInputLogic.resolveWordAndReading(
            rawWord = englishOnly.word,
            rawReading = "あんちぐらびてぃ"
        ) as DictionaryInputLogic.ResolutionResult.Success
        val bilingual = englishOnly.copy(word = upgradedRes.word, reading = upgradedRes.reading)
        assertTrue(bilingual.isJapaneseEntry)
        assertTrue(bilingual.hasEnglishSurface)
        assertFalse(bilingual.isEnglishOnlyEntry)
        assertEquals("あんちぐらびてぃ", bilingual.reading)

        // Edit bilingual word while keeping its reading
        val editedWordRes = DictionaryInputLogic.resolveWordAndReading(
            rawWord = "AntigravityIDE",
            rawReading = DictionaryInputLogic.initialEditReading(bilingual)
        ) as DictionaryInputLogic.ResolutionResult.Success
        val editedBilingual = bilingual.copy(word = editedWordRes.word, reading = editedWordRes.reading)
        assertEquals("AntigravityIDE", editedBilingual.word)
        assertEquals("あんちぐらびてぃ", editedBilingual.reading)
        assertTrue(editedBilingual.isJapaneseEntry)

        // Clear reading back to English-only
        val clearedRes = DictionaryInputLogic.resolveWordAndReading(
            rawWord = editedBilingual.word,
            rawReading = ""
        ) as DictionaryInputLogic.ResolutionResult.Success
        val revertedEnglish = editedBilingual.copy(word = clearedRes.word, reading = clearedRes.reading)
        assertTrue(revertedEnglish.isEnglishOnlyEntry)
        assertEquals("antigravityide", revertedEnglish.reading)
    }

    @Test
    fun testRepositoryUpdateTransitionsIdentityKeyAndMaintainsTombstoneDisjointness() {
        val tempDir = Files.createTempDirectory("dict_repo_edit_test_").toFile()
        try {
            val testContext = object : ContextWrapper(null) {
                override fun getFilesDir(): File = tempDir
                override fun getApplicationContext(): Context = this
            }
            val repo = DictionaryRepository(testContext)

            val initial = DictionaryEntry(
                id = "claude-1",
                word = "Claude",
                reading = "claude",
                priority = Priority.HIGH
            )
            assertTrue(repo.add(initial))

            // Upgrade English-only ("Claude", "claude") to bilingual ("Claude", "くろーど")
            val upgraded = initial.copy(reading = "くろーど")
            assertTrue(repo.update(upgraded))

            val allEntries = repo.getAll()
            assertEquals(1, allEntries.size)
            assertEquals("Claude", allEntries[0].word)
            assertEquals("くろーど", allEntries[0].reading)

            // Old English-only key must be tombstoned so remote stale copies don't resurrect it
            val tombstones = repo.getTombstones()
            assertTrue(tombstones.any { it.word == "Claude" && it.reading == "claude" })
            assertFalse(tombstones.any { it.word == "Claude" && it.reading == "くろーど" })

            // Simulate stale remote sync still carrying the old English-only entry (older timestamp)
            val staleRemote = listOf(initial.copy(updatedAtMillis = upgraded.updatedAtMillis - 10_000L))
            repo.mergeEntriesWithTombstones(staleRemote, emptyList())

            val afterMerge = repo.getAll()
            assertEquals(1, afterMerge.size)
            assertEquals("くろーど", afterMerge[0].reading)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
