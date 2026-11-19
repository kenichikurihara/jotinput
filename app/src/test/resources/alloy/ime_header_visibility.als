module jotinput/ime_header_visibility

open util/boolean

/**
 * Formal Alloy 6 Specification for ImeHeaderVisibilityDecisionTable (BC-7: IME Session & UI).
 *
 * Verifies safety and reachability invariants for hiding mic, dictionary, and clipboard
 * header icons during flick input (active key touch) and uncommitted composing text:
 * 1. Totality: Every input combination resolves to exactly one valid DMN rule.
 * 2. Recording/Decoding Safety: Voice toggle (Stop / Progress) is never hidden when
 *    voiceState != VoiceIdle.
 * 3. Candidate Strip Expansion: When voiceState = VoiceIdle and clipboard overlay is closed,
 *    any active flick/key touch or uncommitted composing hides all three utility icons.
 * 4. Idle Restoration: When fully idle, all three utility icons are visible.
 * 5. Clipboard Overlay Accessibility: When clipboard history overlay is open, all three
 *    header controls remain visible.
 */

abstract sig VoiceState {}
one sig VoiceIdle, VoiceRecording, VoiceDecoding extends VoiceState {}

abstract sig HeaderRule {}
one sig HeaderR1, HeaderR2, HeaderR3, HeaderR4, HeaderR5 extends HeaderRule {}

sig HeaderInput {
    voiceState: one VoiceState,
    keyboardComposing: one Bool,
    streamingComposing: one Bool,
    keyTouchActive: one Bool,
    clipboardShowing: one Bool
}

pred inputOrComposingActive[i: HeaderInput] {
    i.keyboardComposing = True or i.streamingComposing = True or i.keyTouchActive = True
}

fun evalHeaderRule[i: HeaderInput]: one HeaderRule {
    (i.clipboardShowing = True) => HeaderR1 else
    (i.voiceState != VoiceIdle and inputOrComposingActive[i]) => HeaderR2 else
    (i.voiceState != VoiceIdle) => HeaderR3 else
    (inputOrComposingActive[i]) => HeaderR4 else
    HeaderR5
}

fun showVoiceToggle[i: HeaderInput]: one Bool {
    (evalHeaderRule[i] = HeaderR4) => False else True
}

fun showDictionary[i: HeaderInput]: one Bool {
    (evalHeaderRule[i] = HeaderR2 or evalHeaderRule[i] = HeaderR4) => False else True
}

fun showClipboard[i: HeaderInput]: one Bool {
    (evalHeaderRule[i] = HeaderR2 or evalHeaderRule[i] = HeaderR4) => False else True
}

assert HeaderTableIsTotal {
    all i: HeaderInput | one evalHeaderRule[i]
}
check HeaderTableIsTotal for 4

assert VoiceToggleAlwaysVisibleWhenRecordingOrDecoding {
    all i: HeaderInput |
        (i.voiceState != VoiceIdle) implies showVoiceToggle[i] = True
}
check VoiceToggleAlwaysVisibleWhenRecordingOrDecoding for 4

assert IconsHiddenDuringComposingOrFlickWhenVoiceIdle {
    all i: HeaderInput |
        (i.voiceState = VoiceIdle and
            i.clipboardShowing = False and
            inputOrComposingActive[i]) implies
            (showVoiceToggle[i] = False and showDictionary[i] = False and showClipboard[i] = False)
}
check IconsHiddenDuringComposingOrFlickWhenVoiceIdle for 4

assert AllIconsVisibleWhenFullyIdle {
    all i: HeaderInput |
        (i.voiceState = VoiceIdle and not inputOrComposingActive[i]) implies
            (showVoiceToggle[i] = True and showDictionary[i] = True and showClipboard[i] = True)
}
check AllIconsVisibleWhenFullyIdle for 4

assert ClipboardOpenKeepsAllIconsVisible {
    all i: HeaderInput |
        (i.clipboardShowing = True) implies
            (showVoiceToggle[i] = True and showDictionary[i] = True and showClipboard[i] = True)
}
check ClipboardOpenKeepsAllIconsVisible for 4

pred canHideAllIconsDuringFlickOrComposing {
    some i: HeaderInput |
        i.voiceState = VoiceIdle and
        i.clipboardShowing = False and
        inputOrComposingActive[i] and
        showVoiceToggle[i] = False and
        showDictionary[i] = False and
        showClipboard[i] = False
}
run canHideAllIconsDuringFlickOrComposing for 4

pred canKeepStopVisibleWhenRecordingWithComposing {
    some i: HeaderInput |
        i.voiceState = VoiceRecording and
        i.clipboardShowing = False and
        i.keyboardComposing = True and
        showVoiceToggle[i] = True and
        showDictionary[i] = False and
        showClipboard[i] = False
}
run canKeepStopVisibleWhenRecordingWithComposing for 4
