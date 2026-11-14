package com.example.myasr.context.english.domain

import com.example.myasr.context.kanakanji.domain.ConversionCandidate
import kotlin.math.ln

/**
 * Unified Bayesian Truecasing & Case-Lattice Ranker for English QWERTY & Swipe input.
 *
 * Computes posterior probabilities over case realizations s in S(w):
 *   P_case(s | w, ctx) = P_BOS(ctx) * T_BOS(shape(s)) * P_lex(s | w)
 *                      + (1 - P_BOS(ctx)) * T_notBOS(shape(s)) * P_lex(s | w)
 *
 * Combines:
 * 1. Corpus-derived lexical case prior P_lex(s | w) (including contractions & mixed case)
 * 2. OOV character bigram acronym likelihood P_OOV(UPPER | w)
 * 3. Punkt-style sentence boundary probability P_BOS(ctx)
 * 4. Input field capitalization policy (CAP_CHARACTERS, CAP_WORDS, CAP_SENTENCES)
 * 5. Observation/spatial edit cost d_obs(x, w) & unigram log-frequency
 */
object BayesianCaseRanker {

    enum class CaseShape {
        LOWER,
        TITLE,
        UPPER,
        SPECIAL,       // e.g., I'm, Let's, iOS, iPhone, YouTube
        SPECIAL_LOWER  // e.g., let's, don't, can't, it's (lowercase-initial contraction)
    }

    data class RankedCaseCandidate(
        val surface: String,
        val baseWord: String,
        val source: String,
        val posteriorProb: Float,
        val energy: Float,
        val obsPenalty: Float,
        val isPrediction: Boolean,
        val priority: Float = 0.0f
    )

    private const val LAMBDA_OBS = 5.0f
    private const val LAMBDA_FREQ = 0.15f
    private const val MIN_VARIANT_POSTERIOR = 0.04f

    fun classifyShape(surface: String): CaseShape {
        val letters = surface.filter { it.isLetter() }
        if (letters.isEmpty()) return CaseShape.LOWER
        if (surface.contains('\'')) {
            return if (letters[0].isLowerCase() && letters.all { it.isLowerCase() }) {
                CaseShape.SPECIAL_LOWER
            } else {
                CaseShape.SPECIAL
            }
        }
        if (letters.all { it.isLowerCase() }) return CaseShape.LOWER
        if (letters.length >= 2 && letters.all { it.isUpperCase() }) return CaseShape.UPPER
        if (letters[0].isUpperCase() && letters.drop(1).all { it.isLowerCase() }) return CaseShape.TITLE
        return CaseShape.SPECIAL
    }

    /**
     * Generates and scores truecased surface variants for a single base word [baseWord].
     */
    fun generateCaseVariants(
        baseWord: String,
        bosProb: Float,
        policy: InputFieldCapPolicy,
        source: String,
        obsPenalty: Float = 0.0f,
        isPrediction: Boolean = false,
        priority: Float = 0.0f,
        isRawWord: Boolean = false
    ): List<RankedCaseCandidate> {
        if (baseWord.isEmpty() || policy == InputFieldCapPolicy.BYPASS) {
            return if (baseWord.isEmpty()) emptyList() else listOf(
                RankedCaseCandidate(
                    surface = baseWord,
                    baseWord = baseWord,
                    source = source,
                    posteriorProb = 1.0f,
                    energy = LAMBDA_OBS * obsPenalty,
                    obsPenalty = obsPenalty,
                    isPrediction = isPrediction,
                    priority = priority
                )
            )
        }

        val lower = baseWord.lowercase()
        val lexEntry = EnglishCaseModel.lookupLexicon(lower)
        val unigramLogFreq = lexEntry?.logFreq ?: if (EnglishCaseModel.isKnownLowerWord(lower)) 2.0f else 0.5f

        // Collect (surface -> lexical prior P_lex(s | w))
        val variantPriors = LinkedHashMap<String, Float>(4)

        if (lexEntry != null) {
            for (v in lexEntry.variants) {
                variantPriors[v.surface] = maxOf(variantPriors[v.surface] ?: 0.0f, v.prob)
            }
        } else {
            val oovAcroProb = EnglishCaseModel.oovAcronymProbability(lower)
            if (oovAcroProb >= 0.50f) {
                variantPriors[lower.uppercase()] = oovAcroProb
                variantPriors[lower] = 1.0f - oovAcroProb
            } else {
                variantPriors[lower] = 0.95f
            }
        }

        // Ensure TitleCase, capitalized contractions, and UPPERCASE candidates are available when policy or BOS requires them
        val titleSurface = lower.replaceFirstChar { it.uppercase() }
        val upperSurface = lower.uppercase()

        if (bosProb >= 0.5f || policy == InputFieldCapPolicy.CAP_WORDS) {
            // Collapse lowercase-initial contraction mass (e.g., "let's" + "Let's") onto its BOS-capitalized form
            val lowerContractions = variantPriors.entries
                .filter { classifyShape(it.key) == CaseShape.SPECIAL_LOWER }
                .map { it.key to it.value }
            for ((lowContr, lowProb) in lowerContractions) {
                val capContr = lowContr.replaceFirstChar { it.uppercase() }
                variantPriors[capContr] = maxOf(variantPriors[capContr] ?: 0.0f, (variantPriors[capContr] ?: 0.0f) + lowProb)
            }
            if (!variantPriors.containsKey(titleSurface)) {
                val upperPrior = variantPriors[upperSurface] ?: 0.0f
                val contractionPrior = variantPriors.entries
                    .filter { classifyShape(it.key) == CaseShape.SPECIAL }
                    .maxOfOrNull { it.value } ?: 0.0f
                variantPriors[titleSurface] = when {
                    upperPrior >= 0.70f -> 0.08f
                    contractionPrior >= 0.40f -> 0.15f
                    else -> 0.85f
                }
            }
        }
        if (policy == InputFieldCapPolicy.CAP_CHARACTERS && lower.length >= 2) {
            variantPriors[upperSurface] = maxOf(variantPriors[upperSurface] ?: 0.0f, 0.95f)
        }
        if (isRawWord && !variantPriors.containsKey(baseWord)) {
            variantPriors[baseWord] = 0.05f
        }

        // Compute posterior P_case(s | w, ctx)
        val effectiveBos = when (policy) {
            InputFieldCapPolicy.CAP_WORDS -> 0.95f
            InputFieldCapPolicy.CAP_CHARACTERS -> 0.0f
            else -> bosProb
        }

        val strongUpperPrior = variantPriors.entries
            .filter { classifyShape(it.key) == CaseShape.UPPER }
            .maxOfOrNull { it.value } ?: 0.0f
        val strongSpecialPrior = variantPriors.entries
            .filter { classifyShape(it.key) == CaseShape.SPECIAL }
            .maxOfOrNull { it.value } ?: 0.0f

        val rawScores = ArrayList<Pair<String, Float>>(variantPriors.size)
        var sumProb = 0.0f

        for ((surf, pLex) in variantPriors) {
            val shape = classifyShape(surf)
            val pAtBos = bosShapeProb(shape, pLex, strongUpperPrior, strongSpecialPrior, policy)
            val pNotBos = notBosPrior(shape, pLex, policy)
            val pCase = effectiveBos * pAtBos + (1.0f - effectiveBos) * pNotBos
            if (pCase > 0.0f) {
                rawScores.add(surf to pCase)
                sumProb += pCase
            }
        }

        if (sumProb <= 0.0f) {
            return listOf(
                RankedCaseCandidate(
                    surface = baseWord,
                    baseWord = baseWord,
                    source = source,
                    posteriorProb = 1.0f,
                    energy = LAMBDA_OBS * obsPenalty,
                    obsPenalty = obsPenalty,
                    isPrediction = isPrediction,
                    priority = priority
                )
            )
        }

        val results = ArrayList<RankedCaseCandidate>(rawScores.size)
        for ((surf, unnorm) in rawScores) {
            val post = unnorm / sumProb
            if (post < MIN_VARIANT_POSTERIOR && !(isRawWord && surf == baseWord)) {
                continue
            }
            val effectiveSource = if (source == "raw" && surf != baseWord) "english_cap" else source
            val energy = -ln(post.coerceAtLeast(1e-6f)) +
                LAMBDA_OBS * obsPenalty -
                LAMBDA_FREQ * unigramLogFreq
            results.add(
                RankedCaseCandidate(
                    surface = surf,
                    baseWord = baseWord,
                    source = effectiveSource,
                    posteriorProb = post,
                    energy = energy,
                    obsPenalty = obsPenalty,
                    isPrediction = isPrediction,
                    priority = priority
                )
            )
        }

        results.sortBy { it.energy }
        return results
    }

    /**
     * Converts ranked case candidates to Shared/KanaKanji [ConversionCandidate] list,
     * preserving exact-surface uniqueness (so "US" and "us" both survive) and
     * ordering by posterior energy.
     */
    fun toConversionCandidates(
        ranked: List<RankedCaseCandidate>,
        reading: String,
        maxCandidates: Int = 6
    ): List<ConversionCandidate> {
        val out = ArrayList<ConversionCandidate>(maxCandidates)
        val seenExactSurfaces = HashSet<String>()
        for (item in ranked.sortedBy { it.energy }) {
            if (seenExactSurfaces.add(item.surface)) {
                out.add(
                    ConversionCandidate(
                        surface = item.surface,
                        reading = reading,
                        isPrediction = item.isPrediction,
                        source = item.source,
                        priority = item.priority,
                        obsPenalty = item.obsPenalty
                    )
                )
                if (out.size >= maxCandidates) break
            }
        }
        return out
    }

    private fun bosShapeProb(
        shape: CaseShape,
        pLex: Float,
        strongUpperPrior: Float,
        strongSpecialPrior: Float,
        policy: InputFieldCapPolicy
    ): Float {
        if (policy == InputFieldCapPolicy.CAP_CHARACTERS) {
            return if (shape == CaseShape.UPPER) 0.95f else 0.02f
        }
        return when (shape) {
            CaseShape.UPPER -> if (strongUpperPrior >= 0.40f) pLex else 0.02f
            CaseShape.SPECIAL -> if (strongSpecialPrior >= 0.40f) pLex else pLex.coerceAtLeast(0.50f)
            CaseShape.TITLE -> when {
                strongUpperPrior >= 0.40f -> 0.08f
                strongSpecialPrior >= 0.40f -> pLex.coerceAtMost(0.45f)
                else -> 0.92f
            }
            CaseShape.SPECIAL_LOWER,
            CaseShape.LOWER -> 0.02f
        }
    }

    private fun notBosPrior(
        shape: CaseShape,
        pLex: Float,
        policy: InputFieldCapPolicy
    ): Float {
        if (policy == InputFieldCapPolicy.CAP_CHARACTERS) {
            return if (shape == CaseShape.UPPER) 0.95f else 0.02f
        }
        return pLex
    }
}
