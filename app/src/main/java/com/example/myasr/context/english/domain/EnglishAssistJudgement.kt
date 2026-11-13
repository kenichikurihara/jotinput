package com.example.myasr.context.english.domain

import com.example.myasr.context.kanakanji.domain.JapaneseKanaUtils
import com.example.myasr.shared.domain.Judgement
import com.example.myasr.shared.domain.JudgementResult

/**
 * BC-4 (English Typing & Swipe) における英語アシスト有効性・単語候補抽出・
 * 文脈スペル/文法修正 (GEC) 対象節抽出・候補バッジ選択を司る純粋ドメイン Judgement 群。
 */
object EnglishAssistJudgement {

    private val TRAILING_WORD_REGEX = Regex("[A-Za-z']+$")
    private val SENTENCE_BOUNDARY_CHARS = charArrayOf('.', '!', '?', '\n')

    data class AutoAssistModeInput(
        val mode: JapaneseKanaUtils.KeyboardMode?,
        val isFieldBypassed: Boolean
    )

    data class TrailingWordExtraction(
        val rawWord: String,
        val precedingContext: String
    )

    data class WordCandidateEligibilityInput(
        val rawWord: String,
        val hasPrimaryCapitalizationCandidate: Boolean
    )

    data class GecClauseDecision(
        val shouldCorrect: Boolean,
        val clause: String,
        val reason: String
    )

    data class CandidateBadgeInput(
        val slmUsed: Boolean,
        val hasUserCandidates: Boolean,
        val hasPrimaryCapitalization: Boolean
    )

    val autoAssistBypassJudgement = Judgement<AutoAssistModeInput, JudgementResult<Boolean>> { input ->
        when {
            input.mode != JapaneseKanaUtils.KeyboardMode.QWERTY &&
                input.mode != JapaneseKanaUtils.KeyboardMode.QWERTY_SYMBOLS ->
                JudgementResult(true, "non_qwerty_mode")
            input.isFieldBypassed ->
                JudgementResult(true, "field_or_symbol_bypassed")
            else ->
                JudgementResult(false, "english_auto_assist_active")
        }
    }

    val wordCandidateEligibilityJudgement = Judgement<WordCandidateEligibilityInput, JudgementResult<Boolean>> { input ->
        when {
            input.rawWord.isEmpty() ->
                JudgementResult(false, "empty_word")
            input.rawWord.length < 2 && !input.hasPrimaryCapitalizationCandidate ->
                JudgementResult(false, "short_word_without_capitalization")
            else ->
                JudgementResult(true, "eligible_for_english_candidates")
        }
    }

    val gecClauseJudgement = Judgement<String, GecClauseDecision> { textBeforeCursor ->
        val lastSentenceStart = textBeforeCursor.lastIndexOfAny(SENTENCE_BOUNDARY_CHARS)
        val clause = if (lastSentenceStart >= 0) {
            textBeforeCursor.substring(lastSentenceStart + 1).trim()
        } else {
            textBeforeCursor.trim()
        }

        when {
            clause.length < 3 ->
                GecClauseDecision(false, clause, "clause_too_short")
            !clause.contains(" ") ->
                GecClauseDecision(false, clause, "single_word_clause")
            else ->
                GecClauseDecision(true, clause, "eligible_multi_word_clause")
        }
    }

    val candidateBadgeJudgement = Judgement<CandidateBadgeInput, String> { input ->
        when {
            input.slmUsed -> "SLM"
            input.hasUserCandidates -> "USER"
            input.hasPrimaryCapitalization -> "CAPS"
            else -> "SPELL"
        }
    }

    fun extractTrailingWord(textBeforeCursor: String): TrailingWordExtraction {
        val rawWord = TRAILING_WORD_REGEX.find(textBeforeCursor)?.value ?: ""
        val precedingContext = if (rawWord.isNotEmpty()) {
            textBeforeCursor.dropLast(rawWord.length)
        } else {
            textBeforeCursor
        }
        return TrailingWordExtraction(rawWord = rawWord, precedingContext = precedingContext)
    }

    fun judgeAutoAssistBypass(
        mode: JapaneseKanaUtils.KeyboardMode?,
        isFieldBypassed: Boolean
    ): JudgementResult<Boolean> = autoAssistBypassJudgement.judge(AutoAssistModeInput(mode, isFieldBypassed))

    fun judgeWordCandidateEligibility(
        rawWord: String,
        hasPrimaryCapitalizationCandidate: Boolean
    ): JudgementResult<Boolean> = wordCandidateEligibilityJudgement.judge(
        WordCandidateEligibilityInput(rawWord, hasPrimaryCapitalizationCandidate)
    )

    fun judgeGecClause(textBeforeCursor: String): GecClauseDecision =
        gecClauseJudgement.judge(textBeforeCursor)

    fun judgeCandidateBadge(
        slmUsed: Boolean,
        hasUserCandidates: Boolean,
        hasPrimaryCapitalization: Boolean
    ): String = candidateBadgeJudgement.judge(
        CandidateBadgeInput(slmUsed, hasUserCandidates, hasPrimaryCapitalization)
    )
}
