package com.example.myasr.context.english.domain

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import kotlin.math.exp

/**
 * Corpus-derived statistical truecasing, OOV acronym character bigram LM,
 * and Punkt-style abbreviation EOS probability model.
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

    private const val CHAR_ALPHABET = "^abcdefghijklmnopqrstuvwxyz$"
    private const val ALPHABET_SIZE = 28 // '^', 'a'..'z', '$'
    private const val OOV_ACRO_THRESHOLD_LLR = 0.28f

    @Volatile
    private var loaded = false

    private val lock = Any()
    private val lexicon = HashMap<String, LexiconEntry>(24576)
    private val lowerVocab = HashSet<String>(36000)
    private val abbrevEos = HashMap<String, Float>(128)
    // Flattened 28x28 character bigram LLR table: log P_acro(b|a) - log P_word(b|a)
    private val charBigramLlr = FloatArray(ALPHABET_SIZE * ALPHABET_SIZE)

    fun ensureLoaded() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            loadLexicon()
            loadLowerVocab()
            loadAbbrevEos()
            loadCharBigramLm()
            loaded = true
        }
    }

    fun lookupLexicon(lowerWord: String): LexiconEntry? {
        ensureLoaded()
        return lexicon[lowerWord]
    }

    fun isKnownLowerWord(lowerWord: String): Boolean {
        ensureLoaded()
        return lowerVocab.contains(lowerWord)
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
        if (lookupLexicon(lowerWord) != null) return 0.0f
        val llr = oovAcronymLogLikelihoodRatio(lowerWord)
        val z = (llr - OOV_ACRO_THRESHOLD_LLR).coerceIn(-15.0f, 15.0f)
        return (1.0 / (1.0 + exp(-z.toDouble()))).toFloat()
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
                val w = line.trim()
                if (w.isNotEmpty()) {
                    lowerVocab.add(w)
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

    private fun charIndex(c: Char): Int {
        return when (c) {
            '^' -> 0
            '$' -> 27
            in 'a'..'z' -> (c - 'a') + 1
            else -> -1
        }
    }
}
