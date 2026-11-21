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

    data class CollocationEntry(
        val surface: String,
        val reading: String = surface
    )

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

        private val DEFAULT_SENTENCE_START_SEEDS = listOf(
            "お疲れ様です",
            "お世話になっております",
            "よろしくお願いいたします",
            "また、",
            "つきましては、",
            "なお、"
        )

        @Volatile
        private var instance: NextPhrasePredictor? = null

        fun getInstance(context: Context): NextPhrasePredictor {
            return instance ?: synchronized(this) {
                instance ?: NextPhrasePredictor(context.applicationContext).also { instance = it }
            }
        }

        internal val CANONICAL_STEM_READINGS: Map<String, String> = mapOf(
            "よろしく" to "よろしく",
            "お願い" to "おねがい",
            "ありがとう" to "ありがとう",
            "お疲れ様" to "おつかれさま",
            "お疲れ" to "おつかれ",
            "ご" to "ご",
            "確認" to "かくにん",
            "作業" to "さぎょう",
            "準備" to "じゅんび",
            "質問" to "しつもん",
            "了解" to "りょうかい",
            "承知" to "しょうち",
            "いつも" to "いつも",
            "お世話" to "おせわ",
            "お待たせ" to "おまたせ",
            "ご検討" to "ごけんとう",
            "検討" to "けんとう",
            "申し訳" to "もうしわけ",
            "大丈夫" to "だいじょうぶ",
            "問題" to "もんだい",
            "連絡" to "れんらく",
            "今日" to "きょう",
            "明日" to "あした",
            "昨日" to "きのう",
            "今" to "いま",
            "会議" to "かいぎ",
            "資料" to "しりょう",
            "良い" to "いい",
            "いただき" to "いただき",
            "させて" to "させて",
            "電話" to "でんわ",
            "伺い" to "うかがい",
            "行き" to "いき",
            "向かっ" to "むかっ",
            "思い" to "おもい",
            "思っ" to "おもっ",
            "知っ" to "しっ",
            "電車" to "でんしゃ",
            "予定" to "よてい",
            "体調" to "たいちょう",
            "お腹" to "おなか",
            "風邪" to "かぜ",
            "病院" to "びょういん",
            "メール" to "めーる",
            "時間" to "じかん",
            "天気" to "てんき",
            "調整" to "ちょうせい",
            "ご確認" to "ごかくにん",
            "ご報告" to "ごほうこく",
            "ご連絡" to "ごれんらく",
            "回答" to "かいとう",
            "相談" to "そうだん",
            "勉強" to "べんきょう",
            "よろし" to "よろし"
        )

        // Static canonical collocations for standard Japanese expressions and POS-aware transitions
        internal val CANONICAL_COLLOCATIONS: Map<String, List<CollocationEntry>> = mapOf(
            "よろしく" to listOf(
                CollocationEntry("お願いします", "おねがいします"),
                CollocationEntry("お願いいたします", "おねがいいたします"),
                CollocationEntry("お願い", "おねがい"),
                CollocationEntry("どうぞ", "どうぞ")
            ),
            "お願い" to listOf(
                CollocationEntry("します", "します"),
                CollocationEntry("いたします", "いたします"),
                CollocationEntry("申し上げます", "もうしあげます")
            ),
            "ありがとう" to listOf(
                CollocationEntry("ございます", "ございます"),
                CollocationEntry("ございました", "ございました")
            ),
            "お疲れ様" to listOf(
                CollocationEntry("です", "です"),
                CollocationEntry("でした", "でした")
            ),
            "お疲れ" to listOf(
                CollocationEntry("様", "さま"),
                CollocationEntry("様です", "さまです"),
                CollocationEntry("様でした", "さまでした")
            ),
            "ご" to listOf(
                CollocationEntry("検討", "けんとう"),
                CollocationEntry("連絡", "れんらく"),
                CollocationEntry("確認", "かくにん"),
                CollocationEntry("案内", "あんない")
            ),
            "確認" to listOf(
                CollocationEntry("しました", "しました"),
                CollocationEntry("いたします", "いたします"),
                CollocationEntry("お願いします", "おねがいします"),
                CollocationEntry("中", "ちゅう")
            ),
            "作業" to listOf(
                CollocationEntry("を進めます", "をすすめます"),
                CollocationEntry("中ですので", "ちゅうですので"),
                CollocationEntry("を", "を"),
                CollocationEntry("中", "ちゅう"),
                CollocationEntry("完了", "かんりょう"),
                CollocationEntry("内容", "ないよう")
            ),
            "準備" to listOf(
                CollocationEntry("が完了しました", "がかんりょうしました"),
                CollocationEntry("が", "が"),
                CollocationEntry("を", "を"),
                CollocationEntry("中", "ちゅう"),
                CollocationEntry("完了", "かんりょう"),
                CollocationEntry("が進んでいます", "がすすんでいます")
            ),
            "質問" to listOf(
                CollocationEntry("は", "は"),
                CollocationEntry("が", "が"),
                CollocationEntry("を", "を"),
                CollocationEntry("ありません", "ありません")
            ),
            "了解" to listOf(
                CollocationEntry("しました", "しました"),
                CollocationEntry("いたしました", "いたしました"),
                CollocationEntry("です", "です")
            ),
            "承知" to listOf(
                CollocationEntry("いたしました", "いたしました"),
                CollocationEntry("しました", "しました")
            ),
            "いつも" to listOf(
                CollocationEntry("お世話になっております", "おせわになっております"),
                CollocationEntry("お世話", "おせわ"),
                CollocationEntry("ありがとうございます", "ありがとうございます")
            ),
            "お世話" to listOf(
                CollocationEntry("になっております", "になっております"),
                CollocationEntry("になります", "になります"),
                CollocationEntry("に", "に")
            ),
            "お待たせ" to listOf(
                CollocationEntry("いたしました", "いたしました"),
                CollocationEntry("しました", "しました")
            ),
            "ご検討" to listOf(
                CollocationEntry("のほどよろしくお願いいたします", "のほどよろしくおねがいいたします"),
                CollocationEntry("いただけますと幸いです", "いただけますとさいわいです"),
                CollocationEntry("ください", "ください")
            ),
            "検討" to listOf(
                CollocationEntry("ください", "ください"),
                CollocationEntry("します", "します"),
                CollocationEntry("いたします", "いたします"),
                CollocationEntry("中", "ちゅう")
            ),
            "申し訳" to listOf(
                CollocationEntry("ありません", "ありません"),
                CollocationEntry("ございません", "ございません"),
                CollocationEntry("ない", "ない")
            ),
            "大丈夫" to listOf(
                CollocationEntry("です", "です"),
                CollocationEntry("ですか", "ですか"),
                CollocationEntry("でしょうか", "でしょうか")
            ),
            "問題" to listOf(
                CollocationEntry("ありません", "ありません"),
                CollocationEntry("ない", "ない"),
                CollocationEntry("ですか", "ですか")
            ),
            "連絡" to listOf(
                CollocationEntry("します", "します"),
                CollocationEntry("いたします", "いたします"),
                CollocationEntry("お待ちしております", "おまちしております")
            ),
            "今日" to listOf(
                CollocationEntry("は", "は"),
                CollocationEntry("の", "の"),
                CollocationEntry("中に", "ちゅうに"),
                CollocationEntry("も", "も")
            ),
            "明日" to listOf(
                CollocationEntry("は", "は"),
                CollocationEntry("の", "の"),
                CollocationEntry("までに", "までに"),
                CollocationEntry("も", "も")
            ),
            "昨日" to listOf(
                CollocationEntry("は", "は"),
                CollocationEntry("の", "の"),
                CollocationEntry("も", "も")
            ),
            "今" to listOf(
                CollocationEntry("から", "から"),
                CollocationEntry("は", "は"),
                CollocationEntry("のところ", "のところ")
            ),
            "会議" to listOf(
                CollocationEntry("に", "に"),
                CollocationEntry("に参加します", "にさんかします"),
                CollocationEntry("に参加する", "にさんかする"),
                CollocationEntry("を始めます", "をはじめます"),
                CollocationEntry("室を予約する", "しつをよやくする"),
                CollocationEntry("の", "の"),
                CollocationEntry("は", "は"),
                CollocationEntry("室", "しつ")
            ),
            "資料" to listOf(
                CollocationEntry("を", "を"),
                CollocationEntry("を送信しました", "をそうしんしました"),
                CollocationEntry("を添付しました", "をてんぷしました"),
                CollocationEntry("を確認する", "をかくにんする"),
                CollocationEntry("を作成する", "をさくせいする"),
                CollocationEntry("の", "の"),
                CollocationEntry("作成", "さくせい"),
                CollocationEntry("確認", "かくにん")
            ),
            "様" to listOf(
                CollocationEntry("です", "です"),
                CollocationEntry("でした", "でした"),
                CollocationEntry("に", "に")
            ),
            "良い" to listOf(
                CollocationEntry("天気", "てんき"),
                CollocationEntry("一日を", "いちにちを"),
                CollocationEntry("ですね", "ですね")
            ),
            "いただき" to listOf(
                CollocationEntry("ありがとうございます", "ありがとうございます"),
                CollocationEntry("ました", "ました")
            ),
            "させて" to listOf(
                CollocationEntry("いただきます", "いただきます"),
                CollocationEntry("いただきました", "いただきました")
            ),
            "電話" to listOf(
                CollocationEntry("します", "します"),
                CollocationEntry("いたします", "いたします"),
                CollocationEntry("番号", "ばんごう")
            ),
            "伺い" to listOf(
                CollocationEntry("ます", "ます"),
                CollocationEntry("ました", "ました")
            ),
            "行き" to listOf(
                CollocationEntry("ます", "ます"),
                CollocationEntry("ました", "ました")
            ),
            "向かっ" to listOf(
                CollocationEntry("ています", "ています"),
                CollocationEntry("ております", "ております")
            ),
            "思い" to listOf(
                CollocationEntry("ます", "ます"),
                CollocationEntry("ました", "ました")
            ),
            "思っ" to listOf(
                CollocationEntry("ています", "ています"),
                CollocationEntry("ているのに", "ているのに"),
                CollocationEntry("ております", "ております")
            ),
            "知っ" to listOf(
                CollocationEntry("ています", "ています"),
                CollocationEntry("ております", "ております")
            ),
            "を" to listOf(
                CollocationEntry("送信", "そうしん"),
                CollocationEntry("進めます", "すすめます"),
                CollocationEntry("お願いします", "おねがいします"),
                CollocationEntry("確認", "かくにん"),
                CollocationEntry("作成", "さくせい")
            ),
            "に" to listOf(
                CollocationEntry("参加", "さんか"),
                CollocationEntry("ついて", "ついて"),
                CollocationEntry("なります", "なります"),
                CollocationEntry("連絡", "れんらく"),
                CollocationEntry("向かって", "むかって")
            ),
            "が" to listOf(
                CollocationEntry("完了", "かんりょう"),
                CollocationEntry("必要", "ひつよう"),
                CollocationEntry("あります", "あります"),
                CollocationEntry("できます", "できます")
            ),
            "は" to listOf(
                CollocationEntry("ありますか", "ありますか"),
                CollocationEntry("大丈夫", "だいじょうぶ"),
                CollocationEntry("ありません", "ありません"),
                CollocationEntry("いかが", "いかが")
            ),
            "の" to listOf(
                CollocationEntry("会議", "かいぎ"),
                CollocationEntry("件", "けん"),
                CollocationEntry("予定", "よてい"),
                CollocationEntry("連絡", "れんらく"),
                CollocationEntry("内容", "ないよう")
            ),
            "電車" to listOf(
                CollocationEntry("に乗る", "にのる"),
                CollocationEntry("に乗っています", "にのっています"),
                CollocationEntry("が遅れている", "がおくれている")
            ),
            "予定" to listOf(
                CollocationEntry("を確認する", "をかくにんする"),
                CollocationEntry("を調整する", "をちょうせいする"),
                CollocationEntry("がある", "がある")
            ),
            "体調" to listOf(
                CollocationEntry("が悪い", "がわるい"),
                CollocationEntry("に気をつけて", "にきをつけて")
            ),
            "お腹" to listOf(
                CollocationEntry("が空いた", "がすいた"),
                CollocationEntry("が痛い", "がいたい")
            ),
            "風邪" to listOf(
                CollocationEntry("をひいた", "をひいた"),
                CollocationEntry("を引いた", "をひいた")
            ),
            "病院" to listOf(
                CollocationEntry("に行く", "にいく")
            ),
            "メール" to listOf(
                CollocationEntry("を送る", "をおくる"),
                CollocationEntry("を送信しました", "をそうしんしました"),
                CollocationEntry("を確認する", "をかくにんする")
            ),
            "時間" to listOf(
                CollocationEntry("がある", "がある"),
                CollocationEntry("がない", "がない"),
                CollocationEntry("に間に合う", "にまにあう")
            ),
            "天気" to listOf(
                CollocationEntry("が良いですね", "がいいですね"),
                CollocationEntry("が良い", "がいい"),
                CollocationEntry("が悪い", "がわるい")
            ),
            "調整" to listOf(
                CollocationEntry("させていただきます", "させていただきます"),
                CollocationEntry("します", "します"),
                CollocationEntry("いたしました", "いたしました"),
                CollocationEntry("中", "ちゅう")
            ),
            "ご確認" to listOf(
                CollocationEntry("のほどよろしくお願いいたします", "のほどよろしくおねがいいたします"),
                CollocationEntry("のほど", "のほど"),
                CollocationEntry("ください", "ください")
            ),
            "ご報告" to listOf(
                CollocationEntry("いたします", "いたします"),
                CollocationEntry("申し上げます", "もうしあげます")
            ),
            "ご連絡" to listOf(
                CollocationEntry("ください", "ください"),
                CollocationEntry("お待ちしております", "おまちしております"),
                CollocationEntry("いたします", "いたします")
            ),
            "回答" to listOf(
                CollocationEntry("いたしました", "いたしました"),
                CollocationEntry("しました", "しました")
            ),
            "相談" to listOf(
                CollocationEntry("に乗ってください", "にのってください"),
                CollocationEntry("があります", "があります")
            ),
            "勉強" to listOf(
                CollocationEntry("になります", "になります"),
                CollocationEntry("をする", "をする")
            ),
            "よろし" to listOf(
                CollocationEntry("ければ", "ければ")
            )
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
    private val bigramReadings = HashMap<String, String>()
    private var totalBigramPairs = 0
    private var lastCommittedContentPhrase: String = ""
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
    @JvmOverloads
    fun recordTransition(prevPhrase: String, currentPhrase: String, currentReading: String = currentPhrase) {
        ensureHistoryLoaded()
        val prev = prevPhrase.trim()
        val curr = currentPhrase.trim()
        if (prev.isEmpty() || curr.isEmpty() || prev == curr) return

        if (currentReading.isNotBlank() && currentReading != curr) {
            bigramReadings[curr] = currentReading.trim()
        }

        val nowMs = System.currentTimeMillis()
        incrementBigramCount(prev, curr, nowMs)

        // Track preceding content word even when the caller starts with (contentWord -> particle)
        val prevIsSentenceEnd = prev.endsWith("。") || prev.endsWith("！") || prev.endsWith("？") ||
            prev.endsWith(".") || prev.endsWith("!") || prev.endsWith("?") || prev.endsWith("\n")
        if (!isParticle(prev) && !prevIsSentenceEnd) {
            lastCommittedContentPhrase = prev
        }

        // Z2: Particle-Bridged 2nd-Order Skip-Bigram ("ContentWord|Particle" -> Predicate)
        if (isParticle(prev) && !isParticle(curr) && lastCommittedContentPhrase.isNotEmpty() && lastCommittedContentPhrase != curr) {
            val skipKey = "$lastCommittedContentPhrase|$prev"
            incrementBigramCount(skipKey, curr, nowMs)
        }

        if (curr.endsWith("。") || curr.endsWith("！") || curr.endsWith("？") ||
            curr.endsWith(".") || curr.endsWith("!") || curr.endsWith("?") || curr.endsWith("\n")
        ) {
            lastCommittedContentPhrase = ""
        } else if (!isParticle(curr)) {
            lastCommittedContentPhrase = curr
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

    private fun incrementBigramCount(prevKey: String, curr: String, nowMs: Long) {
        val nextMap = bigramHistory.getOrPut(prevKey) { LinkedHashMap(8, 0.75f, true) }
        val nextTsMap = bigramLastUsed.getOrPut(prevKey) { LinkedHashMap(8, 0.75f, true) }
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
    }

    /**
     * Predicts continuation candidates based on the committed phrase and surrounding text.
     * Guaranteed sub-millisecond execution (< 0.1 ms) safe for direct UI invocation.
     */
    @Synchronized
    @JvmOverloads
    fun predict(
        lastPhrase: String,
        textBeforeCursor: String = "",
        suggestPeriod: Boolean = true,
        committedRid: Int = 0,
        connectionMatrix: ConnectionMatrix? = null,
        surfaceToLid: ((String) -> Int)? = null
    ): List<ConversionCandidate> {
        val result = mutableListOf<ConversionCandidate>()
        val seenSurfaces = HashSet<String>()

        fun computeUnifiedDictCost(surface: String): Pair<Int, Int> {
            if (committedRid <= 0 || connectionMatrix == null || surfaceToLid == null) {
                return Pair(5000, 0)
            }
            val lid = surfaceToLid(surface)
            if (lid <= 0) return Pair(5000, 0)
            val connCost = connectionMatrix.getTransitionCost(committedRid, lid)
            return Pair((3500 + connCost).coerceIn(0, 15000), connCost)
        }

        fun addCandidate(cand: ConversionCandidate) {
            val trimmedSurface = cand.surface.trim { it.isWhitespace() || it == '\u3000' }
            if (trimmedSurface.isNotEmpty() && !seenSurfaces.contains(trimmedSurface)) {
                val isUserHist = cand.source == "history_bigram" || cand.source == "history_skip_bigram"
                val (unifiedCost, connCost) = computeUnifiedDictCost(trimmedSurface)
                if (!isUserHist && committedRid > 0 && connectionMatrix != null && surfaceToLid != null) {
                    val lid = surfaceToLid(trimmedSurface)
                    if (lid > 0 && connCost >= ConnectionMatrix.INVALID_COST) {
                        return
                    }
                }
                seenSurfaces.add(trimmedSurface)
                val normalizedCand = cand.copy(
                    surface = trimmedSurface,
                    dictCost = if (cand.dictCost != 5000) cand.dictCost else unifiedCost
                )
                result.add(normalizedCand)
            }
        }

        val cleanLast = if (lastPhrase.isNotBlank()) {
            lastPhrase.trim()
        } else if (textBeforeCursor.isNotEmpty()) {
            JapaneseKanaUtils.extractLastWordOrParticle(textBeforeCursor)
        } else {
            ""
        }

        val anchor = JapaneseKanaUtils.extractTrailingContentAndParticle(textBeforeCursor)
        val effectiveContentWord = anchor.contentWord ?: if (isParticle(cleanLast) && lastCommittedContentPhrase.isNotEmpty()) {
            lastCommittedContentPhrase
        } else {
            null
        }
        val effectiveSkipKey = if (effectiveContentWord != null && isParticle(cleanLast)) {
            "$effectiveContentWord|$cleanLast"
        } else {
            anchor.skipBigramKey
        }

        // 1. User Bigram History Cache (Highest Priority: 2nd-order Skip-Bigram -> 1st-order Bigram)
        if (effectiveSkipKey != null) {
            ensureHistoryLoaded()
            val skipNextMap = bigramHistory[effectiveSkipKey]
            if (skipNextMap != null) {
                val tsMap = bigramLastUsed[effectiveSkipKey]
                for (entry in skipNextMap.entries.sortedByDescending { it.value }) {
                    val entryReading = bigramReadings[entry.key] ?: entry.key
                    addCandidate(
                        ConversionCandidate(
                            surface = entry.key,
                            reading = entryReading,
                            isPrediction = true,
                            source = "history_skip_bigram",
                            historyCount = entry.value,
                            lastUsedAtMs = tsMap?.get(entry.key) ?: 0L
                        )
                    )
                }
            }
        }

        if (cleanLast.isNotEmpty()) {
            ensureHistoryLoaded()
            val userNextMap = bigramHistory[cleanLast]
            if (userNextMap != null) {
                val tsMap = bigramLastUsed[cleanLast]
                val sortedUserNext = userNextMap.entries.sortedByDescending { it.value }
                for (entry in sortedUserNext) {
                    val entryReading = bigramReadings[entry.key] ?: entry.key
                    addCandidate(
                        ConversionCandidate(
                            surface = entry.key,
                            reading = entryReading,
                            isPrediction = true,
                            source = "history_bigram",
                            historyCount = entry.value,
                            lastUsedAtMs = tsMap?.get(entry.key) ?: 0L
                        )
                    )
                }
            }
        }

        // 2. Canonical Collocation Dictionary (Particle-Prefix Projection -> 1st-order Collocations)
        if (effectiveContentWord != null && isParticle(cleanLast)) {
            val contentCollocs = CANONICAL_COLLOCATIONS[effectiveContentWord]
            if (contentCollocs != null) {
                for (entry in contentCollocs) {
                    if (entry.surface.startsWith(cleanLast) && entry.surface.length > cleanLast.length) {
                        val projSurface = entry.surface.removePrefix(cleanLast)
                        val projReading = if (entry.reading.startsWith(cleanLast) && entry.reading.length > cleanLast.length) {
                            entry.reading.removePrefix(cleanLast)
                        } else {
                            projSurface
                        }
                        addCandidate(
                            ConversionCandidate(
                                surface = projSurface,
                                reading = projReading,
                                isPrediction = true,
                                source = "canonical_projected"
                            )
                        )
                    }
                }
            }
        }

        if (cleanLast.isNotEmpty()) {
            val canonicalList = CANONICAL_COLLOCATIONS[cleanLast] ?: run {
                val trailing = JapaneseKanaUtils.extractLastWordOrParticle(cleanLast)
                if (trailing.isNotEmpty() && trailing != cleanLast) CANONICAL_COLLOCATIONS[trailing] else null
            }
            if (canonicalList != null) {
                for (entry in canonicalList) {
                    addCandidate(
                        ConversionCandidate(
                            surface = entry.surface,
                            reading = entry.reading,
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
            for (seed in DEFAULT_SENTENCE_START_SEEDS) {
                addCandidate(ConversionCandidate(seed, seed, true, "sentence_start_seed"))
            }
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

        // 4. Particle Continuation (sorted by Mozc ConnectionMatrix transition cost when available)
        // If lastPhrase does not already end in a particle or punctuation, suggest common particles
        if (cleanLast.isNotEmpty() && !isSentenceStart && !isParticle(cleanLast)) {
            val orderedParticles = if (committedRid > 0 && connectionMatrix != null && surfaceToLid != null) {
                COMMON_PARTICLES.withIndex().sortedBy { (idx, p) ->
                    val lid = surfaceToLid(p)
                    val cost = if (lid > 0) connectionMatrix.getTransitionCost(committedRid, lid) else 2500
                    cost * 100 + idx
                }.map { it.value }
            } else {
                COMMON_PARTICLES
            }
            for (p in orderedParticles) {
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
        bigramReadings.clear()
        totalBigramPairs = 0
        lastCommittedContentPhrase = ""
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
