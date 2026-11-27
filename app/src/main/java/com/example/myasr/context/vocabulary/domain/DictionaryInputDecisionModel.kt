package com.example.myasr.context.vocabulary.domain

import com.example.myasr.shared.domain.dmn.DecisionTable
import com.example.myasr.shared.domain.dmn.DecisionTrace
import com.example.myasr.shared.domain.dmn.HitPolicy
import com.example.myasr.shared.domain.dmn.decisionTable

/**
 * Guide state shown below the reading field in the Custom Dictionary UI (add & edit forms).
 */
enum class DictionaryFormGuideMode {
    NONE,
    ENGLISH_OPTIONAL_READING,
    BILINGUAL_READY,
    READING_KANA_REQUIRED
}

/**
 * Outcome of validating and classifying Word ("単語・表記") and Reading ("よみ") inputs.
 */
enum class DictionaryRegistrationOutcome {
    REGISTER_BILINGUAL_ASCII_WITH_KANA,
    REGISTER_ENGLISH_ONLY_FROM_WORD,
    REGISTER_ENGLISH_ONLY_FROM_READING,
    REGISTER_JAPANESE_WITH_KANA,
    ERROR_WORD_REQUIRED,
    ERROR_READING_REQUIRED,
    ERROR_READING_INVALID_KANA
}

data class DictionaryReadingFormUiInput(
    val hasWord: Boolean,
    val isValidEnglishWord: Boolean,
    val hasReading: Boolean,
    val isValidKanaReading: Boolean
)

data class DictionaryReadingFormUiState(
    val readingFieldVisible: Boolean,
    val readingOptional: Boolean,
    val guideMode: DictionaryFormGuideMode
)

data class DictionaryRegistrationInput(
    val hasWord: Boolean,
    val isWordValidEnglish: Boolean,
    val hasReading: Boolean,
    val isReadingValidKana: Boolean,
    val isReadingValidEnglish: Boolean
)

/**
 * Pure domain DMN decision model for Custom Dictionary form UI visibility/guidance
 * and entry registration/validation classification.
 *
 * Ensures the reading input field always remains visible so ASCII/English words
 * can be registered both with a Japanese reading (bilingual: Japanese ASR + Kana-Kanji + English)
 * and without a reading (1-step English-only).
 */
object DictionaryInputDecisionModel {

    val formUiPolicy: DecisionTable<DictionaryReadingFormUiInput, DictionaryReadingFormUiState> =
        decisionTable(
            name = "DictionaryReadingFormUiPolicy",
            hitPolicy = HitPolicy.FIRST
        ) {
            rule(
                id = "DICT_FORM_EMPTY_WORD",
                description = "Word is empty; keep reading field visible and required for Japanese"
            ) {
                `when` { !it.hasWord }
                thenValue(
                    DictionaryReadingFormUiState(
                        readingFieldVisible = true,
                        readingOptional = false,
                        guideMode = DictionaryFormGuideMode.NONE
                    )
                )
            }
            rule(
                id = "DICT_FORM_EN_BILINGUAL",
                description = "English/ASCII word with valid Kana reading forms a bilingual entry"
            ) {
                `when` { it.isValidEnglishWord && it.hasReading && it.isValidKanaReading }
                thenValue(
                    DictionaryReadingFormUiState(
                        readingFieldVisible = true,
                        readingOptional = true,
                        guideMode = DictionaryFormGuideMode.BILINGUAL_READY
                    )
                )
            }
            rule(
                id = "DICT_FORM_EN_INVALID_READING",
                description = "English/ASCII word with non-Kana reading needs valid Kana or empty reading"
            ) {
                `when` { it.isValidEnglishWord && it.hasReading && !it.isValidKanaReading }
                thenValue(
                    DictionaryReadingFormUiState(
                        readingFieldVisible = true,
                        readingOptional = true,
                        guideMode = DictionaryFormGuideMode.READING_KANA_REQUIRED
                    )
                )
            }
            rule(
                id = "DICT_FORM_EN_OPTIONAL",
                description = "English/ASCII word with empty reading allows 1-step English or optional Kana"
            ) {
                `when` { it.isValidEnglishWord && !it.hasReading }
                thenValue(
                    DictionaryReadingFormUiState(
                        readingFieldVisible = true,
                        readingOptional = true,
                        guideMode = DictionaryFormGuideMode.ENGLISH_OPTIONAL_READING
                    )
                )
            }
            defaultValue(
                id = "DICT_FORM_JAPANESE_REQUIRED",
                description = "Non-English word requires Kana reading",
                value = DictionaryReadingFormUiState(
                    readingFieldVisible = true,
                    readingOptional = false,
                    guideMode = DictionaryFormGuideMode.NONE
                )
            )
        }

    val registrationPolicy: DecisionTable<DictionaryRegistrationInput, DictionaryRegistrationOutcome> =
        decisionTable(
            name = "DictionaryEntryRegistrationPolicy",
            hitPolicy = HitPolicy.FIRST
        ) {
            rule(
                id = "DICT_REG_BILINGUAL_ASCII",
                description = "Valid English/ASCII word paired with valid Kana reading registers bilingual entry"
            ) {
                `when` {
                    it.hasWord && it.isWordValidEnglish && it.hasReading && it.isReadingValidKana
                }
                thenValue(DictionaryRegistrationOutcome.REGISTER_BILINGUAL_ASCII_WITH_KANA)
            }
            rule(
                id = "DICT_REG_ENGLISH_FROM_WORD",
                description = "Valid English/ASCII word with empty reading registers 1-step English-only entry"
            ) {
                `when` { it.hasWord && it.isWordValidEnglish && !it.hasReading }
                thenValue(DictionaryRegistrationOutcome.REGISTER_ENGLISH_ONLY_FROM_WORD)
            }
            rule(
                id = "DICT_REG_ENGLISH_FROM_READING",
                description = "Backward-compatible fallback: empty word with English word in reading field"
            ) {
                `when` { !it.hasWord && it.hasReading && it.isReadingValidEnglish }
                thenValue(DictionaryRegistrationOutcome.REGISTER_ENGLISH_ONLY_FROM_READING)
            }
            rule(
                id = "DICT_REG_WORD_REQUIRED",
                description = "Word is empty and reading is not a fallback English word"
            ) {
                `when` { !it.hasWord }
                thenValue(DictionaryRegistrationOutcome.ERROR_WORD_REQUIRED)
            }
            rule(
                id = "DICT_REG_READING_REQUIRED",
                description = "Non-English word has empty reading"
            ) {
                `when` { !it.hasReading }
                thenValue(DictionaryRegistrationOutcome.ERROR_READING_REQUIRED)
            }
            rule(
                id = "DICT_REG_READING_INVALID_KANA",
                description = "Provided reading is not valid Hiragana/Katakana"
            ) {
                `when` { !it.isReadingValidKana }
                thenValue(DictionaryRegistrationOutcome.ERROR_READING_INVALID_KANA)
            }
            defaultValue(
                id = "DICT_REG_JAPANESE_KANA",
                description = "Word with valid Kana reading registers Japanese/symbol entry",
                value = DictionaryRegistrationOutcome.REGISTER_JAPANESE_WITH_KANA
            )
        }

    fun evaluateFormUi(input: DictionaryReadingFormUiInput): DecisionTrace<DictionaryReadingFormUiState> =
        formUiPolicy.evaluateWithTrace(input)

    fun evaluateRegistration(input: DictionaryRegistrationInput): DecisionTrace<DictionaryRegistrationOutcome> =
        registrationPolicy.evaluateWithTrace(input)
}
