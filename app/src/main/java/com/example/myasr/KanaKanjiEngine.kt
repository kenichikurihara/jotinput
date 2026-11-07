package com.example.myasr

import android.content.Context
import android.util.Log
import android.util.LruCache
import org.json.JSONObject
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
    val obsPenalty: Float = 0.0f
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
    private val historyCache = LruCache<String, Int>(300)
    private val queryCache = LruCache<String, List<ConversionCandidate>>(300)
    private val historyFile = context?.let { File(it.filesDir, "ime_history.json") }
    private val dbHelper = context?.let { DictionaryDatabaseHelper.getInstance(it) }

    @Volatile
    private var connectionMatrix: ConnectionMatrix? = null

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
        clearMemoryCache()
        Log.i(TAG, "MmapDictionary updated (active: ${dict != null})")
    }

    fun getMmapDictionary(): MmapDictionary? = mmapDictionary

    companion object {
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

            // 1. Load Base Dictionary from Storage (Mmap binary dictionary or TSV fallback)
            var baseLoaded = false

            // Try zero-heap memory-mapped binary dictionary first
            val binFile = getSystemDictionaryBinFile()
            if (binFile.exists() && binFile.length() > 64L) {
                try {
                    val mmap = MmapDictionary.loadFromFile(binFile)
                    if (mmap != null) {
                        mmapDictionary = mmap
                        baseLoaded = true
                        Log.i(TAG, "MmapDictionary loaded from storage: ${binFile.absolutePath} (${binFile.length()} bytes)")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to load MmapDictionary from storage: ${e.message}")
                }
            }

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
            CanonicalWord("しています", "しています")
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
        clearMemoryCache()
        initializeIfNeeded()
    }

    fun clearMemoryCache() {
        queryCache.evictAll()
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
        val isContextFreeEnabled = ctx != null && KeyboardHeightManager.isSlmContextFreeRerankEnabled(ctx)
        if (contextPrefix.isBlank() && !isContextFreeEnabled) {
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
        return queryCache.get(normalized) ?: run {
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
            val mmapTokens = mmapDictionary?.getExactTokens(normalized) ?: emptyList()
            for (tok in mmapTokens) {
                addCandidate(ConversionCandidate(surface = tok.surface, reading = normalized, isPrediction = false, source = "mmap"))
            }

            val otherExact = exactTrieWords.filter { it.source != "user" }.sortedWith { a, b ->
                val histA = historyCache.get("${normalized}:${a.surface}") ?: 0
                val histB = historyCache.get("${normalized}:${b.surface}") ?: 0
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
                    val histA = historyCache.get("${a.first}:${a.second.surface}") ?: 0
                    val histB = historyCache.get("${b.first}:${b.second.surface}") ?: 0
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
                // Query SQLite exact matches for top voiced readings (up to 2 readings)
                for (vPath in voicedPaths.take(2)) {
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
                    val histA = historyCache.get("${a.first}:${a.second.surface}") ?: 0
                    val histB = historyCache.get("${b.first}:${b.second.surface}") ?: 0
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
                    val histA = historyCache.get("${a.first}:${a.second.surface}") ?: 0
                    val histB = historyCache.get("${b.first}:${b.second.surface}") ?: 0
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

            // 5.2. Predictive prefix matches from SQLite multi-dictionary (limited to 5)
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
            val ranked = prioritizeByHistory(result)
            queryCache.put(normalized, ranked)
            ranked
        }
    }

    /**
     * Contextual candidate re-ranking using SLM or Android AICore (Gemini Nano 1 / 2).
     * Designed to be executed asynchronously on Dispatchers.Default.
     * Returns a pair of (reranked candidates, whether scoring was successfully applied).
     */
    fun rerankCandidatesWithStatus(contextPrefix: String, baseCandidates: List<ConversionCandidate>): Pair<List<ConversionCandidate>, Boolean> {
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
        val isContextFreeEnabled = KeyboardHeightManager.isSlmContextFreeRerankEnabled(ctx)
        if (contextPrefix.isBlank() && (!isContextFreeEnabled || firstReading == "（" || firstReading == "）" || firstReading == "(" || firstReading == ")")) {
            return Pair(filtered, false)
        }
        val alpha = KeyboardHeightManager.getSlmWeightAlpha(ctx)
        val lambdaObs = KeyboardHeightManager.getSlmLambdaObs(ctx)
        val gammaUser = KeyboardHeightManager.getSlmGammaUser(ctx)
        val engineType = KeyboardHeightManager.getRerankEngineType(ctx)

        return when (engineType) {
            RerankEngineType.NONE -> Pair(filtered, false)
            RerankEngineType.AICORE_NANO_2, RerankEngineType.AICORE_NANO_1 -> {
                AiCoreReranker.getInstance(ctx).rerankWithStatus(engineType, contextPrefix, filtered, weightAlpha = alpha, lambdaObs = lambdaObs, gammaUser = gammaUser)
            }
            RerankEngineType.LOCAL_SLM, RerankEngineType.LOCAL_SLM_1_5B, RerankEngineType.HYBRID_RERANK -> {
                val effectiveAlpha = if (engineType == RerankEngineType.HYBRID_RERANK) maxOf(alpha, 0.40f) else alpha
                LlmReranker.getInstance(ctx).rerankWithStatus(contextPrefix, filtered, weightAlpha = effectiveAlpha, lambdaObs = lambdaObs, gammaUser = gammaUser)
            }
        }
    }

    fun rerankCandidates(contextPrefix: String, baseCandidates: List<ConversionCandidate>): List<ConversionCandidate> {
        return rerankCandidatesWithStatus(contextPrefix, baseCandidates).first
    }

    private fun prioritizeByHistory(
        candidates: List<ConversionCandidate>
    ): List<ConversionCandidate> {
        val userCandidates = candidates.filter { it.source == "user" }
        val nonUserCandidates = candidates.filter { it.source != "user" }

        val sortedNonUser = nonUserCandidates.sortedWith { a, b ->
            val histA = historyCache.get("${a.reading}:${a.surface}") ?: 0
            val histB = historyCache.get("${b.reading}:${b.surface}") ?: 0
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
        val currentCount = historyCache.get(key) ?: 0
        historyCache.put(key, currentCount + 1)
        clearMemoryCache()
        saveHistory()
    }

    private fun loadHistory() {
        val file = historyFile ?: return
        try {
            if (file.exists()) {
                val jsonStr = file.readText(Charsets.UTF_8)
                val json = JSONObject(jsonStr)
                val keys = json.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    historyCache.put(k, json.optInt(k, 1))
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
            val snapshot = historyCache.snapshot()
            for ((k, v) in snapshot) {
                json.put(k, v)
            }
            file.writeText(json.toString(), Charsets.UTF_8)
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
        val lastContentWordSurface: String = ""
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

    /**
     * Fast in-memory N-best Viterbi lattice beam search over Trie subwords.
     * Evaluates in < 0.3ms synchronously on the UI thread for long continuous phrases.
     * Implements quotient-state stratified beam search (同値類層化) and multi-granularity (Prefix) interleaving.
     */
    private fun searchViterbiLattice(reading: String, beamWidth: Int = 6): List<ConversionCandidate> {
        val n = reading.length
        if (n < 3) return emptyList()

        val beam = Array(n + 1) { mutableListOf<ViterbiPath>() }
        beam[0].add(ViterbiPath(0, "", emptyList(), false, lastRid = 0, firstBunsetsuSurface = "", firstBunsetsuReadingLen = 0, isFirstBunsetsuClosed = false, lastContentWordSurface = ""))

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

                    // Also query zero-heap mmap dictionary for subReading
                    val mmapWords = mmapDictionary?.getExactTokens(subReading)
                    if (mmapWords != null) {
                        for (tok in mmapWords) {
                            if (subWords.none { it.surface == tok.surface && it.lid == tok.lid }) {
                                subWords.add(WordEntry(tok.surface, tok.cost, "mmap", tok.lid, tok.rid))
                            }
                        }
                    }

                    if (subWords.isNotEmpty()) {
                        for (w in subWords.take(8)) {
                            val lengthBonus = if (j - i > 1) (j - i - 1) * 2000 else 0
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
                                    lastContentWordSurface = nextLastContent
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
                        lastContentWordSurface = path.lastContentWordSurface
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
        // Penalize repetitive first bunsetsu stems AND repetitive last content words (e.g. avoid repeating "〜幸喜").
        data class ScoredCandidate(val path: ViterbiPath, val effectiveCost: Int)
        val seenFullSurfaces = HashSet<String>()
        val firstStemCounts = HashMap<String, Int>()
        val lastContentCounts = HashMap<String, Int>()
        val scoredFullCandidates = mutableListOf<ScoredCandidate>()

        for (path in finalPaths) {
            if (path.surface.isNotBlank() &&
                path.surface != reading &&
                !JapaneseKanaUtils.isAllHiragana(path.surface) &&
                seenFullSurfaces.add(path.surface)) {
                val stem1 = if (path.firstBunsetsuSurface.isNotEmpty()) path.firstBunsetsuSurface else path.surface
                val count1 = firstStemCounts[stem1] ?: 0
                firstStemCounts[stem1] = count1 + 1

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
            ConversionCandidate(
                surface = it.path.surface,
                reading = reading,
                isPrediction = false,
                source = if (it.path.hasUserWord) "user" else "viterbi"
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
            betaMin[n] = 0

            for (m in (n - 1) downTo 1) {
                var bestCost = 100000
                var branchCount = 0
                var minSuffixWordCost = 100000

                var node: TrieNode? = root
                var j = m
                while (j < n) {
                    val ch = reading[j]
                    node = node?.children?.get(ch)
                    j++

                    val subReading = reading.substring(m, j)
                    val tokens = mutableListOf<WordEntry>()
                    node?.words?.let { tokens.addAll(it) }
                    mmapDictionary?.getExactTokens(subReading)?.let { mmapToks ->
                        for (tok in mmapToks) {
                            if (tokens.none { it.surface == tok.surface && it.lid == tok.lid }) {
                                tokens.add(WordEntry(tok.surface, tok.cost, "mmap", tok.lid, tok.rid))
                            }
                        }
                    }

                    if (tokens.isNotEmpty()) {
                        for (tok in tokens.take(8)) {
                            val lengthBonus = if (j - m > 1) (j - m - 1) * 2000 else 0
                            val transCost = if (matrix != null) {
                                if (j == n) matrix.getTransitionCost(tok.rid, 0) else 0
                            } else 0
                            val futureCost = tok.cost - lengthBonus + transCost + betaMin[j]
                            if (futureCost < bestCost) {
                                bestCost = futureCost
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
                branchFactor[m] = branchCount
            }

            // Gather candidate prefix surfaces across horizons m in (n-1 downTo 2)
            data class ScoredPrefix(
                val surface: String,
                val readingLen: Int,
                val score: Int
            )
            val candidatePrefixList = mutableListOf<ScoredPrefix>()
            val seenPrefixSurface = HashSet<String>()

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
                mmapDictionary?.getExactTokens(subReading)?.let { mmapToks ->
                    for (tok in mmapToks) {
                        if (directTokens.none { it.surface == tok.surface && it.lid == tok.lid }) {
                            directTokens.add(WordEntry(tok.surface, tok.cost, "mmap", tok.lid, tok.rid))
                        }
                    }
                }

                directTokens.sortBy { it.cost }
                for ((rank, tok) in directTokens.take(8).withIndex()) {
                    if (tok.surface.isNotBlank() &&
                        !JapaneseKanaUtils.isAllHiragana(tok.surface) &&
                        seenPrefixSurface.add(tok.surface)) {
                        val transCost = if (matrix != null) matrix.getTransitionCost(0, tok.lid) else 0
                        val score = (tok.cost - lengthBonus) + transCost + suffixCost - branchBonus + rank * 200
                        candidatePrefixList.add(ScoredPrefix(tok.surface, m, score))
                    }
                }
            }

            // Pass 2: Source B (Intermediate beam[m] paths for composite inflected bunsetsu prefixes)
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
                    val rLen = if (p.firstBunsetsuReadingLen in minPrefixLen..m) p.firstBunsetsuReadingLen else if (p.surface == surf) m else 0
                    if (rLen in minPrefixLen..m &&
                        surf.isNotBlank() &&
                        !JapaneseKanaUtils.isAllHiragana(surf) &&
                        seenPrefixSurface.add(surf)) {
                        val compositePenalty = if (p.words.size > 1) 3000 * (p.words.size - 1) else 0
                        val score = p.cost + compositePenalty + betaMin[rLen] - branchBonus
                        candidatePrefixList.add(ScoredPrefix(surf, rLen, score))
                    }
                }
            }

            // Also include firstBunsetsu from scored full paths if missed
            for (sc in scoredFullCandidates) {
                val fb = sc.path.firstBunsetsuSurface
                val fbLen = sc.path.firstBunsetsuReadingLen
                if (fb.isNotBlank() &&
                    fbLen in 2 until n &&
                    !JapaneseKanaUtils.isAllHiragana(fb) &&
                    seenPrefixSurface.add(fb)) {
                    val score = sc.path.cost - branchFactor[fbLen] * 200
                    candidatePrefixList.add(ScoredPrefix(fb, fbLen, score))
                }
            }

            candidatePrefixList.sortBy { it.score }
            for (sp in candidatePrefixList) {
                prefixCandidates.add(
                    ConversionCandidate(
                        surface = sp.surface,
                        reading = reading.substring(0, sp.readingLen),
                        isPrediction = false,
                        source = "viterbi_prefix"
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
