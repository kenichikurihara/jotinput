package com.example.myasr

import android.content.Context
import android.util.Log
import org.json.JSONArray
import java.io.File

class DictionaryRepository(private val context: Context) {
    private val TAG = "DictionaryRepo"
    private val FILE_NAME = "custom_dictionary.json"
    private val entries = mutableListOf<DictionaryEntry>()

    init {
        load()
    }

    private fun getFile(): File {
        return File(context.filesDir, FILE_NAME)
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
    fun add(entry: DictionaryEntry): Boolean {
        // Prevent duplicate surface and reading
        val existingIndex = entries.indexOfFirst {
            (it.word.equals(entry.word, ignoreCase = true) && it.reading == entry.reading) ||
            (entry.isEnglishOnlyEntry && it.isEnglishOnlyEntry && it.reading.equals(entry.reading, ignoreCase = true))
        }
        if (existingIndex >= 0) {
            entries[existingIndex] = entry
        } else {
            entries.add(0, entry)
        }
        return save()
    }

    @Synchronized
    fun update(updated: DictionaryEntry): Boolean {
        val index = entries.indexOfFirst { it.id == updated.id }
        if (index >= 0) {
            entries[index] = updated
            return save()
        }
        return false
    }

    @Synchronized
    fun delete(id: String): Boolean {
        val removed = entries.removeAll { it.id == id }
        if (removed) {
            save()
        }
        return removed
    }

    @Synchronized
    fun toggle(id: String): Boolean {
        val index = entries.indexOfFirst { it.id == id }
        if (index >= 0) {
            val current = entries[index]
            entries[index] = current.copy(isEnabled = !current.isEnabled)
            return save()
        }
        return false
    }

    @Synchronized
    private fun load() {
        val file = getFile()
        entries.clear()

        if (!file.exists()) {
            // Fresh install: start with an empty dictionary by default
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
    private fun save(): Boolean {
        return try {
            val file = getFile()
            val jsonArray = JSONArray()
            for (entry in entries) {
                jsonArray.put(entry.toJson())
            }
            file.writeText(jsonArray.toString(2), Charsets.UTF_8)
            Log.d(TAG, "Saved ${entries.size} dictionary entries")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save dictionary: ${e.message}", e)
            false
        }
    }

    private fun getDefaultEntries(): List<DictionaryEntry> {
        // Starts empty by default
        return emptyList()
    }
}
