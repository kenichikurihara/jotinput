package com.example.myasr.context.speech.domain

import com.example.myasr.shared.domain.dmn.DecisionTable
import com.example.myasr.shared.domain.dmn.DecisionTrace
import com.example.myasr.shared.domain.dmn.HitPolicy
import com.example.myasr.shared.domain.dmn.decisionTable

/**
 * BC-3 (Speech Recognition) における音声修正検知の妥当性判定と
 * スコア引き上げ・自己修復ポリシーを定義する純粋ドメイン DMN モデル。
 */
object VoiceCorrectionDecisionModel {

    const val DEFAULT_PHONETIC_THRESHOLD = 0.40f
    const val DEFAULT_BETA_VOICE = 2.5f
    const val DEFAULT_SLM_GATE_THETA = 4.0f
    const val DEFAULT_SLM_GATE_TEMP = 1.0f

    data class EligibilityInput(
        val isWithinEditWindow: Boolean,
        val phoneticDistance: Float,
        val isExactCaseOrCompoundFix: Boolean,
        val spanLengthValid: Boolean,
        val phoneticThreshold: Float = DEFAULT_PHONETIC_THRESHOLD
    )

    data class BoostPolicyInput(
        val netCount: Int,
        val category: CorrectionCategory,
        val isAmbiguousCommonHomophone: Boolean,
        val slmCoherencePassed: Boolean
    )

    val voiceCorrectionEligibilityDecisionTable: DecisionTable<EligibilityInput, CorrectionCategory> =
        decisionTable(
            name = "VoiceCorrectionEligibilityDecisionTable",
            hitPolicy = HitPolicy.FIRST
        ) {
            rule(id = "VC_ELIG_OUTSIDE_WINDOW", description = "Edit occurred outside post-voice anchor window") {
                `when` { !it.isWithinEditWindow }
                thenValue(CorrectionCategory.IGNORE_OUTSIDE_WINDOW)
            }
            rule(id = "VC_ELIG_INVALID_SPAN", description = "Extracted span is empty, unchanged, or exceeds max phrase length") {
                `when` { !it.spanLengthValid }
                thenValue(CorrectionCategory.IGNORE_INVALID_SPAN)
            }
            rule(id = "VC_ELIG_CASE_OR_COMPOUND", description = "Normalized surface matches (case, compound space, hyphen fix)") {
                `when` { it.isExactCaseOrCompoundFix }
                thenValue(CorrectionCategory.VALID_ORTHOGRAPHIC_OR_CASE)
            }
            rule(id = "VC_ELIG_EXACT_HOMOPHONE", description = "Zero phonetic distance (exact homophone or Kanji variant)") {
                `when` { it.phoneticDistance <= 0.001f }
                thenValue(CorrectionCategory.VALID_EXACT_HOMOPHONE)
            }
            rule(id = "VC_ELIG_NEAR_MISS", description = "Phonetic near-miss within threshold") {
                `when` { it.phoneticDistance <= it.phoneticThreshold }
                thenValue(CorrectionCategory.VALID_PHONETIC_NEAR_MISS)
            }
            defaultValue(value = CorrectionCategory.REJECT_SEMANTIC_REWRITE)
        }

    val voiceCorrectionBoostPolicyDecisionTable: DecisionTable<BoostPolicyInput, BoostAction> =
        decisionTable(
            name = "VoiceCorrectionBoostPolicyDecisionTable",
            hitPolicy = HitPolicy.FIRST
        ) {
            rule(id = "VC_BOOST_SUPPRESSED", description = "Net count <= 0 due to user revert feedback") {
                `when` { it.netCount <= 0 }
                thenValue(BoostAction.SUPPRESSED_NO_BOOST)
            }
            rule(id = "VC_BOOST_GATE_OFF", description = "SLM context coherence gate rejected ambiguous homophone substitution") {
                `when` { it.isAmbiguousCommonHomophone && !it.slmCoherencePassed }
                thenValue(BoostAction.GATE_OFF_KEEP_ORIGINAL_WITH_CHIP)
            }
            rule(id = "VC_BOOST_ORTHO_FIRST_SHOT", description = "Orthographic/case/compound fix or non-ambiguous term applies from 1st correction") {
                `when` { !it.isAmbiguousCommonHomophone && it.netCount >= 1 }
                thenValue(BoostAction.AUTO_REPLACE_AND_HOTWORD)
            }
            rule(id = "VC_BOOST_AMBIGUOUS_TIER1", description = "First-shot ambiguous common homophone shows one-tap chip") {
                `when` { it.isAmbiguousCommonHomophone && it.netCount == 1 }
                thenValue(BoostAction.SUGGEST_CHIP_AND_MILD_BOOST)
            }
            rule(id = "VC_BOOST_AMBIGUOUS_TIER2_PLUS", description = "Repeated ambiguous homophone with coherent context auto-applies") {
                `when` { it.netCount >= 2 && it.slmCoherencePassed }
                thenValue(BoostAction.AUTO_REPLACE_AND_HOTWORD)
            }
            defaultValue(value = BoostAction.SUPPRESSED_NO_BOOST)
        }

    fun evaluateEligibility(input: EligibilityInput): DecisionTrace<CorrectionCategory> =
        voiceCorrectionEligibilityDecisionTable.evaluateWithTrace(input)

    fun evaluateBoostPolicy(input: BoostPolicyInput): DecisionTrace<BoostAction> =
        voiceCorrectionBoostPolicyDecisionTable.evaluateWithTrace(input)
}
