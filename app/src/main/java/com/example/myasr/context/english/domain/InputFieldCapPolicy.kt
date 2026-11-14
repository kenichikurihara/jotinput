package com.example.myasr.context.english.domain

/**
 * Pure domain representation of the target input field's capitalization policy,
 * decoupled from Android's EditorInfo flags.
 */
enum class InputFieldCapPolicy {
    /** Passwords, URIs, emails, NO_SUGGESTIONS, or code/handle prefix contexts. */
    BYPASS,

    /** Standard prose input (default sentence-start + lexical truecasing). */
    STANDARD,

    /** Explicit sentence capitalization requested by host field. */
    CAP_SENTENCES,

    /** Title/name field: every word start is strongly biased toward TitleCase. */
    CAP_WORDS,

    /** All-caps field (e.g., codes/plates): strongly biased toward UPPERCASE. */
    CAP_CHARACTERS
}
