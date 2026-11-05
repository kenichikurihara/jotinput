package com.example.myasr

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

enum class DictionaryType(
    val id: String,
    val displayName: String,
    val description: String,
    val estimatedWords: String,
    val estimatedDownloadSize: String,
    val downloadUrls: List<String>,
    val isBz2: Boolean,
    val isCustomParser: Boolean = false
) {
    MOZC_GENERAL(
        id = "mozc_general",
        displayName = "Mozc 基本・一般語彙",
        description = "日常語・動詞・助詞・慣用句などの標準日本語語彙 (全10部)",
        estimatedWords = "約129万語",
        estimatedDownloadSize = "約12 MB",
        downloadUrls = (0..9).map {
            "https://raw.githubusercontent.com/google/mozc/master/src/data/dictionary_oss/dictionary%02d.txt".format(it)
        },
        isBz2 = false
    ),
    SUDACHIDICT(
        id = "sudachidict",
        displayName = "SudachiDict 現代語・複合語・新語",
        description = "WorksApplicationsによる最新現代日本語・IT用語・日常複合語辞書 (高頻度更新)",
        estimatedWords = "約128万語",
        estimatedDownloadSize = "約11.5 MB",
        downloadUrls = listOf(
            "https://raw.githubusercontent.com/utuhiro78/mozcdic-ut-sudachidict/master/mozcdic-ut-sudachidict.txt.bz2"
        ),
        isBz2 = true
    ),
    JAWIKI(
        id = "jawiki",
        displayName = "Wikipedia 百科事典",
        description = "Wikipedia記事名・組織・歴史・文化・概念などの網羅的固有名詞",
        estimatedWords = "約125万語",
        estimatedDownloadSize = "約12 MB",
        downloadUrls = listOf(
            "https://raw.githubusercontent.com/utuhiro78/mozcdic-ut-jawiki/master/mozcdic-ut-jawiki.txt.bz2"
        ),
        isBz2 = true
    ),
    PLACE_NAMES(
        id = "place_names",
        displayName = "地名・駅名・郵便番号",
        description = "日本郵便データ・全国市区町村・町名・鉄道駅・施設ランドマーク",
        estimatedWords = "約21万語",
        estimatedDownloadSize = "約1.7 MB",
        downloadUrls = listOf(
            "https://raw.githubusercontent.com/utuhiro78/mozcdic-ut-place-names/master/mozcdic-ut-place-names.txt.bz2"
        ),
        isBz2 = true
    ),
    PERSONAL_NAMES(
        id = "personal_names",
        displayName = "人名・姓名辞書",
        description = "日本の名字・名前・著名人の姓名データ",
        estimatedWords = "約8.7万語",
        estimatedDownloadSize = "約0.7 MB",
        downloadUrls = listOf(
            "https://raw.githubusercontent.com/utuhiro78/mozcdic-ut-personal-names/master/mozcdic-ut-personal-names.txt.bz2"
        ),
        isBz2 = true
    ),
    EMOJI_SYMBOLS(
        id = "emoji_symbols",
        displayName = "絵文字・記号・単漢字",
        description = "読みからUnicode絵文字、顔文字、特殊記号、異体字・難読単漢字",
        estimatedWords = "約1万語",
        estimatedDownloadSize = "約0.1 MB",
        downloadUrls = listOf(
            "https://raw.githubusercontent.com/google/mozc/master/src/data/emoji/emoji_data.tsv",
            "https://raw.githubusercontent.com/google/mozc/master/src/data/emoticon/emoticon.tsv",
            "https://raw.githubusercontent.com/google/mozc/master/src/data/single_kanji/single_kanji.tsv",
            "https://raw.githubusercontent.com/google/mozc/master/src/data/symbol/symbol.tsv"
        ),
        isBz2 = false,
        isCustomParser = true
    );

    companion object {
        fun fromId(id: String): DictionaryType? = values().firstOrNull { it.id == id }
    }
}

class MultiDictionaryManager(private val context: Context) {

    private val TAG = "MultiDictManager"
    private val dbHelper = DictionaryDatabaseHelper.getInstance(context)

    fun getWordCount(type: DictionaryType): Int {
        return dbHelper.getWordCountBySource(type.id)
    }

    fun getTotalWordCount(): Int {
        return dbHelper.getTotalWordCount()
    }

    fun isDownloaded(type: DictionaryType): Boolean {
        return getWordCount(type) > 100
    }

    suspend fun deleteDictionary(type: DictionaryType) = withContext(Dispatchers.IO) {
        dbHelper.deleteBySource(type.id)
        KanaKanjiEngine.getInstance(context).clearMemoryCache()
    }

    suspend fun downloadDictionary(
        type: DictionaryType,
        onProgress: (percent: Int, statusText: String) -> Unit
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Starting download for ${type.displayName} (${type.id})")
            onProgress(5, "${type.displayName} を準備中...")

            // Clean previous entries for this source
            dbHelper.deleteBySource(type.id)

            var totalInserted = 0
            val urlCount = type.downloadUrls.size

            type.downloadUrls.forEachIndexed { index, urlStr ->
                val basePct = (index.toDouble() / urlCount * 85).toInt() + 5
                onProgress(basePct, "${type.displayName} データ取得中 (${index + 1}/$urlCount)...")

                val entries = mutableListOf<DictRecord>()
                val BATCH_SIZE = 5000

                openStreamForUrl(urlStr, type.isBz2) { inputStream ->
                    val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8), 32768)
                    var line: String?

                    while (reader.readLine().also { line = it } != null) {
                        val trimmed = line!!.trim()
                        if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

                        if (type.isCustomParser) {
                            parseCustomSpecialLine(urlStr, trimmed, type.id, entries)
                        } else {
                            parseStandardMozcLine(trimmed, type.id, entries)
                        }

                        if (entries.size >= BATCH_SIZE) {
                            dbHelper.insertBatch(entries)
                            totalInserted += entries.size
                            entries.clear()
                            val curPct = basePct + ((entries.size.toDouble() / 10000) * 10).toInt().coerceIn(0, 10)
                            onProgress(curPct.coerceIn(10, 90), "${type.displayName} 登録中... (${totalInserted}語)")
                        }
                    }

                    if (entries.isNotEmpty()) {
                        dbHelper.insertBatch(entries)
                        totalInserted += entries.size
                        entries.clear()
                    }
                }
            }

            onProgress(95, "変換エンジンへ反映中...")
            KanaKanjiEngine.getInstance(context).clearMemoryCache()

            onProgress(100, "完了 (${totalInserted}語)")
            Log.i(TAG, "Completed ${type.displayName}: $totalInserted words inserted")
            Result.success(totalInserted)
        } catch (e: Exception) {
            Log.e(TAG, "Failed downloading ${type.displayName}: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun downloadAllDictionaries(
        onProgress: (percent: Int, statusText: String) -> Unit
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val allTypes = DictionaryType.values()
            var grandTotal = 0

            allTypes.forEachIndexed { idx, type ->
                val stepBase = (idx.toDouble() / allTypes.size * 100).toInt()
                val stepRange = (1.0 / allTypes.size * 100).toInt()

                onProgress(stepBase, "ダウンロード中 (${idx + 1}/${allTypes.size}): ${type.displayName}")

                val result = downloadDictionary(type) { pct, msg ->
                    val overallPct = stepBase + ((pct.toDouble() / 100) * stepRange).toInt()
                    onProgress(overallPct.coerceIn(0, 99), msg)
                }

                if (result.isSuccess) {
                    grandTotal += result.getOrDefault(0)
                } else {
                    Log.w(TAG, "Partial failure on ${type.displayName}: ${result.exceptionOrNull()?.message}")
                }
            }

            KanaKanjiEngine.getInstance(context).clearMemoryCache()
            onProgress(100, "全辞書のインストールが完了しました (計 ${grandTotal}語)")
            Result.success(grandTotal)
        } catch (e: Exception) {
            Log.e(TAG, "Download all failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    private fun openStreamForUrl(
        urlStr: String,
        isBz2: Boolean,
        block: (InputStream) -> Unit
    ) {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 20000
        conn.readTimeout = 40000
        conn.setRequestProperty("User-Agent", "JotInput-Android/1.0")

        try {
            val responseCode = conn.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("HTTP $responseCode: ${conn.responseMessage} for $urlStr")
            }

            val rawStream = conn.inputStream
            val inputStream: InputStream = if (isBz2) {
                BZip2CompressorInputStream(rawStream)
            } else if (conn.contentEncoding?.equals("gzip", ignoreCase = true) == true) {
                GZIPInputStream(rawStream)
            } else {
                rawStream
            }

            inputStream.use { stream ->
                block(stream)
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun parseStandardMozcLine(
        trimmed: String,
        sourceType: String,
        outList: MutableList<DictRecord>
    ) {
        val parts = trimmed.split("\t")
        if (parts.size >= 5) {
            // reading, left_id, right_id, cost, surface
            val reading = parts[0]
            val mozcCost = parts[3].toIntOrNull() ?: 8000
            val score = (15000 - mozcCost).coerceIn(10, 10000)
            val surface = parts[4]
            if (reading.isNotBlank() && surface.isNotBlank()) {
                outList.add(DictRecord(reading, surface, score, sourceType))
            }
        } else if (parts.size >= 2) {
            val reading = parts[0]
            val surface = parts[1]
            val score = if (parts.size >= 3) parts[2].toIntOrNull() ?: 100 else 100
            if (reading.isNotBlank() && surface.isNotBlank()) {
                outList.add(DictRecord(reading, surface, score, sourceType))
            }
        }
    }

    private fun parseCustomSpecialLine(
        urlStr: String,
        trimmed: String,
        sourceType: String,
        outList: MutableList<DictRecord>
    ) {
        when {
            urlStr.contains("emoji_data.tsv") -> {
                val parts = trimmed.split("\t")
                if (parts.size >= 3) {
                    val emoji = parts[1]
                    val readings = parts[2].split(" ")
                    for (r in readings) {
                        val norm = JapaneseKanaUtils.normalizeReading(r)
                        if (norm.isNotBlank() && emoji.isNotBlank()) {
                            outList.add(DictRecord(norm, emoji, 5500, sourceType))
                        }
                    }
                }
            }
            urlStr.contains("emoticon.tsv") -> {
                val parts = trimmed.split("\t")
                if (parts.size >= 2) {
                    val emoticon = parts[0]
                    val readings = parts[1].split(" ")
                    for (r in readings) {
                        val norm = JapaneseKanaUtils.normalizeReading(r)
                        if (norm.isNotBlank() && emoticon.isNotBlank()) {
                            outList.add(DictRecord(norm, emoticon, 4500, sourceType))
                        }
                    }
                }
            }
            urlStr.contains("single_kanji.tsv") -> {
                val parts = trimmed.split("\t")
                if (parts.size >= 2) {
                    val reading = JapaneseKanaUtils.normalizeReading(parts[0])
                    val kanjiString = parts[1]
                    for (ch in kanjiString) {
                        outList.add(DictRecord(reading, ch.toString(), 3000, sourceType))
                    }
                }
            }
            urlStr.contains("symbol.tsv") -> {
                val parts = trimmed.split("\t")
                if (parts.size >= 2) {
                    val reading = JapaneseKanaUtils.normalizeReading(parts[0])
                    val symbol = parts[1]
                    if (reading.isNotBlank() && symbol.isNotBlank()) {
                        outList.add(DictRecord(reading, symbol, 4000, sourceType))
                    }
                }
            }
        }
    }
}
