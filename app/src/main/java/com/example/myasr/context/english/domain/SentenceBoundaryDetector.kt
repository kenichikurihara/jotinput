package com.example.myasr.context.english.domain

/**
 * Punkt-style probabilistic sentence boundary detector P(BOS | rawWord, textBeforeCursor, policy).
 *
 * Replaces naive period-matching heuristics with structural token disambiguation:
 * - Internal-period abbreviations/domains (e.g. "e.g.", "i.e.", "U.S.", "v1.0")
 * - Single-letter initials (e.g. "J. K.")
 * - Numbered lists after newline ("1. First") vs inline ordinals ("Fig. 1.")
 * - Corpus-derived abbreviation EOS probabilities P(EOS | token, '.')
 * - Transparent opening quotes and brackets ("\"hello", "(hello")
 */
object SentenceBoundaryDetector {

    private val OPENING_DELIMITERS = charArrayOf('"', '“', '\'', '‘', '(', '[', '{', '<')

    fun computeBosProbability(
        rawWord: String,
        textBeforeCursor: String,
        policy: InputFieldCapPolicy = InputFieldCapPolicy.STANDARD
    ): Float {
        if (policy == InputFieldCapPolicy.BYPASS) return 0.0f

        val prefix = if (rawWord.isNotEmpty() && textBeforeCursor.endsWith(rawWord, ignoreCase = true)) {
            textBeforeCursor.substring(0, textBeforeCursor.length - rawWord.length)
        } else {
            textBeforeCursor
        }

        // Skip trailing horizontal whitespace and transparent opening quotes/brackets
        var idx = prefix.length - 1
        while (idx >= 0 && (prefix[idx] == ' ' || prefix[idx] == '\t' || prefix[idx] == '\r')) {
            idx--
        }
        while (idx >= 0 && prefix[idx] in OPENING_DELIMITERS) {
            idx--
            while (idx >= 0 && (prefix[idx] == ' ' || prefix[idx] == '\t' || prefix[idx] == '\r')) {
                idx--
            }
        }

        if (idx < 0) {
            // Start of text field
            return 1.0f
        }

        return when (val ch = prefix[idx]) {
            '\n' -> 1.0f
            '!', '?', '！', '？', '。' -> PROB_DEFINITE_BOUNDARY
            '.' -> evaluatePeriodBoundary(prefix, idx)
            else -> 0.0f
        }
    }

    private const val PROB_DEFINITE_BOUNDARY = 0.98f
    private const val PROB_AMBIGUOUS_BOUNDARY = 0.20f
    private const val PROB_NON_BOUNDARY = 0.05f

    private fun evaluatePeriodBoundary(prefix: String, periodIdx: Int): Float {
        // Check for ellipsis ("..." or "..")
        if (periodIdx > 0 && prefix[periodIdx - 1] == '.') {
            return PROB_AMBIGUOUS_BOUNDARY
        }

        // Extract the token immediately preceding the period
        var start = periodIdx - 1
        while (start >= 0 && !prefix[start].isWhitespace() && prefix[start] !in OPENING_DELIMITERS) {
            start--
        }
        val tokenBeforePeriod = prefix.substring(start + 1, periodIdx)
        if (tokenBeforePeriod.isEmpty()) {
            return PROB_DEFINITE_BOUNDARY
        }

        // 1. Internal-period token (e.g., "e.g", "i.e", "U.S", "a.m", "p.m", "v1.0", "google.com")
        if (tokenBeforePeriod.contains('.')) {
            return PROB_NON_BOUNDARY
        }

        // 2. Single-letter initial (e.g., "J.", "A.")
        if (tokenBeforePeriod.length == 1 && tokenBeforePeriod[0].isLetter()) {
            return PROB_NON_BOUNDARY
        }

        // 3. Numeric list item after newline/start ("\n1. ") vs inline number ("Fig. 1. ")
        if (tokenBeforePeriod.all { it.isDigit() }) {
            val beforeNum = prefix.substring(0, start + 1).trimEnd(' ', '\t', '\r')
            return if (beforeNum.isEmpty() || beforeNum.last() == '\n') PROB_DEFINITE_BOUNDARY else PROB_NON_BOUNDARY
        }

        // 4. Corpus-derived Punkt abbreviation EOS probability P(EOS | stem, '.')
        val cleanStem = tokenBeforePeriod.trimEnd(',', ';', ':', ')', ']').lowercase()
        val abbrevP = EnglishCaseModel.abbreviationEosProbability(cleanStem)
        if (abbrevP != null) {
            return abbrevP
        }

        // 5. Standard sentence-ending period
        return PROB_DEFINITE_BOUNDARY
    }
}
