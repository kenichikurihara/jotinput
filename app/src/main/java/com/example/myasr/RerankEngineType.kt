package com.example.myasr

import android.content.Context
import android.os.Build
import android.util.Log

/**
 * Supported on-device language model engine backends.
 */
enum class RerankEngineType(
    val id: String,
    val displayName: String,
    val modelName: String,
    val badgeLabel: String,
    val modelFileName: String = "qwen2.5-0.5b-instruct-q4_k_m.gguf"
) {
    AICORE_NANO_2(
        id = "aicore_nano_2",
        displayName = "Android AICore (Gemini Nano 2)",
        modelName = "Gemini Nano 2 (3.2B • Tensor G5 NPU)",
        badgeLabel = "n2"
    ),
    AICORE_NANO_1(
        id = "aicore_nano_1",
        displayName = "Android AICore (Gemini Nano 1)",
        modelName = "Gemini Nano 1 (1.8B • AICore NPU)",
        badgeLabel = "n1"
    ),
    LOCAL_SLM(
        id = "local_slm",
        displayName = "Local SLM (Qwen2.5-0.5B)",
        modelName = "Qwen2.5-0.5B-Instruct (469MB • llama.cpp CPU)",
        badgeLabel = "SLM",
        modelFileName = "qwen2.5-0.5b-instruct-q4_k_m.gguf"
    ),
    LOCAL_SLM_1_5B(
        id = "local_slm_1_5b",
        displayName = "Local SLM (Qwen2.5-1.5B)",
        modelName = "Qwen2.5-1.5B-Instruct (1.1GB • llama.cpp CPU)",
        badgeLabel = "1.5B",
        modelFileName = "qwen2.5-1.5b-instruct-q4_k_m.gguf"
    ),
    NONE(
        id = "none",
        displayName = "リランクなし (OFF)",
        modelName = "SLM 再ランキング無効 (辞書・Viterbiのみ)",
        badgeLabel = "",
        modelFileName = ""
    );

    companion object {
        fun fromId(id: String): RerankEngineType {
            return values().firstOrNull { it.id == id } ?: LOCAL_SLM
        }

        /**
         * Checks hardware & OS support for Android AICore on this device.
         */
        fun isAiCoreSupported(context: Context): Boolean {
            // Android 14+ (API 34+) and Pixel 8+ / Tensor hardware
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                return false
            }
            return try {
                val pm = context.packageManager
                pm.getPackageInfo("com.google.android.aicore", 0) != null
            } catch (e: Exception) {
                false
            }
        }

        /**
         * Checks if the device has Gemini Nano 2 (Tensor G5 / Pixel 10 Pro or newer).
         */
        fun isNano2Supported(context: Context): Boolean {
            if (!isAiCoreSupported(context)) return false
            val model = Build.MODEL.lowercase()
            val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL.lowercase() else ""
            return model.contains("pixel 10") || soc.contains("g5") || Build.VERSION.SDK_INT >= 36
        }
    }
}
