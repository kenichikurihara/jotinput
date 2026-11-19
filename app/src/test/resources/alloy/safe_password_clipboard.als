module jotinput/safe_password_clipboard

open util/boolean

/**
 * Formal Alloy 6 Specification for BC-8 SafeClipboardHandling DMN DecisionTable:
 * Ensures zero disk persistence, zero plaintext UI exposure, and zero learning/telemetry
 * contamination for sensitive clips (EXTRA_IS_SENSITIVE) and password input fields.
 */

abstract sig SafeClipRule {}
one sig SafeClipR1, SafeClipR2, SafeClipR3, SafeClipR4, SafeClipR5, SafeClipDefault extends SafeClipRule {}

abstract sig ClipDisplayMode {}
one sig ModePlainText, ModeMaskedSensitive, ModeHidden extends ClipDisplayMode {}

sig SafeClipInput {
    isSensitiveClip: one Bool,
    isPasswordInputField: one Bool,
    isVolatileExpired: one Bool,
    isDismissedFromStrip: one Bool,
    isDismissedFromSheet: one Bool
}

pred safeClipR1Matches[i: SafeClipInput] {
    i.isSensitiveClip = True and i.isVolatileExpired = True
}

pred safeClipR2Matches[i: SafeClipInput] {
    not safeClipR1Matches[i] and
    i.isSensitiveClip = True and i.isDismissedFromSheet = True
}

pred safeClipR3Matches[i: SafeClipInput] {
    not safeClipR1Matches[i] and
    not safeClipR2Matches[i] and
    i.isSensitiveClip = True and i.isDismissedFromStrip = True
}

pred safeClipR4Matches[i: SafeClipInput] {
    not safeClipR1Matches[i] and
    not safeClipR2Matches[i] and
    not safeClipR3Matches[i] and
    i.isSensitiveClip = True
}

pred safeClipR5Matches[i: SafeClipInput] {
    not safeClipR1Matches[i] and
    not safeClipR2Matches[i] and
    not safeClipR3Matches[i] and
    not safeClipR4Matches[i] and
    i.isSensitiveClip = False and i.isPasswordInputField = True
}

fun evalSafeClipRule[i: SafeClipInput]: one SafeClipRule {
    safeClipR1Matches[i] => SafeClipR1 else
    safeClipR2Matches[i] => SafeClipR2 else
    safeClipR3Matches[i] => SafeClipR3 else
    safeClipR4Matches[i] => SafeClipR4 else
    safeClipR5Matches[i] => SafeClipR5 else
    SafeClipDefault
}

fun allowDiskPersistence[i: SafeClipInput]: one Bool {
    (i.isSensitiveClip = True) => False else True
}

fun allowLearning[i: SafeClipInput]: one Bool {
    (i.isSensitiveClip = True or i.isPasswordInputField = True) => False else True
}

fun stripDisplayMode[i: SafeClipInput]: one ClipDisplayMode {
    safeClipR1Matches[i] => ModeHidden else
    safeClipR2Matches[i] => ModeHidden else
    safeClipR3Matches[i] => ModeHidden else
    safeClipR4Matches[i] => ModeMaskedSensitive else
    (i.isDismissedFromStrip = True => ModeHidden else ModePlainText)
}

fun sheetDisplayMode[i: SafeClipInput]: one ClipDisplayMode {
    safeClipR1Matches[i] => ModeHidden else
    safeClipR2Matches[i] => ModeHidden else
    safeClipR3Matches[i] => ModeMaskedSensitive else
    safeClipR4Matches[i] => ModeMaskedSensitive else
    (i.isDismissedFromSheet = True => ModeHidden else ModePlainText)
}

// Assertion 1: Every SafeClipInput resolves to a unique rule (totality)
assert SafeClipboardTableIsTotal {
    all i: SafeClipInput | one evalSafeClipRule[i]
}

// Assertion 2: Sensitive clips are NEVER persisted to disk
assert SensitiveClipNeverPersistedToDisk {
    all i: SafeClipInput |
        i.isSensitiveClip = True implies allowDiskPersistence[i] = False
}

// Assertion 3: Sensitive clips are NEVER displayed in plaintext on strip or sheet
assert SensitiveClipNeverDisplayedPlaintext {
    all i: SafeClipInput |
        i.isSensitiveClip = True implies (
            stripDisplayMode[i] != ModePlainText and
            sheetDisplayMode[i] != ModePlainText
        )
}

// Assertion 4: Sensitive clips and password fields NEVER allow learning or prediction
assert SensitiveOrPasswordFieldNeverLearns {
    all i: SafeClipInput |
        (i.isSensitiveClip = True or i.isPasswordInputField = True) implies allowLearning[i] = False
}

// Assertion 5: Fresh non-dismissed sensitive clip is masked on both strip and sheet
assert FreshSensitiveClipIsMaskedAndAccessible {
    all i: SafeClipInput |
        (i.isSensitiveClip = True and i.isVolatileExpired = False and
         i.isDismissedFromStrip = False and i.isDismissedFromSheet = False) implies (
            stripDisplayMode[i] = ModeMaskedSensitive and
            sheetDisplayMode[i] = ModeMaskedSensitive
        )
}

// Non-vacuity reachability predicates
pred canShowMaskedSensitiveClip {
    some i: SafeClipInput |
        stripDisplayMode[i] = ModeMaskedSensitive and
        sheetDisplayMode[i] = ModeMaskedSensitive and
        allowDiskPersistence[i] = False and
        allowLearning[i] = False
}

pred canShowStandardClip {
    some i: SafeClipInput |
        stripDisplayMode[i] = ModePlainText and
        allowDiskPersistence[i] = True and
        allowLearning[i] = True
}

check SafeClipboardTableIsTotal for 4
check SensitiveClipNeverPersistedToDisk for 4
check SensitiveClipNeverDisplayedPlaintext for 4
check SensitiveOrPasswordFieldNeverLearns for 4
check FreshSensitiveClipIsMaskedAndAccessible for 4
run canShowMaskedSensitiveClip for 3
run canShowStandardClip for 3
