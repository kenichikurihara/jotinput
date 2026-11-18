package com.example.myasr.context.reranking.infrastructure

import com.example.myasr.MainActivity
import com.example.myasr.context.ime.presentation.KeyboardHeightManager
import com.example.myasr.context.kanakanji.domain.KanaKanjiEngine
import com.example.myasr.context.reranking.domain.RerankEngineType

import android.content.Context
import android.os.BatteryManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * On-device power and CPU consumption benchmark runner for comparing
 * Kana-Kanji conversion with and without SLM re-ranking.
 *
 * Triggered via ADB intent extras on MainActivity:
 *   --es bench_action "prepare" | "run"
 *   --es bench_engine "none" | "local_slm_3_5_0_8b" | "local_slm_3_5_2b"
 *   --es bench_workload "miss" | "hit" | "typing"
 *   --ei bench_iters 200
 */
object PowerBenchmarkRunner {
    private const val TAG = "PowerBench"

    data class BenchSample(
        val context: String,
        val reading: String,
        val steps: List<String>
    )

    private val SAMPLES = listOf(
        BenchSample("Mozcは私の直感と挙動が", "ことなります", listOf("こ", "こと", "ことな", "ことなり", "ことなります")),
        BenchSample("事故の目撃者から事故直後の", "じょうきょう", listOf("じ", "じょ", "じょう", "じょうきょ", "じょうきょう")),
        BenchSample("新幹線を使って新大阪へ", "いどう", listOf("い", "いど", "いどう")),
        BenchSample("四半期の定期的な人事", "いどう", listOf("い", "いど", "いどう")),
        BenchSample("アンケートにご協力いただき、ご", "かいとう", listOf("か", "かい", "かいと", "かいとう")),
        BenchSample("期末試験の数学の模範", "かいとう", listOf("か", "かい", "かいと", "かいとう")),
        BenchSample("新聞社の政治部の", "きしゃ", listOf("き", "きし", "きしゃ")),
        BenchSample("平素は格別のお引き立てを賜り", "きしゃ", listOf("き", "きし", "きしゃ")),
        BenchSample("有機化合物の合成と", "かがく", listOf("か", "かが", "かがく")),
        BenchSample("最先端の自然科学と", "かがく", listOf("か", "かが", "かがく")),
        BenchSample("新世代のアニメーション映画の", "せいさく", listOf("せ", "せい", "せいさ", "せいさく")),
        BenchSample("政府の新しい金融", "せいさく", listOf("せ", "せい", "せいさ", "せいさく")),
        BenchSample("工作機械で高精度部品の", "せいさく", listOf("せ", "せい", "せいさ", "せいさく")),
        BenchSample("無から有を生み出す芸術的な", "そうぞう", listOf("そ", "そう", "そうぞ", "そうぞう")),
        BenchSample("将来の暮らしを頭の中で", "そうぞう", listOf("そ", "そう", "そうぞ", "そうぞう")),
        BenchSample("現場の作業員に的確な", "しじ", listOf("し", "しじ")),
        BenchSample("多くの有権者から熱い", "しじ", listOf("し", "しじ")),
        BenchSample("司会者が会議のスムーズな", "しんこう", listOf("し", "しん", "しんこ", "しんこう")),
        BenchSample("伝統工芸と地方経済の", "しんこう", listOf("し", "しん", "しんこ", "しんこう")),
        BenchSample("美術館で印象派の絵画を", "かんしょう", listOf("か", "かん", "かんしょ", "かんしょう")),
        BenchSample("他国の内政問題に対して不当に", "かんしょう", listOf("か", "かん", "かんしょ", "かんしょう")),
        BenchSample("部屋の天井に明るいLEDの", "しょうめい", listOf("しょ", "しょう", "しょうめ", "しょうめい")),
        BenchSample("数学の定理を厳密に", "しょうめい", listOf("しょ", "しょう", "しょうめ", "しょうめい")),
        BenchSample("少子高齢化による労働人口の", "げんしょう", listOf("げ", "げん", "げんしょ", "げんしょう")),
        BenchSample("自然界で起きる不思議な超常", "げんしょう", listOf("げ", "げん", "げんしょ", "げんしょう"))
    )

    fun handleIntent(
        context: Context,
        scope: CoroutineScope,
        action: String,
        engineStr: String,
        workload: String,
        iters: Int
    ) {
        scope.launch(Dispatchers.Default) {
            val engineType = RerankEngineType.fromId(engineStr)

            if (action == "prepare") {
                Log.i(TAG, "BENCH_PREPARE_START engine=${engineType.id}")
                val kanaEngine = KanaKanjiEngine.getInstance(context)
                kanaEngine.warmUp()

                if (engineType == RerankEngineType.NONE) {
                    KeyboardHeightManager.setRerankEngineType(context, RerankEngineType.NONE)
                    KeyboardHeightManager.setSlmRerankEnabled(context, false)
                    Log.i(TAG, "BENCH_PREPARE_DONE engine=none ready=true")
                } else {
                    KeyboardHeightManager.setRerankEngineType(context, engineType)
                    KeyboardHeightManager.setSlmRerankEnabled(context, true)
                    val slm = LlmReranker.getInstance(context)
                    var done = false
                    var ok = false
                    slm.initializeIfAvailable(scope, engineType.modelFileName) { success ->
                        ok = success
                        done = true
                    }
                    var waited = 0
                    while (!done && waited < 30000) {
                        kotlinx.coroutines.delay(50)
                        waited += 50
                    }
                    // Warm up with 3 dummy calls
                    if (slm.isReady()) {
                        val base = kanaEngine.getBaseCandidates("ことなります")
                        kanaEngine.rerankCandidatesWithStatus("Mozcは私の直感と挙動が", base)
                        kanaEngine.rerankCandidatesWithStatus("事故の目撃者から事故直後の", base)
                    }
                    Log.i(TAG, "BENCH_PREPARE_DONE engine=${engineType.id} ready=${slm.isReady()} ok=$ok")
                }
                return@launch
            }

            if (action == "run") {
                val kanaEngine = KanaKanjiEngine.getInstance(context)
                val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager

                val cpuStartMs = Process.getElapsedCpuTime()
                val wallStartMs = SystemClock.elapsedRealtime()
                val currentStartUa = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: 0
                val chargeStartUah = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) ?: 0

                var totalConversions = 0
                var slmAppliedCount = 0
                var totalBaseMs = 0.0
                var totalRerankMs = 0.0

                Log.i(TAG, "BENCH_RUN_START engine=${engineType.id} workload=$workload iters=$iters")

                when (workload) {
                    "hit" -> {
                        // Cycle in blocks of 20 queries per context so 95%+ hit the KV cache across diverse candidates
                        for (i in 0 until iters) {
                            val sample = SAMPLES[(i / 20) % SAMPLES.size]
                            kanaEngine.clearMemoryCache()
                            val t0 = SystemClock.elapsedRealtimeNanos()
                            val base = kanaEngine.getBaseCandidates(sample.reading)
                            val t1 = SystemClock.elapsedRealtimeNanos()
                            totalBaseMs += (t1 - t0) / 1_000_000.0

                            if (engineType != RerankEngineType.NONE) {
                                val (_, used) = kanaEngine.rerankCandidatesWithStatus(sample.context, base)
                                val t2 = SystemClock.elapsedRealtimeNanos()
                                totalRerankMs += (t2 - t1) / 1_000_000.0
                                if (used) slmAppliedCount++
                            }
                            totalConversions++
                        }
                    }
                    "typing" -> {
                        // Each iteration simulates typing a phrase step-by-step (1 context miss + N-1 context hits)
                        var stepCount = 0
                        var phraseIdx = 0
                        while (stepCount < iters) {
                            val sample = SAMPLES[phraseIdx % SAMPLES.size]
                            phraseIdx++
                            for (stepReading in sample.steps) {
                                if (stepCount >= iters) break
                                kanaEngine.clearMemoryCache()
                                val t0 = SystemClock.elapsedRealtimeNanos()
                                val base = kanaEngine.getBaseCandidates(stepReading)
                                val t1 = SystemClock.elapsedRealtimeNanos()
                                totalBaseMs += (t1 - t0) / 1_000_000.0

                                if (engineType != RerankEngineType.NONE) {
                                    val (_, used) = kanaEngine.rerankCandidatesWithStatus(sample.context, base)
                                    val t2 = SystemClock.elapsedRealtimeNanos()
                                    totalRerankMs += (t2 - t1) / 1_000_000.0
                                    if (used) slmAppliedCount++
                                }
                                totalConversions++
                                stepCount++
                            }
                        }
                    }
                    else -> {
                        // "miss": Every single iteration has a distinct context prefix (100% KV cache miss)
                        for (i in 0 until iters) {
                            val sample = SAMPLES[i % SAMPLES.size]
                            // Append iteration suffix if cycling to guarantee a context hash miss if needed,
                            // or cycle through 25 distinct contexts (which always misses the previous context!)
                            kanaEngine.clearMemoryCache()
                            val t0 = SystemClock.elapsedRealtimeNanos()
                            val base = kanaEngine.getBaseCandidates(sample.reading)
                            val t1 = SystemClock.elapsedRealtimeNanos()
                            totalBaseMs += (t1 - t0) / 1_000_000.0

                            if (engineType != RerankEngineType.NONE) {
                                val (_, used) = kanaEngine.rerankCandidatesWithStatus(sample.context, base)
                                val t2 = SystemClock.elapsedRealtimeNanos()
                                totalRerankMs += (t2 - t1) / 1_000_000.0
                                if (used) slmAppliedCount++
                            }
                            totalConversions++
                        }
                    }
                }

                val wallEndMs = SystemClock.elapsedRealtime()
                val cpuEndMs = Process.getElapsedCpuTime()
                val currentEndUa = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: 0
                val chargeEndUah = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) ?: 0

                val wallElapsedMs = wallEndMs - wallStartMs
                val cpuElapsedMs = cpuEndMs - cpuStartMs

                val json = JSONObject().apply {
                    put("engine", engineType.id)
                    put("workload", workload)
                    put("conversions", totalConversions)
                    put("slmApplied", slmAppliedCount)
                    put("wallTotalMs", wallElapsedMs)
                    put("cpuTotalMs", cpuElapsedMs)
                    put("wallPerConvMs", if (totalConversions > 0) wallElapsedMs.toDouble() / totalConversions else 0.0)
                    put("cpuPerConvMs", if (totalConversions > 0) cpuElapsedMs.toDouble() / totalConversions else 0.0)
                    put("basePerConvMs", if (totalConversions > 0) totalBaseMs / totalConversions else 0.0)
                    put("rerankPerConvMs", if (totalConversions > 0) totalRerankMs / totalConversions else 0.0)
                    put("currentStartUa", currentStartUa)
                    put("currentEndUa", currentEndUa)
                    put("chargeDeltaUah", chargeStartUah - chargeEndUah)
                }

                Log.i(TAG, "BENCH_RUN_DONE ${json.toString()}")
            }
        }
    }
}
