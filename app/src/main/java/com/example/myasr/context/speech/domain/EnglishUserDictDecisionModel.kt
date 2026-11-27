package com.example.myasr.context.speech.domain

import com.example.myasr.shared.domain.dmn.DecisionTable
import com.example.myasr.shared.domain.dmn.DecisionTrace
import com.example.myasr.shared.domain.dmn.HitPolicy
import com.example.myasr.shared.domain.dmn.decisionTable

enum class EnglishUserDictMatchKind {
    NONE,
    EXACT_NORMALIZED,
    PHONETIC_EXACT,
    PHONETIC_NEAR_MISS,
    ABBREVIATION_SUBSEQUENCE
}

enum class EnglishUserDictAction {
    NO_OP,
    AUTO_REPLACE,
    SUGGEST_CHIP,
    KEEP_ORIGINAL
}

/**
 * Pure domain DMN decision model arbitrating whether an English ASR candidate match
 * against a custom user dictionary entry is auto-applied, surfaced as a 1-tap chip,
 * or kept untouched to protect common English words.
 */
object EnglishUserDictDecisionModel {

    const val DEFAULT_SLM_THETA_NORMAL = 4.0f
    const val DEFAULT_SLM_THETA_HIGH = 4.5f
    const val DEFAULT_SLM_THETA_LOW = 3.5f
    const val DEFAULT_SLM_THETA_CASE_ONLY = 1.0f

    data class EnglishVoiceUserDictResolutionInput(
        val isEnabledEntry: Boolean,
        val matchKind: EnglishUserDictMatchKind,
        val isAlreadyExactSurface: Boolean,
        val isShortTarget: Boolean,
        val isCommonWordCollision: Boolean,
        val isCaseOnlyCommonWord: Boolean,
        val isRevertedPair: Boolean,
        val inPrecedingContext: Boolean,
        val slmAvailable: Boolean,
        val slmCoherencePassed: Boolean,
        val isHighPriority: Boolean
    )

    val resolutionPolicyDecisionTable: DecisionTable<EnglishVoiceUserDictResolutionInput, EnglishUserDictAction> =
        decisionTable(
            name = "EnglishVoiceUserDictResolutionPolicy",
            hitPolicy = HitPolicy.FIRST
        ) {
            rule(
                id = "EN_UD_DISABLED_OR_EXACT",
                description = "Disabled dictionary entry or transcription already matches exact surface"
            ) {
                `when` { !it.isEnabledEntry || it.isAlreadyExactSurface }
                thenValue(EnglishUserDictAction.NO_OP)
            }
            rule(
                id = "EN_UD_NO_MATCH",
                description = "No normalized or phonetic match found"
            ) {
                `when` { it.matchKind == EnglishUserDictMatchKind.NONE }
                thenValue(EnglishUserDictAction.NO_OP)
            }
            rule(
                id = "EN_UD_SHORT_NON_EXACT",
                description = "Short targets (<=3 alnum chars) never fuzzy/phonetically replace"
            ) {
                `when` { it.isShortTarget && it.matchKind != EnglishUserDictMatchKind.EXACT_NORMALIZED }
                thenValue(EnglishUserDictAction.KEEP_ORIGINAL)
            }
            rule(
                id = "EN_UD_REVERTED_NON_EXACT",
                description = "Previously reverted non-exact pair downgrades to 1-tap suggestion chip"
            ) {
                `when` { it.isRevertedPair && it.matchKind != EnglishUserDictMatchKind.EXACT_NORMALIZED }
                thenValue(EnglishUserDictAction.SUGGEST_CHIP)
            }
            rule(
                id = "EN_UD_CONTEXT_CONFIRMED",
                description = "Target appears in preceding context, confirming user intent"
            ) {
                `when` { it.inPrecedingContext }
                thenValue(EnglishUserDictAction.AUTO_REPLACE)
            }
            rule(
                id = "EN_UD_EXACT_NON_COLLISION",
                description = "Exact normalized match without common lowercase collision auto-applies"
            ) {
                `when` { it.matchKind == EnglishUserDictMatchKind.EXACT_NORMALIZED && !it.isCommonWordCollision }
                thenValue(EnglishUserDictAction.AUTO_REPLACE)
            }
            rule(
                id = "EN_UD_NON_COMMON_PHONETIC",
                description = "Phonetic/subsequence match on OOV or non-colliding span auto-applies"
            ) {
                `when` { !it.isCommonWordCollision }
                thenValue(EnglishUserDictAction.AUTO_REPLACE)
            }
            rule(
                id = "EN_UD_COMMON_SLM_PASSED",
                description = "Common-word collision verified by on-device SLM coherence gate auto-applies"
            ) {
                `when` { it.isCommonWordCollision && it.slmAvailable && it.slmCoherencePassed }
                thenValue(EnglishUserDictAction.AUTO_REPLACE)
            }
            rule(
                id = "EN_UD_CASE_ONLY_COMMON_KEEP",
                description = "Case-only common English word stays lowercase when unconfirmed by SLM/context"
            ) {
                `when` { it.isCaseOnlyCommonWord }
                thenValue(EnglishUserDictAction.KEEP_ORIGINAL)
            }
            rule(
                id = "EN_UD_COMMON_FALLBACK_CHIP",
                description = "Unconfirmed common-word collision surfaces safely as a 1-tap suggestion chip"
            ) {
                `when` { it.isCommonWordCollision }
                thenValue(EnglishUserDictAction.SUGGEST_CHIP)
            }
            defaultValue(value = EnglishUserDictAction.KEEP_ORIGINAL)
        }

    fun evaluateResolutionPolicy(
        input: EnglishVoiceUserDictResolutionInput
    ): DecisionTrace<EnglishUserDictAction> =
        resolutionPolicyDecisionTable.evaluateWithTrace(input)
}
