package com.example.myasr

import android.view.inputmethod.EditorInfo

/**
 * Intelligent analyzer for English QWERTY input to determine appropriate
 * capitalization (Sentence Start, Pronoun 'I' and its contractions, Acronyms, Title Case)
 * without disruptive auto-replace, providing candidates directly to CandidateStripView.
 */
object EnglishCapitalizationAnalyzer {

    enum class CapitalizationType {
        NONE,
        SENTENCE_START,
        PRONOUN_I,
        ALL_CAPS,
        PROPER_NOUN
    }

    data class CapitalizationResult(
        val type: CapitalizationType,
        val primaryCandidate: String?,
        val additionalCandidates: List<String> = emptyList()
    )

    // Common acronyms where all-caps is the standard convention
    private val ACRONYMS = hashSetOf(
        "ai", "api", "app", "asap", "atm", "aws", "ceo", "cfo", "cio", "coo", "cpu", "cto",
        "diy", "dna", "eta", "faq", "fyi", "fyi", "gdp", "gpu", "html", "http", "https",
        "id", "ime", "io", "iot", "ip", "iso", "json", "led", "llm", "nasa", "nba", "nfl",
        "npc", "ocr", "ok", "os", "pdf", "pin", "qa", "ram", "rgb", "sdk", "sim", "sms",
        "sql", "ssl", "tcp", "tls", "tv", "ui", "uk", "uri", "url", "usa", "usb", "utc",
        "uuid", "ux", "vip", "vpn", "wifi", "xml"
    )

    // Pronoun 'I' and common contractions without apostrophes
    private val PRONOUN_CONTRACTIONS = mapOf(
        "i" to "I",
        "im" to "I'm",
        "i'm" to "I'm",
        "ive" to "I've",
        "i've" to "I've",
        "ill" to "I'll",
        "i'll" to "I'll",
        "id" to "I'd",
        "i'd" to "I'd"
    )

    // Common proper nouns (days of the week, months, common countries/tech)
    private val PROPER_NOUNS = mapOf(
        "monday" to "Monday", "tuesday" to "Tuesday", "wednesday" to "Wednesday",
        "thursday" to "Thursday", "friday" to "Friday", "saturday" to "Saturday", "sunday" to "Sunday",
        "january" to "January", "february" to "February", "march" to "March", "april" to "April",
        "may" to "May", "june" to "June", "july" to "July", "august" to "August",
        "september" to "September", "october" to "October", "november" to "November", "december" to "December",
        "japan" to "Japan", "tokyo" to "Tokyo", "america" to "America", "english" to "English",
        "japanese" to "Japanese", "google" to "Google", "android" to "Android"
    )

    /**
     * Determines whether capitalization suggestions should be completely bypassed
     * based on field characteristics (passwords, emails, URIs, code symbols).
     */
    fun shouldBypassCapitalization(editorInfo: EditorInfo?, textBeforeCursor: String): Boolean {
        if (editorInfo != null) {
            val variation = editorInfo.inputType and EditorInfo.TYPE_MASK_VARIATION
            if (variation == EditorInfo.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == EditorInfo.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                variation == EditorInfo.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                variation == EditorInfo.TYPE_TEXT_VARIATION_URI
            ) {
                return true
            }
            if ((editorInfo.inputType and EditorInfo.TYPE_CLASS_NUMBER) != 0 &&
                variation == EditorInfo.TYPE_NUMBER_VARIATION_PASSWORD
            ) {
                return true
            }
            if ((editorInfo.inputType and EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0) {
                return true
            }
        }

        // Check if preceding character is special programming or web identifier symbol
        val rawPrefix = textBeforeCursor.dropLastWhile { it.isLetter() || it == '\'' }
        if (rawPrefix.isNotEmpty()) {
            val lastChar = rawPrefix.last()
            if (lastChar == '@' || lastChar == '/' || lastChar == '#' || lastChar == '$' || lastChar == '_') {
                return true
            }
        }

        return false
    }

    /**
     * Analyzes context and current word to produce capitalization candidates.
     *
     * @param rawWord Currently typed letters (e.g. "hello", "i", "im", "usa")
     * @param textBeforeCursor Recent text before cursor (up to 64 chars)
     * @param isShiftActive True if shift / caps lock is currently turned on
     */
    fun analyze(
        rawWord: String,
        textBeforeCursor: String,
        isShiftActive: Boolean
    ): CapitalizationResult {
        if (rawWord.isEmpty()) {
            return CapitalizationResult(CapitalizationType.NONE, null)
        }

        val lowerWord = rawWord.lowercase()

        // 1. If user already explicitly entered first character as uppercase or shift is active,
        // we preserve user's explicit intent.
        if (isShiftActive || rawWord.first().isUpperCase()) {
            return CapitalizationResult(CapitalizationType.NONE, null)
        }

        // 2. Pronoun 'I' and contractions (e.g. i -> I, im -> I'm, ive -> I've)
        PRONOUN_CONTRACTIONS[lowerWord]?.let { pronounUpper ->
            val extras = mutableListOf<String>()
            if (lowerWord == "id") {
                // "id" can mean either "ID" or "I'd"
                extras.add("ID")
            }
            return CapitalizationResult(CapitalizationType.PRONOUN_I, pronounUpper, extras)
        }

        // 3. Known common proper nouns (e.g. monday -> Monday, tokyo -> Tokyo)
        PROPER_NOUNS[lowerWord]?.let { properUpper ->
            return CapitalizationResult(CapitalizationType.PROPER_NOUN, properUpper)
        }

        // 4. Acronyms (e.g. usa -> USA, ai -> AI, asap -> ASAP)
        if (ACRONYMS.contains(lowerWord)) {
            return CapitalizationResult(CapitalizationType.ALL_CAPS, lowerWord.uppercase())
        }

        // 5. Sentence start check
        if (isSentenceStart(rawWord, textBeforeCursor)) {
            val capitalized = lowerWord.replaceFirstChar { it.uppercase() }
            return CapitalizationResult(CapitalizationType.SENTENCE_START, capitalized)
        }

        return CapitalizationResult(CapitalizationType.NONE, null)
    }

    /**
     * Inspects preceding text before rawWord to detect sentence start.
     */
    fun isSentenceStart(rawWord: String, textBeforeCursor: String): Boolean {
        // Strip the current rawWord from the end of textBeforeCursor
        val prefix = if (textBeforeCursor.endsWith(rawWord, ignoreCase = true)) {
            textBeforeCursor.substring(0, textBeforeCursor.length - rawWord.length)
        } else {
            textBeforeCursor
        }

        val trimmed = prefix.trimEnd(' ', '\t', '\r')
        if (trimmed.isEmpty()) {
            // Text field start
            return true
        }

        val lastChar = trimmed.last()
        if (lastChar == '.' || lastChar == '?' || lastChar == '!' || lastChar == '\n') {
            return true
        }

        // Check if immediately inside opening quote or bracket that starts after sentence boundary or start
        if (lastChar == '"' || lastChar == '“' || lastChar == '\'' || lastChar == '‘' ||
            lastChar == '(' || lastChar == '[' || lastChar == '{' || lastChar == '<'
        ) {
            val beforeQuote = trimmed.dropLast(1).trimEnd(' ', '\t', '\r')
            if (beforeQuote.isEmpty() || beforeQuote.last() in charArrayOf('.', '?', '!', '\n')) {
                return true
            }
        }

        return false
    }
}
