module jotinput/swipe_extended_lexicon_and_cache

open util/boolean
open util/ordering[State]

/**
 * Formal Alloy 6 Specification for JotInput BC-4 Extended Swipe Vocabulary:
 *   1. SwipeExtendedPass2TriggerPolicy (Idea A Dual-Trie Confidence Gate)
 *   2. SwipeAdaptiveCacheAdmissionPolicy & Lifecycle Invariants (Idea C LRU Cache)
 */

// ---------------------------------------------------------------------------
// Part 1: SwipeExtendedPass2TriggerPolicy (DMN FIRST Hit Policy)
// ---------------------------------------------------------------------------

abstract sig Pass2Rule {}
one sig SwExtR1, SwExtR2, SwExtDefault extends Pass2Rule {}

abstract sig Pass2Action {}
one sig ActSkipNoLexicon, ActSkipBaseConfident, ActRunExtendedPass2 extends Pass2Action {}

sig Pass2Query {
    hasExtendedLexicon: one Bool,
    baseConfident: one Bool
}

pred swExtR1Matches[q: Pass2Query] {
    q.hasExtendedLexicon = False
}

pred swExtR2Matches[q: Pass2Query] {
    not swExtR1Matches[q] and q.baseConfident = True
}

fun evalPass2Rule[q: Pass2Query]: one Pass2Rule {
    swExtR1Matches[q] => SwExtR1 else
    swExtR2Matches[q] => SwExtR2 else
    SwExtDefault
}

fun evalPass2Action[q: Pass2Query]: one Pass2Action {
    let r = evalPass2Rule[q] |
        (r = SwExtR1) => ActSkipNoLexicon else
        (r = SwExtR2) => ActSkipBaseConfident else
        ActRunExtendedPass2
}

// ---------------------------------------------------------------------------
// Part 2: SwipeAdaptiveCacheAdmissionPolicy & State Machine
// ---------------------------------------------------------------------------

abstract sig CacheRule {}
one sig SwCacheR1, SwCacheR2, SwCacheR3, SwCacheR4, SwCacheR5, SwCacheDefault extends CacheRule {}

abstract sig CacheAction {}
one sig
    ActRejectSensitive,
    ActRejectInvalid,
    ActSkipUserDict,
    ActSkipBase32k,
    ActDeferTypoGuard,
    ActAdmitCache
extends CacheAction {}

abstract sig LearnSource {}
one sig SrcVerifiedOrKnownCommit, SrcRawSpaceTap, SrcSensitiveBypass extends LearnSource {}

abstract sig TokenKind {}
one sig TokBase32k, TokUserDict, TokExtended102k, TokCustomNew, TokPasswordOnly, TokInvalid extends TokenKind {}

sig CommitEvent {
    token: one TokenKind,
    sensitiveField: one Bool,
    validFormat: one Bool,
    inUserDict: one Bool,
    inBase32k: one Bool,
    inExtended102k: one Bool,
    source: one LearnSource,
    observedTwice: one Bool
} {
    (token = TokBase32k) implies (validFormat = True and inUserDict = False and inBase32k = True and inExtended102k = False)
    (token = TokUserDict) implies (validFormat = True and inUserDict = True and inBase32k = False and inExtended102k = False)
    (token = TokExtended102k) implies (validFormat = True and inUserDict = False and inBase32k = False and inExtended102k = True)
    (token = TokCustomNew) implies (validFormat = True and inUserDict = False and inBase32k = False and inExtended102k = False)
    (token = TokPasswordOnly) implies (sensitiveField = True and validFormat = True and inUserDict = False and inBase32k = False and inExtended102k = False)
    (token = TokInvalid) implies (validFormat = False and inUserDict = False and inBase32k = False and inExtended102k = False)
}

pred swCacheR1Matches[e: CommitEvent] {
    e.sensitiveField = True or e.source = SrcSensitiveBypass
}

pred swCacheR2Matches[e: CommitEvent] {
    not swCacheR1Matches[e] and e.validFormat = False
}

pred swCacheR3Matches[e: CommitEvent] {
    not swCacheR1Matches[e] and not swCacheR2Matches[e] and e.inUserDict = True
}

pred swCacheR4Matches[e: CommitEvent] {
    not swCacheR1Matches[e] and not swCacheR2Matches[e] and not swCacheR3Matches[e] and e.inBase32k = True
}

pred swCacheR5Matches[e: CommitEvent] {
    not swCacheR1Matches[e] and not swCacheR2Matches[e] and not swCacheR3Matches[e] and not swCacheR4Matches[e] and
    e.inExtended102k = False and e.source = SrcRawSpaceTap and e.observedTwice = False
}

fun evalCacheRule[e: CommitEvent]: one CacheRule {
    swCacheR1Matches[e] => SwCacheR1 else
    swCacheR2Matches[e] => SwCacheR2 else
    swCacheR3Matches[e] => SwCacheR3 else
    swCacheR4Matches[e] => SwCacheR4 else
    swCacheR5Matches[e] => SwCacheR5 else
    SwCacheDefault
}

fun evalCacheAction[e: CommitEvent]: one CacheAction {
    let r = evalCacheRule[e] |
        (r = SwCacheR1) => ActRejectSensitive else
        (r = SwCacheR2) => ActRejectInvalid else
        (r = SwCacheR3) => ActSkipUserDict else
        (r = SwCacheR4) => ActSkipBase32k else
        (r = SwCacheR5) => ActDeferTypoGuard else
        ActAdmitCache
}

sig State {
    activeCache: set TokenKind,
    pendingRaw: set TokenKind,
    customDict: set TokenKind,
    baseVocab: set TokenKind
}

pred init[s: State] {
    no s.activeCache
    no s.pendingRaw
    s.customDict = TokUserDict
    s.baseVocab = TokBase32k
}

pred stepCommit[s, sNext: State, e: CommitEvent] {
    sNext.customDict = s.customDict
    sNext.baseVocab = s.baseVocab
    // Model observedTwice faithfully: if token already in pendingRaw or activeCache, observedTwice = True; else False
    (e.token in (s.pendingRaw + s.activeCache)) iff (e.observedTwice = True)

    let act = evalCacheAction[e] | {
        (act = ActAdmitCache) implies (
            sNext.activeCache = s.activeCache + e.token and
            sNext.pendingRaw = s.pendingRaw - e.token
        ) else (act = ActDeferTypoGuard) implies (
            sNext.activeCache = s.activeCache and
            sNext.pendingRaw = s.pendingRaw + e.token
        ) else (
            sNext.activeCache = s.activeCache and
            sNext.pendingRaw = s.pendingRaw
        )
    }
}

fact Trace {
    init[first]
    all s: State - last | let sNext = s.next |
        some e: CommitEvent | stepCommit[s, sNext, e]
}

// ---------------------------------------------------------------------------
// Safety Assertions
// ---------------------------------------------------------------------------

assert Pass2TriggerTableIsTotal {
    all q: Pass2Query | one evalPass2Rule[q] and one evalPass2Action[q]
}

assert ConfidentBaseNeverTriggersPass2 {
    all q: Pass2Query |
        (q.hasExtendedLexicon = True and q.baseConfident = True) implies
            (evalPass2Action[q] = ActSkipBaseConfident)
}

assert MissingLexiconNeverTriggersPass2 {
    all q: Pass2Query |
        (q.hasExtendedLexicon = False) implies
            (evalPass2Action[q] = ActSkipNoLexicon)
}

assert AdaptiveAdmissionTableIsTotal {
    all e: CommitEvent | one evalCacheRule[e] and one evalCacheAction[e]
}

assert SensitiveOrPasswordNeverCached {
    all s: State | TokPasswordOnly not in (s.activeCache + s.pendingRaw)
    all e: CommitEvent |
        (e.sensitiveField = True or e.source = SrcSensitiveBypass) implies
            (evalCacheAction[e] = ActRejectSensitive)
    all s: State - last, e: CommitEvent |
        (stepCommit[s, s.next, e] and (e.sensitiveField = True or e.source = SrcSensitiveBypass)) implies
            (s.next.activeCache = s.activeCache and s.next.pendingRaw = s.pendingRaw)
}

assert SingleUnverifiedRawTypoNeverEntersActiveCache {
    all s: State - last | let sNext = s.next |
        all e: CommitEvent |
            (stepCommit[s, sNext, e] and e.token = TokCustomNew and e.source = SrcRawSpaceTap and
             e.sensitiveField = False and e.token not in (s.pendingRaw + s.activeCache)) implies
                (e.token not in sNext.activeCache and e.token in sNext.pendingRaw)
}

assert CustomDictAndBaseVocabIsolatedFromAdaptiveCache {
    all s: State | {
        no (s.activeCache & (s.customDict + s.baseVocab + TokInvalid))
        s.customDict = TokUserDict
        s.baseVocab = TokBase32k
    }
}

// Non-vacuity reachability predicates
pred canTriggerPass2OnOov {
    some q: Pass2Query |
        q.hasExtendedLexicon = True and q.baseConfident = False and evalPass2Action[q] = ActRunExtendedPass2
}

pred canAdmitKnownExtendedWordImmediately {
    some s: State | TokExtended102k in s.activeCache
}

pred canPromoteCustomWordOnSecondTap {
    some s0, s1, s2: State |
        s1 = s0.next and s2 = s1.next and
        TokCustomNew not in s0.activeCache and
        TokCustomNew in s1.pendingRaw and TokCustomNew not in s1.activeCache and
        TokCustomNew in s2.activeCache
}

check Pass2TriggerTableIsTotal for 4
check ConfidentBaseNeverTriggersPass2 for 4
check MissingLexiconNeverTriggersPass2 for 4
check AdaptiveAdmissionTableIsTotal for 6
check SensitiveOrPasswordNeverCached for 5
check SingleUnverifiedRawTypoNeverEntersActiveCache for 5
check CustomDictAndBaseVocabIsolatedFromAdaptiveCache for 5

run canTriggerPass2OnOov for 3
run canAdmitKnownExtendedWordImmediately for 4
run canPromoteCustomWordOnSecondTap for 4
