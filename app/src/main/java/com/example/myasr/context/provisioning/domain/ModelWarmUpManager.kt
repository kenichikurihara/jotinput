package com.example.myasr.context.provisioning.domain

import com.example.myasr.AsrApp
import com.example.myasr.context.english.domain.EnglishCapitalizationAnalyzer
import com.example.myasr.context.english.domain.InputFieldCapPolicy
import com.example.myasr.context.english.infrastructure.EnglishSpellEngine
import com.example.myasr.context.english.infrastructure.FutoSwipeEngine
import com.example.myasr.context.ime.presentation.KeyboardHeightManager
import com.example.myasr.context.kanakanji.domain.KanaKanjiEngine
import com.example.myasr.context.kanakanji.domain.NextPhrasePredictor
import com.example.myasr.context.reranking.infrastructure.LlmReranker
import com.example.myasr.context.speech.domain.AsrLanguage
import com.example.myasr.context.speech.infrastructure.AsrManager

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Orchestrates non-blocking, parallel background model loading and warm-up pipeline.
 *
 * Latency optimization contract:
 * - Cold-start latency for Kana-Kanji (~235ms), English Spell/Swipe (~1.2s+), ASR (~2.8s),
 *   and SLM (~11.7s) drops to <2ms (typing) and ~148ms (ASR) when pre-warmed.
 * - Executes strictly off the Android UI main thread (on Dispatchers.Default / IO).
 * - All independent engines launch concurrently so English cold start never waits behind
 *   Japanese ASR or SLM, and vice versa:
 *   - Interactive Typing Tier (concurrent): KanaKanjiEngine, NextPhrasePredictor,
 *     EnglishSpellEngine, FutoSwipeEngine
 *   - Neural / Voice Tier (concurrent): LlmReranker, AsrManager (JA), AsrManager (EN)
 */
class ModelWarmUpManager private constructor(private val context: Context) {

    private val TAG = "ModelWarmUpManager"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val warmUpStarted = AtomicBoolean(false)
    private val warmUpCompleted = AtomicBoolean(false)

    enum class Status {
        IDLE,
        WARMING,
        READY,
        FAILED
    }

    @Volatile var kanaKanjiStatus = Status.IDLE; private set
    @Volatile var nextPhraseStatus = Status.IDLE; private set
    @Volatile var englishSpellStatus = Status.IDLE; private set
    @Volatile var futoSwipeStatus = Status.IDLE; private set
    @Volatile var slmStatus = Status.IDLE; private set
    @Volatile var asrStatus = Status.IDLE; private set
    @Volatile var asrEnStatus = Status.IDLE; private set

    companion object {
        @Volatile
        private var instance: ModelWarmUpManager? = null

        fun getInstance(context: Context): ModelWarmUpManager {
            return instance ?: synchronized(this) {
                instance ?: ModelWarmUpManager(context.applicationContext).also { instance = it }
            }
        }
    }

    /**
     * Starts the parallel background warm-up pipeline if not already running or completed.
     * Guaranteed 0ms block on the caller thread.
     */
    fun startWarmUpAsync() {
        if (!warmUpStarted.compareAndSet(false, true)) {
            Log.d(TAG, "Warm-up pipeline already started or completed.")
            return
        }

        scope.launch {
            Log.i(TAG, "Starting parallel non-blocking background model warm-up pipeline...")
            val t0 = System.currentTimeMillis()

            coroutineScope {
                // 1a. Kana-Kanji Engine (Immediate interactive Japanese flick typing)
                launch {
                    try {
                        kanaKanjiStatus = Status.WARMING
                        val k0 = System.currentTimeMillis()
                        withTimeout(5000L) {
                            KanaKanjiEngine.getInstance(context).warmUp()
                        }
                        kanaKanjiStatus = Status.READY
                        Log.i(TAG, "KanaKanjiEngine warmed up in ${System.currentTimeMillis() - k0}ms")
                    } catch (e: TimeoutCancellationException) {
                        kanaKanjiStatus = Status.FAILED
                        Log.w(TAG, "KanaKanjiEngine warm-up timed out (>5000ms)")
                    } catch (e: Exception) {
                        kanaKanjiStatus = Status.FAILED
                        Log.w(TAG, "KanaKanjiEngine warm-up error: ${e.message}")
                    }
                }

                // 1b. NextPhrasePredictor (Zero-query continuation history)
                launch(Dispatchers.IO) {
                    try {
                        nextPhraseStatus = Status.WARMING
                        val n0 = System.currentTimeMillis()
                        NextPhrasePredictor.getInstance(context).ensureHistoryLoaded()
                        nextPhraseStatus = Status.READY
                        Log.i(TAG, "NextPhrasePredictor history warmed up in ${System.currentTimeMillis() - n0}ms")
                    } catch (e: Exception) {
                        nextPhraseStatus = Status.FAILED
                        Log.w(TAG, "NextPhrasePredictor warm-up error: ${e.message}")
                    }
                }

                // 1c. EnglishSpellEngine (Fast-path SymSpell + GEC ONNX) + EnglishCapitalizationAnalyzer
                launch(Dispatchers.IO) {
                    try {
                        englishSpellStatus = Status.WARMING
                        val e0 = System.currentTimeMillis()
                        EnglishCapitalizationAnalyzer.analyze("i", "Hello. ", false, InputFieldCapPolicy.CAP_SENTENCES)
                        withTimeout(8000L) {
                            EnglishSpellEngine.getInstance(context).ensureInitialized()
                        }
                        englishSpellStatus = Status.READY
                        Log.i(TAG, "EnglishSpellEngine warmed up in ${System.currentTimeMillis() - e0}ms")
                    } catch (e: TimeoutCancellationException) {
                        englishSpellStatus = Status.FAILED
                        Log.w(TAG, "EnglishSpellEngine warm-up timed out (>8000ms)")
                    } catch (e: Exception) {
                        englishSpellStatus = Status.FAILED
                        Log.w(TAG, "EnglishSpellEngine warm-up error: ${e.message}")
                    }
                }

                // 1d. FutoSwipeEngine (4-model ONNX + Trie)
                launch(Dispatchers.IO) {
                    try {
                        futoSwipeStatus = Status.WARMING
                        val f0 = System.currentTimeMillis()
                        withTimeout(8000L) {
                            FutoSwipeEngine.getInstance(context).ensureInitialized()
                        }
                        futoSwipeStatus = Status.READY
                        Log.i(TAG, "FutoSwipeEngine warmed up in ${System.currentTimeMillis() - f0}ms")
                    } catch (e: TimeoutCancellationException) {
                        futoSwipeStatus = Status.FAILED
                        Log.w(TAG, "FutoSwipeEngine warm-up timed out (>8000ms)")
                    } catch (e: Exception) {
                        futoSwipeStatus = Status.FAILED
                        Log.w(TAG, "FutoSwipeEngine warm-up error: ${e.message}")
                    }
                }

                // 2. SLM Reranker (llama.cpp Qwen2.5-0.5B causal log-likelihood)
                launch {
                    try {
                        if (!KeyboardHeightManager.isSlmRerankEnabled(context)) {
                            slmStatus = Status.READY
                            Log.i(TAG, "SLM Reranker is disabled in settings, skipping SLM warm-up.")
                        } else {
                            slmStatus = Status.WARMING
                            val s0 = System.currentTimeMillis()
                            val slm = LlmReranker.getInstance(context)
                            if (slm.isModelInstalled(slm.getTargetModelFileName())) {
                                val done = CompletableDeferred<Boolean>()
                                slm.warmUp(scope) { ready ->
                                    done.complete(ready)
                                }
                                val ready = withTimeout(15000L) { done.await() }
                                slmStatus = if (ready) Status.READY else Status.FAILED
                                Log.i(TAG, "SLM Reranker warm-up completed in ${System.currentTimeMillis() - s0}ms (status=$slmStatus)")
                            } else {
                                slmStatus = Status.READY
                                Log.d(TAG, "SLM model not installed, skipping SLM warm-up.")
                            }
                        }
                    } catch (e: TimeoutCancellationException) {
                        slmStatus = Status.FAILED
                        Log.w(TAG, "SLM Reranker warm-up timed out (>15000ms)")
                    } catch (e: Exception) {
                        slmStatus = Status.FAILED
                        Log.w(TAG, "SLM Reranker warm-up error: ${e.message}")
                    }
                }

                // 3a. ASR Manager (Japanese ReazonSpeech)
                launch(Dispatchers.IO) {
                    try {
                        asrStatus = Status.WARMING
                        val a0 = System.currentTimeMillis()
                        val app = context.applicationContext as? AsrApp
                        val asr = app?.asrManager ?: AsrManager(context)
                        if (asr.isModelAvailable(AsrLanguage.JAPANESE)) {
                            val ok = withTimeout(10000L) {
                                val hotwordsFile = app?.let {
                                    val activeEntries = it.dictionaryRepository.getActive()
                                    it.hotwordsGenerator.generate(activeEntries)
                                }
                                asr.warmUp(hotwordsFile, AsrLanguage.JAPANESE)
                            }
                            asrStatus = if (ok) Status.READY else Status.FAILED
                            Log.i(TAG, "AsrManager (JA) warmed up in ${System.currentTimeMillis() - a0}ms (status=$asrStatus)")
                        } else {
                            asrStatus = Status.READY
                            Log.d(TAG, "Japanese ASR model not downloaded, skipping ASR warm-up.")
                        }
                    } catch (e: TimeoutCancellationException) {
                        asrStatus = Status.FAILED
                        Log.w(TAG, "AsrManager (JA) warm-up timed out (>10000ms)")
                    } catch (e: Exception) {
                        asrStatus = Status.FAILED
                        Log.w(TAG, "AsrManager (JA) warm-up error: ${e.message}")
                    }
                }

                // 3b. ASR Manager (English Parakeet 110M) - runs concurrently with JA ASR
                launch(Dispatchers.IO) {
                    try {
                        asrEnStatus = Status.WARMING
                        val app = context.applicationContext as? AsrApp
                        val asr = app?.asrManager ?: AsrManager(context)
                        if (asr.isEnglishModelAvailable()) {
                            val e0 = System.currentTimeMillis()
                            val okEn = withTimeout(10000L) {
                                asr.warmUp(null, AsrLanguage.ENGLISH)
                            }
                            asrEnStatus = if (okEn) Status.READY else Status.FAILED
                            Log.i(TAG, "AsrManager (EN) warmed up in ${System.currentTimeMillis() - e0}ms (status=$asrEnStatus)")
                        } else {
                            asrEnStatus = Status.READY
                            Log.d(TAG, "English ASR model not downloaded, skipping EN ASR warm-up.")
                        }
                    } catch (e: TimeoutCancellationException) {
                        asrEnStatus = Status.FAILED
                        Log.w(TAG, "AsrManager (EN) warm-up timed out (>10000ms)")
                    } catch (e: Exception) {
                        asrEnStatus = Status.FAILED
                        Log.w(TAG, "AsrManager (EN) warm-up error: ${e.message}")
                    }
                }
            }

            warmUpCompleted.set(true)
            Log.i(TAG, "All background warm-up pipelines completed in ${System.currentTimeMillis() - t0}ms")
        }
    }

    fun isCompleted(): Boolean = warmUpCompleted.get()
}
