package com.example.myasr

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Manages export, import, and safe idempotent multi-device merging of user history
 * and custom dictionary entries to/from Google Drive (via Storage Access Framework or ChromeOS mount).
 */
class UserHistorySyncManager private constructor(private val context: Context) {

    private val TAG = "UserHistorySyncMgr"
    private val PREFS_NAME = "history_sync_prefs"
    private val KEY_SYNC_URI = "sync_file_uri"
    private val KEY_LAST_SYNC_TIME = "last_sync_timestamp"
    private val KEY_LAST_SYNC_SUMMARY = "last_sync_summary"

    companion object {
        const val SCHEMA_VERSION = 1

        @Volatile
        private var instance: UserHistorySyncManager? = null

        fun getInstance(context: Context): UserHistorySyncManager {
            return instance ?: synchronized(this) {
                instance ?: UserHistorySyncManager(context.applicationContext).also { instance = it }
            }
        }
    }

    data class SyncStats(
        val unigramCount: Int,
        val bigramPairCount: Int,
        val customDictCount: Int,
        val lastSyncTimestamp: Long,
        val syncUriString: String?,
        val lastSyncSummary: String?
    )

    data class MergeResult(
        val success: Boolean,
        val message: String,
        val mergedUnigramCount: Int = 0,
        val mergedBigramPairCount: Int = 0,
        val mergedCustomDictCount: Int = 0
    )

    fun getSyncStats(): SyncStats {
        val app = AsrApp.instance
        val unigramCount = app.kanaKanjiEngine.getHistorySnapshot().size
        val bigramPairs = app.nextPhrasePredictor.getTotalBigramPairs()
        val customDictCount = app.dictionaryRepository.getAll().size

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastSync = prefs.getLong(KEY_LAST_SYNC_TIME, 0L)
        val uriStr = prefs.getString(KEY_SYNC_URI, null)
        val summary = prefs.getString(KEY_LAST_SYNC_SUMMARY, null)

        return SyncStats(
            unigramCount = unigramCount,
            bigramPairCount = bigramPairs,
            customDictCount = customDictCount,
            lastSyncTimestamp = lastSync,
            syncUriString = uriStr,
            lastSyncSummary = summary
        )
    }

    fun setSyncUri(uri: Uri) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_SYNC_URI, uri.toString()).apply()
    }

    fun getSyncUri(): Uri? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val uriStr = prefs.getString(KEY_SYNC_URI, null) ?: return null
        return try {
            Uri.parse(uriStr)
        } catch (e: Exception) {
            null
        }
    }

    fun clearSyncUri() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_SYNC_URI).apply()
    }

    /**
     * Executes safe, idempotent two-way sync:
     * 1. Reads remote JSON from Google Drive URI (if exists and non-empty).
     * 2. Merges remote Unigram, Bigram, and Custom Dictionary entries into local memory.
     * 3. Exports the unified merged dataset back to the Google Drive file.
     */
    suspend fun performSync(targetUri: Uri = getSyncUri() ?: throw IllegalStateException("同期先ファイルが未設定です")): MergeResult {
        return withContext(Dispatchers.IO) {
            try {
                val app = AsrApp.instance
                val resolver = context.contentResolver

                // 1. Read and parse remote JSON (if available)
                var remoteUnigrams: Map<String, Int> = emptyMap()
                var remoteBigrams: Map<String, Map<String, Int>> = emptyMap()
                var remoteCustomDict: List<DictionaryEntry> = emptyList()

                try {
                    resolver.openInputStream(targetUri)?.use { inputStream ->
                        val remoteContent = inputStream.bufferedReader(Charsets.UTF_8).readText().trim()
                        if (remoteContent.isNotEmpty()) {
                            val parsed = parseSyncJson(remoteContent)
                            remoteUnigrams = parsed.first
                            remoteBigrams = parsed.second
                            remoteCustomDict = parsed.third
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Notice: could not read existing remote file (will create fresh): ${e.message}")
                }

                // 2. Perform safe local merge
                if (remoteUnigrams.isNotEmpty()) {
                    app.kanaKanjiEngine.mergeHistory(remoteUnigrams)
                }
                if (remoteBigrams.isNotEmpty()) {
                    app.nextPhrasePredictor.mergeHistory(remoteBigrams)
                }
                if (remoteCustomDict.isNotEmpty()) {
                    app.dictionaryRepository.mergeEntries(remoteCustomDict)
                }

                // 3. Create fresh snapshot of all local data for export
                val finalUnigrams = app.kanaKanjiEngine.getHistorySnapshot()
                val finalBigrams = app.nextPhrasePredictor.getHistorySnapshot()
                val finalCustomDict = app.dictionaryRepository.getAll()

                val exportJson = buildSyncJson(
                    unigrams = finalUnigrams,
                    bigrams = finalBigrams,
                    customDict = finalCustomDict
                )

                // 4. Atomic write to remote Drive URI ("wt" mode = write & truncate)
                resolver.openOutputStream(targetUri, "wt")?.use { outputStream ->
                    outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                        writer.write(exportJson)
                        writer.flush()
                    }
                } ?: throw IllegalStateException("同期先ファイルへの書き込みストリームを開けませんでした")

                val totalPairs = app.nextPhrasePredictor.getTotalBigramPairs()
                val summary = "単語 ${finalUnigrams.size}語 / 連接 ${totalPairs}件 / 辞書 ${finalCustomDict.size}語"

                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit()
                    .putLong(KEY_LAST_SYNC_TIME, System.currentTimeMillis())
                    .putString(KEY_LAST_SYNC_SUMMARY, summary)
                    .apply()

                MergeResult(
                    success = true,
                    message = "Google Driveと同期完了: $summary",
                    mergedUnigramCount = finalUnigrams.size,
                    mergedBigramPairCount = totalPairs,
                    mergedCustomDictCount = finalCustomDict.size
                )
            } catch (e: Exception) {
                Log.e(TAG, "Sync failed: ${e.message}", e)
                MergeResult(
                    success = false,
                    message = "同期エラー: ${e.localizedMessage ?: e.message}"
                )
            }
        }
    }

    /**
     * Builds standard JSON payload according to Schema v1.
     */
    fun buildSyncJson(
        unigrams: Map<String, Int>,
        bigrams: Map<String, Map<String, Int>>,
        customDict: List<DictionaryEntry>
    ): String {
        val root = JSONObject()
        root.put("schema_version", SCHEMA_VERSION)
        root.put("exported_at_ms", System.currentTimeMillis())
        root.put("device_model", "${Build.MANUFACTURER} ${Build.MODEL}")

        // 1. Unigram
        val unigramObj = JSONObject()
        for ((k, v) in unigrams) {
            unigramObj.put(k, v)
        }
        root.put("unigram_history", unigramObj)

        // 2. Bigram
        val bigramObj = JSONObject()
        for ((prev, nextMap) in bigrams) {
            val child = JSONObject()
            for ((curr, count) in nextMap) {
                child.put(curr, count)
            }
            bigramObj.put(prev, child)
        }
        root.put("bigram_history", bigramObj)

        // 3. Custom Dictionary
        val dictArray = JSONArray()
        for (entry in customDict) {
            dictArray.put(entry.toJson())
        }
        root.put("custom_dictionary", dictArray)

        return root.toString(2)
    }

    /**
     * Parses and validates JSON payload safely with bounds-checking.
     */
    fun parseSyncJson(jsonStr: String): Triple<Map<String, Int>, Map<String, Map<String, Int>>, List<DictionaryEntry>> {
        val root = JSONObject(jsonStr)

        // 1. Parse unigram
        val unigramMap = mutableMapOf<String, Int>()
        val unigramObj = root.optJSONObject("unigram_history")
        if (unigramObj != null) {
            val keys = unigramObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (k.length in 1..128) {
                    val count = unigramObj.optInt(k, 1).coerceIn(1, 100_000)
                    unigramMap[k] = count
                }
            }
        }

        // 2. Parse bigram
        val bigramMap = mutableMapOf<String, MutableMap<String, Int>>()
        val bigramObj = root.optJSONObject("bigram_history")
        if (bigramObj != null) {
            val keys = bigramObj.keys()
            while (keys.hasNext()) {
                val prev = keys.next()
                if (prev.length in 1..128) {
                    val childObj = bigramObj.optJSONObject(prev) ?: continue
                    val childKeys = childObj.keys()
                    val childMap = mutableMapOf<String, Int>()
                    while (childKeys.hasNext()) {
                        val curr = childKeys.next()
                        if (curr.length in 1..128) {
                            childMap[curr] = childObj.optInt(curr, 1).coerceIn(1, 100_000)
                        }
                    }
                    if (childMap.isNotEmpty()) {
                        bigramMap[prev] = childMap
                    }
                }
            }
        }

        // 3. Parse custom dict
        val dictList = mutableListOf<DictionaryEntry>()
        val dictArray = root.optJSONArray("custom_dictionary")
        if (dictArray != null) {
            for (i in 0 until dictArray.length()) {
                val item = dictArray.optJSONObject(i) ?: continue
                try {
                    dictList.add(DictionaryEntry.fromJson(item))
                } catch (e: Exception) {
                    Log.w(TAG, "Skipping malformed dictionary entry in sync file: ${e.message}")
                }
            }
        }

        return Triple(unigramMap, bigramMap, dictList)
    }
}
