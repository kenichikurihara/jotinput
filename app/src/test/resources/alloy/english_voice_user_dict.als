module jotinput/english_voice_user_dict

open util/boolean
open util/ordering[DictStorageState]

/**
 * Formal Alloy 6 Specification for English Voice User Dictionary Resolution (BC-3):
 * 1. EnglishVoiceUserDictResolutionPolicy DMN decision table totality & safety invariants
 * 2. Protection of short targets, common English words, and reverted pairs
 * 3. CustomDictionaryIsolationInvariant (voice resolution/revert/chip accept never mutates custom_dictionary.json)
 */

abstract sig MatchKind {}
one sig MatchNone, MatchExactNormalized, MatchPhoneticExact, MatchPhoneticNearMiss, MatchAbbrevSubseq
    extends MatchKind {}

abstract sig ResolutionAction {}
one sig ActNoOp, ActAutoReplace, ActSuggestChip, ActKeepOriginal
    extends ResolutionAction {}

sig ResolutionInput {
    isEnabledEntry: one Bool,
    matchKind: one MatchKind,
    isAlreadyExactSurface: one Bool,
    isShortTarget: one Bool,
    isCommonWordCollision: one Bool,
    isCaseOnlyCommonWord: one Bool,
    isRevertedPair: one Bool,
    inPrecedingContext: one Bool,
    slmAvailable: one Bool,
    slmCoherencePassed: one Bool,
    isHighPriority: one Bool
} {
    // Structural constraints from domain extractor:
    // Case-only common word collision is always a common-word collision on an exact normalized match
    isCaseOnlyCommonWord = True implies (isCommonWordCollision = True and matchKind = MatchExactNormalized)
    // SLM coherence can pass only if SLM was available
    slmCoherencePassed = True implies slmAvailable = True
}

fun evalResolutionPolicy[i: ResolutionInput]: one ResolutionAction {
    (i.isEnabledEntry = False or i.isAlreadyExactSurface = True) => ActNoOp else
    (i.matchKind = MatchNone) => ActNoOp else
    (i.isShortTarget = True and i.matchKind != MatchExactNormalized) => ActKeepOriginal else
    (i.isRevertedPair = True and i.matchKind != MatchExactNormalized) => ActSuggestChip else
    (i.inPrecedingContext = True) => ActAutoReplace else
    (i.matchKind = MatchExactNormalized and i.isCommonWordCollision = False) => ActAutoReplace else
    (i.isCommonWordCollision = False) => ActAutoReplace else
    (i.isCommonWordCollision = True and i.slmAvailable = True and i.slmCoherencePassed = True) => ActAutoReplace else
    (i.isCaseOnlyCommonWord = True) => ActKeepOriginal else
    (i.isCommonWordCollision = True) => ActSuggestChip else
    ActKeepOriginal
}

assert ResolutionTableIsTotal {
    all i: ResolutionInput | one evalResolutionPolicy[i]
}

assert DisabledOrAlreadyExactNeverMutates {
    all i: ResolutionInput |
        (i.isEnabledEntry = False or i.isAlreadyExactSurface = True or i.matchKind = MatchNone)
        implies evalResolutionPolicy[i] = ActNoOp
}

assert ShortTargetNeverFuzzyReplaces {
    all i: ResolutionInput |
        (i.isEnabledEntry = True and
         i.isAlreadyExactSurface = False and
         i.matchKind != MatchNone and
         i.isShortTarget = True and
         i.matchKind != MatchExactNormalized)
        implies evalResolutionPolicy[i] = ActKeepOriginal
}

assert RevertedNonExactNeverAutoReplaces {
    all i: ResolutionInput |
        (i.isEnabledEntry = True and
         i.isAlreadyExactSurface = False and
         i.matchKind != MatchNone and
         i.isRevertedPair = True and
         i.matchKind != MatchExactNormalized)
        implies evalResolutionPolicy[i] != ActAutoReplace
}

assert CommonWordCollisionNeverBlindlyAutoReplaces {
    all i: ResolutionInput |
        (i.isEnabledEntry = True and
         i.isAlreadyExactSurface = False and
         i.matchKind != MatchNone and
         i.isCommonWordCollision = True and
         i.inPrecedingContext = False and
         i.slmCoherencePassed = False)
        implies evalResolutionPolicy[i] != ActAutoReplace
}

// --- Part 2: Custom Dictionary Isolation Invariant ---

sig UserDictEntry {}
sig SuppressedVoicePair {}

sig DictStorageState {
    customDictEntries: set UserDictEntry,
    suppressedPairs: set SuppressedVoicePair
}

pred resolveVoiceUtterance[s, sNext: DictStorageState] {
    sNext.customDictEntries = s.customDictEntries
    sNext.suppressedPairs = s.suppressedPairs
}

pred recordVoiceRevert[s, sNext: DictStorageState, p: SuppressedVoicePair] {
    sNext.customDictEntries = s.customDictEntries
    sNext.suppressedPairs = s.suppressedPairs + p
}

pred acceptChipBoost[s, sNext: DictStorageState, p: SuppressedVoicePair] {
    sNext.customDictEntries = s.customDictEntries
    sNext.suppressedPairs = s.suppressedPairs - p
}

fact ValidDictTransitions {
    all s: DictStorageState - last |
        let sNext = s.next |
            resolveVoiceUtterance[s, sNext] or
            (some p: SuppressedVoicePair | recordVoiceRevert[s, sNext, p] or acceptChipBoost[s, sNext, p])
}

assert CustomDictionaryNeverMutatedByVoiceResolution {
    all s: DictStorageState | s.customDictEntries = first.customDictEntries
}

// --- Non-vacuity reachability predicates ---

pred canAutoReplace {
    some i: ResolutionInput | evalResolutionPolicy[i] = ActAutoReplace
}

pred canSuggestChip {
    some i: ResolutionInput | evalResolutionPolicy[i] = ActSuggestChip
}

pred canKeepOriginal {
    some i: ResolutionInput | evalResolutionPolicy[i] = ActKeepOriginal
}

pred canNoOp {
    some i: ResolutionInput | evalResolutionPolicy[i] = ActNoOp
}

pred canRecordRevertWithoutMutatingCustomDict {
    some s: DictStorageState - last |
        some s.next.suppressedPairs and s.next.customDictEntries = first.customDictEntries
}

check ResolutionTableIsTotal for 4
check DisabledOrAlreadyExactNeverMutates for 4
check ShortTargetNeverFuzzyReplaces for 4
check RevertedNonExactNeverAutoReplaces for 4
check CommonWordCollisionNeverBlindlyAutoReplaces for 4
check CustomDictionaryNeverMutatedByVoiceResolution for 4

run canAutoReplace for 3
run canSuggestChip for 3
run canKeepOriginal for 3
run canNoOp for 3
run canRecordRevertWithoutMutatingCustomDict for 4
