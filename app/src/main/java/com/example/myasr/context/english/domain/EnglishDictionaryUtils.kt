package com.example.myasr.context.english.domain

import com.example.myasr.context.kanakanji.domain.ConversionCandidate
import com.example.myasr.context.vocabulary.domain.DictionaryEntry
import com.example.myasr.context.vocabulary.domain.Priority

import kotlin.math.min
import kotlin.math.sqrt

/**
 * Utility for English dictionary validation, normalization, and in-memory
 * spatial typo/misspelling correction against custom user dictionary entries.
 */
object EnglishDictionaryUtils {

    // Valid English word/token pattern: Starts with a letter, followed by letters, digits, apostrophes, hyphens
    private val ENGLISH_WORD_REGEX = Regex("^[A-Za-z][A-Za-z0-9'\\-]*$")

    // QWERTY key coordinates for physical key distance calculations
    private data class KeyCoord(val x: Float, val y: Float)

    private val QWERTY_KEY_MAP: Map<Char, KeyCoord> by lazy {
        val map = mutableMapOf<Char, KeyCoord>()
        val rows = arrayOf(
            "qwertyuiop",
            "asdfghjkl",
            "zxcvbnm"
        )
        for (r in rows.indices) {
            val offset = when (r) {
                0 -> 0.0f
                1 -> 0.5f
                else -> 1.0f
            }
            val rowStr = rows[r]
            for (c in rowStr.indices) {
                map[rowStr[c]] = KeyCoord(c + offset, r.toFloat())
            }
        }
        map
    }

    /**
     * Checks if the given text is a valid English word or token.
     */
    fun isValidEnglishWord(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.length > 64) return false
        // Support words and short phrases (space-separated English words)
        val tokens = trimmed.split(Regex("\\s+"))
        return tokens.all { ENGLISH_WORD_REGEX.matches(it) }
    }

    /**
     * Normalizes English reading to lower-case representation for dictionary keys.
     */
    fun normalizeEnglishReading(text: String): String {
        return text.trim().lowercase()
    }

    /**
     * Converts an English word into clean a-z key for FUTO Swipe Trie.
     */
    fun toSwipeTrieKey(text: String): String {
        return text.trim().lowercase()
            .replace("'", "")
            .replace("-", "")
            .filter { it in 'a'..'z' }
    }

    private fun keyDistance(c1: Char, c2: Char): Float {
        val l1 = c1.lowercaseChar()
        val l2 = c2.lowercaseChar()
        if (l1 == l2) return 0.0f
        val k1 = QWERTY_KEY_MAP[l1]
        val k2 = QWERTY_KEY_MAP[l2]
        if (k1 != null && k2 != null) {
            val dx = k1.x - k2.x
            val dy = k1.y - k2.y
            return sqrt(dx * dx + dy * dy)
        }
        return 3.0f
    }

    internal const val SPATIAL_BOUNDARY_GAP_COST = 1.5f
    internal const val SPATIAL_INDEL_COST = 1.2f
    internal const val SPATIAL_SUB_BASE_COST = 0.8f
    internal const val SPATIAL_SUB_DIST_SLOPE = 0.3f
    internal const val SPATIAL_SUB_MAX_DIST = 2.0f
    internal const val SPATIAL_TRANSPOSITION_COST = 0.6f

    /**
     * Calculates QWERTY spatial edit distance with Damerau-Levenshtein transposition.
     */
    fun editAndSpatialCost(typed: String, cand: String): Float {
        val m = typed.length
        val n = cand.length
        val dp = Array(m + 1) { FloatArray(n + 1) }

        for (i in 0..m) dp[i][0] = i * SPATIAL_BOUNDARY_GAP_COST
        for (j in 0..n) dp[0][j] = j * SPATIAL_BOUNDARY_GAP_COST

        for (i in 1..m) {
            for (j in 1..n) {
                val kd = keyDistance(typed[i - 1], cand[j - 1])
                val subCost = if (kd == 0.0f) 0.0f else (SPATIAL_SUB_BASE_COST + SPATIAL_SUB_DIST_SLOPE * min(kd, SPATIAL_SUB_MAX_DIST))

                var minCost = dp[i - 1][j] + SPATIAL_INDEL_COST // deletion
                minCost = min(minCost, dp[i][j - 1] + SPATIAL_INDEL_COST) // insertion
                minCost = min(minCost, dp[i - 1][j - 1] + subCost) // substitution

                // Transposition
                if (i > 1 && j > 1 && typed[i - 1] == cand[j - 2] && typed[i - 2] == cand[j - 1]) {
                    minCost = min(minCost, dp[i - 2][j - 2] + SPATIAL_TRANSPOSITION_COST)
                }

                dp[i][j] = minCost
            }
        }
        return dp[m][n]
    }

    private data class ScoredCandidate(
        val surface: String,
        val reading: String,
        val score: Float,
        val cost: Float,
        val normCost: Float = 0.0f
    )

    /**
     * Suggests correction candidates from user dictionary for a typed raw word.
     * Guaranteed fast execution (< 0.2ms for typical user dictionaries).
     */
    fun suggestFromUserDictionary(
        rawWord: String,
        entries: List<DictionaryEntry>,
        maxCandidates: Int = 4
    ): List<ConversionCandidate> {
        val trimmed = rawWord.trim()
        if (trimmed.isEmpty()) return emptyList()

        val typedLower = trimmed.lowercase()
        val englishEntries = entries.filter { it.isEnabled && it.hasEnglishSurface }
        if (englishEntries.isEmpty()) return emptyList()

        val scored = mutableListOf<ScoredCandidate>()
        val seenSurfaces = mutableSetOf<String>()

        for (entry in englishEntries) {
            val surface = entry.word.trim()
            val candLower = surface.lowercase()
            if (candLower.isEmpty() || !seenSurfaces.add(candLower)) continue

            val baseVirtualScore = when (entry.priority) {
                Priority.HIGH -> 22.0f
                Priority.NORMAL -> 19.5f
                Priority.LOW -> 17.5f
            }

            // 1. Exact case-insensitive match (e.g. user typed "reazonspeech", dict has "ReazonSpeech")
            if (typedLower == candLower) {
                scored.add(ScoredCandidate(surface, entry.reading, baseVirtualScore, 0.0f, 0.0f))
                continue
            }

            // 2. Exact prefix match (e.g. user typed "reazon", dict has "ReazonSpeech")
            if (typedLower.length >= 3 && candLower.startsWith(typedLower)) {
                val lenDiff = candLower.length - typedLower.length
                val prefixCost = 0.4f + 0.1f * lenDiff
                val score = baseVirtualScore - 2.8f * prefixCost
                val normCost = 0.05f * lenDiff.toFloat() / candLower.length.toFloat()
                scored.add(ScoredCandidate(surface, entry.reading, score, prefixCost, normCost))
                continue
            }

            // 3. Spatial edit distance match for typos / misspellings
            // Principled Relative Observation Error: d(X, W) / max(|X|, |W|) <= 0.38
            val maxLen = maxOf(typedLower.length, candLower.length)
            val cost = editAndSpatialCost(typedLower, candLower)
            val normCost = if (maxLen > 0) cost / maxLen.toFloat() else cost

            if (normCost <= 0.38f && cost <= 3.6f) {
                val score = baseVirtualScore - 2.8f * cost
                scored.add(ScoredCandidate(surface, entry.reading, score, cost, normCost))
            } else if (typedLower.length >= 4 && candLower.length > typedLower.length) {
                // 4. Fuzzy prefix match (e.g. user typed "reazno", dict has "ReazonSpeech")
                val prefixSub = candLower.substring(0, typedLower.length)
                val fuzzyPrefixCost = editAndSpatialCost(typedLower, prefixSub)
                val fuzzyNormCost = fuzzyPrefixCost / typedLower.length.toFloat()
                if (fuzzyNormCost <= 0.30f && fuzzyPrefixCost <= 1.2f) {
                    val score = baseVirtualScore - 2.8f * (fuzzyPrefixCost + 0.8f)
                    scored.add(ScoredCandidate(surface, entry.reading, score, fuzzyPrefixCost + 0.8f, fuzzyNormCost + 0.1f))
                }
            }
        }

        scored.sortWith(compareByDescending<ScoredCandidate> { it.score }.thenBy { it.cost })

        return scored.take(maxCandidates).map {
            ConversionCandidate(
                surface = it.surface,
                reading = rawWord,
                isPrediction = false,
                source = "user_english",
                priority = it.score,
                obsPenalty = it.normCost
            )
        }
    }
}
