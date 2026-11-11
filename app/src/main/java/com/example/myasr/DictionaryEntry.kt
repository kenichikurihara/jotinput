package com.example.myasr

import org.json.JSONObject
import java.util.UUID

enum class Priority(val displayName: String, val score: Float) {
    LOW("低", 1.5f),
    NORMAL("標準", 3.0f),
    HIGH("高", 4.5f);

    companion object {
        fun fromString(name: String?): Priority {
            return entries.find { it.name.equals(name, ignoreCase = true) } ?: NORMAL
        }
    }
}

data class DictionaryEntry(
    val id: String = UUID.randomUUID().toString(),
    val word: String,           // Surface form: e.g. "中村", "ReazonSpeech", "貴社"
    val reading: String,        // Normalized reading: e.g. "なかむら", "reazonspeech"
    val priority: Priority = Priority.NORMAL,
    val isEnabled: Boolean = true,
    val updatedAtMillis: Long = System.currentTimeMillis()
) {
    val isJapaneseEntry: Boolean
        get() = JapaneseKanaUtils.isValidKana(reading)

    val isEnglishOnlyEntry: Boolean
        get() = !isJapaneseEntry && EnglishDictionaryUtils.isValidEnglishWord(word)

    val hasEnglishSurface: Boolean
        get() = EnglishDictionaryUtils.isValidEnglishWord(word)

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("word", word)
            put("reading", reading)
            put("priority", priority.name)
            put("isEnabled", isEnabled)
            put("updatedAtMillis", updatedAtMillis)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): DictionaryEntry {
            return DictionaryEntry(
                id = json.optString("id", UUID.randomUUID().toString()),
                word = json.getString("word"),
                reading = json.getString("reading"),
                priority = Priority.fromString(json.optString("priority", "NORMAL")),
                isEnabled = json.optBoolean("isEnabled", true),
                updatedAtMillis = json.optLong("updatedAtMillis", 0L)
            )
        }
    }
}

data class DictionaryTombstone(
    val id: String,
    val word: String,
    val reading: String,
    val deletedAtMillis: Long
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("word", word)
            put("reading", reading)
            put("deletedAtMillis", deletedAtMillis)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): DictionaryTombstone {
            return DictionaryTombstone(
                id = json.optString("id", UUID.randomUUID().toString()),
                word = json.getString("word"),
                reading = json.getString("reading"),
                deletedAtMillis = json.optLong("deletedAtMillis", System.currentTimeMillis())
            )
        }
    }
}

