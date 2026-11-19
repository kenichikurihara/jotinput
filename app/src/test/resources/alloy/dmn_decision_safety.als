module jotinput/dmn_decision_safety

open util/boolean

/**
 * Formal Alloy 6 Specification for JotInput DMN Decision Tables:
 * 1. BC-2 TapSafeCandidateOrdering (Policy ⑤)
 * 2. BC-8 ClipboardSuggestionEligibility
 * 3. General FIRST-hit DMN DecisionTable Totality
 */

// --- Part 1: TapSafeCandidateOrdering (BC-2) ---

abstract sig TapSafeRule {}
one sig TapSafeR1, TapSafeR2, TapSafeR3, TapSafeR4, TapSafeDefault extends TapSafeRule {}

sig TapSafeInput {
    hasInitialTop1: one Bool,
    top1Unchanged: one Bool,
    withinSafeWindow: one Bool,
    userTouching: one Bool,
    preservedPartial: one Bool,
    newTopFull: one Bool,
    highUtilityPrediction: one Bool
}

pred tapSafeR1Matches[i: TapSafeInput] {
    i.hasInitialTop1 = False or i.top1Unchanged = True
}

pred tapSafeR2Matches[i: TapSafeInput] {
    not tapSafeR1Matches[i] and
    i.withinSafeWindow = True and i.userTouching = False
}

pred tapSafeR3Matches[i: TapSafeInput] {
    not tapSafeR1Matches[i] and
    not tapSafeR2Matches[i] and
    i.preservedPartial = True and i.newTopFull = True
}

pred tapSafeR4Matches[i: TapSafeInput] {
    not tapSafeR1Matches[i] and
    not tapSafeR2Matches[i] and
    not tapSafeR3Matches[i] and
    i.userTouching = False and i.highUtilityPrediction = True
}

fun evalTapSafeRule[i: TapSafeInput]: one TapSafeRule {
    tapSafeR1Matches[i] => TapSafeR1 else
    tapSafeR2Matches[i] => TapSafeR2 else
    tapSafeR3Matches[i] => TapSafeR3 else
    tapSafeR4Matches[i] => TapSafeR4 else
    TapSafeDefault
}

fun isPinnedTop1[i: TapSafeInput]: one Bool {
    (evalTapSafeRule[i] = TapSafeDefault) => True else False
}

// Assertion 1: Every TapSafeInput resolves to a unique rule (totality)
assert TapSafeTableIsTotal {
    all i: TapSafeInput | one evalTapSafeRule[i]
}

// Assertion 2: Never pins when Top-1 is absent or unchanged
assert NeverPinsWhenTop1UnchangedOrAbsent {
    all i: TapSafeInput |
        (i.hasInitialTop1 = False or i.top1Unchanged = True) implies isPinnedTop1[i] = False
}

// Assertion 3: Never pins within 100ms window unless user is touching the candidate strip
assert NeverPinsWithinWindowUnlessTouching {
    all i: TapSafeInput |
        (i.withinSafeWindow = True and i.userTouching = False) implies isPinnedTop1[i] = False
}

// Assertion 4: Never pins partial prefix when full-span candidate is promoted
assert NeverPinsPartialOverFullSpan {
    all i: TapSafeInput |
        (i.preservedPartial = True and i.newTopFull = True) implies isPinnedTop1[i] = False
}

// Assertion 4b: Never pins when high-utility prediction promotes and user is not touching
assert NeverPinsHighUtilityPredictionWhenNotTouching {
    all i: TapSafeInput |
        (i.userTouching = False and i.highUtilityPrediction = True) implies isPinnedTop1[i] = False
}

// Assertion 4c: Always pins high-utility prediction after window if user IS touching strip (and not partial->full)
assert PinsPredictionWhenUserTouchingAfterWindow {
    all i: TapSafeInput |
        (i.hasInitialTop1 = True and i.top1Unchanged = False and
         i.withinSafeWindow = False and i.userTouching = True and
         not (i.preservedPartial = True and i.newTopFull = True)) implies isPinnedTop1[i] = True
}

// --- Part 2: ClipboardSuggestionEligibility (BC-8) ---

sig ClipboardInput {
    inputViewActive: one Bool,
    voiceIdle: one Bool,
    historyShowing: one Bool,
    keyboardComposing: one Bool,
    streamingComposing: one Bool,
    typingSessionActive: one Bool,
    nonClipboardCandidates: one Bool
}

fun evalClipboardEligible[c: ClipboardInput]: one Bool {
    (c.inputViewActive = True and
     c.voiceIdle = True and
     c.historyShowing = False and
     c.keyboardComposing = False and
     c.streamingComposing = False and
     c.typingSessionActive = False and
     c.nonClipboardCandidates = False) => True else False
}

// Assertion 5: Clipboard suggestion never intrudes during composing, typing, or active voice
assert ClipboardNeverIntrudesDuringActiveInput {
    all c: ClipboardInput |
        (c.keyboardComposing = True or
         c.streamingComposing = True or
         c.typingSessionActive = True or
         c.voiceIdle = False) implies evalClipboardEligible[c] = False
}

// Non-vacuity reachability checks
pred canPinTop1Safely {
    some i: TapSafeInput | isPinnedTop1[i] = True
}

pred canPromoteHighUtilityPrediction {
    some i: TapSafeInput | evalTapSafeRule[i] = TapSafeR4
}

pred canShowClipboardSuggestion {
    some c: ClipboardInput | evalClipboardEligible[c] = True
}

// --- Part 3: ViewportAwareCandidateOrdering (UI-Idea 1 + 2 + 8) ---

abstract sig ViewportRule {}
one sig VpSafeR1, VpSafeR2, VpSafeR3, VpSafeDefault extends ViewportRule {}

abstract sig UiRenderStrategy {}
one sig StrategyDirectSinglePass, StrategyPhase2HeroAndOffscreen, StrategyDeferWhileTouching extends UiRenderStrategy {}

sig ViewportOrderingInput {
    isPhase1AlreadyRendered: one Bool,
    hasReservedHeroSlot: one Bool,
    isUserTouchingStrip: one Bool
}

pred vpSafeR1Matches[i: ViewportOrderingInput] {
    i.isUserTouchingStrip = True and i.isPhase1AlreadyRendered = True
}

pred vpSafeR2Matches[i: ViewportOrderingInput] {
    not vpSafeR1Matches[i] and
    i.isPhase1AlreadyRendered = False
}

pred vpSafeR3Matches[i: ViewportOrderingInput] {
    not vpSafeR1Matches[i] and
    not vpSafeR2Matches[i] and
    i.isPhase1AlreadyRendered = True and i.hasReservedHeroSlot = True
}

fun evalViewportRule[i: ViewportOrderingInput]: one ViewportRule {
    vpSafeR1Matches[i] => VpSafeR1 else
    vpSafeR2Matches[i] => VpSafeR2 else
    vpSafeR3Matches[i] => VpSafeR3 else
    VpSafeDefault
}

fun evalViewportStrategy[i: ViewportOrderingInput]: one UiRenderStrategy {
    vpSafeR1Matches[i] => StrategyDeferWhileTouching else
    vpSafeR2Matches[i] => StrategyDirectSinglePass else
    StrategyPhase2HeroAndOffscreen
}

// Assertion 6: ViewportAwareCandidateOrdering is total
assert ViewportTableIsTotal {
    all i: ViewportOrderingInput | one evalViewportRule[i]
}

// Assertion 7: Completing within 50ms Micro-Wait (Phase 1 not rendered) always uses Single-Pass SLM
assert MicroWaitBypassesPlaceholder {
    all i: ViewportOrderingInput |
        i.isPhase1AlreadyRendered = False implies evalViewportStrategy[i] = StrategyDirectSinglePass
}

// Assertion 8: Active touch after Phase 1 always defers DOM mutation
assert ActiveTouchDefersUpdate {
    all i: ViewportOrderingInput |
        (i.isUserTouchingStrip = True and i.isPhase1AlreadyRendered = True) implies
            evalViewportStrategy[i] = StrategyDeferWhileTouching
}

// Assertion 9: Phase 2 never mutates frozen Zone B and only reranks off-screen Zone C
assert Phase2PreservesViewportWhenNotTouching {
    all i: ViewportOrderingInput |
        (i.isPhase1AlreadyRendered = True and i.isUserTouchingStrip = False) implies
            evalViewportStrategy[i] = StrategyPhase2HeroAndOffscreen
}

pred canApplyPhase2HeroAndOffscreen {
    some i: ViewportOrderingInput | evalViewportRule[i] = VpSafeR3
}

check TapSafeTableIsTotal for 4
check NeverPinsWhenTop1UnchangedOrAbsent for 4
check NeverPinsWithinWindowUnlessTouching for 4
check NeverPinsPartialOverFullSpan for 4
check NeverPinsHighUtilityPredictionWhenNotTouching for 4
check PinsPredictionWhenUserTouchingAfterWindow for 4
check ClipboardNeverIntrudesDuringActiveInput for 4
check ViewportTableIsTotal for 4
check MicroWaitBypassesPlaceholder for 4
check ActiveTouchDefersUpdate for 4
check Phase2PreservesViewportWhenNotTouching for 4
run canPinTop1Safely for 3
run canPromoteHighUtilityPrediction for 3
run canShowClipboardSuggestion for 3
run canApplyPhase2HeroAndOffscreen for 3

