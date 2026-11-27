package com.example.myasr

import com.example.myasr.context.vocabulary.domain.DictionaryEntry
import com.example.myasr.context.vocabulary.domain.DictionaryFormGuideMode
import com.example.myasr.context.vocabulary.domain.DictionaryInputDecisionModel
import com.example.myasr.context.vocabulary.domain.DictionaryInputLogic
import com.example.myasr.context.vocabulary.domain.DictionaryReadingFormUiInput
import com.example.myasr.context.vocabulary.domain.DictionaryRegistrationInput
import com.example.myasr.context.vocabulary.domain.DictionaryRegistrationOutcome
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

    @Test
    fun `ASCII and symbol words with Kana reading resolve as bilingual entries`() {
        val claudeHiragana = DictionaryInputLogic.resolveWordAndReading("Claude", "くろーど")
        assertTrue(claudeHiragana is DictionaryInputLogic.ResolutionResult.Success)
        claudeHiragana as DictionaryInputLogic.ResolutionResult.Success
        assertEquals("Claude", claudeHiragana.word)
        assertEquals("くろーど", claudeHiragana.reading)
        assertFalse(claudeHiragana.isEnglishOnly)

        val claudeKatakana = DictionaryInputLogic.resolveWordAndReading("Claude", "クロード")
        assertTrue(claudeKatakana is DictionaryInputLogic.ResolutionResult.Success)
        claudeKatakana as DictionaryInputLogic.ResolutionResult.Success
        assertEquals("Claude", claudeKatakana.word)
        assertEquals("くろーど", claudeKatakana.reading)
        assertFalse(claudeKatakana.isEnglishOnly)

        val apiResult = DictionaryInputLogic.resolveWordAndReading("API", "えーぴーあい")
        assertTrue(apiResult is DictionaryInputLogic.ResolutionResult.Success)
        apiResult as DictionaryInputLogic.ResolutionResult.Success
        assertEquals("API", apiResult.word)
        assertEquals("えーぴーあい", apiResult.reading)
        assertFalse(apiResult.isEnglishOnly)

        val reazonResult = DictionaryInputLogic.resolveWordAndReading("ReazonSpeech", "れいぞんすぴーち")
        assertTrue(reazonResult is DictionaryInputLogic.ResolutionResult.Success)
        reazonResult as DictionaryInputLogic.ResolutionResult.Success
        assertEquals("ReazonSpeech", reazonResult.word)
        assertEquals("れいぞんすぴーち", reazonResult.reading)
        assertFalse(reazonResult.isEnglishOnly)

        val cppResult = DictionaryInputLogic.resolveWordAndReading("C++", "しーぷらぷら")
        assertTrue(cppResult is DictionaryInputLogic.ResolutionResult.Success)
        cppResult as DictionaryInputLogic.ResolutionResult.Success
        assertEquals("C++", cppResult.word)
        assertEquals("しーぷらぷら", cppResult.reading)
        assertFalse(cppResult.isEnglishOnly)

        val cicdResult = DictionaryInputLogic.resolveWordAndReading("CI/CD", "しーあいしーでぃー")
        assertTrue(cicdResult is DictionaryInputLogic.ResolutionResult.Success)
        cicdResult as DictionaryInputLogic.ResolutionResult.Success
        assertEquals("CI/CD", cicdResult.word)
        assertEquals("しーあいしーでぃー", cicdResult.reading)
        assertFalse(cicdResult.isEnglishOnly)

        val nodeResult = DictionaryInputLogic.resolveWordAndReading("Node.js", "のーどじぇいえす")
        assertTrue(nodeResult is DictionaryInputLogic.ResolutionResult.Success)
        nodeResult as DictionaryInputLogic.ResolutionResult.Success
        assertEquals("Node.js", nodeResult.word)
        assertEquals("のーどじぇいえす", nodeResult.reading)
        assertFalse(nodeResult.isEnglishOnly)
    }

    @Test
    fun `ASCII word with invalid non-kana reading is rejected on reading field`() {
        val result = DictionaryInputLogic.resolveWordAndReading("ReazonSpeech", "reazon")
        assertTrue(result is DictionaryInputLogic.ResolutionResult.Error)
        val err = result as DictionaryInputLogic.ResolutionResult.Error
        assertEquals(DictionaryInputLogic.ErrorField.READING, err.field)
    }

    @Test
    fun `DMN DictionaryReadingFormUiPolicy keeps reading always visible and reflects guide modes`() {
        val bools = listOf(false, true)
        for (hasWord in bools) {
            for (isEn in bools) {
                if (isEn && !hasWord) continue
                for (hasReading in bools) {
                    for (isKana in bools) {
                        if (isKana && !hasReading) continue
                        val trace = DictionaryInputDecisionModel.evaluateFormUi(
                            DictionaryReadingFormUiInput(hasWord, isEn, hasReading, isKana)
                        )
                        assertTrue("Reading field must always remain visible", trace.result.readingFieldVisible)
                        when {
                            !hasWord -> {
                                assertEquals("DICT_FORM_EMPTY_WORD", trace.selectedRuleId)
                                assertEquals(DictionaryFormGuideMode.NONE, trace.result.guideMode)
                                assertFalse(trace.result.readingOptional)
                            }
                            isEn && hasReading && isKana -> {
                                assertEquals("DICT_FORM_EN_BILINGUAL", trace.selectedRuleId)
                                assertEquals(DictionaryFormGuideMode.BILINGUAL_READY, trace.result.guideMode)
                                assertTrue(trace.result.readingOptional)
                            }
                            isEn && hasReading && !isKana -> {
                                assertEquals("DICT_FORM_EN_INVALID_READING", trace.selectedRuleId)
                                assertEquals(DictionaryFormGuideMode.READING_KANA_REQUIRED, trace.result.guideMode)
                                assertTrue(trace.result.readingOptional)
                            }
                            isEn && !hasReading -> {
                                assertEquals("DICT_FORM_EN_OPTIONAL", trace.selectedRuleId)
                                assertEquals(DictionaryFormGuideMode.ENGLISH_OPTIONAL_READING, trace.result.guideMode)
                                assertTrue(trace.result.readingOptional)
                            }
                            else -> {
                                assertEquals("DICT_FORM_JAPANESE_REQUIRED", trace.selectedRuleId)
                                assertEquals(DictionaryFormGuideMode.NONE, trace.result.guideMode)
                                assertFalse(trace.result.readingOptional)
                            }
                        }
                    }
                }
            }
        }

        // Wrapper integration smoke checks
        val optState = DictionaryInputLogic.evaluateFormUi("Claude", "")
        assertTrue(optState.readingFieldVisible)
        assertTrue(optState.readingOptional)
        assertEquals(DictionaryFormGuideMode.ENGLISH_OPTIONAL_READING, optState.guideMode)

        val bilingualState = DictionaryInputLogic.evaluateFormUi("Claude", "くろーど")
        assertTrue(bilingualState.readingFieldVisible)
        assertEquals(DictionaryFormGuideMode.BILINGUAL_READY, bilingualState.guideMode)

        val invalidReadingState = DictionaryInputLogic.evaluateFormUi("Claude", "claude")
        assertTrue(invalidReadingState.readingFieldVisible)
        assertEquals(DictionaryFormGuideMode.READING_KANA_REQUIRED, invalidReadingState.guideMode)
    }

    @Test
    fun `DMN DictionaryEntryRegistrationPolicy covers all 7 rules cleanly`() {
        val bilingualTrace = DictionaryInputDecisionModel.evaluateRegistration(
            DictionaryRegistrationInput(
                hasWord = true,
                isWordValidEnglish = true,
                hasReading = true,
                isReadingValidKana = true,
                isReadingValidEnglish = false
            )
        )
        assertEquals("DICT_REG_BILINGUAL_ASCII", bilingualTrace.selectedRuleId)
        assertEquals(DictionaryRegistrationOutcome.REGISTER_BILINGUAL_ASCII_WITH_KANA, bilingualTrace.result)

        val enFromWordTrace = DictionaryInputDecisionModel.evaluateRegistration(
            DictionaryRegistrationInput(
                hasWord = true,
                isWordValidEnglish = true,
                hasReading = false,
                isReadingValidKana = false,
                isReadingValidEnglish = false
            )
        )
        assertEquals("DICT_REG_ENGLISH_FROM_WORD", enFromWordTrace.selectedRuleId)
        assertEquals(DictionaryRegistrationOutcome.REGISTER_ENGLISH_ONLY_FROM_WORD, enFromWordTrace.result)

        val enFromReadingTrace = DictionaryInputDecisionModel.evaluateRegistration(
            DictionaryRegistrationInput(
                hasWord = false,
                isWordValidEnglish = false,
                hasReading = true,
                isReadingValidKana = false,
                isReadingValidEnglish = true
            )
        )
        assertEquals("DICT_REG_ENGLISH_FROM_READING", enFromReadingTrace.selectedRuleId)
        assertEquals(DictionaryRegistrationOutcome.REGISTER_ENGLISH_ONLY_FROM_READING, enFromReadingTrace.result)

        val missingWordTrace = DictionaryInputDecisionModel.evaluateRegistration(
            DictionaryRegistrationInput(
                hasWord = false,
                isWordValidEnglish = false,
                hasReading = true,
                isReadingValidKana = true,
                isReadingValidEnglish = false
            )
        )
        assertEquals("DICT_REG_WORD_REQUIRED", missingWordTrace.selectedRuleId)
        assertEquals(DictionaryRegistrationOutcome.ERROR_WORD_REQUIRED, missingWordTrace.result)

        val missingReadingTrace = DictionaryInputDecisionModel.evaluateRegistration(
            DictionaryRegistrationInput(
                hasWord = true,
                isWordValidEnglish = false,
                hasReading = false,
                isReadingValidKana = false,
                isReadingValidEnglish = false
            )
        )
        assertEquals("DICT_REG_READING_REQUIRED", missingReadingTrace.selectedRuleId)
        assertEquals(DictionaryRegistrationOutcome.ERROR_READING_REQUIRED, missingReadingTrace.result)

        val invalidKanaTrace = DictionaryInputDecisionModel.evaluateRegistration(
            DictionaryRegistrationInput(
                hasWord = true,
                isWordValidEnglish = true,
                hasReading = true,
                isReadingValidKana = false,
                isReadingValidEnglish = true
            )
        )
        assertEquals("DICT_REG_READING_INVALID_KANA", invalidKanaTrace.selectedRuleId)
        assertEquals(DictionaryRegistrationOutcome.ERROR_READING_INVALID_KANA, invalidKanaTrace.result)

        val jaTrace = DictionaryInputDecisionModel.evaluateRegistration(
            DictionaryRegistrationInput(
                hasWord = true,
                isWordValidEnglish = false,
                hasReading = true,
                isReadingValidKana = true,
                isReadingValidEnglish = false
            )
        )
        assertEquals("DICT_REG_JAPANESE_KANA", jaTrace.selectedRuleId)
        assertEquals(DictionaryRegistrationOutcome.REGISTER_JAPANESE_WITH_KANA, jaTrace.result)
    }

    @Test
    fun `initialEditReading and formatEntryReadingLabel distinguish Japanese, English-only, bilingual, and symbol entries`() {
        val jaEntry = DictionaryEntry(word = "中村", reading = "なかむら")
        val enEntry = DictionaryEntry(word = "Antigravity", reading = "antigravity")
        val bilingualEntry = DictionaryEntry(word = "Claude", reading = "くろーど")
        val symbolEntry = DictionaryEntry(word = "C++", reading = "しーぷらぷら")

        assertEquals("なかむら", DictionaryInputLogic.initialEditReading(jaEntry))
        assertEquals("", DictionaryInputLogic.initialEditReading(enEntry))
        assertEquals("くろーど", DictionaryInputLogic.initialEditReading(bilingualEntry))
        assertEquals("しーぷらぷら", DictionaryInputLogic.initialEditReading(symbolEntry))

        assertEquals("なかむら (ナカムラ)", DictionaryInputLogic.formatEntryReadingLabel(jaEntry))
        assertEquals("英語 (よみなし)", DictionaryInputLogic.formatEntryReadingLabel(enEntry))
        assertEquals("くろーど (クロード) • 日英", DictionaryInputLogic.formatEntryReadingLabel(bilingualEntry))
        assertEquals("しーぷらぷら (シープラプラ)", DictionaryInputLogic.formatEntryReadingLabel(symbolEntry))
    }
}
