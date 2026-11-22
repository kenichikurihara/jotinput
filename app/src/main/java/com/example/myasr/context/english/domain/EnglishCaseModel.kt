package com.example.myasr.context.english.domain

import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import kotlin.math.abs
import kotlin.math.exp

/**
 * Corpus-derived statistical truecasing, OOV acronym character bigram LM,
 * Punkt-style abbreviation EOS probability model, and fast prefix/typo lexicon.
 *
 * Loaded lazily from classpath resources (`english/english_case_lexicon.tsv`, etc.),
 * with zero hardcoded word lists or anecdotal cost overrides.
 */
object EnglishCaseModel {

    data class CaseVariant(
        val surface: String,
        val prob: Float
    )

    data class LexiconEntry(
        val logFreq: Float,
        val variants: List<CaseVariant>
    )

    private const val ALPHABET_SIZE = 28 // '^', 'a'..'z', '$'
    private const val OOV_ACRO_THRESHOLD_LLR = 0.28f
    private const val TYPO_INDEX_MIN_LOG_FREQ = 2.1f

    @Volatile
    private var loaded = false

    private val lock = Any()
    private val lexicon = HashMap<String, LexiconEntry>(40960)
    private val lowerVocabFreq = HashMap<String, Float>(81920)
    private val abbrevEos = HashMap<String, Float>(128)
    // Flattened 28x28 character bigram LLR table: log P_acro(b|a) - log P_word(b|a)
    private val charBigramLlr = FloatArray(ALPHABET_SIZE * ALPHABET_SIZE)

    private var sortedKeys: Array<String> = emptyArray()
    private var sortedKeyLengths: IntArray = IntArray(0)
    private var sortedSurfaces: Array<String> = emptyArray()
    private var sortedLogFreqs: FloatArray = FloatArray(0)
    private var sortedCharMasks: IntArray = IntArray(0)
    private var typoBuckets: HashMap<String, IntArray> = HashMap()

    fun ensureLoaded() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            loadLexicon()
            loadLowerVocab()
            loadAbbrevEos()
            loadCharBigramLm()
            buildSearchIndex()
            loaded = true
        }
    }

    fun lookupLexicon(lowerWord: String): LexiconEntry? {
        ensureLoaded()
        return lexicon[lowerWord]
    }

    fun isKnownLowerWord(lowerWord: String): Boolean {
        ensureLoaded()
        return lowerVocabFreq.containsKey(lowerWord)
    }

    fun isKnownWord(lowerWord: String): Boolean {
        ensureLoaded()
        return lexicon.containsKey(lowerWord) || lowerVocabFreq.containsKey(lowerWord)
    }

    fun getWordLogFreq(lowerWord: String): Float? {
        ensureLoaded()
        return lexicon[lowerWord]?.logFreq ?: lowerVocabFreq[lowerWord]
    }

    fun abbreviationEosProbability(stemLower: String): Float? {
        ensureLoaded()
        return abbrevEos[stemLower]
    }

    /**
     * Computes the character bigram log-likelihood ratio:
     *   LLR(w) = sum_i [ log P_acro(w_i | w_{i-1}) - log P_word(w_i | w_{i-1}) ]
     * Positive values indicate the character sequence follows acronym/initialism
     * phonotactics rather than standard English word phonotactics.
     */
    fun oovAcronymLogLikelihoodRatio(lowerWord: String): Float {
        ensureLoaded()
        if (lowerWord.length < 2 || lowerWord.length > 6) return -10.0f
        for (ch in lowerWord) {
            if (ch !in 'a'..'z') return -10.0f
        }
        var llr = 0.0f
        var prevIdx = 0 // '^'
        for (ch in lowerWord) {
            val curIdx = (ch - 'a') + 1
            llr += charBigramLlr[prevIdx * ALPHABET_SIZE + curIdx]
            prevIdx = curIdx
        }
        val endIdx = 27 // '$'
        llr += charBigramLlr[prevIdx * ALPHABET_SIZE + endIdx]
        return llr
    }

    /**
     * Logistic posterior probability P_OOV(UPPER | w) for an out-of-vocabulary
     * alphabetic token of length 2..6.
     */
    fun oovAcronymProbability(lowerWord: String): Float {
        if (lowerWord.length < 2 || lowerWord.length > 6) return 0.0f
        if (isKnownLowerWord(lowerWord)) return 0.0f
        val llr = oovAcronymLogLikelihoodRatio(lowerWord)
        val z = (llr - OOV_ACRO_THRESHOLD_LLR).coerceIn(-15.0f, 15.0f)
        return (1.0 / (1.0 + exp(-z.toDouble()))).toFloat()
    }

    /**
     * Fast in-domain English prefix completion and spatial typo suggestion (< 0.3ms).
     * Works 100% out-of-the-box without requiring the 27.5 MB external GEC ONNX model.
     */
    fun suggestPrefixAndTypo(rawWord: String, maxCandidates: Int = 6): List<String> {
        ensureLoaded()
        val typedLower = rawWord.trim().lowercase()
        val m = typedLower.length
        if (m < 2 || sortedKeys.isEmpty() || maxCandidates <= 0) return emptyList()

        val bestByLower = HashMap<String, Pair<String, Float>>(32)

        fun record(candLower: String, surface: String, score: Float) {
            val existing = bestByLower[candLower]
            if (existing == null || score > existing.second) {
                bestByLower[candLower] = surface to score
            }
        }

        // 1. Exact prefix completion via binary search on sorted unified vocabulary
        val bs = sortedKeys.binarySearch(typedLower)
        val startIdx = if (bs >= 0) bs else (-bs - 1)
        var i = startIdx
        var exactPrefixCount = 0
        while (i < sortedKeys.size) {
            val k = sortedKeys[i]
            if (!k.startsWith(typedLower)) break
            val n = k.length
            if (n == m) {
                record(k, sortedSurfaces[i], sortedLogFreqs[i] + 1.0f)
            } else if (n <= m + 16) {
                val lenDiff = (n - m).toFloat()
                val covRatio = m.toFloat() / n.toFloat()
                val score = sortedLogFreqs[i] - 0.45f * lenDiff + 2.0f * covRatio
                record(k, sortedSurfaces[i], score)
                exactPrefixCount++
            }
            i++
        }

        // 2. Exact match / apostrophe contraction alias match (e.g., dont -> don't, im -> I'm)
        lexicon[typedLower]?.let { entry ->
            val topSurf = entry.variants.first().surface
            record(topSurf.lowercase(), topSurf, entry.logFreq + 1.0f)
        }
        lowerVocabFreq[typedLower]?.let { lf ->
            record(typedLower, typedLower, lf + 1.0f)
        }

        // 3. Spatial typo & fuzzy prefix match via 2-char bucket index
        if (m >= 3 && !(exactPrefixCount >= 1 && m >= 5 && m <= 24)) {
            val topScores = FloatArray(maxCandidates) { Float.NEGATIVE_INFINITY }
            var minTopScore = Float.NEGATIVE_INFINITY

            fun updateTopScores(score: Float) {
                if (score <= minTopScore) return
                var minIdx = 0
                var minVal = topScores[0]
                for (j in 1 until maxCandidates) {
                    if (topScores[j] < minVal) {
                        minVal = topScores[j]
                        minIdx = j
                    }
                }
                if (score > minVal) {
                    topScores[minIdx] = score
                    var nextMin = topScores[0]
                    for (j in 1 until maxCandidates) {
                        if (topScores[j] < nextMin) nextMin = topScores[j]
                    }
                    minTopScore = nextMin
                }
            }

            for (pair in bestByLower.values) {
                updateTopScores(pair.second)
            }

            val b0 = typedLower.substring(0, 2)
            val bSwap = "${typedLower[1]}${typedLower[0]}"
            val bSkip1 = "${typedLower[0]}${typedLower[2]}"
            val bTail = typedLower.substring(1, 3)

            val bucketKeys = ArrayList<String>(4)
            bucketKeys.add(b0)
            if (bSwap != b0) bucketKeys.add(bSwap)
            if (bSkip1 != b0 && bSkip1 != bSwap) bucketKeys.add(bSkip1)
            if (bTail != b0 && bTail != bSwap && bTail != bSkip1) bucketKeys.add(bTail)

            val typedMask = computeCharMask(typedLower)
            val bufLen = maxOf(m + 4, 36)
            val dpBuf0 = FloatArray(bufLen)
            val dpBuf1 = FloatArray(bufLen)
            val dpBuf2 = FloatArray(bufLen)
            // Admissible length/bitmask bounds: 2 indels cost >= 2.4f, requiring maxLen >= 7 for normCost <= 0.38f.
            val maxLenDiff3a = if (m >= 5) 2 else 1
            val maxMaskDiff3a = if (m >= 6) 4 else 2

            for (bk in bucketKeys) {
                val bucket = typoBuckets[bk] ?: continue
                val allowFuzzyPrefix = (bk == b0 || bk == bSwap)
                for (candIdx in bucket) {
                    val candLogFreq = sortedLogFreqs[candIdx]
                    // Bucket is sorted by logFreq descending; any non-identical edit costs >= 0.6f (2.8 * 0.6 = 1.68).
                    if (candLogFreq - 1.68f < minTopScore) break

                    val n = sortedKeyLengths[candIdx]
                    if (n + 1 > bufLen) continue

                    // 3a. Full-word spatial edit distance typo
                    val lenDiff = abs(n - m)
                    if (lenDiff <= maxLenDiff3a) {
                        val candMask = sortedCharMasks[candIdx]
                        val xorMask = typedMask xor candMask
                        val missing = Integer.bitCount(typedMask and xorMask)
                        val extra = Integer.bitCount(candMask and xorMask)
                        val unmatched = maxOf(missing, extra)
                        val minCost = maxOf(
                            when (lenDiff) {
                                0 -> 0.6f
                                1 -> 1.2f
                                else -> 2.4f
                            },
                            unmatched * 1.1f
                        )
                        val maxCost = if (n >= 7) 3.6f else 3.0f
                        val maxLen = maxOf(m, n)
                        if (minCost > maxCost + 1e-4f || minCost > 0.38f * maxLen.toFloat() + 1e-4f) continue
                        if (candLogFreq - 2.8f * minCost < minTopScore) continue

                        if (missing + extra <= maxMaskDiff3a) {
                            val candKey = sortedKeys[candIdx]
                            if (candKey != typedLower && !bestByLower.containsKey(candKey)) {
                                val cost = EnglishDictionaryUtils.editAndSpatialCostWithBuffers(
                                    typedLower, candKey, n, dpBuf0, dpBuf1, dpBuf2
                                )
                                val normCost = if (maxLen > 0) cost / maxLen.toFloat() else cost
                                if (normCost <= 0.38f && cost <= maxCost) {
                                    val score = candLogFreq - 2.8f * cost
                                    if (score >= minTopScore) {
                                        record(candKey, sortedSurfaces[candIdx], score)
                                        updateTopScores(score)
                                    }
                                }
                            }
                        }
                    } else if (allowFuzzyPrefix && m >= 4 && n > m && n <= m + 10) {
                        // 3b. Fuzzy prefix match for 1-char typo while typing a longer word
                        val maxFuzzyScore = candLogFreq - 3.36f - 0.35f * (n - m).toFloat()
                        if (maxFuzzyScore < minTopScore) continue
                        // Full word char mask is a superset of any prefix mask; prune before touching String heap.
                        if (Integer.bitCount(typedMask and sortedCharMasks[candIdx].inv()) <= 1) {
                            val candKey = sortedKeys[candIdx]
                            if (!candKey.startsWith(typedLower) && !bestByLower.containsKey(candKey)) {
                                val prefixMask = computeCharMask(candKey, minOf(n, m + 1))
                                if (Integer.bitCount(typedMask and prefixMask.inv()) <= 1) {
                                    val fuzzyCost = EnglishDictionaryUtils.editAndSpatialCostWithBuffers(
                                        typedLower, candKey, m, dpBuf0, dpBuf1, dpBuf2
                                    )
                                    val fuzzyNorm = fuzzyCost / m.toFloat()
                                    if (fuzzyNorm <= 0.30f && fuzzyCost <= 1.2f) {
                                        val score = candLogFreq - 2.8f * (fuzzyCost + 0.6f) - 0.35f * (n - m).toFloat()
                                        if (score >= minTopScore) {
                                            record(candKey, sortedSurfaces[candIdx], score)
                                            updateTopScores(score)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        return bestByLower.values
            .sortedByDescending { it.second }
            .take(maxCandidates)
            .map { it.first }
    }

    /**
     * Exports the unified frequency-sorted vocabulary to external storage so the native
     * C++ EnglishSpellEngine SymSpell/prefix engine stays synchronized with the enriched lexicon.
     */
    fun exportVocabularyIfNeeded(targetFile: File) {
        ensureLoaded()
        if (sortedKeys.isEmpty()) return
        if (targetFile.exists() && targetFile.length() > 100_000L) {
            val existingLines = try {
                targetFile.bufferedReader(Charsets.UTF_8).use { reader ->
                    var count = 0
                    while (reader.readLine() != null) {
                        count++
                        if (count >= sortedKeys.size) break
                    }
                    count
                }
            } catch (_: Exception) {
                0
            }
            if (existingLines >= sortedKeys.size) return
        }
        try {
            targetFile.parentFile?.mkdirs()
            val indices = sortedKeys.indices.sortedByDescending { sortedLogFreqs[it] }
            targetFile.bufferedWriter(Charsets.UTF_8).use { writer ->
                for (idx in indices) {
                    writer.write(sortedSurfaces[idx])
                    writer.newLine()
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun openResource(path: String): InputStream? {
        return EnglishCaseModel::class.java.classLoader?.getResourceAsStream(path)
            ?: Thread.currentThread().contextClassLoader?.getResourceAsStream(path)
    }

    private fun loadLexicon() {
        val stream = openResource("english/english_case_lexicon.tsv") ?: return
        BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).useLines { lines ->
            for (line in lines) {
                if (line.isEmpty()) continue
                val parts = line.split('\t')
                if (parts.size < 3) continue
                val key = parts[0]
                val logFreq = parts[1].toFloatOrNull() ?: 1.0f
                val varParts = parts[2].split(',')
                val variants = ArrayList<CaseVariant>(varParts.size)
                for (vp in varParts) {
                    val colon = vp.lastIndexOf(':')
                    if (colon <= 0 || colon >= vp.length - 1) continue
                    val surf = vp.substring(0, colon)
                    val prob = vp.substring(colon + 1).toFloatOrNull() ?: continue
                    variants.add(CaseVariant(surf, prob))
                }
                if (variants.isNotEmpty()) {
                    lexicon[key] = LexiconEntry(logFreq, variants)
                }
            }
        }
    }

    private fun loadLowerVocab() {
        val stream = openResource("english/english_lower_vocab.txt") ?: return
        BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).useLines { lines ->
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                val tab = trimmed.indexOf('\t')
                if (tab > 0) {
                    val w = trimmed.substring(0, tab)
                    val lf = trimmed.substring(tab + 1).toFloatOrNull() ?: 2.0f
                    if (w.isNotEmpty()) {
                        lowerVocabFreq[w] = lf
                    }
                } else {
                    lowerVocabFreq[trimmed] = 2.0f
                }
            }
        }
    }

    private fun loadAbbrevEos() {
        val stream = openResource("english/english_abbrev_eos.tsv") ?: return
        BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).useLines { lines ->
            for (line in lines) {
                if (line.isEmpty()) continue
                val parts = line.split('\t')
                if (parts.size < 2) continue
                val stem = parts[0]
                val pEos = parts[1].toFloatOrNull() ?: continue
                abbrevEos[stem] = pEos
            }
        }
    }

    private fun loadCharBigramLm() {
        val stream = openResource("english/english_acro_char_lm.tsv") ?: return
        BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).useLines { lines ->
            for (line in lines) {
                if (line.isEmpty()) continue
                val parts = line.split('\t')
                if (parts.size < 2 || parts[0].length != 2) continue
                val a = charIndex(parts[0][0])
                val b = charIndex(parts[0][1])
                val llr = parts[1].toFloatOrNull() ?: continue
                if (a >= 0 && b >= 0) {
                    charBigramLlr[a * ALPHABET_SIZE + b] = llr
                }
            }
        }
    }

    private fun buildSearchIndex() {
        val unified = HashMap<String, Pair<String, Float>>(lexicon.size + lowerVocabFreq.size)
        for ((key, entry) in lexicon) {
            if (key.length < 2 && key != "a" && key != "i") continue
            val topSurface = entry.variants.first().surface
            // Skip apostrophe-stripped contraction alias keys (e.g. dont -> don't); keep actual words
            if (key != topSurface.lowercase()) continue
            unified[key] = topSurface to entry.logFreq
        }
        for ((key, lf) in lowerVocabFreq) {
            if (key.length < 2 && key != "a" && key != "i") continue
            val existing = unified[key]
            if (existing == null) {
                unified[key] = key to lf
            } else if (lf > existing.second) {
                unified[key] = existing.first to lf
            }
        }

        val entries = unified.entries.sortedBy { it.key }
        val count = entries.size
        val keys = Array(count) { "" }
        val keyLengths = IntArray(count)
        val surfaces = Array(count) { "" }
        val logFreqs = FloatArray(count)
        val charMasks = IntArray(count)
        val bucketsTmp = HashMap<String, ArrayList<Int>>(2048)

        for ((idx, entry) in entries.withIndex()) {
            val k = entry.key
            val lf = entry.value.second
            keys[idx] = k
            keyLengths[idx] = k.length
            surfaces[idx] = entry.value.first
            logFreqs[idx] = lf
            charMasks[idx] = computeCharMask(k)

            if (k.length >= 2 && lf >= TYPO_INDEX_MIN_LOG_FREQ) {
                val b0 = k.substring(0, 2)
                bucketsTmp.getOrPut(b0) { ArrayList() }.add(idx)
                if (k.length >= 3) {
                    val b1 = k.substring(1, 3)
                    if (b1 != b0) {
                        bucketsTmp.getOrPut(b1) { ArrayList() }.add(idx)
                    }
                }
            }
        }

        val finalizedBuckets = HashMap<String, IntArray>(bucketsTmp.size * 2)
        for ((bk, list) in bucketsTmp) {
            list.sortWith { a, b -> logFreqs[b].compareTo(logFreqs[a]) }
            finalizedBuckets[bk] = list.toIntArray()
        }

        sortedKeys = keys
        sortedKeyLengths = keyLengths
        sortedSurfaces = surfaces
        sortedLogFreqs = logFreqs
        sortedCharMasks = charMasks
        typoBuckets = finalizedBuckets
    }

    private fun computeCharMask(word: String, limit: Int = word.length): Int {
        var mask = 0
        val end = minOf(limit, word.length)
        for (i in 0 until end) {
            val ch = word[i]
            if (ch in 'a'..'z') {
                mask = mask or (1 shl (ch - 'a'))
            }
        }
        return mask
    }

    private fun charIndex(c: Char): Int {
        return when (c) {
            '^' -> 0
            '$' -> 27
            in 'a'..'z' -> (c - 'a') + 1
            else -> -1
        }
    }
}
