package com.example.myasr.context.english.infrastructure

import com.example.myasr.AsrApp
import com.example.myasr.context.english.domain.EnglishCaseModel
import com.example.myasr.context.vocabulary.domain.DictionaryEntry

import android.content.Context
import android.util.Log
import java.io.File

/**
 * FutoSwipeEngine provides offline, layout-agnostic neural swipe decoding for QWERTY keyboard.
 * Powered by FUTO Swipe technology (FUTO Model Weights License 1.0).
 */
class FutoSwipeEngine private constructor(private val context: Context) {

    data class SwipePoint(
        val x: Float,
        val y: Float,
        val t: Long
    )

    private var isInitialized = false

    companion object {
        private const val TAG = "FutoSwipeEngine"

        @Volatile
        private var instance: FutoSwipeEngine? = null

        fun getInstance(context: Context): FutoSwipeEngine {
            return instance ?: synchronized(this) {
                instance ?: FutoSwipeEngine(context.applicationContext).also { instance = it }
            }
        }

        private var isLibraryLoaded = false

        init {
            try {
                System.loadLibrary("myasr_llm")
                isLibraryLoaded = true
                Log.i(TAG, "Native library myasr_llm (with FUTO Swipe) loaded successfully")
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to load native library myasr_llm: ${e.message}")
            }
        }
    }

    /**
     * Initializes the native FUTO Swipe engine if models exist in external files dir.
     */
    @Synchronized
    fun ensureInitialized(): Boolean {
        if (!isLibraryLoaded) return false
        if (isInitialized && (try { nativeIsReady() } catch (_: Throwable) { false })) return true

        val modelDir = File(context.getExternalFilesDir(null), "futo_swipe")
        val encFile = File(modelDir, "encoder.onnx")
        val decFile = File(modelDir, "decoder.onnx")
        val clmFile = File(modelDir, "context_lm.onnx")
        val embFile = File(modelDir, "embeddings.onnx")
        val vocabFile = File(modelDir, "vocab.txt")

        if (!encFile.exists() || !decFile.exists() || !clmFile.exists() || !embFile.exists() || !vocabFile.exists()) {
            Log.d(TAG, "FUTO Swipe model files not complete in ${modelDir.absolutePath}")
            return false
        }

        return try {
            val success = nativeInitialize(modelDir.absolutePath)
            isInitialized = success
            if (success) {
                Log.i(TAG, "FUTO Swipe engine initialized successfully from ${modelDir.absolutePath}")
                pendingLayoutKeys?.let { keys ->
                    if (keys.size >= 52) {
                        try {
                            nativeSetLayoutKeys(keys)
                        } catch (t: Throwable) {
                            Log.w(TAG, "Failed to apply pending layout keys: ${t.message}")
                        }
                    }
                }
                try {
                    reloadExtendedLexicon(forceExport = false)
                } catch (e: Exception) {
                    Log.w(TAG, "Load extended swipe lexicon on init skipped: ${e.message}")
                }
                try {
                    val app = AsrApp.instanceOrNull
                    val active = app?.buildCombinedEnglishSwipeEntries() ?: emptyList()
                    syncUserDictionary(active)
                } catch (e: Exception) {
                    Log.w(TAG, "Sync user dictionary on init skipped: ${e.message}")
                }
                try {
                    val adaptive = AsrApp.instanceOrNull?.swipeAdaptiveCacheRepository?.getActiveSurfaces() ?: emptyList()
                    syncAdaptiveCache(adaptive)
                } catch (e: Exception) {
                    Log.w(TAG, "Sync adaptive cache on init skipped: ${e.message}")
                }
            } else {
                Log.w(TAG, "nativeInitialize returned false")
            }
            success
        } catch (t: Throwable) {
            Log.e(TAG, "Error initializing FUTO Swipe engine: ${t.message}", t)
            false
        }
    }

    private var pendingLayoutKeys: FloatArray? = null

    fun isReady(): Boolean = isLibraryLoaded && isInitialized && try { nativeIsReady() } catch (_: Throwable) { false }

    /**
     * Updates the normalized (x, y) centers for the 26 QWERTY alphabet keys ('a'..'z', size = 52).
     */
    fun updateLayoutKeys(keyCentersXy: FloatArray) {
        if (keyCentersXy.size < 52) return
        pendingLayoutKeys = keyCentersXy.copyOf()
        if (!isReady()) return
        try {
            nativeSetLayoutKeys(keyCentersXy)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to update layout keys in FUTO Swipe: ${t.message}", t)
        }
    }

    /**
     * Decodes a swipe trajectory into candidate words.
     * @param trajectory List of (x, y, timestamp) points in normalized QWERTY panel space.
     * @param previousWords Words preceding the current swipe for ContextLM rescoring.
     * @param beamWidth CTC beam width (default 100).
     * @param maxCandidates Maximum number of candidates to return (default 5).
     */
    fun decode(
        trajectory: List<SwipePoint>,
        previousWords: List<String> = emptyList(),
        beamWidth: Int = 100,
        maxCandidates: Int = 5
    ): List<String> {
        if (!isReady() || trajectory.size < 2) return emptyList()

        return try {
            val count = trajectory.size
            val xs = FloatArray(count)
            val ys = FloatArray(count)
            val ts = LongArray(count)

            for (i in 0 until count) {
                xs[i] = trajectory[i].x
                ys[i] = trajectory[i].y
                ts[i] = trajectory[i].t
            }

            val prevArray = previousWords.toTypedArray()
            val cands = nativeDecode(xs, ys, ts, prevArray, beamWidth, maxCandidates) ?: return emptyList()
            cands.toList()
        } catch (t: Throwable) {
            Log.e(TAG, "Swipe decode error: ${t.message}", t)
            emptyList()
        }
    }

    fun syncUserDictionary(entries: List<DictionaryEntry>) {
        if (!isReady()) return
        try {
            val englishEntries = entries.filter { it.isEnabled && it.hasEnglishSurface }
            val words = englishEntries.map { it.word }.toTypedArray()
            val prios = FloatArray(englishEntries.size) { englishEntries[it].priority.score }
            nativeSyncUserDictionary(words, prios)
            Log.d(TAG, "Synced ${englishEntries.size} user dictionary entries to native FUTO Swipe")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to sync user dictionary to FUTO Swipe: ${t.message}", t)
        }
    }

    fun syncAdaptiveCache(words: List<String>) {
        if (!isReady()) return
        try {
            nativeSyncAdaptiveCache(words.toTypedArray())
            Log.d(TAG, "Synced ${words.size} adaptive cache entries to native FUTO Swipe")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to sync adaptive cache to FUTO Swipe: ${t.message}", t)
        }
    }

    fun reloadExtendedLexicon(forceExport: Boolean = false): Boolean {
        if (!isLibraryLoaded) return false
        val modelDir = File(context.getExternalFilesDir(null), "futo_swipe")
        if (!modelDir.isDirectory) return false
        val extFile = File(modelDir, "extended_lexicon.tsv")
        return try {
            val exported = EnglishCaseModel.exportSwipeExtendedLexiconIfNeeded(extFile, force = forceExport)
            if (!isReady() || exported <= 0 || !extFile.exists() || extFile.length() == 0L) {
                false
            } else {
                nativeLoadExtendedLexicon(extFile.absolutePath)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to reload extended swipe lexicon: ${t.message}", t)
            false
        }
    }

    fun containsBaseWord(lowerWord: String): Boolean {
        if (!isReady() || lowerWord.isBlank()) return false
        return try {
            nativeContainsBaseWord(lowerWord.trim())
        } catch (_: Throwable) {
            false
        }
    }

    fun getExtendedWordCount(): Int {
        if (!isReady()) return 0
        return try {
            nativeGetExtendedWordCount()
        } catch (_: Throwable) {
            0
        }
    }

    fun release() {
        if (isLibraryLoaded && isInitialized) {
            try {
                nativeRelease()
            } catch (t: Throwable) {
                Log.w(TAG, "Error releasing FUTO Swipe: ${t.message}")
            }
            isInitialized = false
        }
    }

    private external fun nativeInitialize(modelDir: String): Boolean
    private external fun nativeIsReady(): Boolean
    private external fun nativeSyncUserDictionary(words: Array<String>, priorities: FloatArray)
    private external fun nativeSyncAdaptiveCache(words: Array<String>)
    private external fun nativeLoadExtendedLexicon(tsvPath: String): Boolean
    private external fun nativeContainsBaseWord(word: String): Boolean
    private external fun nativeGetExtendedWordCount(): Int
    private external fun nativeSetLayoutKeys(keyCentersXy: FloatArray)
    private external fun nativeDecode(
        xs: FloatArray,
        ys: FloatArray,
        ts: LongArray,
        previousWords: Array<String>,
        beamWidth: Int,
        maxCandidates: Int
    ): Array<String>?
    private external fun nativeRelease()
}
