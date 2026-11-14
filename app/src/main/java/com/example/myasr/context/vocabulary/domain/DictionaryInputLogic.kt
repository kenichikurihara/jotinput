package com.example.myasr.context.vocabulary.domain

import com.example.myasr.context.english.domain.EnglishDictionaryUtils
import com.example.myasr.context.kanakanji.domain.JapaneseKanaUtils

/**
 * Pure domain logic for Custom Dictionary UI input resolution, auto-reading suggestion,
 * and real-time search filtering.
 */
object DictionaryInputLogic {

    sealed class ResolutionResult {
        data class Success(
            val word: String,
            val reading: String,
            val isEnglishOnly: Boolean
        ) : ResolutionResult()

        data class Error(
            val field: ErrorField,
            val message: String
        ) : ResolutionResult()
    }

    enum class ErrorField {
        WORD,
        READING
    }

    /**
     * Resolves user input from the Word ("単語・表記") and Reading ("よみ") fields.
     *
     * Supports:
     * 1. English word/phrase in Word field (no reading needed -> 1-step registration).
     * 2. Backward-compatible fallback: English word/phrase in Reading field when Word is empty.
     * 3. Japanese entry: non-empty Word + valid Hiragana/Katakana Reading.
     */
    fun resolveWordAndReading(rawWord: String, rawReading: String): ResolutionResult {
        val word = rawWord.trim()
        val reading = rawReading.trim()

        // 1. Primary English 1-step mode: valid English word in Word field
        if (word.isNotEmpty() && EnglishDictionaryUtils.isValidEnglishWord(word) && reading.isEmpty()) {
            return ResolutionResult.Success(
                word = word,
                reading = EnglishDictionaryUtils.normalizeEnglishReading(word),
                isEnglishOnly = true
            )
        }

        // 2. Backward-compatible English mode: valid English word in Reading field when Word is empty
        if (word.isEmpty() && reading.isNotEmpty() && EnglishDictionaryUtils.isValidEnglishWord(reading)) {
            return ResolutionResult.Success(
                word = reading,
                reading = EnglishDictionaryUtils.normalizeEnglishReading(reading),
                isEnglishOnly = true
            )
        }

        // 3. Japanese mode: both Word and valid Kana Reading are required
        if (word.isEmpty()) {
            return ResolutionResult.Error(
                field = ErrorField.WORD,
                message = "単語を入力してください"
            )
        }
        if (reading.isEmpty()) {
            return ResolutionResult.Error(
                field = ErrorField.READING,
                message = "よみを入力してください"
            )
        }
        if (!JapaneseKanaUtils.isValidKana(reading)) {
            return ResolutionResult.Error(
                field = ErrorField.READING,
                message = "よみはひらがな・カタカナで入力してください"
            )
        }

        return ResolutionResult.Success(
            word = word,
            reading = JapaneseKanaUtils.normalizeReading(reading),
            isEnglishOnly = false
        )
    }

    /**
     * Returns true if the Word field alone represents a valid English-only entry
     * (used to collapse the Reading field for 1-step registration).
     */
    fun isEnglishOnlyWordInput(rawWord: String): Boolean {
        val trimmed = rawWord.trim()
        return trimmed.isNotEmpty() && EnglishDictionaryUtils.isValidEnglishWord(trimmed)
    }

    /**
     * If the user typed a Kana-only surface form (e.g. Katakana "アテンション" or Hiragana),
     * suggests the normalized Hiragana reading ("あてんしょん"). Returns null otherwise.
     */
    fun suggestReadingIfKanaOnly(rawWord: String): String? {
        val trimmed = rawWord.trim()
        if (trimmed.isEmpty()) return null
        return if (JapaneseKanaUtils.isValidKana(trimmed)) {
            JapaneseKanaUtils.normalizeReading(trimmed)
        } else {
            null
        }
    }

    /**
     * Pure view projection: filters [entries] by [query] matching either surface word
     * or reading (case-insensitive and Katakana/Hiragana-normalized).
     */
    fun filterEntries(entries: List<DictionaryEntry>, query: String): List<DictionaryEntry> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return entries

        val qLower = trimmed.lowercase()
        val qHiragana = JapaneseKanaUtils.katakanaToHiragana(trimmed).lowercase()

        return entries.filter { entry ->
            entry.word.lowercase().contains(qLower) ||
                entry.reading.lowercase().contains(qLower) ||
                entry.reading.lowercase().contains(qHiragana)
        }
    }
}
