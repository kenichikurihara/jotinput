package com.example.myasr.context.speech.domain

import com.example.myasr.context.vocabulary.domain.DictionaryEntry
import com.example.myasr.context.vocabulary.domain.Priority
import kotlin.math.max
import kotlin.math.min

/**
 * Pure domain resolver prioritizing active custom user dictionary entries during
 * English voice recognition post-processing (NVIDIA Parakeet TDT-CTC 110M).
 *
 * Uses general English phonetics (Metaphone/G2P), spoken symbol/cardinal normalization,
 * aligned token subsequence matching, corpus log-frequency collision detection, and
 * SLM context-coherence gating arbitrated by [EnglishUserDictDecisionModel].
 * Zero hardcoded word tables.
 */
object EnglishVoiceUserDictionaryResolver {

    private const val MAX_SPAN_TOKENS = 6
    private const val MAX_SLM_EVALS_PER_UTTERANCE = 3

    private val PUNCT_BREAK_CHARS = setOf(',', '.', ';', ':', '!', '?', '(', ')', '[', ']', '{', '}', '"', '\n')

    private val CARDINAL_WORDS: Map<String, String> = mapOf(
        "zero" to "0", "one" to "1", "two" to "2", "three" to "3", "four" to "4",
        "five" to "5", "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9",
        "ten" to "10", "eleven" to "11", "twelve" to "12", "thirteen" to "13",
        "fourteen" to "14", "fifteen" to "15", "sixteen" to "16", "seventeen" to "17",
        "eighteen" to "18", "nineteen" to "19", "twenty" to "20"
    )
    private val DIGIT_TO_WORD: Map<String, String> = CARDINAL_WORDS.entries.associate { (k, v) -> v to k }

    private val REGEX_ED_VOICELESS = Regex("([kpfsc])ed$")
    private val REGEX_POST_VOCALIC_GH = Regex("(?<=[aeiouy])gh")
    private val REGEX_DG_FRONT = Regex("dg([eiy])")
    private val REGEX_SC_FRONT = Regex("sc([eiy])")
    private val REGEX_C_FRONT = Regex("c([eiy])")
    private val REGEX_SILENT_POST_H = Regex("(?<=[aeiouy])h(?![aeiouy])")
    private val REGEX_VOWELS = Regex("[aeiouy]+")
    private val REGEX_WHITESPACE = Regex("\\s+")

    data class TextToken(
        val raw: String,
        val start: Int,
        val end: Int,
        val lower: String,
        val breakChar: Char?
    )

    private data class TargetForm(
        val compactAlnum: String,
        val compactAlpha: String,
        val phoneticKey: String
    )

    private data class PreparedTarget(
        val entry: DictionaryEntry,
        val word: String,
        val compactAlnum: String,
        val forms: List<TargetForm>,
        val contextRegex: Regex
    )

    private data class CandidateMatch(
        val start: Int,
        val end: Int,
        val origSlice: String,
        val proposed: String,
        val action: EnglishUserDictAction,
        val matchKindRank: Int,
        val combinedDist: Float,
        val priorityScore: Float,
        val spanLen: Int
    ) : Comparable<CandidateMatch> {
        override fun compareTo(other: CandidateMatch): Int {
            if (matchKindRank != other.matchKindRank) return matchKindRank.compareTo(other.matchKindRank)
            val distCmp = combinedDist.compareTo(other.combinedDist)
            if (distCmp != 0) return distCmp
            val prioCmp = other.priorityScore.compareTo(priorityScore)
            if (prioCmp != 0) return prioCmp
            val spanCmp = other.spanLen.compareTo(spanLen)
            if (spanCmp != 0) return spanCmp
            return start.compareTo(other.start)
        }
    }

    data class ResolutionResult(
        val formattedText: String,
        val appliedCorrections: List<VoiceCorrectionSuggestion>,
        val suggestedChips: List<VoiceCorrectionSuggestion>
    )

    fun hasCjkOrKana(text: String): Boolean {
        for (ch in text) {
            val code = ch.code
            if (code in 0x3040..0x30FF ||
                code in 0x4E00..0x9FFF ||
                code in 0x3400..0x4DBF ||
                code in 0xF900..0xFAFF ||
                code in 0xFF00..0xFFEF
            ) {
                return true
            }
        }
        return false
    }

    /**
     * Accepts any enabled user dictionary entry whose surface word is Latin/ASCII-based
     * (excluding CJK/Kana surfaces), even when registered with a Japanese Kana reading.
     */
    fun isEligibleForEnglishVoice(entry: DictionaryEntry): Boolean {
        if (!entry.isEnabled) return false
        val word = entry.word.trim()
        if (word.isEmpty() || hasCjkOrKana(word)) return false
        val hasAsciiLetter = word.any { it in 'a'..'z' || it in 'A'..'Z' }
        if (!hasAsciiLetter) return false
        val compact = normalizeCompactAlnum(word)
        if (compact.length >= 2) return true
        val noSpaceLen = word.count { !it.isWhitespace() }
        return noSpaceLen >= 2 && expandSpokenSymbols(word) != word.lowercase()
    }

    internal fun expandSpokenSymbols(word: String): String {
        val sb = StringBuilder(word.length + 12)
        for (ch in word.trim().lowercase()) {
            when (ch) {
                '.' -> sb.append(" dot ")
                '/' -> sb.append(" slash ")
                '+' -> sb.append(" plus ")
                '#' -> sb.append(" sharp ")
                '-' -> sb.append(' ')
                else -> sb.append(ch)
            }
        }
        val tokens = sb.toString().trim().split(REGEX_WHITESPACE).filter { it.isNotEmpty() }
        return tokens.joinToString(" ") { tok -> DIGIT_TO_WORD[tok] ?: tok }
    }

    internal fun normalizeCompactAlnum(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            when (ch) {
                in 'a'..'z', in '0'..'9' -> sb.append(ch)
                in 'A'..'Z' -> sb.append((ch.code + 32).toChar())
            }
        }
        return sb.toString()
    }

    internal fun extractAsciiLowerLetters(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            when (ch) {
                in 'a'..'z' -> sb.append(ch)
                in 'A'..'Z' -> sb.append((ch.code + 32).toChar())
            }
        }
        return sb.toString()
    }

    /**
     * General English Metaphone/G2P phonetic skeleton key.
     */
    fun englishPhoneticKey(raw: String): String {
        var w = extractAsciiLowerLetters(raw)
        if (w.isEmpty()) return ""

        w = when {
            w.startsWith("kn") || w.startsWith("gn") || w.startsWith("pn") -> w.substring(1)
            w.startsWith("wr") || w.startsWith("ps") -> w.substring(1)
            w.startsWith("wh") -> "w" + w.substring(2)
            else -> w
        }

        if (w.length >= 4 && w.endsWith("mb")) {
            w = w.dropLast(1)
        }
        if (w.endsWith("que")) {
            w = w.dropLast(3) + "k"
        }
        w = REGEX_ED_VOICELESS.replace(w, "$1t")
        if (w.length >= 4 && w.endsWith("e") && w[w.length - 2] !in "aeiouy") {
            w = w.dropLast(1)
        }

        w = w.replace("igh", "a").replace("ght", "t")
        w = REGEX_POST_VOCALIC_GH.replace(w, "")
        w = w.replace("ph", "f")
        w = w.replace("tion", "San").replace("sion", "San")
        w = w.replace("sh", "S").replace("ch", "C").replace("th", "T")
        w = w.replace("ck", "k").replace("qu", "kw").replace("q", "k").replace("x", "ks")
        w = REGEX_DG_FRONT.replace(w, "j$1")
        w = REGEX_SC_FRONT.replace(w, "s$1")
        w = REGEX_C_FRONT.replace(w, "s$1")
        w = w.replace('c', 'k')
        w = REGEX_SILENT_POST_H.replace(w, "")

        val hasLeadingYConsonant = w.length >= 2 && w[0] == 'y' && w[1] in "aeiou"
        val body = if (hasLeadingYConsonant) w.substring(1) else w
        val collapsedVowels = REGEX_VOWELS.replace(body, "a")
        val combined = if (hasLeadingYConsonant) "y$collapsedVowels" else collapsedVowels

        val out = StringBuilder(combined.length)
        for (ch in combined) {
            if (out.isEmpty() || out[out.length - 1] != ch) {
                out.append(ch)
            }
        }
        return out.toString()
    }

    private fun isSimilar025(a: Char, b: Char): Boolean {
        return (a == 's' && b == 'z') || (a == 'z' && b == 's') ||
            (a == 't' && b == 'd') || (a == 'd' && b == 't') ||
            (a == 'p' && b == 'b') || (a == 'b' && b == 'p') ||
            (a == 'k' && b == 'g') || (a == 'g' && b == 'k') ||
            (a == 'f' && b == 'v') || (a == 'v' && b == 'f') ||
            (a == 'C' && b == 'j') || (a == 'j' && b == 'C') ||
            (a == 'T' && (b == 't' || b == 'd')) || ((a == 't' || a == 'd') && b == 'T')
    }

    private fun isSimilar030(a: Char, b: Char): Boolean {
        return (a == 'T' && b == 'f') || (a == 'f' && b == 'T') ||
            (a == 'g' && b == 'j') || (a == 'j' && b == 'g') ||
            (a == 'm' && b == 'n') || (a == 'n' && b == 'm') ||
            (a == 'w' && b == 'v') || (a == 'v' && b == 'w')
    }

    private fun onsetCompatible(c1: Char, c2: Char): Boolean = c1 == c2 || isSimilar025(c1, c2)

    fun weightedPhoneticDistance(k1: String, k2: String): Float {
        if (k1 == k2) return 0.0f
        if (k1.isEmpty() || k2.isEmpty()) return 1.0f

        val m = k1.length
        val n = k2.length
        val dp = Array(m + 1) { FloatArray(n + 1) }

        for (i in 1..m) {
            val indel = if (k1[i - 1] == 'r' && i > 1 && k1[i - 2] == 'a') 0.5f else 1.0f
            dp[i][0] = dp[i - 1][0] + indel
        }
        for (j in 1..n) {
            val indel = if (k2[j - 1] == 'r' && j > 1 && k2[j - 2] == 'a') 0.5f else 1.0f
            dp[0][j] = dp[0][j - 1] + indel
        }

        for (i in 1..m) {
            val c1 = k1[i - 1]
            val delCost = if (c1 == 'r' && i > 1 && k1[i - 2] == 'a') 0.5f else 1.0f
            for (j in 1..n) {
                val c2 = k2[j - 1]
                val insCost = if (c2 == 'r' && j > 1 && k2[j - 2] == 'a') 0.5f else 1.0f
                val sub = when {
                    c1 == c2 -> 0.0f
                    isSimilar025(c1, c2) -> 0.25f
                    isSimilar030(c1, c2) -> 0.30f
                    else -> 1.0f
                }
                dp[i][j] = min(
                    min(dp[i - 1][j] + delCost, dp[i][j - 1] + insCost),
                    dp[i - 1][j - 1] + sub
                )
            }
        }
        return dp[m][n] / max(m, n).toFloat()
    }

    internal fun normalizedLevenshtein(a: String, b: String): Float {
        if (a == b) return 0.0f
        if (a.isEmpty() || b.isEmpty()) return 1.0f
        val m = a.length
        val n = b.length
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j
        for (i in 1..m) {
            for (j in 1..n) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = min(min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost)
            }
        }
        return dp[m][n].toFloat() / max(m, n).toFloat()
    }

    internal fun alignedTokenSubsequenceMatch(spanTokensAlpha: List<String>, targetAlpha: String): Boolean {
        if (targetAlpha.length < 5 || spanTokensAlpha.size < 2) return false
        val totalSpanLen = spanTokensAlpha.sumOf { it.length }
        if (totalSpanLen == 0 || totalSpanLen.toFloat() / targetAlpha.length.toFloat() > 1.80f) {
            return false
        }
        val firstTok = spanTokensAlpha[0]
        if (firstTok.isEmpty()) return false
        val fk1 = englishPhoneticKey(firstTok.take(2))
        val fk2 = englishPhoneticKey(targetAlpha.take(2))
        if (firstTok[0] != targetAlpha[0] && (fk1.isEmpty() || fk2.isEmpty() || !onsetCompatible(fk1[0], fk2[0]))) {
            return false
        }

        var tIdx = 0
        for (tok in spanTokensAlpha) {
            var matchedInTok = 0
            for (ch in tok) {
                if (tIdx < targetAlpha.length && ch == targetAlpha[tIdx]) {
                    tIdx++
                    matchedInTok++
                }
            }
            val minReq = if (tok.length == 1) 1 else 2
            if (matchedInTok < minReq) return false
        }
        return tIdx == targetAlpha.length
    }

    internal fun tokenizeWithOffsets(text: String): List<TextToken> {
        val tokens = ArrayList<TextToken>()
        var i = 0
        val n = text.length
        var pendingBreak: Char? = null

        while (i < n) {
            val ch = text[i]
            if (ch.isWhitespace()) {
                i++
                continue
            }
            if (ch in PUNCT_BREAK_CHARS) {
                pendingBreak = ch
                i++
                continue
            }
            val start = i
            while (i < n && !text[i].isWhitespace()) {
                val c = text[i]
                if (c in PUNCT_BREAK_CHARS) {
                    if (c == '.' && i + 1 < n && text[i + 1].isLetterOrDigit()) {
                        i++
                        continue
                    }
                    break
                }
                i++
            }
            val raw = text.substring(start, i)
            if (raw.any { it.isLetterOrDigit() }) {
                tokens.add(
                    TextToken(
                        raw = raw,
                        start = start,
                        end = i,
                        lower = raw.lowercase(),
                        breakChar = pendingBreak
                    )
                )
                pendingBreak = null
            }
            if (i < n && text[i] in PUNCT_BREAK_CHARS) {
                pendingBreak = text[i]
                i++
            }
        }
        return tokens
    }

    private fun stripPossessive(tokens: List<TextToken>): Pair<List<String>, String> {
        val raws = tokens.mapTo(ArrayList(tokens.size)) { it.raw }
        var suffix = ""
        val last = raws.last()
        if (last.endsWith("'s") || last.endsWith("\u2019s")) {
            raws[raws.size - 1] = last.dropLast(2)
            suffix = last.takeLast(2)
        }
        return raws to suffix
    }

    private fun isMixedCaseOrAllCapsOrSymbol(word: String): Boolean {
        if (word.any { it in "./+-#0123456789" }) return true
        val hasUpper = word.any { it in 'A'..'Z' }
        val hasLower = word.any { it in 'a'..'z' }
        if (hasUpper && hasLower && word.drop(1).any { it in 'A'..'Z' }) return true
        if (hasUpper && !hasLower && word.length >= 2) return true
        return false
    }

    private fun prepareTargets(entries: List<DictionaryEntry>): List<PreparedTarget> {
        val list = ArrayList<PreparedTarget>(entries.size)
        for (entry in entries) {
            if (!isEligibleForEnglishVoice(entry)) continue
            val word = entry.word.trim()
            val formStrings = LinkedHashSet<String>()
            formStrings.add(word)
            val reading = entry.reading.trim()
            if (reading.isNotEmpty() &&
                !hasCjkOrKana(reading) &&
                !reading.equals(word, ignoreCase = true) &&
                reading.any { it in 'a'..'z' || it in 'A'..'Z' }
            ) {
                formStrings.add(reading)
            }
            val expanded = expandSpokenSymbols(word)
            if (expanded != word.lowercase()) {
                formStrings.add(expanded)
            }
            val forms = formStrings.map { fm ->
                val alnum = normalizeCompactAlnum(fm)
                val alpha = extractAsciiLowerLetters(fm)
                TargetForm(
                    compactAlnum = alnum,
                    compactAlpha = alpha,
                    phoneticKey = englishPhoneticKey(alpha)
                )
            }
            val ctxRegex = Regex(
                "(?<![A-Za-z0-9])${Regex.escape(word)}(?![A-Za-z0-9])",
                RegexOption.IGNORE_CASE
            )
            list.add(
                PreparedTarget(
                    entry = entry,
                    word = word,
                    compactAlnum = normalizeCompactAlnum(word),
                    forms = forms,
                    contextRegex = ctxRegex
                )
            )
        }
        return list
    }

    fun apply(
        text: String,
        contextBefore: String = "",
        entries: List<DictionaryEntry>,
        wordLogFreqProvider: ((String) -> Float?)? = null,
        slmCoherenceDeltaProvider: ((origSentence: String, corrSentence: String) -> Float?)? = null,
        suppressedPairs: Set<Pair<String, String>> = emptySet(),
        protectedRanges: List<IntRange> = emptyList()
    ): ResolutionResult {
        if (text.isBlank() || entries.isEmpty()) {
            return ResolutionResult(text, emptyList(), emptyList())
        }
        val targets = prepareTargets(entries)
        if (targets.isEmpty()) {
            return ResolutionResult(text, emptyList(), emptyList())
        }
        val tokens = tokenizeWithOffsets(text)
        if (tokens.isEmpty()) {
            return ResolutionResult(text, emptyList(), emptyList())
        }

        val candidates = ArrayList<CandidateMatch>()
        var slmEvalCount = 0
        val nTok = tokens.size

        for (startI in 0 until nTok) {
            for (spanLen in 1..min(MAX_SPAN_TOKENS, nTok - startI)) {
                val spanToks = tokens.subList(startI, startI + spanLen)
                if (spanLen > 1) {
                    val lastBreak = spanToks.last().breakChar
                    if (lastBreak != null && lastBreak != '.') break
                }

                val spanStart = spanToks.first().start
                val spanEnd = spanToks.last().end
                if (protectedRanges.any { !(spanEnd <= it.first || spanStart >= it.last + 1) }) {
                    continue
                }

                val hasDotBreak = spanLen > 1 && spanToks.drop(1).any { it.breakChar == '.' }
                val origSlice = text.substring(spanStart, spanEnd)

                val (strippedRaws, suffix) = stripPossessive(spanToks)
                val variants = ArrayList<Triple<List<String>, String, Boolean>>(2)
                variants.add(Triple(strippedRaws, suffix, false))
                val lastRaw = strippedRaws.last()
                if (suffix.isEmpty() && lastRaw.length >= 5 && (lastRaw.last() == 's' || lastRaw.last() == 'S')) {
                    val altRaws = ArrayList(strippedRaws)
                    altRaws[altRaws.size - 1] = lastRaw.dropLast(1)
                    val singleCommonFreq = if (spanLen == 1) {
                        wordLogFreqProvider?.invoke(strippedRaws[0].lowercase()) ?: 0.0f
                    } else {
                        0.0f
                    }
                    val strippedSuffix = if (spanLen == 1 && singleCommonFreq >= 3.0f) "" else "s"
                    variants.add(Triple(altRaws, strippedSuffix, true))
                }

                for ((candRaws, candSuffix, isPluralStrip) in variants) {
                    val candCardinalJoined = candRaws.joinToString("") { CARDINAL_WORDS[it.lowercase()] ?: it }
                    val candJoined = candRaws.joinToString("")
                    val spanAlnum = normalizeCompactAlnum(candJoined)
                    if (spanAlnum.isEmpty()) continue
                    val spanAlnumCard = normalizeCompactAlnum(candCardinalJoined)
                    val spanAlpha = extractAsciiLowerLetters(candJoined)
                    val spanAlphas = candRaws.map { extractAsciiLowerLetters(it) }

                    val isOovSpan = spanLen == 1 && (wordLogFreqProvider?.invoke(candRaws[0].lowercase()) == null)
                    val spanPhoneticKey = if (spanAlpha.isNotEmpty()) englishPhoneticKey(spanAlpha) else ""

                    for (target in targets) {
                        if (hasDotBreak && '.' !in target.word) continue
                        val proposed = target.word + candSuffix
                        if (origSlice == proposed) continue
                        val isShortTarget = target.compactAlnum.length <= 3

                        var bestRank = Int.MAX_VALUE
                        var bestKind = EnglishUserDictMatchKind.NONE
                        var bestPdist = 1.0f
                        var bestCdist = 1.0f

                        for (form in target.forms) {
                            val isShortForm = form.compactAlnum.length <= 3
                            if (spanAlnum == form.compactAlnum || spanAlnumCard == form.compactAlnum) {
                                if (isShortForm && spanLen > 1 && spanAlphas.any { it.length != 1 }) continue
                                if (isPluralStrip && target.compactAlnum.length <= 3) continue
                                if (0 < bestRank) {
                                    bestRank = 0
                                    bestKind = EnglishUserDictMatchKind.EXACT_NORMALIZED
                                    bestPdist = 0.0f
                                    bestCdist = 0.0f
                                }
                                continue
                            }

                            if (isShortTarget || (isPluralStrip && target.compactAlnum.length <= 4)) continue
                            if (spanAlpha.isEmpty() || form.compactAlpha.isEmpty()) continue

                            val minLen = min(spanAlpha.length, form.compactAlpha.length)
                            val maxLen = max(spanAlpha.length, form.compactAlpha.length)
                            val lenRatio = minLen.toFloat() / maxLen.toFloat()

                            if (lenRatio >= 0.65f && spanPhoneticKey.length >= 3 && form.phoneticKey.length >= 3) {
                                val pdist = weightedPhoneticDistance(spanPhoneticKey, form.phoneticKey)
                                val cdist = normalizedLevenshtein(spanAlpha, form.compactAlpha)
                                if (pdist == 0.0f && cdist <= 0.45f) {
                                    if (1 < bestRank || (1 == bestRank && pdist + cdist < bestPdist + bestCdist)) {
                                        bestRank = 1
                                        bestKind = EnglishUserDictMatchKind.PHONETIC_EXACT
                                        bestPdist = pdist
                                        bestCdist = cdist
                                    }
                                    continue
                                }
                                if (onsetCompatible(spanPhoneticKey[0], form.phoneticKey[0])) {
                                    val maxCdist = when {
                                        pdist <= 0.08f -> 0.68f
                                        isOovSpan && form.compactAlpha.length >= 6 && spanAlpha.length >= 6 && pdist <= 0.12f -> 0.58f
                                        pdist <= 0.10f -> 0.55f
                                        else -> 0.48f
                                    }
                                    val phoneticMatch = pdist <= 0.22f && cdist <= maxCdist
                                    val oovCharMatch = isOovSpan &&
                                        spanAlpha.length >= 6 &&
                                        form.compactAlpha.length >= 6 &&
                                        spanAlpha[0] == form.compactAlpha[0] &&
                                        cdist <= 0.18f
                                    if (phoneticMatch || oovCharMatch) {
                                        if (2 < bestRank || (2 == bestRank && pdist + cdist < bestPdist + bestCdist)) {
                                            bestRank = 2
                                            bestKind = EnglishUserDictMatchKind.PHONETIC_NEAR_MISS
                                            bestPdist = pdist
                                            bestCdist = cdist
                                        }
                                        continue
                                    }
                                }
                            }

                            if (spanLen >= 2 && alignedTokenSubsequenceMatch(spanAlphas, form.compactAlpha)) {
                                if (3 < bestRank) {
                                    bestRank = 3
                                    bestKind = EnglishUserDictMatchKind.ABBREVIATION_SUBSEQUENCE
                                    bestPdist = 0.10f
                                    bestCdist = 0.40f
                                }
                            }
                        }

                        if (bestKind == EnglishUserDictMatchKind.NONE) continue

                        var isCaseOnlyCommon = false
                        var isCommonCollision = false
                        if (bestKind == EnglishUserDictMatchKind.EXACT_NORMALIZED) {
                            if (spanLen == 1 && !isMixedCaseOrAllCapsOrSymbol(target.word)) {
                                val lf = wordLogFreqProvider?.invoke(spanToks[0].lower) ?: 0.0f
                                if (lf >= 4.5f) {
                                    isCaseOnlyCommon = true
                                    isCommonCollision = true
                                }
                            }
                        } else {
                            if (spanLen == 1) {
                                val lf = wordLogFreqProvider?.invoke(candRaws[0].lowercase()) ?: 0.0f
                                if (lf >= 3.0f) {
                                    isCommonCollision = true
                                }
                            } else {
                                val tokFreqs = candRaws.map { wordLogFreqProvider?.invoke(it.lowercase()) ?: 0.0f }
                                if (tokFreqs.all { it >= 3.5f } || (tokFreqs.any { it >= 8.5f } && bestPdist > 0.05f)) {
                                    isCommonCollision = true
                                }
                            }
                        }

                        if (isCommonCollision && bestKind != EnglishUserDictMatchKind.EXACT_NORMALIZED && bestPdist > 0.12f) {
                            continue
                        }

                        val isReverted = (origSlice.lowercase() to proposed) in suppressedPairs
                        val inContext = contextBefore.isNotEmpty() && target.contextRegex.containsMatchIn(contextBefore)
                        val isHighPriority = target.entry.priority == Priority.HIGH

                        val needsSlmCheck = isCommonCollision &&
                            !inContext &&
                            !isShortTarget &&
                            !(isReverted && bestKind != EnglishUserDictMatchKind.EXACT_NORMALIZED) &&
                            slmCoherenceDeltaProvider != null &&
                            slmEvalCount < MAX_SLM_EVALS_PER_UTTERANCE

                        var slmAvailable = false
                        var slmCoherencePassed = false
                        if (needsSlmCheck) {
                            val proposedSentence = buildString(text.length + 16) {
                                append(text, 0, spanStart)
                                append(proposed)
                                append(text, spanEnd, text.length)
                            }
                            val deltaNll = slmCoherenceDeltaProvider?.invoke(text, proposedSentence)
                            slmEvalCount++
                            if (deltaNll != null) {
                                slmAvailable = true
                                val theta = when {
                                    isCaseOnlyCommon -> EnglishUserDictDecisionModel.DEFAULT_SLM_THETA_CASE_ONLY
                                    target.entry.priority == Priority.HIGH -> EnglishUserDictDecisionModel.DEFAULT_SLM_THETA_HIGH
                                    target.entry.priority == Priority.LOW -> EnglishUserDictDecisionModel.DEFAULT_SLM_THETA_LOW
                                    else -> EnglishUserDictDecisionModel.DEFAULT_SLM_THETA_NORMAL
                                }
                                slmCoherencePassed = deltaNll <= theta
                            }
                        }

                        val action = EnglishUserDictDecisionModel.evaluateResolutionPolicy(
                            EnglishUserDictDecisionModel.EnglishVoiceUserDictResolutionInput(
                                isEnabledEntry = target.entry.isEnabled,
                                matchKind = bestKind,
                                isAlreadyExactSurface = origSlice == proposed,
                                isShortTarget = isShortTarget,
                                isCommonWordCollision = isCommonCollision,
                                isCaseOnlyCommonWord = isCaseOnlyCommon,
                                isRevertedPair = isReverted,
                                inPrecedingContext = inContext,
                                slmAvailable = slmAvailable,
                                slmCoherencePassed = slmCoherencePassed,
                                isHighPriority = isHighPriority
                            )
                        ).result

                        if (action == EnglishUserDictAction.AUTO_REPLACE || action == EnglishUserDictAction.SUGGEST_CHIP) {
                            candidates.add(
                                CandidateMatch(
                                    start = spanStart,
                                    end = spanEnd,
                                    origSlice = origSlice,
                                    proposed = proposed,
                                    action = action,
                                    matchKindRank = bestRank,
                                    combinedDist = bestPdist + bestCdist,
                                    priorityScore = target.entry.priority.score,
                                    spanLen = spanLen
                                )
                            )
                        }
                    }
                }
            }
        }

        if (candidates.isEmpty()) {
            return ResolutionResult(text, emptyList(), emptyList())
        }

        candidates.sort()
        val chosenAuto = ArrayList<CandidateMatch>()
        val chosenChips = ArrayList<CandidateMatch>()
        val occupied = ArrayList<Pair<Int, Int>>()

        for (cand in candidates) {
            val overlaps = occupied.any { !(cand.end <= it.first || cand.start >= it.second) }
            if (overlaps) continue
            occupied.add(cand.start to cand.end)
            if (cand.action == EnglishUserDictAction.AUTO_REPLACE) {
                chosenAuto.add(cand)
            } else {
                chosenChips.add(cand)
            }
        }

        chosenAuto.sortBy { it.start }
        val sb = StringBuilder(text.length + 16)
        var cursor = 0
        for (cand in chosenAuto) {
            sb.append(text, cursor, cand.start)
            sb.append(cand.proposed)
            cursor = cand.end
        }
        sb.append(text, cursor, text.length)

        val appliedList = chosenAuto.map {
            VoiceCorrectionSuggestion(
                originalSurface = it.origSlice,
                correctedSurface = it.proposed,
                action = BoostAction.AUTO_REPLACE_AND_HOTWORD,
                score = it.priorityScore
            )
        }
        val chipList = chosenChips.map {
            VoiceCorrectionSuggestion(
                originalSurface = it.origSlice,
                correctedSurface = it.proposed,
                action = BoostAction.SUGGEST_CHIP_AND_MILD_BOOST,
                score = it.priorityScore
            )
        }
        return ResolutionResult(
            formattedText = sb.toString(),
            appliedCorrections = appliedList,
            suggestedChips = chipList
        )
    }
}
