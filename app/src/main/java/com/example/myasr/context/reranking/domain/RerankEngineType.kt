package com.example.myasr.context.reranking.domain

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
    LOCAL_SLM(
        id = "local_slm",
        displayName = "Qwen2.5-0.5B",
        modelName = "Qwen2.5-0.5B (469MB • 高速標準)",
        badgeLabel = "Qwen 0.5B",
        modelFileName = "qwen2.5-0.5b-instruct-q4_k_m.gguf"
    ),
    LOCAL_SLM_1_5B(
        id = "local_slm_1_5b",
        displayName = "Qwen2.5-1.5B",
        modelName = "Qwen2.5-1.5B (1.1GB • 高精度)",
        badgeLabel = "Qwen 1.5B",
        modelFileName = "qwen2.5-1.5b-instruct-q4_k_m.gguf"
    ),
    NONE(
        id = "none",
        displayName = "AI変換オフ (Mozc辞書・Viterbiのみ)",
        modelName = "AI変換無効 (Mozc辞書・Viterbiのみ)",
        badgeLabel = "",
        modelFileName = ""
    );

    companion object {
        fun fromId(id: String): RerankEngineType {
            return when (id) {
                "local_slm", "lm_first_0_5b" -> LOCAL_SLM
                "local_slm_1_5b", "lm_first_1_5b" -> LOCAL_SLM_1_5B
                "hybrid_rerank" -> LOCAL_SLM
                "none" -> NONE
                else -> LOCAL_SLM
            }
        }
    }
}
