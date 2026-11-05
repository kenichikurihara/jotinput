package com.example.myasr

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

        init {
            try {
                System.loadLibrary("myasr_llm")
                Log.i(TAG, "Native library myasr_llm (with FUTO Swipe) loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load native library myasr_llm: ${e.message}")
            }
        }
    }

    /**
     * Initializes the native FUTO Swipe engine if models exist in external files dir.
     */
    fun ensureInitialized(): Boolean {
        if (isInitialized && nativeIsReady()) return true

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
                try {
                    val active = AsrApp.instanceOrNull?.dictionaryRepository?.getActive() ?: emptyList()
                    syncUserDictionary(active)
                } catch (e: Exception) {
                    Log.w(TAG, "Sync user dictionary on init skipped: ${e.message}")
                }
            } else {
                Log.w(TAG, "nativeInitialize returned false")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing FUTO Swipe engine: ${e.message}", e)
            false
        }
    }

    fun isReady(): Boolean = isInitialized && nativeIsReady()

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
        return cands.toList()
    }

    fun syncUserDictionary(entries: List<DictionaryEntry>) {
        if (!isReady()) return
        try {
            val englishEntries = entries.filter { it.isEnabled && it.hasEnglishSurface }
            val words = englishEntries.map { it.word }.toTypedArray()
            val prios = FloatArray(englishEntries.size) { englishEntries[it].priority.score }
            nativeSyncUserDictionary(words, prios)
            Log.d(TAG, "Synced ${englishEntries.size} user dictionary entries to native FUTO Swipe")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync user dictionary to FUTO Swipe: ${e.message}", e)
        }
    }

    fun release() {
        if (isInitialized) {
            nativeRelease()
            isInitialized = false
        }
    }

    private external fun nativeInitialize(modelDir: String): Boolean
    private external fun nativeIsReady(): Boolean
    private external fun nativeSyncUserDictionary(words: Array<String>, priorities: FloatArray)
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
