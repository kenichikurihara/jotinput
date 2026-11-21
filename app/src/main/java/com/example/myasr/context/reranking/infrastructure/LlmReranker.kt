package com.example.myasr.context.reranking.infrastructure

import com.example.myasr.context.ime.presentation.KeyboardHeightManager
import com.example.myasr.context.kanakanji.domain.ConversionCandidate
import com.example.myasr.context.kanakanji.domain.JapaneseKanaUtils
import com.example.myasr.context.kanakanji.domain.diversifyByFirstStem
import com.example.myasr.context.reranking.domain.CandidateRerankingService
import com.example.myasr.context.reranking.domain.ContextWindowTrimmer
import com.example.myasr.context.reranking.domain.RerankEngineType

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.pow

/**
 * High-performance on-device SLM candidate re-ranking engine using llama.cpp and Qwen3.5.
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

    @Volatile
    private var speculativeZeroQueryCache: Pair<String, List<ConversionCandidate>>? = null

    companion object {
        private val CASE_PARTICLES = listOf("に", "を", "が")
        private const val DEFAULT_MU_SPAN_JA = 2.20f
        private const val DEFAULT_MU_SPAN_EN = 1.40f

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

        enum class ScoringProfile(
            val keyCostSec: Float,
            val riskWeight: Float,
            val extGainWeight: Float,
            val slot1MarginSec: Float
        ) {
            JA_FLICK(keyCostSec = 0.40f, riskWeight = 0.25f, extGainWeight = 1.00f, slot1MarginSec = 0.15f),
            JA_ZERO_QUERY(keyCostSec = 0.40f, riskWeight = 0.30f, extGainWeight = 1.00f, slot1MarginSec = 0.00f),
            EN_QWERTY(keyCostSec = 0.30f, riskWeight = 0.45f, extGainWeight = 0.85f, slot1MarginSec = 0.25f)
        }

        internal enum class HorizonBucket {
            PARTIAL,
            EXACT,
            SHORT_EXT,
            CHUNK_EXT
        }

        internal fun classifyHorizon(
            cand: ConversionCandidate,
            deltaUnconsumed: Int,
            deltaExt: Int,
            targetInputLength: Int = 1
        ): HorizonBucket {
            if (targetInputLength == 0) {
                val len = if (cand.reading.isNotEmpty()) cand.reading.length else cand.surface.length
                return if (cand.source == "phrase_chunk" || len >= 5) {
                    HorizonBucket.CHUNK_EXT
                } else {
                    HorizonBucket.SHORT_EXT
                }
            }
            return when {
                deltaUnconsumed > 0 -> HorizonBucket.PARTIAL
                deltaExt == 0 && !cand.isPrediction -> HorizonBucket.EXACT
                deltaExt >= 3 || cand.source == "phrase_chunk" -> HorizonBucket.CHUNK_EXT
                else -> HorizonBucket.SHORT_EXT
            }
        }

        internal fun extractCanonicalStem(
            cand: ConversionCandidate,
            topPool: List<ConversionCandidate>,
            isEnglishPool: Boolean
        ): String {
            if (cand.firstBunsetsuSurface.isNotEmpty()) {
                return cand.firstBunsetsuSurface
            }
            val surf = cand.surface
            if (surf.isEmpty()) return ""
            if (isEnglishPool) {
                val clean = surf.lowercase().filter { it.isLetter() }
                return if (clean.length >= 4) clean.substring(0, 4) else clean
            }
            // Prefix stem canonicalization against shorter non-hiragana candidates in the same pool
            var bestBase: String? = null
            for (other in topPool) {
                val oSurf = other.surface
                if (oSurf.length in 2 until surf.length &&
                    surf.startsWith(oSurf) &&
                    !JapaneseKanaUtils.isAllHiragana(oSurf)
                ) {
                    if (bestBase == null || oSurf.length < bestBase.length) {
                        bestBase = oSurf
                    }
                }
            }
            if (bestBase != null) return bestBase

            // Morphological fallback: extract leading kanji or katakana block
            val firstChar = surf[0]
            val isKanji = firstChar in '\u4E00'..'\u9FFF' || firstChar == '々'
            val isKatakana = firstChar in '\u30A0'..'\u30FF'
            if (isKanji) {
                var end = 1
                while (end < surf.length && (surf[end] in '\u4E00'..'\u9FFF' || surf[end] == '々')) {
                    end++
                }
                if (end >= 3 && surf[end - 1] in listOf('中', '的', '化', '後', '前')) {
                    end--
                }
                return surf.substring(0, end)
            }
            if (isKatakana) {
                var end = 1
                while (end < surf.length && (surf[end] in '\u30A0'..'\u30FF' || surf[end] == 'ー')) {
                    end++
                }
                return surf.substring(0, end)
            }
            return surf
        }

        internal fun computeIntentOverlapKappa(
            stemA: String,
            horizonA: HorizonBucket,
            lastContentA: String,
            stemB: String,
            horizonB: HorizonBucket,
            lastContentB: String
        ): Float {
            if (stemA.isNotEmpty() && stemA == stemB) {
                return if (horizonA == horizonB) 0.80f else 0.35f
            }
            if (lastContentA.isNotEmpty() && lastContentA == lastContentB) {
                return 0.50f
            }
            return 0.0f
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
            val n: Int = if (targetInputLength != null && targetInputLength >= 0) {
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

            val muSpan = if (isEnglishPool) DEFAULT_MU_SPAN_EN else DEFAULT_MU_SPAN_JA
            val kappaCommit = 1.50f
            val lambdaExt = 1.20f

            // ScoringProfile for Risk-Adjusted Expected EIS Utility (Section 3.3)
            val profile = when {
                isEnglishPool -> ScoringProfile.EN_QWERTY
                n == 0 -> ScoringProfile.JA_ZERO_QUERY
                else -> ScoringProfile.JA_FLICK
            }
            val keyCostSec = profile.keyCostSec
            val riskWeight = profile.riskWeight
            val extGainWeight = profile.extGainWeight
            val slot1MarginSec = profile.slot1MarginSec

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

            // Dead-End Reading Gate (Idea 7): Detect if the input has no natural full-span word in lexicon
            val hasNaturalFullWord = topPool.any { cand ->
                cand.obsPenalty == 0.0f &&
                !cand.isPrediction &&
                cand.dictCost <= 5500 &&
                (cand.source in listOf("dict", "user", "mozc") || cand.historyCount >= 1) &&
                cand.reading.isNotEmpty() && cand.reading.length == n
            }
            val isDeadEndReading = !isEnglishPool && n in 2..5 && !hasNaturalFullWord

            var fullRankCounter = 0
            val stratumRanks = IntArray(topPool.size) { i ->
                if (unconsumedDeltas[i] == 0 && extDeltas[i] == 0 && !topPool[i].isPrediction) {
                    fullRankCounter++
                } else {
                    i
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
                } else if (n == 0 && !isEnglishPool) {
                    // (c-0) Zero-query prediction: length-normalized surprisal without uninputted penalty
                    val tokens = maxOf(1, (mLengths[index] + 1) / 2)
                    rawBaseNll = rawNll / tokens.toFloat().pow(0.85f)
                } else {
                    // (c) Uninputted prefix extension cost (waive extension penalty for user history >= 2 or history_bigram)
                    val waiveExt = candidate.historyCount >= 2 ||
                        candidate.source == "history_bigram" ||
                        candidate.source == "history_skip_bigram"
                    rawBaseNll = if (waiveExt) rawNll else rawNll + lambdaExt * maxOf(1, deltaExt)
                }

                // Hybrid-6b History x SLM Fusion (Contextual Bigram Cache + Time-Decay + Coherence Gate + Gated Soft-Clamping + c=1 Damping)
                val isBigram = candidate.source == "history_bigram" || candidate.source == "history_skip_bigram"
                val effectiveNll: Float = if (!isExactUser && (candidate.historyCount > 0 || isBigram) && !isChimeraFull[index]) {
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

                    val clampedBase = if (candidate.historyCount >= 2 || isBigram) {
                        val eta = gate * (if (isBigram) 0.90f else minOf(0.70f, (candidate.historyCount - 1) / 5.0f)) * longDecay
                        eta * minOf(sysBestNll, rawBaseNll) + (1.0f - eta) * rawBaseNll
                    } else {
                        rawBaseNll
                    }

                    val bigramBoost = if (isBigram) 6.0f * longDecay else 0.0f
                    val shortTerm = if (candidate.lastUsedAtMs > 0L) 2.2f * shortDecay else 0.0f
                    val longTerm = 1.8f * minOf(kotlin.math.ln(1.0f + candidate.historyCount.toFloat()), 3.0f) * longDecay
                    val c1Damp = if (candidate.historyCount == 1 && !isBigram) 0.30f else 1.0f
                    clampedBase - gate * (bigramBoost + shortTerm + longTerm) * c1Damp
                } else {
                    rawBaseNll
                }

                val obsDiscount = if (!isEnglishPool && candidate.obsPenalty > 0f) {
                    val deltaNll = rawNll - bestPoolNll
                    val baseDisc = 0.20f + 0.80f * ((deltaNll - 1.5f) / 3.0f).coerceIn(0.0f, 1.0f)
                    if (isDeadEndReading) baseDisc * 0.35f else baseDisc
                } else {
                    1.0f
                }
                val effectiveObs = lambdaObs * candidate.obsPenalty * 5.0f * obsDiscount
                val dictCostPenalty = if (!isEnglishPool) {
                    candidate.dictCost.coerceIn(0, 15000) / 3000.0f
                } else {
                    0.0f
                }
                val rankCost = minOf(4, stratumRanks[index]).toFloat()

                val hysteresisBonus = if (currentShownTop1 != null && candidate.surface == currentShownTop1 && !isExactUser) {
                    hysteresisDelta
                } else {
                    0.0f
                }

                baseScores[index] = (1.0f - weightAlpha) * (effectiveNll + effectiveObs + dictCostPenalty + hysteresisBonus) + weightAlpha * rankCost
            }

            // Compute Softmax Probabilities and Expected EIS Utility for all candidates (B1)
            val logPostScores = FloatArray(topPool.size) { i ->
                val cand = topPool[i]
                val isExactUser = (cand.source == "user" || cand.source == "user_english") && cand.obsPenalty == 0.0f
                if (isExactUser) {
                    10.0f
                } else if (isEnglishPool) {
                    -(baseScores[i])
                } else {
                    // Length-normalized surprisal for Japanese predictive probability estimation (B1)
                    val tokens = maxOf(1, (mLengths[i] + 1) / 2)
                    val normNll = scores[i] / tokens.toFloat().pow(0.85f)
                    val obsDiscount = if (cand.obsPenalty > 0f) {
                        val deltaNll = scores[i] - bestPoolNll
                        val baseDisc = 0.20f + 0.80f * ((deltaNll - 1.5f) / 3.0f).coerceIn(0.0f, 1.0f)
                        if (isDeadEndReading) baseDisc * 0.35f else baseDisc
                    } else {
                        1.0f
                    }
                    val obsEff = lambdaObs * cand.obsPenalty * 5.0f * obsDiscount
                    val c = cand.dictCost.coerceIn(0, 15000)
                    val rareExcess = maxOf(0f, (c - 6000) / 2000.0f)
                    val dictCostNats = (c / 3000.0f) + 0.45f * rareExcess * rareExcess
                    val countPrior = if (cand.historyCount > 0) minOf(3.0f, cand.historyCount.toFloat()) else 0.0f
                    val bigramPrior = when (cand.source) {
                        "history_skip_bigram" -> 2.5f
                        "history_bigram" -> 1.8f
                        "canonical_projected" -> 0.5f
                        else -> 0.0f
                    }
                    val priorHist = countPrior + bigramPrior
                    val rankCost = (minOf(4, stratumRanks[i]) * 0.15f)
                    -(normNll + obsEff + rankCost + dictCostNats - priorHist)
                }
            }
            val maxLogPost = logPostScores.maxOrNull() ?: 0.0f
            var sumExp = 0.0
            val expScores = DoubleArray(topPool.size) { i ->
                val e = kotlin.math.exp(((logPostScores[i] - maxLogPost) / 2.0f).coerceIn(-30.0f, 30.0f).toDouble())
                sumExp += e
                e
            }
            val probs = FloatArray(topPool.size)
            val gTotals = FloatArray(topPool.size)
            val lErrs = FloatArray(topPool.size)
            val utilities = FloatArray(topPool.size) { i ->
                val cand = topPool[i]
                val isExactUser = (cand.source == "user" || cand.source == "user_english") && cand.obsPenalty == 0.0f
                if (isExactUser) {
                    probs[i] = 1.0f
                    gTotals[i] = 10.0f
                    lErrs[i] = 0.0f
                    10.0f - (i * 0.01f)
                } else {
                    val p = if (sumExp > 0.0) (expScores[i] / sumExp).toFloat() else (1.0f / topPool.size)
                    val deltaUnc = unconsumedDeltas[i]
                    val deltaExt = extDeltas[i]
                    val gBase = if (deltaUnc == 0) {
                        keyCostSec * minOf(n, mLengths[i])
                    } else {
                        keyCostSec * mLengths[i] - 0.60f * deltaUnc
                    }
                    // Sublinear extension gain with frequency damping for long rare words (Idea 8)
                    val effectiveExt = if (deltaExt <= 4) deltaExt.toFloat() else 4.0f + kotlin.math.sqrt((deltaExt - 4).toFloat())
                    val extFreqWeight = if (cand.dictCost <= 5000) 1.0f else (1.0f - (cand.dictCost - 5000).coerceIn(0, 10000) / 12000.0f).coerceIn(0.2f, 1.0f)
                    val gExt = extGainWeight * keyCostSec * effectiveExt * extFreqWeight
                    val lErr = if (n == 0 && !isEnglishPool) {
                        keyCostSec
                    } else {
                        1.50f + 0.20f * cand.surface.length + keyCostSec * mLengths[i]
                    }
                    probs[i] = p
                    gTotals[i] = gBase + gExt
                    lErrs[i] = lErr
                    p * (gBase + gExt) - (1.0f - p) * riskWeight * lErr
                }
            }
            val canonicalStems = Array(topPool.size) { i -> extractCanonicalStem(topPool[i], topPool, isEnglishPool) }
            val horizons = Array(topPool.size) { i -> classifyHorizon(topPool[i], unconsumedDeltas[i], extDeltas[i], n) }

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

            // 9. Extensions / Predictions ranked by Submodular Marginal Expected EIS Utility (Idea D1)
            val residualMultiplier = FloatArray(topPool.size) { 1.0f }
            // Condition on exactUser and top-2 full candidates to avoid redundant stem/horizon crowding
            for (uIdx in exactUserIndices) {
                for (eIdx in extIndices) {
                    val kappa = computeIntentOverlapKappa(
                        canonicalStems[eIdx], horizons[eIdx], topPool[eIdx].lastContentWordSurface,
                        canonicalStems[uIdx], horizons[uIdx], topPool[uIdx].lastContentWordSurface
                    )
                    residualMultiplier[eIdx] *= (1.0f - kappa)
                }
            }
            for (fCand in rankedFull.take(2)) {
                val fIdx = topPool.indexOf(fCand)
                if (fIdx >= 0) {
                    for (eIdx in extIndices) {
                        val kappa = computeIntentOverlapKappa(
                            canonicalStems[eIdx], horizons[eIdx], topPool[eIdx].lastContentWordSurface,
                            canonicalStems[fIdx], horizons[fIdx], topPool[fIdx].lastContentWordSurface
                        )
                        residualMultiplier[eIdx] *= (1.0f - kappa)
                    }
                }
            }

            // Greedy submodular selection for Slot 2+; conditioned on already-decided Slot 1 / D2 anchor candidates.
            fun runExtGreedy(conditionOnIndices: List<Int>): List<Int> {
                val residual = residualMultiplier.copyOf()
                val ordered = mutableListOf<Int>()
                val remaining = extIndices.toMutableList()
                var rankPos = 0
                for (condIdx in conditionOnIndices) {
                    if (condIdx in remaining) {
                        ordered.add(condIdx)
                        remaining.remove(condIdx)
                    }
                    // Apply overlap decay regardless of whether condIdx is in extIndices (e.g. EXACT anchor)
                    for (otherIdx in remaining) {
                        val kappa = computeIntentOverlapKappa(
                            canonicalStems[otherIdx], horizons[otherIdx], topPool[otherIdx].lastContentWordSurface,
                            canonicalStems[condIdx], horizons[condIdx], topPool[condIdx].lastContentWordSurface
                        )
                        if (kappa > 0.0f) residual[otherIdx] *= (1.0f - kappa)
                    }
                    rankPos++
                }
                while (remaining.isNotEmpty()) {
                    val slotRiskWeight = riskWeight * 0.65f.pow(minOf(rankPos, 4))
                    var bestIdx = -1
                    var bestMarginalU = -Float.MAX_VALUE
                    for (idx in remaining) {
                        val effectiveP = probs[idx] * residual[idx]
                        val marginalU = effectiveP * gTotals[idx] - (1.0f - probs[idx]) * slotRiskWeight * lErrs[idx]
                        val diff = marginalU - bestMarginalU
                        if (bestIdx < 0 || diff > 0.03f || (kotlin.math.abs(diff) <= 0.03f && baseScores[idx] < baseScores[bestIdx])) {
                            bestMarginalU = marginalU
                            bestIdx = idx
                        }
                    }
                    if (bestIdx < 0) break
                    ordered.add(bestIdx)
                    remaining.remove(bestIdx)
                    for (otherIdx in remaining) {
                        val kappa = computeIntentOverlapKappa(
                            canonicalStems[otherIdx], horizons[otherIdx], topPool[otherIdx].lastContentWordSurface,
                            canonicalStems[bestIdx], horizons[bestIdx], topPool[bestIdx].lastContentWordSurface
                        )
                        if (kappa > 0.0f) residual[otherIdx] *= (1.0f - kappa)
                    }
                    rankPos++
                }
                return ordered
            }

            // Slot 1 promotion candidates are chosen by INDEPENDENT utility (r = 1 has no prior selection to condition on)
            val independentExtOrder = extIndices.sortedWith { a, b ->
                val uA = utilities[a]
                val uB = utilities[b]
                if (kotlin.math.abs(uA - uB) > 0.05f) uB.compareTo(uA) else baseScores[a].compareTo(baseScores[b])
            }
            val topHighPriIdx = independentExtOrder.firstOrNull { topPool[it].priority >= 3.5f }
            val topHistPredIdx = independentExtOrder.firstOrNull {
                topPool[it].historyCount >= 2 ||
                    topPool[it].source == "history_bigram" ||
                    topPool[it].source == "history_skip_bigram"
            }
            val bestExtIdx = independentExtOrder.firstOrNull()

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
            // Stratum 1: Top full candidates + Prediction / History Promotion (Slot-2 / Conditional Top-1)
            var fullIdx = 0
            var partialIdx = 0
            val effectiveSlot1Margin = if (isDeadEndReading) 0.0f else slot1MarginSec
            val candidateToPromote = if (rankedFull.isNotEmpty() && n > 0) {
                if (n == 1) {
                    topHighPriIdx ?: topHistPredIdx
                } else {
                    topHighPriIdx ?: topHistPredIdx ?: bestExtIdx?.takeIf { extIdx ->
                        val topFullIdx = topPool.indexOf(rankedFull[0])
                        topFullIdx >= 0 && (
                            utilities[extIdx] > utilities[topFullIdx] + effectiveSlot1Margin ||
                            topPool[extIdx].priority >= 3.5f ||
                            (isDeadEndReading && utilities[extIdx] > 0.0f)
                        )
                    }
                }
            } else {
                null
            }

            var promotedCandidateSurface: String? = null
            var promotedExtIdx: Int? = null
            var topChunkIdxForAnchor: Int? = null
            if (candidateToPromote != null && rankedFull.isNotEmpty() && n > 0) {
                val topFullIdx = topPool.indexOf(rankedFull[0])
                val isStrictCoherent = topFullIdx >= 0 && (scores[candidateToPromote] - scores[topFullIdx] < 5.0f || topPool[candidateToPromote].priority >= 3.5f)
                val canPromoteTop1 = !isEnglishPool && (n in 2..4 || (n == 1 && topPool[candidateToPromote].priority >= 3.5f)) && topFullIdx >= 0 && (isStrictCoherent || isDeadEndReading) && (
                    utilities[candidateToPromote] > utilities[topFullIdx] + effectiveSlot1Margin ||
                    topPool[candidateToPromote].priority >= 3.5f ||
                    (isDeadEndReading && utilities[candidateToPromote] > 0.0f) ||
                    (candidateToPromote == topHistPredIdx && baseScores[candidateToPromote] < baseScores[topFullIdx] - 0.50f)
                )
                if (canPromoteTop1) {
                    val cand = topPool[candidateToPromote]
                    promotedCandidateSurface = cand.surface
                    promotedExtIdx = candidateToPromote
                    if (horizons[candidateToPromote] == HorizonBucket.CHUNK_EXT) {
                        topChunkIdxForAnchor = candidateToPromote
                    }
                    addCandidate(cand)
                    addCandidate(rankedFull[fullIdx++])
                } else if (candidateToPromote == topHistPredIdx) {
                    val cand = topPool[candidateToPromote]
                    promotedCandidateSurface = cand.surface
                    promotedExtIdx = candidateToPromote
                    addCandidate(rankedFull[fullIdx++])
                    addCandidate(cand)
                } else {
                    addCandidate(rankedFull[fullIdx++])
                }
            } else if (rankedFull.isNotEmpty()) {
                addCandidate(rankedFull[fullIdx++])
            } else if (bestExtIdx != null && (n == 0 || horizons[bestExtIdx] == HorizonBucket.CHUNK_EXT)) {
                if (horizons[bestExtIdx] == HorizonBucket.CHUNK_EXT) {
                    topChunkIdxForAnchor = bestExtIdx
                }
                promotedExtIdx = bestExtIdx
                promotedCandidateSurface = topPool[bestExtIdx].surface
                addCandidate(topPool[bestExtIdx])
            }

            // Idea D2 / Z6: Dual-Horizon Pairing (Pair a short anchor stem or pivot particle in Slot 2 when a chunk wins Slot 1)
            var pairedAnchorIdx: Int? = null
            if (!isEnglishPool && topChunkIdxForAnchor != null) {
                val chunkStem = canonicalStems[topChunkIdxForAnchor]
                val chunkLen = topPool[topChunkIdxForAnchor].surface.length
                val sameStemAnchor = if (chunkStem.isNotEmpty()) {
                    topPool.indices
                        .filter { idx ->
                            idx != topChunkIdxForAnchor &&
                                canonicalStems[idx] == chunkStem &&
                                (horizons[idx] == HorizonBucket.SHORT_EXT || horizons[idx] == HorizonBucket.EXACT) &&
                                topPool[idx].surface.length < chunkLen &&
                                !seenSurfaces.contains(topPool[idx].surface)
                        }
                        .maxByOrNull { utilities[it] }
                } else {
                    null
                }
                val anchorIdx = sameStemAnchor ?: if (n == 0) {
                    topPool.indices
                        .filter { idx ->
                            idx != topChunkIdxForAnchor &&
                                topPool[idx].source == "particle" &&
                                !seenSurfaces.contains(topPool[idx].surface)
                        }
                        .maxByOrNull { utilities[it] }
                } else {
                    null
                }
                if (anchorIdx != null) {
                    pairedAnchorIdx = anchorIdx
                    addCandidate(topPool[anchorIdx])
                }
            }

            while (fullIdx < minOf(topFullQuota, rankedFull.size)) {
                addCandidate(rankedFull[fullIdx++])
            }
            // Interleave remaining high-utility prediction candidate into Stratum 2/3 (B1: Eliminates Stratum 6 burial)
            // R1: Slot 2+ submodular greedy is conditioned on the already-decided Slot 1 promotion and D2 anchor.
            val conditionedIndices = listOfNotNull(promotedExtIdx, pairedAnchorIdx)
            val rankedExtIndices = runExtGreedy(conditionedIndices)
            val rankedExt = rankedExtIndices.map { topPool[it] }
            // R5: exclude surfaces already emitted (e.g. paired anchor) so slots aren't wasted.
            val remainingExt = rankedExt.filter { it.surface != promotedCandidateSurface && it.surface !in seenSurfaces }
            var remExtIdx = 0
            if (remExtIdx < remainingExt.size && !isEnglishPool && (remainingExt[remExtIdx].priority >= 3.0f || utilities[topPool.indexOf(remainingExt[remExtIdx])] > 0.0f)) {
                addCandidate(remainingExt[remExtIdx++])
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
            if (remExtIdx < remainingExt.size && !isEnglishPool) {
                addCandidate(remainingExt[remExtIdx++])
            }
            // Stratum 4: Remaining partial candidates
            while (partialIdx < rankedPartial.size) {
                addCandidate(rankedPartial[partialIdx++])
            }
            // Stratum 5: Remaining full candidates
            while (fullIdx < rankedFull.size) {
                addCandidate(rankedFull[fullIdx++])
            }
            // Remaining extension / prediction candidates
            while (remExtIdx < remainingExt.size) {
                addCandidate(remainingExt[remExtIdx++])
            }

            return result
        }
    }

    /**
     * Checks if the SLM model file is installed in the app's internal or external storage.
     * @param fileName Optional filename. Defaults to the configured engine model or Qwen3.5-0.8B.
     */
    fun getModelFile(fileName: String = RerankEngineType.DEFAULT.modelFileName): File {
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

    fun isModelInstalled(fileName: String = RerankEngineType.DEFAULT.modelFileName): Boolean {
        val file = getModelFile(fileName)
        return file.exists() && file.length() > 10 * 1024 * 1024 // > 10MB
    }

    fun getTargetModelFileName(): String {
        val selected = KeyboardHeightManager.getRerankEngineType(context)
        return if (selected.modelFileName.isNotEmpty()) {
            selected.modelFileName
        } else {
            RerankEngineType.DEFAULT.modelFileName
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
                val isParticleExact = JapaneseKanaUtils.isCommonParticle(it.surface) && it.surface == it.reading
                val isRedundantHiragana = !isParticleExact && !it.isPrediction && JapaneseKanaUtils.isAllHiragana(it.surface) && it.surface == it.reading
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
            for (c in nonHiraganaCandidates.filter { it.historyCount >= 2 }.sortedByDescending { it.historyCount }.take(4)) {
                if (topPoolSurfaces.add(c.surface)) topPool.add(c)
            }
            val prioritizedFullCands = fullCands.filter { it.source != "system" } + fullCands.filter { it.source == "system" }
            for (c in prioritizedFullCands.take(8)) {
                if (topPool.size >= 16) break
                if (topPoolSurfaces.add(c.surface)) topPool.add(c)
            }
            val singleWordPrefix = prefixCands.filter { it.source != "phrase_chunk" }
            val phraseChunkPrefix = prefixCands.filter { it.source == "phrase_chunk" }

            // Idea 10: Protect at least 2 slots for short-extension high-frequency fuzzy/phonetic candidates
            val fuzzyShort = singleWordPrefix.filter { it.obsPenalty > 0f && it.dictCost <= 6000 }
            val regularSingle = singleWordPrefix.filter { it !in fuzzyShort }
            val prioritizedSingle = mutableListOf<ConversionCandidate>()
            prioritizedSingle.addAll(fuzzyShort.take(2))
            prioritizedSingle.addAll(regularSingle)
            if (fuzzyShort.size > 2) prioritizedSingle.addAll(fuzzyShort.drop(2))

            val diversifiedPhraseChunkPrefix = diversifyByFirstStem(phraseChunkPrefix)
            val balancedPrefix = mutableListOf<ConversionCandidate>()
            balancedPrefix.addAll(prioritizedSingle.take(4))
            balancedPrefix.addAll(diversifiedPhraseChunkPrefix.take(4))
            if (balancedPrefix.size < 8) {
                val remaining = prioritizedSingle.drop(4) + diversifiedPhraseChunkPrefix.drop(4)
                balancedPrefix.addAll(remaining.take(8 - balancedPrefix.size))
            }
            for (c in balancedPrefix) {
                if (topPool.size >= 16) break
                if (topPoolSurfaces.add(c.surface)) topPool.add(c)
            }
            for (c in nonHiraganaCandidates) {
                if (topPool.size >= 16) break
                if (topPoolSurfaces.add(c.surface)) topPool.add(c)
            }
            val restPool = nonHiraganaCandidates.filter { cand -> topPool.none { it.surface == cand.surface } }

            val trimmedContext = ContextWindowTrimmer.trimToOptimalContextSticky(contextPrefix)
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
                val isParticleExact = JapaneseKanaUtils.isCommonParticle(it.surface) && it.surface == it.reading
                val isRedundantHiragana = !isParticleExact && !it.isPrediction && JapaneseKanaUtils.isAllHiragana(it.surface) && it.surface == it.reading
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
            val prioritizedFullCands = fullCands.filter { it.source != "system" } + fullCands.filter { it.source == "system" }
            for (c in prioritizedFullCands.take(8)) {
                if (topPool.size >= 16) break
                if (topPoolSurfaces.add(c.surface)) topPool.add(c)
            }
            val singleWordPrefix = prefixCands.filter { it.source != "phrase_chunk" }
            val phraseChunkPrefix = prefixCands.filter { it.source == "phrase_chunk" }

            // Idea 10: Protect at least 2 slots for short-extension high-frequency fuzzy/phonetic candidates
            val fuzzyShort = singleWordPrefix.filter { it.obsPenalty > 0f && it.dictCost <= 6000 }
            val regularSingle = singleWordPrefix.filter { it !in fuzzyShort }
            val prioritizedSingle = mutableListOf<ConversionCandidate>()
            prioritizedSingle.addAll(fuzzyShort.take(2))
            prioritizedSingle.addAll(regularSingle)
            if (fuzzyShort.size > 2) prioritizedSingle.addAll(fuzzyShort.drop(2))

            val diversifiedPhraseChunkPrefix = diversifyByFirstStem(phraseChunkPrefix)
            val balancedPrefix = mutableListOf<ConversionCandidate>()
            balancedPrefix.addAll(prioritizedSingle.take(4))
            balancedPrefix.addAll(diversifiedPhraseChunkPrefix.take(4))
            if (balancedPrefix.size < 8) {
                val remaining = prioritizedSingle.drop(4) + diversifiedPhraseChunkPrefix.drop(4)
                balancedPrefix.addAll(remaining.take(8 - balancedPrefix.size))
            }
            for (c in balancedPrefix) {
                if (topPool.size >= 16) break
                if (topPoolSurfaces.add(c.surface)) topPool.add(c)
            }
            for (c in nonHiraganaCandidates) {
                if (topPool.size >= 16) break
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

            val n = targetInputLength ?: fullCands.maxOfOrNull { it.reading.length } ?: 0
            val isEnglishPool = topPool.first().let { c ->
                c.source in listOf("user_english", "english_typo", "english_cap", "english_gec", "raw") ||
                    (c.reading.isNotEmpty() && c.reading.all { it.code < 128 && (it.isLetter() || it == '\'' || it == '-') })
            }
            val muSpan = if (isEnglishPool) DEFAULT_MU_SPAN_EN else DEFAULT_MU_SPAN_JA
            val surfaceMinPriorMap = HashMap<String, Float>()
            for (i in topPool.indices) {
                val cand = topPool[i]
                val evalSurf = evalSurfaces[i]
                val isExactUser = (cand.source == "user" || cand.source == "user_english") && cand.obsPenalty == 0.0f
                val priorLowerBound = if (isExactUser || cand.historyCount >= 2 || cand.source == "history_bigram") {
                    0.0f
                } else {
                    val dictPenalty = if (!isEnglishPool) (cand.dictCost.coerceIn(0, 15000) / 3000.0f) else 0.0f
                    val minObsPenalty = lambdaObs * cand.obsPenalty * 5.0f * 0.07f
                    val mLen = if (cand.reading.isNotEmpty()) cand.reading.length else cand.surface.length
                    val deltaUnc = maxOf(0, n - mLen)
                    val spanPenalty = if (deltaUnc > 0 && cand.completedSurface.isEmpty()) (muSpan * deltaUnc) else 0.0f
                    dictPenalty + minObsPenalty + spanPenalty
                }
                val prev = surfaceMinPriorMap[evalSurf]
                if (prev == null || priorLowerBound < prev) {
                    surfaceMinPriorMap[evalSurf] = priorLowerBound
                }
                latentVariantsMap[evalSurf]?.forEach { variant ->
                    val vPrev = surfaceMinPriorMap[variant]
                    if (vPrev == null || priorLowerBound < vPrev) {
                        surfaceMinPriorMap[variant] = priorLowerBound
                    }
                }
            }

            val uniqueSurfaces = surfacesToScore.distinct().toTypedArray()
            val uniquePriors = FloatArray(uniqueSurfaces.size) { idx ->
                surfaceMinPriorMap[uniqueSurfaces[idx]] ?: 0.0f
            }
            val trimmedContext = ContextWindowTrimmer.trimToOptimalContextSticky(contextPrefix)
            val effectiveContext = if (trimmedContext.isBlank()) "<s>" else trimmedContext
            val uniqueScores = nativeScoreCandidatesWithPriors(effectiveContext, uniqueSurfaces, uniquePriors)

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
            val trimmedContext = ContextWindowTrimmer.trimToOptimalContextSticky(contextPrefix)
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
    fun predictNextPhrases(
        contextPrefix: String,
        maxCandidates: Int = 3,
        maxTokens: Int = 5,
        readingResolver: ((String) -> String)? = null
    ): List<ConversionCandidate> {
        if (state != State.READY || !nativeLibraryLoaded) {
            return emptyList()
        }
        try {
            val trimmedContext = ContextWindowTrimmer.trimToOptimalContextSticky(contextPrefix)
            val effectiveContext = if (trimmedContext.isBlank()) "<s>" else trimmedContext
            val surfaces = nativePredictNextPhrases(effectiveContext, maxCandidates, maxTokens) ?: return emptyList()
            return surfaces
                .map { it.trim { ch -> ch.isWhitespace() || ch == '\u3000' } }
                .filter { it.isNotEmpty() }
                .distinct()
                .map { surface ->
                    val resolvedReading = readingResolver?.invoke(surface)?.takeIf { it.isNotEmpty() } ?: surface
                    ConversionCandidate(
                        surface = surface,
                        reading = resolvedReading,
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
     * Phase 3 (Z5): Speculatively pre-warms KV cache and next-phrase predictions for (context + Top-1)
     * while the user is visually confirming candidates before tapping commit.
     */
    fun speculativePrepareNextPhrases(
        contextPlusTop1: String,
        maxCandidates: Int = 3,
        maxTokens: Int = 5,
        readingResolver: ((String) -> String)? = null
    ) {
        if (state != State.READY || !nativeLibraryLoaded || contextPlusTop1.isBlank()) return
        val trimmed = ContextWindowTrimmer.trimToOptimalContextSticky(contextPlusTop1)
        if (trimmed.isBlank()) return
        val existing = speculativeZeroQueryCache
        if (existing != null && existing.first == trimmed) return

        val preds = predictNextPhrases(
            contextPrefix = trimmed,
            maxCandidates = maxCandidates,
            maxTokens = maxTokens,
            readingResolver = readingResolver
        )
        if (preds.isNotEmpty()) {
            speculativeZeroQueryCache = Pair(trimmed, preds)
        }
    }

    /**
     * Returns pre-computed speculative next-phrase predictions if the committed context matches.
     */
    fun getSpeculativeNextPhrases(contextBeforeCursor: String): List<ConversionCandidate>? {
        val trimmed = ContextWindowTrimmer.trimToOptimalContextSticky(contextBeforeCursor)
        val cached = speculativeZeroQueryCache ?: return null
        return if (cached.first == trimmed) cached.second else null
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
            val trimmed = ContextWindowTrimmer.trimToOptimalContextSticky(contextPrefix)
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
    private external fun nativeScoreCandidatesWithPriors(context: String, candidates: Array<String>, priorPenalties: FloatArray): FloatArray?
    private external fun nativeTryFastScoreCandidates(context: String, candidates: Array<String>, outIsComplete: BooleanArray): FloatArray?
    private external fun nativeRequestAbortScoring()
    private external fun nativeWarmContext(context: String): Boolean
    private external fun nativePredictNextPhrases(context: String, maxCandidates: Int, maxTokens: Int): Array<String>?
    private external fun nativeRelease()
    private external fun nativeIsReady(): Boolean
}
