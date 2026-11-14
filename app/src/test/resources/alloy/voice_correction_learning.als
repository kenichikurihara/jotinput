module jotinput/voice_correction_learning

open util/boolean
open util/ordering[StorageState]

/**
 * Formal Alloy 6 Specification for Voice Correction Learning & Scoring (BC-3):
 * 1. VoiceCorrectionEligibilityDecisionTable totality & anti-pollution invariants
 * 2. VoiceCorrectionBoostPolicyDecisionTable totality, revert self-healing & SLM gate safety
 * 3. CustomDictionaryIsolationInvariant (custom_dictionary.json is never mutated/erased)
 */

// --- Part 1: Eligibility Decision Table ---

abstract sig EligibilityOutcome {}
one sig OutIgnoreWindow, OutIgnoreSpan, OutValidOrtho, OutValidHomophone, OutValidNearMiss, OutRejectSemantic
    extends EligibilityOutcome {}

sig EligibilityInput {
    withinEditWindow: one Bool,
    spanLengthValid: one Bool,
    exactCaseOrCompound: one Bool,
    zeroPhoneticDist: one Bool,
    withinPhoneticThreshold: one Bool
}

fun evalEligibility[i: EligibilityInput]: one EligibilityOutcome {
    (i.withinEditWindow = False) => OutIgnoreWindow else
    (i.spanLengthValid = False) => OutIgnoreSpan else
    (i.exactCaseOrCompound = True) => OutValidOrtho else
    (i.zeroPhoneticDist = True) => OutValidHomophone else
    (i.withinPhoneticThreshold = True) => OutValidNearMiss else
    OutRejectSemantic
}

pred isAcceptedCorrection[o: EligibilityOutcome] {
    o in (OutValidOrtho + OutValidHomophone + OutValidNearMiss)
}

assert EligibilityTableIsTotal {
    all i: EligibilityInput | one evalEligibility[i]
}

assert NoSemanticRewritePollution {
    all i: EligibilityInput |
        (i.withinEditWindow = False or
         i.spanLengthValid = False or
         (i.exactCaseOrCompound = False and i.zeroPhoneticDist = False and i.withinPhoneticThreshold = False))
        implies not isAcceptedCorrection[evalEligibility[i]]
}

// --- Part 2: Boost Policy Decision Table ---

abstract sig BoostOutcome {}
one sig BoostSuppressed, BoostGateOffChip, BoostAutoReplace, BoostSuggestChip
    extends BoostOutcome {}

sig BoostPolicyInput {
    netCountPositive: one Bool,
    netCountAtLeastTwo: one Bool,
    isAmbiguousHomophone: one Bool,
    slmCoherencePassed: one Bool
} {
    // Structural constraint: netCount >= 2 implies netCount > 0
    netCountAtLeastTwo = True implies netCountPositive = True
}

fun evalBoostPolicy[b: BoostPolicyInput]: one BoostOutcome {
    (b.netCountPositive = False) => BoostSuppressed else
    (b.isAmbiguousHomophone = True and b.slmCoherencePassed = False) => BoostGateOffChip else
    (b.isAmbiguousHomophone = False and b.netCountPositive = True) => BoostAutoReplace else
    (b.isAmbiguousHomophone = True and b.netCountAtLeastTwo = False) => BoostSuggestChip else
    (b.netCountAtLeastTwo = True and b.slmCoherencePassed = True) => BoostAutoReplace else
    BoostSuppressed
}

assert BoostTableIsTotal {
    all b: BoostPolicyInput | one evalBoostPolicy[b]
}

assert ReverseEditSafetyInvariant {
    all b: BoostPolicyInput |
        b.netCountPositive = False implies evalBoostPolicy[b] = BoostSuppressed
}

assert AmbiguousHomophoneGateSafety {
    all b: BoostPolicyInput |
        (b.netCountPositive = True and b.isAmbiguousHomophone = True and b.slmCoherencePassed = False)
        implies evalBoostPolicy[b] = BoostGateOffChip
}

// --- Part 3: Storage Isolation Invariant ---

sig DictEntry {}
sig CorrectionItem {}

sig StorageState {
    customDictEntries: set DictEntry,
    voiceCorrectionEntries: set CorrectionItem
}

pred learnCorrection[s, sNext: StorageState, c: CorrectionItem] {
    sNext.voiceCorrectionEntries = s.voiceCorrectionEntries + c
    sNext.customDictEntries = s.customDictEntries
}

pred revertOrEvictCorrection[s, sNext: StorageState, c: CorrectionItem] {
    sNext.voiceCorrectionEntries = s.voiceCorrectionEntries - c
    sNext.customDictEntries = s.customDictEntries
}

pred clearCorrectionHistory[s, sNext: StorageState] {
    no sNext.voiceCorrectionEntries
    sNext.customDictEntries = s.customDictEntries
}

fact ValidStorageTransitions {
    all s: StorageState - last |
        let sNext = s.next |
            (some c: CorrectionItem | learnCorrection[s, sNext, c] or revertOrEvictCorrection[s, sNext, c]) or
            clearCorrectionHistory[s, sNext]
}

assert CustomDictionaryIsolationInvariant {
    all s: StorageState | s.customDictEntries = first.customDictEntries
}

// --- Non-vacuity reachability predicates ---

pred canAutoReplaceSafely {
    some b: BoostPolicyInput | evalBoostPolicy[b] = BoostAutoReplace
}

pred canGateOffAmbiguousHomophone {
    some b: BoostPolicyInput | evalBoostPolicy[b] = BoostGateOffChip
}

pred canLearnWithoutTouchingCustomDict {
    some s: StorageState - first |
        some s.voiceCorrectionEntries and s.customDictEntries = first.customDictEntries
}

check EligibilityTableIsTotal for 4
check NoSemanticRewritePollution for 4
check BoostTableIsTotal for 4
check ReverseEditSafetyInvariant for 4
check AmbiguousHomophoneGateSafety for 4
check CustomDictionaryIsolationInvariant for 4
run canAutoReplaceSafely for 3
run canGateOffAmbiguousHomophone for 3
run canLearnWithoutTouchingCustomDict for 4
