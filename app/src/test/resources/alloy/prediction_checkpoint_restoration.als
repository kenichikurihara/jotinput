module jotinput/prediction_checkpoint_restoration

open util/ordering[TraceState]
open util/boolean

/**
 * Formal Alloy 6 Specification for Prediction Checkpoint Restoration
 * (BC-2 PredictionRestorationPolicy DMN DecisionTable + BC-7 VoiceTouchInputArbitrator).
 *
 * Verifies:
 * 1. DMN PredictionRestorationPolicy totality and safety invariants:
 *    - Never restores in non-Kana mode, active composing/streaming, or password/bypassed fields.
 *    - Matching checkpoint always triggers checkpoint restoration.
 *    - Blank or non-predictable context without checkpoint shows empty status.
 * 2. Stateful stack trace invariants:
 *    - Selecting a prediction and deleting back to the prior boundary restores the exact
 *      prior displayed candidate snapshot and reverts speculative transition history.
 */

// --- Part 1: DMN PredictionRestorationPolicy ---

abstract sig RestorationRule {}
one sig
    PredRestoreR1,
    PredRestoreR2,
    PredRestoreR3,
    PredRestoreR4,
    PredRestoreR5,
    PredRestoreR6,
    PredRestoreDefault
extends RestorationRule {}

abstract sig RestorationAction {}
one sig
    ActShowEmptyStatus,
    ActRestoreCheckpointSinglePass,
    ActRestoreCheckpointAndCompleteSlm,
    ActRecomputeFromContext
extends RestorationAction {}

sig RestorationInput {
    isKanaMode: one Bool,
    isComposingOrStreaming: one Bool,
    isPasswordOrBypassed: one Bool,
    isContextBlank: one Bool,
    hasMatchingCheckpoint: one Bool,
    checkpointIsFullyReranked: one Bool,
    hasTrailingPredictablePhrase: one Bool
}

pred predRestoreR1[i: RestorationInput] {
    i.isKanaMode = False
}

pred predRestoreR2[i: RestorationInput] {
    not predRestoreR1[i] and
    i.isComposingOrStreaming = True
}

pred predRestoreR3[i: RestorationInput] {
    not predRestoreR1[i] and
    not predRestoreR2[i] and
    i.isPasswordOrBypassed = True
}

pred predRestoreR4[i: RestorationInput] {
    not predRestoreR1[i] and
    not predRestoreR2[i] and
    not predRestoreR3[i] and
    i.hasMatchingCheckpoint = True and
    i.checkpointIsFullyReranked = True
}

pred predRestoreR5[i: RestorationInput] {
    not predRestoreR1[i] and
    not predRestoreR2[i] and
    not predRestoreR3[i] and
    not predRestoreR4[i] and
    i.hasMatchingCheckpoint = True and
    i.checkpointIsFullyReranked = False
}

pred predRestoreR6[i: RestorationInput] {
    not predRestoreR1[i] and
    not predRestoreR2[i] and
    not predRestoreR3[i] and
    not predRestoreR4[i] and
    not predRestoreR5[i] and
    (i.isContextBlank = True or i.hasTrailingPredictablePhrase = False)
}

fun evalRestorationRule[i: RestorationInput]: one RestorationRule {
    predRestoreR1[i] => PredRestoreR1 else
    predRestoreR2[i] => PredRestoreR2 else
    predRestoreR3[i] => PredRestoreR3 else
    predRestoreR4[i] => PredRestoreR4 else
    predRestoreR5[i] => PredRestoreR5 else
    predRestoreR6[i] => PredRestoreR6 else
    PredRestoreDefault
}

fun evalRestorationAction[i: RestorationInput]: one RestorationAction {
    (evalRestorationRule[i] = PredRestoreR1 or
     evalRestorationRule[i] = PredRestoreR2 or
     evalRestorationRule[i] = PredRestoreR3 or
     evalRestorationRule[i] = PredRestoreR6) => ActShowEmptyStatus else
    (evalRestorationRule[i] = PredRestoreR4) => ActRestoreCheckpointSinglePass else
    (evalRestorationRule[i] = PredRestoreR5) => ActRestoreCheckpointAndCompleteSlm else
    ActRecomputeFromContext
}

assert RestorationTableIsTotal {
    all i: RestorationInput |
        one evalRestorationRule[i] and one evalRestorationAction[i]
}

assert NeverRestoresInPasswordOrNonKanaOrComposing {
    all i: RestorationInput |
        (i.isKanaMode = False or i.isComposingOrStreaming = True or i.isPasswordOrBypassed = True)
            implies evalRestorationAction[i] = ActShowEmptyStatus
}

assert CheckpointMatchAlwaysRestoresPredictions {
    all i: RestorationInput |
        (i.isKanaMode = True and
         i.isComposingOrStreaming = False and
         i.isPasswordOrBypassed = False and
         i.hasMatchingCheckpoint = True)
            implies (evalRestorationAction[i] = ActRestoreCheckpointSinglePass or
                     evalRestorationAction[i] = ActRestoreCheckpointAndCompleteSlm)
}

assert BlankOrNonPredictableWithoutCheckpointShowsEmptyStatus {
    all i: RestorationInput |
        (i.hasMatchingCheckpoint = False and
         (i.isContextBlank = True or i.hasTrailingPredictablePhrase = False))
            implies evalRestorationAction[i] = ActShowEmptyStatus
}

// --- Part 2: Checkpoint Stack & Transition Rollback Trace ---

sig CandidateSnapshot {}
sig TransitionRecord {}

sig Checkpoint {
    snapshot: one CandidateSnapshot,
    parent: lone Checkpoint,
    rollbackRecord: lone TransitionRecord
}

fact AcyclicCheckpointParent {
    no c: Checkpoint | c in c.^parent
}

abstract sig TraceAction {}
one sig ActInit, ActCommitPhrase, ActSelectPrediction, ActDeleteBack extends TraceAction {}

sig TraceState {
    action: one TraceAction,
    stack: set Checkpoint,
    topCheckpoint: lone Checkpoint,
    displayed: lone CandidateSnapshot,
    activeTransitions: set TransitionRecord
}

pred init[s: TraceState] {
    s.action = ActInit
    no s.stack
    no s.topCheckpoint
    no s.displayed
    no s.activeTransitions
}

pred commitPhrase[s, sNext: TraceState, cp: Checkpoint] {
    cp not in s.stack
    cp.parent = s.topCheckpoint
    no cp.rollbackRecord
    sNext.action = ActCommitPhrase
    sNext.stack = s.stack + cp
    sNext.topCheckpoint = cp
    sNext.displayed = cp.snapshot
    sNext.activeTransitions = s.activeTransitions
}

pred selectPrediction[s, sNext: TraceState, cp: Checkpoint, tr: TransitionRecord] {
    some s.topCheckpoint
    cp not in s.stack
    cp.parent = s.topCheckpoint
    cp.rollbackRecord = tr
    tr not in s.activeTransitions
    sNext.action = ActSelectPrediction
    sNext.stack = s.stack + cp
    sNext.topCheckpoint = cp
    sNext.displayed = cp.snapshot
    sNext.activeTransitions = s.activeTransitions + tr
}

pred deleteToPreviousCheckpoint[s, sNext: TraceState] {
    some s.topCheckpoint
    let popped = s.topCheckpoint | {
        sNext.action = ActDeleteBack
        sNext.stack = s.stack - popped
        sNext.topCheckpoint = popped.parent
        (some popped.parent) implies (sNext.displayed = popped.parent.snapshot) else (no sNext.displayed)
        sNext.activeTransitions = s.activeTransitions - popped.rollbackRecord
    }
}

pred step[s, sNext: TraceState] {
    (some cp: Checkpoint | commitPhrase[s, sNext, cp]) or
    (some cp: Checkpoint, tr: TransitionRecord | selectPrediction[s, sNext, cp, tr]) or
    deleteToPreviousCheckpoint[s, sNext]
}

fact Trace {
    init[first]
    all s: TraceState - last | step[s, s.next]
}

assert DeleteSelectedPredictionRestoresExactCandidatesAndRevertsHistory {
    all s1, s2, s3: TraceState |
        (s2 = s1.next and s3 = s2.next and
         some s1.topCheckpoint and
         s2.action = ActSelectPrediction and
         s3.action = ActDeleteBack) implies (
            s3.topCheckpoint = s1.topCheckpoint and
            s3.displayed = s1.displayed and
            s3.activeTransitions = s1.activeTransitions
        )
}

pred canCommitSelectDeleteAndRestoreExactPredictions {
    some s1, s2, s3: TraceState |
        s2 = s1.next and s3 = s2.next and
        s1.action = ActCommitPhrase and
        s2.action = ActSelectPrediction and
        s3.action = ActDeleteBack and
        s3.topCheckpoint = s1.topCheckpoint and
        s3.displayed = s1.displayed and
        s3.activeTransitions = s1.activeTransitions
}

check RestorationTableIsTotal for 5
check NeverRestoresInPasswordOrNonKanaOrComposing for 5
check CheckpointMatchAlwaysRestoresPredictions for 5
check BlankOrNonPredictableWithoutCheckpointShowsEmptyStatus for 5
check DeleteSelectedPredictionRestoresExactCandidatesAndRevertsHistory for 5
run canCommitSelectDeleteAndRestoreExactPredictions for 5
