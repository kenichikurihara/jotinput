package com.example.myasr.context.speech.infrastructure

import android.content.Context
import android.util.Log
import com.example.myasr.context.speech.domain.AsrLanguage
import com.example.myasr.context.speech.domain.VoiceCorrectionEntry
import com.example.myasr.shared.domain.InputLanguage
import com.example.myasr.shared.event.DomainEventBus
import com.example.myasr.shared.event.VoiceCorrectionLearnedEvent
import org.json.JSONArray
import java.io.File

/**
 * Infrastructure repository persisting learned voice corrections in
 * `context.filesDir/voice_correction_history.json` (isolated from `custom_dictionary.json`).
 */
class VoiceCorrectionRepository(
    private val context: Context,
    private val eventBus: DomainEventBus = DomainEventBus.global
) {
    private val TAG = "VoiceCorrectionRepo"
    private val FILE_NAME = "voice_correction_history.json"
    private val MAX_ENTRIES = 500
    private val lock = Any()
    private val entries = mutableListOf<VoiceCorrectionEntry>()

    init {
        loadFromDisk()
    }

    private fun getFile(): File = File(context.filesDir, FILE_NAME)

    private fun loadFromDisk() {
        synchronized(lock) {
            entries.clear()
            val file = getFile()
            if (!file.exists() || file.length() == 0L) return
            try {
                val jsonStr = file.readText(Charsets.UTF_8)
                val arr = JSONArray(jsonStr)
                for (i in 0 until arr.length()) {
                    entries.add(VoiceCorrectionEntry.fromJson(arr.getJSONObject(i)))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load voice correction history: ${e.message}")
            }
        }
    }

    private fun saveToDiskLocked() {
        try {
            val arr = JSONArray()
            for (e in entries) {
                arr.put(e.toJson())
            }
            getFile().writeText(arr.toString(), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save voice correction history: ${e.message}")
        }
    }

    fun getAll(language: AsrLanguage? = null): List<VoiceCorrectionEntry> {
        synchronized(lock) {
            return if (language == null) {
                entries.toList()
            } else {
                entries.filter { it.language == language }
            }
        }
    }

    fun getActiveBoostEntries(language: AsrLanguage): List<VoiceCorrectionEntry> {
        synchronized(lock) {
            return entries.filter { it.language == language && it.netCount() > 0 }
        }
    }

    /**
     * Returns `(normalizedOriginalSurface, correctedSurface)` pairs that were suppressed by
     * user revert feedback (`revertCount > 0 && netCount() == 0`).
     */
    fun getSuppressedPairs(language: AsrLanguage): Set<Pair<String, String>> {
        synchronized(lock) {
            val out = HashSet<Pair<String, String>>()
            for (entry in entries) {
                if (entry.language == language && entry.revertCount > 0 && entry.netCount() == 0) {
                    val keyOrig = if (language == AsrLanguage.ENGLISH) {
                        entry.originalSurface.lowercase()
                    } else {
                        entry.originalSurface
                    }
                    out.add(keyOrig to entry.correctedSurface)
                }
            }
            return out
        }
    }

    fun recordCorrection(
        language: AsrLanguage,
        originalSurface: String,
        correctedSurface: String,
        normalizedReading: String,
        contextTokens: List<String> = emptyList(),
        nowMs: Long = System.currentTimeMillis()
    ): VoiceCorrectionEntry {
        val resultEntry: VoiceCorrectionEntry
        synchronized(lock) {
            val idx = entries.indexOfFirst {
                it.language == language &&
                    it.originalSurface.equals(originalSurface, ignoreCase = language == AsrLanguage.ENGLISH) &&
                    it.correctedSurface == correctedSurface
            }

            resultEntry = if (idx >= 0) {
                val existing = entries[idx]
                val mergedTokens = (existing.recentContextTokens + contextTokens)
                    .distinct()
                    .takeLast(16)
                val updated = existing.copy(
                    normalizedReading = normalizedReading.ifEmpty { existing.normalizedReading },
                    correctionCount = existing.correctionCount + 1,
                    lastCorrectedAtMs = nowMs,
                    recentContextTokens = mergedTokens
                )
                entries[idx] = updated
                updated
            } else {
                val created = VoiceCorrectionEntry(
                    language = language,
                    originalSurface = originalSurface,
                    correctedSurface = correctedSurface,
                    normalizedReading = normalizedReading,
                    correctionCount = 1,
                    firstCorrectedAtMs = nowMs,
                    lastCorrectedAtMs = nowMs,
                    recentContextTokens = contextTokens.distinct().takeLast(16)
                )
                entries.add(created)
                if (entries.size > MAX_ENTRIES) {
                    val victimIdx = entries.indices.minByOrNull { entries[it].lastCorrectedAtMs } ?: 0
                    entries.removeAt(victimIdx)
                }
                created
            }
            saveToDiskLocked()
        }

        eventBus.publish(
            VoiceCorrectionLearnedEvent(
                language = if (language == AsrLanguage.ENGLISH) InputLanguage.ENGLISH else InputLanguage.JAPANESE,
                originalSurface = resultEntry.originalSurface,
                correctedSurface = resultEntry.correctedSurface,
                normalizedReading = resultEntry.normalizedReading,
                netCount = resultEntry.netCount(),
                occurredAtMillis = nowMs
            )
        )
        return resultEntry
    }

    fun recordAccept(
        language: AsrLanguage,
        originalSurface: String,
        correctedSurface: String,
        contextTokens: List<String> = emptyList(),
        nowMs: Long = System.currentTimeMillis()
    ) {
        val updatedEntry: VoiceCorrectionEntry
        synchronized(lock) {
            val idx = entries.indexOfFirst {
                it.language == language &&
                    it.originalSurface.equals(originalSurface, ignoreCase = language == AsrLanguage.ENGLISH) &&
                    it.correctedSurface == correctedSurface
            }
            updatedEntry = if (idx >= 0) {
                val existing = entries[idx]
                val mergedTokens = if (contextTokens.isEmpty()) {
                    existing.recentContextTokens
                } else {
                    (existing.recentContextTokens + contextTokens).distinct().takeLast(16)
                }
                val updated = existing.copy(
                    acceptCount = existing.acceptCount + 1,
                    lastCorrectedAtMs = nowMs,
                    recentContextTokens = mergedTokens
                )
                entries[idx] = updated
                updated
            } else {
                val normReading = if (language == AsrLanguage.ENGLISH) {
                    correctedSurface.lowercase().replace(Regex("[\\s\\-']+"), "")
                } else {
                    correctedSurface
                }
                val created = VoiceCorrectionEntry(
                    language = language,
                    originalSurface = originalSurface,
                    correctedSurface = correctedSurface,
                    normalizedReading = normReading,
                    correctionCount = 1,
                    acceptCount = 1,
                    firstCorrectedAtMs = nowMs,
                    lastCorrectedAtMs = nowMs,
                    recentContextTokens = contextTokens.distinct().takeLast(16)
                )
                entries.add(created)
                if (entries.size > MAX_ENTRIES) {
                    val victimIdx = entries.indices.minByOrNull { entries[it].lastCorrectedAtMs } ?: 0
                    entries.removeAt(victimIdx)
                }
                created
            }
            saveToDiskLocked()
        }

        eventBus.publish(
            VoiceCorrectionLearnedEvent(
                language = if (language == AsrLanguage.ENGLISH) InputLanguage.ENGLISH else InputLanguage.JAPANESE,
                originalSurface = updatedEntry.originalSurface,
                correctedSurface = updatedEntry.correctedSurface,
                normalizedReading = updatedEntry.normalizedReading,
                netCount = updatedEntry.netCount(),
                occurredAtMillis = nowMs
            )
        )
    }

    fun recordRevert(
        language: AsrLanguage,
        originalSurface: String,
        correctedSurface: String,
        nowMs: Long = System.currentTimeMillis()
    ) {
        synchronized(lock) {
            val idx = entries.indexOfFirst {
                it.language == language &&
                    it.originalSurface.equals(originalSurface, ignoreCase = language == AsrLanguage.ENGLISH) &&
                    it.correctedSurface == correctedSurface
            }
            if (idx >= 0) {
                val existing = entries[idx]
                entries[idx] = existing.copy(
                    revertCount = existing.revertCount + 1,
                    lastCorrectedAtMs = nowMs
                )
            } else {
                val normReading = if (language == AsrLanguage.ENGLISH) {
                    correctedSurface.lowercase().replace(Regex("[\\s\\-']+"), "")
                } else {
                    correctedSurface
                }
                entries.add(
                    VoiceCorrectionEntry(
                        language = language,
                        originalSurface = originalSurface,
                        correctedSurface = correctedSurface,
                        normalizedReading = normReading,
                        correctionCount = 0,
                        acceptCount = 0,
                        revertCount = 1,
                        firstCorrectedAtMs = nowMs,
                        lastCorrectedAtMs = nowMs
                    )
                )
                if (entries.size > MAX_ENTRIES) {
                    val victimIdx = entries.indices.minByOrNull { entries[it].lastCorrectedAtMs } ?: 0
                    entries.removeAt(victimIdx)
                }
            }
            saveToDiskLocked()
        }
    }

    fun clear() {
        synchronized(lock) {
            entries.clear()
            saveToDiskLocked()
        }
    }
}
