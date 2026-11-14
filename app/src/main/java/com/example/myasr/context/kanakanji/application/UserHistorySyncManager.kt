package com.example.myasr.context.kanakanji.application

import com.example.myasr.AsrApp
import com.example.myasr.context.kanakanji.domain.HistorySyncDecisionModel
import com.example.myasr.context.kanakanji.domain.HistorySyncDecisionModel.SyncScheduleInput
import com.example.myasr.context.kanakanji.infrastructure.HistorySyncJobService
import com.example.myasr.context.vocabulary.domain.DictionaryEntry
import com.example.myasr.context.vocabulary.domain.DictionaryTombstone

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Manages export, import, periodic opportunistic auto-sync, and safe 3-Way Delta & Tombstone
 * merging of user history and custom dictionary entries to/from Google Drive (via SAF or DriveFS).
 */
class UserHistorySyncManager private constructor(private val context: Context) {

    private val TAG = "UserHistorySyncMgr"
    private val PREFS_NAME = "history_sync_prefs"
    private val KEY_SYNC_URI = "sync_file_uri"
    private val KEY_LAST_SYNC_TIME = "last_sync_timestamp"
    private val KEY_LAST_SYNC_SUMMARY = "last_sync_summary"
    private val KEY_AUTO_SYNC_ENABLED = "auto_sync_enabled"
    private val KEY_SYNC_INTERVAL_MINUTES = "sync_interval_minutes"
    private val KEY_DEVICE_ID = "sync_device_id"

    private val BASE_SNAPSHOT_FILE = "sync_base_snapshot.json"
    private val BACKUP_PREV_FILE = "sync_backup_prev.json"

    private val syncMutex = Mutex()
    private val scope = CoroutineScope(Dispatchers.IO)
    private var debouncedSyncJob: Job? = null

    @Volatile
    private var isHistoryDirty = false

    @Volatile
    private var isDictionaryDirty = false

    @Volatile
    private var lastSyncAttemptTime = 0L

    companion object {
        const val SCHEMA_VERSION = 2
        const val OPPORTUNISTIC_COOLDOWN_MS = 15L * 60 * 1000L // 15 minutes
        const val DEBOUNCE_DELAY_MS = 3000L // 3 seconds
        const val PERIODIC_CHECK_THRESHOLD_MS = 30L * 60 * 1000L // 30 minutes

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
        val lastSyncSummary: String?,
        val isAutoSyncEnabled: Boolean,
        val syncIntervalMinutes: Long = HistorySyncDecisionModel.DEFAULT_PERIODIC_INTERVAL_MINUTES,
        val hasValidPermission: Boolean = false
    )

    data class MergeResult(
        val success: Boolean,
        val message: String,
        val mergedUnigramCount: Int = 0,
        val mergedBigramPairCount: Int = 0,
        val mergedCustomDictCount: Int = 0
    )

    fun getDeviceId(): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var deviceId = prefs.getString(KEY_DEVICE_ID, null)
        if (deviceId == null) {
            deviceId = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply()
        }
        return deviceId
    }

    fun isAutoSyncEnabled(): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_AUTO_SYNC_ENABLED, true)
    }

    fun setAutoSyncEnabled(enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_AUTO_SYNC_ENABLED, enabled).apply()
        ensurePeriodicSyncSchedule()
        if (enabled && (isHistoryDirty || isDictionaryDirty)) {
            triggerOpportunisticSyncIfNeeded()
        }
    }

    fun getSyncIntervalMinutes(): Long {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getLong(
            KEY_SYNC_INTERVAL_MINUTES,
            HistorySyncDecisionModel.DEFAULT_PERIODIC_INTERVAL_MINUTES
        ).coerceAtLeast(HistorySyncDecisionModel.MIN_PERIODIC_INTERVAL_MINUTES)
    }

    fun setSyncIntervalMinutes(minutes: Long) {
        val clamped = minutes.coerceAtLeast(HistorySyncDecisionModel.MIN_PERIODIC_INTERVAL_MINUTES)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putLong(KEY_SYNC_INTERVAL_MINUTES, clamped).apply()
        ensurePeriodicSyncSchedule()
    }

    fun notifyHistoryMutated() {
        isHistoryDirty = true
    }

    fun notifyDictionaryMutated() {
        isDictionaryDirty = true
        if (isAutoSyncEnabled() && getSyncUri() != null) {
            scheduleDebouncedSync()
        }
    }

    /**
     * Schedules a debounced background sync (e.g. 3 seconds after dictionary mutation).
     */
    fun scheduleDebouncedSync(delayMs: Long = DEBOUNCE_DELAY_MS) {
        debouncedSyncJob?.cancel()
        debouncedSyncJob = scope.launch {
            delay(delayMs)
            val uri = getSyncUri() ?: return@launch
            if (isAutoSyncEnabled()) {
                performSync(uri, trigger = "debounced_dict_edit")
            }
        }
    }

    /**
     * Called when IME window is hidden / closed.
     * Opportunistically syncs if dirty and cooldown has elapsed.
     */
    fun triggerOpportunisticSyncIfNeeded() {
        if (!isAutoSyncEnabled()) return
        val uri = getSyncUri() ?: return

        val now = System.currentTimeMillis()
        val elapsedSinceLastAttempt = now - lastSyncAttemptTime
        if (elapsedSinceLastAttempt < OPPORTUNISTIC_COOLDOWN_MS) {
            return
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastSync = prefs.getLong(KEY_LAST_SYNC_TIME, 0L)
        val elapsedSinceLastSync = now - lastSync

        val shouldSync = isHistoryDirty || isDictionaryDirty || (elapsedSinceLastSync >= PERIODIC_CHECK_THRESHOLD_MS)
        if (!shouldSync) {
            return
        }

        scope.launch {
            performSync(uri, trigger = "ime_window_hidden")
        }
    }

    fun getSyncStats(): SyncStats {
        val app = AsrApp.instance
        val unigramCount = app.kanaKanjiEngine.getHistorySnapshot().size
        val bigramPairs = app.nextPhrasePredictor.getTotalBigramPairs()
        val customDictCount = app.dictionaryRepository.getAll().size

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastSync = prefs.getLong(KEY_LAST_SYNC_TIME, 0L)
        val uriStr = prefs.getString(KEY_SYNC_URI, null)
        val summary = prefs.getString(KEY_LAST_SYNC_SUMMARY, null)
        val autoSync = isAutoSyncEnabled()

        return SyncStats(
            unigramCount = unigramCount,
            bigramPairCount = bigramPairs,
            customDictCount = customDictCount,
            lastSyncTimestamp = lastSync,
            syncUriString = uriStr,
            lastSyncSummary = summary,
            isAutoSyncEnabled = autoSync,
            syncIntervalMinutes = getSyncIntervalMinutes(),
            hasValidPermission = hasValidSyncPermission()
        )
    }

    fun setSyncUri(uri: Uri) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val oldUri = prefs.getString(KEY_SYNC_URI, null)
        val newUriStr = uri.toString()
        if (oldUri != null && oldUri != newUriStr) {
            // Reset base snapshot when switching to a different Google Drive file
            // so 3-way delta never applies tombstones/deletes from the previous file.
            runCatching { File(context.filesDir, BASE_SNAPSHOT_FILE).delete() }
        }
        prefs.edit().putString(KEY_SYNC_URI, newUriStr).apply()
        ensurePeriodicSyncSchedule()
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
        runCatching { File(context.filesDir, BASE_SNAPSHOT_FILE).delete() }
        ensurePeriodicSyncSchedule()
    }

    fun ensurePeriodicSyncSchedule() {
        val decision = HistorySyncDecisionModel.evaluateSyncSchedule(
            SyncScheduleInput(
                autoSyncEnabled = isAutoSyncEnabled(),
                hasStoredUri = getSyncUri() != null,
                hasValidPermission = hasValidSyncPermission(),
                intervalMinutes = getSyncIntervalMinutes()
            )
        )
        if (decision.shouldBeScheduled) {
            HistorySyncJobService.schedulePeriodic(context, decision.effectiveIntervalMinutes)
        } else {
            HistorySyncJobService.cancel(context)
        }
    }

    /**
     * Verifies whether the stored SAF sync URI still holds valid persisted read/write permissions.
     * Essential for detecting Android Auto Backup reinstalls where SharedPreferences restores the
     * URI string, but OS-level persistable URI permissions are revoked.
     */
    fun hasValidSyncPermission(): Boolean {
        val uri = getSyncUri() ?: return false
        return try {
            val persisted = context.contentResolver.persistedUriPermissions
            persisted.any { it.uri == uri && it.isReadPermission && it.isWritePermission }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to verify persisted URI permission: ${e.message}")
            false
        }
    }

    /**
     * Executes safe, idempotent 3-Way Delta and LWW Tombstone sync:
     * 1. Reads remote JSON from Google Drive URI.
     * 2. Reads local sync base snapshot (previous sync point).
     * 3. Merges remote Unigram, Bigram, and Custom Dictionary entries using 3-Way Delta / LWW.
     * 4. Hot-reloads in-memory user dictionaries and ASR hotwords if dictionary mutated.
     * 5. Atomically exports the unified dataset back to the Drive file and updates Base Snapshot.
     */
    suspend fun performSync(
        targetUri: Uri = getSyncUri() ?: throw IllegalStateException("同期先ファイルが未設定です"),
        trigger: String = "manual"
    ): MergeResult {
        return withContext(Dispatchers.IO) {
            if (!syncMutex.tryLock()) {
                Log.d(TAG, "Sync already in progress; skipping duplicate trigger ($trigger)")
                return@withContext MergeResult(
                    success = true,
                    message = "別の同期処理が実行中です"
                )
            }

            try {
                lastSyncAttemptTime = System.currentTimeMillis()
                val app = AsrApp.instance
                val resolver = context.contentResolver

                // 1. Read base snapshot for 3-way delta
                val (baseUnigrams, baseBigrams, _) = loadBaseSnapshot()

                // 2. Read and parse remote JSON (if available)
                var remoteUnigrams: Map<String, Int> = emptyMap()
                var remoteBigrams: Map<String, Map<String, Int>> = emptyMap()
                var remoteCustomDict: List<DictionaryEntry> = emptyList()
                var remoteTombstones: List<DictionaryTombstone> = emptyList()

                try {
                    resolver.openInputStream(targetUri)?.use { inputStream ->
                        val remoteContent = inputStream.bufferedReader(Charsets.UTF_8).readText().trim()
                        if (remoteContent.isNotEmpty()) {
                            val parsed = parseSyncJson(remoteContent)
                            remoteUnigrams = parsed.first
                            remoteBigrams = parsed.second
                            remoteCustomDict = parsed.third.first
                            remoteTombstones = parsed.third.second
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Notice: could not read existing remote file (will create fresh): ${e.message}")
                }

                // 3. Perform 3-Way Delta local merge for Unigram and Bigram
                if (baseUnigrams.isNotEmpty() || remoteUnigrams.isNotEmpty()) {
                    app.kanaKanjiEngine.mergeHistoryDelta(remoteUnigrams, baseUnigrams)
                }
                if (baseBigrams.isNotEmpty() || remoteBigrams.isNotEmpty()) {
                    app.nextPhrasePredictor.mergeHistoryDelta(remoteBigrams, baseBigrams)
                }

                // 4. Perform LWW + Tombstone merge for Custom Dictionary
                val dictMutations = app.dictionaryRepository.mergeEntriesWithTombstones(
                    remoteCustomDict,
                    remoteTombstones
                )

                // 5. Hot-reload in-memory dictionaries and ASR hotwords if mutated
                if (dictMutations > 0) {
                    try {
                        app.syncAllUserDictionaries()
                        Log.i(TAG, "Hot-reloaded all user dictionaries and ASR hotwords after sync ($dictMutations changes)")
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not hot-reload user dictionaries: ${e.message}")
                    }
                }

                // 6. Create fresh snapshot of all local data for export
                val finalUnigrams = app.kanaKanjiEngine.getHistorySnapshot()
                val finalBigrams = app.nextPhrasePredictor.getHistorySnapshot()
                val finalCustomDict = app.dictionaryRepository.getAll()
                val finalTombstones = app.dictionaryRepository.getTombstones()

                val newRevisionId = UUID.randomUUID().toString()
                val exportJson = buildSyncJson(
                    unigrams = finalUnigrams,
                    bigrams = finalBigrams,
                    customDict = finalCustomDict,
                    tombstones = finalTombstones,
                    revisionId = newRevisionId
                )

                // 7. Safe atomic write: stage to local file first, then write to remote Drive URI
                val stagingFile = File(context.filesDir, "sync_staging_out.json")
                stagingFile.writeText(exportJson, Charsets.UTF_8)

                // Backup previous local state before committing
                saveBackupPrev(exportJson)

                resolver.openOutputStream(targetUri, "wt")?.use { outputStream ->
                    outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                        writer.write(exportJson)
                        writer.flush()
                    }
                } ?: throw IllegalStateException("同期先ファイルへの書き込みストリームを開けませんでした")

                stagingFile.delete()

                // 8. Commit new Base Snapshot for subsequent 3-way delta merges
                saveBaseSnapshot(finalUnigrams, finalBigrams, newRevisionId)

                // 9. Reset dirty flags and update sync timestamps
                isHistoryDirty = false
                isDictionaryDirty = false

                val totalPairs = app.nextPhrasePredictor.getTotalBigramPairs()
                val summary = "単語 ${finalUnigrams.size}語 / 連接 ${totalPairs}件 / 辞書 ${finalCustomDict.size}語"

                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit()
                    .putLong(KEY_LAST_SYNC_TIME, System.currentTimeMillis())
                    .putString(KEY_LAST_SYNC_SUMMARY, summary)
                    .apply()

                Log.i(TAG, "Google Drive sync succeeded via $trigger: $summary")
                MergeResult(
                    success = true,
                    message = "Google Driveと同期完了: $summary",
                    mergedUnigramCount = finalUnigrams.size,
                    mergedBigramPairCount = totalPairs,
                    mergedCustomDictCount = finalCustomDict.size
                )
            } catch (e: Exception) {
                Log.e(TAG, "Sync failed ($trigger): ${e.message}", e)
                MergeResult(
                    success = false,
                    message = "同期エラー: ${e.localizedMessage ?: e.message}"
                )
            } finally {
                syncMutex.unlock()
            }
        }
    }

    /**
     * Builds standard JSON payload according to Schema v2.
     */
    fun buildSyncJson(
        unigrams: Map<String, Int>,
        bigrams: Map<String, Map<String, Int>>,
        customDict: List<DictionaryEntry>,
        tombstones: List<DictionaryTombstone> = emptyList(),
        revisionId: String = UUID.randomUUID().toString()
    ): String {
        val root = JSONObject()
        root.put("schema_version", SCHEMA_VERSION)
        root.put("sync_revision_id", revisionId)
        root.put("exported_at_ms", System.currentTimeMillis())
        root.put("device_id", getDeviceId())
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

        // 4. Custom Dictionary Tombstones (Schema v2)
        val tombArray = JSONArray()
        for (t in tombstones) {
            tombArray.put(t.toJson())
        }
        root.put("custom_dictionary_tombstones", tombArray)

        return root.toString(2)
    }

    /**
     * Parses and validates JSON payload safely with bounds-checking and Schema v1 / v2 compatibility.
     */
    fun parseSyncJson(jsonStr: String): Triple<Map<String, Int>, Map<String, Map<String, Int>>, Pair<List<DictionaryEntry>, List<DictionaryTombstone>>> {
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

        // 4. Parse tombstones (Schema v2)
        val tombList = mutableListOf<DictionaryTombstone>()
        val tombArray = root.optJSONArray("custom_dictionary_tombstones")
        if (tombArray != null) {
            for (i in 0 until tombArray.length()) {
                val item = tombArray.optJSONObject(i) ?: continue
                try {
                    tombList.add(DictionaryTombstone.fromJson(item))
                } catch (e: Exception) {
                    Log.w(TAG, "Skipping malformed tombstone entry in sync file: ${e.message}")
                }
            }
        }

        return Triple(unigramMap, bigramMap, Pair(dictList, tombList))
    }

    private fun loadBaseSnapshot(): Triple<Map<String, Int>, Map<String, Map<String, Int>>, String?> {
        val file = File(context.filesDir, BASE_SNAPSHOT_FILE)
        if (!file.exists()) {
            return Triple(emptyMap(), emptyMap(), null)
        }
        return try {
            val content = file.readText(Charsets.UTF_8)
            val root = JSONObject(content)
            val revisionId = if (root.has("last_synced_revision_id")) root.getString("last_synced_revision_id") else null

            val unigramMap = mutableMapOf<String, Int>()
            val uObj = root.optJSONObject("base_unigram")
            if (uObj != null) {
                val keys = uObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    unigramMap[k] = uObj.optInt(k, 1)
                }
            }

            val bigramMap = mutableMapOf<String, MutableMap<String, Int>>()
            val bObj = root.optJSONObject("base_bigram")
            if (bObj != null) {
                val keys = bObj.keys()
                while (keys.hasNext()) {
                    val prev = keys.next()
                    val child = bObj.optJSONObject(prev) ?: continue
                    val childMap = mutableMapOf<String, Int>()
                    val cKeys = child.keys()
                    while (cKeys.hasNext()) {
                        val curr = cKeys.next()
                        childMap[curr] = child.optInt(curr, 1)
                    }
                    bigramMap[prev] = childMap
                }
            }

            Triple(unigramMap, bigramMap, revisionId)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load base snapshot: ${e.message}")
            Triple(emptyMap(), emptyMap(), null)
        }
    }

    private fun saveBaseSnapshot(
        unigrams: Map<String, Int>,
        bigrams: Map<String, Map<String, Int>>,
        revisionId: String
    ) {
        try {
            val root = JSONObject()
            root.put("last_synced_revision_id", revisionId)
            root.put("last_synced_at_ms", System.currentTimeMillis())

            val uObj = JSONObject()
            for ((k, v) in unigrams) {
                uObj.put(k, v)
            }
            root.put("base_unigram", uObj)

            val bObj = JSONObject()
            for ((prev, nextMap) in bigrams) {
                val child = JSONObject()
                for ((curr, count) in nextMap) {
                    child.put(curr, count)
                }
                bObj.put(prev, child)
            }
            root.put("base_bigram", bObj)

            val file = File(context.filesDir, BASE_SNAPSHOT_FILE)
            val tempFile = File(file.parentFile, "${file.name}.tmp")
            tempFile.writeText(root.toString(), Charsets.UTF_8)
            if (!tempFile.renameTo(file)) {
                tempFile.copyTo(file, overwrite = true)
                tempFile.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save base snapshot: ${e.message}")
        }
    }

    private fun saveBackupPrev(jsonStr: String) {
        try {
            val file = File(context.filesDir, BACKUP_PREV_FILE)
            file.writeText(jsonStr, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save backup: ${e.message}")
        }
    }
}

