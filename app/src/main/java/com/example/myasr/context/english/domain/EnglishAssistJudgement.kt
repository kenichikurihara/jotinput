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

    enum class PrefixCompletionStrategy {
        NONE,
        PROTECT_EXACT_WORD,
        PROMOTE_PREFIX_COMPLETION,
        RAW_AND_TYPO_ONLY
    }

    data class PrefixCompletionPolicyInput(
        val rawWordLength: Int,
        val isKnownExactWord: Boolean,
        val isOovAcronym: Boolean,
        val hasPrefixCompletions: Boolean
    )

    enum class OtaOverlayAdmissionAction {
        REJECT_INVALID,
        REJECT_CAPACITY_EXCEEDED,
        REJECT_PROTECT_CORE_SHORT_WORD,
        ADMIT_NEW_WORD,
        UPGRADE_EXISTING_FREQ,
        KEEP_BUNDLED
    }

    data class OtaOverlayAdmissionInput(
        val keyLength: Int,
        val hasValidSurfaceVariants: Boolean,
        val hasPositiveLogFreq: Boolean,
        val isBundledCoreLowerWord: Boolean,
        val existsInBundledLexicon: Boolean,
        val otaLogFreqExceedsBundled: Boolean,
        val withinCapacityCap: Boolean
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

    val prefixCompletionPolicyDecisionTable: DecisionTable<PrefixCompletionPolicyInput, JudgementResult<PrefixCompletionStrategy>> =
        decisionTable(
            name = "EnglishPrefixCompletionPolicy",
            hitPolicy = HitPolicy.FIRST
        ) {
            rule(id = "EN_PREFIX_R1", description = "Disable prefix completion for 1-char or empty input") {
                `when` { it.rawWordLength < 2 }
                thenValue(JudgementResult(PrefixCompletionStrategy.NONE, "input_too_short"))
            }
            rule(id = "EN_PREFIX_R2", description = "Protect exact known word or OOV acronym at Rank 1") {
                `when` { it.isKnownExactWord || it.isOovAcronym }
                thenValue(JudgementResult(PrefixCompletionStrategy.PROTECT_EXACT_WORD, "protect_known_exact_or_acronym"))
            }
            rule(id = "EN_PREFIX_R3", description = "Promote dictionary prefix completion ahead of OOV fragment") {
                `when` { !it.isKnownExactWord && !it.isOovAcronym && it.hasPrefixCompletions }
                thenValue(JudgementResult(PrefixCompletionStrategy.PROMOTE_PREFIX_COMPLETION, "promote_oov_prefix_completion"))
            }
            defaultValue(
                id = "EN_PREFIX_DEFAULT",
                description = "Fallback to raw input and spatial typo suggestions",
                value = JudgementResult(PrefixCompletionStrategy.RAW_AND_TYPO_ONLY, "raw_and_typo_only")
            )
        }

    val otaOverlayAdmissionDecisionTable: DecisionTable<OtaOverlayAdmissionInput, JudgementResult<OtaOverlayAdmissionAction>> =
        decisionTable(
            name = "EnglishOtaOverlayAdmissionPolicy",
            hitPolicy = HitPolicy.FIRST
        ) {
            rule(id = "EN_OTA_R1", description = "Reject invalid OTA lexicon row or key length outside 2..28") {
                `when` { !it.hasValidSurfaceVariants || !it.hasPositiveLogFreq || it.keyLength < 2 || it.keyLength > 28 }
                thenValue(JudgementResult(OtaOverlayAdmissionAction.REJECT_INVALID, "invalid_ota_entry"))
            }
            rule(id = "EN_OTA_R2", description = "Reject OTA admission when memory/capacity budget cap is reached") {
                `when` { !it.withinCapacityCap }
                thenValue(JudgementResult(OtaOverlayAdmissionAction.REJECT_CAPACITY_EXCEEDED, "ota_capacity_exceeded"))
            }
            rule(id = "EN_OTA_R3", description = "Protect core short lowercase words (len <= 4) from OTA casing overwrite") {
                `when` { it.keyLength <= 4 && it.isBundledCoreLowerWord }
                thenValue(JudgementResult(OtaOverlayAdmissionAction.REJECT_PROTECT_CORE_SHORT_WORD, "protect_core_short_word"))
            }
            rule(id = "EN_OTA_R4", description = "Admit new OOV word from OTA overlay") {
                `when` { !it.existsInBundledLexicon }
                thenValue(JudgementResult(OtaOverlayAdmissionAction.ADMIT_NEW_WORD, "admit_new_ota_word"))
            }
            rule(id = "EN_OTA_R5", description = "Upgrade bundled lexicon entry when OTA entry has higher log frequency") {
                `when` { it.otaLogFreqExceedsBundled }
                thenValue(JudgementResult(OtaOverlayAdmissionAction.UPGRADE_EXISTING_FREQ, "upgrade_bundled_with_ota"))
            }
            defaultValue(
                id = "EN_OTA_DEFAULT",
                description = "Keep existing bundled lexicon entry",
                value = JudgementResult(OtaOverlayAdmissionAction.KEEP_BUNDLED, "keep_bundled_entry")
            )
        }

    val autoAssistBypassJudgement: Judgement<AutoAssistModeInput, JudgementResult<Boolean>> =
        autoAssistBypassDecisionTable
    val wordCandidateEligibilityJudgement: Judgement<WordCandidateEligibilityInput, JudgementResult<Boolean>> =
        wordCandidateEligibilityDecisionTable
    val gecClauseJudgement: Judgement<String, GecClauseDecision> = gecClauseDecisionTable
    val candidateBadgeJudgement: Judgement<CandidateBadgeInput, String> = candidateBadgeDecisionTable
    val prefixCompletionPolicyJudgement: Judgement<PrefixCompletionPolicyInput, JudgementResult<PrefixCompletionStrategy>> =
        prefixCompletionPolicyDecisionTable
    val otaOverlayAdmissionJudgement: Judgement<OtaOverlayAdmissionInput, JudgementResult<OtaOverlayAdmissionAction>> =
        otaOverlayAdmissionDecisionTable

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

    fun judgePrefixCompletionPolicy(
        rawWordLength: Int,
        isKnownExactWord: Boolean,
        isOovAcronym: Boolean,
        hasPrefixCompletions: Boolean
    ): JudgementResult<PrefixCompletionStrategy> = prefixCompletionPolicyJudgement.judge(
        PrefixCompletionPolicyInput(rawWordLength, isKnownExactWord, isOovAcronym, hasPrefixCompletions)
    )

    fun judgePrefixCompletionPolicyWithTrace(
        rawWordLength: Int,
        isKnownExactWord: Boolean,
        isOovAcronym: Boolean,
        hasPrefixCompletions: Boolean
    ): DecisionTrace<JudgementResult<PrefixCompletionStrategy>> = prefixCompletionPolicyDecisionTable.evaluateWithTrace(
        PrefixCompletionPolicyInput(rawWordLength, isKnownExactWord, isOovAcronym, hasPrefixCompletions)
    )

    fun judgeOtaOverlayAdmission(
        input: OtaOverlayAdmissionInput
    ): JudgementResult<OtaOverlayAdmissionAction> = otaOverlayAdmissionJudgement.judge(input)

    fun traceOtaOverlayAdmission(
        input: OtaOverlayAdmissionInput
    ): DecisionTrace<JudgementResult<OtaOverlayAdmissionAction>> =
        otaOverlayAdmissionDecisionTable.evaluateWithTrace(input)
}
