package com.example.myasr.context.english.domain

import com.example.myasr.shared.domain.Judgement
import com.example.myasr.shared.domain.JudgementResult
import com.example.myasr.shared.domain.dmn.DecisionTable
import com.example.myasr.shared.domain.dmn.DecisionTrace
import com.example.myasr.shared.domain.dmn.HitPolicy
import com.example.myasr.shared.domain.dmn.decisionTable

/**
 * BC-4 (English Typing & Swipe) におけるスワイプ大規模語彙拡張（Idea A Compact CSR Dual-Trie）の
 * 第 2 パス発火制御、および適応型オンデバイス Swipe LRU キャッシュ（Idea C）の受理・保護ポリシーを司る
 * 純粋ドメイン DMN DecisionTable / Judgement 群。
 */
object SwipeVocabularyJudgement {

    const val EXTENDED_PASS2_GATE_CTC_LOG_PROB = -0.50f
    const val EXTENDED_PASS2_BEAM_WIDTH = 40
    const val EXTENDED_OOV_PENALTY_SPATIAL = 1.50f
    const val EXTENDED_OOV_PENALTY_CONTEXT = 2.50f
    const val EXTENDED_LOG_FREQ_WEIGHT = 0.20f
    const val EXTENDED_MIN_WORD_LEN = 3
    const val EXTENDED_MAX_WORD_LEN = 28

    const val ADAPTIVE_CACHE_MAX_ENTRIES = 500
    const val ADAPTIVE_CACHE_PENDING_MAX_ENTRIES = 256
    const val ADAPTIVE_CACHE_MIN_WORD_LEN = 3
    const val ADAPTIVE_CACHE_MAX_WORD_LEN = 28

    private val ADAPTIVE_TOKEN_REGEX = Regex("^[A-Za-z][A-Za-z'\\-]*[A-Za-z]$")
    private val WHITESPACE_REGEX = Regex("\\s+")
    private const val TRIM_EDGE_PUNCTUATION = ".,!?;:()[]{}\"'“”‘’*#<>|`~"

    enum class Pass2TriggerAction {
        SKIP_NO_LEXICON,
        SKIP_BASE_CONFIDENT,
        RUN_EXTENDED_PASS2
    }

    data class Pass2TriggerInput(
        val hasExtendedLexicon: Boolean,
        val bestBaseCtcLogProb: Float,
        val gateThreshold: Float = EXTENDED_PASS2_GATE_CTC_LOG_PROB
    )

    enum class SwipeLearnSource {
        TAP_CANDIDATE_SELECTED,
        TAP_SPACE_COMMITTED,
        VOICE_ENGLISH_COMMITTED,
        SWIPE_COMMITTED,
        CLIPBOARD_PASTE_NON_SENSITIVE,
        SENSITIVE_OR_BYPASS
    }

    enum class AdaptiveCacheAdmissionAction {
        REJECT_SENSITIVE_OR_BYPASS,
        REJECT_INVALID_TOKEN,
        SKIP_COVERED_BY_USER_DICT,
        SKIP_COVERED_BY_BASE_VOCAB,
        DEFER_UNVERIFIED_SINGLE_TYPO_GUARD,
        ADMIT_TO_ADAPTIVE_CACHE
    }

    data class AdaptiveCacheAdmissionInput(
        val isSensitiveOrPasswordField: Boolean,
        val isValidSwipeToken: Boolean,
        val isExplicitUserDictWord: Boolean,
        val isAlreadyInBase32kVocab: Boolean,
        val isKnownInExtendedLexicon: Boolean,
        val commitSource: SwipeLearnSource,
        val observedCount: Int
    )

    val extendedPass2TriggerDecisionTable: DecisionTable<Pass2TriggerInput, JudgementResult<Pass2TriggerAction>> =
        decisionTable(
            name = "SwipeExtendedPass2TriggerPolicy",
            hitPolicy = HitPolicy.FIRST
        ) {
            rule(
                id = "SWIPE_EXT_R1",
                description = "Skip Pass 2 when Compact CSR extended lexicon is not loaded"
            ) {
                `when` { !it.hasExtendedLexicon }
                thenValue(JudgementResult(Pass2TriggerAction.SKIP_NO_LEXICON, "no_extended_lexicon"))
            }
            rule(
                id = "SWIPE_EXT_R2",
                description = "Skip Pass 2 at 0ms extra cost when Pass 1 base 32k match is confident"
            ) {
                `when` { it.bestBaseCtcLogProb >= it.gateThreshold }
                thenValue(JudgementResult(Pass2TriggerAction.SKIP_BASE_CONFIDENT, "base_32k_confident"))
            }
            defaultValue(
                id = "SWIPE_EXT_DEFAULT",
                description = "Run Compact CSR extended Pass 2 when base 32k yields low CTC confidence",
                value = JudgementResult(Pass2TriggerAction.RUN_EXTENDED_PASS2, "run_extended_pass2")
            )
        }

    val adaptiveCacheAdmissionDecisionTable: DecisionTable<AdaptiveCacheAdmissionInput, JudgementResult<AdaptiveCacheAdmissionAction>> =
        decisionTable(
            name = "SwipeAdaptiveCacheAdmissionPolicy",
            hitPolicy = HitPolicy.FIRST
        ) {
            rule(
                id = "SWIPE_CACHE_R1",
                description = "Reject learning from password fields, email fields, or sensitive clipboard data"
            ) {
                `when` { it.isSensitiveOrPasswordField || it.commitSource == SwipeLearnSource.SENSITIVE_OR_BYPASS }
                thenValue(
                    JudgementResult(
                        AdaptiveCacheAdmissionAction.REJECT_SENSITIVE_OR_BYPASS,
                        "sensitive_or_bypass_field"
                    )
                )
            }
            rule(
                id = "SWIPE_CACHE_R2",
                description = "Reject tokens unsuitable for QWERTY swipe Trie (too short/long, digits, symbols)"
            ) {
                `when` { !it.isValidSwipeToken }
                thenValue(
                    JudgementResult(
                        AdaptiveCacheAdmissionAction.REJECT_INVALID_TOKEN,
                        "invalid_swipe_token"
                    )
                )
            }
            rule(
                id = "SWIPE_CACHE_R3",
                description = "Skip tokens already covered by explicit custom user dictionary"
            ) {
                `when` { it.isExplicitUserDictWord }
                thenValue(
                    JudgementResult(
                        AdaptiveCacheAdmissionAction.SKIP_COVERED_BY_USER_DICT,
                        "covered_by_user_dictionary"
                    )
                )
            }
            rule(
                id = "SWIPE_CACHE_R4",
                description = "Skip tokens already indexed in FUTO base 32k Pass 1 Trie"
            ) {
                `when` { it.isAlreadyInBase32kVocab }
                thenValue(
                    JudgementResult(
                        AdaptiveCacheAdmissionAction.SKIP_COVERED_BY_BASE_VOCAB,
                        "covered_by_base_32k_vocab"
                    )
                )
            }
            rule(
                id = "SWIPE_CACHE_R5",
                description = "Guard against 1-shot raw typo pollution on unknown space-committed tokens"
            ) {
                `when` {
                    !it.isKnownInExtendedLexicon &&
                        it.commitSource == SwipeLearnSource.TAP_SPACE_COMMITTED &&
                        it.observedCount < 2
                }
                thenValue(
                    JudgementResult(
                        AdaptiveCacheAdmissionAction.DEFER_UNVERIFIED_SINGLE_TYPO_GUARD,
                        "defer_single_raw_typo"
                    )
                )
            }
            defaultValue(
                id = "SWIPE_CACHE_DEFAULT",
                description = "Admit verified or repeated OOV token into adaptive LRU cache",
                value = JudgementResult(
                    AdaptiveCacheAdmissionAction.ADMIT_TO_ADAPTIVE_CACHE,
                    "admit_to_adaptive_cache"
                )
            )
        }

    val pass2TriggerJudgement: Judgement<Pass2TriggerInput, JudgementResult<Pass2TriggerAction>> =
        extendedPass2TriggerDecisionTable
    val adaptiveCacheAdmissionJudgement: Judgement<AdaptiveCacheAdmissionInput, JudgementResult<AdaptiveCacheAdmissionAction>> =
        adaptiveCacheAdmissionDecisionTable

    fun judgePass2Trigger(
        hasExtendedLexicon: Boolean,
        bestBaseCtcLogProb: Float,
        gateThreshold: Float = EXTENDED_PASS2_GATE_CTC_LOG_PROB
    ): JudgementResult<Pass2TriggerAction> = pass2TriggerJudgement.judge(
        Pass2TriggerInput(hasExtendedLexicon, bestBaseCtcLogProb, gateThreshold)
    )

    fun tracePass2Trigger(
        hasExtendedLexicon: Boolean,
        bestBaseCtcLogProb: Float,
        gateThreshold: Float = EXTENDED_PASS2_GATE_CTC_LOG_PROB
    ): DecisionTrace<JudgementResult<Pass2TriggerAction>> = extendedPass2TriggerDecisionTable.evaluateWithTrace(
        Pass2TriggerInput(hasExtendedLexicon, bestBaseCtcLogProb, gateThreshold)
    )

    fun judgeAdaptiveCacheAdmission(
        input: AdaptiveCacheAdmissionInput
    ): JudgementResult<AdaptiveCacheAdmissionAction> = adaptiveCacheAdmissionJudgement.judge(input)

    fun traceAdaptiveCacheAdmission(
        input: AdaptiveCacheAdmissionInput
    ): DecisionTrace<JudgementResult<AdaptiveCacheAdmissionAction>> =
        adaptiveCacheAdmissionDecisionTable.evaluateWithTrace(input)

    /**
     * Checks whether [surface] is structurally valid and safe to store in the adaptive swipe Trie.
     */
    fun isValidAdaptiveSwipeToken(surface: String): Boolean {
        val trimmed = surface.trim()
        if (trimmed.length !in ADAPTIVE_CACHE_MIN_WORD_LEN..ADAPTIVE_CACHE_MAX_WORD_LEN) return false
        if (!ADAPTIVE_TOKEN_REGEX.matches(trimmed)) return false
        if (trimmed.contains("''") || trimmed.contains("--") ||
            trimmed.contains("'-") || trimmed.contains("-'")
        ) {
            return false
        }
        val trieKey = EnglishDictionaryUtils.toSwipeTrieKey(trimmed)
        if (trieKey.length !in ADAPTIVE_CACHE_MIN_WORD_LEN..ADAPTIVE_CACHE_MAX_WORD_LEN) return false
        if (trimmed.length > 10 && trimmed.all { !it.isLetter() || it.isUpperCase() }) {
            return false
        }
        return true
    }

    /**
     * Extracts clean English word tokens from committed text for adaptive cache consideration,
     * rejecting URLs, email addresses, filesystem paths, and code assignment identifiers.
     */
    fun extractEnglishTokensForLearning(text: String): List<String> {
        val trimmedText = text.trim()
        if (trimmedText.isEmpty()) return emptyList()
        val rawTokens = trimmedText.split(WHITESPACE_REGEX)
        val result = ArrayList<String>(rawTokens.size)
        for (raw in rawTokens) {
            if (raw.isEmpty()) continue
            if (raw.contains("://") || raw.contains('@') || raw.contains('/') ||
                raw.contains('\\') || raw.contains('_') || raw.contains('=')
            ) {
                continue
            }
            val clean = raw.trim { it in TRIM_EDGE_PUNCTUATION }
            if (clean.isNotEmpty() && isValidAdaptiveSwipeToken(clean)) {
                result.add(clean)
            }
        }
        return result
    }
}
