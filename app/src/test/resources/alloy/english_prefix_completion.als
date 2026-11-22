module jotinput/english_prefix_completion

open util/boolean

/**
 * Formal Alloy 6 Specification for JotInput BC-4 English Prefix Completion
 * DMN Policy (EnglishPrefixCompletionPolicy) and Rank-1 Safety Invariants.
 *
 * Models:
 *  - DMN FIRST-hit resolution across EN_PREFIX_R1, EN_PREFIX_R2, EN_PREFIX_R3, EN_PREFIX_DEFAULT
 *  - Known exact short-word protection (e.g., "an" over "and", "tail" over "Tailscale")
 *  - Dead-end OOV prefix completion promotion (e.g., "tailsc" -> "Tailscale" ahead of "tailsc" / "tails")
 *  - Preservation of raw typed string in the candidate pool as an escape hatch
 */

abstract sig PrefixRule {}
one sig EnPrefixR1, EnPrefixR2, EnPrefixR3, EnPrefixDefault extends PrefixRule {}

abstract sig PrefixStrategy {}
one sig
    StrategyNone,
    StrategyProtectExactWord,
    StrategyPromotePrefixCompletion,
    StrategyRawAndTypoOnly
extends PrefixStrategy {}

abstract sig CandidateKind {}
one sig CandUserOverride, CandExactKnown, CandPrefixCompletion, CandShorterTypo, CandRawOov extends CandidateKind {}

sig Query {
    rawLenGe2: one Bool,
    isKnownExactWord: one Bool,
    isOovAcronym: one Bool,
    hasPrefixCompletion: one Bool,
    hasUserOverride: one Bool,
    hasShorterTypo: one Bool,
    pool: some CandidateKind,
    rank1: one CandidateKind
}

pred enPrefixR1Matches[q: Query] {
    q.rawLenGe2 = False
}

pred enPrefixR2Matches[q: Query] {
    not enPrefixR1Matches[q] and
    (q.isKnownExactWord = True or q.isOovAcronym = True)
}

pred enPrefixR3Matches[q: Query] {
    not enPrefixR1Matches[q] and
    not enPrefixR2Matches[q] and
    q.hasPrefixCompletion = True
}

fun evalPrefixRule[q: Query]: one PrefixRule {
    enPrefixR1Matches[q] => EnPrefixR1 else
    enPrefixR2Matches[q] => EnPrefixR2 else
    enPrefixR3Matches[q] => EnPrefixR3 else
    EnPrefixDefault
}

fun evalPrefixStrategy[q: Query]: one PrefixStrategy {
    let r = evalPrefixRule[q] |
        (r = EnPrefixR1) => StrategyNone else
        (r = EnPrefixR2) => StrategyProtectExactWord else
        (r = EnPrefixR3) => StrategyPromotePrefixCompletion else
        StrategyRawAndTypoOnly
}

fact CandidatePoolAndRankingSemantics {
    all q: Query | {
        // Raw escape hatch is always present in the candidate pool for eligible queries
        q.rawLenGe2 = True implies (
            ((q.isKnownExactWord = True or q.isOovAcronym = True) implies CandExactKnown in q.pool else CandRawOov in q.pool)
        )
        (q.rawLenGe2 = False) implies (CandExactKnown in q.pool or CandRawOov in q.pool)
        (q.hasUserOverride = True) iff (CandUserOverride in q.pool)
        (q.rawLenGe2 = True and q.hasPrefixCompletion = True) iff (CandPrefixCompletion in q.pool)
        (q.rawLenGe2 = True and q.hasShorterTypo = True) iff (CandShorterTypo in q.pool)

        // Rank 1 must belong to the candidate pool
        q.rank1 in q.pool

        // Ranking arbitration governed by DMN PrefixCompletionStrategy
        let strat = evalPrefixStrategy[q] | {
            (q.hasUserOverride = True) implies (q.rank1 = CandUserOverride)
            (q.hasUserOverride = False and strat = StrategyProtectExactWord) implies (q.rank1 = CandExactKnown)
            (q.hasUserOverride = False and strat = StrategyPromotePrefixCompletion) implies (q.rank1 = CandPrefixCompletion)
            (q.hasUserOverride = False and strat = StrategyRawAndTypoOnly) implies (q.rank1 in (CandRawOov + CandShorterTypo))
            (q.hasUserOverride = False and strat = StrategyNone) implies (q.rank1 in (CandExactKnown + CandRawOov))
        }
    }
}

// Assertion 1: DMN DecisionTable resolves every query to a unique rule & strategy
assert PrefixPolicyTableIsTotal {
    all q: Query | one evalPrefixRule[q] and one evalPrefixStrategy[q]
}

// Assertion 2: Known exact short words (e.g., "an", "tail") are protected at Rank 1 over extensions
assert KnownExactShortWordProtectedAtRank1 {
    all q: Query |
        (q.rawLenGe2 = True and q.isKnownExactWord = True and q.hasUserOverride = False) implies
            (evalPrefixStrategy[q] = StrategyProtectExactWord and q.rank1 = CandExactKnown and q.rank1 != CandPrefixCompletion)
}

// Assertion 3: Dead-end OOV prefix (e.g., "tailsc") promotes completion ("Tailscale") to Rank 1 ahead of OOV raw & shorter typos
assert DeadEndOovPrefixPromotesCompletionToRank1 {
    all q: Query |
        (q.rawLenGe2 = True and q.isKnownExactWord = False and q.isOovAcronym = False and
         q.hasPrefixCompletion = True and q.hasUserOverride = False) implies
            (evalPrefixStrategy[q] = StrategyPromotePrefixCompletion and q.rank1 = CandPrefixCompletion)
}

// Assertion 4: Raw escape hatch (exact known or raw OOV) always remains available in the pool
assert RawEscapeHatchAlwaysPresent {
    all q: Query | (CandExactKnown in q.pool or CandRawOov in q.pool)
}

// Non-vacuity reachability predicates
pred canPromoteDeadEndPrefix[q: Query] {
    q.rawLenGe2 = True and
    q.isKnownExactWord = False and
    q.isOovAcronym = False and
    q.hasPrefixCompletion = True and
    q.hasShorterTypo = True and
    q.hasUserOverride = False and
    q.rank1 = CandPrefixCompletion
}

pred canProtectKnownWord[q: Query] {
    q.rawLenGe2 = True and
    q.isKnownExactWord = True and
    q.hasPrefixCompletion = True and
    q.hasUserOverride = False and
    q.rank1 = CandExactKnown
}

check PrefixPolicyTableIsTotal for 6
check KnownExactShortWordProtectedAtRank1 for 6
check DeadEndOovPrefixPromotesCompletionToRank1 for 6
check RawEscapeHatchAlwaysPresent for 6
run canPromoteDeadEndPrefix for 4
run canProtectKnownWord for 4
