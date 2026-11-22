package com.example.myasr

import com.example.myasr.context.kanakanji.domain.ConnectionMatrix
import com.example.myasr.context.kanakanji.domain.NextPhrasePredictor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NextPhrasePredictorTest {

    private lateinit var predictor: NextPhrasePredictor

    @Before
    fun setUp() {
        // Instantiate without Android Context for pure JVM unit testing
        predictor = NextPhrasePredictor(null)
    }

    @Test
    fun testCanonicalCollocationPredictions() {
        val yoroshikuPreds = predictor.predict("よろしく")
        val surfaces = yoroshikuPreds.map { it.surface }
        assertTrue("Expected 'お願いします' in suggestions for 'よろしく'", surfaces.contains("お願いします"))
        assertTrue("Expected 'お願いいたします' in suggestions for 'よろしく'", surfaces.contains("お願いいたします"))

        val arigatouPreds = predictor.predict("ありがとう")
        val arigatouSurfaces = arigatouPreds.map { it.surface }
        assertTrue("Expected 'ございます' in suggestions for 'ありがとう'", arigatouSurfaces.contains("ございます"))

        val sagyouPreds = predictor.predict("作業")
        val sagyouSurfaces = sagyouPreds.map { it.surface }
        assertTrue("Expected '中' in suggestions for '作業'", sagyouSurfaces.contains("中"))
    }

    @Test
    fun testUserBigramLearningAndPriority() {
        // Initially, no custom user bigram for 'テスト'
        val initialPreds = predictor.predict("テスト").map { it.surface }
        assertFalse(initialPreds.contains("大成功"))

        // Record phrase transition
        predictor.recordTransition("テスト", "完了")
        predictor.recordTransition("テスト", "大成功")
        predictor.recordTransition("テスト", "大成功") // frequency count 2

        val afterPreds = predictor.predict("テスト")
        assertTrue(afterPreds.isNotEmpty())

        // Top prediction should be '大成功' due to higher frequency
        assertEquals("大成功", afterPreds.first().surface)
        assertEquals("history_bigram", afterPreds.first().source)
        assertTrue(afterPreds.first().isPrediction)

        // Second prediction should be '完了'
        assertEquals("完了", afterPreds[1].surface)
    }

    @Test
    fun testParticleContinuationForNouns() {
        val preds = predictor.predict("会議")
        val surfaces = preds.map { it.surface }

        // Particles should be suggested after a noun
        assertTrue("Should suggest 'は'", surfaces.contains("は"))
        assertTrue("Should suggest 'に'", surfaces.contains("に"))
        assertTrue("Should suggest 'で'", surfaces.contains("で"))
        assertTrue("Should suggest 'の'", surfaces.contains("の"))

        // But after a phrase already ending in a particle, particle duplication should be suppressed
        val predsAfterParticle = predictor.predict("会議で")
        val surfacesAfterParticle = predsAfterParticle.map { it.surface }
        assertFalse("Should not suggest 'で' after '会議で'", surfacesAfterParticle.contains("で"))
    }

    @Test
    fun testSentenceStartAndPunctuationHeuristics() {
        val emptyPreds = predictor.predict("")
        val surfacesEmpty = emptyPreds.map { it.surface }
        assertTrue("Should suggest 'お疲れ様です' at sentence start", surfacesEmpty.contains("お疲れ様です"))
        assertTrue("Should suggest 'お世話になっております' at sentence start", surfacesEmpty.contains("お世話になっております"))

        val periodPreds = predictor.predict("完了しました。")
        val surfacesPeriod = periodPreds.map { it.surface }
        assertTrue("Should suggest conjunctions like 'また、' after period", surfacesPeriod.contains("また、"))
        assertTrue("Should suggest 'つきましては、' after period", surfacesPeriod.contains("つきましては、"))
    }

    @Test
    fun testSubMillisecondExecutionLatency() {
        // Pre-warm
        predictor.predict("よろしく")

        val startTime = System.nanoTime()
        val iterations = 100
        for (i in 0 until iterations) {
            predictor.predict("お疲れ様")
        }
        val elapsedNano = System.nanoTime() - startTime
        val avgMicros = (elapsedNano / iterations) / 1000.0

        // Sub-millisecond budget (< 1000 microseconds)
        println("Average prediction latency: $avgMicros µs")
        assertTrue("Latency must be < 1000 µs (sub-millisecond), got ${avgMicros} µs", avgMicros < 1000.0)
    }

    @Test
    fun testJapanesePeriodSuggestionAndSuppression() {
        // 1. Sentence-ending predicate ("完了しました"): '。' is suggested at the very top
        val donePreds = predictor.predict("完了しました")
        assertTrue("Expected '。' in suggestions for '完了しました'", donePreds.any { it.surface == "。" })
        assertEquals("。", donePreds.first().surface)
        assertEquals("punctuation_suggestion", donePreds.first().source)

        // 2. Question ending ("大丈夫ですか"): both '。' and '？' are suggested before particles
        val qPreds = predictor.predict("大丈夫ですか").map { it.surface }
        assertTrue("Expected '。' for '大丈夫ですか'", qPreds.contains("。"))
        assertTrue("Expected '？' for '大丈夫ですか'", qPreds.contains("？"))
        assertTrue("Expected '。' before particles", qPreds.indexOf("。") < qPreds.indexOf("は"))

        // 3. Noun ("会議"): particles first, '。' still available as fallback
        val nounPreds = predictor.predict("会議").map { it.surface }
        assertTrue("Expected '。' in suggestions for '会議'", nounPreds.contains("。"))
        assertTrue("Expected particles before '。' for noun '会議'", nounPreds.indexOf("は") < nounPreds.indexOf("。"))

        // 4. Suppression cases: sentence start, already ending in '。', comma '、', or case particle '会議で'
        assertFalse("Should not suggest '。' at sentence start", predictor.predict("").any { it.surface == "。" })
        assertFalse("Should not suggest '。' after '。'", predictor.predict("完了しました。").any { it.surface == "。" })
        assertFalse("Should not suggest '。' after '、'", predictor.predict("また、").any { it.surface == "。" })
        assertFalse("Should not suggest '。' after case particle '会議で'", predictor.predict("会議で").any { it.surface == "。" })

        // 5. Opt-out flag
        assertFalse("Should respect suggestPeriod = false", predictor.predict("完了しました", suggestPeriod = false).any { it.surface == "。" })
    }

    @Test
    fun testNoDuplicatePeriodWhenHistoryContainsPeriodOrWhitespaceVariants() {
        predictor.recordTransition("完了しました", "。")
        predictor.mergeHistory(
            mapOf(
                "完了しました" to mapOf("。\n" to 3, " 。" to 2, "　。 " to 1)
            )
        )

        val preds = predictor.predict("完了しました")
        val periodCandidates = preds.filter { it.surface.trim { ch -> ch.isWhitespace() || ch == '\u3000' } == "。" }
        assertEquals("Period '。' should appear at most once", 1, periodCandidates.size)
        assertEquals("。", periodCandidates.first().surface)
    }

    @Test
    fun testParticlePrefixProjectionPromotesContextSpecificVerbOverGenericParticle() {
        // After "作業を", CANONICAL_COLLOCATIONS["作業"] has "を進めます" -> projects "進めます" at Rank 1
        val sagyoPreds = predictor.predict("を", textBeforeCursor = "作業を", suggestPeriod = false)
        assertTrue("Expected non-empty predictions for '作業を'", sagyoPreds.isNotEmpty())
        assertEquals("進めます", sagyoPreds.first().surface)

        // After "資料を", CANONICAL_COLLOCATIONS["資料"] has "を送信しました" -> projects "送信しました" at Rank 1
        val shiryoPreds = predictor.predict("を", textBeforeCursor = "資料を", suggestPeriod = false)
        assertTrue("Expected non-empty predictions for '資料を'", shiryoPreds.isNotEmpty())
        assertEquals("送信しました", shiryoPreds.first().surface)
    }

    @Test
    fun testSkipBigramHistoryLearningAcrossParticle() {
        predictor.recordTransition("書類", "を")
        predictor.recordTransition("を", "提出します", currentReading = "ていしゅつします")

        val preds = predictor.predict("を", textBeforeCursor = "書類を", suggestPeriod = false)
        assertTrue("Expected non-empty predictions", preds.isNotEmpty())
        assertEquals("提出します", preds.first().surface)
        assertEquals("history_skip_bigram", preds.first().source)
    }

    @Test
    fun testConnectionMatrixPrunesInvalidTransitionsAndSortsParticles() {
        // Build 5x5 matrix: rid=1 (noun), lid=2 ('が', low cost=400), lid=3 ('を', higher cost=1200), lid=4 ('は', INVALID_COST)
        val size = 5
        val data = ShortArray(size * size) { 2500.toShort() }
        data[1 * size + 2] = 400.toShort()
        data[1 * size + 3] = 1200.toShort()
        data[1 * size + 4] = ConnectionMatrix.INVALID_COST.toShort()
        val matrix = ConnectionMatrix.createInMemory(size, data)

        val lidLookup: (String) -> Int = { surface ->
            when (surface) {
                "が" -> 2
                "を" -> 3
                "は" -> 4
                else -> 0
            }
        }

        val preds = predictor.predict(
            lastPhrase = "報告書",
            textBeforeCursor = "報告書",
            suggestPeriod = false,
            committedRid = 1,
            connectionMatrix = matrix,
            surfaceToLid = lidLookup
        )
        val particleSurfaces = preds.filter { it.source == "particle" }.map { it.surface }
        assertFalse("INVALID_COST particle 'は' must be pruned", particleSurfaces.contains("は"))
        assertTrue("Expected 'が' before 'を' due to lower connection cost", particleSurfaces.indexOf("が") < particleSurfaces.indexOf("を"))
    }

    @Test
    fun testMultiBunsetsuPhraseEndingWithParticlePredictsAndRecordsSkipBigram() {
        // 1. Predict immediately after committing single compound phrase "明日の"
        val initialPreds = predictor.predict("明日の", textBeforeCursor = "明日の", suggestPeriod = false)
        assertTrue(
            "Expected canonical 'の' continuation '会議' after '明日の'",
            initialPreds.any { it.surface == "会議" }
        )

        // 2. Record transition from compound phrase "明日の" -> "資料"
        val token = predictor.recordTransitionWithToken("明日の", "資料", currentReading = "しりょう")
        assertNotNull(token)

        val snapAfterCompound = predictor.getHistorySnapshot()
        assertEquals(1, snapAfterCompound["明日の"]?.get("資料"))
        assertEquals(1, snapAfterCompound["の"]?.get("資料"))
        assertEquals(1, snapAfterCompound["明日|の"]?.get("資料"))

        // 3. Subsequent predict("明日の") must rank "資料" as history_skip_bigram
        val learnedPreds = predictor.predict("明日の", textBeforeCursor = "明日の", suggestPeriod = false)
        assertEquals("資料", learnedPreds.first().surface)
        assertEquals("history_skip_bigram", learnedPreds.first().source)

        // 4. Separate two-step "明日" + "の" should also see the "明日|の" skip-bigram
        val splitPreds = predictor.predict("の", textBeforeCursor = "明日の", suggestPeriod = false)
        assertEquals("資料", splitPreds.first().surface)
        assertEquals("history_skip_bigram", splitPreds.first().source)
    }

    @Test
    fun testRollbackTransitionRestoresExactPreviousBigramCountsAndContentPhrase() {
        // Establish baseline history: "資料" -> "を" -> "作成" (count 1), then content phrase "資料" -> "会議" (count 1)
        predictor.recordTransition("資料", "を")
        val existingToken = predictor.recordTransitionWithToken("を", "作成", currentReading = "さくせい")
        assertNotNull(existingToken)

        val pairsBeforeTap = predictor.getTotalBigramPairs()

        // Simulate user committing "明日の", then accidentally tapping "会議"
        val tapToken = predictor.recordTransitionWithToken("明日の", "会議", currentReading = "かいぎ")
        assertNotNull(tapToken)
        assertEquals(1, predictor.getHistorySnapshot()["明日の"]?.get("会議"))
        assertEquals(1, predictor.getHistorySnapshot()["明日|の"]?.get("会議"))

        // Roll back the accidental "会議" tap
        predictor.rollbackTransition(tapToken!!)

        val afterRollback = predictor.getHistorySnapshot()
        assertNull(afterRollback["明日の"])
        assertNull(afterRollback["の"])
        assertNull(afterRollback["明日|の"])
        assertEquals(pairsBeforeTap, predictor.getTotalBigramPairs())

        // Also test rolling back an increment on an ALREADY-existing bigram ("を" -> "作成" count 1 -> 2 -> 1)
        predictor.recordTransition("資料", "を")
        val secondToken = predictor.recordTransitionWithToken("を", "作成", currentReading = "さくせい")
        assertNotNull(secondToken)
        assertEquals(2, predictor.getHistorySnapshot()["を"]?.get("作成"))
        assertEquals(2, predictor.getHistorySnapshot()["資料|を"]?.get("作成"))

        predictor.rollbackTransition(secondToken!!)
        assertEquals(1, predictor.getHistorySnapshot()["を"]?.get("作成"))
        assertEquals(1, predictor.getHistorySnapshot()["資料|を"]?.get("作成"))

        // Ensure null token is returned for empty/self transitions
        assertNull(predictor.recordTransitionWithToken("", "会議"))
        assertNull(predictor.recordTransitionWithToken("会議", "会議"))
    }
}
