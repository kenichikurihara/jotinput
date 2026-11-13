package com.example.myasr.context.speech.domain

/**
 * Pure domain post-processor for English ASR outputs (Parakeet TDT-CTC 110M).
 *
 * Parakeet TDT-CTC 110M outputs native English punctuation and capitalization (PnC),
 * so this processor preserves casing/punctuation while handling boundary spacing
 * against preceding text and word-boundary-safe streaming prefix freezing.
 */
object EnglishVoicePostProcessor {

    private val BOUNDARY_PRECEDING_CHARS = setOf('.', ',', '!', '?', ':', ';', ')', ']', '"', '\'')

    /**
     * Normalizes whitespace and prepends a single space if [contextBefore] ends with
     * an alphanumeric character or sentence/clause punctuation without trailing whitespace.
     */
    fun format(rawText: String, contextBefore: String = ""): String {
        val trimmed = rawText.trim().replace(Regex("\\s+"), " ")
        if (trimmed.isEmpty()) return ""

        if (contextBefore.isEmpty()) return trimmed

        val lastChar = contextBefore.last()
        val needsLeadingSpace = lastChar.isLetterOrDigit() || lastChar in BOUNDARY_PRECEDING_CHARS
        return if (needsLeadingSpace) " $trimmed" else trimmed
    }

    /**
     * Splits [currentFormatted] into `(frozenPrefix, activeSuffix)` for streaming English voice input.
     * Computes longest common prefix (LCP) over the last [minConsecutiveSteps] steps,
     * protects [protectTrailingChars] at the tail, and snaps the freeze point back to a
     * whitespace boundary so partial words are never frozen mid-word.
     */
    fun extractWordAwareFreezePrefix(
        history: List<String>,
        currentFormatted: String,
        minConsecutiveSteps: Int = 3,
        protectTrailingChars: Int = 12
    ): Pair<String, String> {
        if (currentFormatted.isEmpty() || history.size < minConsecutiveSteps) {
            return "" to currentFormatted
        }

        val window = history.takeLast(minConsecutiveSteps)
        var lcpLen = currentFormatted.length
        for (step in window) {
            val common = commonPrefixLength(currentFormatted, step)
            if (common < lcpLen) lcpLen = common
        }

        val maxFreezeLen = (currentFormatted.length - protectTrailingChars).coerceAtLeast(0)
        var freezeLen = lcpLen.coerceAtMost(maxFreezeLen)
        if (freezeLen <= 0) {
            return "" to currentFormatted
        }

        // Snap back to the nearest whitespace boundary if we are in the middle of a word
        if (freezeLen < currentFormatted.length && !currentFormatted[freezeLen].isWhitespace()) {
            val lastSpace = currentFormatted.lastIndexOf(' ', freezeLen - 1)
            freezeLen = if (lastSpace >= 0) lastSpace + 1 else 0
        }

        if (freezeLen <= 0) {
            return "" to currentFormatted
        }

        return currentFormatted.substring(0, freezeLen) to currentFormatted.substring(freezeLen)
    }

    private fun commonPrefixLength(a: String, b: String): Int {
        val len = minOf(a.length, b.length)
        var i = 0
        while (i < len && a[i] == b[i]) {
            i++
        }
        return i
    }
}
