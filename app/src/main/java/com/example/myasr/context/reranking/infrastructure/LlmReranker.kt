package com.example.myasr.context.reranking.infrastructure

import com.example.myasr.context.ime.presentation.KeyboardHeightManager
import com.example.myasr.context.kanakanji.domain.ConversionCandidate
import com.example.myasr.context.kanakanji.domain.JapaneseKanaUtils
import com.example.myasr.context.reranking.domain.CandidateRerankingService
import com.example.myasr.context.reranking.domain.ContextWindowTrimmer
import com.example.myasr.context.reranking.domain.RerankEngineType

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
class LlmReranker private constructor(private val context: Context) : CandidateRerankingService {

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

    override fun isReady(): Boolean = state == State.READY

    private var currentModelPath: String? = null

    companion object {
        private val CASE_PARTICLES = listOf("に", "を", "が")

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

        /**
         * Unified Span-Aware Bayesian Scoring & Subsumption Dominance Engine.
         * Evaluates candidates across full/partial/predictive spans with principled unconsumed penalties
         * and intra-word surprisal clamping for user dictionaries.
         */
        internal fun scoreAndRankPool(
            topPool: List<ConversionCandidate>,
            scores: FloatArray,
            currentShownTop1: String?,
            weightAlpha: Float,
            lambdaObs: Float,
            gammaUser: Float,
            targetInputLength: Int? = null,
            hysteresisDelta: Float = -1.20f,
            nowMs: Long = System.currentTimeMillis()
        ): List<ConversionCandidate> {
            if (topPool.isEmpty()) return emptyList()

            // 1. Detect if candidates are English or Japanese
            val firstCand = topPool.first()
            val isEnglishPool = firstCand.source in listOf("user_english", "english_typo", "english_cap", "english_gec", "raw") ||
                    (firstCand.reading.isNotEmpty() && firstCand.reading.all { it.code < 128 && (it.isLetter() || it == '\'' || it == '-') })

            // 2. Compute effective target input length N
            val n: Int = if (targetInputLength != null && targetInputLength > 0) {
                targetInputLength
            } else if (isEnglishPool) {
                val letters = firstCand.reading.filter { it.isLetter() }.length
                if (letters > 0) letters else firstCand.reading.length
            } else {
                val fullCand = topPool.filter { it.source != "viterbi_prefix" && !it.isPrediction && it.reading.isNotEmpty() }
                    .maxByOrNull { it.reading.length }
                    ?: topPool.firstOrNull { it.reading.isNotEmpty() }
                fullCand?.reading?.length ?: 0
            }

            val muSpan = if (isEnglishPool) 1.40f else 2.20f
            val kappaCommit = 1.50f
            val lambdaExt = 1.20f

            // 3. Compute consumed lengths M_i and span deltas
            val mLengths = IntArray(topPool.size) { i ->
                val cand = topPool[i]
                if (isEnglishPool) {
                    if (cand.source == "raw") n else cand.surface.filter { it.isLetter() }.length
                } else {
                    if (cand.reading.isNotEmpty()) cand.reading.length else cand.surface.length
                }
            }
            val unconsumedDeltas = IntArray(topPool.size) { i -> maxOf(0, n - mLengths[i]) }
            val extDeltas = IntArray(topPool.size) { i -> maxOf(0, mLengths[i] - n) }

            // 4. Find sysBestNll for user dictionary Intra-Word Surprisal Clamping
            val sysNlls = topPool.mapIndexedNotNull { index, cand ->
                if (cand.source != "user" && cand.source != "user_english") scores[index] else null
            }
            val sysBestNll = sysNlls.minOrNull() ?: 15.0f
            val bestPoolNll = scores.minOrNull() ?: 15.0f

            val thetaCoherent = 4.0f

            // 5. Identify full-span candidates and chimera detection
            val fullSpanIndices = topPool.indices.filter {
                unconsumedDeltas[it] == 0 && extDeltas[it] == 0 && !topPool[it].isPrediction
            }

            val isChimeraFull = BooleanArray(topPool.size)
            for (fIdx in fullSpanIndices) {
                val fullCand = topPool[fIdx]
                val fullNll = scores[fIdx]
                for (pIdx in topPool.indices) {
                    if (unconsumedDeltas[pIdx] > 0) {
                        val partCand = topPool[pIdx]
                        val isPrefix = if (isEnglishPool) {
                            fullCand.surface.lowercase().startsWith(partCand.surface.lowercase()) &&
                                fullCand.surface.length > partCand.surface.length
                        } else {
                            fullCand.surface.startsWith(partCand.surface) && fullCand.surface != partCand.surface
                        }
                        if (isPrefix) {
                            val partNll = scores[pIdx]
                            val tailSurprisal = (fullNll - partNll) / unconsumedDeltas[pIdx].toFloat()
                            if (tailSurprisal > thetaCoherent) {
                                isChimeraFull[fIdx] = true
                            }
                        }
                    }
                }
            }

            val baseScores = FloatArray(topPool.size)
            for (index in topPool.indices) {
                val candidate = topPool[index]
                val rawNll = scores[index]
                val isUser = candidate.source == "user" || candidate.source == "user_english"
                val isExactUser = isUser && candidate.obsPenalty == 0.0f
                val deltaUnconsumed = unconsumedDeltas[index]
                val deltaExt = extDeltas[index]

                val rawBaseNll: Float
                if (isExactUser) {
                    val priority = if (candidate.priority > 0f) candidate.priority else 3.0f
                    val clampedNll = minOf(sysBestNll, rawNll)
                    val userBoost = gammaUser * priority
                    val tieBreaker = 0.05f * rawNll
                    rawBaseNll = clampedNll - userBoost + tieBreaker
                } else if (deltaUnconsumed == 0 && deltaExt == 0 && !candidate.isPrediction) {
                    // (a) Full-span exact candidate
                    val chimeraPenalty = if (isChimeraFull[index]) 10.0f else 0.0f
                    rawBaseNll = rawNll + chimeraPenalty
                } else if (deltaUnconsumed > 0) {
                    // (b) Partial-prefix candidate (Virtual suffix completed or marginalized)
                    if (candidate.completedSurface.isNotEmpty() && candidate.completedSurface != candidate.surface) {
                        // Virtual completed full surface NLL has already been evaluated!
                        val branchBonus = (0.55f * kotlin.math.ln(maxOf(1.0f, candidate.suffixBranchingFactor))).coerceAtMost(1.20f)
                        rawBaseNll = rawNll - branchBonus + kappaCommit
                    } else {
                        // Fallback when completedSurface is absent (e.g. English typos, direct unit tests)
                        val subsumingFullNll = fullSpanIndices.filter { !isChimeraFull[it] }.mapNotNull { fIdx ->
                            val fullCand = topPool[fIdx]
                            val isSub = if (isEnglishPool) {
                                fullCand.surface.lowercase().startsWith(candidate.surface.lowercase())
                            } else {
                                fullCand.surface.startsWith(candidate.surface) || fullCand.reading.startsWith(candidate.reading)
                            }
                            if (isSub) scores[fIdx] else null
                        }.minOrNull()

                        val baseCompNll = subsumingFullNll ?: (rawNll + muSpan * deltaUnconsumed)
                        rawBaseNll = baseCompNll + kappaCommit
                    }
                } else {
                    // (c) Uninputted prefix extension cost (waive extension penalty for user history >= 2 or history_bigram)
                    val waiveExt = candidate.historyCount >= 2 || candidate.source == "history_bigram"
                    rawBaseNll = if (waiveExt) rawNll else rawNll + lambdaExt * maxOf(1, deltaExt)
                }

                // Hybrid-6b History x SLM Fusion (Time-Decay + Coherence Gate + Gated Soft-Clamping + c=1 Damping)
                val effectiveNll: Float = if (!isExactUser && candidate.historyCount > 0 && !isChimeraFull[index]) {
                    val deltaNll = rawNll - bestPoolNll
                    val thetaGate = 4.6f + (if (deltaExt > 0) 0.25f * deltaExt else 0.0f)
                    val gate = 1.0f / (1.0f + kotlin.math.exp(((deltaNll - thetaGate) / 0.6f).coerceIn(-20.0f, 20.0f)))

                    val hoursElapsed = if (candidate.lastUsedAtMs > 0L && nowMs > candidate.lastUsedAtMs) {
                        (nowMs - candidate.lastUsedAtMs).toFloat() / 3_600_000.0f
                    } else {
                        0.0f
                    }
                    val shortDecay = kotlin.math.exp((-hoursElapsed / 1.0f).coerceIn(-50.0f, 0.0f))
                    val longDecay = kotlin.math.exp((-hoursElapsed / 336.0f).coerceIn(-50.0f, 0.0f))

                    val clampedBase = if (candidate.historyCount >= 2) {
                        val eta = gate * minOf(0.70f, (candidate.historyCount - 1) / 5.0f) * longDecay
                        eta * minOf(sysBestNll, rawBaseNll) + (1.0f - eta) * rawBaseNll
                    } else {
                        rawBaseNll
                    }

                    val shortTerm = if (candidate.lastUsedAtMs > 0L) 2.2f * shortDecay else 0.0f
                    val longTerm = 1.8f * minOf(kotlin.math.ln(1.0f + candidate.historyCount.toFloat()), 3.0f) * longDecay
                    val c1Damp = if (candidate.historyCount == 1) 0.30f else 1.0f
                    clampedBase - gate * (shortTerm + longTerm) * c1Damp
                } else {
                    rawBaseNll
                }

                val effectiveObs = lambdaObs * candidate.obsPenalty * 5.0f
                val rankCost = (index * 1000.0f) / 1000.0f

                val hysteresisBonus = if (currentShownTop1 != null && candidate.surface == currentShownTop1 && !isExactUser) {
                    hysteresisDelta
                } else {
                    0.0f
                }

                baseScores[index] = (1.0f - weightAlpha) * (effectiveNll + effectiveObs + hysteresisBonus) + weightAlpha * rankCost
            }

            // 6. Strata Partitioning
            val exactUserIndices = topPool.indices.filter {
                val c = topPool[it]
                (c.source == "user" || c.source == "user_english") && c.obsPenalty == 0.0f && unconsumedDeltas[it] == 0
            }
            val fullIndices = topPool.indices.filter {
                unconsumedDeltas[it] == 0 && extDeltas[it] == 0 && !topPool[it].isPrediction && !isChimeraFull[it] && it !in exactUserIndices
            }
            val prefixIndices = topPool.indices.filter {
                unconsumedDeltas[it] > 0 && it !in exactUserIndices
            }
            val extIndices = topPool.indices.filter {
                it !in exactUserIndices && it !in fullIndices && it !in prefixIndices
            }

            // 7. Sequential 2D-MMR Greedy Selection for Full-Span Strata
            val alphaDiv = minOf(0.5f, weightAlpha)
            val rankedFull = mutableListOf<ConversionCandidate>()
            val remainingFull = fullIndices.toMutableList()
            val firstStemCounts = HashMap<String, Int>()
            val lastContentCounts = HashMap<String, Int>()

            while (remainingFull.isNotEmpty()) {
                var bestIdx = -1
                var bestScore = Float.MAX_VALUE
                for (idx in remainingFull) {
                    val cand = topPool[idx]
                    val stem1 = cand.firstBunsetsuSurface.ifEmpty { cand.surface }
                    val count1 = firstStemCounts[stem1] ?: 0
                    val stemLast = cand.lastContentWordSurface
                    val countLast = if (stemLast.isNotEmpty()) lastContentCounts[stemLast] ?: 0 else 0

                    val divPenalty = (1.0f - alphaDiv) * (count1 * 2.00f + countLast * 1.60f)
                    val score = baseScores[idx] + divPenalty
                    if (score < bestScore) {
                        bestScore = score
                        bestIdx = idx
                    }
                }
                if (bestIdx >= 0) {
                    val chosen = topPool[bestIdx]
                    rankedFull.add(chosen)
                    remainingFull.remove(bestIdx)
                    val stem1 = chosen.firstBunsetsuSurface.ifEmpty { chosen.surface }
                    firstStemCounts[stem1] = (firstStemCounts[stem1] ?: 0) + 1
                    val stemLast = chosen.lastContentWordSurface
                    if (stemLast.isNotEmpty()) {
                        lastContentCounts[stemLast] = (lastContentCounts[stemLast] ?: 0) + 1
                    }
                } else {
                    break
                }
            }

            // 8. Sequential Greedy Selection for Partial-Prefix Strata with Det-Sub Prevention
            val partialStemCounts = HashMap<String, Int>()
            val coveredFullStems = HashSet<String>()
            for (c in rankedFull.take(3)) {
                val s = c.firstBunsetsuSurface.ifEmpty { c.surface }
                if (s.isNotEmpty()) coveredFullStems.add(s)
                for (pIdx in prefixIndices) {
                    val pCand = topPool[pIdx]
                    if (c.surface.startsWith(pCand.surface) && c.surface != pCand.surface) {
                        coveredFullStems.add(pCand.surface)
                        if (pCand.firstBunsetsuSurface.isNotEmpty()) {
                            coveredFullStems.add(pCand.firstBunsetsuSurface)
                        }
                    }
                }
            }

            val rankedPartial = mutableListOf<ConversionCandidate>()
            val remainingPartial = prefixIndices.toMutableList()

            while (remainingPartial.isNotEmpty()) {
                var bestIdx = -1
                var bestScore = Float.MAX_VALUE
                for (idx in remainingPartial) {
                    val cand = topPool[idx]
                    val stem1 = cand.firstBunsetsuSurface.ifEmpty { cand.surface }
                    val count1 = partialStemCounts[stem1] ?: 0

                    val isDetSub = coveredFullStems.contains(stem1) && cand.suffixBranchingFactor < 1.80f
                    val detSubPenalty = if (isDetSub) 2.50f else 0.0f
                    val divPenalty = (1.0f - alphaDiv) * (count1 * 2.00f + detSubPenalty)

                    val score = baseScores[idx] + divPenalty
                    if (score < bestScore) {
                        bestScore = score
                        bestIdx = idx
                    }
                }
                if (bestIdx >= 0) {
                    val chosen = topPool[bestIdx]
                    rankedPartial.add(chosen)
                    remainingPartial.remove(bestIdx)
                    val stem1 = chosen.firstBunsetsuSurface.ifEmpty { chosen.surface }
                    partialStemCounts[stem1] = (partialStemCounts[stem1] ?: 0) + 1
                } else {
                    break
                }
            }

            // 9. Extensions / Predictions ranked by base score
            val rankedExtIndices = extIndices.sortedBy { baseScores[it] }
            val rankedExt = rankedExtIndices.map { topPool[it] }
            val topHistPredIdx = rankedExtIndices.firstOrNull { topPool[it].historyCount >= 2 }

            // 10. Adaptive Stratified Interleaving Schedule
            val maxBranch = prefixIndices.map { topPool[it].suffixBranchingFactor }.maxOrNull() ?: 1.0f
            val isHighBranch = maxBranch >= 1.80f
            val topFullQuota = if (isHighBranch) 2 else 3
            val midPartialQuota = if (isHighBranch) 4 else 3

            val result = mutableListOf<ConversionCandidate>()
            val seenSurfaces = HashSet<String>()
            fun addCandidate(cand: ConversionCandidate) {
                if (seenSurfaces.add(cand.surface)) {
                    result.add(cand)
                }
            }

            // Stratum 0: User exact entries
            for (idx in exactUserIndices) {
                addCandidate(topPool[idx])
            }
            // Stratum 1: Top full candidates + History Prefix Prediction Promotion (Slot-2 / Conditional Top-1)
            var fullIdx = 0
            var partialIdx = 0
            if (topHistPredIdx != null && rankedFull.isNotEmpty() && n > 0) {
                val topFullIdx = topPool.indexOf(rankedFull[0])
                val canPromoteTop1 = n >= 2 && topFullIdx >= 0 && baseScores[topHistPredIdx] < baseScores[topFullIdx] - 0.50f
                if (canPromoteTop1) {
                    addCandidate(topPool[topHistPredIdx])
                    addCandidate(rankedFull[fullIdx++])
                } else {
                    addCandidate(rankedFull[fullIdx++])
                    addCandidate(topPool[topHistPredIdx])
                }
            }
            while (fullIdx < minOf(topFullQuota, rankedFull.size)) {
                addCandidate(rankedFull[fullIdx++])
            }
            // Stratum 2: Mid partial candidates
            while (partialIdx < minOf(midPartialQuota, rankedPartial.size)) {
                addCandidate(rankedPartial[partialIdx++])
            }
            // Stratum 3: Next full candidates
            val nextFullLimit = minOf(fullIdx + 2, rankedFull.size)
            while (fullIdx < nextFullLimit) {
                addCandidate(rankedFull[fullIdx++])
            }
            // Stratum 4: Remaining partial candidates
            while (partialIdx < rankedPartial.size) {
                addCandidate(rankedPartial[partialIdx++])
            }
            // Stratum 5: Remaining full candidates
            while (fullIdx < rankedFull.size) {
                addCandidate(rankedFull[fullIdx++])
            }
            // Stratum 6: Extension / prediction candidates
            for (ext in rankedExt) {
                addCandidate(ext)
            }

            return result
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

    override fun requestAbortScoring() {
        if (state == State.READY && nativeLibraryLoaded) {
            try {
                nativeRequestAbortScoring()
            } catch (e: Exception) {
                Log.w(TAG, "Error in requestAbortScoring: ${e.message}")
            }
        }
    }

    private fun getLatentParticleVariants(
        candidate: ConversionCandidate,
        contextPrefix: String
    ): List<String> {
        if (!candidate.isPredicate) return emptyList()

        val variants = ArrayList<String>(3)
        val fb = candidate.firstBunsetsuSurface
        val tail = candidate.predicateTailSurface

        // 1. Continuous input (D2): e.g. "ジム行けた" (firstBunsetsu: "ジム", tail: "行けた")
        if (fb.isNotEmpty() && tail.isNotEmpty() && candidate.surface.length > fb.length && candidate.surface.startsWith(fb)) {
            for (p in CASE_PARTICLES) {
                variants.add("$fb$p$tail")
            }
        } else if (contextPrefix.isNotBlank()) {
            // 2. Split context (D1): contextPrefix ends in a noun without particle or punctuation
            val lastChar = contextPrefix.trimEnd().lastOrNull()
            if (lastChar != null && lastChar !in "、。！？!? \n\t，．・はがをにでもとやかへの") {
                for (p in CASE_PARTICLES) {
                    variants.add("$p${candidate.surface}")
                }
            }
        }
        return variants
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
        gammaUser: Float = KeyboardHeightManager.DEFAULT_SLM_GAMMA_USER,
        targetInputLength: Int? = null
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

            val fullCands = nonHiraganaCandidates.filter {
                !it.isPrediction && (it.reading.isEmpty() || it.source != "viterbi_prefix")
            }
            val prefixCands = nonHiraganaCandidates.filter {
                it.source == "viterbi_prefix" || it.isPrediction
            }
            val topPool = mutableListOf<ConversionCandidate>()
            val topPoolSurfaces = HashSet<String>()
            for (c in fullCands.take(6)) {
                if (topPoolSurfaces.add(c.surface)) topPool.add(c)
            }
            for (c in prefixCands.take(6)) {
                if (topPoolSurfaces.add(c.surface)) topPool.add(c)
            }
            for (c in nonHiraganaCandidates) {
                if (topPool.size >= 12) break
                if (topPoolSurfaces.add(c.surface)) topPool.add(c)
            }
            val restPool = nonHiraganaCandidates.filter { cand -> topPool.none { it.surface == cand.surface } }

            val trimmedContext = ContextWindowTrimmer.trimToOptimalContext(contextPrefix)
            val effectiveContext = if (trimmedContext.isBlank()) "<s>" else trimmedContext

            val evalSurfaces = Array(topPool.size) { i ->
                val c = topPool[i]
                if (c.completedSurface.isNotEmpty()) c.completedSurface else c.surface
            }

            val latentVariantsMap = HashMap<String, List<String>>()
            val surfacesToScore = ArrayList<String>(topPool.size * 2)
            for (i in topPool.indices) {
                val evalSurf = evalSurfaces[i]
                surfacesToScore.add(evalSurf)
                val variants = getLatentParticleVariants(topPool[i], contextPrefix)
                if (variants.isNotEmpty()) {
                    latentVariantsMap[evalSurf] = variants
                    surfacesToScore.addAll(variants)
                }
            }

            // 1. Check if all candidates are already memoized in Kotlin cache
            var allCached = true
            val memoizedScores = FloatArray(topPool.size)
            for (i in topPool.indices) {
                val key = "$effectiveContext:${evalSurfaces[i]}"
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
                val uniqueSurfaces = surfacesToScore.distinct().toTypedArray()
                val isCompleteArray = BooleanArray(uniqueSurfaces.size)
                val fastScores = nativeTryFastScoreCandidates(effectiveContext, uniqueSurfaces, isCompleteArray)
                    ?: return null
                val scoreMap = HashMap<String, Float>()
                val completeMap = HashMap<String, Boolean>()
                for (i in uniqueSurfaces.indices) {
                    scoreMap[uniqueSurfaces[i]] = fastScores[i]
                    completeMap[uniqueSurfaces[i]] = isCompleteArray[i]
                    if (isCompleteArray[i]) {
                        memoizedNllCache.put("$effectiveContext:${uniqueSurfaces[i]}", fastScores[i])
                    }
                }
                scores = FloatArray(topPool.size) { idx ->
                    val evalSurf = evalSurfaces[idx]
                    val baseNll = scoreMap[evalSurf] ?: 20.0f
                    val variants = latentVariantsMap[evalSurf]
                    val minLatentNll = variants?.mapNotNull { scoreMap[it] }?.minOrNull()
                    val finalNll = if (minLatentNll != null) minOf(baseNll, minLatentNll) else baseNll
                    memoizedNllCache.put("$effectiveContext:$evalSurf", finalNll)
                    finalNll
                }
                var complete = true
                for (i in topPool.indices) {
                    val key = "$effectiveContext:${evalSurfaces[i]}"
                    if (memoizedNllCache.get(key) == null && completeMap[evalSurfaces[i]] != true) {
                        complete = false
                    }
                }
                isFullyComplete = complete
            }

            val sortedPool = scoreAndRankPool(
                topPool = topPool,
                scores = scores,
                currentShownTop1 = currentShownTop1,
                weightAlpha = weightAlpha,
                lambdaObs = lambdaObs,
                gammaUser = gammaUser,
                targetInputLength = targetInputLength,
                hysteresisDelta = -0.35f
            )

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
     * Implements span-aware unconsumed penalty and subsumption dominance for full-span candidate priority (ADR-020).
     * Returns a pair of (reranked candidates, whether SLM scoring was successfully applied).
     */
    fun rerankWithStatus(
        contextPrefix: String,
        candidates: List<ConversionCandidate>,
        weightAlpha: Float = KeyboardHeightManager.DEFAULT_SLM_WEIGHT_ALPHA,
        lambdaObs: Float = KeyboardHeightManager.DEFAULT_SLM_LAMBDA_OBS,
        gammaUser: Float = KeyboardHeightManager.DEFAULT_SLM_GAMMA_USER,
        currentShownTop1: String? = null,
        targetInputLength: Int? = null
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

            val fullCands = nonHiraganaCandidates.filter {
                !it.isPrediction && (it.reading.isEmpty() || it.source != "viterbi_prefix")
            }
            val prefixCands = nonHiraganaCandidates.filter {
                it.source == "viterbi_prefix" || it.isPrediction
            }
            val topPool = mutableListOf<ConversionCandidate>()
            val topPoolSurfaces = HashSet<String>()
            // Ensure high-frequency history candidates (both full-span and predictions) enter topPool
            for (c in nonHiraganaCandidates.filter { it.historyCount >= 2 }.sortedByDescending { it.historyCount }.take(4)) {
                if (topPoolSurfaces.add(c.surface)) topPool.add(c)
            }
            for (c in fullCands.take(6)) {
                if (topPool.size >= 12) break
                if (topPoolSurfaces.add(c.surface)) topPool.add(c)
            }
            for (c in prefixCands.take(6)) {
                if (topPool.size >= 12) break
                if (topPoolSurfaces.add(c.surface)) topPool.add(c)
            }
            for (c in nonHiraganaCandidates) {
                if (topPool.size >= 12) break
                if (topPoolSurfaces.add(c.surface)) topPool.add(c)
            }
            val restPool = nonHiraganaCandidates.filter { cand -> topPool.none { it.surface == cand.surface } }

            val evalSurfaces = Array(topPool.size) { i ->
                val c = topPool[i]
                if (c.completedSurface.isNotEmpty()) c.completedSurface else c.surface
            }

            val latentVariantsMap = HashMap<String, List<String>>()
            val surfacesToScore = ArrayList<String>(topPool.size * 2)
            for (i in topPool.indices) {
                val evalSurf = evalSurfaces[i]
                surfacesToScore.add(evalSurf)
                val variants = getLatentParticleVariants(topPool[i], contextPrefix)
                if (variants.isNotEmpty()) {
                    latentVariantsMap[evalSurf] = variants
                    surfacesToScore.addAll(variants)
                }
            }

            val uniqueSurfaces = surfacesToScore.distinct().toTypedArray()
            val trimmedContext = ContextWindowTrimmer.trimToOptimalContext(contextPrefix)
            val effectiveContext = if (trimmedContext.isBlank()) "<s>" else trimmedContext
            val uniqueScores = nativeScoreCandidates(effectiveContext, uniqueSurfaces)

            if (uniqueScores == null || uniqueScores.size != uniqueSurfaces.size) {
                // Safe fallback: user entries first
                val userFallback = nonHiraganaCandidates.filter { it.source == "user" || it.source == "user_english" }
                val otherFallback = nonHiraganaCandidates.filter { it.source != "user" && it.source != "user_english" }
                return Pair(userFallback + otherFallback, false)
            }

            // Memoize completed scores in Kotlin cache for fast 0ms retrieval on subsequent typing
            val scoreMap = HashMap<String, Float>()
            for (i in uniqueSurfaces.indices) {
                scoreMap[uniqueSurfaces[i]] = uniqueScores[i]
                memoizedNllCache.put("$effectiveContext:${uniqueSurfaces[i]}", uniqueScores[i])
            }
            val scores = FloatArray(topPool.size) { idx ->
                val evalSurf = evalSurfaces[idx]
                val baseNll = scoreMap[evalSurf] ?: 20.0f
                val variants = latentVariantsMap[evalSurf]
                val minLatentNll = variants?.mapNotNull { scoreMap[it] }?.minOrNull()
                val finalNll = if (minLatentNll != null) minOf(baseNll, minLatentNll) else baseNll
                memoizedNllCache.put("$effectiveContext:$evalSurf", finalNll)
                finalNll
            }

            val sortedPool = scoreAndRankPool(
                topPool = topPool,
                scores = scores,
                currentShownTop1 = currentShownTop1,
                weightAlpha = weightAlpha,
                lambdaObs = lambdaObs,
                gammaUser = gammaUser,
                targetInputLength = targetInputLength,
                hysteresisDelta = -1.20f
            )

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

    /**
     * S3: Computes NLL(corrText | contextPrefix) - NLL(origText | contextPrefix) in nats.
     * Returns null if SLM is not ready or scoring fails/is aborted.
     */
    fun scorePairDeltaNll(contextPrefix: String, origText: String, corrText: String): Float? {
        if (!isReady() || origText.isBlank() || corrText.isBlank() || origText == corrText) {
            return null
        }
        return try {
            val trimmedContext = ContextWindowTrimmer.trimToOptimalContext(contextPrefix)
            val effectiveContext = if (trimmedContext.isBlank()) "<s>" else trimmedContext
            val scores = nativeScoreCandidates(effectiveContext, arrayOf(origText, corrText))
            if (scores != null && scores.size == 2) {
                scores[1] - scores[0]
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
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
            return surfaces
                .map { it.trim { ch -> ch.isWhitespace() || ch == '\u3000' } }
                .filter { it.isNotEmpty() }
                .distinct()
                .map { surface ->
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
    override fun warmContext(contextPrefix: String): Boolean {
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

    override fun rerankFast(
        contextPrefix: String,
        candidates: List<ConversionCandidate>,
        alpha: Float
    ): Pair<List<ConversionCandidate>, Boolean> {
        val fast = tryFastRerankWithStatus(
            contextPrefix = contextPrefix,
            candidates = candidates,
            weightAlpha = alpha
        ) ?: return candidates to false
        return fast.candidates to fast.isFullyComplete
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
