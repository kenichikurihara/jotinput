package com.example.myasr.context.reranking.domain

import com.example.myasr.context.kanakanji.domain.ConversionCandidate
import com.example.myasr.shared.domain.Judgement
import com.example.myasr.shared.domain.JudgementResult

/**
 * BC-2 (Neural Language Modeling & Reranking) におけるリランキング実行可否・デバウンス・
 * タップ安全ウィンドウ保護（Policy ⑤）を司る純粋ドメイン Judgement 群。
 *
 * 従来 [com.example.myasr.VoiceInputMethodService] 内に直接記述されていた判定ロジックを
 * 抽出し、JVM 単体テストで決定論的に検証可能にする。
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

    val executionJudgement = Judgement<ExecutionInput, ExecutionDecision> { input ->
        when {
            !input.isRerankEnabled -> ExecutionDecision(
                shouldRerank = false,
                debounceMs = 0L,
                reason = "slm_rerank_disabled"
            )
            !input.isSlmReady -> ExecutionDecision(
                shouldRerank = false,
                debounceMs = 0L,
                reason = "slm_not_ready"
            )
            input.candidateCount <= 1 -> ExecutionDecision(
                shouldRerank = false,
                debounceMs = 0L,
                reason = "insufficient_candidates"
            )
            else -> {
                val isRapidShortTyping = input.interKeyIntervalMs < RAPID_TYPING_IKI_THRESHOLD_MS &&
                    input.readingLength <= RAPID_TYPING_MAX_READING_LEN
                val debounceMs = if (isRapidShortTyping) RAPID_TYPING_DEBOUNCE_MS else NORMAL_DEBOUNCE_MS
                val reason = if (isRapidShortTyping) "rapid_short_typing_debounce" else "normal_rerank"
                ExecutionDecision(
                    shouldRerank = true,
                    debounceMs = debounceMs,
                    reason = reason
                )
            }
        }
    }

    val tapSafeOrderingJudgement = Judgement<TapSafeOrderingInput, TapSafeOrderingDecision> { input ->
        val currentTop1 = input.initialRenderedTop1
        val newTop1 = input.rerankedCandidates.firstOrNull()?.surface

        if (currentTop1 == null || newTop1 == currentTop1) {
            return@Judgement TapSafeOrderingDecision(
                finalCandidates = input.rerankedCandidates,
                pinnedInitialTop1 = false,
                reason = "top1_unchanged_or_absent"
            )
        }

        val isWindowOrTouchActive = input.elapsedFromFirstRenderMs > TAP_SAFE_WINDOW_MS || input.isUserTouchingStrip
        if (!isWindowOrTouchActive) {
            return@Judgement TapSafeOrderingDecision(
                finalCandidates = input.rerankedCandidates,
                pinnedInitialTop1 = false,
                reason = "within_tap_safe_window"
            )
        }

        val preservedCand = input.baseCandidates.find { it.surface == currentTop1 }
        val isPreservedPartial = preservedCand != null &&
            (preservedCand.source == "viterbi_prefix" ||
                (preservedCand.reading.isNotEmpty() && preservedCand.reading.length < input.reading.length))
        val isNewTopFull = input.rerankedCandidates.firstOrNull()?.let {
            it.source != "viterbi_prefix" && !it.isPrediction && it.reading.length == input.reading.length
        } ?: false

        if (isPreservedPartial && isNewTopFull) {
            return@Judgement TapSafeOrderingDecision(
                finalCandidates = input.rerankedCandidates,
                pinnedInitialTop1 = false,
                reason = "promote_full_span_over_partial"
            )
        }

        val pinnedTop = input.rerankedCandidates.find { it.surface == currentTop1 }
            ?: ConversionCandidate(surface = currentTop1, reading = input.reading, isPrediction = false)
        val otherCands = input.rerankedCandidates.filter { it.surface != currentTop1 }
        TapSafeOrderingDecision(
            finalCandidates = listOf(pinnedTop) + otherCands,
            pinnedInitialTop1 = true,
            reason = "pinned_initial_top1_for_tap_safety"
        )
    }

    val nextPhraseRerankJudgement = Judgement<NextPhraseRerankInput, JudgementResult<Boolean>> { input ->
        when {
            !input.isRerankEnabled -> JudgementResult(false, "slm_rerank_disabled")
            !input.isSlmReady -> JudgementResult(false, "slm_not_ready")
            input.textBeforeCursor.isBlank() -> JudgementResult(false, "empty_context_before_cursor")
            else -> JudgementResult(true, "eligible_for_next_phrase_slm")
        }
    }

    fun judgeExecution(input: ExecutionInput): ExecutionDecision = executionJudgement.judge(input)

    fun judgeTapSafeOrdering(input: TapSafeOrderingInput): TapSafeOrderingDecision =
        tapSafeOrderingJudgement.judge(input)

    fun judgeNextPhraseRerank(input: NextPhraseRerankInput): JudgementResult<Boolean> =
        nextPhraseRerankJudgement.judge(input)
}
