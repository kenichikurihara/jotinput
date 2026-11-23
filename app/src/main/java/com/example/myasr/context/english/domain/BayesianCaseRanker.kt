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
    private const val OOV_RAW_WORD_ENERGY_PENALTY = 2.2f
    private const val KNOWN_WORD_EXTENSION_ENERGY_PENALTY = 0.45f
    private const val KNOWN_WORD_TYPO_ENERGY_PENALTY = 0.40f
    private const val SHORTER_WORD_TYPO_PENALTY = 0.18f

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
        isRawWord: Boolean = false,
        suppressOovAcronym: Boolean = false
    ): List<RankedCaseCandidate> {
        if (baseWord.isEmpty()) return emptyList()

        val lower = baseWord.lowercase()
        val lexEntry = EnglishCaseModel.lookupLexicon(lower)
        val unigramLogFreq = EnglishCaseModel.getWordLogFreq(lower) ?: 0.5f
        val oovAcroProb = if (suppressOovAcronym) 0.0f else EnglishCaseModel.oovAcronymProbability(lower)
        val isOovUnknown = lexEntry == null &&
            !EnglishCaseModel.isKnownLowerWord(lower) &&
            oovAcroProb < 0.50f
        val oovPenalty = if (isRawWord && isOovUnknown) OOV_RAW_WORD_ENERGY_PENALTY else 0.0f

        if (policy == InputFieldCapPolicy.BYPASS) {
            return listOf(
                RankedCaseCandidate(
                    surface = baseWord,
                    baseWord = baseWord,
                    source = source,
                    posteriorProb = 1.0f,
                    energy = LAMBDA_OBS * obsPenalty - LAMBDA_FREQ * unigramLogFreq + oovPenalty,
                    obsPenalty = obsPenalty,
                    isPrediction = isPrediction,
                    priority = priority
                )
            )
        }

        // Collect (surface -> lexical prior P_lex(s | w))
        val variantPriors = LinkedHashMap<String, Float>(4)

        if (lexEntry != null) {
            for (v in lexEntry.variants) {
                variantPriors[v.surface] = maxOf(variantPriors[v.surface] ?: 0.0f, v.prob)
            }
        } else {
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
                LAMBDA_FREQ * unigramLogFreq +
                oovPenalty
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

    /**
     * Adapts casing of ranked neural swipe candidates from [FutoSwipeEngine] while strictly
     * preserving swipe Rank 1 ordering so rare OOV/technical words (e.g. `Tailscale`, `JotInput`,
     * `Anthropic`) are never overtaken by higher-frequency Rank 2 words (e.g. `table`) or stripped
     * of mixed casing.
     */
    fun buildSwipeCandidates(
        swipeCandidates: List<String>,
        bosProb: Float,
        fieldPolicy: InputFieldCapPolicy,
        isShiftActive: Boolean,
        maxCandidates: Int = 5
    ): List<ConversionCandidate> {
        if (swipeCandidates.isEmpty() || maxCandidates <= 0) return emptyList()
        val reading = swipeCandidates[0]

        if (fieldPolicy == InputFieldCapPolicy.BYPASS) {
            val bypassOut = ArrayList<ConversionCandidate>(minOf(swipeCandidates.size, maxCandidates))
            val seen = HashSet<String>()
            for ((idx, cand) in swipeCandidates.withIndex()) {
                if (seen.add(cand)) {
                    bypassOut.add(
                        ConversionCandidate(
                            surface = cand,
                            reading = reading,
                            isPrediction = false,
                            source = "futo_swipe",
                            obsPenalty = idx * 0.08f
                        )
                    )
                    if (bypassOut.size >= maxCandidates) break
                }
            }
            return bypassOut
        }

        val primaries = ArrayList<ConversionCandidate>(swipeCandidates.size)
        var topSecondary: ConversionCandidate? = null

        for ((idx, cand) in swipeCandidates.withIndex()) {
            if (cand.isEmpty()) continue
            val penalty = idx * 0.08f
            val lower = cand.lowercase()
            val lexEntry = EnglishCaseModel.lookupLexicon(lower)
            val hasExplicitUpper = cand.any { it.isUpperCase() }
            val unknownMixedOrUserSurface = hasExplicitUpper &&
                (lexEntry == null || !lexEntry.variants.any { it.surface == cand })

            if (fieldPolicy == InputFieldCapPolicy.CAP_CHARACTERS && lower.length >= 2) {
                primaries.add(
                    ConversionCandidate(
                        surface = cand.uppercase(),
                        reading = reading,
                        isPrediction = false,
                        source = "futo_swipe",
                        obsPenalty = penalty
                    )
                )
            } else if (unknownMixedOrUserSurface) {
                val shaped = if ((isShiftActive || bosProb >= 0.5f || fieldPolicy == InputFieldCapPolicy.CAP_WORDS) &&
                    cand[0].isLowerCase() &&
                    classifyShape(cand) == CaseShape.LOWER
                ) {
                    cand.replaceFirstChar { it.uppercase() }
                } else {
                    cand
                }
                primaries.add(
                    ConversionCandidate(
                        surface = shaped,
                        reading = reading,
                        isPrediction = false,
                        source = "futo_swipe",
                        obsPenalty = penalty
                    )
                )
            } else {
                val variants = generateCaseVariants(
                    baseWord = cand,
                    bosProb = bosProb,
                    policy = fieldPolicy,
                    source = "futo_swipe",
                    obsPenalty = penalty,
                    isRawWord = false
                )
                val topVariant = variants.firstOrNull()
                val rawPrimary = topVariant?.surface ?: cand
                val adaptedPrimary = if (isShiftActive &&
                    rawPrimary.isNotEmpty() &&
                    rawPrimary[0].isLowerCase() &&
                    classifyShape(rawPrimary) == CaseShape.LOWER
                ) {
                    rawPrimary.replaceFirstChar { it.uppercase() }
                } else {
                    rawPrimary
                }
                primaries.add(
                    ConversionCandidate(
                        surface = adaptedPrimary,
                        reading = reading,
                        isPrediction = false,
                        source = "futo_swipe",
                        obsPenalty = penalty
                    )
                )
                if (idx == 0 && topSecondary == null) {
                    val alt = variants.firstOrNull {
                        it.surface != adaptedPrimary && it.posteriorProb >= 0.25f
                    }
                    if (alt != null) {
                        topSecondary = ConversionCandidate(
                            surface = alt.surface,
                            reading = reading,
                            isPrediction = false,
                            source = "futo_swipe",
                            obsPenalty = penalty
                        )
                    }
                }
            }
        }

        val out = ArrayList<ConversionCandidate>(maxCandidates)
        val seenSurfaces = HashSet<String>()
        if (primaries.isNotEmpty() && seenSurfaces.add(primaries[0].surface)) {
            out.add(primaries[0])
        }
        if (topSecondary != null && out.size < maxCandidates && seenSurfaces.add(topSecondary.surface)) {
            out.add(topSecondary)
        }
        for (i in 1 until primaries.size) {
            if (out.size >= maxCandidates) break
            val item = primaries[i]
            if (seenSurfaces.add(item.surface)) {
                out.add(item)
            }
        }
        return out
    }

    /**
     * Builds and ranks English candidate list combining user dictionary entries,
     * Bayesian truecasing variants of [rawWord], and prefix/typo suggestions.
     */
    fun buildEnglishCandidates(
        rawWord: String,
        bosProb: Float,
        fieldPolicy: InputFieldCapPolicy,
        isShiftActive: Boolean,
        userCandidates: List<ConversionCandidate>,
        lexiconSuggestions: List<String>
    ): List<ConversionCandidate> {
        if (rawWord.isEmpty()) return emptyList()

        val rawLower = rawWord.lowercase()
        val isExplicitUpperOrShift = isShiftActive || rawWord.first().isUpperCase()
        val isAllCapsTyped = rawWord.length >= 2 && rawWord.all { !it.isLetter() || it.isUpperCase() }
        val isSentenceStart = !isExplicitUpperOrShift && bosProb >= 0.5f
        val candidateList = mutableListOf<ConversionCandidate>()
        // Exact-surface deduplication preserves distinct case variants (e.g. "US" vs "us", "May" vs "may")
        val seenSurfaces = mutableSetOf<String>()

        // 1. User dictionary entries (exact case / normalized typo / prefix)
        for (uCand in userCandidates) {
            val surface = uCand.surface
            val adapted = if (isSentenceStart && surface.all { it.isLowerCase() || !it.isLetter() }) {
                surface.replaceFirstChar { it.uppercase() }
            } else {
                surface
            }
            if (seenSurfaces.add(adapted)) {
                candidateList.add(
                    ConversionCandidate(
                        surface = adapted,
                        reading = rawWord,
                        isPrediction = false,
                        source = "user_english",
                        priority = uCand.priority,
                        obsPenalty = uCand.obsPenalty
                    )
                )
            }
        }

        // 2. DMN policy: protect known exact words vs promote OOV prefix completions
        val hasPrefixCompletions = lexiconSuggestions.any {
            val sLow = it.lowercase()
            sLow.startsWith(rawLower) && sLow.length > rawLower.length
        }
        val isIncompletePrefixOfKnownWord = !EnglishCaseModel.isKnownWord(rawLower) &&
            hasPrefixCompletions &&
            rawLower.length >= 5
        val isEffectiveOovAcronym = !isIncompletePrefixOfKnownWord &&
            EnglishCaseModel.oovAcronymProbability(rawLower) >= 0.50f
        val prefixPolicy = EnglishAssistJudgement.judgePrefixCompletionPolicy(
            rawWordLength = rawWord.length,
            isKnownExactWord = EnglishCaseModel.isKnownWord(rawLower),
            isOovAcronym = isEffectiveOovAcronym,
            hasPrefixCompletions = hasPrefixCompletions
        ).decision

        // 3. Bayesian truecasing variants for rawWord + typo/completion suggestions
        val rankedPool = mutableListOf<RankedCaseCandidate>()
        if (!isExplicitUpperOrShift && fieldPolicy != InputFieldCapPolicy.BYPASS) {
            rankedPool.addAll(
                generateCaseVariants(
                    baseWord = rawWord,
                    bosProb = bosProb,
                    policy = fieldPolicy,
                    source = "raw",
                    obsPenalty = 0.0f,
                    isRawWord = true,
                    suppressOovAcronym = isIncompletePrefixOfKnownWord
                )
            )
        } else {
            val isRawOovUnknown = !EnglishCaseModel.isKnownWord(rawLower) &&
                !isEffectiveOovAcronym
            val rawUnigramFreq = EnglishCaseModel.getWordLogFreq(rawLower) ?: 0.5f
            val rawOovPenalty = if (isRawOovUnknown) OOV_RAW_WORD_ENERGY_PENALTY else 0.0f
            rankedPool.add(
                RankedCaseCandidate(
                    surface = rawWord,
                    baseWord = rawWord,
                    source = "raw",
                    posteriorProb = 1.0f,
                    energy = rawOovPenalty - LAMBDA_FREQ * rawUnigramFreq,
                    obsPenalty = 0.0f,
                    isPrediction = false
                )
            )
        }

        for (sug in lexiconSuggestions) {
            val sugLower = sug.lowercase()
            val isPrefix = sugLower.startsWith(rawLower)
            val isPrediction = isPrefix && sug.length > rawWord.length
            val obsPenalty = if (isPrefix) {
                0.025f * (sug.length - rawWord.length).coerceAtMost(12)
            } else {
                val cost = EnglishDictionaryUtils.editAndSpatialCost(rawLower, sugLower)
                val maxLen = maxOf(rawWord.length, sug.length)
                val baseObs = if (maxLen > 0) cost / maxLen.toFloat() else 0.0f
                val shorterPenalty = if (hasPrefixCompletions && sug.length < rawWord.length) {
                    SHORTER_WORD_TYPO_PENALTY
                } else {
                    0.0f
                }
                baseObs + shorterPenalty
            }

            val knownProtectionOffset = if (
                prefixPolicy == EnglishAssistJudgement.PrefixCompletionStrategy.PROTECT_EXACT_WORD &&
                sugLower != rawLower
            ) {
                when {
                    isPrediction -> KNOWN_WORD_EXTENSION_ENERGY_PENALTY
                    !isPrefix -> KNOWN_WORD_TYPO_ENERGY_PENALTY
                    else -> 0.0f
                }
            } else {
                0.0f
            }

            if (!isExplicitUpperOrShift && fieldPolicy != InputFieldCapPolicy.BYPASS) {
                val variants = generateCaseVariants(
                    baseWord = sug,
                    bosProb = bosProb,
                    policy = fieldPolicy,
                    source = "english_typo",
                    obsPenalty = obsPenalty,
                    isPrediction = isPrediction
                )
                if (knownProtectionOffset > 0.0f) {
                    rankedPool.addAll(variants.map { it.copy(energy = it.energy + knownProtectionOffset) })
                } else {
                    rankedPool.addAll(variants)
                }
            } else {
                val adaptedSurface = when {
                    fieldPolicy == InputFieldCapPolicy.BYPASS -> sug
                    isAllCapsTyped -> sug.uppercase()
                    sug.all { !it.isLetter() || it.isLowerCase() } -> sug.replaceFirstChar { it.uppercase() }
                    else -> sug
                }
                val sugLogFreq = EnglishCaseModel.getWordLogFreq(sugLower) ?: 0.5f
                rankedPool.add(
                    RankedCaseCandidate(
                        surface = adaptedSurface,
                        baseWord = sug,
                        source = "english_typo",
                        posteriorProb = 1.0f,
                        energy = LAMBDA_OBS * obsPenalty - LAMBDA_FREQ * sugLogFreq + knownProtectionOffset,
                        obsPenalty = obsPenalty,
                        isPrediction = isPrediction
                    )
                )
            }
        }

        for (item in rankedPool.sortedBy { it.energy }) {
            if (seenSurfaces.add(item.surface)) {
                candidateList.add(
                    ConversionCandidate(
                        surface = item.surface,
                        reading = rawWord,
                        isPrediction = item.isPrediction,
                        source = item.source,
                        obsPenalty = item.obsPenalty
                    )
                )
            }
        }

        // 4. Always ensure exact raw typed word is present as an escape hatch
        if (seenSurfaces.add(rawWord)) {
            candidateList.add(
                ConversionCandidate(
                    surface = rawWord,
                    reading = rawWord,
                    isPrediction = false,
                    source = "raw",
                    obsPenalty = 0.0f
                )
            )
        }

        return candidateList
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
