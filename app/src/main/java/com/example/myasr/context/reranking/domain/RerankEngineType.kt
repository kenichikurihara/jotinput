package com.example.myasr.context.reranking.domain

/**
 * Supported on-device language model engine backends (Qwen3.5 generation).
 */
enum class RerankEngineType(
    val id: String,
    val displayName: String,
    val modelName: String,
    val badgeLabel: String,
    val modelFileName: String = "qwen3.5-0.8b-instruct-q4_k_m.gguf",
    val downloadUrl: String = "",
    val estimatedSizeBytes: Long = 0L,
    val sizeLabel: String = ""
) {
    LOCAL_SLM_3_5_0_8B(
        id = "local_slm_3_5_0_8b",
        displayName = "Qwen3.5-0.8B",
        modelName = "Qwen3.5-0.8B (508MB • 高速標準・推奨)",
        badgeLabel = "Qwen3.5 0.8B",
        modelFileName = "qwen3.5-0.8b-instruct-q4_k_m.gguf",
        downloadUrl = "https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF/resolve/main/Qwen3.5-0.8B-Q4_K_M.gguf",
        estimatedSizeBytes = 532_517_120L,
        sizeLabel = "約508MB"
    ),
    LOCAL_SLM_3_5_2B(
        id = "local_slm_3_5_2b",
        displayName = "Qwen3.5-2B",
        modelName = "Qwen3.5-2B (1.19GB • 最高精度)",
        badgeLabel = "Qwen3.5 2B",
        modelFileName = "qwen3.5-2b-instruct-q4_k_m.gguf",
        downloadUrl = "https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/resolve/main/Qwen3.5-2B-Q4_K_M.gguf",
        estimatedSizeBytes = 1_280_835_840L,
        sizeLabel = "約1.19GB"
    ),
    NONE(
        id = "none",
        displayName = "AI変換オフ (Mozc辞書・Viterbiのみ)",
        modelName = "AI変換無効 (Mozc辞書・Viterbiのみ)",
        badgeLabel = "",
        modelFileName = "",
        downloadUrl = "",
        estimatedSizeBytes = 0L,
        sizeLabel = ""
    );

    companion object {
        val DEFAULT: RerankEngineType = LOCAL_SLM_3_5_0_8B

        fun fromId(id: String): RerankEngineType {
            return when (id.lowercase()) {
                "local_slm_3_5_0_8b", "qwen3.5_0.8b", "0.8b", "qwen3.5-0.8b",
                "local_slm", "lm_first_0_5b", "qwen2.5_0.5b", "0.5b", "hybrid_rerank" -> LOCAL_SLM_3_5_0_8B
                "local_slm_3_5_2b", "qwen3.5_2b", "2b", "qwen3.5-2b",
                "local_slm_1_5b", "lm_first_1_5b", "qwen2.5_1.5b", "1.5b" -> LOCAL_SLM_3_5_2B
                "none" -> NONE
                else -> LOCAL_SLM_3_5_0_8B
            }
        }
    }
}

