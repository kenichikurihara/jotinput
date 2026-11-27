package com.example.myasr.context.speech.domain

import com.example.myasr.context.vocabulary.domain.DictionaryEntry

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
     * Formats English ASR output, applies learned voice corrections (S2/S3/S4/S5),
     * and resolves active custom user dictionary entries.
     */
    fun formatWithCorrections(
        rawText: String,
        contextBefore: String = "",
        learnedCorrections: List<VoiceCorrectionEntry> = emptyList(),
        nowMs: Long = System.currentTimeMillis(),
        slmCoherenceDeltaProvider: ((origSentence: String, corrSentence: String) -> Float?)? = null,
        userDictionaryEntries: List<DictionaryEntry> = emptyList(),
        wordLogFreqProvider: ((String) -> Float?)? = null,
        suppressedPairs: Set<Pair<String, String>> = emptySet()
    ): VoiceCorrectionApplyResult {
        val baseFormatted = format(rawText, contextBefore)
        if (baseFormatted.isEmpty()) {
            return VoiceCorrectionApplyResult(baseFormatted, emptyList(), emptyList())
        }

        val correctionRes = if (learnedCorrections.isNotEmpty()) {
            VoiceCorrectionScoring.applyCorrections(
                text = baseFormatted,
                contextBefore = contextBefore,
                entries = learnedCorrections,
                language = AsrLanguage.ENGLISH,
                nowMs = nowMs,
                slmCoherenceDeltaProvider = slmCoherenceDeltaProvider
            )
        } else {
            VoiceCorrectionApplyResult(baseFormatted, emptyList(), emptyList())
        }

        if (userDictionaryEntries.isEmpty()) {
            return correctionRes
        }

        val protectedRanges = computeProtectedRanges(
            text = correctionRes.formattedText,
            appliedCorrections = correctionRes.appliedCorrections
        )
        val userDictRes = EnglishVoiceUserDictionaryResolver.apply(
            text = correctionRes.formattedText,
            contextBefore = contextBefore,
            entries = userDictionaryEntries,
            wordLogFreqProvider = wordLogFreqProvider,
            slmCoherenceDeltaProvider = slmCoherenceDeltaProvider,
            suppressedPairs = suppressedPairs,
            protectedRanges = protectedRanges
        )

        val mergedApplied = correctionRes.appliedCorrections + userDictRes.appliedCorrections
        val seenChips = HashSet<Pair<String, String>>(
            correctionRes.suggestedChips.size + userDictRes.suggestedChips.size
        )
        val mergedChips = ArrayList<VoiceCorrectionSuggestion>(
            correctionRes.suggestedChips.size + userDictRes.suggestedChips.size
        )
        for (chip in correctionRes.suggestedChips) {
            if (seenChips.add(chip.originalSurface.lowercase() to chip.correctedSurface)) {
                mergedChips.add(chip)
            }
        }
        for (chip in userDictRes.suggestedChips) {
            if (seenChips.add(chip.originalSurface.lowercase() to chip.correctedSurface)) {
                mergedChips.add(chip)
            }
        }

        return VoiceCorrectionApplyResult(
            formattedText = userDictRes.formattedText,
            appliedCorrections = mergedApplied,
            suggestedChips = mergedChips
        )
    }

    private fun computeProtectedRanges(
        text: String,
        appliedCorrections: List<VoiceCorrectionSuggestion>
    ): List<IntRange> {
        if (appliedCorrections.isEmpty() || text.isEmpty()) return emptyList()
        val ranges = ArrayList<IntRange>(appliedCorrections.size)
        for (corr in appliedCorrections) {
            val surface = corr.correctedSurface
            if (surface.isEmpty()) continue
            var startIdx = 0
            while (startIdx < text.length) {
                val found = text.indexOf(surface, startIndex = startIdx)
                if (found < 0) break
                ranges.add(found until (found + surface.length))
                startIdx = found + surface.length
            }
        }
        return ranges
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
