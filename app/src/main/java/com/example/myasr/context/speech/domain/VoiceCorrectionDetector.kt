package com.example.myasr.context.speech.domain

import com.example.myasr.context.kanakanji.domain.JapaneseKanaUtils
import kotlin.math.min

data class DetectedVoiceCorrection(
    val language: AsrLanguage,
    val originalSurface: String,
    val correctedSurface: String,
    val normalizedReading: String,
    val category: CorrectionCategory,
    val phoneticDistance: Float,
    val contextTokens: List<String>
)

/**
 * Pure domain detector for identifying user corrections to voice-input text (JP & EN).
 * Combines:
 *  - D1: Explicit typed reading capture (when user retyped via Flick/Kana)
 *  - D2: Snapshot prefix/suffix diff alignment
 *  - D3: Phonetic & G2P plausibility gate (via DMN VoiceCorrectionDecisionModel)
 *  - D4: Morpheme / script-block / token-boundary span expansion
 */
object VoiceCorrectionDetector {

    const val EDIT_WINDOW_MS = 15_000L
    private const val MAX_SPAN_LENGTH = 36

    private val COMMON_JP_SUFFIXES = listOf("先生", "さん", "部長", "課長", "社長", "担当", "様", "くん", "ちゃん", "氏")

    private val VOICING_GROUPS = listOf(
        setOf('か', 'き', 'く', 'け', 'こ', 'が', 'ぎ', 'ぐ', 'げ', 'ご'),
        setOf('さ', 'し', 'す', 'せ', 'そ', 'ざ', 'じ', 'ず', 'ぜ', 'ぞ'),
        setOf('た', 'ち', 'つ', 'て', 'と', 'だ', 'ぢ', 'づ', 'で', 'ど'),
        setOf('は', 'ひ', 'ふ', 'へ', 'ほ', 'ば', 'び', 'ぶ', 'べ', 'ぼ', 'ぱ', 'ぴ', 'ぷ', 'ぺ', 'ぽ'),
        setOf('う', 'ゔ')
    )

    private val SMALL_TO_LARGE = mapOf(
        'ぁ' to 'あ', 'ぃ' to 'い', 'ぅ' to 'う', 'ぇ' to 'え', 'ぉ' to 'お',
        'ゃ' to 'や', 'ゅ' to 'ゆ', 'ょ' to 'よ', 'っ' to 'つ'
    )

    /**
     * Detects whether editing [beforeText] -> [afterText] represents a valid voice correction.
     * Returns null if it is a continuation, unchanged, or rejected as a semantic rewrite.
     */
    fun detect(
        beforeText: String,
        afterText: String,
        language: AsrLanguage,
        contextBefore: String = "",
        elapsedSinceVoiceCommitMs: Long = 0L,
        typedReadingHint: String = "",
        readingResolver: ((String) -> String?)? = null
    ): DetectedVoiceCorrection? {
        if (beforeText.isEmpty() || afterText.isEmpty() || beforeText == afterText) {
            return null
        }
        // Continuation guard: simply appending after voice output is not a correction
        if (afterText.startsWith(beforeText)) {
            return null
        }

        val withinWindow = elapsedSinceVoiceCommitMs in 0L..EDIT_WINDOW_MS
        if (!withinWindow) {
            return null
        }

        val span = computeDiffSpan(beforeText, afterText) ?: return null
        val (uOrig, uCorr) = expandSpanBoundary(beforeText, afterText, span, language)

        val spanValid = uOrig.isNotEmpty() &&
            uCorr.isNotEmpty() &&
            uOrig != uCorr &&
            uOrig.length <= MAX_SPAN_LENGTH &&
            uCorr.length <= MAX_SPAN_LENGTH

        if (!spanValid) {
            return null
        }

        val isExactCaseOrCompoundFix = normalizeForExactComparison(uOrig, language) ==
            normalizeForExactComparison(uCorr, language)

        val normTypedReading = if (typedReadingHint.isNotBlank()) {
            JapaneseKanaUtils.normalizeReading(typedReadingHint)
        } else {
            ""
        }

        val resolvedReadingCorr = when {
            language == AsrLanguage.ENGLISH -> uCorr.lowercase()
            normTypedReading.isNotEmpty() -> normTypedReading
            else -> readingResolver?.invoke(uCorr)?.let { JapaneseKanaUtils.normalizeReading(it) } ?: ""
        }

        val resolvedReadingOrig = when {
            language == AsrLanguage.ENGLISH -> uOrig.lowercase()
            else -> readingResolver?.invoke(uOrig)?.let { JapaneseKanaUtils.normalizeReading(it) } ?: ""
        }

        val phoneticDist = when {
            isExactCaseOrCompoundFix -> 0.0f
            language == AsrLanguage.ENGLISH -> englishPhoneticDistance(uOrig, uCorr)
            else -> {
                if (resolvedReadingOrig.isNotEmpty() && resolvedReadingCorr.isNotEmpty()) {
                    weightedKanaDistance(resolvedReadingOrig, resolvedReadingCorr)
                } else {
                    // Fallback when reading resolver cannot resolve one side (e.g. mixed script/Kanji variant)
                    fallbackJapaneseSurfaceDistance(uOrig, uCorr)
                }
            }
        }

        val eligibility = VoiceCorrectionDecisionModel.evaluateEligibility(
            VoiceCorrectionDecisionModel.EligibilityInput(
                isWithinEditWindow = true,
                phoneticDistance = phoneticDist,
                isExactCaseOrCompoundFix = isExactCaseOrCompoundFix,
                spanLengthValid = true
            )
        ).result

        if (eligibility == CorrectionCategory.IGNORE_OUTSIDE_WINDOW ||
            eligibility == CorrectionCategory.IGNORE_INVALID_SPAN ||
            eligibility == CorrectionCategory.REJECT_SEMANTIC_REWRITE
        ) {
            return null
        }

        val finalReading = if (language == AsrLanguage.ENGLISH) {
            uCorr.lowercase().replace(Regex("[\\s\\-']+"), "")
        } else {
            resolvedReadingCorr.ifEmpty {
                JapaneseKanaUtils.normalizeReading(uCorr)
            }
        }

        return DetectedVoiceCorrection(
            language = language,
            originalSurface = uOrig,
            correctedSurface = uCorr,
            normalizedReading = finalReading,
            category = eligibility,
            phoneticDistance = phoneticDist,
            contextTokens = extractContextTokens(contextBefore)
        )
    }

    internal data class DiffSpan(val origStart: Int, val origEnd: Int, val corrStart: Int, val corrEnd: Int)

    internal fun computeDiffSpan(before: String, after: String): DiffSpan? {
        if (before == after) return null
        var prefix = 0
        val minLen = min(before.length, after.length)
        while (prefix < minLen && before[prefix] == after[prefix]) {
            prefix++
        }
        var suffix = 0
        while (suffix < (before.length - prefix) &&
            suffix < (after.length - prefix) &&
            before[before.length - 1 - suffix] == after[after.length - 1 - suffix]
        ) {
            suffix++
        }
        return DiffSpan(prefix, before.length - suffix, prefix, after.length - suffix)
    }

    private fun isKanji(ch: Char): Boolean {
        return ch in '\u4E00'..'\u9FFF' ||
            ch in '\u3400'..'\u4DBF' ||
            ch in '\uF900'..'\uFAFF' ||
            ch == '々' || ch == 'ヶ'
    }

    private fun isKatakana(ch: Char): Boolean = ch in '\u30A0'..'\u30FF'

    private fun isAsciiAlnum(ch: Char): Boolean = ch.isLetterOrDigit() && ch.code < 128

    private fun jpCharClass(ch: Char): Int = when {
        isKanji(ch) -> 1
        isKatakana(ch) -> 2
        isAsciiAlnum(ch) -> 3
        else -> 0
    }

    internal fun expandSpanBoundary(
        before: String,
        after: String,
        span: DiffSpan,
        language: AsrLanguage
    ): Pair<String, String> {
        var os = span.origStart
        var oe = span.origEnd
        var cs = span.corrStart
        var ce = span.corrEnd

        if (language == AsrLanguage.ENGLISH) {
            while (os > 0 && (before[os - 1].isLetterOrDigit() || before[os - 1] == '-' || before[os - 1] == '\'')) {
                os--
            }
            while (oe < before.length && (before[oe].isLetterOrDigit() || before[oe] == '-' || before[oe] == '\'')) {
                oe++
            }
            while (cs > 0 && (after[cs - 1].isLetterOrDigit() || after[cs - 1] == '-' || after[cs - 1] == '\'')) {
                cs--
            }
            while (ce < after.length && (after[ce].isLetterOrDigit() || after[ce] == '-' || after[ce] == '\'')) {
                ce++
            }
            return before.substring(os, oe).trim() to after.substring(cs, ce).trim()
        } else {
            if (os < oe) {
                val clsL = jpCharClass(before[os])
                if (clsL != 0) {
                    while (os > 0 && jpCharClass(before[os - 1]) == clsL) os--
                }
                val clsR = jpCharClass(before[oe - 1])
                if (clsR != 0) {
                    while (oe < before.length && jpCharClass(before[oe]) == clsR) oe++
                }
            }
            if (cs < ce) {
                val clsL = jpCharClass(after[cs])
                if (clsL != 0) {
                    while (cs > 0 && jpCharClass(after[cs - 1]) == clsL) cs--
                }
                val clsR = jpCharClass(after[ce - 1])
                if (clsR != 0) {
                    while (ce < after.length && jpCharClass(after[ce]) == clsR) ce++
                }
            }

            // Synchronize outer anchors for mixed-script compounds (e.g. "グラフ九L" vs "GraphQL")
            val leftAnchor = min(os, cs)
            val rightTail = min(before.length - oe, after.length - ce)
            os = leftAnchor
            oe = (before.length - rightTail).coerceAtLeast(os)
            cs = leftAnchor
            ce = (after.length - rightTail).coerceAtLeast(cs)

            var uOrig = before.substring(os, oe).trim()
            var uCorr = after.substring(cs, ce).trim()

            for (suf in COMMON_JP_SUFFIXES) {
                if (uOrig.endsWith(suf) && uCorr.endsWith(suf) &&
                    uOrig.length > suf.length && uCorr.length > suf.length
                ) {
                    uOrig = uOrig.dropLast(suf.length)
                    uCorr = uCorr.dropLast(suf.length)
                    break
                }
            }
            return uOrig to uCorr
        }
    }

    private fun normalizeForExactComparison(text: String, language: AsrLanguage): String {
        return if (language == AsrLanguage.ENGLISH) {
            text.lowercase().replace(Regex("[\\s\\-']+"), "")
        } else {
            text.replace(Regex("\\s+"), "").lowercase()
        }
    }

    fun weightedKanaDistance(r1: String, r2: String): Float {
        val a = JapaneseKanaUtils.normalizeReading(r1).replace("ー", "")
        val b = JapaneseKanaUtils.normalizeReading(r2).replace("ー", "")
        if (a.isEmpty() && b.isEmpty()) return 0.0f
        if (a.isEmpty() || b.isEmpty()) return 1.0f

        val m = a.length
        val n = b.length
        val dp = Array(m + 1) { FloatArray(n + 1) }
        for (i in 0..m) dp[i][0] = i.toFloat()
        for (j in 0..n) dp[0][j] = j.toFloat()

        for (i in 1..m) {
            for (j in 1..n) {
                val c1 = a[i - 1]
                val c2 = b[j - 1]
                val subCost = when {
                    c1 == c2 -> 0.0f
                    (SMALL_TO_LARGE[c1] ?: c1) == (SMALL_TO_LARGE[c2] ?: c2) -> 0.3f
                    VOICING_GROUPS.any { c1 in it && c2 in it } -> 0.3f
                    else -> 1.0f
                }
                dp[i][j] = min(
                    min(dp[i - 1][j] + 1.0f, dp[i][j - 1] + 1.0f),
                    dp[i - 1][j - 1] + subCost
                )
            }
        }
        return dp[m][n] / maxOf(m, n).toFloat()
    }

    private fun fallbackJapaneseSurfaceDistance(uOrig: String, uCorr: String): Float {
        // Kanji variants (e.g. 渡辺 -> 渡邊) or mixed-script tech terms with same length or shared chars
        if (uOrig.length <= 4 && uCorr.length <= 4 && uOrig.any { isKanji(it) } && uCorr.any { isKanji(it) }) {
            val shared = uOrig.count { it in uCorr }
            if (shared > 0) return 0.2f
        }
        val n1 = JapaneseKanaUtils.normalizeReading(uOrig)
        val n2 = JapaneseKanaUtils.normalizeReading(uCorr)
        return weightedKanaDistance(n1, n2)
    }

    fun englishPhoneticSkeleton(s: String): String {
        var w = s.lowercase().replace(Regex("[^a-z]"), "")
        if (w.isEmpty()) return ""
        w = w.replace("igh", "ai").replace("ght", "t").replace("gh", "")
        w = w.replace("ph", "f").replace("sh", "S").replace("ch", "C")
        w = w.replace("ck", "k").replace("qu", "kw").replace("x", "ks")
        if (w.startsWith("wr")) w = w.substring(1)
        if (w.startsWith("kn")) w = w.substring(1)
        w = w.replace(Regex("[aeiouy]+"), "a")
        val sb = StringBuilder()
        for (ch in w) {
            if (sb.isEmpty() || sb.last() != ch) {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    fun englishPhoneticDistance(uOrig: String, uCorr: String): Float {
        val n1 = uOrig.lowercase().replace(Regex("[\\s\\-']+"), "")
        val n2 = uCorr.lowercase().replace(Regex("[\\s\\-']+"), "")
        if (n1 == n2) return 0.0f
        val dChar = normalizedLevenshtein(n1, n2)
        val s1 = englishPhoneticSkeleton(uOrig)
        val s2 = englishPhoneticSkeleton(uCorr)
        val dMeta = normalizedLevenshtein(s1, s2)
        val isSubseq = n2.length >= 4 && n1.isNotEmpty() && n1[0] == n2[0] && isSubsequence(n2, n1)
        return if (isSubseq) min(min(dChar, dMeta), 0.35f) else min(dChar, dMeta)
    }

    private fun isSubsequence(sub: String, target: String): Boolean {
        var i = 0
        for (ch in target) {
            if (i < sub.length && ch == sub[i]) i++
        }
        return i == sub.length
    }

    private fun normalizedLevenshtein(a: String, b: String): Float {
        if (a == b) return 0.0f
        if (a.isEmpty() || b.isEmpty()) return 1.0f
        val m = a.length
        val n = b.length
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j
        for (i in 1..m) {
            for (j in 1..n) {
                val c = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = min(min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + c)
            }
        }
        return dp[m][n].toFloat() / maxOf(m, n).toFloat()
    }

    fun extractContextTokens(contextBefore: String, maxTokens: Int = 12): List<String> {
        if (contextBefore.isBlank()) return emptyList()
        val tail = contextBefore.takeLast(120)
        return tail.split(Regex("[\\s、。,.!?「」()\\[\\]\"']+"))
            .map { it.trim() }
            .filter { it.length >= 2 }
            .distinct()
            .takeLast(maxTokens)
    }
}
