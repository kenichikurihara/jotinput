package com.example.myasr.context.kanakanji.domain

import com.example.myasr.AsrApp
import com.example.myasr.context.ime.presentation.KeyboardHeightManager
import com.example.myasr.context.kanakanji.application.UserHistorySyncManager
import com.example.myasr.context.provisioning.infrastructure.ConnectionDataManager
import com.example.myasr.context.reranking.domain.RerankEngineType
import com.example.myasr.context.reranking.infrastructure.LlmReranker
import com.example.myasr.context.vocabulary.domain.DictionaryEntry
import com.example.myasr.context.vocabulary.domain.Priority

import android.content.Context
import android.util.Log
import android.util.LruCache
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream

/** Clause-start / topic context (affects B3 tail ordering); shared by cache key and chunk synthesis. */
internal fun isClauseStartOrTopicContext(contextPrefix: String): Boolean {
    val last = contextPrefix.trimEnd().lastOrNull()
    return last == null || last in listOf('、', '。', '！', '？', 'は', 'が', 'も', '\n')
}

/**
 * Stem round-robin: among phrase_chunk entries carrying a stem, the first occurrence of each stem
 * comes first, then the remaining ones (stable). Other entries keep their positions.
 */
internal fun diversifyByFirstStem(cands: List<ConversionCandidate>): List<ConversionCandidate> {
    val isChunk = { c: ConversionCandidate -> c.source == "phrase_chunk" && c.firstBunsetsuSurface.isNotEmpty() }
    val chunks = cands.filter(isChunk)
    if (chunks.size < 2) return cands.toList()
    val seenStems = HashSet<String>()
    val firsts = ArrayList<ConversionCandidate>()
    val rest = ArrayList<ConversionCandidate>()
    for (c in chunks) {
        if (seenStems.add(c.firstBunsetsuSurface)) firsts.add(c) else rest.add(c)
    }
    val reordered = (firsts + rest).iterator()
    return cands.map { if (isChunk(it)) reordered.next() else it }
}

data class ConversionCandidate(
    val surface: String,
    val reading: String,
    val isPrediction: Boolean = false,
    val source: String = "dict",
    val priority: Float = 0.0f,
    val obsPenalty: Float = 0.0f,
    val dictCost: Int = 5000,
    val firstBunsetsuSurface: String = "",
    val lastContentWordSurface: String = "",
    val completedSurface: String = "",
    val suffixBranchingFactor: Float = 1.0f,
    val isPredicate: Boolean = false,
    val predicateTailSurface: String = "",
    val historyCount: Int = 0,
    val lastUsedAtMs: Long = 0L
)

class KanaKanjiEngine internal constructor(private val context: Context? = null) {

    private val TAG = "KanaKanjiEngine"

    private data class CanonicalWord(
        val reading: String,
        val surface: String,
        val lid: Int = 0,
        val rid: Int = 0
    )

    private data class WordEntry(
        val surface: String,
        val cost: Int,
        val source: String,
        val lid: Int = 0,
        val rid: Int = 0,
        val priority: Float = 0.0f
    )

    private class TrieNode {
        val children = HashMap<Char, TrieNode>()
        val words = ArrayList<WordEntry>()
        var minCostInSubtree: Int = Int.MAX_VALUE
    }

    private val root = TrieNode()
    private val surfaceToRidMap = HashMap<String, Int>(16384)
    private val surfaceToLidMap = HashMap<String, Int>(16384)
    private val surfaceToReadingMap = HashMap<String, String>(16384)
    // In-memory access-ordered LinkedHashMap to guarantee consistent JVM/Android LRU behavior and null-safety
    private val historyMap = LinkedHashMap<String, Int>(256, 0.75f, true)
    private val historyLastUsedMap = LinkedHashMap<String, Long>(256, 0.75f, true)
    private val queryCache = LinkedHashMap<String, List<ConversionCandidate>>(300, 0.75f, true)
    private val historyFile = context?.let { File(it.filesDir, "ime_history.json") }
    private val historyTimestampFile = context?.let { File(it.filesDir, "ime_history_timestamps.json") }

    private val ioScope = CoroutineScope(Dispatchers.IO)
    private var saveHistoryJob: Job? = null
    private val historyLock = Any()
    private val historyLoadLock = Any()

    @Volatile
    private var isHistoryLoaded = false

    @Volatile
    private var connectionMatrix: ConnectionMatrix? = null

    var predictor: NextPhrasePredictor? = null

    data class MmapEntry(val sourceId: String, val dict: MmapDictionary)
    private val activeMmapDictionaries = java.util.concurrent.CopyOnWriteArrayList<MmapEntry>()

    @Volatile
    private var mmapDictionary: MmapDictionary? = null

    @Volatile
    private var isInitialized = false

    init {
        if (historyFile != null) {
            ioScope.launch {
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

    fun setConnectionMatrix(matrix: ConnectionMatrix?) {
        this.connectionMatrix = matrix
        clearMemoryCache()
        Log.i(TAG, "ConnectionMatrix updated (active: ${matrix != null})")
    }

    fun getConnectionMatrix(): ConnectionMatrix? = connectionMatrix

    fun lookupSurfaceRid(surface: String): Int {
        surfaceToRidMap[surface]?.let { if (it > 0) return it }
        for (len in minOf(surface.length - 1, 6) downTo 1) {
            val suffix = surface.substring(surface.length - len)
            surfaceToRidMap[suffix]?.let { if (it > 0) return it }
        }
        return 0
    }

    fun lookupLeadingLid(surface: String): Int {
        surfaceToLidMap[surface]?.let { if (it > 0) return it }
        for (len in minOf(surface.length - 1, 6) downTo 1) {
            val prefix = surface.substring(0, len)
            surfaceToLidMap[prefix]?.let { if (it > 0) return it }
        }
        return 0
    }

    /**
     * Infers the Hiragana reading for an SLM-generated surface string using exact lexicon lookup
     * with greedy longest-prefix morphological decomposition fallback.
     */
    fun inferReading(surface: String): String {
        if (surface.isEmpty()) return ""
        surfaceToReadingMap[surface]?.let { return it }
        if (JapaneseKanaUtils.isAllHiragana(surface)) return surface

        val sb = StringBuilder()
        var i = 0
        val n = surface.length
        while (i < n) {
            var matched = false
            val maxLen = minOf(n - i, 8)
            for (len in maxLen downTo 1) {
                val sub = surface.substring(i, i + len)
                val subReading = surfaceToReadingMap[sub]
                if (subReading != null) {
                    sb.append(subReading)
                    i += len
                    matched = true
                    break
                }
            }
            if (!matched) {
                val ch = surface[i].toString()
                sb.append(JapaneseKanaUtils.normalizeReading(ch))
                i++
            }
        }
        return sb.toString()
    }

    fun setMmapDictionary(dict: MmapDictionary?) {
        this.mmapDictionary = dict
        activeMmapDictionaries.clear()
        if (dict != null) {
            activeMmapDictionaries.add(MmapEntry("mozc_general", dict))
        }
        clearMemoryCache()
        Log.i(TAG, "MmapDictionary updated (active: ${dict != null})")
    }

    fun getMmapDictionary(): MmapDictionary? = mmapDictionary

    fun getActiveMmapDictionaries(): List<MmapEntry> = activeMmapDictionaries

    internal fun attachMmapDictionaryForTesting(sourceId: String, dict: MmapDictionary) {
        activeMmapDictionaries.add(MmapEntry(sourceId, dict))
        if (mmapDictionary == null || sourceId == "mozc_general") {
            mmapDictionary = dict
        }
        isInitialized = true
        clearMemoryCache()
    }

    fun getMozcDir(): File {
        val ext = context?.getExternalFilesDir(null)
        val extDir = if (ext != null) File(ext, "mozc") else null
        if (extDir != null && (extDir.exists() || extDir.mkdirs())) return extDir
        val internalDir = File(context?.filesDir, "mozc")
        if (!internalDir.exists()) internalDir.mkdirs()
        return internalDir
    }

    @Synchronized
    fun reloadMmapDictionaries() {
        for (entry in activeMmapDictionaries) {
            try { entry.dict.close() } catch (_: Exception) {}
        }
        activeMmapDictionaries.clear()

        val mozcDir = getMozcDir()
        val binFiles = mozcDir.listFiles { f -> f.name.endsWith(".bin") } ?: emptyArray()
        for (f in binFiles) {
            if (f.length() > 64L && f.name != "connection_data.bin") {
                try {
                    val mmap = MmapDictionary.loadFromFile(f)
                    if (mmap != null) {
                        val sourceId = when {
                            f.name.startsWith("dict_") -> f.name.removePrefix("dict_").removeSuffix(".bin")
                            f.name == "system_dictionary.bin" -> "mozc_general"
                            else -> f.name.removeSuffix(".bin")
                        }
                        activeMmapDictionaries.add(MmapEntry(sourceId, mmap))
                        Log.i(TAG, "Loaded MmapDictionary '$sourceId' from ${f.name} (${mmap.numTokens} tokens)")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed loading mmap dict ${f.name}: ${e.message}")
                }
            }
        }

        mmapDictionary = activeMmapDictionaries.firstOrNull { it.sourceId == "mozc_general" }?.dict
            ?: activeMmapDictionaries.firstOrNull()?.dict

        clearMemoryCache()
    }

    companion object {
        const val MAX_HISTORY_WORDS = 10_000

        @Volatile
        private var instance: KanaKanjiEngine? = null

        fun getInstance(context: Context): KanaKanjiEngine {
            return instance ?: synchronized(this) {
                instance ?: KanaKanjiEngine(context.applicationContext).also {
                    it.predictor = NextPhrasePredictor.getInstance(context.applicationContext)
                    instance = it
                }
            }
        }

        private val DYNAMIC_SAHEN_TAILS = listOf(
            NextPhrasePredictor.CollocationEntry("します", "します"),
            NextPhrasePredictor.CollocationEntry("しました", "しました"),
            NextPhrasePredictor.CollocationEntry("しています", "しています"),
            NextPhrasePredictor.CollocationEntry("いたします", "いたします"),
            NextPhrasePredictor.CollocationEntry("中ですので", "ちゅうですので"),
            NextPhrasePredictor.CollocationEntry("中", "ちゅう"),
            NextPhrasePredictor.CollocationEntry("お願いします", "おねがいします"),
            NextPhrasePredictor.CollocationEntry("予定です", "よていです")
        )

        private val DYNAMIC_VERB_TAILS = listOf(
            NextPhrasePredictor.CollocationEntry("ています", "ています"),
            NextPhrasePredictor.CollocationEntry("ているのに", "ているのに"),
            NextPhrasePredictor.CollocationEntry("ております", "ております"),
            NextPhrasePredictor.CollocationEntry("ます", "ます"),
            NextPhrasePredictor.CollocationEntry("ました", "ました"),
            NextPhrasePredictor.CollocationEntry("てください", "てください")
        )

        private val DYNAMIC_NOUN_TAILS = listOf(
            NextPhrasePredictor.CollocationEntry("です", "です"),
            NextPhrasePredictor.CollocationEntry("でした", "でした"),
            NextPhrasePredictor.CollocationEntry("ですので", "ですので"),
            NextPhrasePredictor.CollocationEntry("ですが", "ですが"),
            NextPhrasePredictor.CollocationEntry("でしょうか", "でしょうか"),
            NextPhrasePredictor.CollocationEntry("中", "ちゅう")
        )
    }

    @Synchronized
    fun initializeIfNeeded() {
        if (isInitialized) return
        try {
            // 0. Load Mozc Bigram Connection Cost Matrix if installed
            if (connectionMatrix == null && context != null) {
                try {
                    connectionMatrix = ConnectionDataManager.getInstance(context).getConnectionMatrix()
                } catch (e: Exception) {
                    Log.w(TAG, "ConnectionMatrix load skipped: ${e.message}")
                }
            }

            // 1. Load Mmap Dictionaries from Storage (files/mozc/*.bin or system_dictionary.bin)
            reloadMmapDictionaries()

            var baseLoaded = activeMmapDictionaries.isNotEmpty()

            // Only fallback to heavy TSV heap parsing if no zero-heap binary dictionary is present
            if (!baseLoaded) {
                val storageFile = getBaseDictionaryFile()
                if (storageFile.exists() && storageFile.length() > 0) {
                    try {
                        storageFile.inputStream().use { inputStream ->
                            loadTsv(inputStream, "base")
                            baseLoaded = true
                            Log.i(TAG, "Base dictionary loaded from storage: ${storageFile.absolutePath} (${storageFile.length()} bytes)")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to load base dictionary from storage: ${e.message}")
                    }
                }
            }
            if (!baseLoaded) {
                try {
                    context?.assets?.open("mozc/base_dictionary.tsv")?.use { inputStream ->
                        loadTsv(inputStream, "base")
                        baseLoaded = true
                        Log.i(TAG, "Base dictionary loaded from assets fallback.")
                    }
                } catch (e: Exception) {
                    Log.i(TAG, "Base dictionary not found in storage or assets. Can be downloaded on demand.")
                }
            }

            // 2. Register high-priority grammatical particles and common conversational vocabulary
            registerCanonicalVocabulary()

            // 3. Load User Custom Dictionary (highest priority)
            try {
                val userDict = AsrApp.instance.dictionaryRepository.getActive()
                syncUserDictionary(userDict)
            } catch (e: Exception) {
                Log.w(TAG, "User dictionary load skipped or unavailable: ${e.message}")
            }

            isInitialized = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize KanaKanjiEngine: ${e.message}", e)
        }
    }

    /**
     * Non-blocking or background warm-up: ensures base dictionaries, history, and user dictionaries
     * are loaded and performs sample queries (exact + multi-bunsetsu Viterbi) to prime Trie paths,
     * ConnectionMatrix pages, and query caches.
     */
    fun warmUp() {
        ensureHistoryLoaded()
        initializeIfNeeded()
        try {
            getBaseCandidates("きょう")
            getBaseCandidates("きょうはいいてんき")
            Log.i(TAG, "KanaKanjiEngine warm-up completed successfully.")
        } catch (e: Exception) {
            Log.w(TAG, "KanaKanjiEngine warm-up query failed: ${e.message}")
        }
    }

    /**
     * Testing / offline initialization method to load a dictionary stream directly without Android Context.
     */
    @Synchronized
    fun initializeWithStream(inputStream: InputStream) {
        loadTsv(inputStream, "base")
        registerCanonicalVocabulary()
        isInitialized = true
    }

    fun getSystemDictionaryBinFile(): java.io.File {
        val ext = context?.getExternalFilesDir(null)
        if (ext != null) {
            val f = java.io.File(ext, "mozc/system_dictionary.bin")
            if (f.exists() && f.length() > 64L) return f
        }
        val internal = java.io.File(context?.filesDir, "mozc/system_dictionary.bin")
        if (internal.exists() && internal.length() > 64L) return internal
        return java.io.File(context?.filesDir, "mozc/system_dictionary.bin")
    }

    fun getBaseDictionaryFile(): java.io.File {
        val ext = context?.getExternalFilesDir(null)
        if (ext != null) {
            val f = java.io.File(ext, "mozc/base_dictionary.tsv")
            if (f.exists() && f.length() > 0) return f
        }
        val internal = java.io.File(context?.filesDir, "mozc/base_dictionary.tsv")
        if (internal.exists() && internal.length() > 0) return internal
        return java.io.File(context?.filesDir, "mozc/base_dictionary.tsv")
    }

    fun isBaseDictionaryDownloaded(): Boolean {
        if (activeMmapDictionaries.any { it.sourceId == "mozc_general" }) return true
        val dictMozc = File(getMozcDir(), "dict_mozc_general.bin")
        if (dictMozc.exists() && dictMozc.length() > 64L) return true
        val bin = getSystemDictionaryBinFile()
        if (bin.exists() && bin.length() > 64L) return true
        val f = getBaseDictionaryFile()
        return f.exists() && f.length() > 1024
    }

    internal fun registerCanonicalVocabulary() {
        val highPriority = listOf(
            // Primary functional particles with Mozc OSS POS IDs
            CanonicalWord("は", "は", 283, 283),
            CanonicalWord("が", "が", 369, 369),
            CanonicalWord("を", "を", 379, 379),
            CanonicalWord("に", "に", 372, 372),
            CanonicalWord("で", "で", 370, 370),
            CanonicalWord("と", "と", 371, 371),
            CanonicalWord("の", "の", 433, 433),
            CanonicalWord("も", "も", 284, 284),
            CanonicalWord("へ", "へ", 375, 375),
            CanonicalWord("か", "か", 325, 325),
            CanonicalWord("や", "や", 265, 265),
            CanonicalWord("よ", "よ", 430, 430),
            CanonicalWord("ね", "ね", 423, 423),
            CanonicalWord("わ", "わ", 431, 431),
            CanonicalWord("さ", "さ", 18, 18),
            CanonicalWord("ぞ", "ぞ", 281, 281),
            // Compound particles and conjunctions
            CanonicalWord("から", "から", 368, 368),
            CanonicalWord("まで", "まで", 314, 314),
            CanonicalWord("より", "より", 15, 15),
            CanonicalWord("ので", "ので", 359, 359),
            CanonicalWord("のに", "のに", 360, 360),
            CanonicalWord("なら", "なら", 117, 117),
            CanonicalWord("たら", "たら", 116, 116),
            CanonicalWord("ても", "ても", 33, 33),
            CanonicalWord("でも", "でも", 299, 299),
            CanonicalWord("けど", "けど", 334, 334),
            CanonicalWord("けれど", "けれど", 336, 336),
            CanonicalWord("けれども", "けれども", 337, 337),
            CanonicalWord("ながら", "ながら", 355, 355),
            CanonicalWord("たり", "たり", 114, 114),
            CanonicalWord("だり", "だり", 270, 270),
            CanonicalWord("ほど", "ほど", 313, 313),
            CanonicalWord("ばかり", "ばかり", 310, 310),
            CanonicalWord("だけ", "だけ", 296, 296),
            CanonicalWord("しか", "しか", 97, 97),
            CanonicalWord("くらい", "くらい", 289, 289),
            CanonicalWord("ぐらい", "ぐらい", 290, 290),
            CanonicalWord("など", "など", 302, 302),
            // Auxiliary verbs, inflections, and colloquial contractions
            CanonicalWord("です", "です", 172, 172),
            CanonicalWord("ます", "ます", 240, 240),
            CanonicalWord("でした", "でした", 172, 172),
            CanonicalWord("ました", "ました", 240, 240),
            CanonicalWord("ません", "ません", 659, 659),
            CanonicalWord("たい", "たい", 152, 152),
            CanonicalWord("たく", "たく", 160, 160),
            CanonicalWord("たかった", "たかった", 152, 152),
            CanonicalWord("ない", "ない", 204, 204),
            CanonicalWord("なかった", "なかった", 204, 204),
            CanonicalWord("だ", "だ", 165, 165),
            CanonicalWord("である", "である"),
            CanonicalWord("だった", "だった"),
            CanonicalWord("だろう", "だろう"),
            CanonicalWord("でしょう", "でしょう", 183, 35),
            CanonicalWord("て", "て", 348, 348),
            CanonicalWord("で", "で", 370, 370),
            CanonicalWord("ている", "ている", 348, 1084),
            CanonicalWord("てい", "てい", 348, 1084),
            CanonicalWord("てる", "てる", 1097, 1097),
            CanonicalWord("でる", "でる", 1099, 1099),
            CanonicalWord("た", "た", 142, 142),
            CanonicalWord("てお", "てお"),
            CanonicalWord("おり", "おり", 1680, 1680),
            CanonicalWord("おる", "おる", 1580, 1580),
            CanonicalWord("し", "し"),
            CanonicalWord("して", "して"),
            CanonicalWord("している", "している"),
            CanonicalWord("しています", "しています"),
            // Potential verb Ichidan ren'youkei stems with Mozc OSS POS IDs
            CanonicalWord("いけ", "行け", 701, 701),
            CanonicalWord("いけ", "いけ", 701, 701),
            CanonicalWord("かけ", "書け", 701, 701),
            CanonicalWord("のめ", "飲め", 701, 701),
            CanonicalWord("とれ", "取れ", 701, 701),
            CanonicalWord("よめ", "読め", 701, 701),
            CanonicalWord("はなせ", "話せ", 701, 701),
            // Conversational potential past & te-form phrases
            CanonicalWord("いけた", "行けた", 701, 142),
            CanonicalWord("いけて", "行けて", 701, 348),
            CanonicalWord("いけます", "行けます", 701, 240),
            CanonicalWord("いけました", "行けました", 701, 240)
        )
        for (item in highPriority) {
            insertWord(item.reading, item.surface, cost = 50, source = "canonical", lid = item.lid, rid = item.rid)
        }

        // Japanese and ASCII brackets derived from Mozc OSS symbol.tsv
        val openingBrackets = listOf(
            "（", "「", "『", "【", "（）", "「」", "『』", "【】",
            "［", "〔", "｛", "〈", "《", "［］", "〔〕", "｛｝", "〈〉", "《》",
            "‹", "«", "〘", "〚", "〝", "(", "[", "{", "<", "()", "[]", "{}", "<>"
        )
        for ((idx, surface) in openingBrackets.withIndex()) {
            val cost = 50 + idx * 5
            insertWord("（", surface, cost, "canonical")
            insertWord("(", surface, cost, "canonical")
        }

        val closingBrackets = listOf(
            "）", "」", "』", "】", "］", "〕", "｝", "〉", "》",
            "›", "»", "〙", "〛", "〟", ")", "]", "}", ">"
        )
        for ((idx, surface) in closingBrackets.withIndex()) {
            val cost = 50 + idx * 5
            insertWord("）", surface, cost, "canonical")
            insertWord(")", surface, cost, "canonical")
        }

        val kakkoPairs = listOf(
            "（）", "「」", "『』", "【】", "［］", "〔〕", "｛｝", "〈〉", "《》",
            "()", "[]", "{}", "<>", "«»", "‹›", "〘〙", "〚〛", "〝〟", "‘’", "“”"
        )
        for ((idx, surface) in kakkoPairs.withIndex()) {
            val cost = 50 + idx * 5
            insertWord("かっこ", surface, cost, "canonical")
        }
    }

    @Synchronized
    fun reloadDictionaries() {
        isInitialized = false
        root.children.clear()
        root.words.clear()
        root.minCostInSubtree = Int.MAX_VALUE
        surfaceToRidMap.clear()
        surfaceToLidMap.clear()
        surfaceToReadingMap.clear()
        reloadMmapDictionaries()
        clearMemoryCache()
        initializeIfNeeded()
    }

    fun clearMemoryCache() {
        synchronized(queryCache) {
            queryCache.clear()
        }
    }

    @Synchronized
    fun syncUserDictionary(entries: List<DictionaryEntry>) {
        removeUserWords(root)
        for (entry in entries) {
            if (entry.isEnabled && entry.isJapaneseEntry && entry.reading.isNotBlank() && entry.word.isNotBlank()) {
                val normalizedReading = JapaneseKanaUtils.normalizeReading(entry.reading)
                val cost = when (entry.priority) {
                    Priority.HIGH -> 0
                    Priority.NORMAL -> 10
                    Priority.LOW -> 50
                }
                val priorityScore = entry.priority.score
                insertWord(normalizedReading, entry.word, cost = cost, source = "user", lid = 1851, rid = 1851, priority = priorityScore)
            }
        }
        clearMemoryCache()
    }

    private fun removeUserWords(node: TrieNode) {
        node.words.removeAll { it.source == "user" }
        for (child in node.children.values) {
            removeUserWords(child)
        }
    }

    internal fun loadTsv(inputStream: InputStream, source: String) {
        val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8), 32768)
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            val trimmed = line!!.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val parts = trimmed.split("\t")
            if (parts.size >= 5) {
                val reading = parts[0]
                val lid = parts[1].toIntOrNull() ?: 0
                val rid = parts[2].toIntOrNull() ?: 0
                val mozcCost = parts[3].toIntOrNull() ?: 8000
                val surface = parts[4]
                insertWord(reading, surface, mozcCost, source, lid, rid)
            } else if (parts.size >= 2) {
                val reading = parts[0]
                val surface = parts[1]
                val cost = if (parts.size >= 3) parts[2].toIntOrNull() ?: 100 else 100
                insertWord(reading, surface, cost, source)
            }
        }
    }

    internal fun insertWord(
        reading: String,
        surface: String,
        cost: Int,
        source: String,
        lid: Int = 0,
        rid: Int = 0,
        priority: Float = 0.0f
    ) {
        if (rid > 0 && !surfaceToRidMap.containsKey(surface)) {
            surfaceToRidMap[surface] = rid
        }
        if (lid > 0 && !surfaceToLidMap.containsKey(surface)) {
            surfaceToLidMap[surface] = lid
        }
        if (reading.isNotEmpty() && !surfaceToReadingMap.containsKey(surface)) {
            surfaceToReadingMap[surface] = reading
        }
        var node = root
        if (cost < node.minCostInSubtree) node.minCostInSubtree = cost
        for (ch in reading) {
            node = node.children.getOrPut(ch) { TrieNode() }
            if (cost < node.minCostInSubtree) node.minCostInSubtree = cost
        }
        val existingIndex = node.words.indexOfFirst {
            it.surface == surface && (lid == 0 || it.lid == 0 || it.lid == lid) && (rid == 0 || it.rid == 0 || it.rid == rid)
        }
        if (existingIndex >= 0) {
            val existing = node.words[existingIndex]
            val effectiveLid = if (lid > 0) lid else existing.lid
            val effectiveRid = if (rid > 0) rid else existing.rid
            val effectiveCost = minOf(cost, existing.cost)
            val effectiveSource = if (source == "user" || existing.source == "user") "user" else source
            val effectivePriority = if (priority > 0f) priority else existing.priority
            if (cost <= existing.cost || source == "user") {
                node.words[existingIndex] = WordEntry(surface, effectiveCost, effectiveSource, effectiveLid, effectiveRid, effectivePriority)
            } else if (effectiveLid != existing.lid || effectiveRid != existing.rid) {
                node.words[existingIndex] = WordEntry(surface, existing.cost, existing.source, effectiveLid, effectiveRid, effectivePriority)
            }
        } else {
            node.words.add(WordEntry(surface, cost, source, lid, rid, priority))
        }
        node.words.sortWith { a, b ->
            if (a.cost != b.cost) {
                a.cost.compareTo(b.cost)
            } else {
                val aUser = if (a.source == "user") 0 else 1
                val bUser = if (b.source == "user") 0 else 1
                aUser.compareTo(bUser)
            }
        }
    }

    /**
     * Generates a ranked list of candidates for a given kana reading and optional context prefix.
     */
    fun getCandidates(rawReading: String, contextPrefix: String = ""): List<ConversionCandidate> {
        val baseCandidates = getBaseCandidates(rawReading, contextPrefix)
        val ctx = context
        if (ctx != null && !KeyboardHeightManager.isSlmRerankEnabled(ctx)) {
            return baseCandidates
        }
        return rerankCandidates(contextPrefix, baseCandidates)
    }

    /**
     * Fast-path candidate retrieval (in-memory Trie + Mmap candidate query cache).
     * Safe to run synchronously on the UI thread (< 1ms).
     */
    @JvmOverloads
    fun getBaseCandidates(rawReading: String, contextPrefix: String = ""): List<ConversionCandidate> {
        if (rawReading.isBlank()) return emptyList()
        ensureHistoryLoaded()
        initializeIfNeeded()

        val normalized = JapaneseKanaUtils.normalizeReading(rawReading)
        val contextKey = if (contextPrefix.isNotEmpty()) JapaneseKanaUtils.extractLastWordOrParticle(contextPrefix) else ""
        val clauseMarker = if (isClauseStartOrTopicContext(contextPrefix)) "|c" else ""
        val cacheKey = if (contextKey.isEmpty()) "$normalized$clauseMarker" else "$normalized|$contextKey$clauseMarker"

        // Retrieve base candidates from queryCache or generate them
        val cached = synchronized(queryCache) { queryCache[cacheKey] }
        if (cached != null) return cached

        val result = mutableListOf<ConversionCandidate>()
        val seenSurfaces = HashSet<String>()

        fun addCandidate(cand: ConversionCandidate) {
            // All-hiragana candidates matching composing text exactly are excluded because
            // pressing the commit/enter button directly commits the un-converted Hiragana composing text.
            // Exceptions:
            // 1. Common case particles (e.g. "に", "を", "が", "は", "の"): Single-char grammatical particles
            //    must be preserved and selectable in the candidate strip.
            // 2. Mistype flick typo corrections (source == "flick_correction").
            // 3. Predictive candidates (isPrediction == true).
            val isParticleExact = JapaneseKanaUtils.isCommonParticle(cand.surface) && cand.surface == normalized
            val isRedundantHiragana = !isParticleExact &&
                    JapaneseKanaUtils.isAllHiragana(cand.surface) &&
                    !cand.isPrediction &&
                    cand.source != "flick_correction" &&
                    cand.surface == normalized
            if (cand.surface.isNotBlank() && !isRedundantHiragana) {
                if (seenSurfaces.add(cand.surface)) {
                    result.add(cand)
                } else if (cand.priority > 0f) {
                    val existing = result.firstOrNull { it.surface == cand.surface }
                    if (existing != null && cand.priority > existing.priority) {
                        val idx = result.indexOf(existing)
                        result[idx] = existing.copy(priority = cand.priority, source = cand.source)
                    }
                }
            }
        }

        // 0. Context-aware Collocation Prefix Completions (Promoted to Rank 1 for predictive typing)
        if (contextKey.isNotEmpty()) {
            val pred = predictor ?: (context?.let { NextPhrasePredictor.getInstance(it) })
            if (pred != null) {
                val nextPredictions = pred.predict(contextKey, textBeforeCursor = contextPrefix, suggestPeriod = false)
                for (cand in nextPredictions) {
                    val candReading = JapaneseKanaUtils.normalizeReading(cand.reading)
                    if (candReading.startsWith(normalized) && cand.surface != normalized) {
                        val collocPriority = if (cand.source == "canonical_projected") 4.5f else 5.0f
                        addCandidate(
                            cand.copy(
                                isPrediction = true,
                                source = "context_collocation",
                                priority = collocPriority
                            )
                        )
                    }
                }
                // Keep direct 1st-order context collocations (5.0f) ahead of projected multi-word continuations (4.5f)
                result.sortByDescending { it.priority }
            }
        }

            // 1. User dictionary entries (Absolute priority)
            val matchingPaths = findMatchingTrieNodes(normalized)
            val exactPath = matchingPaths.firstOrNull { it.voicingDistance == 0 && it.typoCostPenalty == 0 }
            val exactNode = exactPath?.node ?: run {
                var node: TrieNode? = root
                for (ch in normalized) {
                    node = node?.children?.get(ch)
                    if (node == null) break
                }
                node
            }
            val exactTrieWords = exactNode?.words?.toList() ?: emptyList()
            val userMatches = exactTrieWords.filter { it.source == "user" }
            for (w in userMatches) {
                addCandidate(ConversionCandidate(surface = w.surface, reading = normalized, isPrediction = false, source = "user", priority = w.priority))
            }

            // 1.5. Dynamic Arabic Numeral Candidate Generation (e.g. "ごきろ" -> "5キロ", "にせんにじゅうろくねん" -> "2026年")
            val numeralCandidates = JapaneseNumeralUtils.generateNumeralCandidates(normalized)
            for (numCand in numeralCandidates) {
                addCandidate(numCand)
            }

            // 2. Base in-memory Trie & MmapDictionary exact matches
            val contextRid = if (contextKey.isNotEmpty()) surfaceToRidMap[contextKey] ?: 0 else 0
            val matrix = connectionMatrix
            val connRid = if (contextRid > 0) contextRid else 0
            val boundaryChars = "とはがをにでものかねよ"

            fun singleViterbiCost(rawCost: Int, lid: Int, rid: Int, source: String): Int {
                val spanLen = normalized.length
                val isUserOrCanon = source == "user" || source == "canonical"
                val bonus = if (spanLen > 1 && !isUserOrCanon) {
                    val conf = ((8500 - rawCost).toFloat() / 4000.0f).coerceIn(0.15f, 1.0f)
                    val touchesTrailingParticle = normalized.last() in boundaryChars
                    val isRiskyOverSpan = (isProperNoun(lid) && rawCost > 4500) || rawCost > 5200
                    val effectiveConf = if (touchesTrailingParticle && isRiskyOverSpan) minOf(conf, 0.25f) else conf
                    ((spanLen - 1) * 2000 * effectiveConf).toInt()
                } else if (spanLen > 1) {
                    (spanLen - 1) * 2000
                } else {
                    0
                }
                val rarityPen = if (isUserOrCanon) 0 else maxOf(0, rawCost - 6200) / 2
                val transCost = if (matrix != null && lid > 0) matrix.getTransitionCost(connRid, lid) else 0
                val eosCost = if (matrix != null && rid > 0) matrix.getTransitionCost(rid, 0) else 0
                return rawCost - bonus + transCost + rarityPen + eosCost
            }

            val step2StartIndex = result.size
            val step2ViterbiCosts = HashMap<String, Int>()

            fun recordStep2Cost(surface: String, vCost: Int) {
                val prev = step2ViterbiCosts[surface]
                if (prev == null || vCost < prev) {
                    step2ViterbiCosts[surface] = vCost
                }
            }

            val mmapTokens = queryAllMmapExactTokens(normalized).sortedBy { tok ->
                singleViterbiCost(tok.cost, tok.lid, tok.rid, tok.source)
            }
            for (tok in mmapTokens) {
                val cand = ConversionCandidate(
                    surface = tok.surface,
                    reading = normalized,
                    isPrediction = false,
                    source = tok.source,
                    dictCost = tok.cost
                )
                val vCost = singleViterbiCost(tok.cost, tok.lid, tok.rid, tok.source)
                val prevSize = result.size
                addCandidate(cand)
                if (result.size > prevSize) {
                    recordStep2Cost(tok.surface, vCost)
                }
            }

            val otherExact = exactTrieWords.filter { it.source != "user" }.sortedWith { a, b ->
                val histA = synchronized(historyLock) { historyMap["${normalized}:${a.surface}"] ?: 0 }
                val histB = synchronized(historyLock) { historyMap["${normalized}:${b.surface}"] ?: 0 }
                if (histA != histB) {
                    histB.compareTo(histA)
                } else {
                    singleViterbiCost(a.cost, a.lid, a.rid, a.source).compareTo(
                        singleViterbiCost(b.cost, b.lid, b.rid, b.source)
                    )
                }
            }
            for (w in otherExact) {
                val cand = ConversionCandidate(
                    surface = w.surface,
                    reading = normalized,
                    isPrediction = false,
                    source = w.source,
                    dictCost = w.cost
                )
                val vCost = singleViterbiCost(w.cost, w.lid, w.rid, w.source)
                val prevSize = result.size
                addCandidate(cand)
                if (result.size > prevSize) {
                    recordStep2Cost(w.surface, vCost)
                }
            }

            val step2EndIndex = result.size

            // 3.6. Voiced & Semi-voiced Exact Matches (e.g. "かいき" -> "会議" [かいぎ], "こはん" -> "ご飯" [ごはん])
            val voicedPaths = matchingPaths.filter { it.voicingDistance > 0 && it.typoCostPenalty == 0 }
            if (voicedPaths.isNotEmpty()) {
                val voicedExactWords = mutableListOf<Triple<String, WordEntry, Int>>()
                for (vPath in voicedPaths) {
                    val vNode = vPath.node ?: continue
                    for (w in vNode.words) {
                        voicedExactWords.add(Triple(vPath.reading, w, vPath.voicingDistance))
                    }
                }
                voicedExactWords.sortWith { a, b ->
                    val histA = synchronized(historyLock) { historyMap["${a.first}:${a.second.surface}"] ?: 0 }
                    val histB = synchronized(historyLock) { historyMap["${b.first}:${b.second.surface}"] ?: 0 }
                    if (histA != histB) {
                        histB.compareTo(histA)
                    } else {
                        val costA = a.second.cost + a.third * 1200
                        val costB = b.second.cost + b.third * 1200
                        costA.compareTo(costB)
                    }
                }
                for ((reading, entry, vDist) in voicedExactWords) {
                    addCandidate(ConversionCandidate(surface = entry.surface, reading = reading, isPrediction = true, source = entry.source, priority = entry.priority, obsPenalty = 1.2f * vDist, dictCost = entry.cost))
                }
                // Query Mmap exact matches for top voiced readings (up to 4 readings)
                val sortedExactVoicedPaths = voicedPaths.sortedBy { it.voicingDistance * 1200 + (it.node?.minCostInSubtree ?: 3000) }
                for (vPath in sortedExactVoicedPaths.take(4)) {
                    val mmapVoicedExact = queryAllMmapExactTokens(vPath.reading)
                    for (tok in mmapVoicedExact) {
                        addCandidate(ConversionCandidate(surface = tok.surface, reading = vPath.reading, isPrediction = true, source = tok.source, obsPenalty = 1.2f * vPath.voicingDistance, dictCost = tok.cost))
                    }
                }
            }

            // 3.8. Voiced, Semi-voiced & Small-Kana Predictive Prefix Matches (e.g. "とうきよ" -> "東京", "すーは" -> "スーパー", "は" -> "パン", "場合")
            // Evaluated before Viterbi so natural unvoiced prefixes of single words (like "とうきよ" -> "東京", "すーは" -> "スーパー")
            // take precedence over artificial multi-word Viterbi chunk splits (like "冬季よ", "すー葉").
            if (voicedPaths.isNotEmpty()) {
                val voicedPrefixWords = mutableListOf<Triple<String, WordEntry, Int>>()
                // Sort paths by modifier distance and subtree minimum cost to guarantee high-frequency paths come first
                val sortedVoicedPaths = voicedPaths.sortedBy { it.voicingDistance * 1200 + (it.node?.minCostInSubtree ?: 3000) }
                for (vPath in sortedVoicedPaths) {
                    val vNode = vPath.node ?: continue
                    val prefixWords = mutableListOf<Pair<String, WordEntry>>()
                    collectPrefixWords(vNode, vPath.reading, prefixWords, maxCount = 80)
                    for ((fullReading, w) in prefixWords) {
                        voicedPrefixWords.add(Triple(fullReading, w, vPath.voicingDistance))
                    }
                }
                voicedPrefixWords.sortWith { a, b ->
                    val histA = synchronized(historyLock) { historyMap["${a.first}:${a.second.surface}"] ?: 0 }
                    val histB = synchronized(historyLock) { historyMap["${b.first}:${b.second.surface}"] ?: 0 }
                    if (histA != histB) {
                        histB.compareTo(histA)
                    } else {
                        val costA = a.second.cost + a.third * 1200
                        val costB = b.second.cost + b.third * 1200
                        costA.compareTo(costB)
                    }
                }
                for ((reading, entry, vDist) in voicedPrefixWords) {
                    addCandidate(ConversionCandidate(surface = entry.surface, reading = reading, isPrediction = true, source = entry.source, priority = entry.priority, obsPenalty = 1.2f * vDist, dictCost = entry.cost))
                }
                // Query Mmap predictive prefix matches for top modified readings (up to 4 readings)
                for (vPath in sortedVoicedPaths.take(4)) {
                    val mmapVoicedPrefix = queryAllMmapPrefixTokens(vPath.reading, maxCount = 12)
                    for (cand in mmapVoicedPrefix) {
                        addCandidate(ConversionCandidate(surface = cand.surface, reading = cand.reading, isPrediction = true, source = cand.source, obsPenalty = 1.2f * vPath.voicingDistance, dictCost = cand.dictCost))
                    }

                    // Phonetic 1-Mora Lookahead (長音・撥音・促音先読み):
                    // In Japanese phonotactics, youon (e.g. "きょ", "しょ", "りょ") are predominantly followed by "う" or "ん".
                    // Look up exact matches for reading + "う" / "ん" directly in Mmap dictionaries (O(log N)).
                    if (vPath.reading.isNotEmpty() && vPath.reading.last() in listOf('ゃ', 'ゅ', 'ょ', 'ぁ', 'ぃ', 'ぅ', 'ぇ', 'ぉ')) {
                        for (lookaheadMora in listOf("う", "ん")) {
                            val fastReading = vPath.reading + lookaheadMora
                            val exactTokens = queryAllMmapExactTokens(fastReading)
                            for (tok in exactTokens) {
                                addCandidate(
                                    ConversionCandidate(
                                        surface = tok.surface,
                                        reading = fastReading,
                                        isPrediction = true,
                                        source = tok.source,
                                        obsPenalty = 1.2f * vPath.voicingDistance,
                                        dictCost = tok.cost
                                    )
                                )
                            }
                        }
                    }
                }
            }

            // 4. Multi-bunsetsu Viterbi Lattice Search (for continuous phrase conversion)
            // Evaluated BEFORE regular prefix matches so exact multi-bunsetsu phrases
            // (e.g. "いえの" -> "家の", "しってる" -> "知ってる", "でません" -> "出ません")
            // are prioritized over long predictive extensions (e.g. "家の外", "家の中").
            // Beam width is set to 12 to ensure natural inflected verb/particle phrases are never pruned.
            if (normalized.length >= 3) {
                val (viterbiCandidates, viterbiFullCosts) = searchViterbiLatticeWithCosts(
                    normalized,
                    beamWidth = 12,
                    initialRid = connRid
                )
                if (step2EndIndex > step2StartIndex) {
                    // Merge competitive full-span Viterbi phrases (e.g. "朝の", "家の") with Step 2 exact-reading
                    // single-token matches by Viterbi effectiveCost so high-frequency multi-bunsetsu
                    // phrases are not buried behind homophonic proper nouns (e.g. "浅野", "朝乃", "麻野", "家野").
                    val bestStep2Cost = step2ViterbiCosts.values.minOrNull() ?: Int.MAX_VALUE
                    val hasVoicedExpansion = voicedPaths.isNotEmpty()
                    val mergedFull = result.subList(step2StartIndex, step2EndIndex).toMutableList()
                    val remainingViterbi = mutableListOf<ConversionCandidate>()
                    for (cand in viterbiCandidates) {
                        val vCost = viterbiFullCosts[cand.surface] ?: Int.MAX_VALUE
                        val allowPromotion = !hasVoicedExpansion || vCost <= bestStep2Cost + 2500
                        if (!cand.isPrediction && cand.source != "viterbi_prefix" && allowPromotion) {
                            val isParticleExact = JapaneseKanaUtils.isCommonParticle(cand.surface) && cand.surface == normalized
                            val isRedundantHiragana = !isParticleExact &&
                                JapaneseKanaUtils.isAllHiragana(cand.surface) &&
                                !cand.isPrediction &&
                                cand.surface == normalized
                            if (cand.surface.isNotBlank() && !isRedundantHiragana && seenSurfaces.add(cand.surface)) {
                                mergedFull.add(cand)
                                recordStep2Cost(cand.surface, vCost)
                            }
                        } else {
                            remainingViterbi.add(cand)
                        }
                    }
                    mergedFull.sortBy { step2ViterbiCosts[it.surface] ?: Int.MAX_VALUE }
                    val before = result.subList(0, step2StartIndex).toList()
                    val after = result.subList(step2EndIndex, result.size).toList()
                    result.clear()
                    result.addAll(before)
                    result.addAll(mergedFull)
                    result.addAll(after)
                    for (cand in remainingViterbi) {
                        addCandidate(cand)
                    }
                } else {
                    for (cand in viterbiCandidates) {
                        addCandidate(cand)
                    }
                }
            }

            // 4.5. Flick Typo Exact Matches (e.g. "わたさ" -> "私" [わたし], "わたき" -> "私" [わたし])
            // Evaluated after Viterbi so exact multi-bunsetsu phrases (like "いえの" -> "家の")
            // are never shadowed by typo interpretations (like "いえな" -> "家な").
            val flickPaths = matchingPaths.filter { it.typoCostPenalty > 0 }
            if (flickPaths.isNotEmpty()) {
                val flickExactWords = mutableListOf<Triple<String, WordEntry, Int>>()
                val validFlickReadings = LinkedHashSet<Pair<String, Int>>()
                for (fPath in flickPaths) {
                    val fWords = fPath.node?.words ?: emptyList()
                    val mmapWords = if (fWords.isEmpty()) queryAllMmapExactTokens(fPath.reading) else emptyList()
                    val combined = if (fWords.isNotEmpty()) fWords else mmapWords
                    if (fPath.reading.isNotBlank() && fPath.reading != normalized && combined.isNotEmpty()) {
                        validFlickReadings.add(Pair(fPath.reading, fPath.typoCostPenalty))
                    }
                    for (w in combined) {
                        flickExactWords.add(Triple(fPath.reading, w, fPath.typoCostPenalty))
                    }
                }
                flickExactWords.sortWith { a, b ->
                    val histA = synchronized(historyLock) { historyMap["${a.first}:${a.second.surface}"] ?: 0 }
                    val histB = synchronized(historyLock) { historyMap["${b.first}:${b.second.surface}"] ?: 0 }
                    if (histA != histB) {
                        histB.compareTo(histA)
                    } else {
                        val costA = a.second.cost + a.third
                        val costB = b.second.cost + b.third
                        costA.compareTo(costB)
                    }
                }
                for ((reading, entry, penalty) in flickExactWords.take(8)) {
                    addCandidate(ConversionCandidate(surface = entry.surface, reading = reading, isPrediction = true, source = entry.source, priority = entry.priority, obsPenalty = penalty.toFloat() / 1000.0f, dictCost = entry.cost))
                }

                // If the typo-corrected reading represents a valid word in the lexicon,
                // offer the corrected Hiragana surface itself (e.g. "わたさ" -> "わたし")
                for ((flickReading, penalty) in validFlickReadings.take(3)) {
                    addCandidate(
                        ConversionCandidate(
                            surface = flickReading,
                            reading = flickReading,
                            isPrediction = true,
                            source = "flick_correction",
                            obsPenalty = penalty.toFloat() / 1000.0f,
                            dictCost = 4000
                        )
                    )
                }
            }

            // 5. Predictive prefix matches from in-memory Trie (scans cost-priority subtree up to 80, takes top 40 by cost + Mozc connection cost)
            val prefixWords = mutableListOf<Pair<String, WordEntry>>()
            if (exactNode != null) {
                collectPrefixWords(exactNode, normalized, prefixWords, maxCount = 80)
                prefixWords.sortBy { (_, entry) ->
                    val connCost = if (matrix != null && contextRid > 0 && entry.lid > 0) matrix.getTransitionCost(contextRid, entry.lid) else 0
                    entry.cost + connCost
                }
                for ((reading, entry) in prefixWords.take(40)) {
                    addCandidate(ConversionCandidate(surface = entry.surface, reading = reading, isPrediction = true, source = entry.source, dictCost = entry.cost))
                }
            }

            // 5.2. Predictive prefix matches from Mmap dictionaries
            val mmapPrefixes = queryAllMmapPrefixTokens(normalized, maxCount = 10)
            for (cand in mmapPrefixes) {
                addCandidate(cand)
            }

            // 5.5 Compound Multi-Bunsetsu Chunk Prediction (B3: Dynamic Multi-Bunsetsu Tail Rollout)
            if (normalized.length in 2..8) {
                val synthesizedChunks = synthesizeDynamicMultiBunsetsuChunks(
                    normalized = normalized,
                    exactWords = userMatches + otherExact,
                    prefixWords = prefixWords,
                    contextRid = contextRid,
                    contextPrefix = contextPrefix
                )
                for (cand in synthesizedChunks) {
                    addCandidate(cand)
                }
            }

            // 6. Katakana conversion
            val katakana = JapaneseKanaUtils.hiraganaToKatakana(normalized)
            addCandidate(ConversionCandidate(surface = katakana, reading = normalized, isPrediction = false, source = "system"))

            // 7. Half-width Katakana
            val halfWidth = JapaneseKanaUtils.toHalfWidthKatakana(normalized)
            addCandidate(ConversionCandidate(surface = halfWidth, reading = normalized, isPrediction = false, source = "system"))

            // Sort non-user exact matches by selection history
            val ranked = prioritizeByHistory(result, targetReading = normalized)
            val finalCandidates = interleavePredictiveCandidates(ranked, normalized)
            synchronized(queryCache) {
                if (queryCache.size >= 300) {
                    val oldestKey = queryCache.keys.firstOrNull()
                    if (oldestKey != null) queryCache.remove(oldestKey)
                }
                queryCache[cacheKey] = finalCandidates
            }
            return finalCandidates
    }

    private fun interleavePredictiveCandidates(
        candidates: List<ConversionCandidate>,
        normalized: String
    ): List<ConversionCandidate> {
        // Step 3 predictive interleaving only targets short prefix typing (2..4 characters).
        // For long phrases (5+ characters), preserve full Viterbi multi-prefix and 2D diversity ranking.
        if (normalized.length !in 2..4 || candidates.size <= 2) {
            return candidates
        }

        val userCands = candidates.filter { it.source == "user" }
        val collocCands = candidates.filter { it.source == "context_collocation" }
        val rest = candidates.filter { it.source != "user" && it.source != "context_collocation" }

        // Current-input candidates: exact matches, voicing/typo corrections of current input length
        val currentCands = rest.filter { it.source != "phrase_chunk" && it.reading.length <= normalized.length }.toMutableList()
        // Future predictive candidates: multi-bunsetsu phrase chunks and prefix completions
        val futurePredCands = rest.filter { it.source == "phrase_chunk" || it.reading.length > normalized.length }.toMutableList()

        if (futurePredCands.isEmpty() || currentCands.isEmpty()) {
            return candidates
        }

        // Prioritize history, intentional phrase chunks (priority >= 3.0f), and then low-cost/short-extension predictions
        futurePredCands.sortWith { a, b ->
            val histCmp = b.historyCount.compareTo(a.historyCount)
            if (histCmp != 0) return@sortWith histCmp
            val priA = if (a.priority >= 3.0f) a.priority else 0.0f
            val priB = if (b.priority >= 3.0f) b.priority else 0.0f
            val priCmp = priB.compareTo(priA)
            if (priCmp != 0) return@sortWith priCmp
            val scoreA = a.dictCost + (a.obsPenalty * 1500.0f).toInt() + maxOf(0, a.reading.length - normalized.length) * 600
            val scoreB = b.dictCost + (b.obsPenalty * 1500.0f).toInt() + maxOf(0, b.reading.length - normalized.length) * 600
            scoreA.compareTo(scoreB)
        }
        // R3: preserve stem round-robin diversity after the sort above
        val diversifiedFuture = diversifyByFirstStem(futurePredCands).toList()
        futurePredCands.clear()
        futurePredCands.addAll(diversifiedFuture)

        val interleaved = mutableListOf<ConversionCandidate>()
        val seen = HashSet<String>()

        fun add(c: ConversionCandidate) {
            if (seen.add(c.surface)) interleaved.add(c)
        }

        for (c in userCands) add(c)
        for (c in collocCands) add(c)

        // Slot 1 (if no user/colloc candidate): Always preserve the natural #1 ranked candidate
        if (interleaved.isEmpty()) {
            val topCandidate = candidates.first()
            add(topCandidate)
            currentCands.remove(topCandidate)
            futurePredCands.remove(topCandidate)
        }

        var curIdx = 0
        var predIdx = 0

        // Slot 2: Top future predictive candidate / compound phrase chunk
        if (predIdx < futurePredCands.size) {
            add(futurePredCands[predIdx++])
        }

        // Slot 3: Second candidate for current typed input
        if (curIdx < currentCands.size) {
            add(currentCands[curIdx++])
        }

        // Slot 4: Second future predictive candidate
        if (predIdx < futurePredCands.size) {
            add(futurePredCands[predIdx++])
        }

        // Rest of current-input candidates
        while (curIdx < currentCands.size) {
            add(currentCands[curIdx++])
        }

        // Rest of future predictive candidates
        while (predIdx < futurePredCands.size) {
            add(futurePredCands[predIdx++])
        }

        return interleaved
    }

    /**
     * Tries fast synchronous candidate scoring directly on Main Thread (< 0.1ms) using cached context logits.
     * Returns FastRerankResult if cache hit, or null if full background decode is required.
     */
    fun tryFastRerankCandidatesWithStatus(
        contextPrefix: String,
        baseCandidates: List<ConversionCandidate>,
        currentShownTop1: String? = null,
        targetInputLength: Int? = null
    ): LlmReranker.FastRerankResult? {
        val firstReading = baseCandidates.firstOrNull()?.reading
        val filtered = baseCandidates.filter {
            val isParticleExact = JapaneseKanaUtils.isCommonParticle(it.surface) && (firstReading != null && it.surface == firstReading)
            val isRedundantHiragana = !isParticleExact && !it.isPrediction && JapaneseKanaUtils.isAllHiragana(it.surface) && (firstReading != null && it.surface == firstReading)
            !isRedundantHiragana
        }
        if (filtered.size <= 1) {
            return null
        }
        val ctx = context ?: return null
        if (!KeyboardHeightManager.isSlmRerankEnabled(ctx)) {
            return null
        }
        if (contextPrefix.isBlank() && (firstReading == "（" || firstReading == "）" || firstReading == "(" || firstReading == ")")) {
            return null
        }
        val engineType = KeyboardHeightManager.getRerankEngineType(ctx)
        if (engineType == RerankEngineType.NONE) {
            return null
        }

        val alpha = KeyboardHeightManager.getSlmWeightAlpha(ctx)
        val lambdaObs = KeyboardHeightManager.getSlmLambdaObs(ctx)
        val gammaUser = KeyboardHeightManager.getSlmGammaUser(ctx)
        val effectiveAlpha = alpha
        val effectiveTargetLength = targetInputLength
            ?: filtered.filter { !it.isPrediction && it.source != "viterbi_prefix" }.maxOfOrNull { it.reading.length }
            ?: firstReading?.length

        return LlmReranker.getInstance(ctx).tryFastRerankWithStatus(
            contextPrefix = contextPrefix,
            candidates = filtered,
            currentShownTop1 = currentShownTop1,
            weightAlpha = effectiveAlpha,
            lambdaObs = lambdaObs,
            gammaUser = gammaUser,
            targetInputLength = effectiveTargetLength
        )
    }

    /**
     * Contextual candidate re-ranking using on-device SLM (Qwen2.5 / Qwen3.5).
     * Designed to be executed asynchronously on Dispatchers.Default.
     * Returns a pair of (reranked candidates, whether scoring was successfully applied).
     */
    fun rerankCandidatesWithStatus(
        contextPrefix: String,
        baseCandidates: List<ConversionCandidate>,
        currentShownTop1: String? = null,
        targetInputLength: Int? = null
    ): Pair<List<ConversionCandidate>, Boolean> {
        val firstReading = baseCandidates.firstOrNull()?.reading
        val filtered = baseCandidates.filter {
            val isParticleExact = JapaneseKanaUtils.isCommonParticle(it.surface) && (firstReading != null && it.surface == firstReading)
            val isRedundantHiragana = !isParticleExact && !it.isPrediction && JapaneseKanaUtils.isAllHiragana(it.surface) && (firstReading != null && it.surface == firstReading)
            !isRedundantHiragana
        }
        if (filtered.size <= 1) {
            return Pair(filtered, false)
        }
        val ctx = context ?: return Pair(filtered, false)
        if (!KeyboardHeightManager.isSlmRerankEnabled(ctx)) {
            return Pair(filtered, false)
        }
        if (contextPrefix.isBlank() && (firstReading == "（" || firstReading == "）" || firstReading == "(" || firstReading == ")")) {
            return Pair(filtered, false)
        }
        val alpha = KeyboardHeightManager.getSlmWeightAlpha(ctx)
        val lambdaObs = KeyboardHeightManager.getSlmLambdaObs(ctx)
        val gammaUser = KeyboardHeightManager.getSlmGammaUser(ctx)
        val engineType = KeyboardHeightManager.getRerankEngineType(ctx)
        val effectiveTargetLength = targetInputLength
            ?: filtered.filter { !it.isPrediction && it.source != "viterbi_prefix" }.maxOfOrNull { it.reading.length }
            ?: firstReading?.length

        return when (engineType) {
            RerankEngineType.NONE -> Pair(filtered, false)
            else -> {
                LlmReranker.getInstance(ctx).rerankWithStatus(
                    contextPrefix,
                    filtered,
                    weightAlpha = alpha,
                    lambdaObs = lambdaObs,
                    gammaUser = gammaUser,
                    currentShownTop1 = currentShownTop1,
                    targetInputLength = effectiveTargetLength
                )
            }
        }
    }

    fun rerankCandidates(contextPrefix: String, baseCandidates: List<ConversionCandidate>): List<ConversionCandidate> {
        return rerankCandidatesWithStatus(contextPrefix, baseCandidates).first
    }

    private fun prioritizeByHistory(
        candidates: List<ConversionCandidate>,
        targetReading: String = ""
    ): List<ConversionCandidate> {
        val enriched = synchronized(historyLock) {
            candidates.map { cand ->
                val key = "${cand.reading}:${cand.surface}"
                val count = historyMap[key] ?: 0
                val ts = historyLastUsedMap[key] ?: 0L
                if (count > 0 || ts > 0L) cand.copy(historyCount = count, lastUsedAtMs = ts) else cand
            }
        }
        val userCandidates = enriched.filter { it.source == "user" }
        val nonUserCandidates = enriched.filter { it.source != "user" }

        val sortedNonUser = nonUserCandidates.sortedWith { a, b ->
            val aIsColloc = a.source == "context_collocation"
            val bIsColloc = b.source == "context_collocation"
            if (aIsColloc != bIsColloc) {
                if (aIsColloc) -1 else 1
            } else if (aIsColloc && bIsColloc) {
                b.historyCount.compareTo(a.historyCount)
            } else {
                val aIsFull = targetReading.isEmpty() || (a.reading == targetReading && !a.isPrediction && a.source != "viterbi_prefix")
                val bIsFull = targetReading.isEmpty() || (b.reading == targetReading && !b.isPrediction && b.source != "viterbi_prefix")
                val histA = if (aIsFull) a.historyCount else 0
                val histB = if (bIsFull) b.historyCount else 0
                histB.compareTo(histA)
            }
        }

        return userCandidates + sortedNonUser
    }

    private data class MatchedTrieNode(
        val node: TrieNode?,
        val reading: String,
        val voicingDistance: Int,
        val typoCostPenalty: Int = 0
    )

    private fun hasAnyMmapPrefix(prefix: String): Boolean {
        val dicts = activeMmapDictionaries
        if (dicts.isEmpty()) return false
        for (entry in dicts) {
            if (entry.dict.hasAnyPrefix(prefix)) return true
        }
        return false
    }

    /**
     * Traverses both the in-memory Trie and Mmap dictionaries (via O(log N) prefix oracle) with dynamic branching for:
     * 1. Voiced (濁音), semi-voiced (半濁音), and small-kana (小書き仮名) expansions.
     * 2. Flick typing error expansions (incomplete flick / "a"-row fallback and adjacent 12-keys).
     * Pruning is performed on-the-fly: non-existent branches in the Japanese lexicon are immediately discarded.
     */
    private fun findMatchingTrieNodes(normalized: String, maxVoicingDistance: Int = 2): List<MatchedTrieNode> {
        if (normalized.isEmpty()) return emptyList()
        var currentPaths = listOf(MatchedTrieNode(root, "", 0, 0))

        for (i in normalized.indices) {
            val originalChar = normalized[i]
            val voicingExpansions = JapaneseKanaUtils.getPhoneticModifierExpansions(originalChar)
            val flickExpansions = JapaneseKanaUtils.getFlickTypoExpansions(originalChar)

            val nextPaths = ArrayList<MatchedTrieNode>(currentPaths.size * (voicingExpansions.size + flickExpansions.size))

            for (path in currentPaths) {
                // 1. Phonetic modifier expansions (including exact original char)
                for (candidateChar in voicingExpansions) {
                    val isVoicingChange = (candidateChar != originalChar)
                    val newDistance = path.voicingDistance + (if (isVoicingChange) 1 else 0)
                    if (newDistance > maxVoicingDistance) continue

                    val nextReading = path.reading + candidateChar
                    val childNode = path.node?.children?.get(candidateChar)
                    if (childNode != null || hasAnyMmapPrefix(nextReading)) {
                        nextPaths.add(MatchedTrieNode(childNode, nextReading, newDistance, path.typoCostPenalty))
                    }
                }

                // 2. Flick typing error expansions (only if path hasn't accumulated heavy penalties already)
                if (path.voicingDistance == 0 && path.typoCostPenalty == 0) {
                    for (flick in flickExpansions) {
                        val nextReading = path.reading + flick.char
                        val childNode = path.node?.children?.get(flick.char)
                        if (childNode != null || hasAnyMmapPrefix(nextReading)) {
                            nextPaths.add(MatchedTrieNode(childNode, nextReading, 0, flick.costPenalty))
                        }
                    }
                }
            }
            if (nextPaths.isEmpty()) return emptyList()
            currentPaths = if (nextPaths.size > 28) {
                nextPaths.sortBy { it.voicingDistance * 1200 + it.typoCostPenalty + (it.node?.minCostInSubtree ?: 3000) }
                nextPaths.subList(0, 28)
            } else {
                nextPaths
            }
        }
        return currentPaths
    }

    private fun collectPrefixWords(
        startNode: TrieNode,
        baseReading: String,
        outList: MutableList<Pair<String, WordEntry>>,
        maxCount: Int
    ) {
        val queue = ArrayDeque<Pair<String, TrieNode>>()
        queue.add(Pair(baseReading, startNode))

        while (queue.isNotEmpty() && outList.size < maxCount) {
            val (currentReading, currentNode) = queue.removeFirst()

            if (currentNode !== startNode) {
                val countToAdd = minOf(3, currentNode.words.size)
                for (i in 0 until countToAdd) {
                    outList.add(Pair(currentReading, currentNode.words[i]))
                    if (outList.size >= maxCount) return
                }
            }

            val sortedChildren = currentNode.children.entries.sortedBy { it.value.minCostInSubtree }
            for ((ch, childNode) in sortedChildren) {
                queue.add(Pair(currentReading + ch, childNode))
            }
        }
    }

    /**
     * Records candidate selection in LRU history to prioritize frequent words.
     */
    fun commitCandidate(candidate: ConversionCandidate) {
        ensureHistoryLoaded()
        val key = "${candidate.reading}:${candidate.surface}"
        val nowMs = System.currentTimeMillis()
        synchronized(historyLock) {
            val currentCount = historyMap[key] ?: 0
            historyMap[key] = (currentCount + 1).coerceAtMost(100_000)
            historyLastUsedMap[key] = nowMs
            if (historyMap.size > MAX_HISTORY_WORDS) {
                val oldestKey = historyMap.keys.firstOrNull()
                if (oldestKey != null) {
                    historyMap.remove(oldestKey)
                    historyLastUsedMap.remove(oldestKey)
                }
            }
        }
        clearMemoryCache()
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
     * Returns an immutable snapshot copy of current Unigram history.
     */
    fun getHistorySnapshot(): Map<String, Int> {
        ensureHistoryLoaded()
        return synchronized(historyLock) {
            LinkedHashMap(historyMap)
        }
    }

    /**
     * Safely merges remote unigram history entries using idempotent 3-Way Delta Merge:
     * Delta_L(k) = max(0, L(k) - B(k))
     * M(k) = min(100_000, max(R(k), B(k)) + Delta_L(k))
     */
    fun mergeHistoryDelta(remoteHistory: Map<String, Int>, baseHistory: Map<String, Int>) {
        if (remoteHistory.isEmpty() && baseHistory.isEmpty()) return
        ensureHistoryLoaded()
        synchronized(historyLock) {
            val allKeys = LinkedHashSet<String>()
            allKeys.addAll(historyMap.keys)
            allKeys.addAll(remoteHistory.keys)
            allKeys.addAll(baseHistory.keys)

            for (key in allKeys) {
                if (key.isBlank()) continue
                val localCount = historyMap[key] ?: 0
                val baseCount = baseHistory[key] ?: 0
                val remoteCount = remoteHistory[key] ?: 0

                val deltaL = maxOf(0, localCount - baseCount)
                val baseOrRemote = maxOf(remoteCount, baseCount)
                val merged = (baseOrRemote + deltaL).coerceIn(0, 100_000)

                if (merged > 0) {
                    historyMap[key] = merged
                } else {
                    historyMap.remove(key)
                }
            }

            while (historyMap.size > MAX_HISTORY_WORDS) {
                val oldestKey = historyMap.keys.firstOrNull() ?: break
                historyMap.remove(oldestKey)
            }
        }
        clearMemoryCache()
        scheduleSaveHistory(immediate = true)
    }

    /**
     * Safely merges remote unigram history entries using idempotent Max-Merge (Fallback).
     */
    fun mergeHistory(remoteHistory: Map<String, Int>) {
        if (remoteHistory.isEmpty()) return
        ensureHistoryLoaded()
        synchronized(historyLock) {
            for ((key, remoteCount) in remoteHistory) {
                if (key.isBlank() || remoteCount <= 0) continue
                val localCount = historyMap[key] ?: 0
                val mergedCount = maxOf(localCount, remoteCount).coerceAtMost(100_000)
                historyMap[key] = mergedCount
            }
            while (historyMap.size > MAX_HISTORY_WORDS) {
                val oldestKey = historyMap.keys.firstOrNull() ?: break
                historyMap.remove(oldestKey)
            }
        }
        clearMemoryCache()
        scheduleSaveHistory(immediate = true)
    }

    /**
     * Clears all unigram user selection history.
     */
    fun clearHistory() {
        ensureHistoryLoaded()
        synchronized(historyLock) {
            historyMap.clear()
            historyLastUsedMap.clear()
        }
        clearMemoryCache()
        scheduleSaveHistory(immediate = true)
    }

    private fun scheduleSaveHistory(immediate: Boolean = false) {
        saveHistoryJob?.cancel()
        saveHistoryJob = ioScope.launch {
            if (!immediate) {
                delay(1200L) // 1.2s debounce to coalesce fast continuous typing commits
            }
            saveHistory()
        }
    }

    private fun loadHistory() {
        val file = historyFile ?: return
        try {
            if (file.exists()) {
                val jsonStr = file.readText(Charsets.UTF_8)
                val json = JSONObject(jsonStr)
                val keys = json.keys()
                synchronized(historyLock) {
                    historyMap.clear()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        historyMap[k] = json.optInt(k, 1)
                    }
                }
            }
            val tsFile = historyTimestampFile
            if (tsFile != null && tsFile.exists()) {
                val tsJson = JSONObject(tsFile.readText(Charsets.UTF_8))
                val tsKeys = tsJson.keys()
                synchronized(historyLock) {
                    historyLastUsedMap.clear()
                    while (tsKeys.hasNext()) {
                        val k = tsKeys.next()
                        val ts = tsJson.optLong(k, 0L)
                        if (ts > 0L) historyLastUsedMap[k] = ts
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error loading history: ${e.message}")
        }
    }

    private fun saveHistory() {
        val file = historyFile ?: return
        try {
            val json = JSONObject()
            val tsJson = JSONObject()
            val (snapshot, tsSnapshot) = synchronized(historyLock) {
                Pair(LinkedHashMap(historyMap), LinkedHashMap(historyLastUsedMap))
            }
            for ((k, v) in snapshot) {
                json.put(k, v)
            }
            for ((k, ts) in tsSnapshot) {
                if (snapshot.containsKey(k) && ts > 0L) {
                    tsJson.put(k, ts)
                }
            }
            val tempFile = File(file.parentFile, "${file.name}.tmp")
            tempFile.writeText(json.toString(), Charsets.UTF_8)
            if (!tempFile.renameTo(file)) {
                tempFile.copyTo(file, overwrite = true)
                tempFile.delete()
            }
            historyTimestampFile?.let { tsFile ->
                val tsTemp = File(tsFile.parentFile, "${tsFile.name}.tmp")
                tsTemp.writeText(tsJson.toString(), Charsets.UTF_8)
                if (!tsTemp.renameTo(tsFile)) {
                    tsTemp.copyTo(tsFile, overwrite = true)
                    tsTemp.delete()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error saving history: ${e.message}")
        }
    }

    private data class ViterbiPath(
        val cost: Int,
        val surface: String,
        val words: List<String>,
        val hasUserWord: Boolean = false,
        val lastRid: Int = 0,
        val firstBunsetsuSurface: String = "",
        val firstBunsetsuReadingLen: Int = 0,
        val isFirstBunsetsuClosed: Boolean = false,
        val lastContentWordSurface: String = "",
        val secondBunsetsuIsProper: Boolean = false,
        val maxWordCost: Int = 0,
        val wordCostSum: Int = 0,
        val wordCount: Int = 0,
        val excessTransCostSum: Int = 0,
        val pendingCoordKind: Int = 0
    )

    private fun isContentWord(lid: Int): Boolean {
        if (isSuffix(lid)) return false
        return (lid in 1841..2195) || (lid in 434..1840) || (lid in 2196..2590) || (lid in 12..28) || (lid in 2659..2671)
    }

    private fun isSuffix(lid: Int): Boolean {
        return lid == 2032 || lid == 1941 || lid == 1951 || lid == 1975 || (lid in 1941..2034)
    }

    private fun isFunctionalWord(lid: Int): Boolean {
        return lid in 29..433
    }

    private fun isAuxiliaryVerb(lid: Int): Boolean {
        return lid in 29..267
    }

    private fun isPredicatePos(posId: Int): Boolean {
        return (posId in 434..1840) || (posId in 2196..2590)
    }

    private fun isProperNoun(lid: Int): Boolean {
        return lid in 1917..1940
    }

    private fun queryAllMmapExactTokens(reading: String, includeEmojiSymbols: Boolean = true): List<WordEntry> {
        val dicts = activeMmapDictionaries
        if (dicts.isEmpty()) return emptyList()
        val result = ArrayList<WordEntry>(16)
        for (entry in dicts) {
            if (!includeEmojiSymbols && entry.sourceId == "emoji_symbols") continue
            val tokens = entry.dict.getExactTokens(reading) ?: continue
            for (tok in tokens) {
                val isLegacySingleKanji = entry.sourceId == "emoji_symbols" &&
                    tok.surface.length == 1 &&
                    tok.surface[0] in '\u4E00'..'\u9FFF'
                val effectiveCost = if (isLegacySingleKanji) maxOf(tok.cost, 7500) else tok.cost
                result.add(
                    WordEntry(
                        surface = tok.surface,
                        cost = effectiveCost,
                        source = entry.sourceId,
                        lid = tok.lid,
                        rid = tok.rid
                    )
                )
            }
        }
        return result
    }

    private fun queryAllMmapPrefixTokens(prefix: String, maxCount: Int = 10): List<ConversionCandidate> {
        val dicts = activeMmapDictionaries
        if (dicts.isEmpty()) return emptyList()
        val result = ArrayList<ConversionCandidate>()
        for (entry in dicts) {
            val tokens = entry.dict.getPrefixTokens(prefix, maxCount)
            for ((reading, token) in tokens) {
                result.add(
                    ConversionCandidate(
                        surface = token.surface,
                        reading = reading,
                        isPrediction = true,
                        source = entry.sourceId,
                        dictCost = token.cost
                    )
                )
            }
        }
        return result
    }

    /**
     * Stratifies candidate subwords by part-of-speech (POS) classes to guarantee that
     * verb/adjective inflections and functional particles are never crowded out by homophonic nouns
     * in Viterbi lattice beam search.
     */
    private fun selectPosStratifiedSubwords(
        candidates: List<WordEntry>,
        maxTotal: Int = 24
    ): List<WordEntry> {
        if (candidates.size <= maxTotal) {
            return candidates.sortedBy { it.cost }
        }

        val sorted = candidates.sortedBy { it.cost }
        val selected = ArrayList<WordEntry>(maxTotal)
        val overflow = ArrayList<WordEntry>()

        val posPairCounts = HashMap<Int, Int>()
        var generalNounCount = 0
        var properNounCount = 0
        var symbolCount = 0

        for (w in sorted) {
            val lid = w.lid
            val posKey = (lid shl 16) or (w.rid and 0xFFFF)
            val countForPos = posPairCounts.getOrDefault(posKey, 0)

            val keep = when {
                // Priority 0: User dictionary & canonical are always preserved
                w.source == "user" || w.source == "canonical" -> true

                // Priority 1: Particles / auxiliary verbs (29..433) -> up to 2 per POS pair
                isFunctionalWord(lid) -> countForPos < 2

                // Priority 2: Verbs (434..1840) & Adjectives (2196..2590) -> up to 2 per POS pair
                (lid in 434..1840) || (lid in 2196..2590) -> countForPos < 2

                // Priority 3: Proper nouns, personal/place names (1917..1940) -> up to 4 total, 2 per POS
                lid in 1917..1940 -> properNounCount < 4 && countForPos < 2

                // Priority 4: General nouns, suffixes, adverbs -> up to 8 total, 3 per POS
                isContentWord(lid) || isSuffix(lid) -> generalNounCount < 8 && countForPos < 3

                // Priority 5: Symbols / others -> up to 2
                else -> symbolCount < 2
            }

            if (keep) {
                selected.add(w)
                posPairCounts[posKey] = countForPos + 1
                when {
                    lid in 1917..1940 -> properNounCount++
                    isContentWord(lid) || isSuffix(lid) -> generalNounCount++
                    !isFunctionalWord(lid) && lid !in 434..1840 && lid !in 2196..2590 -> symbolCount++
                }
                if (selected.size >= maxTotal) break
            } else {
                overflow.add(w)
            }
        }

        if (selected.size < maxTotal && overflow.isNotEmpty()) {
            for (w in overflow) {
                selected.add(w)
                if (selected.size >= maxTotal) break
            }
        }

        return selected
    }

    /**
     * Fast in-memory N-best Viterbi lattice beam search over Trie subwords.
     * Evaluates in < 0.3ms synchronously on the UI thread for long continuous phrases.
     * Implements quotient-state stratified beam search (同値類層化) and multi-granularity (Prefix) interleaving.
     */
    private fun searchViterbiLattice(reading: String, beamWidth: Int = 6): List<ConversionCandidate> {
        return searchViterbiLatticeWithCosts(reading, beamWidth).first
    }

    private fun searchViterbiLatticeWithCosts(
        reading: String,
        beamWidth: Int = 6,
        initialRid: Int = 0
    ): Pair<List<ConversionCandidate>, Map<String, Int>> {
        val n = reading.length
        if (n < 3) return Pair(emptyList(), emptyMap())

        val beam = Array(n + 1) { mutableListOf<ViterbiPath>() }
        beam[0].add(ViterbiPath(0, "", emptyList(), false, lastRid = initialRid, firstBunsetsuSurface = "", firstBunsetsuReadingLen = 0, isFirstBunsetsuClosed = false, lastContentWordSurface = "", secondBunsetsuIsProper = false))

        val matrix = connectionMatrix

        for (i in 0 until n) {
            val currentPaths = beam[i]
            if (currentPaths.isEmpty()) continue

            for (path in currentPaths) {
                var node: TrieNode? = root
                var j = i
                while (j < n) {
                    val ch = reading[j]
                    node = node?.children?.get(ch)
                    j++

                    val subReading = reading.substring(i, j)
                    val subWords = mutableListOf<WordEntry>()
                    node?.words?.let { subWords.addAll(it) }

                    // Also query zero-heap mmap dictionaries for subReading
                    val mmapWords = queryAllMmapExactTokens(subReading, includeEmojiSymbols = false)
                    for (tok in mmapWords) {
                        if (subWords.none { it.surface == tok.surface && it.lid == tok.lid }) {
                            subWords.add(tok)
                        }
                    }

                    if (subWords.isNotEmpty()) {
                        val stratifiedWords = selectPosStratifiedSubwords(subWords, maxTotal = 24)
                        val boundaryChars = "とはがをにでものかねよ"
                        for (w in stratifiedWords) {
                            val spanLen = j - i
                            val isUser = w.source == "user"
                            val isCanonical = w.source == "canonical"

                            // Idea V1: Unigram Confidence-Gated Length Bonus & Functional-Boundary Over-Spanning Guard
                            val baseBonus = if (spanLen > 1 && !isUser && !isCanonical) {
                                val conf = ((8500 - w.cost).toFloat() / 4000.0f).coerceIn(0.15f, 1.0f)
                                val touchesLeadingParticle = i > 0 && !isFunctionalWord(path.lastRid) && subReading.first() in boundaryChars
                                val touchesTrailingParticle = subReading.last() in boundaryChars
                                val touchesParticleBoundary = touchesLeadingParticle || touchesTrailingParticle
                                val isRiskyOverSpan = (isProperNoun(w.lid) && w.cost > 4500) || w.cost > 5200
                                val effectiveConf = if (touchesParticleBoundary && isRiskyOverSpan) minOf(conf, 0.25f) else conf
                                ((spanLen - 1) * 2000 * effectiveConf).toInt()
                            } else if (spanLen > 1) {
                                (spanLen - 1) * 2000
                            } else {
                                0
                            }

                            // Idea A: Additive Bunsetsu-level Length Bonus Normalization for auxiliary verbs attaching to verb/adj stems
                            val predicateBunsetsuBonus = if (i > 0 && isAuxiliaryVerb(w.lid) && isPredicatePos(path.lastRid)) 2000 else 0
                            val lengthBonus = baseBonus + predicateBunsetsuBonus
                            val userBonus = if (isUser) 50000 else 0

                            // Morphological connection transition cost
                            val transitionCost = if (matrix != null) {
                                val lid = if (w.lid > 0) w.lid else 0
                                matrix.getTransitionCost(path.lastRid, lid)
                            } else {
                                // Fallback penalty for isolated single-character katakana or archaic particle traps when matrix is absent
                                val isSingleKatakana = w.surface.length == 1 && w.surface[0] in '\u30A0'..'\u30FF'
                                val isArchaicNo = w.lid == 385 && w.surface == "之"
                                if (isSingleKatakana || isArchaicNo) 3000 else 0
                            }

                            // Idea V2: Incremental Max-Morpheme Surprisal (L_inf) Hinge Penalty & Single Proper Noun Guard
                            val effectiveWordCost = if (isUser || isCanonical) 0 else w.cost.coerceAtLeast(0)
                            val newMaxWordCost = maxOf(path.maxWordCost, effectiveWordCost)
                            val prevRarityPen = maxOf(0, path.maxWordCost - 6200) / 2
                            val nextRarityPen = maxOf(0, newMaxWordCost - 6200) / 2
                            val rarityDelta = nextRarityPen - prevRarityPen

                            val properAfterParticlePen = if (i > 0 && isFunctionalWord(path.lastRid) && isProperNoun(w.lid) && w.cost > 5000) 1200 else 0

                            // Idea V2: Parallel Coordination Symmetry Bonus (e.g. "オン + と + オフ")
                            val isKatakanaWord = w.surface.isNotEmpty() && w.surface.all { it in '\u30A0'..'\u30FF' || it == 'ー' }
                            val isKanjiWord = !isKatakanaWord && w.surface.any { it in '\u4E00'..'\u9FFF' }
                            val isCoordParticle = (w.surface == "と" || w.surface == "や") && isFunctionalWord(w.lid)
                            val coordBonus = when {
                                path.pendingCoordKind == -1 && isKatakanaWord -> 1200
                                path.pendingCoordKind == -2 && isKanjiWord -> 600
                                else -> 0
                            }
                            val nextCoordKind = when {
                                isCoordParticle && path.pendingCoordKind > 0 -> -path.pendingCoordKind
                                isKatakanaWord && (isContentWord(w.lid) || isProperNoun(w.lid)) -> 1
                                isKanjiWord && isContentWord(w.lid) -> 2
                                else -> 0
                            }

                            val newCost = path.cost + (w.cost - lengthBonus).coerceAtLeast(-50000) - userBonus + transitionCost + rarityDelta + properAfterParticlePen - coordBonus
                            val nextRid = if (w.rid > 0) w.rid else path.lastRid

                            val nextWordCostSum = path.wordCostSum + if (isFunctionalWord(w.lid) || isCanonical) 0 else effectiveWordCost
                            val nextWordCount = path.wordCount + if (isFunctionalWord(w.lid) || isCanonical) 0 else 1
                            val nextExcessTrans = path.excessTransCostSum + maxOf(0, transitionCost - 1500)

                            // Update first bunsetsu metadata
                            val (nextFb, nextFbLen, nextClosed) = if (i == 0) {
                                Triple(w.surface, j, false)
                            } else if (!path.isFirstBunsetsuClosed) {
                                if (isSuffix(w.lid)) {
                                    Triple(path.firstBunsetsuSurface + w.surface, j, false)
                                } else if (isFunctionalWord(w.lid) || isContentWord(w.lid)) {
                                    Triple(path.firstBunsetsuSurface, path.firstBunsetsuReadingLen, true)
                                } else {
                                    Triple(path.firstBunsetsuSurface, path.firstBunsetsuReadingLen, path.isFirstBunsetsuClosed)
                                }
                            } else {
                                Triple(path.firstBunsetsuSurface, path.firstBunsetsuReadingLen, true)
                            }

                            val nextLastContent = if (isContentWord(w.lid) || (w.surface.length >= 2 && !JapaneseKanaUtils.isAllHiragana(w.surface))) {
                                w.surface
                            } else {
                                path.lastContentWordSurface
                            }

                            val nextSecondProper = if (i > 0 && !path.isFirstBunsetsuClosed && isContentWord(w.lid)) {
                                isProperNoun(w.lid)
                            } else {
                                path.secondBunsetsuIsProper
                            }

                            beam[j].add(
                                ViterbiPath(
                                    cost = newCost,
                                    surface = path.surface + w.surface,
                                    words = path.words + listOf(w.surface),
                                    hasUserWord = path.hasUserWord || isUser,
                                    lastRid = nextRid,
                                    firstBunsetsuSurface = nextFb,
                                    firstBunsetsuReadingLen = nextFbLen,
                                    isFirstBunsetsuClosed = nextClosed,
                                    lastContentWordSurface = nextLastContent,
                                    secondBunsetsuIsProper = nextSecondProper,
                                    maxWordCost = newMaxWordCost,
                                    wordCostSum = nextWordCostSum,
                                    wordCount = nextWordCount,
                                    excessTransCostSum = nextExcessTrans,
                                    pendingCoordKind = nextCoordKind
                                )
                            )
                        }
                    }
                }

                // Fallback single-character hiragana transition
                val singleChar = reading[i].toString()
                val fallbackCost = path.cost + 18000
                val fb = if (i > 0) path.firstBunsetsuSurface else singleChar
                val fbLen = if (i > 0) path.firstBunsetsuReadingLen else 1
                beam[i + 1].add(
                    ViterbiPath(
                        cost = fallbackCost,
                        surface = path.surface + singleChar,
                        words = path.words + listOf(singleChar),
                        hasUserWord = path.hasUserWord,
                        lastRid = path.lastRid,
                        firstBunsetsuSurface = fb,
                        firstBunsetsuReadingLen = fbLen,
                        isFirstBunsetsuClosed = true,
                        lastContentWordSurface = path.lastContentWordSurface,
                        secondBunsetsuIsProper = path.secondBunsetsuIsProper
                    )
                )
            }

            // Layered Beam Pruning:
            // Deduplicate exact surface + lastRid pairs to preserve beam capacity,
            // while preserving diverse content word stems and inflections.
            for (k in 1..n) {
                if (beam[k].size > beamWidth * 2) {
                    val byKey = LinkedHashMap<Pair<String, Int>, ViterbiPath>()
                    for (p in beam[k]) {
                        val key = Pair(p.surface, p.lastRid)
                        val existing = byKey[key]
                        if (existing == null || p.cost < existing.cost) {
                            byKey[key] = p
                        }
                    }
                    val sortedList = byKey.values.toMutableList()
                    sortedList.sortBy { it.cost }
                    beam[k] = if (sortedList.size > beamWidth * 2) sortedList.subList(0, beamWidth * 2).toMutableList() else sortedList
                }
            }
        }

        val finalPaths = beam[n]
        if (matrix != null) {
            finalPaths.sortBy { it.cost + matrix.getTransitionCost(it.lastRid, 0) }
        } else {
            finalPaths.sortBy { it.cost }
        }

        // 1. Bilateral 2D Diversity for Full-Sentence Candidates (m = n):
        // Penalize repetitive first bunsetsu stems AND repetitive last content words.
        // Idea B: Partition first bunsetsu diversity key by (firstBunsetsuSurface, secondBunsetsuIsProper)
        // so compound surnames (ジム池田) and predicate phrases (ジム行けた) do not penalize each other.
        // Idea V3: Pair Novelty Exemption & Outlier Guard so rare-morpheme chimeras do not lock out legitimate pairs.
        data class ScoredCandidate(val path: ViterbiPath, val effectiveCost: Int)
        val seenFullSurfaces = HashSet<String>()
        val seenPairs = HashSet<Pair<String, String>>()
        val firstStemCounts = HashMap<Pair<String, Boolean>, Int>()
        val lastContentCounts = HashMap<String, Int>()
        val scoredFullCandidates = mutableListOf<ScoredCandidate>()
        val bestFullCost = finalPaths.firstOrNull()?.cost ?: 0

        for (path in finalPaths) {
            if (path.surface.isNotBlank() &&
                path.surface != reading &&
                !JapaneseKanaUtils.isAllHiragana(path.surface) &&
                seenFullSurfaces.add(path.surface)) {
                val stem1 = if (path.firstBunsetsuSurface.isNotEmpty()) path.firstBunsetsuSurface else path.surface
                val diversityKey = Pair(stem1, path.secondBunsetsuIsProper)
                val stemLast = if (path.lastContentWordSurface.isNotEmpty()) path.lastContentWordSurface else path.surface

                val count1 = firstStemCounts.getOrDefault(diversityKey, 0)
                val countLast = lastContentCounts[stemLast] ?: 0
                val isNewPair = seenPairs.add(Pair(stem1, stemLast))
                val isHighQualityPath = (path.cost - bestFullCost <= 3000) && path.maxWordCost <= 6000

                // Outlier paths (> +3500 from best or containing ultra-rare morphemes > 6500) do not pollute stem counts
                if (path.cost - bestFullCost <= 3500 && path.maxWordCost <= 6500) {
                    firstStemCounts[diversityKey] = count1 + 1
                    lastContentCounts[stemLast] = countLast + 1
                }

                // 2D Diversity penalty: relax to min(pen1, pen2) when pair is novel and high-quality
                val pen1 = count1 * 3000
                val pen2 = countLast * 2500
                val penalty = if (isNewPair && isHighQualityPath) minOf(pen1, pen2) else (pen1 + pen2)
                scoredFullCandidates.add(ScoredCandidate(path, path.cost + penalty))
            }
        }
        scoredFullCandidates.sortBy { it.effectiveCost }

        val fullCandidates = scoredFullCandidates.map {
            val fb = it.path.firstBunsetsuSurface.ifEmpty { it.path.surface }
            val lastCw = if (it.path.lastContentWordSurface.isNotEmpty() && it.path.lastContentWordSurface != fb) {
                it.path.lastContentWordSurface
            } else {
                ""
            }
            val isPred = isPredicatePos(it.path.lastRid) || isAuxiliaryVerb(it.path.lastRid)
            val tailSurface = if (isPred && fb.isNotEmpty() && it.path.surface.length > fb.length) {
                it.path.surface.substring(fb.length)
            } else if (isPred) {
                it.path.surface
            } else {
                ""
            }
            // Idea R1: Propagate structured lattice cost to SLM reranker on [0, 15000] scale
            val avgWordCost = if (it.path.wordCount > 0) it.path.wordCostSum / it.path.wordCount else 5000
            val maxExcess = maxOf(0, it.path.maxWordCost - 4500) / 2
            val avgExcessTrans = if (it.path.wordCount > 0) it.path.excessTransCostSum / it.path.wordCount else 0
            val effDictCost = (avgWordCost + maxExcess + avgExcessTrans).coerceIn(0, 15000)

            ConversionCandidate(
                surface = it.path.surface,
                reading = reading,
                isPrediction = false,
                source = if (it.path.hasUserWord) "user" else "viterbi",
                dictCost = effDictCost,
                firstBunsetsuSurface = fb,
                lastContentWordSurface = lastCw,
                completedSurface = it.path.surface,
                suffixBranchingFactor = 1.0f,
                isPredicate = isPred,
                predicateTailSurface = tailSurface
            )
        }

        // 2. Multi-Horizon Prefix Candidate Extraction (m = n-1, n-2, ..., 2):
        // Collect candidate tokens and stems across all horizons, and score them using
        // Backward-Viterbi cost and suffix branching entropy bonus.
        val prefixCandidates = mutableListOf<ConversionCandidate>()
        if (n >= 4) {
            // Compute Backward DP costs from position m to end of string n
            val betaMin = IntArray(n + 1) { 100000 }
            val branchFactor = IntArray(n + 1) { 0 }
            val bestSuffixSurface = Array(n + 1) { "" }
            val effectiveBranching = FloatArray(n + 1) { 1.0f }
            betaMin[n] = 0
            bestSuffixSurface[n] = ""

            for (m in (n - 1) downTo 1) {
                var bestCost = 100000
                var bestSurf = reading.substring(m, n)
                var branchCount = 0
                var minSuffixWordCost = 100000
                val transitionCosts = mutableListOf<Int>()

                var node: TrieNode? = root
                var j = m
                while (j < n) {
                    val ch = reading[j]
                    node = node?.children?.get(ch)
                    j++

                    val subReading = reading.substring(m, j)
                    val tokens = mutableListOf<WordEntry>()
                    node?.words?.let { tokens.addAll(it) }
                    val mmapToks = queryAllMmapExactTokens(subReading, includeEmojiSymbols = false)
                    for (tok in mmapToks) {
                        if (tokens.none { it.surface == tok.surface && it.lid == tok.lid }) {
                            tokens.add(tok)
                        }
                    }

                    if (tokens.isNotEmpty()) {
                        val stratifiedTokens = selectPosStratifiedSubwords(tokens, maxTotal = 20)
                        val bBoundaryChars = "とはがをにでものかねよ"
                        for (tok in stratifiedTokens) {
                            val bSpan = j - m
                            val isTokUserOrCanon = tok.source == "user" || tok.source == "canonical"
                            val lengthBonus = if (bSpan > 1 && !isTokUserOrCanon) {
                                val conf = ((8500 - tok.cost).toFloat() / 4000.0f).coerceIn(0.15f, 1.0f)
                                val touchesBoundary = (m > 0 && subReading.first() in bBoundaryChars) || subReading.last() in bBoundaryChars
                                val isRisky = (isProperNoun(tok.lid) && tok.cost > 4500) || tok.cost > 5200
                                val effConf = if (touchesBoundary && isRisky) minOf(conf, 0.25f) else conf
                                ((bSpan - 1) * 2000 * effConf).toInt()
                            } else if (bSpan > 1) {
                                (bSpan - 1) * 2000
                            } else {
                                0
                            }
                            val rarityPen = if (isTokUserOrCanon) 0 else maxOf(0, tok.cost - 6200) / 2
                            val transCost = if (matrix != null) {
                                if (j == n) matrix.getTransitionCost(tok.rid, 0) else 0
                            } else 0
                            val futureCost = tok.cost - lengthBonus + rarityPen + transCost + betaMin[j]
                            transitionCosts.add(futureCost)
                            if (futureCost < bestCost) {
                                bestCost = futureCost
                                bestSurf = tok.surface + bestSuffixSurface[j]
                            }
                            if (tok.cost < minSuffixWordCost) {
                                minSuffixWordCost = tok.cost
                            }
                            if (tok.cost - minSuffixWordCost <= 2500) {
                                branchCount++
                            }
                        }
                    }
                }
                betaMin[m] = bestCost.coerceAtMost(100000)
                bestSuffixSurface[m] = bestSurf
                branchFactor[m] = branchCount

                if (transitionCosts.isNotEmpty() && bestCost < 100000) {
                    var sumExp = 0.0f
                    for (c in transitionCosts) {
                        val diff = (c - bestCost).toFloat()
                        if (diff <= 6000.0f) {
                            sumExp += kotlin.math.exp(-diff / 1500.0f)
                        }
                    }
                    effectiveBranching[m] = sumExp.coerceAtLeast(1.0f)
                }
            }

            // Gather candidate prefix surfaces across horizons m in (n-1 downTo 2)
            data class ScoredPrefix(
                val surface: String,
                val readingLen: Int,
                val score: Int,
                val dictCost: Int = 5000
            )
            val bestPrefixMap = LinkedHashMap<String, ScoredPrefix>()

            fun recordPrefix(surface: String, readingLen: Int, score: Int, dictCost: Int = 5000) {
                if (surface.isBlank() || JapaneseKanaUtils.isAllHiragana(surface)) return
                val existing = bestPrefixMap[surface]
                if (existing == null || score < existing.score) {
                    bestPrefixMap[surface] = ScoredPrefix(surface, readingLen, score, dictCost)
                }
            }

            val minPrefixLen = if (n >= 6) 3 else 2
            // Pass 1: Source A (Direct exact dictionary tokens for all horizons m)
            // Evaluated first so exact single-word prefixes take absolute precedence over intermediate chopped beam fragments.
            for (m in (n - 1) downTo minPrefixLen) {
                val subReading = reading.substring(0, m)
                val mBranch = branchFactor[m]
                val branchBonus = when {
                    mBranch >= 12 -> 2400
                    mBranch >= 6 -> 1800
                    mBranch >= 3 -> 1200
                    mBranch >= 1 -> 600
                    else -> 0
                }
                val suffixCost = betaMin[m]
                val lengthBonus = if (m > 1) (m - 1) * 2000 else 0

                val directTokens = mutableListOf<WordEntry>()
                var node: TrieNode? = root
                for (ch in subReading) {
                    node = node?.children?.get(ch)
                    if (node == null) break
                }
                node?.words?.let { directTokens.addAll(it) }
                val mmapToks = queryAllMmapExactTokens(subReading, includeEmojiSymbols = false)
                for (tok in mmapToks) {
                    if (directTokens.none { it.surface == tok.surface && it.lid == tok.lid }) {
                        directTokens.add(tok)
                    }
                }

                directTokens.sortBy { it.cost }
                var acceptedRank = 0
                for (tok in directTokens) {
                    // Idea V3: Suppress ultra-rare unigrams (cost > 6000) from polluting top prefix slots
                    if (tok.source != "user" && tok.source != "canonical" && tok.cost > 6000) continue
                    val transCost = if (matrix != null) matrix.getTransitionCost(0, tok.lid) else 0
                    val score = (tok.cost - lengthBonus) + transCost + suffixCost - branchBonus + acceptedRank * 200
                    recordPrefix(tok.surface, m, score, tok.cost.coerceIn(0, 15000))
                    acceptedRank++
                    if (acceptedRank >= 8) break
                }
            }

            // Pass 2: Source B (Intermediate beam[m] paths for composite inflected bunsetsu prefixes of length m)
            for (m in (n - 1) downTo minPrefixLen) {
                val mBranch = branchFactor[m]
                val branchBonus = when {
                    mBranch >= 12 -> 2400
                    mBranch >= 6 -> 1800
                    mBranch >= 3 -> 1200
                    mBranch >= 1 -> 600
                    else -> 0
                }
                for (p in beam[m]) {
                    if (!p.hasUserWord && p.maxWordCost > 6500) continue
                    val surf = if (p.firstBunsetsuSurface.isNotBlank()) p.firstBunsetsuSurface else p.surface
                    val rLen = if (p.firstBunsetsuReadingLen == m) m else if (p.surface == surf) m else 0
                    if (rLen == m) {
                        val compositePenalty = if (p.words.size > 1) 1500 * (p.words.size - 1) else 0
                        val score = p.cost + compositePenalty + betaMin[m] - branchBonus
                        val pDictCost = (if (p.wordCount > 0) p.wordCostSum / p.wordCount else 5000).coerceIn(0, 15000)
                        recordPrefix(surf, m, score, pDictCost)
                    }
                }
            }

            // Also include firstBunsetsu from scored full paths if missed
            for (sc in scoredFullCandidates) {
                if (!sc.path.hasUserWord && sc.path.maxWordCost > 6500) continue
                val fb = sc.path.firstBunsetsuSurface
                val fbLen = sc.path.firstBunsetsuReadingLen
                if (fb.isNotBlank() && fbLen in 2 until n) {
                    val score = sc.path.cost - branchFactor[fbLen] * 200
                    val pDictCost = (if (sc.path.wordCount > 0) sc.path.wordCostSum / sc.path.wordCount else 5000).coerceIn(0, 15000)
                    recordPrefix(fb, fbLen, score, pDictCost)
                }
            }

            val candidatePrefixList = bestPrefixMap.values.toMutableList()
            candidatePrefixList.sortBy { it.score }
            for (sp in candidatePrefixList) {
                val matchingFull = scoredFullCandidates.firstOrNull {
                    it.path.firstBunsetsuSurface == sp.surface && it.path.firstBunsetsuReadingLen == sp.readingLen
                }
                val compSurface = matchingFull?.path?.surface ?: (sp.surface + bestSuffixSurface[sp.readingLen])
                val branchFactorVal = if (sp.readingLen in 1 until n) effectiveBranching[sp.readingLen] else 1.0f

                prefixCandidates.add(
                    ConversionCandidate(
                        surface = sp.surface,
                        reading = reading.substring(0, sp.readingLen),
                        isPrediction = false,
                        source = "viterbi_prefix",
                        dictCost = sp.dictCost,
                        firstBunsetsuSurface = sp.surface,
                        lastContentWordSurface = "",
                        completedSurface = compSurface,
                        suffixBranchingFactor = branchFactorVal
                    )
                )
            }
        }

        // 3. Candidate Strip Interleaving:
        // Slot 1, 2: Top 2D-diverse full-sentence candidates (length n)
        // Slot 3..6: Top multi-horizon prefix candidates (length m < n, e.g. m = n-1, n-2, ...)
        // Slot 7, 8: Next diverse full-sentence candidates
        // Slot 9+: Remaining prefix candidates followed by full candidates
        val results = mutableListOf<ConversionCandidate>()
        val seenFinal = HashSet<String>()

        fun tryAdd(cand: ConversionCandidate) {
            if (seenFinal.add(cand.surface)) {
                results.add(cand)
            }
        }

        var fullIdx = 0
        var prefixIdx = 0

        // Slots 1-2: Top 2 full candidates
        while (fullIdx < minOf(2, fullCandidates.size)) {
            tryAdd(fullCandidates[fullIdx++])
        }
        // Slots 3-6: Top 4 multi-horizon prefix candidates
        while (prefixIdx < minOf(4, prefixCandidates.size)) {
            tryAdd(prefixCandidates[prefixIdx++])
        }
        // Slots 7-8: Next 2 full candidates
        while (fullIdx < minOf(4, fullCandidates.size)) {
            tryAdd(fullCandidates[fullIdx++])
        }
        // Slot 9+: Remaining prefix candidates
        while (prefixIdx < minOf(12, prefixCandidates.size)) {
            tryAdd(prefixCandidates[prefixIdx++])
        }
        // Remaining full candidates
        while (fullIdx < fullCandidates.size && results.size < beamWidth + 12) {
            tryAdd(fullCandidates[fullIdx++])
        }
        // Remaining prefix candidates
        while (prefixIdx < prefixCandidates.size && results.size < beamWidth + 18) {
            tryAdd(prefixCandidates[prefixIdx++])
        }

        val fullCostMap = HashMap<String, Int>(scoredFullCandidates.size)
        for (sc in scoredFullCandidates) {
            fullCostMap.putIfAbsent(sc.path.surface, sc.effectiveCost)
        }
        return Pair(results, fullCostMap)
    }

    private fun isSahenNoun(lid: Int, rid: Int, surface: String): Boolean {
        if (isProperNoun(lid) || isProperNoun(rid)) return false
        return lid == 1841 || rid == 1841 || lid in 2035..2150 || rid in 2035..2150
    }

    private fun isVerbStem(lid: Int, rid: Int, surface: String): Boolean {
        if (isProperNoun(lid) || isProperNoun(rid)) return false
        if (isPredicatePos(lid) || isPredicatePos(rid)) return true
        return surface.length >= 2 && (surface.endsWith("っ") || surface.endsWith("い") || surface.endsWith("め") ||
            surface.endsWith("り") || surface.endsWith("き") || surface.endsWith("ぎ") || surface.endsWith("し") || surface.endsWith("ち"))
    }

    private fun isGeneralNoun(lid: Int, rid: Int, surface: String): Boolean {
        if (isProperNoun(lid) || isProperNoun(rid)) return false
        if (isSahenNoun(lid, rid, surface)) return false
        return (lid in 1841..2195 || rid in 1841..2195) || isContentWord(lid)
    }

    /**
     * B3: Dynamic Multi-Bunsetsu Tail Rollout (動的複文節テイル合成) with Stem Round-Robin Diversification (Idea D3).
     * Synthesizes natural, high-affinity functional and predicate completions from top stem candidates
     * when typing short prefixes (2..8 characters), validated via Mozc connection costs.
     * Uses breadth-first round-robin across distinct stems so topPool receives diverse stem hypotheses.
     */
    private fun synthesizeDynamicMultiBunsetsuChunks(
        normalized: String,
        exactWords: List<WordEntry>,
        prefixWords: List<Pair<String, WordEntry>>,
        contextRid: Int,
        contextPrefix: String = ""
    ): List<ConversionCandidate> {
        val results = mutableListOf<ConversionCandidate>()
        val seenSurfaces = HashSet<String>()
        val matrix = connectionMatrix

        // 1. Canonical Collocations from NextPhrasePredictor (Highest priority = 3.5f)
        // Collect up to 2 tails per matching stem, then flatten breadth-first (round-robin)
        val canonicalPerStem = mutableListOf<List<ConversionCandidate>>()
        for ((stemSurface, stemReading) in NextPhrasePredictor.CANONICAL_STEM_READINGS) {
            if (stemReading.startsWith(normalized) || normalized.startsWith(stemReading)) {
                val continuations = NextPhrasePredictor.CANONICAL_COLLOCATIONS[stemSurface] ?: continue
                val stemList = ArrayList<ConversionCandidate>(2)
                for (entry in continuations) {
                    val fullSurface = stemSurface + entry.surface
                    val fullReading = stemReading + entry.reading
                    if (fullReading.startsWith(normalized) && fullSurface != normalized) {
                        stemList.add(
                            ConversionCandidate(
                                surface = fullSurface,
                                reading = fullReading,
                                isPrediction = true,
                                source = "phrase_chunk",
                                priority = 3.5f,
                                firstBunsetsuSurface = stemSurface
                            )
                        )
                        if (stemList.size >= 2) break
                    }
                }
                if (stemList.isNotEmpty()) {
                    canonicalPerStem.add(stemList)
                }
            }
        }
        val canonicalChunks = mutableListOf<ConversionCandidate>()
        for (pass in 0 until 2) {
            for (stemList in canonicalPerStem) {
                if (pass < stemList.size && canonicalChunks.size < 5) {
                    val cand = stemList[pass]
                    if (seenSurfaces.add(cand.surface)) {
                        canonicalChunks.add(cand)
                    }
                }
            }
        }
        results.addAll(canonicalChunks)

        // 2. Dynamic Functional & Auxiliary Tail Rollout for Content Stems (Priority = 3.3f)
        val candidateStems = mutableListOf<Pair<String, WordEntry>>()
        for (w in exactWords) {
            if (w.surface.length >= 2 && !JapaneseKanaUtils.isAllHiragana(w.surface)) {
                candidateStems.add(Pair(normalized, w))
            }
        }
        for (p in prefixWords) {
            val (r, w) = p
            if (w.surface.length >= 2 && !JapaneseKanaUtils.isAllHiragana(w.surface)) {
                candidateStems.add(Pair(r, w))
            }
        }

        val topStems = candidateStems
            .distinctBy { it.second.surface }
            .sortedBy { (_, entry) ->
                val connCost = if (matrix != null && contextRid > 0 && entry.lid > 0) {
                    matrix.getTransitionCost(contextRid, entry.lid)
                } else 0
                entry.cost + connCost
            }
            .take(8)

        // Context-aware tail preference: after case particles (を/に/で/へ/から/と), action verbs are favored;
        // at clause start or after topic/punctuation, modal/state tails (中ですので/予定です) are diversified upfront.
        val isClauseStartOrTopic = isClauseStartOrTopicContext(contextPrefix)
        val sahenTailsOrdered = if (isClauseStartOrTopic) {
            listOf(
                NextPhrasePredictor.CollocationEntry("します", "します"),
                NextPhrasePredictor.CollocationEntry("中ですので", "ちゅうですので"),
                NextPhrasePredictor.CollocationEntry("しました", "しました"),
                NextPhrasePredictor.CollocationEntry("予定です", "よていです"),
                NextPhrasePredictor.CollocationEntry("しています", "しています"),
                NextPhrasePredictor.CollocationEntry("いたします", "いたします"),
                NextPhrasePredictor.CollocationEntry("中", "ちゅう"),
                NextPhrasePredictor.CollocationEntry("お願いします", "おねがいします")
            )
        } else {
            DYNAMIC_SAHEN_TAILS
        }

        val dynamicPerStem = mutableListOf<List<ConversionCandidate>>()
        for ((stemReading, stemWord) in topStems) {
            val stemSurface = stemWord.surface
            if (NextPhrasePredictor.CANONICAL_STEM_READINGS.containsKey(stemSurface)) continue

            val isSahen = isSahenNoun(stemWord.lid, stemWord.rid, stemSurface)
            val isVerb = isVerbStem(stemWord.lid, stemWord.rid, stemSurface)
            val isNoun = isGeneralNoun(stemWord.lid, stemWord.rid, stemSurface)

            val applicableTails: List<NextPhrasePredictor.CollocationEntry> = when {
                isSahen -> sahenTailsOrdered
                isVerb -> DYNAMIC_VERB_TAILS
                isNoun -> DYNAMIC_NOUN_TAILS
                else -> emptyList()
            }

            val stemList = ArrayList<ConversionCandidate>(2)
            for (tail in applicableTails) {
                val fullSurface = stemSurface + tail.surface
                val fullReading = stemReading + tail.reading
                if (fullReading.startsWith(normalized) && fullSurface != normalized) {
                    if (matrix != null && stemWord.rid > 0 && tail.reading.isNotEmpty()) {
                        val tailLid = when (tail.surface) {
                            "です", "でした", "ですので", "ですが" -> 172
                            "中", "中ですので" -> 2003
                            "します", "しました", "いたします" -> 1825
                            else -> 0
                        }
                        if (tailLid > 0) {
                            val transCost = matrix.getTransitionCost(stemWord.rid, tailLid)
                            if (transCost >= ConnectionMatrix.INVALID_COST) {
                                continue
                            }
                        }
                    }
                    stemList.add(
                        ConversionCandidate(
                            surface = fullSurface,
                            reading = fullReading,
                            isPrediction = true,
                            source = "phrase_chunk",
                            priority = 3.3f,
                            dictCost = stemWord.cost,
                            firstBunsetsuSurface = stemSurface
                        )
                    )
                    if (stemList.size >= 2) break
                }
            }
            if (stemList.isNotEmpty()) {
                dynamicPerStem.add(stemList)
            }
        }

        val dynamicChunks = mutableListOf<ConversionCandidate>()
        for (pass in 0 until 2) {
            for (stemList in dynamicPerStem) {
                if (pass < stemList.size && dynamicChunks.size < 5) {
                    val cand = stemList[pass]
                    if (seenSurfaces.add(cand.surface)) {
                        dynamicChunks.add(cand)
                    }
                }
            }
        }
        results.addAll(dynamicChunks)

        return results.take(8)
    }
}
