package com.example.myasr

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * High-performance on-device SLM candidate re-ranking engine using llama.cpp and Qwen2.5-0.5B.
 * Computes causal token log-likelihood conditioned on the preceding context prefix.
 */
class LlmReranker private constructor(private val context: Context) {

    private val TAG = "LlmReranker"

    // Kotlin-level memoized NLL cache (contextHash:surface -> complete NLL)
    private val memoizedNllCache = android.util.LruCache<String, Float>(512)

    data class FastRerankResult(
        val candidates: List<ConversionCandidate>,
        val isFullyComplete: Boolean,
        val slmUsed: Boolean
    )

    enum class State {
        UNINITIALIZED,
        INITIALIZING,
        READY,
        ERROR
    }

    @Volatile
    var state: State = State.UNINITIALIZED
        private set

    fun isReady(): Boolean = state == State.READY

    private var currentModelPath: String? = null

    companion object {
        @Volatile
        private var instance: LlmReranker? = null
        private var nativeLibraryLoaded = false

        init {
            try {
                System.loadLibrary("myasr_llm")
                nativeLibraryLoaded = true
            } catch (e: UnsatisfiedLinkError) {
                Log.w("LlmReranker", "Native library libmyasr_llm.so not available yet: ${e.message}")
            }
        }

        fun getInstance(context: Context): LlmReranker {
            return instance ?: synchronized(this) {
                instance ?: LlmReranker(context.applicationContext).also { instance = it }
            }
        }
    }

    /**
     * Checks if the SLM model file is installed in the app's internal or external storage.
     * @param fileName Optional filename. Defaults to the configured engine model or 0.5B.
     */
    fun getModelFile(fileName: String = "qwen2.5-0.5b-instruct-q4_k_m.gguf"): File {
        // 1. Internal app files directory
        val internalFile = File(File(context.filesDir, "models"), fileName)
        if (internalFile.exists() && internalFile.length() > 10 * 1024 * 1024) {
            return internalFile
        }

        // 2. App-specific external files dir (/sdcard/Android/data/com.example.myasr/files/models/)
        val appExternalDir = context.getExternalFilesDir("models")
        if (appExternalDir != null) {
            val appExternalFile = File(appExternalDir, fileName)
            if (appExternalFile.exists() && appExternalFile.length() > 10 * 1024 * 1024) {
                return appExternalFile
            }
        }

        // 3. Public Download directory (/sdcard/Download/)
        val publicDownload = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), fileName)
        if (publicDownload.exists() && publicDownload.length() > 10 * 1024 * 1024) {
            return publicDownload
        }

        // Default to internal
        val modelsDir = File(context.filesDir, "models")
        if (!modelsDir.exists()) {
            modelsDir.mkdirs()
        }
        return internalFile
    }

    fun isModelInstalled(fileName: String = "qwen2.5-0.5b-instruct-q4_k_m.gguf"): Boolean {
        val file = getModelFile(fileName)
        return file.exists() && file.length() > 10 * 1024 * 1024 // > 10MB
    }

    fun getTargetModelFileName(): String {
        val selected = KeyboardHeightManager.getRerankEngineType(context)
        return if (selected == RerankEngineType.LOCAL_SLM_1_5B) {
            "qwen2.5-1.5b-instruct-q4_k_m.gguf"
        } else {
            "qwen2.5-0.5b-instruct-q4_k_m.gguf"
        }
    }

    /**
     * Asynchronously initializes the SLM model in the background if the model file is available.
     */
    fun initializeIfAvailable(
        scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
        modelFileName: String? = null,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        if (!nativeLibraryLoaded) {
            Log.w(TAG, "Cannot initialize: native library libmyasr_llm is not loaded.")
            state = State.ERROR
            onComplete?.invoke(false)
            return
        }

        val targetName = modelFileName ?: getTargetModelFileName()
        val file = getModelFile(targetName)
        if (!file.exists() || file.length() == 0L) {
            Log.d(TAG, "SLM model file not present: ${file.absolutePath}. Re-ranking is disabled.")
            state = State.UNINITIALIZED
            onComplete?.invoke(false)
            return
        }

        if (state == State.READY && currentModelPath == file.absolutePath) {
            onComplete?.invoke(true)
            return
        }

        if (state == State.INITIALIZING) {
            return
        }

        state = State.INITIALIZING
        scope.launch(Dispatchers.IO) {
            try {
                Log.i(TAG, "Loading SLM model from ${file.absolutePath}...")
                val success = nativeInit(file.absolutePath, 4)
                if (success) {
                    state = State.READY
                    currentModelPath = file.absolutePath
                    isWarmedUp = false
                    Log.i(TAG, "SLM model loaded successfully and ready for re-ranking.")
                } else {
                    state = State.ERROR
                    Log.e(TAG, "nativeInit failed to load model.")
                }
                onComplete?.invoke(success)
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing SLM model: ${e.message}", e)
                state = State.ERROR
                onComplete?.invoke(false)
            }
        }
    }

    @Volatile
    private var isWarmedUp = false

    /**
     * Non-blocking background warm-up: ensures SLM model weights are mapped into memory,
     * thread pools spawned, and executes a dummy scoring pass to initialize llama.cpp KV cache
     * and graph execution structures. Drops subsequent first-query latency from ~11.7s to ~1ms.
     */
    fun warmUp(scope: CoroutineScope = CoroutineScope(Dispatchers.Default), onComplete: ((Boolean) -> Unit)? = null) {
        val targetName = getTargetModelFileName()
        if (!nativeLibraryLoaded || !isModelInstalled(targetName)) {
            onComplete?.invoke(false)
            return
        }
        val file = getModelFile(targetName)
        if (isWarmedUp && state == State.READY && currentModelPath == file.absolutePath) {
            onComplete?.invoke(true)
            return
        }

        initializeIfAvailable(scope, targetName) { ready ->
            if (ready) {
                scope.launch(Dispatchers.Default) {
                    try {
                        val dummyCands = listOf(
                            ConversionCandidate("今日", "きょう"),
                            ConversionCandidate("教", "きょう")
                        )
                        rerank("<s>", dummyCands)
                        isWarmedUp = true
                        Log.i(TAG, "LlmReranker warm-up scoring completed successfully.")
                        onComplete?.invoke(true)
                    } catch (e: Exception) {
                        Log.w(TAG, "LlmReranker warm-up scoring failed: ${e.message}")
                        onComplete?.invoke(false)
                    }
                }
            } else {
                onComplete?.invoke(false)
            }
        }
    }

    fun requestAbortScoring() {
        if (state == State.READY && nativeLibraryLoaded) {
            try {
                nativeRequestAbortScoring()
            } catch (e: Exception) {
                Log.w(TAG, "Error in requestAbortScoring: ${e.message}")
            }
        }
    }

    /**
     * Tries fast synchronous candidate scoring directly from cached context logits on Main Thread (< 0.1ms).
     * If all candidates are 1-token or already memoized, returns complete final ranking immediately.
     * Otherwise returns approximate ranking for immediate display while Stage 2 Tree batch decodes in background.
     */
    fun tryFastRerankWithStatus(
        contextPrefix: String,
        candidates: List<ConversionCandidate>,
        currentShownTop1: String? = null,
        weightAlpha: Float = KeyboardHeightManager.DEFAULT_SLM_WEIGHT_ALPHA,
        lambdaObs: Float = KeyboardHeightManager.DEFAULT_SLM_LAMBDA_OBS,
        gammaUser: Float = KeyboardHeightManager.DEFAULT_SLM_GAMMA_USER
    ): FastRerankResult? {
        if (state != State.READY || !nativeLibraryLoaded || candidates.size <= 1) {
            return null
        }

        try {
            val nonHiraganaCandidates = candidates.filter {
                val isRedundantHiragana = JapaneseKanaUtils.isAllHiragana(it.surface) && it.surface == it.reading
                !isRedundantHiragana
            }
            if (nonHiraganaCandidates.size <= 1) {
                return null
            }

            val topPool = nonHiraganaCandidates.take(8)
            val restPool = nonHiraganaCandidates.drop(8)

            val trimmedContext = ContextWindowTrimmer.trimToOptimalContext(contextPrefix)
            val effectiveContext = if (trimmedContext.isBlank()) "<s>" else trimmedContext

            // 1. Check if all candidates are already memoized in Kotlin cache
            var allCached = true
            val memoizedScores = FloatArray(topPool.size)
            for (i in topPool.indices) {
                val key = "$effectiveContext:${topPool[i].surface}"
                val cached = memoizedNllCache.get(key)
                if (cached != null) {
                    memoizedScores[i] = cached
                } else {
                    allCached = false
                    break
                }
            }

            val scores: FloatArray
            val isFullyComplete: Boolean

            if (allCached) {
                scores = memoizedScores
                isFullyComplete = true
            } else {
                val surfaces = topPool.map { it.surface }.toTypedArray()
                val isCompleteArray = BooleanArray(surfaces.size)
                val fastScores = nativeTryFastScoreCandidates(effectiveContext, surfaces, isCompleteArray)
                    ?: return null
                scores = fastScores
                var complete = true
                for (i in topPool.indices) {
                    val key = "$effectiveContext:${topPool[i].surface}"
                    val cached = memoizedNllCache.get(key)
                    if (cached != null) {
                        scores[i] = cached
                    } else if (!isCompleteArray[i]) {
                        complete = false
                    }
                }
                isFullyComplete = complete
            }

            // Intra-Word Surprisal Clamping & Scoring
            val sysNlls = topPool.mapIndexedNotNull { index, cand ->
                if (cand.source != "user" && cand.source != "user_english") scores[index] else null
            }
            val sysBestNll = sysNlls.minOrNull() ?: 15.0f

            val scored = topPool.mapIndexed { index, candidate ->
                val rawNll = scores[index]
                val isUser = candidate.source == "user" || candidate.source == "user_english"
                val isExactUser = isUser && candidate.obsPenalty == 0.0f

                val effectiveNll = if (isExactUser) {
                    val priority = if (candidate.priority > 0f) candidate.priority else 3.0f
                    val clampedNll = minOf(sysBestNll, rawNll)
                    val userBoost = gammaUser * priority
                    val tieBreaker = 0.05f * rawNll
                    clampedNll - userBoost + tieBreaker
                } else {
                    rawNll
                }

                val effectiveObs = lambdaObs * candidate.obsPenalty * 5.0f
                val rankCost = (index * 1000.0f) / 1000.0f

                // Hysteresis: Apply gentle anchor bonus (0.35 nats) to currently shown top1
                val hysteresisBonus = if (currentShownTop1 != null && candidate.surface == currentShownTop1 && !isExactUser) {
                    -0.35f
                } else {
                    0.0f
                }

                val finalScore = (1.0f - weightAlpha) * (effectiveNll + effectiveObs + hysteresisBonus) + weightAlpha * rankCost
                Pair(candidate, finalScore)
            }

            val sortedPool = scored.sortedBy { it.second }.map { it.first }
            return FastRerankResult(
                candidates = sortedPool + restPool,
                isFullyComplete = isFullyComplete,
                slmUsed = true
            )
        } catch (e: Exception) {
            Log.w(TAG, "tryFastRerankWithStatus failed: ${e.message}")
            return null
        }
    }

    /**
     * Re-ranks conversion candidates based on preceding text context and SLM conditional log-likelihood.
     * Retains user-defined dictionary entries at absolute top priority via Intra-Word Surprisal Clamping (ADR-011 & Section 9.3).
     * Returns a pair of (reranked candidates, whether SLM scoring was successfully applied).
     */
    fun rerankWithStatus(
        contextPrefix: String,
        candidates: List<ConversionCandidate>,
        weightAlpha: Float = KeyboardHeightManager.DEFAULT_SLM_WEIGHT_ALPHA,
        lambdaObs: Float = KeyboardHeightManager.DEFAULT_SLM_LAMBDA_OBS,
        gammaUser: Float = KeyboardHeightManager.DEFAULT_SLM_GAMMA_USER,
        currentShownTop1: String? = null
    ): Pair<List<ConversionCandidate>, Boolean> {
        if (state != State.READY || candidates.size <= 1) {
            return Pair(candidates, false)
        }

        try {
            // All-hiragana candidates matching their reading exactly are excluded from conversion ranking
            val nonHiraganaCandidates = candidates.filter {
                val isRedundantHiragana = JapaneseKanaUtils.isAllHiragana(it.surface) && it.surface == it.reading
                !isRedundantHiragana
            }
            if (nonHiraganaCandidates.size <= 1) {
                return Pair(nonHiraganaCandidates, false)
            }

            // Cap candidate evaluation pool to at most top 8 for low latency and zero UI lag
            val topPool = nonHiraganaCandidates.take(8)
            val restPool = nonHiraganaCandidates.drop(8)

            val surfaces = topPool.map { it.surface }.toTypedArray()
            val trimmedContext = ContextWindowTrimmer.trimToOptimalContext(contextPrefix)
            val effectiveContext = if (trimmedContext.isBlank()) "<s>" else trimmedContext
            val scores = nativeScoreCandidates(effectiveContext, surfaces)

            if (scores == null || scores.size != topPool.size) {
                // Safe fallback: user entries first
                val userFallback = nonHiraganaCandidates.filter { it.source == "user" || it.source == "user_english" }
                val otherFallback = nonHiraganaCandidates.filter { it.source != "user" && it.source != "user_english" }
                return Pair(userFallback + otherFallback, false)
            }

            // Memoize completed scores in Kotlin cache for fast 0ms retrieval on subsequent typing
            for (i in topPool.indices) {
                val key = "$effectiveContext:${topPool[i].surface}"
                memoizedNllCache.put(key, scores[i])
            }

            // Find best system NLL for Intra-Word Surprisal Clamping
            val sysNlls = topPool.mapIndexedNotNull { index, cand ->
                if (cand.source != "user" && cand.source != "user_english") scores[index] else null
            }
            val sysBestNll = sysNlls.minOrNull() ?: 15.0f

            // LM-First Unified Scoring
            val scored = topPool.mapIndexed { index, candidate ->
                val rawNll = scores[index] // -log P(W|C)
                val isUser = candidate.source == "user" || candidate.source == "user_english"
                val isExactUser = isUser && candidate.obsPenalty == 0.0f

                // 1. Effective NLL with Clamped Floor and Priority Boost for user words
                val effectiveNll = if (isExactUser) {
                    val priority = if (candidate.priority > 0f) candidate.priority else 3.0f
                    val clampedNll = minOf(sysBestNll, rawNll)
                    val userBoost = gammaUser * priority
                    val tieBreaker = 0.05f * rawNll
                    clampedNll - userBoost + tieBreaker
                } else {
                    rawNll
                }

                // 2. Observation penalty (e.g. flick typo distance, voicing omission)
                val effectiveObs = lambdaObs * candidate.obsPenalty * 5.0f

                // 3. Base rank cost (index in candidate pool)
                val rankCost = (index * 1000.0f) / 1000.0f

                // 4. Hysteresis (Policy ④): Anchor currently shown Top-1 candidate with Δ_hysteresis
                // Requires new candidate to beat currently shown top1 by at least 1.20 nats (~3.3x probability)
                val hysteresisBonus = if (currentShownTop1 != null && candidate.surface == currentShownTop1 && !isExactUser) {
                    -1.20f
                } else {
                    0.0f
                }

                // 5. Combined Score (lower is better)
                val finalScore = (1.0f - weightAlpha) * (effectiveNll + effectiveObs + hysteresisBonus) + weightAlpha * rankCost
                Pair(candidate, finalScore)
            }

            val sortedPool = scored.sortedBy { it.second }.map { it.first }
            return Pair(sortedPool + restPool, true)
        } catch (e: Exception) {
            Log.w(TAG, "Reranking failed: ${e.message}, falling back to original candidates.")
            val userFallback = candidates.filter { it.source == "user" || it.source == "user_english" }
            val otherFallback = candidates.filter { it.source != "user" && it.source != "user_english" }
            return Pair(userFallback + otherFallback, false)
        }
    }

    /**
     * Backward-compatible helper returning only the candidates.
     */
    fun rerank(
        contextPrefix: String,
        candidates: List<ConversionCandidate>,
        weightAlpha: Float = KeyboardHeightManager.DEFAULT_SLM_WEIGHT_ALPHA
    ): List<ConversionCandidate> {
        return rerankWithStatus(contextPrefix, candidates, weightAlpha).first
    }

    fun release() {
        if (state == State.READY) {
            try {
                nativeRelease()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing SLM model: ${e.message}")
            }
            state = State.UNINITIALIZED
            currentModelPath = null
        }
    }

    /**
     * Generates speculative continuation phrase suggestions directly from on-device SLM
     * conditioned on the preceding context. Safe to execute asynchronously on Dispatchers.Default.
     */
    fun predictNextPhrases(contextPrefix: String, maxCandidates: Int = 3, maxTokens: Int = 5): List<ConversionCandidate> {
        if (state != State.READY || !nativeLibraryLoaded) {
            return emptyList()
        }
        try {
            val trimmedContext = ContextWindowTrimmer.trimToOptimalContext(contextPrefix)
            val effectiveContext = if (trimmedContext.isBlank()) "<s>" else trimmedContext
            val surfaces = nativePredictNextPhrases(effectiveContext, maxCandidates, maxTokens) ?: return emptyList()
            return surfaces.filter { it.isNotBlank() }.map { surface ->
                ConversionCandidate(
                    surface = surface,
                    reading = surface,
                    isPrediction = true,
                    source = "slm"
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "SLM predictNextPhrases failed: ${e.message}")
            return emptyList()
        }
    }

    /**
     * Pre-warms the SLM KV cache with the preceding context during idle time or after committing text.
     * Drops first-keystroke latency on subsequent typing to < 1ms.
     */
    fun warmContext(contextPrefix: String): Boolean {
        if (state != State.READY || !nativeLibraryLoaded) {
            return false
        }
        return try {
            val trimmed = ContextWindowTrimmer.trimToOptimalContext(contextPrefix)
            val effective = if (trimmed.isBlank()) "<s>" else trimmed
            nativeWarmContext(effective)
        } catch (e: Exception) {
            Log.w(TAG, "SLM warmContext failed: ${e.message}")
            false
        }
    }

    // Native JNI functions implemented in libmyasr_llm.so
    private external fun nativeInit(modelPath: String, nThreads: Int): Boolean
    private external fun nativeScoreCandidates(context: String, candidates: Array<String>): FloatArray?
    private external fun nativeTryFastScoreCandidates(context: String, candidates: Array<String>, outIsComplete: BooleanArray): FloatArray?
    private external fun nativeRequestAbortScoring()
    private external fun nativeWarmContext(context: String): Boolean
    private external fun nativePredictNextPhrases(context: String, maxCandidates: Int, maxTokens: Int): Array<String>?
    private external fun nativeRelease()
    private external fun nativeIsReady(): Boolean
}
