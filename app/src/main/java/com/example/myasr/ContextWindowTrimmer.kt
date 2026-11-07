package com.example.myasr

/**
 * Universal, language-agnostic context window trimmer for on-device SLM inference.
 *
 * Designed according to the LM-First unified surface-token architecture:
 * 1. Language-Agnostic Token Budgeting:
 *    Measures and constraints context length using estimated BPE tokens rather than raw character counts,
 *    unifying English QWERTY (~4.2 chars/token), Japanese Flick (~1.3 chars/token), and code-switching texts
 *    under a deterministic inference latency budget.
 *
 * 2. Universal 3-Tier Structural Boundary Alignment:
 *    Avoids mid-lexeme cuts (e.g. "re|garding", "販|売戦略") and orphan morphemes (e.g. "された", "'t")
 *    by snapping to natural linguistic boundaries:
 *    - Tier 1 (Highest): Sentence terminals ('.', '。', '!', '！', '?', '？', '\n')
 *    - Tier 2 (High): Clause delimiters (',', '、', ';', ':', ')', '）', '」', '』', '】')
 *    - Tier 3 (Standard): Free-morpheme / lexical word onsets (spaces in Latin, Hiragana -> Kanji/Katakana in Japanese)
 *
 * 3. Space & BOS Normalization:
 *    Trims leading whitespace so the first token aligns cleanly with the BOS token (<s>),
 *    while strictly preserving trailing whitespace (before cursor) for space-adaptive BPE evaluation.
 */
object ContextWindowTrimmer {

    const val DEFAULT_TARGET_TOKENS = 24
    const val DEFAULT_MIN_TOKENS = 10
    const val DEFAULT_MAX_TOKENS = 36

    private val SENTENCE_TERMINALS = charArrayOf('。', '．', '.', '！', '!', '？', '?', '\n', '\r')
    private val CLAUSE_DELIMITERS = charArrayOf('、', '，', ',', '；', ';', ':', '：', '—', '—', ')', '）', ']', '」', '』', '】', '"', '\'')

    private val ENGLISH_ABBREVIATIONS = setOf(
        "mr.", "mrs.", "ms.", "dr.", "prof.", "sr.", "jr.", "vs.", "etc.",
        "e.g.", "i.e.", "u.s.", "inc.", "ltd.", "co.", "corp.", "dept.", "fig.", "no."
    )

    private val ENGLISH_CLITICS = listOf("'ll", "'ve", "'re", "'d", "n't", "'s", "'m")

    enum class CharClass {
        LATIN,
        HIRAGANA,
        KATAKANA,
        KANJI,
        SPACE,
        OTHER
    }

    /**
     * Estimates the BPE token count in a language-agnostic manner.
     * Uses corpus-derived character densities:
     * - ASCII Latin & numbers: ~4.2 characters per BPE token
     * - CJK & multibyte scripts: ~1.3 characters per BPE token
     */
    fun estimateTokenCount(text: CharSequence): Float {
        if (text.isEmpty()) return 0.0f
        var asciiCount = 0
        var cjkCount = 0
        for (i in 0 until text.length) {
            val code = text[i].code
            if (code < 128) {
                asciiCount++
            } else {
                cjkCount++
            }
        }
        return (asciiCount / 4.2f) + (cjkCount / 1.3f)
    }

    fun getCharClass(ch: Char): CharClass {
        val code = ch.code
        if ((code in 65..90) || (code in 97..122) || (code in 48..57) || ch == '\'') {
            return CharClass.LATIN
        }
        if (code in 0x3040..0x309F) {
            return CharClass.HIRAGANA
        }
        if (code in 0x30A0..0x30FF || ch == 'ー') {
            return CharClass.KATAKANA
        }
        if (code in 0x4E00..0x9FFF || code in 0x3400..0x4DBF) {
            return CharClass.KANJI
        }
        if (Character.isWhitespace(ch)) {
            return CharClass.SPACE
        }
        return CharClass.OTHER
    }

    private fun isSentenceTerminal(text: CharSequence, idx: Int): Boolean {
        val ch = text[idx]
        if (ch !in SENTENCE_TERMINALS) return false

        if (ch == '.') {
            // Avoid decimal point in numbers (e.g., "3.14")
            if (idx > 0 && idx + 1 < text.length && text[idx - 1].isDigit() && text[idx + 1].isDigit()) {
                return false
            }
            // Check preceding word against common English abbreviations
            var start = idx - 1
            while (start >= 0 && (text[start].isLetter() || text[start] == '.')) {
                start--
            }
            val word = text.subSequence(start + 1, idx + 1).toString().lowercase()
            if (word in ENGLISH_ABBREVIATIONS) {
                return false
            }
        }
        return true
    }

    private data class CutCandidate(
        val tier: Int,
        val tokenDistance: Float,
        val cutIndex: Int
    )

    /**
     * Extracts an optimal preceding context window bounded by token budget [targetTokens].
     * Snaps to the cleanest syntactic/lexical boundary without corrupting words or morphemes.
     */
    fun trimToOptimalContext(
        rawText: String,
        targetTokens: Int = DEFAULT_TARGET_TOKENS,
        minTokens: Int? = null,
        maxTokens: Int? = null
    ): String {
        if (rawText.isBlank() || targetTokens <= 0) {
            return ""
        }

        val totalEstimated = estimateTokenCount(rawText)
        if (totalEstimated <= targetTokens) {
            return normalizeOutput(rawText)
        }

        val n = rawText.length
        val candidates = mutableListOf<CutCandidate>()

        val effectiveMin = minTokens ?: maxOf(1, (targetTokens * 0.35f).toInt())
        val effectiveMax = maxTokens ?: maxOf(targetTokens + 4, (targetTokens * 1.4f).toInt())

        // Search backward from end of text to evaluate potential start positions i
        for (i in (n - 1) downTo 0) {
            // Guard surrogate pairs
            if (Character.isLowSurrogate(rawText[i])) {
                continue
            }

            val tail = rawText.substring(i)
            val tokens = estimateTokenCount(tail)
            if (tokens < effectiveMin) {
                continue
            }
            if (tokens > effectiveMax) {
                break
            }

            val prevChar = if (i > 0) rawText[i - 1] else null
            val currChar = rawText[i]

            // Tier 1: Immediately following a sentence terminal
            if (prevChar != null && isSentenceTerminal(rawText, i - 1)) {
                candidates.add(CutCandidate(tier = 1, tokenDistance = kotlin.math.abs(tokens - targetTokens), cutIndex = i))
                continue
            }

            // Tier 2: Immediately following a clause / delimiter boundary
            if (prevChar != null && prevChar in CLAUSE_DELIMITERS) {
                candidates.add(CutCandidate(tier = 2, tokenDistance = kotlin.math.abs(tokens - targetTokens), cutIndex = i))
                continue
            }

            // Tier 3: Universal Free-Morpheme / Word Onset
            val prevClass = if (prevChar != null) getCharClass(prevChar) else CharClass.OTHER
            val currClass = getCharClass(currChar)

            // Invariant: Never cut inside a contiguous lexical run
            if (prevClass == CharClass.LATIN && currClass == CharClass.LATIN) continue
            if (prevClass == CharClass.KANJI && currClass == CharClass.KANJI) continue
            if (prevClass == CharClass.KATAKANA && currClass == CharClass.KATAKANA) continue

            // 1. Latin word onset following whitespace
            if (prevClass == CharClass.SPACE && currClass == CharClass.LATIN) {
                val tailSub = tail.lowercase()
                val isClitic = ENGLISH_CLITICS.any { tailSub.startsWith(it) }
                if (!isClitic) {
                    candidates.add(CutCandidate(tier = 3, tokenDistance = kotlin.math.abs(tokens - targetTokens), cutIndex = i))
                    continue
                }
            }

            // 2. Japanese Bunsetsu Onset: Hiragana (functional particle/auxiliary) -> Kanji/Katakana/Latin (content word)
            if (prevClass == CharClass.HIRAGANA && (currClass == CharClass.KANJI || currClass == CharClass.KATAKANA || currClass == CharClass.LATIN)) {
                candidates.add(CutCandidate(tier = 3, tokenDistance = kotlin.math.abs(tokens - targetTokens), cutIndex = i))
                continue
            }

            // 3. Opening bracket / quotation onset
            if (currChar in "「『（([{\"") {
                candidates.add(CutCandidate(tier = 3, tokenDistance = kotlin.math.abs(tokens - targetTokens), cutIndex = i))
                continue
            }
        }

        if (candidates.isEmpty()) {
            // Fallback: estimate character budget directly and avoid cutting Latin word
            var cutIdx = (n - (targetTokens * 2.5f).toInt()).coerceIn(0, n - 1)
            while (cutIdx < n && cutIdx > 0 && getCharClass(rawText[cutIdx]) == CharClass.LATIN && getCharClass(rawText[cutIdx - 1]) == CharClass.LATIN) {
                cutIdx++
            }
            if (cutIdx >= n) {
                cutIdx = (n - 1).coerceAtLeast(0)
            }
            return normalizeOutput(rawText.substring(cutIdx))
        }

        // Sort by Tier ascending, then by token distance ascending
        candidates.sortWith(compareBy({ it.tier }, { it.tokenDistance }))
        val bestCut = candidates[0].cutIndex
        return normalizeOutput(rawText.substring(bestCut))
    }

    /**
     * Normalizes trimmed context:
     * - Trims leading whitespace to align cleanly with BOS (<s>).
     * - Preserves trailing whitespace (right before cursor) for space-adaptive tokenization.
     */
    private fun normalizeOutput(text: String): String {
        return text.trimStart()
    }
}
