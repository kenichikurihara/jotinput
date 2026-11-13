module jotinput/voice_touch_arbitration

open util/ordering[State]
open util/boolean

/**
 * Formal Alloy 6 Specification for VoiceTouchInputArbitrator (BC-7: IME Session & UI).
 *
 * Verifies the Fundamental Multimodal Invariants:
 * 1. Single-Composing Ownership: Keyboard composing and Streaming Voice composing
 *    never coexist on the single Android InputConnection composing span.
 * 2. Undo Safety: Any voice commitment or active streaming voice immediately
 *    invalidates stale keyboard Undo state so Undo never corrupts spoken text.
 * 3. Pending Voice Flush: Queued post-typing voice chunks are never stranded once
 *    the keyboard composing session closes (owner returns to OwnerNone).
 */

abstract sig ComposingOwner {}
one sig OwnerNone, OwnerKeyboard, OwnerStreamingVoice extends ComposingOwner {}

sig Session {}
sig VoiceChunk {}

sig State {
    owner: one ComposingOwner,
    activeSession: lone Session,
    undoValid: one Bool,
    pendingVoice: set VoiceChunk,
    voiceJustCommitted: one Bool
}

pred init[s: State] {
    s.owner = OwnerNone
    no s.activeSession
    s.undoValid = False
    no s.pendingVoice
    s.voiceJustCommitted = False
}

// Type Kana key in 12-key Flick mode:
// Auto-commits any active streaming voice first, opens/continues a keyboard session,
// and invalidates stale Undo.
pred typeKana[s, sNext: State, sess: Session] {
    sNext.owner = OwnerKeyboard
    (s.owner = OwnerKeyboard) implies (sNext.activeSession = s.activeSession) else (sNext.activeSession = sess)
    sNext.undoValid = False
    sNext.pendingVoice = s.pendingVoice
    sNext.voiceJustCommitted = (s.owner = OwnerStreamingVoice implies True else False)
}

// Commit active keyboard composing session (candidate selection or Enter):
// Enables Undo for the committed keyboard surface and flushes all pendingVoice chunks.
pred commitKeyboard[s, sNext: State] {
    s.owner = OwnerKeyboard
    sNext.owner = OwnerNone
    no sNext.activeSession
    sNext.undoValid = True
    no sNext.pendingVoice
    sNext.voiceJustCommitted = False
}

// Streaming voice ASR tick when keyboard composing is NOT active.
pred streamVoiceUpdate[s, sNext: State] {
    s.owner != OwnerKeyboard
    sNext.owner = OwnerStreamingVoice
    no sNext.activeSession
    sNext.undoValid = False
    sNext.pendingVoice = s.pendingVoice
    sNext.voiceJustCommitted = False
}

// Post-typing voice chunk arrives while keyboard composing session is still open:
// Must be queued in pendingVoice without overwriting keyboard composing.
pred queueVoiceDuringKeyboard[s, sNext: State, vc: VoiceChunk] {
    s.owner = OwnerKeyboard
    sNext.owner = OwnerKeyboard
    sNext.activeSession = s.activeSession
    sNext.undoValid = s.undoValid
    sNext.pendingVoice = s.pendingVoice + vc
    sNext.voiceJustCommitted = False
}

// Confirm / Enter key while streaming voice is active:
// Immediately commits streaming voice and clears Undo.
pred confirmStreamingVoice[s, sNext: State] {
    s.owner = OwnerStreamingVoice
    sNext.owner = OwnerNone
    no sNext.activeSession
    sNext.undoValid = False
    no sNext.pendingVoice
    sNext.voiceJustCommitted = True
}

// Undo button pressed in 12-key Flick mode when idle and undoValid = True.
pred pressUndo[s, sNext: State, sess: Session] {
    s.owner = OwnerNone
    s.undoValid = True
    sNext.owner = OwnerKeyboard
    sNext.activeSession = sess
    sNext.undoValid = False
    no sNext.pendingVoice
    sNext.voiceJustCommitted = False
}

pred step[s, sNext: State] {
    (some sess: Session | typeKana[s, sNext, sess]) or
    commitKeyboard[s, sNext] or
    streamVoiceUpdate[s, sNext] or
    (some vc: VoiceChunk | queueVoiceDuringKeyboard[s, sNext, vc]) or
    confirmStreamingVoice[s, sNext] or
    (some sess: Session | pressUndo[s, sNext, sess])
}

fact Trace {
    init[first]
    all s: State - last | step[s, s.next]
}

// Invariant 1: Single-Composing Ownership
assert SingleComposingOwnership {
    all s: State |
        (s.owner = OwnerKeyboard iff some s.activeSession) and
        not (s.owner = OwnerStreamingVoice and some s.activeSession)
}

// Invariant 2: Undo Safety after any voice activity
assert UndoSafetyAfterVoice {
    all s: State |
        (s.owner = OwnerStreamingVoice or s.voiceJustCommitted = True) implies s.undoValid = False
}

// Invariant 3: Pending voice chunks are always flushed when idle (OwnerNone)
assert PendingVoiceFlushedWhenIdle {
    all s: State |
        s.owner = OwnerNone implies no s.pendingVoice
}

// Reachability check (non-vacuity): voice queued during keyboard session and then flushed
pred canQueueVoiceAndFlush {
    some s1, s2: State |
        lt[s1, s2] and
        s1.owner = OwnerKeyboard and some s1.pendingVoice and
        s2.owner = OwnerNone and no s2.pendingVoice
}

// Reachability check (non-vacuity): safe Undo after keyboard commit
pred canUndoSafelyAfterKeyboardCommit {
    some s1, s2: State |
        lt[s1, s2] and
        s1.owner = OwnerNone and s1.undoValid = True and
        s2.owner = OwnerKeyboard
}

check SingleComposingOwnership for 6
check UndoSafetyAfterVoice for 6
check PendingVoiceFlushedWhenIdle for 6
run canQueueVoiceAndFlush for 5
run canUndoSafelyAfterKeyboardCommit for 5
