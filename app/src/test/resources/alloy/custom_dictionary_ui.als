module jotinput/custom_dictionary_ui

open util/ordering[State]
open util/boolean

/**
 * Formal Alloy 6 Specification for Custom Dictionary UI & Repository State
 * (BC-6: Vocabulary & Custom Dictionary + DictionaryActivity UX).
 *
 * Verifies the safety invariants across Add, Edit, Toggle, Delete, UndoDelete,
 * and SearchFilter transitions:
 * 1. ActiveAndTombstoneDisjoint: Active entries and tombstones never share a key.
 * 2. UndoSlotConsistent: Any key held in undoSlot is deleted (in tombstones, not active).
 * 3. UndoRestoresDeletedAndClearsTombstone: Undo right after Delete restores the key
 *    to activeEntries, removes its tombstone, and clears undoSlot.
 * 4. VisibleSubsetOfActive: Filtered visible entries are always a subset of activeEntries.
 * 5. UnfilteredShowsAllActive: When filterActive = False, visibleEntries = activeEntries.
 * 6. SearchFilterPreservesRepository: SearchFilter never mutates activeEntries,
 *    tombstones, or undoSlot.
 */

sig IdentityKey {}

abstract sig Action {}
one sig
    Init,
    AddWord,
    EditWord,
    DeleteWord,
    UndoDelete,
    ApplySearchFilter,
    ClearSearchFilter
extends Action {}

sig State {
    action: one Action,
    activeEntries: set IdentityKey,
    tombstones: set IdentityKey,
    undoSlot: lone IdentityKey,
    filterActive: one Bool,
    visibleEntries: set IdentityKey
}

pred init[s: State] {
    s.action = Init
    no s.activeEntries
    no s.tombstones
    no s.undoSlot
    s.filterActive = False
    no s.visibleEntries
}

// Helper: project visibleEntries from activeEntries according to filterActive
pred projectVisible[s: State] {
    (s.filterActive = False) implies (s.visibleEntries = s.activeEntries)
    else (s.visibleEntries in s.activeEntries)
}

// 1. AddWord (or revive a previously deleted key):
// Adds k to activeEntries, removes k from tombstones, clears undoSlot if it held k.
pred addWord[s, sNext: State, k: IdentityKey] {
    sNext.action = AddWord
    sNext.activeEntries = s.activeEntries + k
    sNext.tombstones = s.tombstones - k
    (s.undoSlot = k) implies (no sNext.undoSlot) else (sNext.undoSlot = s.undoSlot)
    sNext.filterActive = s.filterActive
    projectVisible[sNext]
}

// 2. EditWord:
// Replaces oldKey with newKey (which may equal oldKey if only priority changed).
// If key changed, oldKey becomes a tombstone (or removed) and newKey is active & un-tombstoned.
pred editWord[s, sNext: State, oldKey, newKey: IdentityKey] {
    oldKey in s.activeEntries
    sNext.action = EditWord
    (oldKey = newKey) implies {
        sNext.activeEntries = s.activeEntries
        sNext.tombstones = s.tombstones
        sNext.undoSlot = s.undoSlot
    } else {
        sNext.activeEntries = (s.activeEntries - oldKey) + newKey
        sNext.tombstones = (s.tombstones - newKey) + oldKey
        (s.undoSlot = newKey) implies (no sNext.undoSlot) else (sNext.undoSlot = s.undoSlot)
    }
    sNext.filterActive = s.filterActive
    projectVisible[sNext]
}

// 3. DeleteWord:
// Removes k from activeEntries, records tombstone for k, and places k in undoSlot.
pred deleteWord[s, sNext: State, k: IdentityKey] {
    k in s.activeEntries
    sNext.action = DeleteWord
    sNext.activeEntries = s.activeEntries - k
    sNext.tombstones = s.tombstones + k
    sNext.undoSlot = k
    sNext.filterActive = s.filterActive
    projectVisible[sNext]
}

// 4. UndoDelete:
// Restores the key in undoSlot back to activeEntries and removes its tombstone.
pred undoDelete[s, sNext: State] {
    some s.undoSlot
    let k = s.undoSlot | {
        sNext.action = UndoDelete
        sNext.activeEntries = s.activeEntries + k
        sNext.tombstones = s.tombstones - k
        no sNext.undoSlot
        sNext.filterActive = s.filterActive
        projectVisible[sNext]
    }
}

// 5. ApplySearchFilter:
// Pure view projection; does not mutate repository state.
pred applySearchFilter[s, sNext: State] {
    sNext.action = ApplySearchFilter
    sNext.activeEntries = s.activeEntries
    sNext.tombstones = s.tombstones
    sNext.undoSlot = s.undoSlot
    sNext.filterActive = True
    sNext.visibleEntries in sNext.activeEntries
}

// 6. ClearSearchFilter:
// Pure view projection; restores full activeEntries view.
pred clearSearchFilter[s, sNext: State] {
    sNext.action = ClearSearchFilter
    sNext.activeEntries = s.activeEntries
    sNext.tombstones = s.tombstones
    sNext.undoSlot = s.undoSlot
    sNext.filterActive = False
    sNext.visibleEntries = sNext.activeEntries
}

fact Traces {
    init[first]
    all s: State - last | let sNext = s.next |
        (some k: IdentityKey | addWord[s, sNext, k]) or
        (some oldK, newK: IdentityKey | editWord[s, sNext, oldK, newK]) or
        (some k: IdentityKey | deleteWord[s, sNext, k]) or
        undoDelete[s, sNext] or
        applySearchFilter[s, sNext] or
        clearSearchFilter[s, sNext]
}

// ============================================================================
// Safety Invariants
// ============================================================================

assert ActiveAndTombstoneDisjoint {
    all s: State | no (s.activeEntries & s.tombstones)
}

assert UndoSlotConsistent {
    all s: State | some s.undoSlot implies (
        (s.undoSlot in s.tombstones) and
        !(s.undoSlot in s.activeEntries)
    )
}

assert UndoRestoresDeletedAndClearsTombstone {
    all s1, s2, s3: State |
        (s2 = s1.next and s3 = s2.next and
         s2.action = DeleteWord and s3.action = UndoDelete)
        implies (
            let k = s2.undoSlot |
                (k in s3.activeEntries) and
                !(k in s3.tombstones) and
                no s3.undoSlot
        )
}

assert VisibleSubsetOfActive {
    all s: State | s.visibleEntries in s.activeEntries
}

assert UnfilteredShowsAllActive {
    all s: State | s.filterActive = False implies s.visibleEntries = s.activeEntries
}

assert SearchFilterPreservesRepository {
    all s: State - last | let sNext = s.next |
        (sNext.action = ApplySearchFilter or sNext.action = ClearSearchFilter) implies (
            sNext.activeEntries = s.activeEntries and
            sNext.tombstones = s.tombstones and
            sNext.undoSlot = s.undoSlot
        )
}

check ActiveAndTombstoneDisjoint for 4 but 6 State, 4 IdentityKey
check UndoSlotConsistent for 4 but 6 State, 4 IdentityKey
check UndoRestoresDeletedAndClearsTombstone for 4 but 6 State, 4 IdentityKey
check VisibleSubsetOfActive for 4 but 6 State, 4 IdentityKey
check UnfilteredShowsAllActive for 4 but 6 State, 4 IdentityKey
check SearchFilterPreservesRepository for 4 but 6 State, 4 IdentityKey

// ============================================================================
// Reachability (Non-Vacuity)
// ============================================================================

pred canAddDeleteAndUndo {
    some s1, s2, s3: State |
        s2 = s1.next and s3 = s2.next and
        s1.action = AddWord and
        s2.action = DeleteWord and
        s3.action = UndoDelete and
        some s3.activeEntries
}

pred canFilterAndClearFilter {
    some s1, s2, s3: State |
        s2 = s1.next and s3 = s2.next and
        some s1.activeEntries and
        s2.action = ApplySearchFilter and
        s3.action = ClearSearchFilter and
        s3.visibleEntries = s3.activeEntries
}

run canAddDeleteAndUndo for 3 but 5 State, 3 IdentityKey
run canFilterAndClearFilter for 3 but 5 State, 3 IdentityKey

// ============================================================================
// DMN Form UI & Bilingual Registration Policy Specification
// (Mirrors DictionaryReadingFormUiPolicy & DictionaryEntryRegistrationPolicy)
// ============================================================================

abstract sig FormGuideMode {}
one sig
    GuideNone,
    GuideEnglishOptionalReading,
    GuideBilingualReady,
    GuideReadingKanaRequired
extends FormGuideMode {}

sig FormInput {
    hasWord: one Bool,
    isValidEnglishWord: one Bool,
    hasReading: one Bool,
    isValidKanaReading: one Bool
} {
    isValidEnglishWord = True implies hasWord = True
    isValidKanaReading = True implies hasReading = True
}

fun evalFormReadingVisible[i: FormInput]: one Bool {
    True
}

fun evalFormGuideMode[i: FormInput]: one FormGuideMode {
    (i.hasWord = False) => GuideNone else
    (i.isValidEnglishWord = True and i.hasReading = True and i.isValidKanaReading = True) => GuideBilingualReady else
    (i.isValidEnglishWord = True and i.hasReading = True and i.isValidKanaReading = False) => GuideReadingKanaRequired else
    (i.isValidEnglishWord = True and i.hasReading = False) => GuideEnglishOptionalReading else
    GuideNone
}

abstract sig RegOutcome {}
one sig
    RegBilingualAsciiWithKana,
    RegEnglishOnlyFromWord,
    RegEnglishOnlyFromReading,
    RegJapaneseWithKana,
    ErrWordRequired,
    ErrReadingRequired,
    ErrReadingInvalidKana
extends RegOutcome {}

sig RegInput {
    hasWord: one Bool,
    isWordValidEnglish: one Bool,
    hasReading: one Bool,
    isReadingValidKana: one Bool,
    isReadingValidEnglish: one Bool
} {
    isWordValidEnglish = True implies hasWord = True
    isReadingValidKana = True implies hasReading = True
    isReadingValidEnglish = True implies hasReading = True
    !(isReadingValidKana = True and isReadingValidEnglish = True)
}

fun evalRegistrationPolicy[i: RegInput]: one RegOutcome {
    (i.hasWord = True and i.isWordValidEnglish = True and i.hasReading = True and i.isReadingValidKana = True) => RegBilingualAsciiWithKana else
    (i.hasWord = True and i.isWordValidEnglish = True and i.hasReading = False) => RegEnglishOnlyFromWord else
    (i.hasWord = False and i.hasReading = True and i.isReadingValidEnglish = True) => RegEnglishOnlyFromReading else
    (i.hasWord = False) => ErrWordRequired else
    (i.hasReading = False) => ErrReadingRequired else
    (i.isReadingValidKana = False) => ErrReadingInvalidKana else
    RegJapaneseWithKana
}

assert FormPolicyIsTotal {
    all i: FormInput | one evalFormReadingVisible[i] and one evalFormGuideMode[i]
}

assert ReadingFieldAlwaysVisibleForAllInputs {
    all i: FormInput | evalFormReadingVisible[i] = True
}

assert RegistrationPolicyIsTotal {
    all i: RegInput | one evalRegistrationPolicy[i]
}

assert AsciiWordWithValidKanaAlwaysRegistersBilingual {
    all i: RegInput |
        (i.hasWord = True and i.isWordValidEnglish = True and i.hasReading = True and i.isReadingValidKana = True)
        implies evalRegistrationPolicy[i] = RegBilingualAsciiWithKana
}

assert AsciiWordWithoutReadingStillAllowsOneStepEnglish {
    all i: RegInput |
        (i.hasWord = True and i.isWordValidEnglish = True and i.hasReading = False)
        implies evalRegistrationPolicy[i] = RegEnglishOnlyFromWord
}

assert AsciiWordWithInvalidNonKanaReadingRejected {
    all i: RegInput |
        (i.hasWord = True and i.isWordValidEnglish = True and i.hasReading = True and i.isReadingValidKana = False)
        implies evalRegistrationPolicy[i] = ErrReadingInvalidKana
}

check FormPolicyIsTotal for 4
check ReadingFieldAlwaysVisibleForAllInputs for 4
check RegistrationPolicyIsTotal for 4
check AsciiWordWithValidKanaAlwaysRegistersBilingual for 4
check AsciiWordWithoutReadingStillAllowsOneStepEnglish for 4
check AsciiWordWithInvalidNonKanaReadingRejected for 4

pred canRegisterBilingualAsciiWithKana {
    some i: RegInput | evalRegistrationPolicy[i] = RegBilingualAsciiWithKana
}

pred canRegisterOneStepEnglish {
    some i: RegInput | evalRegistrationPolicy[i] = RegEnglishOnlyFromWord
}

run canRegisterBilingualAsciiWithKana for 3
run canRegisterOneStepEnglish for 3
