package com.example.myasr

import android.content.Context
import android.util.Log
import org.json.JSONArray
import java.io.File

class DictionaryRepository(private val context: Context) {
    private val TAG = "DictionaryRepo"
    private val FILE_NAME = "custom_dictionary.json"
    private val TOMBSTONE_FILE_NAME = "custom_dictionary_tombstones.json"
    private val TOMBSTONE_TTL_MS = 30L * 24 * 60 * 60 * 1000L // 30 days

    private val entries = mutableListOf<DictionaryEntry>()
    private val tombstones = mutableListOf<DictionaryTombstone>()

    init {
        load()
        loadTombstones()
        purgeExpiredTombstones()
    }

    private fun getFile(): File {
        return File(context.filesDir, FILE_NAME)
    }

    private fun getTombstoneFile(): File {
        return File(context.filesDir, TOMBSTONE_FILE_NAME)
    }

    private fun makeIdentityKey(reading: String, word: String, isEnglishOnly: Boolean): String {
        return if (isEnglishOnly) {
            reading.lowercase()
        } else {
            "${reading.lowercase()}\u0000${word.lowercase()}"
        }
    }

    private fun makeIdentityKey(entry: DictionaryEntry): String {
        return makeIdentityKey(entry.reading, entry.word, entry.isEnglishOnlyEntry)
    }

    private fun makeIdentityKey(tombstone: DictionaryTombstone): String {
        val isEnglishOnly = EnglishDictionaryUtils.isValidEnglishWord(tombstone.word) &&
                !JapaneseKanaUtils.isValidKana(tombstone.reading)
        return makeIdentityKey(tombstone.reading, tombstone.word, isEnglishOnly)
    }

    @Synchronized
    fun getAll(): List<DictionaryEntry> {
        return entries.toList()
    }

    @Synchronized
    fun getActive(): List<DictionaryEntry> {
        return entries.filter { it.isEnabled }
    }

    @Synchronized
    fun getTombstones(): List<DictionaryTombstone> {
        return tombstones.toList()
    }

    @Synchronized
    fun add(entry: DictionaryEntry): Boolean {
        val entryWithTime = if (entry.updatedAtMillis <= 0L) {
            entry.copy(updatedAtMillis = System.currentTimeMillis())
        } else {
            entry
        }
        val key = makeIdentityKey(entryWithTime)

        // If reviving a previously deleted word, remove any matching tombstone
        tombstones.removeAll { makeIdentityKey(it) == key }
        saveTombstones()

        val existingIndex = entries.indexOfFirst { makeIdentityKey(it) == key }
        if (existingIndex >= 0) {
            entries[existingIndex] = entryWithTime
        } else {
            entries.add(0, entryWithTime)
        }
        val saved = save()
        if (saved) {
            notifyMutated()
        }
        return saved
    }

    @Synchronized
    fun update(updated: DictionaryEntry): Boolean {
        val index = entries.indexOfFirst { it.id == updated.id }
        if (index >= 0) {
            val entryWithTime = updated.copy(updatedAtMillis = System.currentTimeMillis())
            entries[index] = entryWithTime
            val saved = save()
            if (saved) {
                notifyMutated()
            }
            return saved
        }
        return false
    }

    @Synchronized
    fun delete(id: String): Boolean {
        val target = entries.find { it.id == id } ?: return false
        entries.remove(target)
        val tombstone = DictionaryTombstone(
            id = target.id,
            word = target.word,
            reading = target.reading,
            deletedAtMillis = System.currentTimeMillis()
        )
        tombstones.removeAll { makeIdentityKey(it) == makeIdentityKey(tombstone) }
        tombstones.add(tombstone)
        saveTombstones()
        val saved = save()
        if (saved) {
            notifyMutated()
        }
        return saved
    }

    @Synchronized
    fun toggle(id: String): Boolean {
        val index = entries.indexOfFirst { it.id == id }
        if (index >= 0) {
            val current = entries[index]
            entries[index] = current.copy(
                isEnabled = !current.isEnabled,
                updatedAtMillis = System.currentTimeMillis()
            )
            val saved = save()
            if (saved) {
                notifyMutated()
            }
            return saved
        }
        return false
    }

    private fun notifyMutated() {
        try {
            UserHistorySyncManager.getInstance(context).notifyDictionaryMutated()
        } catch (e: Exception) {
            Log.w(TAG, "Notice: could not notify sync manager of dictionary mutation: ${e.message}")
        }
    }

    @Synchronized
    private fun load() {
        val file = getFile()
        entries.clear()

        if (!file.exists()) {
            save()
            Log.d(TAG, "Initialized new empty dictionary")
            return
        }

        try {
            val content = file.readText(Charsets.UTF_8)
            val jsonArray = JSONArray(content)
            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.getJSONObject(i)
                entries.add(DictionaryEntry.fromJson(item))
            }
            Log.d(TAG, "Loaded ${entries.size} dictionary entries (preserving across updates)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load dictionary: ${e.message}", e)
            entries.clear()
            save()
        }
    }

    @Synchronized
    private fun loadTombstones() {
        val file = getTombstoneFile()
        tombstones.clear()
        if (!file.exists()) return

        try {
            val content = file.readText(Charsets.UTF_8)
            val jsonArray = JSONArray(content)
            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.getJSONObject(i)
                tombstones.add(DictionaryTombstone.fromJson(item))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load tombstones: ${e.message}")
        }
    }

    @Synchronized
    private fun saveTombstones(): Boolean {
        return try {
            val file = getTombstoneFile()
            val jsonArray = JSONArray()
            for (t in tombstones) {
                jsonArray.put(t.toJson())
            }
            val tempFile = File(file.parentFile, "${file.name}.tmp")
            tempFile.writeText(jsonArray.toString(2), Charsets.UTF_8)
            if (!tempFile.renameTo(file)) {
                tempFile.copyTo(file, overwrite = true)
                tempFile.delete()
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save tombstones: ${e.message}")
            false
        }
    }

    @Synchronized
    fun purgeExpiredTombstones(now: Long = System.currentTimeMillis()) {
        val threshold = now - TOMBSTONE_TTL_MS
        val removed = tombstones.removeAll { it.deletedAtMillis < threshold }
        if (removed) {
            saveTombstones()
        }
    }

    /**
     * Backward-compatible merge (delegates to mergeEntriesWithTombstones without remote tombstones).
     */
    @Synchronized
    fun mergeEntries(remoteEntries: List<DictionaryEntry>): Int {
        return mergeEntriesWithTombstones(remoteEntries, emptyList())
    }

    /**
     * Safe, idempotent Last-Write-Wins (LWW) merge with Tombstone deletion propagation.
     * Prevents deleted words from resurrecting and correctly propagates priority and toggle changes.
     */
    @Synchronized
    fun mergeEntriesWithTombstones(
        remoteEntries: List<DictionaryEntry>,
        remoteTombstones: List<DictionaryTombstone>
    ): Int {
        var mutationsCount = 0
        purgeExpiredTombstones()

        // 1. Merge tombstones: Keep newest deletion timestamp per identity key
        val tombstoneMap = LinkedHashMap<String, DictionaryTombstone>()
        for (lt in tombstones) {
            tombstoneMap[makeIdentityKey(lt)] = lt
        }
        for (rt in remoteTombstones) {
            val key = makeIdentityKey(rt)
            val existing = tombstoneMap[key]
            if (existing == null || rt.deletedAtMillis > existing.deletedAtMillis) {
                tombstoneMap[key] = rt
            }
        }

        // 2. Index local entries by identity key
        val localEntryMap = LinkedHashMap<String, DictionaryEntry>()
        for (e in entries) {
            localEntryMap[makeIdentityKey(e)] = e
        }

        // 3. Process remote entries against local entries and tombstones
        val allKeys = LinkedHashSet<String>()
        allKeys.addAll(localEntryMap.keys)
        for (re in remoteEntries) {
            allKeys.add(makeIdentityKey(re))
        }

        val finalEntries = mutableListOf<DictionaryEntry>()

        for (key in allKeys) {
            val local = localEntryMap[key]
            val remote = remoteEntries.firstOrNull { makeIdentityKey(it) == key }
            val tombstone = tombstoneMap[key]

            val tTomb = tombstone?.deletedAtMillis ?: -1L
            val tLocal = local?.updatedAtMillis ?: -1L
            val tRemote = remote?.updatedAtMillis ?: -1L
            val tEntryMax = maxOf(tLocal, tRemote)

            // Case 1: Deletion is strictly newer than any creation/edit -> Deleted
            if (tTomb > tEntryMax) {
                if (local != null) {
                    mutationsCount++
                }
                continue
            }

            // If entry was revived after tombstone, remove tombstone
            if (tombstone != null && tEntryMax > tTomb) {
                tombstoneMap.remove(key)
            }

            // Case 2: Only exists on remote
            if (local == null && remote != null) {
                finalEntries.add(remote)
                mutationsCount++
                continue
            }

            // Case 3: Only exists locally
            if (local != null && remote == null) {
                finalEntries.add(local)
                continue
            }

            // Case 4: Exists on both local and remote -> Compare timestamps
            if (local != null && remote != null) {
                when {
                    tRemote > tLocal -> {
                        // Remote is newer: adopt remote
                        finalEntries.add(remote)
                        if (local != remote) {
                            mutationsCount++
                        }
                    }
                    tLocal > tRemote -> {
                        // Local is newer: preserve local
                        finalEntries.add(local)
                    }
                    else -> {
                        // Timestamps equal or both 0: deterministic tie-break
                        val bestPriority = if (remote.priority == Priority.HIGH || local.priority == Priority.HIGH) {
                            Priority.HIGH
                        } else {
                            Priority.NORMAL
                        }
                        val mergedEnabled = local.isEnabled || remote.isEnabled
                        val nowMs = System.currentTimeMillis()
                        val merged = local.copy(
                            priority = bestPriority,
                            isEnabled = mergedEnabled,
                            updatedAtMillis = if (local.updatedAtMillis > 0L) local.updatedAtMillis else nowMs
                        )
                        finalEntries.add(merged)
                        if (merged != local) {
                            mutationsCount++
                        }
                    }
                }
            }
        }

        // Apply final state
        entries.clear()
        entries.addAll(finalEntries)
        tombstones.clear()
        tombstones.addAll(tombstoneMap.values)

        if (mutationsCount > 0) {
            save()
            saveTombstones()
        }

        return mutationsCount
    }

    @Synchronized
    private fun save(): Boolean {
        return try {
            val file = getFile()
            val jsonArray = JSONArray()
            for (entry in entries) {
                jsonArray.put(entry.toJson())
            }
            val tempFile = File(file.parentFile, "${file.name}.tmp")
            tempFile.writeText(jsonArray.toString(2), Charsets.UTF_8)
            if (!tempFile.renameTo(file)) {
                tempFile.copyTo(file, overwrite = true)
                tempFile.delete()
            }
            Log.d(TAG, "Saved ${entries.size} dictionary entries")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save dictionary: ${e.message}", e)
            false
        }
    }
}

