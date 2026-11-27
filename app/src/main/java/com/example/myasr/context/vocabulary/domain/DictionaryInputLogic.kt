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
     * Resolves user input from the Word ("単語・表記") and Reading ("よみ") fields
     * via [DictionaryInputDecisionModel.registrationPolicy].
     *
     * Supports:
     * 1. Bilingual ASCII/English word in Word field + Hiragana/Katakana in Reading field.
     * 2. English word/phrase in Word field with empty Reading -> 1-step English-only registration.
     * 3. Backward-compatible fallback: English word/phrase in Reading field when Word is empty.
     * 4. Japanese/symbol entry: non-empty Word + valid Hiragana/Katakana Reading.
     */
    fun resolveWordAndReading(rawWord: String, rawReading: String): ResolutionResult {
        val word = rawWord.trim()
        val reading = rawReading.trim()

        val isWordValidEnglish = word.isNotEmpty() && EnglishDictionaryUtils.isValidEnglishWord(word)
        val isReadingValidKana = reading.isNotEmpty() && JapaneseKanaUtils.isValidKana(reading)
        val isReadingValidEnglish = reading.isNotEmpty() && EnglishDictionaryUtils.isValidEnglishWord(reading)

        val outcome = DictionaryInputDecisionModel.evaluateRegistration(
            DictionaryRegistrationInput(
                hasWord = word.isNotEmpty(),
                isWordValidEnglish = isWordValidEnglish,
                hasReading = reading.isNotEmpty(),
                isReadingValidKana = isReadingValidKana,
                isReadingValidEnglish = isReadingValidEnglish
            )
        ).result

        return when (outcome) {
            DictionaryRegistrationOutcome.REGISTER_BILINGUAL_ASCII_WITH_KANA,
            DictionaryRegistrationOutcome.REGISTER_JAPANESE_WITH_KANA -> ResolutionResult.Success(
                word = word,
                reading = JapaneseKanaUtils.normalizeReading(reading),
                isEnglishOnly = false
            )
            DictionaryRegistrationOutcome.REGISTER_ENGLISH_ONLY_FROM_WORD -> ResolutionResult.Success(
                word = word,
                reading = EnglishDictionaryUtils.normalizeEnglishReading(word),
                isEnglishOnly = true
            )
            DictionaryRegistrationOutcome.REGISTER_ENGLISH_ONLY_FROM_READING -> ResolutionResult.Success(
                word = reading,
                reading = EnglishDictionaryUtils.normalizeEnglishReading(reading),
                isEnglishOnly = true
            )
            DictionaryRegistrationOutcome.ERROR_WORD_REQUIRED -> ResolutionResult.Error(
                field = ErrorField.WORD,
                message = "単語を入力してください"
            )
            DictionaryRegistrationOutcome.ERROR_READING_REQUIRED -> ResolutionResult.Error(
                field = ErrorField.READING,
                message = "よみを入力してください"
            )
            DictionaryRegistrationOutcome.ERROR_READING_INVALID_KANA -> ResolutionResult.Error(
                field = ErrorField.READING,
                message = if (isWordValidEnglish) {
                    "よみはひらがな・カタカナで入力してください（英語のみは空欄）"
                } else {
                    "よみはひらがな・カタカナで入力してください"
                }
            )
        }
    }

    /**
     * Evaluates the Reading field UI visibility, optionality, and bilingual guide mode
     * via [DictionaryInputDecisionModel.formUiPolicy].
     */
    fun evaluateFormUi(rawWord: String, rawReading: String): DictionaryReadingFormUiState {
        val word = rawWord.trim()
        val reading = rawReading.trim()
        return DictionaryInputDecisionModel.evaluateFormUi(
            DictionaryReadingFormUiInput(
                hasWord = word.isNotEmpty(),
                isValidEnglishWord = word.isNotEmpty() && EnglishDictionaryUtils.isValidEnglishWord(word),
                hasReading = reading.isNotEmpty(),
                isValidKanaReading = reading.isNotEmpty() && JapaneseKanaUtils.isValidKana(reading)
            )
        ).result
    }

    /**
     * Returns true if the Word field alone represents a valid English word
     * (meaning the Reading field is optional rather than hidden).
     */
    fun isEnglishOnlyWordInput(rawWord: String): Boolean {
        val trimmed = rawWord.trim()
        return trimmed.isNotEmpty() && EnglishDictionaryUtils.isValidEnglishWord(trimmed)
    }

    /**
     * Initial text for the edit-sheet Reading field: empty for English-only entries
     * (allowing users to either keep English-only or add a Kana reading) and the stored
     * reading for Japanese/bilingual entries.
     */
    fun initialEditReading(entry: DictionaryEntry): String {
        return if (entry.isEnglishOnlyEntry) "" else entry.reading
    }

    /**
     * Formats the subtitle reading label displayed in the dictionary list.
     */
    fun formatEntryReadingLabel(entry: DictionaryEntry): String {
        if (entry.isEnglishOnlyEntry) {
            return "英語 (よみなし)"
        }
        val katakana = JapaneseKanaUtils.hiraganaToKatakana(entry.reading)
        return if (entry.hasEnglishSurface) {
            "${entry.reading} ($katakana) • 日英"
        } else {
            "${entry.reading} ($katakana)"
        }
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
