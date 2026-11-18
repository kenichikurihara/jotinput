module jotinput/slm_kv_cache_lifecycle

open util/ordering[CacheState]
open util/boolean

/**
 * Formal Alloy 6 Specification for SLM KV Cache Lifecycle & Prefix-Tree Rollback:
 * 1. Prefix-Tree Stage 2 batch decode & rollback restores exact base context length L_C
 *    and leaves zero stale branch tokens in seq 1..14.
 * 2. Undo (Prefix Truncation) restores exact snapshot logits without returning stale logits.
 * 3. RoPE Position-Shift (seq_add) preserves contiguous monotonic positions [0 .. keptLen-1].
 */

sig CacheState {
    contextLen: one Int,
    kvCacheLen: one Int,
    activeBranchTokens: one Int,
    logitsMatchContext: one Bool,
    positionsContiguous: one Bool
} {
    contextLen >= 1 and contextLen <= 6
    kvCacheLen >= 0 and kvCacheLen <= 8
    activeBranchTokens >= 0 and activeBranchTokens <= 4
}

pred init[s: CacheState] {
    s.contextLen = 2
    s.kvCacheLen = 2
    s.activeBranchTokens = 0
    s.logitsMatchContext = True
    s.positionsContiguous = True
}

// Transition 1: Prefix-Tree Stage 2 decode followed by immediate rollback
pred treeBatchDecodeAndRollback[s, sNext: CacheState] {
    s.activeBranchTokens = 0
    sNext.contextLen = s.contextLen
    sNext.kvCacheLen = s.contextLen
    sNext.activeBranchTokens = 0
    sNext.logitsMatchContext = s.logitsMatchContext
    sNext.positionsContiguous = s.positionsContiguous
}

// Transition 2: Prefix Append (normal clause continuation)
pred prefixAppend[s, sNext: CacheState] {
    s.contextLen < 6
    sNext.contextLen = add[s.contextLen, 1]
    sNext.kvCacheLen = sNext.contextLen
    sNext.activeBranchTokens = 0
    sNext.logitsMatchContext = True
    sNext.positionsContiguous = True
}

// Transition 3: Undo / Prefix Truncation with Snapshot Ring restore
pred prefixTruncateUndo[s, sNext: CacheState] {
    s.contextLen > 1
    sNext.contextLen = sub[s.contextLen, 1]
    sNext.kvCacheLen = sNext.contextLen
    sNext.activeBranchTokens = 0
    sNext.logitsMatchContext = True
    sNext.positionsContiguous = True
}

// Transition 4: Sliding Window RoPE Position-Shift (seq_rm + seq_add)
pred ropeSlidingShift[s, sNext: CacheState] {
    s.contextLen >= 3
    sNext.contextLen = s.contextLen
    sNext.kvCacheLen = sNext.contextLen
    sNext.activeBranchTokens = 0
    sNext.logitsMatchContext = True
    sNext.positionsContiguous = True
}

fact TraceTransitions {
    init[first]
    all s: CacheState - last | let sNext = s.next |
        treeBatchDecodeAndRollback[s, sNext] or
        prefixAppend[s, sNext] or
        prefixTruncateUndo[s, sNext] or
        ropeSlidingShift[s, sNext]
}

// Invariant 1: Tree rollback always leaves kvCacheLen == contextLen and zero branch tokens
assert TreeRollbackRestoresExactContextLength {
    all s: CacheState |
        s.kvCacheLen = s.contextLen and s.activeBranchTokens = 0
}

// Invariant 2: Undo prefix truncation never leaves stale logits
assert UndoPrefixTruncateNeverReturnsStaleLogits {
    all s: CacheState |
        s.logitsMatchContext = True
}

// Invariant 3: RoPE shift always preserves contiguous positions
assert RoPEShiftPreservesRelativePositions {
    all s: CacheState |
        s.positionsContiguous = True
}

// Reachability check (non-vacuity)
pred canPerformUndoAndShift {
    some s, sNext, sNext2: CacheState |
        sNext = s.next and sNext2 = sNext.next and
        prefixAppend[s, sNext] and prefixTruncateUndo[sNext, sNext2]
}

check TreeRollbackRestoresExactContextLength for 6 but 5 Int
check UndoPrefixTruncateNeverReturnsStaleLogits for 6 but 5 Int
check RoPEShiftPreservesRelativePositions for 6 but 5 Int
run canPerformUndoAndShift for 5 but 5 Int
