package com.example.myasr.context.reranking.domain

import com.example.myasr.context.kanakanji.domain.ConversionCandidate

/**
 * BC-2 (Neural Language Modeling & Reranking) が外部コンテキストへ公開する
 * Open Host Service (OHS) インターフェース。
 */
interface CandidateRerankingService {
    fun isReady(): Boolean
    fun warmContext(contextPrefix: String): Boolean
    fun requestAbortScoring()
    fun rerankFast(
        contextPrefix: String,
        candidates: List<ConversionCandidate>,
        alpha: Float
    ): Pair<List<ConversionCandidate>, Boolean>
}
