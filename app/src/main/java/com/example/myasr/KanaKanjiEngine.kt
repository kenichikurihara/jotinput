package com.example.myasr

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

data class ConversionCandidate(
    val surface: String,
    val reading: String,
    val isPrediction: Boolean = false,
    val source: String = "dict",
    val priority: Float = 0.0f,
    val obsPenalty: Float = 0.0f,
    val firstBunsetsuSurface: String = "",
    val lastContentWordSurface: String = "",
    val completedSurface: String = "",
    val suffixBranchingFactor: Float = 1.0f,
    val isPredicate: Boolean = false,
    val predicateTailSurface: String = ""
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
    }

    private val root = TrieNode()
    // In-memory access-ordered LinkedHashMap to guarantee consistent JVM/Android LRU behavior and null-safety
    private val historyMap = LinkedHashMap<String, Int>(256, 0.75f, true)
    private val queryCache = LinkedHashMap<String, List<ConversionCandidate>>(300, 0.75f, true)
    private val historyFile = context?.let { File(it.filesDir, "ime_history.json") }
    private val dbHelper = context?.let { DictionaryDatabaseHelper.getInstance(it) }

    private val ioScope = CoroutineScope(Dispatchers.IO)
    private var saveHistoryJob: Job? = null
    private val historyLock = Any()

    @Volatile
    private var connectionMatrix: ConnectionMatrix? = null

    data class MmapEntry(val sourceId: String, val dict: MmapDictionary)
    private val activeMmapDictionaries = java.util.concurrent.CopyOnWriteArrayList<MmapEntry>()

    @Volatile
    private var mmapDictionary: MmapDictionary? = null

    @Volatile
    private var isInitialized = false

    init {
        loadHistory()
    }

    fun setConnectionMatrix(matrix: ConnectionMatrix?) {
        this.connectionMatrix = matrix
        clearMemoryCache()
        Log.i(TAG, "ConnectionMatrix updated (active: ${matrix != null})")
    }

    fun getConnectionMatrix(): ConnectionMatrix? = connectionMatrix

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
                instance ?: KanaKanjiEngine(context.applicationContext).also { instance = it }
            }
        }
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
     * Non-blocking or background warm-up: ensures base dictionaries and user dictionaries are loaded
     * and performs a sample query to prime Trie node traversal paths and query caches.
     */
    @Synchronized
    fun warmUp() {
        initializeIfNeeded()
        try {
            getBaseCandidates("きょう")
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
        var node = root
        for (ch in reading) {
            node = node.children.getOrPut(ch) { TrieNode() }
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
        val baseCandidates = getBaseCandidates(rawReading)
        val ctx = context
        if (ctx != null && !KeyboardHeightManager.isSlmRerankEnabled(ctx)) {
            return baseCandidates
        }
        return rerankCandidates(contextPrefix, baseCandidates)
    }

    /**
     * Fast-path candidate retrieval (in-memory Trie + SQLite query cache).
     * Safe to run synchronously on the UI thread (< 1ms).
     */
    fun getBaseCandidates(rawReading: String): List<ConversionCandidate> {
        if (rawReading.isBlank()) return emptyList()
        initializeIfNeeded()

        val normalized = JapaneseKanaUtils.normalizeReading(rawReading)

        // Retrieve base candidates from queryCache or generate them
        val cached = synchronized(queryCache) { queryCache[normalized] }
        if (cached != null) return cached

        val result = mutableListOf<ConversionCandidate>()
            val seenSurfaces = HashSet<String>()

            fun addCandidate(cand: ConversionCandidate) {
                // All-hiragana candidates matching composing text exactly are excluded because
                // pressing the commit/enter button directly commits the un-converted Hiragana composing text.
                // However, hiragana candidates resulting from flick typo corrections (source == "flick_correction" or typo paths)
                // provide immense utility to the user when mistyping and are preserved.
                val isRedundantHiragana = JapaneseKanaUtils.isAllHiragana(cand.surface) &&
                        (cand.source != "flick_correction" && !cand.isPrediction || cand.surface == normalized)
                if (cand.surface.isNotBlank() &&
                    !isRedundantHiragana &&
                    seenSurfaces.add(cand.surface)) {
                    result.add(cand)
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

            // 2. Exact matches from SQLite multi-dictionary database
            try {
                val dbExact = dbHelper?.getExactMatches(normalized, limit = 50) ?: emptyList()
                for (cand in dbExact) {
                    addCandidate(cand)
                }
            } catch (e: Exception) {
                Log.w(TAG, "DB exact query failed: ${e.message}")
            }

            // 3. Base in-memory Trie & MmapDictionary exact matches
            val mmapTokens = queryAllMmapExactTokens(normalized)
            for (tok in mmapTokens) {
                addCandidate(ConversionCandidate(surface = tok.surface, reading = normalized, isPrediction = false, source = tok.source))
            }

            val otherExact = exactTrieWords.filter { it.source != "user" }.sortedWith { a, b ->
                val histA = synchronized(historyLock) { historyMap["${normalized}:${a.surface}"] ?: 0 }
                val histB = synchronized(historyLock) { historyMap["${normalized}:${b.surface}"] ?: 0 }
                if (histA != histB) {
                    histB.compareTo(histA)
                } else {
                    a.cost.compareTo(b.cost)
                }
            }
            for (w in otherExact) {
                addCandidate(ConversionCandidate(surface = w.surface, reading = normalized, isPrediction = false, source = w.source))
            }

            // 3.6. Voiced & Semi-voiced Exact Matches (e.g. "かいき" -> "会議" [かいぎ], "こはん" -> "ご飯" [ごはん])
            val voicedPaths = matchingPaths.filter { it.voicingDistance > 0 && it.typoCostPenalty == 0 }
            if (voicedPaths.isNotEmpty()) {
                val voicedExactWords = mutableListOf<Triple<String, WordEntry, Int>>()
                for (vPath in voicedPaths) {
                    for (w in vPath.node.words) {
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
                    addCandidate(ConversionCandidate(surface = entry.surface, reading = reading, isPrediction = true, source = entry.source, priority = entry.priority, obsPenalty = 1.2f * vDist))
                }
                // Query Mmap & SQLite exact matches for top voiced readings (up to 2 readings)
                for (vPath in voicedPaths.take(2)) {
                    val mmapVoicedExact = queryAllMmapExactTokens(vPath.reading)
                    for (tok in mmapVoicedExact) {
                        addCandidate(ConversionCandidate(surface = tok.surface, reading = vPath.reading, isPrediction = true, source = tok.source, obsPenalty = 1.2f * vPath.voicingDistance))
                    }
                    try {
                        val dbVoicedExact = dbHelper?.getExactMatches(vPath.reading, limit = 15) ?: emptyList()
                        for (cand in dbVoicedExact) {
                            addCandidate(ConversionCandidate(surface = cand.surface, reading = cand.reading, isPrediction = true, source = cand.source))
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "DB voiced exact query failed: ${e.message}")
                    }
                }
            }

            // 3.8. Voiced & Semi-voiced Predictive Prefix Matches (e.g. "すーは" -> "スーパー", "は" -> "パン", "場合")
            // Evaluated before Viterbi so natural unvoiced prefixes of single words (like "すーは" -> "スーパー")
            // take precedence over artificial multi-word Viterbi chunk splits (like "すー葉").
            if (voicedPaths.isNotEmpty()) {
                val voicedPrefixWords = mutableListOf<Triple<String, WordEntry, Int>>()
                for (vPath in voicedPaths) {
                    val prefixWords = mutableListOf<Pair<String, WordEntry>>()
                    collectPrefixWords(vPath.node, vPath.reading, prefixWords, maxCount = 80)
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
                for ((reading, entry, _) in voicedPrefixWords) {
                    addCandidate(ConversionCandidate(surface = entry.surface, reading = reading, isPrediction = true, source = entry.source))
                }
                // Query SQLite prefix matches for top voiced readings (up to 2 readings)
                for (vPath in voicedPaths.take(2)) {
                    try {
                        val dbVoicedPrefix = dbHelper?.getPrefixMatches(vPath.reading, limit = 15) ?: emptyList()
                        for (cand in dbVoicedPrefix) {
                            addCandidate(ConversionCandidate(surface = cand.surface, reading = cand.reading, isPrediction = true, source = cand.source))
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "DB voiced prefix query failed: ${e.message}")
                    }
                }
            }

            // 4. Multi-bunsetsu Viterbi Lattice Search (for continuous phrase conversion)
            // Evaluated BEFORE regular prefix matches so exact multi-bunsetsu phrases
            // (e.g. "いえの" -> "家の", "しってる" -> "知ってる", "でません" -> "出ません")
            // are prioritized over long predictive extensions (e.g. "家の外", "家の中").
            // Beam width is set to 12 to ensure natural inflected verb/particle phrases are never pruned.
            if (normalized.length >= 3) {
                val viterbiCandidates = searchViterbiLattice(normalized, beamWidth = 12)
                for (cand in viterbiCandidates) {
                    addCandidate(cand)
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
                    if (fPath.reading.isNotBlank() && fPath.reading != normalized && fPath.node.words.isNotEmpty()) {
                        validFlickReadings.add(Pair(fPath.reading, fPath.typoCostPenalty))
                    }
                    for (w in fPath.node.words) {
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
                    addCandidate(ConversionCandidate(surface = entry.surface, reading = reading, isPrediction = true, source = entry.source, priority = entry.priority, obsPenalty = penalty.toFloat() / 1000.0f))
                }

                // If the typo-corrected reading represents a valid word in the lexicon,
                // offer the corrected Hiragana surface itself (e.g. "わたさ" -> "わたし")
                for ((flickReading, penalty) in validFlickReadings.take(3)) {
                    addCandidate(
                        ConversionCandidate(
                            surface = flickReading,
                            reading = flickReading,
                            isPrediction = true,
                            source = "flick_correction"
                        )
                    )
                }
            }

            // 5. Predictive prefix matches from in-memory Trie (scans breadth up to 80, takes top 40 by cost)
            if (exactNode != null) {
                val prefixWords = mutableListOf<Pair<String, WordEntry>>()
                collectPrefixWords(exactNode, normalized, prefixWords, maxCount = 80)
                prefixWords.sortBy { it.second.cost }
                for ((reading, entry) in prefixWords.take(40)) {
                    addCandidate(ConversionCandidate(surface = entry.surface, reading = reading, isPrediction = true, source = entry.source))
                }
            }

            // 5.2. Predictive prefix matches from Mmap dictionaries & SQLite
            val mmapPrefixes = queryAllMmapPrefixTokens(normalized, maxCount = 10)
            for (cand in mmapPrefixes) {
                addCandidate(cand)
            }
            try {
                val dbPrefix = dbHelper?.getPrefixMatches(normalized, limit = 5) ?: emptyList()
                for (cand in dbPrefix) {
                    addCandidate(cand)
                }
            } catch (e: Exception) {
                Log.w(TAG, "DB prefix query failed: ${e.message}")
            }

            // 6. Katakana conversion
            val katakana = JapaneseKanaUtils.hiraganaToKatakana(normalized)
            addCandidate(ConversionCandidate(surface = katakana, reading = normalized, isPrediction = false, source = "system"))

            // 7. Half-width Katakana
            val halfWidth = JapaneseKanaUtils.toHalfWidthKatakana(normalized)
            addCandidate(ConversionCandidate(surface = halfWidth, reading = normalized, isPrediction = false, source = "system"))

            // Sort non-user exact matches by selection history
            val ranked = prioritizeByHistory(result, targetReading = normalized)
            synchronized(queryCache) {
                if (queryCache.size >= 300) {
                    val oldestKey = queryCache.keys.firstOrNull()
                    if (oldestKey != null) queryCache.remove(oldestKey)
                }
                queryCache[normalized] = ranked
            }
            return ranked
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
            val isRedundantHiragana = JapaneseKanaUtils.isAllHiragana(it.surface) && (firstReading != null && it.surface == firstReading)
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
        if (engineType != RerankEngineType.LOCAL_SLM && engineType != RerankEngineType.LOCAL_SLM_1_5B) {
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
     * Contextual candidate re-ranking using on-device SLM (Qwen2.5-0.5B / 1.5B).
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
            val isRedundantHiragana = JapaneseKanaUtils.isAllHiragana(it.surface) && (firstReading != null && it.surface == firstReading)
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
            RerankEngineType.LOCAL_SLM, RerankEngineType.LOCAL_SLM_1_5B -> {
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
        val userCandidates = candidates.filter { it.source == "user" }
        val nonUserCandidates = candidates.filter { it.source != "user" }

        val sortedNonUser = nonUserCandidates.sortedWith { a, b ->
            val aIsFull = targetReading.isEmpty() || (a.reading == targetReading && !a.isPrediction && a.source != "viterbi_prefix")
            val bIsFull = targetReading.isEmpty() || (b.reading == targetReading && !b.isPrediction && b.source != "viterbi_prefix")
            val histA = if (aIsFull) synchronized(historyLock) { historyMap["${a.reading}:${a.surface}"] ?: 0 } else 0
            val histB = if (bIsFull) synchronized(historyLock) { historyMap["${b.reading}:${b.surface}"] ?: 0 } else 0
            histB.compareTo(histA)
        }

        return userCandidates + sortedNonUser
    }

    private data class MatchedTrieNode(
        val node: TrieNode,
        val reading: String,
        val voicingDistance: Int,
        val typoCostPenalty: Int = 0
    )

    /**
     * Traverses the in-memory Trie with dynamic branching for:
     * 1. Voiced (濁音) and semi-voiced (半濁音) expansions.
     * 2. Flick typing error expansions (incomplete flick / "a"-row fallback and adjacent 12-keys).
     * Pruning is performed on-the-fly: non-existent branches in the Japanese lexicon are immediately discarded (O(1)).
     */
    private fun findMatchingTrieNodes(normalized: String, maxVoicingDistance: Int = 2): List<MatchedTrieNode> {
        if (normalized.isEmpty()) return emptyList()
        var currentPaths = listOf(MatchedTrieNode(root, "", 0, 0))

        for (i in normalized.indices) {
            val originalChar = normalized[i]
            val voicingExpansions = JapaneseKanaUtils.getVoicingExpansions(originalChar)
            val flickExpansions = JapaneseKanaUtils.getFlickTypoExpansions(originalChar)

            val nextPaths = ArrayList<MatchedTrieNode>(currentPaths.size * (voicingExpansions.size + flickExpansions.size))

            for (path in currentPaths) {
                // 1. Voicing expansions (including exact original char)
                for (candidateChar in voicingExpansions) {
                    val isVoicingChange = (candidateChar != originalChar)
                    val newDistance = path.voicingDistance + (if (isVoicingChange) 1 else 0)
                    if (newDistance > maxVoicingDistance) continue

                    val childNode = path.node.children[candidateChar] ?: continue
                    nextPaths.add(MatchedTrieNode(childNode, path.reading + candidateChar, newDistance, path.typoCostPenalty))
                }

                // 2. Flick typing error expansions (only if path hasn't accumulated heavy penalties already)
                if (path.voicingDistance == 0 && path.typoCostPenalty == 0) {
                    for (flick in flickExpansions) {
                        val childNode = path.node.children[flick.char] ?: continue
                        nextPaths.add(MatchedTrieNode(childNode, path.reading + flick.char, 0, flick.costPenalty))
                    }
                }
            }
            if (nextPaths.isEmpty()) return emptyList()
            currentPaths = if (nextPaths.size > 28) {
                nextPaths.sortBy { it.voicingDistance * 1200 + it.typoCostPenalty }
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
                val countToAdd = minOf(2, currentNode.words.size)
                for (i in 0 until countToAdd) {
                    outList.add(Pair(currentReading, currentNode.words[i]))
                    if (outList.size >= maxCount) return
                }
            }

            for ((ch, childNode) in currentNode.children) {
                queue.add(Pair(currentReading + ch, childNode))
            }
        }
    }

    /**
     * Records candidate selection in LRU history to prioritize frequent words.
     */
    fun commitCandidate(candidate: ConversionCandidate) {
        val key = "${candidate.reading}:${candidate.surface}"
        synchronized(historyLock) {
            val currentCount = historyMap[key] ?: 0
            historyMap[key] = (currentCount + 1).coerceAtMost(100_000)
            if (historyMap.size > MAX_HISTORY_WORDS) {
                val oldestKey = historyMap.keys.firstOrNull()
                if (oldestKey != null) historyMap.remove(oldestKey)
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
        synchronized(historyLock) {
            historyMap.clear()
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
        } catch (e: Exception) {
            Log.w(TAG, "Error loading history: ${e.message}")
        }
    }

    private fun saveHistory() {
        val file = historyFile ?: return
        try {
            val json = JSONObject()
            val snapshot = synchronized(historyLock) {
                LinkedHashMap(historyMap)
            }
            for ((k, v) in snapshot) {
                json.put(k, v)
            }
            val tempFile = File(file.parentFile, "${file.name}.tmp")
            tempFile.writeText(json.toString(), Charsets.UTF_8)
            if (!tempFile.renameTo(file)) {
                tempFile.copyTo(file, overwrite = true)
                tempFile.delete()
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
        val secondBunsetsuIsProper: Boolean = false
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

    private fun queryAllMmapExactTokens(reading: String): List<WordEntry> {
        val dicts = activeMmapDictionaries
        if (dicts.isEmpty()) return emptyList()
        val result = ArrayList<WordEntry>(16)
        for (entry in dicts) {
            val tokens = entry.dict.getExactTokens(reading) ?: continue
            for (tok in tokens) {
                result.add(
                    WordEntry(
                        surface = tok.surface,
                        cost = tok.cost,
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
                        source = entry.sourceId
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
        val n = reading.length
        if (n < 3) return emptyList()

        val beam = Array(n + 1) { mutableListOf<ViterbiPath>() }
        beam[0].add(ViterbiPath(0, "", emptyList(), false, lastRid = 0, firstBunsetsuSurface = "", firstBunsetsuReadingLen = 0, isFirstBunsetsuClosed = false, lastContentWordSurface = "", secondBunsetsuIsProper = false))

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
                    val mmapWords = queryAllMmapExactTokens(subReading)
                    for (tok in mmapWords) {
                        if (subWords.none { it.surface == tok.surface && it.lid == tok.lid }) {
                            subWords.add(tok)
                        }
                    }

                    if (subWords.isNotEmpty()) {
                        val stratifiedWords = selectPosStratifiedSubwords(subWords, maxTotal = 24)
                        for (w in stratifiedWords) {
                            val baseBonus = if (j - i > 1) (j - i - 1) * 2000 else 0
                            // Idea A: Additive Bunsetsu-level Length Bonus Normalization for auxiliary verbs attaching to verb/adj stems
                            val predicateBunsetsuBonus = if (i > 0 && isAuxiliaryVerb(w.lid) && isPredicatePos(path.lastRid)) 2000 else 0
                            val lengthBonus = baseBonus + predicateBunsetsuBonus
                            val isUser = w.source == "user"
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

                            val newCost = path.cost + (w.cost - lengthBonus).coerceAtLeast(-50000) - userBonus + transitionCost
                            val nextRid = if (w.rid > 0) w.rid else path.lastRid

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
                                    secondBunsetsuIsProper = nextSecondProper
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
        data class ScoredCandidate(val path: ViterbiPath, val effectiveCost: Int)
        val seenFullSurfaces = HashSet<String>()
        val firstStemCounts = HashMap<Pair<String, Boolean>, Int>()
        val lastContentCounts = HashMap<String, Int>()
        val scoredFullCandidates = mutableListOf<ScoredCandidate>()

        for (path in finalPaths) {
            if (path.surface.isNotBlank() &&
                path.surface != reading &&
                !JapaneseKanaUtils.isAllHiragana(path.surface) &&
                seenFullSurfaces.add(path.surface)) {
                val stem1 = if (path.firstBunsetsuSurface.isNotEmpty()) path.firstBunsetsuSurface else path.surface
                val diversityKey = Pair(stem1, path.secondBunsetsuIsProper)
                val count1 = firstStemCounts.getOrDefault(diversityKey, 0)
                firstStemCounts[diversityKey] = count1 + 1

                val stemLast = if (path.lastContentWordSurface.isNotEmpty()) path.lastContentWordSurface else path.surface
                val countLast = lastContentCounts[stemLast] ?: 0
                lastContentCounts[stemLast] = countLast + 1

                // 2D Diversity penalty: 3000 for duplicate prefix stem, 2500 for duplicate suffix word
                val penalty = count1 * 3000 + countLast * 2500
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
            ConversionCandidate(
                surface = it.path.surface,
                reading = reading,
                isPrediction = false,
                source = if (it.path.hasUserWord) "user" else "viterbi",
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
                    val mmapToks = queryAllMmapExactTokens(subReading)
                    for (tok in mmapToks) {
                        if (tokens.none { it.surface == tok.surface && it.lid == tok.lid }) {
                            tokens.add(tok)
                        }
                    }

                    if (tokens.isNotEmpty()) {
                        val stratifiedTokens = selectPosStratifiedSubwords(tokens, maxTotal = 20)
                        for (tok in stratifiedTokens) {
                            val lengthBonus = if (j - m > 1) (j - m - 1) * 2000 else 0
                            val transCost = if (matrix != null) {
                                if (j == n) matrix.getTransitionCost(tok.rid, 0) else 0
                            } else 0
                            val futureCost = tok.cost - lengthBonus + transCost + betaMin[j]
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
                val score: Int
            )
            val bestPrefixMap = LinkedHashMap<String, ScoredPrefix>()

            fun recordPrefix(surface: String, readingLen: Int, score: Int) {
                if (surface.isBlank() || JapaneseKanaUtils.isAllHiragana(surface)) return
                val existing = bestPrefixMap[surface]
                if (existing == null || score < existing.score) {
                    bestPrefixMap[surface] = ScoredPrefix(surface, readingLen, score)
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
                val mmapToks = queryAllMmapExactTokens(subReading)
                for (tok in mmapToks) {
                    if (directTokens.none { it.surface == tok.surface && it.lid == tok.lid }) {
                        directTokens.add(tok)
                    }
                }

                directTokens.sortBy { it.cost }
                for ((rank, tok) in directTokens.take(8).withIndex()) {
                    val transCost = if (matrix != null) matrix.getTransitionCost(0, tok.lid) else 0
                    val score = (tok.cost - lengthBonus) + transCost + suffixCost - branchBonus + rank * 200
                    recordPrefix(tok.surface, m, score)
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
                    val surf = if (p.firstBunsetsuSurface.isNotBlank()) p.firstBunsetsuSurface else p.surface
                    val rLen = if (p.firstBunsetsuReadingLen == m) m else if (p.surface == surf) m else 0
                    if (rLen == m) {
                        val compositePenalty = if (p.words.size > 1) 1500 * (p.words.size - 1) else 0
                        val score = p.cost + compositePenalty + betaMin[m] - branchBonus
                        recordPrefix(surf, m, score)
                    }
                }
            }

            // Also include firstBunsetsu from scored full paths if missed
            for (sc in scoredFullCandidates) {
                val fb = sc.path.firstBunsetsuSurface
                val fbLen = sc.path.firstBunsetsuReadingLen
                if (fb.isNotBlank() && fbLen in 2 until n) {
                    val score = sc.path.cost - branchFactor[fbLen] * 200
                    recordPrefix(fb, fbLen, score)
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

        return results
    }
}
