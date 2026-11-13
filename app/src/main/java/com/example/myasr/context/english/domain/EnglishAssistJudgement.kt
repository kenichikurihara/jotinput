package com.example.myasr.context.english.domain

import com.example.myasr.context.kanakanji.domain.JapaneseKanaUtils
import com.example.myasr.shared.domain.Judgement
import com.example.myasr.shared.domain.JudgementResult
import com.example.myasr.shared.domain.dmn.DecisionTable
import com.example.myasr.shared.domain.dmn.DecisionTrace
import com.example.myasr.shared.domain.dmn.HitPolicy
import com.example.myasr.shared.domain.dmn.decisionTable

/**
 * BC-4 (English Typing & Swipe) における英語アシスト有効性・単語候補抽出・
 * 文脈スペル/文法修正 (GEC) 対象節抽出・候補バッジ選択を司る純粋ドメイン DMN DecisionTable / Judgement 群。
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

    val autoAssistBypassDecisionTable: DecisionTable<AutoAssistModeInput, JudgementResult<Boolean>> = decisionTable(
        name = "EnglishAutoAssistBypass",
        hitPolicy = HitPolicy.FIRST
    ) {
        rule(id = "EN_BYPASS_R1", description = "Bypass when keyboard mode is not QWERTY or QWERTY_SYMBOLS") {
            `when` { input ->
                input.mode != JapaneseKanaUtils.KeyboardMode.QWERTY &&
                    input.mode != JapaneseKanaUtils.KeyboardMode.QWERTY_SYMBOLS
            }
            thenValue(JudgementResult(true, "non_qwerty_mode"))
        }
        rule(id = "EN_BYPASS_R2", description = "Bypass when target field or symbol mode disables auto-assist") {
            `when` { it.isFieldBypassed }
            thenValue(JudgementResult(true, "field_or_symbol_bypassed"))
        }
        defaultValue(
            id = "EN_BYPASS_DEFAULT",
            description = "English auto-assist is active",
            value = JudgementResult(false, "english_auto_assist_active")
        )
    }

    val wordCandidateEligibilityDecisionTable: DecisionTable<WordCandidateEligibilityInput, JudgementResult<Boolean>> =
        decisionTable(
            name = "EnglishWordCandidateEligibility",
            hitPolicy = HitPolicy.FIRST
        ) {
            rule(id = "EN_WORD_R1", description = "Reject empty trailing word") {
                `when` { it.rawWord.isEmpty() }
                thenValue(JudgementResult(false, "empty_word"))
            }
            rule(id = "EN_WORD_R2", description = "Reject 1-char word without primary capitalization candidate") {
                `when` { it.rawWord.length < 2 && !it.hasPrimaryCapitalizationCandidate }
                thenValue(JudgementResult(false, "short_word_without_capitalization"))
            }
            defaultValue(
                id = "EN_WORD_DEFAULT",
                description = "Eligible for English word candidates",
                value = JudgementResult(true, "eligible_for_english_candidates")
            )
        }

    val gecClauseDecisionTable: DecisionTable<String, GecClauseDecision> = decisionTable(
        name = "EnglishGecClauseEligibility",
        hitPolicy = HitPolicy.FIRST
    ) {
        rule(id = "EN_GEC_R1", description = "Reject trailing clause shorter than 3 chars") {
            `when` { textBeforeCursor -> extractTrailingClause(textBeforeCursor).length < 3 }
            then { textBeforeCursor ->
                GecClauseDecision(false, extractTrailingClause(textBeforeCursor), "clause_too_short")
            }
        }
        rule(id = "EN_GEC_R2", description = "Reject single-word trailing clause") {
            `when` { textBeforeCursor -> !extractTrailingClause(textBeforeCursor).contains(" ") }
            then { textBeforeCursor ->
                GecClauseDecision(false, extractTrailingClause(textBeforeCursor), "single_word_clause")
            }
        }
        default(id = "EN_GEC_DEFAULT", description = "Eligible multi-word clause for GEC") { textBeforeCursor ->
            GecClauseDecision(true, extractTrailingClause(textBeforeCursor), "eligible_multi_word_clause")
        }
    }

    val candidateBadgeDecisionTable: DecisionTable<CandidateBadgeInput, String> = decisionTable(
        name = "EnglishCandidateBadge",
        hitPolicy = HitPolicy.PRIORITY
    ) {
        rule(id = "EN_BADGE_SLM", description = "Prefer SLM badge when SLM rerank/GEC was used", priority = 100) {
            `when` { it.slmUsed }
            thenValue("SLM")
        }
        rule(id = "EN_BADGE_USER", description = "Prefer USER badge when user dictionary candidates exist", priority = 80) {
            `when` { it.hasUserCandidates }
            thenValue("USER")
        }
        rule(id = "EN_BADGE_CAPS", description = "Prefer CAPS badge when primary capitalization applies", priority = 60) {
            `when` { it.hasPrimaryCapitalization }
            thenValue("CAPS")
        }
        defaultValue(
            id = "EN_BADGE_DEFAULT",
            description = "Fallback to SPELL badge",
            value = "SPELL"
        )
    }

    val autoAssistBypassJudgement: Judgement<AutoAssistModeInput, JudgementResult<Boolean>> =
        autoAssistBypassDecisionTable
    val wordCandidateEligibilityJudgement: Judgement<WordCandidateEligibilityInput, JudgementResult<Boolean>> =
        wordCandidateEligibilityDecisionTable
    val gecClauseJudgement: Judgement<String, GecClauseDecision> = gecClauseDecisionTable
    val candidateBadgeJudgement: Judgement<CandidateBadgeInput, String> = candidateBadgeDecisionTable

    private fun extractTrailingClause(textBeforeCursor: String): String {
        val lastSentenceStart = textBeforeCursor.lastIndexOfAny(SENTENCE_BOUNDARY_CHARS)
        return if (lastSentenceStart >= 0) {
            textBeforeCursor.substring(lastSentenceStart + 1).trim()
        } else {
            textBeforeCursor.trim()
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

    fun judgeAutoAssistBypassWithTrace(
        mode: JapaneseKanaUtils.KeyboardMode?,
        isFieldBypassed: Boolean
    ): DecisionTrace<JudgementResult<Boolean>> =
        autoAssistBypassDecisionTable.evaluateWithTrace(AutoAssistModeInput(mode, isFieldBypassed))

    fun judgeWordCandidateEligibility(
        rawWord: String,
        hasPrimaryCapitalizationCandidate: Boolean
    ): JudgementResult<Boolean> = wordCandidateEligibilityJudgement.judge(
        WordCandidateEligibilityInput(rawWord, hasPrimaryCapitalizationCandidate)
    )

    fun judgeWordCandidateEligibilityWithTrace(
        rawWord: String,
        hasPrimaryCapitalizationCandidate: Boolean
    ): DecisionTrace<JudgementResult<Boolean>> = wordCandidateEligibilityDecisionTable.evaluateWithTrace(
        WordCandidateEligibilityInput(rawWord, hasPrimaryCapitalizationCandidate)
    )

    fun judgeGecClause(textBeforeCursor: String): GecClauseDecision =
        gecClauseJudgement.judge(textBeforeCursor)

    fun judgeGecClauseWithTrace(textBeforeCursor: String): DecisionTrace<GecClauseDecision> =
        gecClauseDecisionTable.evaluateWithTrace(textBeforeCursor)

    fun judgeCandidateBadge(
        slmUsed: Boolean,
        hasUserCandidates: Boolean,
        hasPrimaryCapitalization: Boolean
    ): String = candidateBadgeJudgement.judge(
        CandidateBadgeInput(slmUsed, hasUserCandidates, hasPrimaryCapitalization)
    )

    fun judgeCandidateBadgeWithTrace(
        slmUsed: Boolean,
        hasUserCandidates: Boolean,
        hasPrimaryCapitalization: Boolean
    ): DecisionTrace<String> = candidateBadgeDecisionTable.evaluateWithTrace(
        CandidateBadgeInput(slmUsed, hasUserCandidates, hasPrimaryCapitalization)
    )
}
