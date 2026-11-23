package com.example.myasr.context.reranking.domain

import com.example.myasr.context.kanakanji.domain.ConversionCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidateRerankJudgementTest {

    @Test
    fun `executionJudgement rejects when disabled, not ready, or single candidate`() {
        val disabled = CandidateRerankJudgement.judgeExecution(
            CandidateRerankJudgement.ExecutionInput(
                isRerankEnabled = false,
                candidateCount = 5,
                isSlmReady = true,
                interKeyIntervalMs = 200L,
                readingLength = 3
            )
        )
        assertFalse(disabled.shouldRerank)
        assertEquals("slm_rerank_disabled", disabled.reason)

        val notReady = CandidateRerankJudgement.judgeExecution(
            CandidateRerankJudgement.ExecutionInput(
                isRerankEnabled = true,
                candidateCount = 5,
                isSlmReady = false,
                interKeyIntervalMs = 200L,
                readingLength = 3
            )
        )
        assertFalse(notReady.shouldRerank)
        assertEquals("slm_not_ready", notReady.reason)

        val single = CandidateRerankJudgement.judgeExecution(
            CandidateRerankJudgement.ExecutionInput(
                isRerankEnabled = true,
                candidateCount = 1,
                isSlmReady = true,
                interKeyIntervalMs = 200L,
                readingLength = 3
            )
        )
        assertFalse(single.shouldRerank)
        assertEquals("insufficient_candidates", single.reason)
    }

    @Test
    fun `executionJudgement applies 40ms debounce for rapid short typing and 4ms otherwise`() {
        val rapidShort = CandidateRerankJudgement.judgeExecution(
            CandidateRerankJudgement.ExecutionInput(
                isRerankEnabled = true,
                candidateCount = 4,
                isSlmReady = true,
                interKeyIntervalMs = 80L,
                readingLength = 2
            )
        )
        assertTrue(rapidShort.shouldRerank)
        assertEquals(40L, rapidShort.debounceMs)

        val normal = CandidateRerankJudgement.judgeExecution(
            CandidateRerankJudgement.ExecutionInput(
                isRerankEnabled = true,
                candidateCount = 4,
                isSlmReady = true,
                interKeyIntervalMs = 200L,
                readingLength = 5
            )
        )
        assertTrue(normal.shouldRerank)
        assertEquals(4L, normal.debounceMs)
    }

    @Test
    fun `tapSafeOrdering pins initial Top-1 after 100ms or while user is touching strip`() {
        val base = listOf(
            ConversionCandidate(surface = "今日", reading = "きょう", source = "dict"),
            ConversionCandidate(surface = "京", reading = "きょう", source = "dict")
        )
        val reranked = listOf(
            ConversionCandidate(surface = "京", reading = "きょう", source = "slm"),
            ConversionCandidate(surface = "今日", reading = "きょう", source = "dict")
        )

        val withinWindow = CandidateRerankJudgement.judgeTapSafeOrdering(
            CandidateRerankJudgement.TapSafeOrderingInput(
                baseCandidates = base,
                rerankedCandidates = reranked,
                initialRenderedTop1 = "今日",
                reading = "きょう",
                elapsedFromFirstRenderMs = 50L,
                isUserTouchingStrip = false
            )
        )
        assertFalse(withinWindow.pinnedInitialTop1)
        assertEquals("京", withinWindow.finalCandidates.first().surface)

        val afterWindow = CandidateRerankJudgement.judgeTapSafeOrdering(
            CandidateRerankJudgement.TapSafeOrderingInput(
                baseCandidates = base,
                rerankedCandidates = reranked,
                initialRenderedTop1 = "今日",
                reading = "きょう",
                elapsedFromFirstRenderMs = 150L,
                isUserTouchingStrip = false
            )
        )
        assertTrue(afterWindow.pinnedInitialTop1)
        assertEquals("今日", afterWindow.finalCandidates.first().surface)
        assertEquals("京", afterWindow.finalCandidates[1].surface)
    }

    @Test
    fun `tapSafeOrdering allows full-span candidate to promote over partial prefix`() {
        val base = listOf(
            ConversionCandidate(surface = "きょ", reading = "きょ", source = "viterbi_prefix")
        )
        val reranked = listOf(
            ConversionCandidate(surface = "今日", reading = "きょう", source = "dict")
        )

        val decision = CandidateRerankJudgement.judgeTapSafeOrdering(
            CandidateRerankJudgement.TapSafeOrderingInput(
                baseCandidates = base,
                rerankedCandidates = reranked,
                initialRenderedTop1 = "きょ",
                reading = "きょう",
                elapsedFromFirstRenderMs = 200L,
                isUserTouchingStrip = true
            )
        )
        assertFalse(decision.pinnedInitialTop1)
        assertEquals("promote_full_span_over_partial", decision.reason)
        assertEquals("今日", decision.finalCandidates.first().surface)
    }

    @Test
    fun `nextPhraseRerankJudgement requires enabled, ready, and non-blank context`() {
        assertFalse(
            CandidateRerankJudgement.judgeNextPhraseRerank(
                CandidateRerankJudgement.NextPhraseRerankInput(true, true, "   ")
            ).decision
        )
        assertTrue(
            CandidateRerankJudgement.judgeNextPhraseRerank(
                CandidateRerankJudgement.NextPhraseRerankInput(true, true, "今日は")
            ).decision
        )
    }

    @Test
    fun `DMN decision tables expose auditable rule IDs and hit policies`() {
        val rapidTrace = CandidateRerankJudgement.judgeExecutionWithTrace(
            CandidateRerankJudgement.ExecutionInput(
                isRerankEnabled = true,
                candidateCount = 3,
                isSlmReady = true,
                interKeyIntervalMs = 50L,
                readingLength = 2
            )
        )
        assertEquals("CandidateRerankExecution", rapidTrace.decisionName)
        assertEquals("RERANK_EXEC_R4", rapidTrace.selectedRuleId)
        assertFalse(rapidTrace.usedDefaultFallback)

        val normalTrace = CandidateRerankJudgement.judgeExecutionWithTrace(
            CandidateRerankJudgement.ExecutionInput(
                isRerankEnabled = true,
                candidateCount = 3,
                isSlmReady = true,
                interKeyIntervalMs = 300L,
                readingLength = 5
            )
        )
        assertEquals("RERANK_EXEC_DEFAULT", normalTrace.selectedRuleId)
        assertTrue(normalTrace.usedDefaultFallback)
    }

    @Test
    fun `tapSafeOrdering allows high-utility prediction promotion after 100ms when not touching but pins when touching`() {
        val base = listOf(
            ConversionCandidate(surface = "詐欺", reading = "さぎ", source = "dict")
        )
        val reranked = listOf(
            ConversionCandidate(
                surface = "作業を進めます",
                reading = "さぎょうをすすめます",
                isPrediction = true,
                source = "phrase_chunk",
                priority = 3.5f
            ),
            ConversionCandidate(surface = "詐欺", reading = "さぎ", source = "dict")
        )

        // Not touching strip after 150ms -> TAP_SAFE_R4 allows promotion
        val notTouchingTrace = CandidateRerankJudgement.judgeTapSafeOrderingWithTrace(
            CandidateRerankJudgement.TapSafeOrderingInput(
                baseCandidates = base,
                rerankedCandidates = reranked,
                initialRenderedTop1 = "詐欺",
                reading = "さぎ",
                elapsedFromFirstRenderMs = 150L,
                isUserTouchingStrip = false
            )
        )
        assertEquals("TAP_SAFE_R4", notTouchingTrace.selectedRuleId)
        assertFalse(notTouchingTrace.result.pinnedInitialTop1)
        assertEquals("作業を進めます", notTouchingTrace.result.finalCandidates.first().surface)

        // Touching strip after 150ms -> TAP_SAFE_DEFAULT pins initial Top-1 for tap safety
        val touchingTrace = CandidateRerankJudgement.judgeTapSafeOrderingWithTrace(
            CandidateRerankJudgement.TapSafeOrderingInput(
                baseCandidates = base,
                rerankedCandidates = reranked,
                initialRenderedTop1 = "詐欺",
                reading = "さぎ",
                elapsedFromFirstRenderMs = 150L,
                isUserTouchingStrip = true
            )
        )
        assertEquals("TAP_SAFE_DEFAULT", touchingTrace.selectedRuleId)
        assertTrue(touchingTrace.result.pinnedInitialTop1)
        assertEquals("詐欺", touchingTrace.result.finalCandidates.first().surface)
    }

    @Test
    fun `tapSafeOrdering allows 1-mora context collocation prediction promotion when not touching`() {
        val base = listOf(
            ConversionCandidate(surface = "そ", reading = "そ", source = "dict")
        )
        val reranked = listOf(
            ConversionCandidate(
                surface = "送信",
                reading = "そうしん",
                isPrediction = true,
                source = "context_collocation",
                priority = 5.0f
            ),
            ConversionCandidate(surface = "そ", reading = "そ", source = "dict")
        )

        val notTouchingTrace = CandidateRerankJudgement.judgeTapSafeOrderingWithTrace(
            CandidateRerankJudgement.TapSafeOrderingInput(
                baseCandidates = base,
                rerankedCandidates = reranked,
                initialRenderedTop1 = "そ",
                reading = "そ",
                elapsedFromFirstRenderMs = 120L,
                isUserTouchingStrip = false
            )
        )
        assertEquals("TAP_SAFE_R4", notTouchingTrace.selectedRuleId)
        assertFalse(notTouchingTrace.result.pinnedInitialTop1)
        assertEquals("送信", notTouchingTrace.result.finalCandidates.first().surface)
    }

    @Test
    fun `speculativeWarmDecisionTable guards disabled, not ready, and active composing states`() {
        val disabled = CandidateRerankJudgement.judgeSpeculativeWarmWithTrace(
            CandidateRerankJudgement.SpeculativeWarmInput(
                isRerankEnabled = false,
                isSlmReady = true,
                isAlreadyComposing = false,
                trigger = CandidateRerankJudgement.WarmTrigger.TEXT_COMMITTED
            )
        )
        assertEquals("WARM_R1", disabled.selectedRuleId)
        assertFalse(disabled.result.shouldWarm)

        val touchWhileComposing = CandidateRerankJudgement.judgeSpeculativeWarmWithTrace(
            CandidateRerankJudgement.SpeculativeWarmInput(
                isRerankEnabled = true,
                isSlmReady = true,
                isAlreadyComposing = true,
                trigger = CandidateRerankJudgement.WarmTrigger.KEY_TOUCH_DOWN
            )
        )
        assertEquals("WARM_R4", touchWhileComposing.selectedRuleId)
        assertFalse(touchWhileComposing.result.shouldWarm)

        val touchBeforeFirstChar = CandidateRerankJudgement.judgeSpeculativeWarmWithTrace(
            CandidateRerankJudgement.SpeculativeWarmInput(
                isRerankEnabled = true,
                isSlmReady = true,
                isAlreadyComposing = false,
                trigger = CandidateRerankJudgement.WarmTrigger.KEY_TOUCH_DOWN
            )
        )
        assertEquals("WARM_DEFAULT", touchBeforeFirstChar.selectedRuleId)
        assertTrue(touchBeforeFirstChar.result.shouldWarm)

        val composingTop1Stable = CandidateRerankJudgement.judgeSpeculativeWarmWithTrace(
            CandidateRerankJudgement.SpeculativeWarmInput(
                isRerankEnabled = true,
                isSlmReady = true,
                isAlreadyComposing = true,
                trigger = CandidateRerankJudgement.WarmTrigger.COMPOSING_TOP1_STABLE
            )
        )
        assertEquals("WARM_DEFAULT", composingTop1Stable.selectedRuleId)
        assertTrue(composingTop1Stable.result.shouldWarm)
    }

    @Test
    fun `zeroQueryHorizonDecisionTable and applyZeroQueryHorizonPairing enforce dual-horizon Slot 2`() {
        val traceR3 = CandidateRerankJudgement.judgeZeroQueryHorizonWithTrace(
            CandidateRerankJudgement.ZeroQueryHorizonInput(
                candidateCount = 4,
                top1IsHistoryBigram = false,
                top1IsCompoundChunk = true,
                hasSameStemShortCandidate = true,
                hasPivotParticleCandidate = true
            )
        )
        assertEquals("ZQ_HORIZON_R3", traceR3.selectedRuleId)
        assertEquals(CandidateRerankJudgement.Slot2PairingStrategy.PAIR_SAME_STEM_SHORT_CANDIDATE, traceR3.result.slot2Strategy)

        val traceR4 = CandidateRerankJudgement.judgeZeroQueryHorizonWithTrace(
            CandidateRerankJudgement.ZeroQueryHorizonInput(
                candidateCount = 4,
                top1IsHistoryBigram = false,
                top1IsCompoundChunk = true,
                hasSameStemShortCandidate = false,
                hasPivotParticleCandidate = true
            )
        )
        assertEquals("ZQ_HORIZON_R4", traceR4.selectedRuleId)
        assertEquals(CandidateRerankJudgement.Slot2PairingStrategy.PAIR_TOP_PIVOT_PARTICLE, traceR4.result.slot2Strategy)

        val candidates = listOf(
            ConversionCandidate(surface = "お願いします", reading = "おねがいします", isPrediction = true, source = "canonical_collocation"),
            ConversionCandidate(surface = "お願いいたします", reading = "おねがいいたします", isPrediction = true, source = "canonical_collocation"),
            ConversionCandidate(surface = "お願い", reading = "おねがい", isPrediction = true, source = "canonical_collocation"),
            ConversionCandidate(surface = "を", reading = "を", isPrediction = true, source = "particle")
        )
        val paired = CandidateRerankJudgement.applyZeroQueryHorizonPairing(candidates)
        assertEquals("お願いします", paired[0].surface)
        assertEquals("お願い", paired[1].surface)
    }

    @Test
    fun `zero-query next phrase predictions follow viewport-aware anti-jitter contract`() {
        val fastPredictions = listOf(
            ConversionCandidate(surface = "を", reading = "を", isPrediction = true, source = "particle"),
            ConversionCandidate(surface = "が", reading = "が", isPrediction = true, source = "particle"),
            ConversionCandidate(surface = "に", reading = "に", isPrediction = true, source = "particle"),
            ConversionCandidate(surface = "は", reading = "は", isPrediction = true, source = "particle"),
            ConversionCandidate(surface = "から", reading = "から", isPrediction = true, source = "particle")
        )
        val slmRerankedPredictions = listOf(
            ConversionCandidate(surface = "確認します", reading = "かくにんします", isPrediction = true, source = "slm"),
            ConversionCandidate(surface = "に", reading = "に", isPrediction = true, source = "particle"),
            ConversionCandidate(surface = "について", reading = "について", isPrediction = true, source = "slm"),
            ConversionCandidate(surface = "を", reading = "を", isPrediction = true, source = "particle"),
            ConversionCandidate(surface = "が", reading = "が", isPrediction = true, source = "particle"),
            ConversionCandidate(surface = "は", reading = "は", isPrediction = true, source = "particle"),
            ConversionCandidate(surface = "から", reading = "から", isPrediction = true, source = "particle")
        )

        // 1. Within 50ms micro-wait hold -> single-pass direct SLM
        val withinHold = CandidateRerankJudgement.judgeViewportAwareOrdering(
            CandidateRerankJudgement.ViewportAwareOrderingInput(
                baseCandidates = fastPredictions,
                rerankedCandidates = slmRerankedPredictions,
                isPhase1AlreadyRendered = false,
                hasReservedHeroSlot = false,
                frozenVisibleCount = 0,
                isUserTouchingStrip = false
            )
        )
        assertEquals(CandidateRerankJudgement.UiRenderStrategy.DIRECT_SINGLE_PASS_SLM, withinHold.strategy)
        assertEquals("確認します", withinHold.finalCandidates.first().surface)

        // 2. After 50ms Phase 1 render (3 visible slots frozen) -> Hero gets "確認します", Zone B frozen ["を", "が", "に"], Zone C reranked ["について", "は", "から"]
        val afterHold = CandidateRerankJudgement.judgeViewportAwareOrdering(
            CandidateRerankJudgement.ViewportAwareOrderingInput(
                baseCandidates = fastPredictions,
                rerankedCandidates = slmRerankedPredictions,
                isPhase1AlreadyRendered = true,
                hasReservedHeroSlot = true,
                frozenVisibleCount = 3,
                isUserTouchingStrip = false
            )
        )
        assertEquals(
            CandidateRerankJudgement.UiRenderStrategy.PHASE2_POPULATE_HERO_AND_RERANK_OFFSCREEN_TAIL,
            afterHold.strategy
        )
        assertEquals("確認します", afterHold.heroSlotCandidate?.surface)
        assertEquals(listOf("を", "が", "に"), afterHold.frozenZoneBCandidates.map { it.surface })
        assertEquals(listOf("について", "は", "から"), afterHold.offScreenZoneCCandidates.map { it.surface })

        // 3. While user is touching/scrolling strip -> defer update
        val whileTouching = CandidateRerankJudgement.judgeViewportAwareOrdering(
            CandidateRerankJudgement.ViewportAwareOrderingInput(
                baseCandidates = fastPredictions,
                rerankedCandidates = slmRerankedPredictions,
                isPhase1AlreadyRendered = true,
                hasReservedHeroSlot = true,
                frozenVisibleCount = 3,
                isUserTouchingStrip = true
            )
        )
        assertEquals(CandidateRerankJudgement.UiRenderStrategy.DEFER_WHILE_USER_TOUCHING, whileTouching.strategy)
    }

    @Test
    fun `slmInvocationGateDecisionTable covers all rules and protects homophones, user dict, and typos`() {
        // R1: disabled or not ready
        val r1 = CandidateRerankJudgement.judgeSlmInvocationGateWithTrace(
            CandidateRerankJudgement.SlmInvocationGateInput(
                isRerankEnabled = false,
                isSlmReady = true,
                effectiveCandidateCount = 4,
                userDictCandidateCount = 0,
                top1IsUserDict = false,
                hasTypoObservation = false,
                top4KanjiOrKatakanaCount = 3,
                top2CostMargin = 200,
                readingLength = 3,
                isExplicitConversion = true
            )
        )
        assertEquals("SLM_GATE_R1", r1.selectedRuleId)
        assertFalse(r1.result.shouldInvokeSlm)

        // R2: single effective candidate (trailing echo hiragana excluded)
        val extractedSingle = CandidateRerankJudgement.extractSlmInvocationGateInput(
            candidates = listOf(
                ConversionCandidate(surface = "ある", reading = "ある", dictCost = 3000),
                ConversionCandidate(surface = "有る", reading = "ある", dictCost = 3500)
            ),
            reading = "ある",
            isExplicitConversion = true,
            isRerankEnabled = true,
            isSlmReady = true
        )
        val r2 = CandidateRerankJudgement.judgeSlmInvocationGateWithTrace(extractedSingle)
        assertEquals("SLM_GATE_R2", r2.selectedRuleId)
        assertFalse(r2.result.shouldInvokeSlm)

        // R3: uncontested user dictionary Top-1 without typo observation
        val extractedUserTop1 = CandidateRerankJudgement.extractSlmInvocationGateInput(
            candidates = listOf(
                ConversionCandidate(surface = "MyCustomTerm", reading = "まいかすたむ", source = "user", priority = 3.0f, dictCost = 10),
                ConversionCandidate(surface = "舞カスタム", reading = "まいかすたむ", dictCost = 4500),
                ConversionCandidate(surface = "毎日カスタム", reading = "まいかすたむ", dictCost = 5200)
            ),
            reading = "まいかすたむ",
            isExplicitConversion = true,
            isRerankEnabled = true,
            isSlmReady = true
        )
        val r3 = CandidateRerankJudgement.judgeSlmInvocationGateWithTrace(extractedUserTop1)
        assertEquals("SLM_GATE_R3", r3.selectedRuleId)
        assertFalse(r3.result.shouldInvokeSlm)

        // R4: competing user dictionary entries (must invoke SLM)
        val extractedCompetingUser = CandidateRerankJudgement.extractSlmInvocationGateInput(
            candidates = listOf(
                ConversionCandidate(surface = "田中太郎", reading = "たなか", source = "user", priority = 3.0f, dictCost = 10),
                ConversionCandidate(surface = "田中花子", reading = "たなか", source = "user", priority = 3.0f, dictCost = 10)
            ),
            reading = "たなか",
            isExplicitConversion = false,
            isRerankEnabled = true,
            isSlmReady = true
        )
        val r4 = CandidateRerankJudgement.judgeSlmInvocationGateWithTrace(extractedCompetingUser)
        assertEquals("SLM_GATE_R4", r4.selectedRuleId)
        assertTrue(r4.result.shouldInvokeSlm)

        // R5: typo observation candidates override margin/user skip
        val extractedTypo = CandidateRerankJudgement.extractSlmInvocationGateInput(
            candidates = listOf(
                ConversionCandidate(surface = "今日", reading = "きょう", dictCost = 2000),
                ConversionCandidate(surface = "教", reading = "きょう", dictCost = 2200, obsPenalty = 0.25f)
            ),
            reading = "きょう",
            isExplicitConversion = false,
            isRerankEnabled = true,
            isSlmReady = true
        )
        val r5 = CandidateRerankJudgement.judgeSlmInvocationGateWithTrace(extractedTypo)
        assertEquals("SLM_GATE_R5", r5.selectedRuleId)
        assertTrue(r5.result.shouldInvokeSlm)

        // R6: <= 1 Kanji/Katakana candidate in Top-4 (only one kanji + hiragana variations)
        val extractedNoKanjiRace = CandidateRerankJudgement.extractSlmInvocationGateInput(
            candidates = listOf(
                ConversionCandidate(surface = "これ", reading = "これ", dictCost = 2000),
                ConversionCandidate(surface = "此れ", reading = "これ", dictCost = 8500),
                ConversionCandidate(surface = "これー", reading = "これ", dictCost = 9000)
            ),
            reading = "これ",
            isExplicitConversion = true,
            isRerankEnabled = true,
            isSlmReady = true
        )
        val r6 = CandidateRerankJudgement.judgeSlmInvocationGateWithTrace(extractedNoKanjiRace)
        assertEquals("SLM_GATE_R6", r6.selectedRuleId)
        assertFalse(r6.result.shouldInvokeSlm)

        // R7: incremental composing with clear margin (>= 1500) skips SLM, while explicit conversion invokes SLM
        val homophonesClearMargin = listOf(
            ConversionCandidate(surface = "医師", reading = "いし", dictCost = 3000),
            ConversionCandidate(surface = "意志", reading = "いし", dictCost = 5000),
            ConversionCandidate(surface = "石", reading = "いし", dictCost = 5500)
        )
        val composingTrace = CandidateRerankJudgement.judgeSlmInvocationGateWithTrace(
            CandidateRerankJudgement.extractSlmInvocationGateInput(
                candidates = homophonesClearMargin,
                reading = "いし",
                isExplicitConversion = false,
                isRerankEnabled = true,
                isSlmReady = true
            )
        )
        assertEquals("SLM_GATE_R7", composingTrace.selectedRuleId)
        assertFalse(composingTrace.result.shouldInvokeSlm)

        val explicitTrace = CandidateRerankJudgement.judgeSlmInvocationGateWithTrace(
            CandidateRerankJudgement.extractSlmInvocationGateInput(
                candidates = homophonesClearMargin,
                reading = "いし",
                isExplicitConversion = true,
                isRerankEnabled = true,
                isSlmReady = true
            )
        )
        assertEquals("SLM_GATE_DEFAULT", explicitTrace.selectedRuleId)
        assertTrue(explicitTrace.result.shouldInvokeSlm)
    }
}
