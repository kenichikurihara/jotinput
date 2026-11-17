package com.example.myasr.context.reranking.domain

import com.example.myasr.context.kanakanji.domain.ConversionCandidate
import com.example.myasr.shared.domain.Judgement
import com.example.myasr.shared.domain.JudgementResult
import com.example.myasr.shared.domain.dmn.DecisionTable
import com.example.myasr.shared.domain.dmn.DecisionTrace
import com.example.myasr.shared.domain.dmn.HitPolicy
import com.example.myasr.shared.domain.dmn.decisionTable

/**
 * BC-2 (Neural Language Modeling & Reranking) におけるリランキング実行可否・デバウンス・
 * タップ安全ウィンドウ保護（Policy ⑤）を司る純粋ドメイン DMN DecisionTable / Judgement 群。
 *
 * 従来 [com.example.myasr.VoiceInputMethodService] 内に直接記述されていた判定ロジックを
 * DMN (Decision Model and Notation) の決定表として宣言的に定義し、
 * 監査可能なトレース付きで JVM 単体テスト可能にする。
 */
object CandidateRerankJudgement {

    const val RAPID_TYPING_IKI_THRESHOLD_MS = 140L
    const val RAPID_TYPING_MAX_READING_LEN = 2
    const val RAPID_TYPING_DEBOUNCE_MS = 40L
    const val NORMAL_DEBOUNCE_MS = 4L
    const val TAP_SAFE_WINDOW_MS = 100L

    data class ExecutionInput(
        val isRerankEnabled: Boolean,
        val candidateCount: Int,
        val isSlmReady: Boolean,
        val interKeyIntervalMs: Long,
        val readingLength: Int
    )

    data class ExecutionDecision(
        val shouldRerank: Boolean,
        val debounceMs: Long,
        val reason: String
    )

    data class TapSafeOrderingInput(
        val baseCandidates: List<ConversionCandidate>,
        val rerankedCandidates: List<ConversionCandidate>,
        val initialRenderedTop1: String?,
        val reading: String,
        val elapsedFromFirstRenderMs: Long,
        val isUserTouchingStrip: Boolean
    )

    data class TapSafeOrderingDecision(
        val finalCandidates: List<ConversionCandidate>,
        val pinnedInitialTop1: Boolean,
        val reason: String
    )

    data class NextPhraseRerankInput(
        val isRerankEnabled: Boolean,
        val isSlmReady: Boolean,
        val textBeforeCursor: String
    )

    val executionDecisionTable: DecisionTable<ExecutionInput, ExecutionDecision> = decisionTable(
        name = "CandidateRerankExecution",
        hitPolicy = HitPolicy.FIRST
    ) {
        rule(id = "RERANK_EXEC_R1", description = "Reject when SLM rerank is disabled") {
            `when` { !it.isRerankEnabled }
            thenValue(ExecutionDecision(shouldRerank = false, debounceMs = 0L, reason = "slm_rerank_disabled"))
        }
        rule(id = "RERANK_EXEC_R2", description = "Reject when SLM engine is not ready") {
            `when` { !it.isSlmReady }
            thenValue(ExecutionDecision(shouldRerank = false, debounceMs = 0L, reason = "slm_not_ready"))
        }
        rule(id = "RERANK_EXEC_R3", description = "Reject when candidate count <= 1") {
            `when` { it.candidateCount <= 1 }
            thenValue(ExecutionDecision(shouldRerank = false, debounceMs = 0L, reason = "insufficient_candidates"))
        }
        rule(id = "RERANK_EXEC_R4", description = "Debounce 40ms during rapid short-reading typing") {
            `when` {
                it.interKeyIntervalMs < RAPID_TYPING_IKI_THRESHOLD_MS &&
                    it.readingLength <= RAPID_TYPING_MAX_READING_LEN
            }
            thenValue(
                ExecutionDecision(
                    shouldRerank = true,
                    debounceMs = RAPID_TYPING_DEBOUNCE_MS,
                    reason = "rapid_short_typing_debounce"
                )
            )
        }
        default(id = "RERANK_EXEC_DEFAULT", description = "Normal 4ms rerank execution") {
            ExecutionDecision(shouldRerank = true, debounceMs = NORMAL_DEBOUNCE_MS, reason = "normal_rerank")
        }
    }

    val tapSafeOrderingDecisionTable: DecisionTable<TapSafeOrderingInput, TapSafeOrderingDecision> = decisionTable(
        name = "TapSafeCandidateOrdering",
        hitPolicy = HitPolicy.FIRST
    ) {
        rule(id = "TAP_SAFE_R1", description = "Skip pinning when Top-1 is unchanged or absent") {
            `when` { input ->
                val currentTop1 = input.initialRenderedTop1
                val newTop1 = input.rerankedCandidates.firstOrNull()?.surface
                currentTop1 == null || newTop1 == currentTop1
            }
            then { input ->
                TapSafeOrderingDecision(
                    finalCandidates = input.rerankedCandidates,
                    pinnedInitialTop1 = false,
                    reason = "top1_unchanged_or_absent"
                )
            }
        }
        rule(id = "TAP_SAFE_R2", description = "Allow Top-1 promotion within 100ms tap-safe window when not touching") {
            `when` { input ->
                !(input.elapsedFromFirstRenderMs > TAP_SAFE_WINDOW_MS || input.isUserTouchingStrip)
            }
            then { input ->
                TapSafeOrderingDecision(
                    finalCandidates = input.rerankedCandidates,
                    pinnedInitialTop1 = false,
                    reason = "within_tap_safe_window"
                )
            }
        }
        rule(id = "TAP_SAFE_R3", description = "Allow full-span candidate to promote over partial prefix") {
            `when` { input ->
                val currentTop1 = input.initialRenderedTop1
                val preservedCand = input.baseCandidates.find { it.surface == currentTop1 }
                val isPreservedPartial = preservedCand != null &&
                    (preservedCand.source == "viterbi_prefix" ||
                        (preservedCand.reading.isNotEmpty() && preservedCand.reading.length < input.reading.length))
                val isNewTopFull = input.rerankedCandidates.firstOrNull()?.let {
                    it.source != "viterbi_prefix" && !it.isPrediction && it.reading.length == input.reading.length
                } ?: false
                isPreservedPartial && isNewTopFull
            }
            then { input ->
                TapSafeOrderingDecision(
                    finalCandidates = input.rerankedCandidates,
                    pinnedInitialTop1 = false,
                    reason = "promote_full_span_over_partial"
                )
            }
        }
        rule(id = "TAP_SAFE_R4", description = "Allow high-utility prediction to promote over short prefix when not touching") {
            `when` { input ->
                val newTop = input.rerankedCandidates.firstOrNull()
                val isHighUtilityPredictionPromotion = newTop != null &&
                    newTop.isPrediction &&
                    (input.reading.length in 2..4 || (input.reading.length == 1 && (newTop.priority >= 3.5f || newTop.source == "context_collocation"))) &&
                    newTop.reading.length > input.reading.length &&
                    (newTop.priority >= 3.5f || newTop.historyCount >= 2 || newTop.source == "history_bigram" || newTop.source == "context_collocation")
                !input.isUserTouchingStrip && isHighUtilityPredictionPromotion
            }
            then { input ->
                TapSafeOrderingDecision(
                    finalCandidates = input.rerankedCandidates,
                    pinnedInitialTop1 = false,
                    reason = "promote_high_utility_prediction"
                )
            }
        }
        default(id = "TAP_SAFE_DEFAULT", description = "Pin initial Top-1 for tap safety") { input ->
            val currentTop1 = input.initialRenderedTop1!!
            val pinnedTop = input.rerankedCandidates.find { it.surface == currentTop1 }
                ?: ConversionCandidate(surface = currentTop1, reading = input.reading, isPrediction = false)
            val otherCands = input.rerankedCandidates.filter { it.surface != currentTop1 }
            TapSafeOrderingDecision(
                finalCandidates = listOf(pinnedTop) + otherCands,
                pinnedInitialTop1 = true,
                reason = "pinned_initial_top1_for_tap_safety"
            )
        }
    }

    val nextPhraseRerankDecisionTable: DecisionTable<NextPhraseRerankInput, JudgementResult<Boolean>> = decisionTable(
        name = "NextPhraseSlmRerank",
        hitPolicy = HitPolicy.FIRST
    ) {
        rule(id = "NEXT_PHRASE_R1", description = "Reject when SLM rerank is disabled") {
            `when` { !it.isRerankEnabled }
            thenValue(JudgementResult(false, "slm_rerank_disabled"))
        }
        rule(id = "NEXT_PHRASE_R2", description = "Reject when SLM engine is not ready") {
            `when` { !it.isSlmReady }
            thenValue(JudgementResult(false, "slm_not_ready"))
        }
        rule(id = "NEXT_PHRASE_R3", description = "Reject when text before cursor is blank") {
            `when` { it.textBeforeCursor.isBlank() }
            thenValue(JudgementResult(false, "empty_context_before_cursor"))
        }
        defaultValue(
            id = "NEXT_PHRASE_DEFAULT",
            description = "Eligible for next-phrase SLM reranking",
            value = JudgementResult(true, "eligible_for_next_phrase_slm")
        )
    }

    val executionJudgement: Judgement<ExecutionInput, ExecutionDecision> = executionDecisionTable
    val tapSafeOrderingJudgement: Judgement<TapSafeOrderingInput, TapSafeOrderingDecision> = tapSafeOrderingDecisionTable
    val nextPhraseRerankJudgement: Judgement<NextPhraseRerankInput, JudgementResult<Boolean>> = nextPhraseRerankDecisionTable

    fun judgeExecution(input: ExecutionInput): ExecutionDecision = executionJudgement.judge(input)

    fun judgeExecutionWithTrace(input: ExecutionInput): DecisionTrace<ExecutionDecision> =
        executionDecisionTable.evaluateWithTrace(input)

    fun judgeTapSafeOrdering(input: TapSafeOrderingInput): TapSafeOrderingDecision =
        tapSafeOrderingJudgement.judge(input)

    fun judgeTapSafeOrderingWithTrace(input: TapSafeOrderingInput): DecisionTrace<TapSafeOrderingDecision> =
        tapSafeOrderingDecisionTable.evaluateWithTrace(input)

    fun judgeNextPhraseRerank(input: NextPhraseRerankInput): JudgementResult<Boolean> =
        nextPhraseRerankJudgement.judge(input)

    fun judgeNextPhraseRerankWithTrace(input: NextPhraseRerankInput): DecisionTrace<JudgementResult<Boolean>> =
        nextPhraseRerankDecisionTable.evaluateWithTrace(input)
}
