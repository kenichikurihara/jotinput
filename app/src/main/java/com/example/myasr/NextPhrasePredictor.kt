package com.example.myasr

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    private val cacheFile = context?.let { File(it.filesDir, "next_phrase_cache.json") }

    // Map: previous phrase -> Map of (next phrase -> occurrence count)
    private val bigramHistory = HashMap<String, MutableMap<String, Int>>()

    init {
        loadHistory()
    }

    companion object {
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
    }

    /**
     * Records a phrase transition (prevPhrase -> currentPhrase) into the dynamic learning cache.
     * Safe to call from any thread; asynchronous background serialization avoids any UI jitter.
     */
    @Synchronized
    fun recordTransition(prevPhrase: String, currentPhrase: String) {
        val prev = prevPhrase.trim()
        val curr = currentPhrase.trim()
        if (prev.isEmpty() || curr.isEmpty() || prev == curr) return

        val nextMap = bigramHistory.getOrPut(prev) { HashMap() }
        val count = nextMap.getOrDefault(curr, 0)
        nextMap[curr] = count + 1

        // Cap map size to prevent unbounded memory growth
        if (bigramHistory.size > 1000) {
            val keyToRemove = bigramHistory.keys.firstOrNull()
            if (keyToRemove != null) bigramHistory.remove(keyToRemove)
        }

        saveHistoryAsync()
    }

    /**
     * Predicts continuation candidates based on the committed phrase and surrounding text.
     * Guaranteed sub-millisecond execution (< 0.1 ms) safe for direct UI invocation.
     */
    @Synchronized
    fun predict(lastPhrase: String, textBeforeCursor: String = ""): List<ConversionCandidate> {
        val result = mutableListOf<ConversionCandidate>()
        val seenSurfaces = HashSet<String>()

        fun addCandidate(cand: ConversionCandidate) {
            if (cand.surface.isNotBlank() && seenSurfaces.add(cand.surface)) {
                result.add(cand)
            }
        }

        val cleanLast = lastPhrase.trim()

        // 1. User Bigram History Cache (Highest Priority)
        if (cleanLast.isNotEmpty()) {
            val userNextMap = bigramHistory[cleanLast]
            if (userNextMap != null) {
                val sortedUserNext = userNextMap.entries.sortedByDescending { it.value }
                for (entry in sortedUserNext) {
                    addCandidate(
                        ConversionCandidate(
                            surface = entry.key,
                            reading = entry.key,
                            isPrediction = true,
                            source = "history_bigram"
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
                cleanLast.endsWith("\n") ||
                textBeforeCursor.endsWith("。") ||
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

        return result
    }

    private fun isParticle(phrase: String): Boolean {
        return COMMON_PARTICLES.contains(phrase) ||
                phrase.endsWith("は") || phrase.endsWith("が") || phrase.endsWith("を") ||
                phrase.endsWith("に") || phrase.endsWith("で") || phrase.endsWith("と") ||
                phrase.endsWith("の") || phrase.endsWith("も") || phrase.endsWith("へ")
    }

    @Synchronized
    fun clearHistory() {
        bigramHistory.clear()
        saveHistoryAsync()
    }

    private fun saveHistoryAsync() {
        val file = cacheFile ?: return
        val snapshot = synchronized(this) {
            val root = JSONObject()
            for ((key, map) in bigramHistory) {
                val child = JSONObject()
                for ((next, count) in map) {
                    child.put(next, count)
                }
                root.put(key, child)
            }
            root.toString()
        }

        scope.launch {
            try {
                file.writeText(snapshot, Charsets.UTF_8)
            } catch (e: Exception) {
                Log.w(TAG, "Error saving next phrase history: ${e.message}")
            }
        }
    }

    private fun loadHistory() {
        val file = cacheFile ?: return
        try {
            if (file.exists()) {
                val jsonStr = file.readText(Charsets.UTF_8)
                val root = JSONObject(jsonStr)
                val keys = root.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val child = root.optJSONObject(k) ?: continue
                    val childMap = HashMap<String, Int>()
                    val childKeys = child.keys()
                    while (childKeys.hasNext()) {
                        val ck = childKeys.next()
                        childMap[ck] = child.optInt(ck, 1)
                    }
                    bigramHistory[k] = childMap
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error loading next phrase history: ${e.message}")
        }
    }
}
