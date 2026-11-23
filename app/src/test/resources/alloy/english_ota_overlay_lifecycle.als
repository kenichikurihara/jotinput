module jotinput/english_ota_overlay_lifecycle

open util/boolean
open util/ordering[State]

/**
 * Formal Alloy 6 Specification for JotInput Phase 3 English OTA Lexicon Overlay
 * DMN Policy (EnglishOtaOverlayAdmissionPolicy) and Reload Lifecycle Invariants.
 */

abstract sig OtaRule {}
one sig EnOtaR1, EnOtaR2, EnOtaR3, EnOtaR4, EnOtaR5, EnOtaDefault extends OtaRule {}

abstract sig OtaAction {}
one sig
    ActRejectInvalid,
    ActRejectCapacity,
    ActProtectCoreShortWord,
    ActAdmitNewWord,
    ActUpgradeExistingFreq,
    ActKeepBundled
extends OtaAction {}

abstract sig CustomDictItem {}
one sig UserWordA extends CustomDictItem {}

abstract sig LexEntry {
    isCoreShortLower: one Bool
}
one sig CoreShortWord, BundledLongWord, OtaTechWord extends LexEntry {}

fact EntryProperties {
    CoreShortWord.isCoreShortLower = True
    BundledLongWord.isCoreShortLower = False
    OtaTechWord.isCoreShortLower = False
}

sig AdmissionCandidate {
    entry: one LexEntry,
    validVariants: one Bool,
    positiveLogFreq: one Bool,
    validLength: one Bool,
    withinCapacity: one Bool,
    inBundled: one Bool,
    otaExceedsBundled: one Bool
} {
    (entry = CoreShortWord) implies (inBundled = True)
    (entry = BundledLongWord) implies (inBundled = True)
    (entry = OtaTechWord) implies (inBundled = False)
}

pred enOtaR1[c: AdmissionCandidate] {
    c.validVariants = False or c.positiveLogFreq = False or c.validLength = False
}

pred enOtaR2[c: AdmissionCandidate] {
    not enOtaR1[c] and c.withinCapacity = False
}

pred enOtaR3[c: AdmissionCandidate] {
    not enOtaR1[c] and not enOtaR2[c] and c.entry.isCoreShortLower = True
}

pred enOtaR4[c: AdmissionCandidate] {
    not enOtaR1[c] and not enOtaR2[c] and not enOtaR3[c] and c.inBundled = False
}

pred enOtaR5[c: AdmissionCandidate] {
    not enOtaR1[c] and not enOtaR2[c] and not enOtaR3[c] and not enOtaR4[c] and c.otaExceedsBundled = True
}

fun evalOtaRule[c: AdmissionCandidate]: one OtaRule {
    enOtaR1[c] => EnOtaR1 else
    enOtaR2[c] => EnOtaR2 else
    enOtaR3[c] => EnOtaR3 else
    enOtaR4[c] => EnOtaR4 else
    enOtaR5[c] => EnOtaR5 else
    EnOtaDefault
}

fun evalOtaAction[c: AdmissionCandidate]: one OtaAction {
    let r = evalOtaRule[c] |
        (r = EnOtaR1) => ActRejectInvalid else
        (r = EnOtaR2) => ActRejectCapacity else
        (r = EnOtaR3) => ActProtectCoreShortWord else
        (r = EnOtaR4) => ActAdmitNewWord else
        (r = EnOtaR5) => ActUpgradeExistingFreq else
        ActKeepBundled
}

sig State {
    bundled: set LexEntry,
    otaAdmitted: set LexEntry,
    active: set LexEntry,
    customDict: set CustomDictItem
}

pred activeMatchesOverlays[s: State] {
    s.active = s.bundled + s.otaAdmitted
}

pred installOta[s, sNext: State, c: AdmissionCandidate] {
    let act = evalOtaAction[c] | {
        sNext.bundled = s.bundled
        sNext.customDict = s.customDict
        (act in (ActAdmitNewWord + ActUpgradeExistingFreq)) implies (
            sNext.otaAdmitted = s.otaAdmitted + c.entry
        ) else (
            sNext.otaAdmitted = s.otaAdmitted
        )
        CoreShortWord not in sNext.otaAdmitted
        activeMatchesOverlays[sNext]
    }
}

pred deleteOta[s, sNext: State] {
    sNext.bundled = s.bundled
    sNext.customDict = s.customDict
    no sNext.otaAdmitted
    sNext.active = sNext.bundled
}

fact LifecycleTrace {
    all s: State | {
        s.bundled = (CoreShortWord + BundledLongWord)
        activeMatchesOverlays[s]
        no (s.otaAdmitted & CoreShortWord)
    }
    no first.otaAdmitted
    first.customDict = UserWordA
    all s: State - last | let sNext = s.next |
        (some c: AdmissionCandidate | installOta[s, sNext, c]) or deleteOta[s, sNext]
}

// Assertion 1: DMN DecisionTable is total across all inputs
assert OtaAdmissionTableIsTotal {
    all c: AdmissionCandidate | one evalOtaRule[c] and one evalOtaAction[c]
}

// Assertion 2: Core short lowercase words (len <= 4) are never hijacked/overwritten by OTA
assert CoreShortLowerWordNeverHijackedByOta {
    all c: AdmissionCandidate |
        (c.entry.isCoreShortLower = True) implies
            (evalOtaAction[c] not in (ActAdmitNewWord + ActUpgradeExistingFreq))
}

// Assertion 3: Invalid or over-capacity candidates are never admitted
assert InvalidOrOverCapacityNeverAdmitted {
    all c: AdmissionCandidate |
        (c.validVariants = False or c.positiveLogFreq = False or c.validLength = False or c.withinCapacity = False) implies
            (evalOtaAction[c] not in (ActAdmitNewWord + ActUpgradeExistingFreq))
}

// Assertion 4: Custom user dictionary is completely isolated from OTA install/delete ops
assert UserCustomDictionaryIsolatedFromOtaOps {
    all s: State | s.customDict = first.customDict
}

// Assertion 5: Deleting OTA restores the active lexicon strictly to bundled entries
assert OtaDeleteRestoresBundledLexicon {
    all s: State - last | let sNext = s.next |
        deleteOta[s, sNext] implies (sNext.active = sNext.bundled and no sNext.otaAdmitted)
}

// Non-vacuity reachability predicates
pred canAdmitNewOtaWordAndPreserveCore {
    some c: AdmissionCandidate | evalOtaAction[c] = ActAdmitNewWord
    some s: State | OtaTechWord in s.active and CoreShortWord in s.bundled and CoreShortWord not in s.otaAdmitted
}

pred canDeleteOtaAndRestoreBundled {
    some s: State - last | (OtaTechWord in s.otaAdmitted) and deleteOta[s, s.next]
}

check OtaAdmissionTableIsTotal for 5
check CoreShortLowerWordNeverHijackedByOta for 5
check InvalidOrOverCapacityNeverAdmitted for 5
check UserCustomDictionaryIsolatedFromOtaOps for 4
check OtaDeleteRestoresBundledLexicon for 4
run canAdmitNewOtaWordAndPreserveCore for 4
run canDeleteOtaAndRestoreBundled for 4
