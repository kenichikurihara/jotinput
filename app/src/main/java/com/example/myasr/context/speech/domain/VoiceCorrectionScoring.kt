package com.example.myasr.context.speech.domain

import kotlin.math.exp
import kotlin.math.ln

/**
 * Pure domain scorer for applying learned voice correction entries (S2 + S3 + S5)
 * across both Japanese and English ASR outputs.
 */
object VoiceCorrectionScoring {

    private val AMBIGUOUS_JP_HOMOPHONES = setOf(
        "貴社", "記者", "帰社", "汽車",
        "移動", "異動",
        "決裁", "決済",
        "科学", "化学",
        "回答", "解答",
        "市立", "私立",
        "保障", "保証", "補償",
        "構成", "校正", "厚生",
        "制作", "製作",
        "対象", "対照", "対称",
        "移行", "意向"
    )

    private val AMBIGUOUS_EN_HOMOPHONES = setOf(
        "their", "there", "they're",
        "write", "right",
        "principle", "principal",
        "complement", "compliment",
        "affect", "effect",
        "accept", "except",
        "whether", "weather",
        "peace", "piece",
        "break", "brake"
    )

    fun isAmbiguousCommonHomophone(entry: VoiceCorrectionEntry): Boolean {
        return when (entry.language) {
            AsrLanguage.JAPANESE ->
                entry.originalSurface in AMBIGUOUS_JP_HOMOPHONES ||
                    entry.correctedSurface in AMBIGUOUS_JP_HOMOPHONES
            AsrLanguage.ENGLISH ->
                entry.originalSurface.lowercase() in AMBIGUOUS_EN_HOMOPHONES ||
                    entry.correctedSurface.lowercase() in AMBIGUOUS_EN_HOMOPHONES
        }
    }

    /**
     * S3: Sigmoid context coherence gate g(deltaL) in [0, 1].
     * deltaL = NLL(corrected | context) - NLL(original | context).
     * When deltaL >> thetaGate (SLM strongly rejects corrected form in this context), g -> 0.
     */
    fun computeCoherenceGate(
        deltaNll: Float,
        thetaGate: Float = VoiceCorrectionDecisionModel.DEFAULT_SLM_GATE_THETA,
        temp: Float = VoiceCorrectionDecisionModel.DEFAULT_SLM_GATE_TEMP
    ): Float {
        val exponent = ((deltaNll - thetaGate) / temp).toDouble().coerceIn(-30.0, 30.0)
        return (1.0 / (1.0 + exp(exponent))).toFloat()
    }

    /**
     * S2: Computes personal confusion-pair + context token overlap score.
     */
    fun computeConfusionScore(
        entry: VoiceCorrectionEntry,
        contextBefore: String,
        nowMs: Long = System.currentTimeMillis()
    ): Float {
        val net = entry.netCount()
        if (net <= 0) return -100f

        val elapsedDays = ((nowMs - entry.lastCorrectedAtMs).coerceAtLeast(0L)) / (1000.0 * 60.0 * 60.0 * 24.0)
        val recencyDecay = exp(-elapsedDays / 60.0).toFloat()
        val freqScore = 1.5f * ln(1.0 + net.toDouble()).toFloat() * recencyDecay

        val ctxLower = contextBefore.lowercase()
        val tokenMatches = entry.recentContextTokens.count { tok ->
            tok.isNotBlank() && ctxLower.contains(tok.lowercase())
        }
        val ctxBonus = tokenMatches * 0.8f
        val exactInCtxBonus = if (contextBefore.contains(entry.correctedSurface, ignoreCase = entry.language == AsrLanguage.ENGLISH)) {
            2.0f
        } else {
            0.0f
        }
        return freqScore + ctxBonus + exactInCtxBonus
    }

    /**
     * Applies learned voice corrections to [text] using S2 (Confusion-Pair + Context),
     * S3 (optional SLM coherence scorer), and S5 (3-tier BoostPolicy DMN).
     *
     * @param slmCoherenceDeltaProvider Optional callback returning (NLL(corrSentence) - NLL(origSentence)) in nats.
     *                                  If null (e.g. SLM not loaded), ambiguous homophones fall back to chip suggestion
     *                                  unless explicitly disambiguated by strong context cues.
     */
    fun applyCorrections(
        text: String,
        contextBefore: String,
        entries: List<VoiceCorrectionEntry>,
        language: AsrLanguage,
        nowMs: Long = System.currentTimeMillis(),
        slmCoherenceDeltaProvider: ((origSentence: String, corrSentence: String) -> Float?)? = null
    ): VoiceCorrectionApplyResult {
        if (text.isBlank() || entries.isEmpty()) {
            return VoiceCorrectionApplyResult(text, emptyList(), emptyList())
        }

        val activeEntries = entries
            .filter { it.language == language && it.netCount() > 0 && it.originalSurface.isNotBlank() }
            .sortedWith(
                compareByDescending<VoiceCorrectionEntry> { it.originalSurface.length }
                    .thenByDescending { computeConfusionScore(it, contextBefore, nowMs) }
            )

        if (activeEntries.isEmpty()) {
            return VoiceCorrectionApplyResult(text, emptyList(), emptyList())
        }

        var result = text
        val applied = mutableListOf<VoiceCorrectionSuggestion>()
        val chips = mutableListOf<VoiceCorrectionSuggestion>()
        val handledOriginals = mutableSetOf<String>()

        for (entry in activeEntries) {
            val orig = entry.originalSurface
            val corr = entry.correctedSurface
            if (orig == corr) continue

            val origKey = if (language == AsrLanguage.ENGLISH) orig.lowercase() else orig
            if (origKey in handledOriginals) continue

            val matchRegex = if (language == AsrLanguage.ENGLISH) {
                Regex("\\b${Regex.escape(orig)}\\b", RegexOption.IGNORE_CASE)
            } else {
                Regex(Regex.escape(orig))
            }

            if (!matchRegex.containsMatchIn(result)) continue

            val isAmbiguous = isAmbiguousCommonHomophone(entry)
            val isOrthographicOrCase = orig.lowercase().replace(Regex("[\\s\\-']+"), "") ==
                corr.lowercase().replace(Regex("[\\s\\-']+"), "")

            val category = when {
                isOrthographicOrCase -> CorrectionCategory.VALID_ORTHOGRAPHIC_OR_CASE
                isAmbiguous -> CorrectionCategory.VALID_EXACT_HOMOPHONE
                else -> CorrectionCategory.VALID_PHONETIC_NEAR_MISS
            }

            val candidateText = matchRegex.replaceFirst(result, Regex.escapeReplacement(corr))
            var slmCoherencePassed = true

            if (isAmbiguous) {
                val deltaNll = slmCoherenceDeltaProvider?.invoke(result, candidateText)
                if (deltaNll != null) {
                    val gate = computeCoherenceGate(deltaNll)
                    val boost = gate * VoiceCorrectionDecisionModel.DEFAULT_BETA_VOICE *
                        ln(1.0 + entry.netCount().toDouble()).toFloat().coerceAtMost(3.0f)
                    // Only pass coherence if net energy (deltaNll - boost) favors corrected form
                    slmCoherencePassed = (deltaNll - boost) < 0.0f && gate >= 0.35f
                } else {
                    // Without SLM scoring, check strong context cue match; otherwise stay safe (gate off for tier 1)
                    val ctxScore = computeConfusionScore(entry, contextBefore, nowMs)
                    slmCoherencePassed = ctxScore >= 2.2f
                }
            }

            val action = VoiceCorrectionDecisionModel.evaluateBoostPolicy(
                VoiceCorrectionDecisionModel.BoostPolicyInput(
                    netCount = entry.netCount(),
                    category = category,
                    isAmbiguousCommonHomophone = isAmbiguous,
                    slmCoherencePassed = slmCoherencePassed
                )
            ).result

            val score = computeConfusionScore(entry, contextBefore, nowMs)
            val suggestion = VoiceCorrectionSuggestion(
                originalSurface = orig,
                correctedSurface = corr,
                action = action,
                score = score
            )

            when (action) {
                BoostAction.AUTO_REPLACE_AND_HOTWORD -> {
                    result = candidateText
                    applied.add(suggestion)
                    handledOriginals.add(origKey)
                }
                BoostAction.SUGGEST_CHIP_AND_MILD_BOOST,
                BoostAction.GATE_OFF_KEEP_ORIGINAL_WITH_CHIP -> {
                    chips.add(suggestion)
                    handledOriginals.add(origKey)
                }
                BoostAction.SUPPRESSED_NO_BOOST -> Unit
            }
        }

        return VoiceCorrectionApplyResult(
            formattedText = result,
            appliedCorrections = applied,
            suggestedChips = chips
        )
    }
}
