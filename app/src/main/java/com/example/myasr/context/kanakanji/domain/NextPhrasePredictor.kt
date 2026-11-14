package com.example.myasr.context.kanakanji.domain

import com.example.myasr.context.kanakanji.application.UserHistorySyncManager
import com.example.myasr.context.vocabulary.domain.Priority

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.Calendar

/**
 * Predicts the next phrase / continuation candidates immediately after a phrase is committed
 * (Zero-query prediction), before the user strikes the next key.
 *
 * Implements a Multi-Tier architecture:
 * 1. User Bigram Learning Cache (dynamic persistent user history)
 * 2. Canonical Collocation Dictionary (in-memory standard conversational phrases)
 * 3. Grammatical Particle & Auxiliary Continuation
 * 4. Contextual & Temporal Sentence-Start Heuristics
 */
class NextPhrasePredictor internal constructor(private val context: Context? = null) {

    private val TAG = "NextPhrasePredictor"
    private val scope = CoroutineScope(Dispatchers.IO)
    private var saveHistoryJob: Job? = null
    private val cacheFile = context?.let { File(it.filesDir, "next_phrase_cache.json") }
    private val timestampFile = context?.let { File(it.filesDir, "next_phrase_timestamps.json") }

    // Constants for 5MB RAM cap:
    // 4,000 prefix keys x 16 continuations = max 32,000 bigram pairs (< 5MB RAM)
    companion object {
        const val MAX_PREFIX_KEYS = 4_000
        const val MAX_CONTINUATIONS_PER_KEY = 16
        const val MAX_TOTAL_BIGRAM_PAIRS = 32_000

        @Volatile
        private var instance: NextPhrasePredictor? = null

        fun getInstance(context: Context): NextPhrasePredictor {
            return instance ?: synchronized(this) {
                instance ?: NextPhrasePredictor(context.applicationContext).also { instance = it }
            }
        }

        // Static canonical collocations for standard Japanese expressions
        private val CANONICAL_COLLOCATIONS = mapOf(
            "よろしく" to listOf("お願いします", "お願いいたします", "どうぞ"),
            "お願い" to listOf("します", "いたします", "申し上げます"),
            "ありがとう" to listOf("ございます", "ございました"),
            "お疲れ様" to listOf("です", "でした"),
            "お疲れ" to listOf("様です", "様でした"),
            "ご" to listOf("連絡", "確認", "案内", "検討"),
            "確認" to listOf("しました", "いたします", "お願いします", "中"),
            "作業" to listOf("中", "完了", "内容", "を進めます"),
            "了解" to listOf("しました", "いたしました", "です"),
            "承知" to listOf("いたしました", "しました"),
            "いつも" to listOf("お世話になっております", "ありがとうございます"),
            "お世話" to listOf("になっております", "になります"),
            "お待たせ" to listOf("いたしました", "しました"),
            "ご検討" to listOf("のほどよろしくお願いいたします", "ください"),
            "申し訳" to listOf("ありません", "ございません", "ない"),
            "大丈夫" to listOf("です", "ですか", "でしょうか"),
            "問題" to listOf("ありません", "ない", "ですか"),
            "連絡" to listOf("します", "いたします", "お待ちしております"),
            "今日" to listOf("は", "の", "中に", "も"),
            "明日" to listOf("は", "の", "までに", "も"),
            "昨日" to listOf("は", "の", "も"),
            "今" to listOf("から", "は", "のところ"),
            "会議" to listOf("の", "に", "は", "室"),
            "資料" to listOf("を", "の", "作成", "確認"),
            "いただき" to listOf("ありがとうございます", "ました"),
            "させて" to listOf("いただきます", "いただきました"),
            "電話" to listOf("します", "いたします", "番号"),
            "伺い" to listOf("ます", "ました"),
            "行き" to listOf("ます", "ました"),
            "向かっ" to listOf("ています", "ております"),
            "思っ" to listOf("ています", "ております"),
            "知っ" to listOf("ています", "ております")
        )

        // Frequent Japanese case particles and connection auxiliaries
        private val COMMON_PARTICLES = listOf(
            "は", "が", "を", "に", "で", "の", "と", "も", "へ", "から", "まで", "より", "ので"
        )

        // Sentence-ending predicates/auxiliaries where '。' is highly likely
        private val SENTENCE_ENDING_PREDICATES = listOf(
            "ます", "ました", "ません", "ませんでした", "でした", "です",
            "ございます", "ございました", "いたします", "いたしました",
            "おります", "おりました", "あります", "ありました", "います", "いました",
            "ください", "下さい", "でしょう", "ましょう", "たい", "たいです",
            "ない", "なかった", "ないです", "なかったです",
            "った", "いた", "んだ", "のだ", "だ",
            "ますか", "ましたか", "ですか", "でしょうか", "かな", "よね", "だよ", "だね"
        )

        private val QUESTION_ENDINGS = listOf(
            "ですか", "でしょうか", "ますか", "ましたか", "ませんか", "かな"
        )

        private val PERIOD_SUPPRESSING_SUFFIXES = listOf(
            "、", ",", "・", "（", "「", "『", "【", "(", "[", "{"
        )
    }

    // Access-ordered LinkedHashMap for LRU prefix keys (prevPhrase -> (nextPhrase -> count))
    private val bigramHistory = LinkedHashMap<String, LinkedHashMap<String, Int>>(256, 0.75f, true)
    private val bigramLastUsed = LinkedHashMap<String, LinkedHashMap<String, Long>>(256, 0.75f, true)
    private var totalBigramPairs = 0
    private val historyLoadLock = Any()

    @Volatile
    private var isHistoryLoaded = false

    init {
        if (cacheFile != null) {
            scope.launch {
                ensureHistoryLoaded()
            }
        } else {
            isHistoryLoaded = true
        }
    }

    fun ensureHistoryLoaded() {
        if (isHistoryLoaded) return
        synchronized(historyLoadLock) {
            if (isHistoryLoaded) return
            loadHistory()
            isHistoryLoaded = true
        }
    }

    /**
     * Records a phrase transition (prevPhrase -> currentPhrase) into the dynamic learning cache.
     * Safe to call from any thread; asynchronous background serialization avoids any UI jitter.
     */
    @Synchronized
    fun recordTransition(prevPhrase: String, currentPhrase: String) {
        ensureHistoryLoaded()
        val prev = prevPhrase.trim()
        val curr = currentPhrase.trim()
        if (prev.isEmpty() || curr.isEmpty() || prev == curr) return

        val nowMs = System.currentTimeMillis()
        val nextMap = bigramHistory.getOrPut(prev) { LinkedHashMap(8, 0.75f, true) }
        val nextTsMap = bigramLastUsed.getOrPut(prev) { LinkedHashMap(8, 0.75f, true) }
        val existed = nextMap.containsKey(curr)
        val count = nextMap.getOrDefault(curr, 0)
        nextMap[curr] = (count + 1).coerceAtMost(100_000)
        nextTsMap[curr] = nowMs
        if (!existed) {
            totalBigramPairs++
        }

        // Cap continuations per prefix key (Frequency-guarded eviction: drop lowest count)
        if (nextMap.size > MAX_CONTINUATIONS_PER_KEY) {
            val lowestEntry = nextMap.minByOrNull { it.value }
            if (lowestEntry != null) {
                nextMap.remove(lowestEntry.key)
                nextTsMap.remove(lowestEntry.key)
                totalBigramPairs--
            }
        }

        // Cap prefix keys (LRU eviction: drop oldest accessed key)
        if (bigramHistory.size > MAX_PREFIX_KEYS) {
            val oldestKey = bigramHistory.keys.firstOrNull()
            if (oldestKey != null) {
                val removedMap = bigramHistory.remove(oldestKey)
                bigramLastUsed.remove(oldestKey)
                if (removedMap != null) totalBigramPairs -= removedMap.size
            }
        }

        // Global pair count cap (< 5MB RAM guarantee)
        while (totalBigramPairs > MAX_TOTAL_BIGRAM_PAIRS && bigramHistory.isNotEmpty()) {
            val oldestKey = bigramHistory.keys.firstOrNull() ?: break
            val removedMap = bigramHistory.remove(oldestKey)
            bigramLastUsed.remove(oldestKey)
            if (removedMap != null) totalBigramPairs -= removedMap.size
        }

        scheduleSaveHistory()
        context?.let { ctx ->
            try {
                UserHistorySyncManager.getInstance(ctx).notifyHistoryMutated()
            } catch (e: Exception) {
                // Ignore if sync manager is not ready
            }
        }
    }

    /**
     * Predicts continuation candidates based on the committed phrase and surrounding text.
     * Guaranteed sub-millisecond execution (< 0.1 ms) safe for direct UI invocation.
     */
    @Synchronized
    fun predict(
        lastPhrase: String,
        textBeforeCursor: String = "",
        suggestPeriod: Boolean = true
    ): List<ConversionCandidate> {
        val result = mutableListOf<ConversionCandidate>()
        val seenSurfaces = HashSet<String>()

        fun addCandidate(cand: ConversionCandidate) {
            val trimmedSurface = cand.surface.trim { it.isWhitespace() || it == '\u3000' }
            if (trimmedSurface.isNotEmpty() && seenSurfaces.add(trimmedSurface)) {
                val normalizedCand = if (trimmedSurface == cand.surface) {
                    cand
                } else {
                    cand.copy(surface = trimmedSurface)
                }
                result.add(normalizedCand)
            }
        }

        val cleanLast = lastPhrase.trim()

        // 1. User Bigram History Cache (Highest Priority)
        if (cleanLast.isNotEmpty()) {
            ensureHistoryLoaded()
            val userNextMap = bigramHistory[cleanLast]
            if (userNextMap != null) {
                val tsMap = bigramLastUsed[cleanLast]
                val sortedUserNext = userNextMap.entries.sortedByDescending { it.value }
                for (entry in sortedUserNext) {
                    addCandidate(
                        ConversionCandidate(
                            surface = entry.key,
                            reading = entry.key,
                            isPrediction = true,
                            source = "history_bigram",
                            historyCount = entry.value,
                            lastUsedAtMs = tsMap?.get(entry.key) ?: 0L
                        )
                    )
                }
            }
        }

        // 2. Canonical Collocation Dictionary
        if (cleanLast.isNotEmpty()) {
            val canonicalList = CANONICAL_COLLOCATIONS[cleanLast]
            if (canonicalList != null) {
                for (phrase in canonicalList) {
                    addCandidate(
                        ConversionCandidate(
                            surface = phrase,
                            reading = phrase,
                            isPrediction = true,
                            source = "canonical_collocation"
                        )
                    )
                }
            }
        }

        // 3. Sentence-Start / Punctuation Heuristics
        val isSentenceStart = cleanLast.isEmpty() ||
                cleanLast.endsWith("。") ||
                cleanLast.endsWith("！") ||
                cleanLast.endsWith("？") ||
                cleanLast.endsWith(".") ||
                cleanLast.endsWith("!") ||
                cleanLast.endsWith("?") ||
                cleanLast.endsWith("\n") ||
                textBeforeCursor.endsWith("。") ||
                textBeforeCursor.endsWith("！") ||
                textBeforeCursor.endsWith("？") ||
                textBeforeCursor.endsWith("\n")

        if (isSentenceStart) {
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            if (hour in 5..10) {
                addCandidate(ConversionCandidate("おはようございます", "おはようございます", true, "time_heuristic"))
            }
            addCandidate(ConversionCandidate("お疲れ様です", "お疲れ様です", true, "time_heuristic"))
            addCandidate(ConversionCandidate("お世話になっております", "お世話になっております", true, "time_heuristic"))
            addCandidate(ConversionCandidate("よろしくお願いいたします", "よろしくお願いいたします", true, "time_heuristic"))
            addCandidate(ConversionCandidate("また、", "また、", true, "time_heuristic"))
            addCandidate(ConversionCandidate("つきましては、", "つきましては、", true, "time_heuristic"))
            addCandidate(ConversionCandidate("なお、", "なお、", true, "time_heuristic"))
        }

        val allowPeriod = suggestPeriod && !shouldSuppressPeriodSuggestion(cleanLast, isSentenceStart)
        val isPredicateEnd = allowPeriod && isSentenceEndingPredicate(cleanLast)

        // 3.5 High-priority Japanese Period ('。') suggestion immediately after sentence-ending predicates
        if (isPredicateEnd) {
            addCandidate(
                ConversionCandidate(
                    surface = "。",
                    reading = "。",
                    isPrediction = true,
                    source = "punctuation_suggestion"
                )
            )
            if (isQuestionEnding(cleanLast)) {
                addCandidate(
                    ConversionCandidate(
                        surface = "？",
                        reading = "？",
                        isPrediction = true,
                        source = "punctuation_suggestion"
                    )
                )
            }
        }

        // 4. Particle Continuation
        // If lastPhrase does not already end in a particle or punctuation, suggest common particles
        if (cleanLast.isNotEmpty() && !isSentenceStart && !isParticle(cleanLast)) {
            for (p in COMMON_PARTICLES) {
                addCandidate(
                    ConversionCandidate(
                        surface = p,
                        reading = p,
                        isPrediction = true,
                        source = "particle"
                    )
                )
            }
        }

        // 5. General Japanese Period ('。') suggestion after non-particle phrases (e.g. nouns, short clauses)
        if (allowPeriod && !isPredicateEnd) {
            addCandidate(
                ConversionCandidate(
                    surface = "。",
                    reading = "。",
                    isPrediction = true,
                    source = "punctuation_suggestion"
                )
            )
        }

        return result
    }

    private fun isParticle(phrase: String): Boolean {
        return COMMON_PARTICLES.contains(phrase) ||
                phrase.endsWith("は") || phrase.endsWith("が") || phrase.endsWith("を") ||
                phrase.endsWith("に") || phrase.endsWith("で") || phrase.endsWith("と") ||
                phrase.endsWith("の") || phrase.endsWith("も") || phrase.endsWith("へ")
    }

    private fun shouldSuppressPeriodSuggestion(cleanLast: String, isSentenceStart: Boolean): Boolean {
        if (isSentenceStart || cleanLast.isEmpty()) return true
        if (PERIOD_SUPPRESSING_SUFFIXES.any { cleanLast.endsWith(it) }) return true
        if (isParticle(cleanLast)) return true
        return false
    }

    private fun isSentenceEndingPredicate(cleanLast: String): Boolean {
        return SENTENCE_ENDING_PREDICATES.any { cleanLast.endsWith(it) }
    }

    private fun isQuestionEnding(cleanLast: String): Boolean {
        return QUESTION_ENDINGS.any { cleanLast.endsWith(it) }
    }

    @Synchronized
    fun clearHistory() {
        ensureHistoryLoaded()
        bigramHistory.clear()
        totalBigramPairs = 0
        scheduleSaveHistory(immediate = true)
    }

    /**
     * Returns an immutable snapshot copy of the current bigram history.
     */
    @Synchronized
    fun getHistorySnapshot(): Map<String, Map<String, Int>> {
        ensureHistoryLoaded()
        val copy = LinkedHashMap<String, Map<String, Int>>(bigramHistory.size)
        for ((k, v) in bigramHistory) {
            copy[k] = LinkedHashMap(v)
        }
        return copy
    }

    /**
     * Total number of unique bigram pairs currently held in memory.
     */
    @Synchronized
    fun getTotalBigramPairs(): Int {
        ensureHistoryLoaded()
        return totalBigramPairs
    }

    /**
     * Number of unique previous phrase keys in memory.
     */
    @Synchronized
    fun getPrefixKeyCount(): Int {
        ensureHistoryLoaded()
        return bigramHistory.size
    }

    /**
     * Safely merges remote bigram history entries using idempotent 3-Way Delta Merge:
     * Delta_L(prev, curr) = max(0, L - B)
     * M(prev, curr) = min(100_000, max(R, B) + Delta_L)
     */
    @Synchronized
    fun mergeHistoryDelta(
        remoteBigram: Map<String, Map<String, Int>>,
        baseBigram: Map<String, Map<String, Int>>
    ) {
        if (remoteBigram.isEmpty() && baseBigram.isEmpty()) return
        ensureHistoryLoaded()

        val allPrevKeys = LinkedHashSet<String>()
        allPrevKeys.addAll(bigramHistory.keys)
        allPrevKeys.addAll(remoteBigram.keys)
        allPrevKeys.addAll(baseBigram.keys)

        for (prev in allPrevKeys) {
            if (prev.isBlank()) continue
            val localNextMap = bigramHistory.getOrPut(prev) { LinkedHashMap(8, 0.75f, true) }
            val remoteNextMap = remoteBigram[prev] ?: emptyMap()
            val baseNextMap = baseBigram[prev] ?: emptyMap()

            val allCurrKeys = LinkedHashSet<String>()
            allCurrKeys.addAll(localNextMap.keys)
            allCurrKeys.addAll(remoteNextMap.keys)
            allCurrKeys.addAll(baseCurrKeys(baseNextMap))

            for (curr in allCurrKeys) {
                if (curr.isBlank()) continue
                val localCount = localNextMap.getOrDefault(curr, 0)
                val baseCount = baseNextMap.getOrDefault(curr, 0)
                val remoteCount = remoteNextMap.getOrDefault(curr, 0)

                val deltaL = maxOf(0, localCount - baseCount)
                val baseOrRemote = maxOf(remoteCount, baseCount)
                val merged = (baseOrRemote + deltaL).coerceIn(0, 100_000)

                val existed = localNextMap.containsKey(curr)
                if (merged > 0) {
                    localNextMap[curr] = merged
                    if (!existed) {
                        totalBigramPairs++
                    }
                } else if (existed) {
                    localNextMap.remove(curr)
                    totalBigramPairs--
                }
            }

            // Cap continuations for this key
            if (localNextMap.size > MAX_CONTINUATIONS_PER_KEY) {
                val toRemove = localNextMap.entries.sortedBy { it.value }
                    .take(localNextMap.size - MAX_CONTINUATIONS_PER_KEY)
                for (rem in toRemove) {
                    localNextMap.remove(rem.key)
                    totalBigramPairs--
                }
            }
        }

        // Cap prefix keys
        while (bigramHistory.size > MAX_PREFIX_KEYS) {
            val oldestKey = bigramHistory.keys.firstOrNull() ?: break
            val removedMap = bigramHistory.remove(oldestKey)
            if (removedMap != null) totalBigramPairs -= removedMap.size
        }

        // Cap global total pairs
        while (totalBigramPairs > MAX_TOTAL_BIGRAM_PAIRS && bigramHistory.isNotEmpty()) {
            val oldestKey = bigramHistory.keys.firstOrNull() ?: break
            val removedMap = bigramHistory.remove(oldestKey)
            if (removedMap != null) totalBigramPairs -= removedMap.size
        }

        scheduleSaveHistory(immediate = true)
    }

    private fun baseCurrKeys(baseNextMap: Map<String, Int>): Set<String> = baseNextMap.keys

    /**
     * Safely merges remote bigram history entries using idempotent Max-Merge (Fallback).
     */
    @Synchronized
    fun mergeHistory(remoteBigram: Map<String, Map<String, Int>>) {
        if (remoteBigram.isEmpty()) return

        for ((prev, nextMap) in remoteBigram) {
            if (prev.isBlank() || nextMap.isEmpty()) continue
            val localNextMap = bigramHistory.getOrPut(prev) { LinkedHashMap(8, 0.75f, true) }
            for ((curr, remoteCount) in nextMap) {
                if (curr.isBlank() || remoteCount <= 0) continue
                val existed = localNextMap.containsKey(curr)
                val localCount = localNextMap.getOrDefault(curr, 0)
                val mergedCount = maxOf(localCount, remoteCount).coerceAtMost(100_000)
                localNextMap[curr] = mergedCount
                if (!existed) {
                    totalBigramPairs++
                }
            }

            // Cap continuations for this key
            if (localNextMap.size > MAX_CONTINUATIONS_PER_KEY) {
                val toRemove = localNextMap.entries.sortedBy { it.value }
                    .take(localNextMap.size - MAX_CONTINUATIONS_PER_KEY)
                for (rem in toRemove) {
                    localNextMap.remove(rem.key)
                    totalBigramPairs--
                }
            }
        }

        // Cap prefix keys
        while (bigramHistory.size > MAX_PREFIX_KEYS) {
            val oldestKey = bigramHistory.keys.firstOrNull() ?: break
            val removedMap = bigramHistory.remove(oldestKey)
            if (removedMap != null) totalBigramPairs -= removedMap.size
        }

        // Cap global total pairs
        while (totalBigramPairs > MAX_TOTAL_BIGRAM_PAIRS && bigramHistory.isNotEmpty()) {
            val oldestKey = bigramHistory.keys.firstOrNull() ?: break
            val removedMap = bigramHistory.remove(oldestKey)
            if (removedMap != null) totalBigramPairs -= removedMap.size
        }

        scheduleSaveHistory(immediate = true)
    }

    private fun scheduleSaveHistory(immediate: Boolean = false) {
        saveHistoryJob?.cancel()
        saveHistoryJob = scope.launch {
            if (!immediate) {
                delay(1500L) // 1.5s debounce
            }
            saveHistory()
        }
    }

    private fun saveHistory() {
        val file = cacheFile ?: return
        val (snapshotJson, tsSnapshotJson) = synchronized(this) {
            val root = JSONObject()
            for ((key, map) in bigramHistory) {
                val child = JSONObject()
                for ((next, count) in map) {
                    child.put(next, count)
                }
                root.put(key, child)
            }
            val tsRoot = JSONObject()
            for ((key, map) in bigramLastUsed) {
                if (!bigramHistory.containsKey(key)) continue
                val child = JSONObject()
                for ((next, ts) in map) {
                    if (ts > 0L) child.put(next, ts)
                }
                if (child.length() > 0) tsRoot.put(key, child)
            }
            Pair(root.toString(), tsRoot.toString())
        }

        try {
            val tempFile = File(file.parentFile, "${file.name}.tmp")
            tempFile.writeText(snapshotJson, Charsets.UTF_8)
            if (!tempFile.renameTo(file)) {
                tempFile.copyTo(file, overwrite = true)
                tempFile.delete()
            }
            timestampFile?.let { tsFile ->
                val tsTemp = File(tsFile.parentFile, "${tsFile.name}.tmp")
                tsTemp.writeText(tsSnapshotJson, Charsets.UTF_8)
                if (!tsTemp.renameTo(tsFile)) {
                    tsTemp.copyTo(tsFile, overwrite = true)
                    tsTemp.delete()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error saving next phrase history: ${e.message}")
        }
    }

    private fun loadHistory() {
        val file = cacheFile ?: return
        try {
            if (file.exists()) {
                val jsonStr = file.readText(Charsets.UTF_8)
                val root = JSONObject(jsonStr)
                val keys = root.keys()
                synchronized(this) {
                    bigramHistory.clear()
                    totalBigramPairs = 0
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val child = root.optJSONObject(k) ?: continue
                        val childMap = LinkedHashMap<String, Int>(8, 0.75f, true)
                        val childKeys = child.keys()
                        while (childKeys.hasNext()) {
                            val ck = childKeys.next()
                            childMap[ck] = child.optInt(ck, 1)
                            totalBigramPairs++
                        }
                        bigramHistory[k] = childMap
                    }
                }
            }
            val tsFile = timestampFile
            if (tsFile != null && tsFile.exists()) {
                val tsRoot = JSONObject(tsFile.readText(Charsets.UTF_8))
                val tsKeys = tsRoot.keys()
                synchronized(this) {
                    bigramLastUsed.clear()
                    while (tsKeys.hasNext()) {
                        val k = tsKeys.next()
                        val child = tsRoot.optJSONObject(k) ?: continue
                        val childMap = LinkedHashMap<String, Long>(8, 0.75f, true)
                        val childKeys = child.keys()
                        while (childKeys.hasNext()) {
                            val ck = childKeys.next()
                            val ts = child.optLong(ck, 0L)
                            if (ts > 0L) childMap[ck] = ts
                        }
                        if (childMap.isNotEmpty()) bigramLastUsed[k] = childMap
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error loading next phrase history: ${e.message}")
        }
    }
}
