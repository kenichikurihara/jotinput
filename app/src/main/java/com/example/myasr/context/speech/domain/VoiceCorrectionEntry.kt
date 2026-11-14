package com.example.myasr.context.speech.domain

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.exp
import kotlin.math.ln

enum class CorrectionCategory {
    IGNORE_OUTSIDE_WINDOW,
    IGNORE_INVALID_SPAN,
    VALID_ORTHOGRAPHIC_OR_CASE,
    VALID_EXACT_HOMOPHONE,
    VALID_PHONETIC_NEAR_MISS,
    REJECT_SEMANTIC_REWRITE
}

enum class BoostAction {
    SUPPRESSED_NO_BOOST,
    SUGGEST_CHIP_AND_MILD_BOOST,
    AUTO_REPLACE_AND_HOTWORD,
    GATE_OFF_KEEP_ORIGINAL_WITH_CHIP
}

data class VoiceCorrectionEntry(
    val id: String = UUID.randomUUID().toString(),
    val language: AsrLanguage,
    val originalSurface: String,
    val correctedSurface: String,
    val normalizedReading: String,
    val correctionCount: Int = 1,
    val acceptCount: Int = 0,
    val revertCount: Int = 0,
    val firstCorrectedAtMs: Long = System.currentTimeMillis(),
    val lastCorrectedAtMs: Long = System.currentTimeMillis(),
    val recentContextTokens: List<String> = emptyList()
) {
    /**
     * Net confidence count penalizing user reverts (negative feedback self-healing).
     */
    fun netCount(): Int = (correctionCount + acceptCount - 2 * revertCount).coerceAtLeast(0)

    /**
     * Computes dynamic in-beam hotword boost score in [2.0f, 4.5f] with 60-day exponential decay.
     * Returns 0f if suppressed (netCount <= 0).
     */
    fun computeHotwordScore(nowMs: Long = System.currentTimeMillis()): Float {
        val net = netCount()
        if (net <= 0) return 0f
        val elapsedDays = ((nowMs - lastCorrectedAtMs).coerceAtLeast(0L)) / (1000.0 * 60.0 * 60.0 * 24.0)
        val decay = exp(-elapsedDays / 60.0)
        val raw = 2.0 + 1.2 * ln(1.0 + net.toDouble()) * decay
        return raw.toFloat().coerceIn(2.0f, 4.5f)
    }

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("language", language.name)
            put("originalSurface", originalSurface)
            put("correctedSurface", correctedSurface)
            put("normalizedReading", normalizedReading)
            put("correctionCount", correctionCount)
            put("acceptCount", acceptCount)
            put("revertCount", revertCount)
            put("firstCorrectedAtMs", firstCorrectedAtMs)
            put("lastCorrectedAtMs", lastCorrectedAtMs)
            put("recentContextTokens", JSONArray(recentContextTokens))
        }
    }

    companion object {
        fun fromJson(json: JSONObject): VoiceCorrectionEntry {
            val langName = json.optString("language", AsrLanguage.JAPANESE.name)
            val lang = AsrLanguage.entries.find { it.name == langName } ?: AsrLanguage.JAPANESE
            val tokensJson = json.optJSONArray("recentContextTokens")
            val tokens = mutableListOf<String>()
            if (tokensJson != null) {
                for (i in 0 until tokensJson.length()) {
                    val tok = tokensJson.optString(i)
                    if (tok.isNotBlank()) tokens.add(tok)
                }
            }
            return VoiceCorrectionEntry(
                id = json.optString("id", UUID.randomUUID().toString()),
                language = lang,
                originalSurface = json.getString("originalSurface"),
                correctedSurface = json.getString("correctedSurface"),
                normalizedReading = json.optString("normalizedReading", ""),
                correctionCount = json.optInt("correctionCount", 1),
                acceptCount = json.optInt("acceptCount", 0),
                revertCount = json.optInt("revertCount", 0),
                firstCorrectedAtMs = json.optLong("firstCorrectedAtMs", 0L),
                lastCorrectedAtMs = json.optLong("lastCorrectedAtMs", 0L),
                recentContextTokens = tokens
            )
        }
    }
}

data class VoiceCorrectionSuggestion(
    val originalSurface: String,
    val correctedSurface: String,
    val action: BoostAction,
    val score: Float
)

data class VoiceCorrectionApplyResult(
    val formattedText: String,
    val appliedCorrections: List<VoiceCorrectionSuggestion>,
    val suggestedChips: List<VoiceCorrectionSuggestion>
)
