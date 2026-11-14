package com.example.myasr.context.speech.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceCorrectionDecisionModelTest {

    @Test
    fun `eligibility table rejects edits outside window`() {
        val trace = VoiceCorrectionDecisionModel.evaluateEligibility(
            VoiceCorrectionDecisionModel.EligibilityInput(
                isWithinEditWindow = false,
                phoneticDistance = 0.0f,
                isExactCaseOrCompoundFix = true,
                spanLengthValid = true
            )
        )
        assertEquals("VC_ELIG_OUTSIDE_WINDOW", trace.selectedRuleId)
        assertEquals(CorrectionCategory.IGNORE_OUTSIDE_WINDOW, trace.result)
    }

    @Test
    fun `eligibility table rejects invalid spans`() {
        val trace = VoiceCorrectionDecisionModel.evaluateEligibility(
            VoiceCorrectionDecisionModel.EligibilityInput(
                isWithinEditWindow = true,
                phoneticDistance = 0.0f,
                isExactCaseOrCompoundFix = true,
                spanLengthValid = false
            )
        )
        assertEquals("VC_ELIG_INVALID_SPAN", trace.selectedRuleId)
        assertEquals(CorrectionCategory.IGNORE_INVALID_SPAN, trace.result)
    }

    @Test
    fun `eligibility table accepts orthographic or case fixes`() {
        val trace = VoiceCorrectionDecisionModel.evaluateEligibility(
            VoiceCorrectionDecisionModel.EligibilityInput(
                isWithinEditWindow = true,
                phoneticDistance = 0.1f,
                isExactCaseOrCompoundFix = true,
                spanLengthValid = true
            )
        )
        assertEquals("VC_ELIG_CASE_OR_COMPOUND", trace.selectedRuleId)
        assertEquals(CorrectionCategory.VALID_ORTHOGRAPHIC_OR_CASE, trace.result)
    }

    @Test
    fun `eligibility table accepts exact homophones and near misses, rejects semantic rewrites`() {
        val homophone = VoiceCorrectionDecisionModel.evaluateEligibility(
            VoiceCorrectionDecisionModel.EligibilityInput(
                isWithinEditWindow = true,
                phoneticDistance = 0.0f,
                isExactCaseOrCompoundFix = false,
                spanLengthValid = true
            )
        )
        assertEquals(CorrectionCategory.VALID_EXACT_HOMOPHONE, homophone.result)

        val nearMiss = VoiceCorrectionDecisionModel.evaluateEligibility(
            VoiceCorrectionDecisionModel.EligibilityInput(
                isWithinEditWindow = true,
                phoneticDistance = 0.25f,
                isExactCaseOrCompoundFix = false,
                spanLengthValid = true
            )
        )
        assertEquals(CorrectionCategory.VALID_PHONETIC_NEAR_MISS, nearMiss.result)

        val semantic = VoiceCorrectionDecisionModel.evaluateEligibility(
            VoiceCorrectionDecisionModel.EligibilityInput(
                isWithinEditWindow = true,
                phoneticDistance = 0.78f,
                isExactCaseOrCompoundFix = false,
                spanLengthValid = true
            )
        )
        assertEquals(CorrectionCategory.REJECT_SEMANTIC_REWRITE, semantic.result)
    }

    @Test
    fun `boost policy enforces revert suppression, SLM gate, and 3-tier promotion`() {
        val suppressed = VoiceCorrectionDecisionModel.evaluateBoostPolicy(
            VoiceCorrectionDecisionModel.BoostPolicyInput(
                netCount = 0,
                category = CorrectionCategory.VALID_EXACT_HOMOPHONE,
                isAmbiguousCommonHomophone = false,
                slmCoherencePassed = true
            )
        )
        assertEquals(BoostAction.SUPPRESSED_NO_BOOST, suppressed.result)

        val gatedOff = VoiceCorrectionDecisionModel.evaluateBoostPolicy(
            VoiceCorrectionDecisionModel.BoostPolicyInput(
                netCount = 5,
                category = CorrectionCategory.VALID_EXACT_HOMOPHONE,
                isAmbiguousCommonHomophone = true,
                slmCoherencePassed = false
            )
        )
        assertEquals(BoostAction.GATE_OFF_KEEP_ORIGINAL_WITH_CHIP, gatedOff.result)

        val orthoFirstShot = VoiceCorrectionDecisionModel.evaluateBoostPolicy(
            VoiceCorrectionDecisionModel.BoostPolicyInput(
                netCount = 1,
                category = CorrectionCategory.VALID_ORTHOGRAPHIC_OR_CASE,
                isAmbiguousCommonHomophone = false,
                slmCoherencePassed = true
            )
        )
        assertEquals(BoostAction.AUTO_REPLACE_AND_HOTWORD, orthoFirstShot.result)

        val ambiguousTier1 = VoiceCorrectionDecisionModel.evaluateBoostPolicy(
            VoiceCorrectionDecisionModel.BoostPolicyInput(
                netCount = 1,
                category = CorrectionCategory.VALID_EXACT_HOMOPHONE,
                isAmbiguousCommonHomophone = true,
                slmCoherencePassed = true
            )
        )
        assertEquals(BoostAction.SUGGEST_CHIP_AND_MILD_BOOST, ambiguousTier1.result)

        val ambiguousTier2 = VoiceCorrectionDecisionModel.evaluateBoostPolicy(
            VoiceCorrectionDecisionModel.BoostPolicyInput(
                netCount = 2,
                category = CorrectionCategory.VALID_EXACT_HOMOPHONE,
                isAmbiguousCommonHomophone = true,
                slmCoherencePassed = true
            )
        )
        assertEquals(BoostAction.AUTO_REPLACE_AND_HOTWORD, ambiguousTier2.result)
    }
}
