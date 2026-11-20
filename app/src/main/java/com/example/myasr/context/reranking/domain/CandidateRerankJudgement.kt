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

    // Anti-Jitter UI (UI-Idea 1 + UI-Idea 2 + UI-Idea 8) constants
    const val MICRO_WAIT_HOLD_MS = 50L
    const val RAPID_TYPING_HOLD_MS = 85L
    const val DEFAULT_VISIBLE_SLOT_FALLBACK = 3

    enum class UiRenderStrategy {
        DIRECT_SINGLE_PASS_SLM,
        PHASE1_HERO_PLACEHOLDER_AND_FROZEN_VIEWPORT,
        PHASE2_POPULATE_HERO_AND_RERANK_OFFSCREEN_TAIL,
        DEFER_WHILE_USER_TOUCHING
    }

    data class ViewportAwareOrderingInput(
        val baseCandidates: List<ConversionCandidate>,
        val rerankedCandidates: List<ConversionCandidate>,
        val isPhase1AlreadyRendered: Boolean,
        val hasReservedHeroSlot: Boolean,
        val frozenVisibleCount: Int,
        val isUserTouchingStrip: Boolean
    )

    data class ViewportAwareOrderingDecision(
        val strategy: UiRenderStrategy,
        val heroSlotCandidate: ConversionCandidate?,
        val frozenZoneBCandidates: List<ConversionCandidate>,
        val offScreenZoneCCandidates: List<ConversionCandidate>,
        val finalCandidates: List<ConversionCandidate>,
        val spotlightIndexInViewport: Int?,
        val reason: String
    )

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

    enum class WarmTrigger {
        TEXT_COMMITTED,
        INPUT_VIEW_STARTED,
        CURSOR_MOVED_EXTERNAL,
        KEY_TOUCH_DOWN,
        VOICE_CHUNK_DECODING,
        COMPOSING_TOP1_STABLE
    }

    data class ZeroQueryHorizonInput(
        val candidateCount: Int,
        val top1IsHistoryBigram: Boolean,
        val top1IsCompoundChunk: Boolean,
        val hasSameStemShortCandidate: Boolean,
        val hasPivotParticleCandidate: Boolean
    )

    enum class Slot2PairingStrategy {
        KEEP_UTILITY_ORDER,
        PAIR_SAME_STEM_SHORT_CANDIDATE,
        PAIR_TOP_PIVOT_PARTICLE
    }

    data class ZeroQueryHorizonDecision(
        val slot2Strategy: Slot2PairingStrategy,
        val reason: String
    )

    data class SpeculativeWarmInput(
        val isRerankEnabled: Boolean,
        val isSlmReady: Boolean,
        val isAlreadyComposing: Boolean,
        val trigger: WarmTrigger
    )

    data class SpeculativeWarmDecision(
        val shouldWarm: Boolean,
        val reason: String
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

    val speculativeWarmDecisionTable: DecisionTable<SpeculativeWarmInput, SpeculativeWarmDecision> = decisionTable(
        name = "SpeculativeContextWarming",
        hitPolicy = HitPolicy.FIRST
    ) {
        rule(id = "WARM_R1", description = "Skip warming when SLM rerank is disabled") {
            `when` { !it.isRerankEnabled }
            thenValue(SpeculativeWarmDecision(shouldWarm = false, reason = "slm_rerank_disabled"))
        }
        rule(id = "WARM_R2", description = "Skip warming when SLM engine is not ready") {
            `when` { !it.isSlmReady }
            thenValue(SpeculativeWarmDecision(shouldWarm = false, reason = "slm_not_ready"))
        }
        rule(id = "WARM_R3", description = "Skip external cursor warm while actively composing") {
            `when` { it.trigger == WarmTrigger.CURSOR_MOVED_EXTERNAL && it.isAlreadyComposing }
            thenValue(SpeculativeWarmDecision(shouldWarm = false, reason = "composing_in_progress"))
        }
        rule(id = "WARM_R4", description = "Skip touch-down warm when already composing") {
            `when` { it.trigger == WarmTrigger.KEY_TOUCH_DOWN && it.isAlreadyComposing }
            thenValue(SpeculativeWarmDecision(shouldWarm = false, reason = "touch_down_already_composing"))
        }
        defaultValue(
            id = "WARM_DEFAULT",
            description = "Execute speculative background KV cache warming",
            value = SpeculativeWarmDecision(shouldWarm = true, reason = "execute_speculative_warm")
        )
    }

    val viewportAwareOrderingDecisionTable: DecisionTable<ViewportAwareOrderingInput, ViewportAwareOrderingDecision> = decisionTable(
        name = "ViewportAwareCandidateOrdering",
        hitPolicy = HitPolicy.FIRST
    ) {
        rule(id = "VP_SAFE_R1", description = "Defer DOM update while user is actively touching or scrolling strip after Phase 1") {
            `when` { it.isUserTouchingStrip && it.isPhase1AlreadyRendered }
            then { input ->
                ViewportAwareOrderingDecision(
                    strategy = UiRenderStrategy.DEFER_WHILE_USER_TOUCHING,
                    heroSlotCandidate = null,
                    frozenZoneBCandidates = emptyList(),
                    offScreenZoneCCandidates = emptyList(),
                    finalCandidates = input.baseCandidates,
                    spotlightIndexInViewport = null,
                    reason = "defer_while_user_touching"
                )
            }
        }
        rule(id = "VP_SAFE_R2", description = "UI-Idea 2: SLM completed within micro-wait hold before Phase 1 render") {
            `when` { !it.isPhase1AlreadyRendered }
            then { input ->
                val list = input.rerankedCandidates.ifEmpty { input.baseCandidates }
                ViewportAwareOrderingDecision(
                    strategy = UiRenderStrategy.DIRECT_SINGLE_PASS_SLM,
                    heroSlotCandidate = null,
                    frozenZoneBCandidates = emptyList(),
                    offScreenZoneCCandidates = list,
                    finalCandidates = list,
                    spotlightIndexInViewport = null,
                    reason = "single_pass_within_micro_wait"
                )
            }
        }
        rule(id = "VP_SAFE_R3", description = "UI-Idea 1 + 8: Populate reserved Hero Slot, freeze Zone B, and rerank off-screen Zone C") {
            `when` { it.isPhase1AlreadyRendered && it.hasReservedHeroSlot }
            then { input ->
                buildPhase2HeroAndOffscreenDecision(input, withHeroSlot = true, reason = "populate_hero_and_rerank_offscreen")
            }
        }
        default(id = "VP_SAFE_DEFAULT", description = "UI-Idea 8: Freeze Zone B and rerank off-screen Zone C without Hero Slot") { input ->
            buildPhase2HeroAndOffscreenDecision(input, withHeroSlot = false, reason = "freeze_viewport_and_rerank_offscreen")
        }
    }

    private fun buildPhase2HeroAndOffscreenDecision(
        input: ViewportAwareOrderingInput,
        withHeroSlot: Boolean,
        reason: String
    ): ViewportAwareOrderingDecision {
        val safeFrozenCount = input.frozenVisibleCount.coerceIn(0, input.baseCandidates.size)
        val frozenZoneB = input.baseCandidates.take(safeFrozenCount)
        val frozenSurfaces = frozenZoneB.mapTo(HashSet()) { it.surface.trim() }

        val reranked = input.rerankedCandidates.ifEmpty { input.baseCandidates }
        val slmTop1 = reranked.firstOrNull()
        val spotlightIdx = if (slmTop1 != null) {
            frozenZoneB.indexOfFirst { it.surface.trim() == slmTop1.surface.trim() }.takeIf { it >= 0 }
        } else {
            null
        }

        val heroCandidate = if (withHeroSlot) {
            if (slmTop1 != null && slmTop1.surface.trim() !in frozenSurfaces) {
                slmTop1
            } else {
                // SLM Top-1 is already visible in Zone B: spotlight it in-place and place best unseen SLM candidate in Hero
                reranked.firstOrNull { it.surface.trim() !in frozenSurfaces } ?: slmTop1
            }
        } else {
            null
        }

        val shownSurfaces = HashSet<String>(frozenSurfaces)
        if (heroCandidate != null) {
            shownSurfaces.add(heroCandidate.surface.trim())
        }

        val offScreenZoneC = reranked.filter { cand ->
            val key = cand.surface.trim()
            key.isNotEmpty() && shownSurfaces.add(key)
        }

        val finalList = buildList {
            if (heroCandidate != null) add(heroCandidate)
            addAll(frozenZoneB)
            addAll(offScreenZoneC)
        }

        return ViewportAwareOrderingDecision(
            strategy = UiRenderStrategy.PHASE2_POPULATE_HERO_AND_RERANK_OFFSCREEN_TAIL,
            heroSlotCandidate = heroCandidate,
            frozenZoneBCandidates = frozenZoneB,
            offScreenZoneCCandidates = offScreenZoneC,
            finalCandidates = finalList,
            spotlightIndexInViewport = spotlightIdx,
            reason = reason
        )
    }

    val zeroQueryHorizonDecisionTable: DecisionTable<ZeroQueryHorizonInput, ZeroQueryHorizonDecision> = decisionTable(
        name = "ZeroQueryHorizonPolicy",
        hitPolicy = HitPolicy.FIRST
    ) {
        rule(id = "ZQ_HORIZON_R1", description = "Keep order when candidate count <= 1") {
            `when` { it.candidateCount <= 1 }
            thenValue(ZeroQueryHorizonDecision(Slot2PairingStrategy.KEEP_UTILITY_ORDER, "insufficient_candidates"))
        }
        rule(id = "ZQ_HORIZON_R2", description = "Preserve short history bigram at Top-1") {
            `when` { it.top1IsHistoryBigram && !it.top1IsCompoundChunk }
            thenValue(ZeroQueryHorizonDecision(Slot2PairingStrategy.KEEP_UTILITY_ORDER, "preserve_short_history_top1"))
        }
        rule(id = "ZQ_HORIZON_R3", description = "Pair same-stem short candidate into Slot 2 when compound chunk is Top-1") {
            `when` { it.top1IsCompoundChunk && it.hasSameStemShortCandidate }
            thenValue(
                ZeroQueryHorizonDecision(
                    Slot2PairingStrategy.PAIR_SAME_STEM_SHORT_CANDIDATE,
                    "pair_short_stem_anchor_with_chunk_top1"
                )
            )
        }
        rule(id = "ZQ_HORIZON_R4", description = "Pair pivot particle into Slot 2 when compound chunk is Top-1") {
            `when` { it.top1IsCompoundChunk && it.hasPivotParticleCandidate }
            thenValue(
                ZeroQueryHorizonDecision(
                    Slot2PairingStrategy.PAIR_TOP_PIVOT_PARTICLE,
                    "pair_pivot_particle_with_chunk_top1"
                )
            )
        }
        defaultValue(
            id = "ZQ_HORIZON_DEFAULT",
            description = "Keep default utility order",
            value = ZeroQueryHorizonDecision(Slot2PairingStrategy.KEEP_UTILITY_ORDER, "default_utility_order")
        )
    }

    val executionJudgement: Judgement<ExecutionInput, ExecutionDecision> = executionDecisionTable
    val tapSafeOrderingJudgement: Judgement<TapSafeOrderingInput, TapSafeOrderingDecision> = tapSafeOrderingDecisionTable
    val nextPhraseRerankJudgement: Judgement<NextPhraseRerankInput, JudgementResult<Boolean>> = nextPhraseRerankDecisionTable
    val speculativeWarmJudgement: Judgement<SpeculativeWarmInput, SpeculativeWarmDecision> = speculativeWarmDecisionTable
    val viewportAwareOrderingJudgement: Judgement<ViewportAwareOrderingInput, ViewportAwareOrderingDecision> = viewportAwareOrderingDecisionTable
    val zeroQueryHorizonJudgement: Judgement<ZeroQueryHorizonInput, ZeroQueryHorizonDecision> = zeroQueryHorizonDecisionTable

    fun judgeExecution(input: ExecutionInput): ExecutionDecision = executionJudgement.judge(input)

    fun judgeExecutionWithTrace(input: ExecutionInput): DecisionTrace<ExecutionDecision> =
        executionDecisionTable.evaluateWithTrace(input)

    fun judgeTapSafeOrdering(input: TapSafeOrderingInput): TapSafeOrderingDecision =
        tapSafeOrderingJudgement.judge(input)

    fun judgeTapSafeOrderingWithTrace(input: TapSafeOrderingInput): DecisionTrace<TapSafeOrderingDecision> =
        tapSafeOrderingDecisionTable.evaluateWithTrace(input)

    fun judgeViewportAwareOrdering(input: ViewportAwareOrderingInput): ViewportAwareOrderingDecision =
        viewportAwareOrderingJudgement.judge(input)

    fun judgeViewportAwareOrderingWithTrace(input: ViewportAwareOrderingInput): DecisionTrace<ViewportAwareOrderingDecision> =
        viewportAwareOrderingDecisionTable.evaluateWithTrace(input)

    fun judgeNextPhraseRerank(input: NextPhraseRerankInput): JudgementResult<Boolean> =
        nextPhraseRerankJudgement.judge(input)

    fun judgeNextPhraseRerankWithTrace(input: NextPhraseRerankInput): DecisionTrace<JudgementResult<Boolean>> =
        nextPhraseRerankDecisionTable.evaluateWithTrace(input)

    fun judgeSpeculativeWarm(input: SpeculativeWarmInput): SpeculativeWarmDecision =
        speculativeWarmJudgement.judge(input)

    fun judgeSpeculativeWarmWithTrace(input: SpeculativeWarmInput): DecisionTrace<SpeculativeWarmDecision> =
        speculativeWarmDecisionTable.evaluateWithTrace(input)

    fun judgeZeroQueryHorizon(input: ZeroQueryHorizonInput): ZeroQueryHorizonDecision =
        zeroQueryHorizonJudgement.judge(input)

    fun judgeZeroQueryHorizonWithTrace(input: ZeroQueryHorizonInput): DecisionTrace<ZeroQueryHorizonDecision> =
        zeroQueryHorizonDecisionTable.evaluateWithTrace(input)

    /**
     * Applies ZeroQueryHorizonPolicy to fast-path zero-query candidates so Slot 2 preserves
     * a short stem anchor or pivot particle alongside a Top-1 compound chunk.
     */
    fun applyZeroQueryHorizonPairing(candidates: List<ConversionCandidate>): List<ConversionCandidate> {
        if (candidates.size <= 2) return candidates
        val top1 = candidates[0]
        val rest = candidates.drop(1)
        val sameStemShort = rest.firstOrNull {
            it.surface.length < top1.surface.length &&
                it.surface.length >= 2 &&
                top1.surface.startsWith(it.surface)
        }
        val pivotParticle = rest.firstOrNull { it.source == "particle" }
        val top1IsHistory = top1.source == "history_bigram" || top1.source == "history_skip_bigram"
        val top1IsChunk = top1.surface.length >= 4 || top1.reading.length >= 5 || sameStemShort != null

        val decision = judgeZeroQueryHorizon(
            ZeroQueryHorizonInput(
                candidateCount = candidates.size,
                top1IsHistoryBigram = top1IsHistory,
                top1IsCompoundChunk = top1IsChunk,
                hasSameStemShortCandidate = sameStemShort != null,
                hasPivotParticleCandidate = pivotParticle != null
            )
        )
        val slot2Anchor = when (decision.slot2Strategy) {
            Slot2PairingStrategy.PAIR_SAME_STEM_SHORT_CANDIDATE -> sameStemShort
            Slot2PairingStrategy.PAIR_TOP_PIVOT_PARTICLE -> pivotParticle
            Slot2PairingStrategy.KEEP_UTILITY_ORDER -> null
        } ?: return candidates

        if (candidates[1].surface == slot2Anchor.surface) return candidates
        val reordered = ArrayList<ConversionCandidate>(candidates.size)
        reordered.add(top1)
        reordered.add(slot2Anchor)
        for (c in rest) {
            if (c.surface != slot2Anchor.surface) reordered.add(c)
        }
        return reordered
    }
}
