package com.example.myasr.context.english.infrastructure

import android.content.Context
import android.util.Log
import com.example.myasr.context.english.domain.EnglishCaseModel
import com.example.myasr.context.english.domain.EnglishDictionaryUtils
import com.example.myasr.context.english.domain.SwipeVocabularyJudgement
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Idea C: Adaptive on-device LRU cache for recently committed OOV English words
 * (capacity capped at 500 entries) persisted separately from `custom_dictionary.json`.
 */
class SwipeAdaptiveCacheRepository internal constructor(
    private val storageFile: File,
    private val maxEntries: Int = SwipeVocabularyJudgement.ADAPTIVE_CACHE_MAX_ENTRIES
) {

    data class AdaptiveSwipeEntry(
        val swipeKey: String,
        val surface: String,
        val useCount: Int,
        val lastUsedAtMs: Long
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("swipeKey", swipeKey)
            put("surface", surface)
            put("useCount", useCount)
            put("lastUsedAtMs", lastUsedAtMs)
        }

        companion object {
            fun fromJson(obj: JSONObject): AdaptiveSwipeEntry? {
                val key = obj.optString("swipeKey", "")
                val surf = obj.optString("surface", "")
                if (key.isEmpty() || surf.isEmpty()) return null
                return AdaptiveSwipeEntry(
                    swipeKey = key,
                    surface = surf,
                    useCount = obj.optInt("useCount", 1).coerceAtLeast(1),
                    lastUsedAtMs = obj.optLong("lastUsedAtMs", 0L)
                )
            }
        }
    }

    constructor(context: Context) : this(
        storageFile = File(context.filesDir, FILE_NAME),
        maxEntries = SwipeVocabularyJudgement.ADAPTIVE_CACHE_MAX_ENTRIES
    )

    companion object {
        private const val TAG = "SwipeAdaptiveCacheRepo"
        const val FILE_NAME = "swipe_adaptive_cache.json"
    }

    private val lock = Any()
    private val activeEntries = LinkedHashMap<String, AdaptiveSwipeEntry>(64, 0.75f, false)
    private val pendingObservations = LinkedHashMap<String, Pair<String, Int>>(64, 0.75f, false)

    init {
        loadFromDisk()
    }

    private fun loadFromDisk() {
        synchronized(lock) {
            activeEntries.clear()
            if (!storageFile.exists() || storageFile.length() == 0L) return
            try {
                val arr = JSONArray(storageFile.readText(Charsets.UTF_8))
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val entry = AdaptiveSwipeEntry.fromJson(obj) ?: continue
                    if (SwipeVocabularyJudgement.isValidAdaptiveSwipeToken(entry.surface)) {
                        activeEntries.remove(entry.swipeKey)
                        activeEntries[entry.swipeKey] = entry
                    }
                }
                evictActiveOverflowLocked()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load adaptive swipe cache: ${e.message}")
            }
        }
    }

    private fun saveToDiskLocked() {
        try {
            storageFile.parentFile?.let { parent ->
                if (!parent.exists()) parent.mkdirs()
            }
            val arr = JSONArray()
            for (entry in activeEntries.values) {
                arr.put(entry.toJson())
            }
            val tmpFile = File(storageFile.parentFile, "${storageFile.name}.tmp")
            tmpFile.writeText(arr.toString(), Charsets.UTF_8)
            if (storageFile.exists()) {
                storageFile.delete()
            }
            if (!tmpFile.renameTo(storageFile)) {
                tmpFile.copyTo(storageFile, overwrite = true)
                tmpFile.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save adaptive swipe cache: ${e.message}")
        }
    }

    private fun evictActiveOverflowLocked() {
        val cap = maxEntries.coerceAtLeast(1)
        while (activeEntries.size > cap) {
            val it = activeEntries.entries.iterator()
            if (it.hasNext()) {
                it.next()
                it.remove()
            } else {
                break
            }
        }
    }

    private fun evictPendingOverflowLocked() {
        while (pendingObservations.size > SwipeVocabularyJudgement.ADAPTIVE_CACHE_PENDING_MAX_ENTRIES) {
            val it = pendingObservations.entries.iterator()
            if (it.hasNext()) {
                it.next()
                it.remove()
            } else {
                break
            }
        }
    }

    private fun resolvePreferredSurface(
        committedSurface: String,
        existing: AdaptiveSwipeEntry?
    ): String {
        val hasExplicitUpper = committedSurface.any { it.isUpperCase() }
        if (hasExplicitUpper) {
            return committedSurface
        }
        val canonicalTop = EnglishCaseModel.lookupLexicon(committedSurface.lowercase())
            ?.variants
            ?.firstOrNull()
            ?.surface
        if (!canonicalTop.isNullOrEmpty() && canonicalTop.any { it.isUpperCase() }) {
            return canonicalTop
        }
        return existing?.surface ?: committedSurface
    }

    private fun recordCommittedWordLocked(
        surface: String,
        source: SwipeVocabularyJudgement.SwipeLearnSource,
        isSensitiveOrPasswordField: Boolean,
        isExplicitUserDictWord: Boolean,
        isAlreadyInBase32kVocab: Boolean,
        nowMs: Long,
        deferSave: Boolean
    ): SwipeVocabularyJudgement.AdaptiveCacheAdmissionAction {
        val trimmed = surface.trim()
        val isValid = SwipeVocabularyJudgement.isValidAdaptiveSwipeToken(trimmed)
        val swipeKey = if (isValid) EnglishDictionaryUtils.toSwipeTrieKey(trimmed) else ""
        val existingActive = if (swipeKey.isNotEmpty()) activeEntries[swipeKey] else null
        val pendingCount = if (swipeKey.isNotEmpty()) (pendingObservations[swipeKey]?.second ?: 0) else 0
        val observedCount = (existingActive?.useCount ?: pendingCount) + 1
        val isKnownExt = if (isValid) EnglishCaseModel.isKnownWord(trimmed.lowercase()) else false

        val action = SwipeVocabularyJudgement.judgeAdaptiveCacheAdmission(
            SwipeVocabularyJudgement.AdaptiveCacheAdmissionInput(
                isSensitiveOrPasswordField = isSensitiveOrPasswordField,
                isValidSwipeToken = isValid,
                isExplicitUserDictWord = isExplicitUserDictWord,
                isAlreadyInBase32kVocab = isAlreadyInBase32kVocab,
                isKnownInExtendedLexicon = isKnownExt,
                commitSource = source,
                observedCount = observedCount
            )
        ).decision

        when (action) {
            SwipeVocabularyJudgement.AdaptiveCacheAdmissionAction.DEFER_UNVERIFIED_SINGLE_TYPO_GUARD -> {
                if (swipeKey.isNotEmpty()) {
                    pendingObservations.remove(swipeKey)
                    pendingObservations[swipeKey] = trimmed to observedCount
                    evictPendingOverflowLocked()
                }
            }
            SwipeVocabularyJudgement.AdaptiveCacheAdmissionAction.ADMIT_TO_ADAPTIVE_CACHE -> {
                if (swipeKey.isNotEmpty()) {
                    val preferredSurface = resolvePreferredSurface(trimmed, existingActive)
                    val updated = AdaptiveSwipeEntry(
                        swipeKey = swipeKey,
                        surface = preferredSurface,
                        useCount = observedCount,
                        lastUsedAtMs = nowMs
                    )
                    activeEntries.remove(swipeKey)
                    activeEntries[swipeKey] = updated
                    pendingObservations.remove(swipeKey)
                    evictActiveOverflowLocked()
                    if (!deferSave) {
                        saveToDiskLocked()
                    }
                }
            }
            else -> {
                // Reject or skip without modifying active cache
            }
        }
        return action
    }

    fun recordCommittedWord(
        surface: String,
        source: SwipeVocabularyJudgement.SwipeLearnSource,
        isSensitiveOrPasswordField: Boolean,
        isExplicitUserDictWord: Boolean,
        isAlreadyInBase32kVocab: Boolean,
        nowMs: Long = System.currentTimeMillis()
    ): SwipeVocabularyJudgement.AdaptiveCacheAdmissionAction {
        synchronized(lock) {
            return recordCommittedWordLocked(
                surface = surface,
                source = source,
                isSensitiveOrPasswordField = isSensitiveOrPasswordField,
                isExplicitUserDictWord = isExplicitUserDictWord,
                isAlreadyInBase32kVocab = isAlreadyInBase32kVocab,
                nowMs = nowMs,
                deferSave = false
            )
        }
    }

    fun recordCommittedText(
        text: String,
        source: SwipeVocabularyJudgement.SwipeLearnSource,
        isSensitiveOrPasswordField: Boolean,
        explicitUserDictSwipeKeys: Set<String>,
        isInBase32kFn: (String) -> Boolean,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (isSensitiveOrPasswordField || source == SwipeVocabularyJudgement.SwipeLearnSource.SENSITIVE_OR_BYPASS) {
            return false
        }
        val tokens = SwipeVocabularyJudgement.extractEnglishTokensForLearning(text)
        if (tokens.isEmpty()) return false

        synchronized(lock) {
            var anyAdmitted = false
            for (token in tokens) {
                val swipeKey = EnglishDictionaryUtils.toSwipeTrieKey(token)
                val inUserDict = swipeKey in explicitUserDictSwipeKeys
                val inBase32k = !inUserDict && isInBase32kFn(token.lowercase())
                val action = recordCommittedWordLocked(
                    surface = token,
                    source = source,
                    isSensitiveOrPasswordField = false,
                    isExplicitUserDictWord = inUserDict,
                    isAlreadyInBase32kVocab = inBase32k,
                    nowMs = nowMs,
                    deferSave = true
                )
                if (action == SwipeVocabularyJudgement.AdaptiveCacheAdmissionAction.ADMIT_TO_ADAPTIVE_CACHE) {
                    anyAdmitted = true
                }
            }
            if (anyAdmitted) {
                saveToDiskLocked()
            }
            return anyAdmitted
        }
    }

    fun getActiveSurfaces(): List<String> {
        synchronized(lock) {
            return activeEntries.values.map { it.surface }.reversed()
        }
    }

    fun getActiveCount(): Int {
        synchronized(lock) {
            return activeEntries.size
        }
    }

    fun clear() {
        synchronized(lock) {
            activeEntries.clear()
            pendingObservations.clear()
            saveToDiskLocked()
        }
    }
}
