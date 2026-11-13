package com.example.myasr.context.speech.application

import com.example.myasr.context.kanakanji.domain.JapaneseKanaUtils
import com.example.myasr.context.speech.infrastructure.AsrManager
import com.example.myasr.context.vocabulary.domain.DictionaryEntry

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

class HotwordsGenerator(private val context: Context) {
    private val TAG = "HotwordsGenerator"
    private val HOTWORDS_FILE_NAME = "hotwords.txt"
    private var validTokensSet: Set<String>? = null

    init {
        loadTokensSet()
    }

    fun reloadTokensSet() {
        loadTokensSet()
    }

    private fun loadTokensSet() {
        try {
            val storageTokens = File(AsrManager(context).getStorageModelDir(), AsrManager.TOKENS)
            val inputStream = if (storageTokens.exists() && storageTokens.length() > 0) {
                storageTokens.inputStream()
            } else {
                try {
                    context.assets.open("reazonspeech/tokens.txt")
                } catch (e: Exception) {
                    null
                }
            }

            if (inputStream != null) {
                inputStream.use { stream ->
                    val reader = BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
                    val set = mutableSetOf<String>()
                    var line: String? = reader.readLine()
                    while (line != null) {
                        val parts = line.split("\t")
                        if (parts.isNotEmpty()) {
                            set.add(parts[0])
                        }
                        line = reader.readLine()
                    }
                    validTokensSet = set
                    Log.d(TAG, "Loaded ${set.size} valid tokens from tokens.txt")
                }
            } else {
                Log.w(TAG, "tokens.txt not found in storage or assets")
                validTokensSet = null
            }
        } catch (e: Exception) {
            Log.w(TAG, "tokens.txt loading failed: ${e.message}")
            validTokensSet = null
        }
    }

    fun getHotwordsFile(): File {
        return File(context.filesDir, HOTWORDS_FILE_NAME)
    }

    /**
     * Converts a string into space-separated characters recognized by cjkchar modeling unit.
     * e.g. "ReazonSpeech" -> "R e a z o n S p e e c h"
     * e.g. "中村" -> "中 村"
     * e.g. "なかむら" -> "な か む ら"
     *
     * Validates that each character exists in tokens.txt if loaded.
     */
    private fun toSpaceSeparatedTokens(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null

        val tokens = mutableListOf<String>()
        val set = validTokensSet

        for (ch in trimmed) {
            val tokenStr = ch.toString()
            if (tokenStr.isBlank()) continue
            if (set != null && !set.contains(tokenStr)) {
                Log.w(TAG, "Character '$ch' not found in tokens.txt, omitting from hotword rule")
                return null // Invalidate phrase if character is missing from vocabulary
            }
            tokens.add(tokenStr)
        }

        return if (tokens.isNotEmpty()) tokens.joinToString(" ") else null
    }

    /**
     * Builds space-separated hotword rules from active entries.
     * Each rule is formatted as: "<char> <char> ... :<score>"
     */
    fun buildHotwordRules(entries: List<DictionaryEntry>): List<String> {
        val activeEntries = entries.filter { it.isEnabled && it.word.isNotBlank() }
        if (activeEntries.isEmpty()) return emptyList()

        val lines = mutableListOf<String>()
        val seenPhrases = mutableSetOf<String>()

        for (entry in activeEntries) {
            val scoreStr = String.format("%.1f", entry.priority.score)
            val trimmedWord = entry.word.trim()
            val trimmedReading = JapaneseKanaUtils.normalizeReading(entry.reading)

            // 1. Surface Word (space-separated tokens) - highest priority boost
            val wordTokens = toSpaceSeparatedTokens(trimmedWord)
            if (wordTokens != null && seenPhrases.add(wordTokens)) {
                lines.add("$wordTokens :$scoreStr")
            }

            // 2. Hiragana Reading (space-separated tokens) - in-beam acoustic phonetic reinforcement
            val hiraganaTokens = toSpaceSeparatedTokens(trimmedReading)
            if (hiraganaTokens != null && seenPhrases.add(hiraganaTokens)) {
                lines.add("$hiraganaTokens :$scoreStr")
            }

            // 3. Katakana Reading (space-separated tokens)
            val katakana = JapaneseKanaUtils.hiraganaToKatakana(trimmedReading)
            val katakanaTokens = toSpaceSeparatedTokens(katakana)
            if (katakanaTokens != null && seenPhrases.add(katakanaTokens)) {
                lines.add("$katakanaTokens :$scoreStr")
            }
        }
        return lines
    }

    /**
     * Formats hotwords as a single string for createStream(hotwords).
     * In sherpa-onnx, hotword rules are separated by '/'.
     * Inside each rule, characters must be space-separated for cjkchar models.
     */
    fun getHotwordsString(entries: List<DictionaryEntry>): String {
        val rules = buildHotwordRules(entries)
        val result = rules.joinToString("/")
        Log.d(TAG, "Formatted hotwords string (${rules.size} rules): $result")
        return result
    }

    /**
     * Generates a hotwords file on disk from active entries.
     */
    fun generate(entries: List<DictionaryEntry>): File? {
        val rules = buildHotwordRules(entries)
        val file = getHotwordsFile()

        if (rules.isEmpty()) {
            if (file.exists()) {
                file.delete()
            }
            return null
        }

        try {
            file.writeText(rules.joinToString("\n") + "\n", Charsets.UTF_8)
            Log.i(TAG, "Generated ${rules.size} space-separated hotword rules in ${file.absolutePath}")
            return file
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write hotwords file: ${e.message}", e)
            return null
        }
    }
}
