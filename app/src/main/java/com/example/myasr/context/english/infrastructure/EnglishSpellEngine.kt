package com.example.myasr.context.english.infrastructure

import com.example.myasr.AsrApp
import com.example.myasr.context.vocabulary.domain.DictionaryEntry

import android.content.Context
import android.util.Log
import java.io.File

/**
 * EnglishSpellEngine provides:
 * 1. Fast-path single-word spatial/edit-distance typo suggestions (< 1ms).
 * 2. On-device neural context spelling correction via GEC Transformer ONNX (~20ms).
 */
class EnglishSpellEngine private constructor(private val context: Context) {

    private var isInitialized = false

    companion object {
        private const val TAG = "EnglishSpellEngine"

        @Volatile
        private var instance: EnglishSpellEngine? = null

        fun getInstance(context: Context): EnglishSpellEngine {
            return instance ?: synchronized(this) {
                instance ?: EnglishSpellEngine(context.applicationContext).also { instance = it }
            }
        }

        init {
            try {
                System.loadLibrary("myasr_llm")
                Log.i(TAG, "Native library myasr_llm loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load native library myasr_llm: ${e.message}")
            }
        }
    }

    /**
     * Initializes the native English spell engine if models exist in external files dir.
     */
    @Synchronized
    fun ensureInitialized(): Boolean {
        if (isInitialized && nativeIsReady()) return true

        val modelDir = File(context.getExternalFilesDir(null), "english_spelling")
        val encFile = File(modelDir, "gec-encoder-int8.onnx")
        val decFile = File(modelDir, "gec-decoder-int8.onnx")
        val spmFile = File(modelDir, "spm_unigram.bin")

        if (!encFile.exists() || !decFile.exists() || !spmFile.exists()) {
            Log.d(TAG, "English spelling model files not complete in ${modelDir.absolutePath}")
            return false
        }

        return try {
            val success = nativeInitialize(modelDir.absolutePath)
            isInitialized = success
            if (success) {
                Log.i(TAG, "EnglishSpellEngine initialized successfully from ${modelDir.absolutePath}")
                try {
                    val active = AsrApp.instanceOrNull?.dictionaryRepository?.getActive() ?: emptyList()
                    syncUserDictionary(active)
                } catch (e: Exception) {
                    Log.w(TAG, "Sync user dictionary to spell engine skipped: ${e.message}")
                }
            } else {
                Log.w(TAG, "nativeInitialize returned false")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing EnglishSpellEngine: ${e.message}", e)
            false
        }
    }

    fun isReady(): Boolean = isInitialized && nativeIsReady()

    /**
     * Fast-path typo correction (< 1ms).
     * @param word Raw typed word (e.g., "teh", "quikc")
     * @param maxCandidates Number of candidates to return
     */
    fun suggestTypo(word: String, maxCandidates: Int = 5): List<String> {
        if (!isReady() || word.isBlank()) return emptyList()
        return nativeSuggestTypo(word.trim(), maxCandidates).toList()
    }

    /**
     * Context-aware neural sentence correction (~20ms).
     * @param text Full sentence or clause before cursor
     * @param lang Language code ("en")
     */
    fun correctSentence(text: String, lang: String = "en"): String {
        if (!isReady() || text.isBlank()) return text
        return nativeCorrectSentence(text.trim(), lang)
    }

    fun syncUserDictionary(entries: List<DictionaryEntry>) {
        if (!isReady()) return
        try {
            val englishEntries = entries.filter { it.isEnabled && it.hasEnglishSurface }
            val words = englishEntries.map { it.word }.toTypedArray()
            val prios = FloatArray(englishEntries.size) { englishEntries[it].priority.score }
            nativeSyncUserDictionary(words, prios)
            Log.d(TAG, "Synced ${englishEntries.size} user dictionary entries to native EnglishSpellEngine")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync user dictionary to EnglishSpellEngine: ${e.message}", e)
        }
    }

    // Native JNI functions
    private external fun nativeInitialize(modelDir: String): Boolean
    private external fun nativeIsReady(): Boolean
    private external fun nativeSyncUserDictionary(words: Array<String>, priorities: FloatArray)
    private external fun nativeSuggestTypo(word: String, maxCandidates: Int): Array<String>
    private external fun nativeCorrectSentence(text: String, lang: String): String
}
