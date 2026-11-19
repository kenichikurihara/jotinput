module jotinput/ime_header_visibility

open util/boolean

/**
 * Formal Alloy 6 Specification for ImeHeaderVisibilityDecisionTable (BC-7: IME Session & UI).
 *
 * Verifies safety and reachability invariants for hiding mic, dictionary, and clipboard
 * header icons during flick input (active key touch) and uncommitted composing text:
 * 1. Totality: Every input combination resolves to exactly one valid rule output.
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

sig HeaderInput {
    voiceState: one VoiceState,
    keyboardComposing: one Bool,
    streamingComposing: one Bool,
    keyTouchActive: one Bool,
    clipboardShowing: one Bool
}

sig HeaderOutput {
    showVoiceToggle: one Bool,
    showDictionary: one Bool,
    showClipboard: one Bool
}

pred inputOrComposingActive[i: HeaderInput] {
    i.keyboardComposing = True or i.streamingComposing = True or i.keyTouchActive = True
}

pred evalHeaderVisibility[i: HeaderInput, o: HeaderOutput] {
    // HEADER_R1: Clipboard overlay open
    (i.clipboardShowing = True) implies (
        o.showVoiceToggle = True and o.showDictionary = True and o.showClipboard = True
    ) else (
        // HEADER_R2: Voice active + composing/touch
        (i.voiceState != VoiceIdle and inputOrComposingActive[i]) implies (
            o.showVoiceToggle = True and o.showDictionary = False and o.showClipboard = False
        ) else (
            // HEADER_R3: Voice active without composing/touch
            (i.voiceState != VoiceIdle) implies (
                o.showVoiceToggle = True and o.showDictionary = True and o.showClipboard = True
            ) else (
                // HEADER_R4: Voice idle + flick touch or composing
                (inputOrComposingActive[i]) implies (
                    o.showVoiceToggle = False and o.showDictionary = False and o.showClipboard = False
                ) else (
                    // HEADER_R5: Default idle
                    o.showVoiceToggle = True and o.showDictionary = True and o.showClipboard = True
                )
            )
        )
    )
}

assert HeaderTableIsTotal {
    all i: HeaderInput | some o: HeaderOutput | evalHeaderVisibility[i, o]
}
check HeaderTableIsTotal for 4

assert VoiceToggleAlwaysVisibleWhenRecordingOrDecoding {
    all i: HeaderInput, o: HeaderOutput |
        (evalHeaderVisibility[i, o] and i.voiceState != VoiceIdle) implies
            o.showVoiceToggle = True
}
check VoiceToggleAlwaysVisibleWhenRecordingOrDecoding for 4

assert IconsHiddenDuringComposingOrFlickWhenVoiceIdle {
    all i: HeaderInput, o: HeaderOutput |
        (evalHeaderVisibility[i, o] and
            i.voiceState = VoiceIdle and
            i.clipboardShowing = False and
            inputOrComposingActive[i]) implies
            (o.showVoiceToggle = False and o.showDictionary = False and o.showClipboard = False)
}
check IconsHiddenDuringComposingOrFlickWhenVoiceIdle for 4

assert AllIconsVisibleWhenFullyIdle {
    all i: HeaderInput, o: HeaderOutput |
        (evalHeaderVisibility[i, o] and
            i.voiceState = VoiceIdle and
            not inputOrComposingActive[i]) implies
            (o.showVoiceToggle = True and o.showDictionary = True and o.showClipboard = True)
}
check AllIconsVisibleWhenFullyIdle for 4

assert ClipboardOpenKeepsAllIconsVisible {
    all i: HeaderInput, o: HeaderOutput |
        (evalHeaderVisibility[i, o] and i.clipboardShowing = True) implies
            (o.showVoiceToggle = True and o.showDictionary = True and o.showClipboard = True)
}
check ClipboardOpenKeepsAllIconsVisible for 4

pred canHideAllIconsDuringFlickOrComposing {
    some i: HeaderInput, o: HeaderOutput |
        evalHeaderVisibility[i, o] and
        i.voiceState = VoiceIdle and
        i.clipboardShowing = False and
        inputOrComposingActive[i] and
        o.showVoiceToggle = False and
        o.showDictionary = False and
        o.showClipboard = False
}
run canHideAllIconsDuringFlickOrComposing for 4

pred canKeepStopVisibleWhenRecordingWithComposing {
    some i: HeaderInput, o: HeaderOutput |
        evalHeaderVisibility[i, o] and
        i.voiceState = VoiceRecording and
        i.clipboardShowing = False and
        i.keyboardComposing = True and
        o.showVoiceToggle = True and
        o.showDictionary = False and
        o.showClipboard = False
}
run canKeepStopVisibleWhenRecordingWithComposing for 4
