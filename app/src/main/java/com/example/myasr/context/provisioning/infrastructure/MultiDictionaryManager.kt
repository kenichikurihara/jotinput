package com.example.myasr.context.provisioning.infrastructure

import com.example.myasr.AsrApp
import com.example.myasr.context.kanakanji.domain.JapaneseKanaUtils
import com.example.myasr.context.kanakanji.domain.KanaKanjiEngine
import com.example.myasr.context.kanakanji.domain.MozcPosCatalog
import com.example.myasr.context.kanakanji.infrastructure.MmapDictionaryCompiler

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
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

    init {
        cleanupLegacyDatabase()
    }

    private fun cleanupLegacyDatabase() {
        try {
            val dbFile = context.getDatabasePath("mozc_dictionaries.db")
            if (dbFile.exists()) {
                val sizeMb = dbFile.length() / (1024 * 1024)
                context.deleteDatabase("mozc_dictionaries.db")
                Log.i(TAG, "Deleted legacy SQLite database ($sizeMb MB freed)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Legacy DB cleanup failed: ${e.message}")
        }
    }

    fun getDictFile(type: DictionaryType): java.io.File {
        val dir = KanaKanjiEngine.getInstance(context).getMozcDir()
        return java.io.File(dir, "dict_${type.id}.bin")
    }

    private fun readTokensCountFromHeader(file: java.io.File): Int {
        try {
            java.io.RandomAccessFile(file, "r").use { raf ->
                if (raf.length() >= 64) {
                    raf.seek(16)
                    val b0 = raf.read()
                    val b1 = raf.read()
                    val b2 = raf.read()
                    val b3 = raf.read()
                    if (b0 >= 0 && b1 >= 0 && b2 >= 0 && b3 >= 0) {
                        return (b0) or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
                    }
                }
            }
        } catch (_: Exception) {}
        return 0
    }

    fun getWordCount(type: DictionaryType): Int {
        val file = getDictFile(type)
        if (!file.exists() || file.length() < 64) {
            if (type == DictionaryType.MOZC_GENERAL) {
                val legacy = java.io.File(file.parentFile, "system_dictionary.bin")
                if (legacy.exists() && legacy.length() >= 64) {
                    return readTokensCountFromHeader(legacy)
                }
            }
            return 0
        }
        return readTokensCountFromHeader(file)
    }

    fun getTotalWordCount(): Int {
        return DictionaryType.values().sumOf { getWordCount(it) }
    }

    fun isDownloaded(type: DictionaryType): Boolean {
        return getWordCount(type) > 100
    }

    suspend fun deleteDictionary(type: DictionaryType) = withContext(Dispatchers.IO) {
        val file = getDictFile(type)
        if (file.exists()) file.delete()
        if (type == DictionaryType.MOZC_GENERAL) {
            val legacy = java.io.File(file.parentFile, "system_dictionary.bin")
            if (legacy.exists()) legacy.delete()
        }
        AsrApp.instanceOrNull?.dictionaryUpdateTracker?.clearLocalUpdatedAt(type.id)
        KanaKanjiEngine.getInstance(context).reloadDictionaries()
    }

    suspend fun downloadDictionary(
        type: DictionaryType,
        onProgress: (percent: Int, statusText: String) -> Unit
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Starting download for ${type.displayName} (${type.id})")
            onProgress(5, "${type.displayName} を準備中...")

            val builder = MmapDictionaryCompiler.StreamingBuilder(initialCapacity = 16384)
            var totalReadLines = 0
            val urlCount = type.downloadUrls.size

            type.downloadUrls.forEachIndexed { index, urlStr ->
                val basePct = (index.toDouble() / urlCount * 80).toInt() + 5
                onProgress(basePct, "${type.displayName} データ取得中 (${index + 1}/$urlCount)...")

                openStreamForUrl(urlStr, type.isBz2) { inputStream ->
                    val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8), 32768)
                    var line: String?

                    while (reader.readLine().also { line = it } != null) {
                        ensureActive()
                        val trimmed = line!!.trim()
                        if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

                        totalReadLines++
                        if (type.isCustomParser) {
                            parseCustomSpecialLine(urlStr, trimmed, builder)
                        } else {
                            parseStandardMozcLine(trimmed, builder)
                        }

                        if (totalReadLines % 50000 == 0) {
                            onProgress(basePct + 2, "${type.displayName} 解析中... (${totalReadLines}行)")
                        }
                    }
                }
            }

            ensureActive()
            if (builder.tokenCount <= 0) {
                return@withContext Result.failure(IllegalStateException("${type.displayName} のデータが空でした"))
            }

            onProgress(88, "バイナリ辞書をコンパイル中 (MYASRDIC v1)...")
            val targetFile = getDictFile(type)
            val compileResult = builder.compileToFile(
                targetFile,
                isCancelled = { !isActive }
            )

            if (compileResult.isFailure) {
                return@withContext compileResult
            }
            val totalTokens = compileResult.getOrDefault(0)
            if (totalTokens <= 0) {
                return@withContext Result.failure(IllegalStateException("${type.displayName} のコンパイル結果が0語でした"))
            }

            onProgress(96, "変換エンジンへ反映中...")
            KanaKanjiEngine.getInstance(context).reloadDictionaries()
            AsrApp.instanceOrNull?.dictionaryUpdateTracker?.markDictionaryUpdated(type.id)

            onProgress(100, "完了 (${totalTokens}語)")
            Log.i(TAG, "Completed ${type.displayName}: $totalTokens tokens compiled to ${targetFile.name}")
            Result.success(totalTokens)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.e(TAG, "Failed downloading ${type.displayName}: ${t.message}", t)
            val ex = if (t is Exception) t else RuntimeException(t.message ?: t.javaClass.simpleName, t)
            Result.failure(ex)
        }
    }

    suspend fun downloadAllDictionaries(
        onProgress: (percent: Int, statusText: String) -> Unit
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val allTypes = DictionaryType.values()
            var grandTotal = 0
            val failedDicts = mutableListOf<String>()

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
                    failedDicts.add(type.displayName)
                    Log.w(TAG, "Partial failure on ${type.displayName}: ${result.exceptionOrNull()?.message}")
                }
            }

            KanaKanjiEngine.getInstance(context).reloadDictionaries()
            if (failedDicts.isNotEmpty()) {
                val msg = "一部の辞書の導入に失敗しました (${failedDicts.joinToString("・")})"
                onProgress(100, msg)
                Result.failure(IllegalStateException(msg))
            } else {
                onProgress(100, "全辞書のインストールが完了しました (計 ${grandTotal}語)")
                Result.success(grandTotal)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.e(TAG, "Download all failed: ${t.message}", t)
            val ex = if (t is Exception) t else RuntimeException(t.message ?: t.javaClass.simpleName, t)
            Result.failure(ex)
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
                BZip2InputStream(rawStream, decompressConcatenated = true)
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
        builder: MmapDictionaryCompiler.StreamingBuilder
    ) {
        val parts = trimmed.split("\t")
        if (parts.size >= 5) {
            // reading, left_id, right_id, cost, surface
            val reading = JapaneseKanaUtils.normalizeReading(parts[0])
            val lid = parts[1].toIntOrNull() ?: MozcPosCatalog.POS_NOUN_GENERAL
            val rid = parts[2].toIntOrNull() ?: MozcPosCatalog.POS_NOUN_GENERAL
            val cost = parts[3].toIntOrNull() ?: 8000
            val surface = parts[4]
            if (reading.isNotBlank() && surface.isNotBlank()) {
                builder.addToken(reading, surface, cost, lid, rid)
            }
        } else if (parts.size >= 2) {
            val reading = JapaneseKanaUtils.normalizeReading(parts[0])
            val surface = parts[1]
            val cost = if (parts.size >= 3) parts[2].toIntOrNull() ?: 6000 else 6000
            if (reading.isNotBlank() && surface.isNotBlank()) {
                builder.addToken(reading, surface, cost, MozcPosCatalog.POS_NOUN_GENERAL, MozcPosCatalog.POS_NOUN_GENERAL)
            }
        }
    }

    private fun parseCustomSpecialLine(
        urlStr: String,
        trimmed: String,
        builder: MmapDictionaryCompiler.StreamingBuilder
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
                            builder.addToken(norm, emoji, 5500, MozcPosCatalog.POS_SYMBOL, MozcPosCatalog.POS_SYMBOL)
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
                            builder.addToken(norm, emoticon, 4500, MozcPosCatalog.POS_SYMBOL, MozcPosCatalog.POS_SYMBOL)
                        }
                    }
                }
            }
            urlStr.contains("single_kanji.tsv") -> {
                val parts = trimmed.split("\t")
                if (parts.size >= 2) {
                    val reading = JapaneseKanaUtils.normalizeReading(parts[0])
                    val kanjiString = parts[1]
                    if (reading.isNotBlank()) {
                        kanjiString.forEachIndexed { idx, ch ->
                            val kanjiCost = (7500 + idx * 120).coerceAtMost(14000)
                            builder.addToken(reading, ch.toString(), kanjiCost, MozcPosCatalog.POS_NOUN_GENERAL, MozcPosCatalog.POS_NOUN_GENERAL)
                        }
                    }
                }
            }
            urlStr.contains("symbol.tsv") -> {
                val parts = trimmed.split("\t")
                if (parts.size >= 2) {
                    val reading = JapaneseKanaUtils.normalizeReading(parts[0])
                    val symbol = parts[1]
                    if (reading.isNotBlank() && symbol.isNotBlank()) {
                        builder.addToken(reading, symbol, 4000, MozcPosCatalog.POS_SYMBOL, MozcPosCatalog.POS_SYMBOL)
                    }
                }
            }
        }
    }
}
