module english_auto_space_period

open util/ordering[State]

/**
 * Formal Alloy 6 / Alloy 4+ specification for JotInput English Auto-Space,
 * Gboard-style Double-Space Auto-Period, and Android onUpdateSelection
 * synchronization in VoiceTouchInputArbitrator.
 *
 * Models the state transitions across:
 *   - Direct character tap (Alnum vs Punctuation)
 *   - Swipe word commit
 *   - English candidate chip selection
 *   - Space press (within timeout vs expired)
 *   - Delete press
 *   - Asynchronous Android InputMethodService.onUpdateSelection callbacks
 *     (IME self-echo vs external cursor tap)
 */

abstract sig CharClass {}
one sig Empty, Space, Alnum, ClosingQuoteOrPct, SentenceEndPunct, OtherSepPunct, OpenOrIdentSym extends CharClass {}

abstract sig DeltaMatch {}
one sig DeltaZero, DeltaMatchesIme, DeltaMismatchesIme extends DeltaMatch {}

abstract sig Action {}
one sig
    Init,
    TypeDirectAlnum,
    TypeDirectPunct,
    CommitSwipeWord,
    SelectEnglishCandidate,
    SpacePressFast,
    SpacePressSlow,
    DeletePress,
    ImeSelectionEcho,
    ExternalCursorMove
extends Action {}

sig State {
    action: one Action,
    bypass: one Bool,
    composing: one Bool,
    cursorChar: one CharClass,
    // Character immediately before a trailing Space (for double-space auto-period verification)
    charBeforeTrailingSpace: one CharClass,
    pendingAutoSpace: one Bool,
    lastWasSwipe: one Bool,
    autoPeriodWindow: one Bool,
    pendingImeDelta: one DeltaMatch,
    // Observable outputs of the transition that produced this State
    outPrependedSpace: one Bool,
    outConvertedToPeriod: one Bool
}

abstract sig Bool {}
one sig True, False extends Bool {}

pred isAutoSpacePreceding[c: CharClass] {
    c in (Alnum + ClosingQuoteOrPct + SentenceEndPunct + OtherSepPunct)
}

pred isAutoPeriodEligible[c: CharClass] {
    c in (Alnum + ClosingQuoteOrPct)
}

fact InitialState {
    let s0 = first |
        s0.action = Init and
        s0.composing = False and
        s0.cursorChar = Empty and
        s0.charBeforeTrailingSpace = Empty and
        s0.pendingAutoSpace = False and
        s0.lastWasSwipe = False and
        s0.autoPeriodWindow = False and
        s0.pendingImeDelta = DeltaZero and
        s0.outPrependedSpace = False and
        s0.outConvertedToPeriod = False
}

pred shouldAutoSpaceOnDirectAlnum[s: State] {
    s.bypass = False and
    (s.pendingAutoSpace = True or s.lastWasSwipe = True) and
    s.cursorChar != Space and
    s.cursorChar != Empty
}

pred shouldAutoSpaceOnSwipe[s: State] {
    s.bypass = False and
    (
        (s.cursorChar != Empty and s.cursorChar != Space and isAutoSpacePreceding[s.cursorChar])
        or
        (s.cursorChar = Empty and s.lastWasSwipe = True)
    )
}

pred canConvertDoubleSpaceToPeriod[s: State] {
    s.bypass = False and
    s.composing = False and
    s.autoPeriodWindow = True and
    s.cursorChar = Space and
    isAutoPeriodEligible[s.charBeforeTrailingSpace]
}

pred step[s, sNext: State] {
    sNext.bypass = s.bypass and
    (
        // 1. Direct Alphanumeric Tap
        (
            sNext.action = TypeDirectAlnum and
            sNext.composing = False and
            (shouldAutoSpaceOnDirectAlnum[s] => sNext.outPrependedSpace = True else sNext.outPrependedSpace = False) and
            sNext.outConvertedToPeriod = False and
            sNext.cursorChar = Alnum and
            sNext.charBeforeTrailingSpace = Empty and
            sNext.pendingAutoSpace = False and
            sNext.lastWasSwipe = False and
            sNext.autoPeriodWindow = False and
            sNext.pendingImeDelta = DeltaMatchesIme
        )
        or
        // 2. Direct Punctuation Tap (must NEVER prepend space)
        (
            sNext.action = TypeDirectPunct and
            sNext.composing = False and
            sNext.outPrependedSpace = False and
            sNext.outConvertedToPeriod = False and
            (sNext.cursorChar in (SentenceEndPunct + OtherSepPunct + OpenOrIdentSym + ClosingQuoteOrPct)) and
            sNext.charBeforeTrailingSpace = Empty and
            sNext.pendingAutoSpace = False and
            sNext.lastWasSwipe = False and
            sNext.autoPeriodWindow = False and
            sNext.pendingImeDelta = DeltaMatchesIme
        )
        or
        // 3. Swipe Word Commit
        (
            sNext.action = CommitSwipeWord and
            sNext.composing = False and
            (shouldAutoSpaceOnSwipe[s] => sNext.outPrependedSpace = True else sNext.outPrependedSpace = False) and
            sNext.outConvertedToPeriod = False and
            sNext.cursorChar = Alnum and
            sNext.charBeforeTrailingSpace = Empty and
            sNext.lastWasSwipe = True and
            (s.bypass = False => sNext.pendingAutoSpace = True else sNext.pendingAutoSpace = False) and
            sNext.autoPeriodWindow = False and
            sNext.pendingImeDelta = DeltaMatchesIme
        )
        or
        // 4. English Candidate Selection (typo / cap / gec / user_english / swipe alt)
        (
            sNext.action = SelectEnglishCandidate and
            sNext.composing = False and
            sNext.outPrependedSpace = False and
            sNext.outConvertedToPeriod = False and
            sNext.cursorChar = Alnum and
            sNext.charBeforeTrailingSpace = Empty and
            (s.bypass = False => sNext.pendingAutoSpace = True else sNext.pendingAutoSpace = False) and
            sNext.lastWasSwipe = s.lastWasSwipe and
            sNext.autoPeriodWindow = False and
            sNext.pendingImeDelta = DeltaMatchesIme
        )
        or
        // 5. Space Press (within DOUBLE_SPACE_TIMEOUT_MS)
        (
            sNext.action = SpacePressFast and
            sNext.composing = False and
            sNext.outPrependedSpace = False and
            sNext.pendingAutoSpace = False and
            sNext.lastWasSwipe = False and
            sNext.pendingImeDelta = DeltaMatchesIme and
            (
                canConvertDoubleSpaceToPeriod[s] implies (
                    sNext.outConvertedToPeriod = True and
                    sNext.cursorChar = Space and
                    sNext.charBeforeTrailingSpace = SentenceEndPunct and
                    sNext.autoPeriodWindow = False
                ) else (
                    sNext.outConvertedToPeriod = False and
                    sNext.cursorChar = Space and
                    sNext.charBeforeTrailingSpace = s.cursorChar and
                    (
                        (s.bypass = False and isAutoPeriodEligible[s.cursorChar])
                        => sNext.autoPeriodWindow = True
                        else sNext.autoPeriodWindow = False
                    )
                )
            )
        )
        or
        // 6. Space Press (after DOUBLE_SPACE_TIMEOUT_MS expired)
        (
            sNext.action = SpacePressSlow and
            sNext.composing = False and
            sNext.outPrependedSpace = False and
            sNext.outConvertedToPeriod = False and
            sNext.pendingAutoSpace = False and
            sNext.lastWasSwipe = False and
            sNext.pendingImeDelta = DeltaMatchesIme and
            sNext.cursorChar = Space and
            sNext.charBeforeTrailingSpace = s.cursorChar and
            (
                (s.bypass = False and isAutoPeriodEligible[s.cursorChar])
                => sNext.autoPeriodWindow = True
                else sNext.autoPeriodWindow = False
            )
        )
        or
        // 7. Delete Press
        (
            sNext.action = DeletePress and
            sNext.composing = False and
            sNext.outPrependedSpace = False and
            sNext.outConvertedToPeriod = False and
            sNext.pendingAutoSpace = False and
            sNext.lastWasSwipe = False and
            sNext.autoPeriodWindow = False and
            sNext.pendingImeDelta = DeltaMatchesIme and
            sNext.charBeforeTrailingSpace = Empty
        )
        or
        // 8. Asynchronous Android onUpdateSelection echo of IME's own commit/delete
        (
            sNext.action = ImeSelectionEcho and
            s.pendingImeDelta = DeltaMatchesIme and
            sNext.composing = s.composing and
            sNext.cursorChar = s.cursorChar and
            sNext.charBeforeTrailingSpace = s.charBeforeTrailingSpace and
            // CRITICAL: IME echo MUST preserve pendingAutoSpace and autoPeriodWindow!
            sNext.pendingAutoSpace = s.pendingAutoSpace and
            sNext.lastWasSwipe = s.lastWasSwipe and
            sNext.autoPeriodWindow = s.autoPeriodWindow and
            sNext.pendingImeDelta = DeltaZero and
            sNext.outPrependedSpace = False and
            sNext.outConvertedToPeriod = False
        )
        or
        // 9. External cursor tap / selection change by user in EditText
        (
            sNext.action = ExternalCursorMove and
            sNext.composing = False and
            sNext.pendingAutoSpace = False and
            sNext.lastWasSwipe = s.lastWasSwipe and
            sNext.autoPeriodWindow = False and
            sNext.pendingImeDelta = DeltaZero and
            sNext.outPrependedSpace = False and
            sNext.outConvertedToPeriod = False
        )
    )
}

fact Trace {
    all s: State - last | step[s, s.next]
}

// ============================================================================
// Safety & Liveness Assertions
// ============================================================================

// A1: Auto-period NEVER fires when bypassed, after Space/Punct/Empty, or after timeout
assert NoIllegalAutoPeriod {
    all s: State | s.outConvertedToPeriod = True implies (
        let prev = s.prev |
            s.action = SpacePressFast and
            prev.bypass = False and
            prev.cursorChar = Space and
            isAutoPeriodEligible[prev.charBeforeTrailingSpace] and
            prev.autoPeriodWindow = True
    )
}

// A2: Triple space NEVER produces a second period (".. ")
assert NoTripleSpaceDoublePeriod {
    all s: State | s.outConvertedToPeriod = True implies s.autoPeriodWindow = False
}

// A3: Direct punctuation tap NEVER prepends a space
assert PunctuationNeverPrependsSpace {
    all s: State | s.action = TypeDirectPunct implies s.outPrependedSpace = False
}

// A4: Auto-space NEVER prepends a space when cursor already follows Space or Empty or when bypassed
assert NoDoubleSpaceOnAutoSpace {
    all s: State | s.outPrependedSpace = True implies (
        let prev = s.prev |
            prev.bypass = False and
            prev.cursorChar != Space
    )
}

// A5: IME selection echo after 1st space preserves auto-period eligibility for 2nd space
assert DoubleSpaceSurvivesImeEcho {
    all s1, s2, s3: State |
        (s2 = s1.next and s3 = s2.next and
         s1.bypass = False and isAutoPeriodEligible[s1.cursorChar] and
         s2.action = SpacePressFast and s2.outConvertedToPeriod = False and
         s3.action = ImeSelectionEcho)
        implies s3.autoPeriodWindow = True
}

// A6: IME selection echo after English candidate selection preserves pendingAutoSpace
assert CandidateAutoSpaceSurvivesImeEcho {
    all s1, s2: State |
        (s2 = s1.next and
         s1.action = SelectEnglishCandidate and s1.bypass = False and
         s2.action = ImeSelectionEcho)
        implies s2.pendingAutoSpace = True
}

// A7: External cursor movement ALWAYS clears auto-assist state
assert ExternalMoveClearsAutoAssist {
    all s: State | s.action = ExternalCursorMove implies (
        s.pendingAutoSpace = False and s.autoPeriodWindow = False
    )
}

check NoIllegalAutoPeriod for 8 State
check NoTripleSpaceDoublePeriod for 8 State
check PunctuationNeverPrependsSpace for 8 State
check NoDoubleSpaceOnAutoSpace for 8 State
check DoubleSpaceSurvivesImeEcho for 8 State
check CandidateAutoSpaceSurvivesImeEcho for 8 State
check ExternalMoveClearsAutoAssist for 8 State
