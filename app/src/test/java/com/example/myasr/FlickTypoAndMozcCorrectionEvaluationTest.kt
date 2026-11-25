package com.example.myasr

import com.example.myasr.context.kanakanji.domain.ConnectionMatrix
import com.example.myasr.context.kanakanji.domain.ConversionCandidate
import com.example.myasr.context.kanakanji.domain.JapaneseKanaUtils
import com.example.myasr.context.kanakanji.domain.KanaKanjiEngine
import com.example.myasr.context.kanakanji.domain.MmapDictionary
import com.example.myasr.context.reranking.infrastructure.LlmReranker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class FlickTypoAndMozcCorrectionEvaluationTest {

    private lateinit var engine: KanaKanjiEngine

    @Before
    fun setUp() {
        engine = KanaKanjiEngine(null)
        val tsvFile = listOf(
            File("src/test/resources/mozc/base_dictionary.tsv"),
            File("app/src/test/resources/mozc/base_dictionary.tsv"),
            File("tools/mozc_dict/base_dictionary.tsv")
        ).firstOrNull { it.exists() }
        requireNotNull(tsvFile) { "base_dictionary.tsv not found for unit test" }
        tsvFile.inputStream().use { stream ->
            engine.initializeWithStream(stream)
        }

        val binFile = listOf(
            File("/tmp/connection_data.bin"),
            File("app/src/main/assets/mozc/connection_data.bin"),
            File("src/main/assets/mozc/connection_data.bin"),
            File("../app/src/main/assets/mozc/connection_data.bin"),
            File("tools/mozc_dict/connection_data.bin"),
            File("../tools/mozc_dict/connection_data.bin")
        ).firstOrNull { it.exists() && it.length() > 1000 }
        if (binFile != null) {
            val matrix = ConnectionMatrix.loadFromBinaryFile(binFile)
            if (matrix != null) {
                engine.setConnectionMatrix(matrix)
            }
        }
    }

    @Test
    fun testFlickTypoWatasaSuggestsWatashi() {
        // User inputs "わたさ" (missed left flick on 'さ', intending 'わたし')
        val candidates = engine.getBaseCandidates("わたさ")
        val surfaces = candidates.map { it.surface }
        assertTrue("Candidates for 'わたさ' should include '私', got: $surfaces",
            surfaces.contains("私"))
        // Both Kanji '私' and corrected Hiragana 'わたし' should be present
        assertTrue("Candidates for 'わたさ' should include corrected Hiragana 'わたし', got: $surfaces",
            surfaces.contains("わたし"))
    }

    @Test
    fun testFlickTypoWatakiSuggestsWatashi() {
        // User inputs "わたき" (adjacent 12-key typo: pressed 'か' column key instead of 'さ' column key)
        val candidates = engine.getBaseCandidates("わたき")
        val surfaces = candidates.map { it.surface }
        assertTrue("Candidates for 'わたき' should include '私', got: $surfaces",
            surfaces.contains("私"))
        assertTrue("Candidates for 'わたき' should include corrected Hiragana 'わたし', got: $surfaces",
            surfaces.contains("わたし"))
    }

    @Test
    fun testMozcReadingCorrectionFuinkiSuggestsFuniki() {
        // Mozc reading_correction: "ふいんき" -> "雰囲気" (correct reading: ふんいき)
        val candidates = engine.getBaseCandidates("ふいんき")
        val surfaces = candidates.map { it.surface }
        assertTrue("Candidates for 'ふいんき' must include '雰囲気', got: $surfaces",
            surfaces.contains("雰囲気"))
        assertEquals("雰囲気", candidates.first().surface)
    }

    @Test
    fun testMozcReadingCorrectionOdosanSuggestsOmiyage() {
        // Mozc reading_correction: "おどさん" -> "お土産" (correct reading: おみやげ)
        val candidates = engine.getBaseCandidates("おどさん")
        val surfaces = candidates.map { it.surface }
        assertTrue("Candidates for 'おどさん' must include 'お土産', got: $surfaces",
            surfaces.contains("お土産"))
    }

    @Test
    fun testMozcReadingCorrectionIchiyouSuggestsIchiou() {
        // Mozc reading_correction: "いちよう" -> "一応" (correct reading: いちおう)
        val candidates = engine.getBaseCandidates("いちよう")
        val surfaces = candidates.map { it.surface }
        assertTrue("Candidates for 'いちよう' must include '一応', got: $surfaces",
            surfaces.contains("一応"))
    }

    @Test
    fun testShortPhraseIenoSuggestsIenoExact() {
        // "いえの" -> "家の" should be top 1 candidate, not pushed down by "家の外" etc.
        val candidates = engine.getBaseCandidates("いえの")
        assertTrue("Candidates for 'いえの' should not be empty", candidates.isNotEmpty())
        assertEquals("家の", candidates.first().surface)
    }

    @Test
    fun testFlickUnmodifiedToukiyoSuggestsTokyo() {
        // User inputs "とうきよ" (unmodified flick: typed uppercase 'よ' instead of small 'ょ', and typed 4 chars of 'とうきょう')
        val candidates = engine.getBaseCandidates("とうきよ")
        val surfaces = candidates.map { it.surface }
        assertTrue("Candidates for 'とうきよ' must include '東京', got: $surfaces",
            surfaces.contains("東京"))
        val tokyoIdx = surfaces.indexOf("東京")
        assertTrue("東京 should be ranked in Top 3 for 'とうきよ', got index: $tokyoIdx in $surfaces",
            tokyoIdx in 0..2)
    }

    @Test
    fun testFlickUnmodifiedChiyotsutoSuggestsChotto() {
        // User inputs "ちよつと" (unmodified flick: uppercase 'よ' and 'つ' instead of small 'ょ' and 'っ')
        val candidates = engine.getBaseCandidates("ちよつと")
        val surfaces = candidates.map { it.surface }
        assertTrue("Candidates for 'ちよつと' must include 'ちょっと', got: $surfaces",
            surfaces.contains("ちょっと"))
    }

    @Test
    fun testFlickUnmodifiedKaikiSuggestsKaigi() {
        // User inputs "かいき" (unmodified flick: unvoiced 'き' instead of voiced 'ぎ')
        val candidates = engine.getBaseCandidates("かいき")
        val surfaces = candidates.map { it.surface }
        assertTrue("Candidates for 'かいき' must include '会議', got: $surfaces",
            surfaces.contains("会議"))
    }

    @Test
    fun testFlickUnmodifiedToukiyoRerankingRanksTokyoAtTop1() {
        val candTokyo = com.example.myasr.context.kanakanji.domain.ConversionCandidate(
            surface = "東京",
            reading = "とうきょう",
            isPrediction = true,
            source = "dict",
            obsPenalty = 1.2f,
            dictCost = 2100
        )
        val candPostal = com.example.myasr.context.kanakanji.domain.ConversionCandidate(
            surface = "東京貯金事務センター",
            reading = "とうきよちよきんじむせんたー",
            isPrediction = true,
            source = "dict",
            obsPenalty = 0.0f,
            dictCost = 8500
        )
        val candViterbiFragment = com.example.myasr.context.kanakanji.domain.ConversionCandidate(
            surface = "冬季よ",
            reading = "とうきよ",
            isPrediction = false,
            source = "viterbi",
            obsPenalty = 0.0f,
            dictCost = 7200
        )
        val pool = listOf(candViterbiFragment, candPostal, candTokyo)
        val scores = floatArrayOf(12.50f, 13.00f, 9.80f)

        val reranked = com.example.myasr.context.reranking.infrastructure.LlmReranker.scoreAndRankPool(
            topPool = pool,
            scores = scores,
            currentShownTop1 = null,
            weightAlpha = 0.20f,
            lambdaObs = 0.50f,
            gammaUser = 3.00f,
            targetInputLength = 4
        )

        assertEquals("東京", reranked.first().surface)
    }

    @Test
    fun testMmapOnlyUnmodifiedToukiyoSuggestsTokyoInTop3() {
        val binFile = listOf(
            File("tools/mozc_dict/system_dictionary.bin"),
            File("app/src/test/resources/mozc/system_dictionary.bin"),
            File("src/test/resources/mozc/system_dictionary.bin"),
            File("../app/src/test/resources/mozc/system_dictionary.bin"),
            File("../tools/mozc_dict/system_dictionary.bin")
        ).firstOrNull { it.exists() && it.length() > 64 }
        requireNotNull(binFile) { "system_dictionary.bin not found for Mmap-only test" }

        val mmapDict = com.example.myasr.context.kanakanji.domain.MmapDictionary.loadFromFile(binFile)
        requireNotNull(mmapDict) { "Failed to load system_dictionary.bin" }

        try {
            // Production-equivalent engine: NO TSV loaded into in-memory root Trie, ONLY MmapDictionary
            val mmapOnlyEngine = KanaKanjiEngine(null)
            mmapOnlyEngine.attachMmapDictionaryForTesting("mozc_general", mmapDict)

            val candidates = mmapOnlyEngine.getBaseCandidates("とうきよ")
            val surfaces = candidates.map { it.surface }
            assertTrue(
                "Mmap-only candidates for 'とうきよ' must include '東京', got: $surfaces",
                surfaces.contains("東京")
            )
            val tokyoIdx = surfaces.indexOf("東京")
            assertTrue(
                "Mmap-only '東京' should be ranked in Top 3 for 'とうきよ', got index: $tokyoIdx in $surfaces",
                tokyoIdx in 0..2
            )

            val kaikiCands = mmapOnlyEngine.getBaseCandidates("かいき").map { it.surface }
            assertTrue(
                "Mmap-only candidates for 'かいき' must include '会議', got: $kaikiCands",
                kaikiCands.contains("会議")
            )
        } finally {
            mmapDict.close()
        }
    }

    private fun withMmapOnlyEngine(block: (KanaKanjiEngine) -> Unit) {
        val binFile = listOf(
            File("app/src/test/resources/mozc/system_dictionary.bin"),
            File("src/test/resources/mozc/system_dictionary.bin"),
            File("../app/src/test/resources/mozc/system_dictionary.bin"),
            File("tools/mozc_dict/system_dictionary.bin"),
            File("../tools/mozc_dict/system_dictionary.bin")
        ).firstOrNull { it.exists() && it.length() > 64 }
        requireNotNull(binFile) { "system_dictionary.bin not found for Mmap-only test" }

        val mmapDict = MmapDictionary.loadFromFile(binFile)
        requireNotNull(mmapDict) { "Failed to load system_dictionary.bin" }

        val matrixFile = listOf(
            File("/tmp/connection_data.bin"),
            File("app/src/main/assets/mozc/connection_data.bin"),
            File("src/main/assets/mozc/connection_data.bin"),
            File("../app/src/main/assets/mozc/connection_data.bin"),
            File("tools/mozc_dict/connection_data.bin"),
            File("../tools/mozc_dict/connection_data.bin")
        ).firstOrNull { it.exists() && it.length() > 1000 }
        val matrix = matrixFile?.let { ConnectionMatrix.loadFromBinaryFile(it) }

        try {
            val mmapOnlyEngine = KanaKanjiEngine(null)
            mmapOnlyEngine.attachMmapDictionaryForTesting("mozc_general", mmapDict)
            if (matrix != null) {
                mmapOnlyEngine.setConnectionMatrix(matrix)
            }
            block(mmapOnlyEngine)
        } finally {
            mmapDict.close()
            matrix?.close()
        }
    }

    @Test
    fun testVoicedAndDiagonalFlickTypoGeometry() {
        // Voiced center <-> directional flicks (e.g. 'が' <-> 'げ', 'ご' <-> 'ぎ'/'げ')
        val gaChars = JapaneseKanaUtils.getFlickTypoExpansions('が').map { it.char }
        assertTrue("が should expand to ぎ/ぐ/げ/ご", gaChars.containsAll(listOf('ぎ', 'ぐ', 'げ', 'ご', 'ざ')))
        val goExp = JapaneseKanaUtils.getFlickTypoExpansions('ご')
        assertTrue("ご should expand to が(2200), ぎ(2600), げ(2600), ぞ(3200)",
            goExp.any { it.char == 'が' && it.costPenalty == 2200 } &&
            goExp.any { it.char == 'ぎ' && it.costPenalty == 2600 } &&
            goExp.any { it.char == 'げ' && it.costPenalty == 2600 } &&
            goExp.any { it.char == 'ぞ' && it.costPenalty == 3200 }
        )

        // Voiced ざ/だ/ば and semi-voiced ぱ rows
        assertTrue(JapaneseKanaUtils.getFlickTypoExpansions('ざ').any { it.char == 'じ' })
        assertTrue(JapaneseKanaUtils.getFlickTypoExpansions('だ').any { it.char == 'で' })
        assertTrue(JapaneseKanaUtils.getFlickTypoExpansions('ば').any { it.char == 'ぶ' })
        assertTrue(JapaneseKanaUtils.getFlickTypoExpansions('ぱ').any { it.char == 'ぷ' })

        // Same-key 90-degree diagonal unvoiced flicks ('こ' -> 'き'/'け', 'く' -> 'き'/'け')
        val koChars = JapaneseKanaUtils.getFlickTypoExpansions('こ').map { it.char }
        assertTrue("こ should include 90-degree adjacent き and け", koChars.containsAll(listOf('か', 'き', 'け', 'そ')))
        val kuChars = JapaneseKanaUtils.getFlickTypoExpansions('く').map { it.char }
        assertTrue("く should include 90-degree adjacent き and け", kuChars.containsAll(listOf('か', 'き', 'け', 'す')))
    }

    @Test
    fun testMultiMorphemeTypoMitsukorimasenRanksMitsukarimasenTop1() {
        // 1. TSV mode
        val tsvCands = engine.getBaseCandidates("みつこりません")
        assertTrue("TSV candidates for 'みつこりません' should not be empty", tsvCands.isNotEmpty())
        assertEquals("見つかりません", tsvCands.first().surface)

        // 2. Mmap-only mode (50MB unpruned + ConnectionMatrix)
        withMmapOnlyEngine { mmapEngine ->
            val mmapCands = mmapEngine.getBaseCandidates("みつこりません")
            assertTrue("Mmap candidates for 'みつこりません' should not be empty", mmapCands.isNotEmpty())
            assertEquals("見つかりません", mmapCands.first().surface)

            // 3. SLM reranking with measured Qwen NLLs
            val candTarget = mmapCands.first { it.surface == "見つかりません" }
            val candExactGarbage = ConversionCandidate("密こりません", "みつこりません", source = "viterbi", dictCost = 5963)
            val candPrefix = ConversionCandidate("光子", "みつこ", source = "viterbi_prefix", dictCost = 4712)
            val pool = listOf(candTarget, candExactGarbage, candPrefix)
            val scores = floatArrayOf(21.9564f, 37.2928f, 14.2226f)
            val reranked = LlmReranker.scoreAndRankPool(
                topPool = pool,
                scores = scores,
                currentShownTop1 = null,
                weightAlpha = 0.20f,
                lambdaObs = 0.50f,
                gammaUser = 3.00f,
                targetInputLength = "みつこりません".length
            )
            assertEquals("見つかりません", reranked.first().surface)
        }
    }

    @Test
    fun testVoicedTypoKangaikaiRanksKangeikai() {
        // 1. TSV mode: 歓迎会 must rank in Top-2 (in fact #1)
        val tsvCands = engine.getBaseCandidates("かんがいかい")
        val tsvTop2 = tsvCands.take(2).map { it.surface }
        assertTrue("TSV Top-2 for 'かんがいかい' must include '歓迎会', got: ${tsvCands.take(5).map { it.surface }}",
            tsvTop2.contains("歓迎会"))

        // 2. Mmap-only mode + SLM reranking
        withMmapOnlyEngine { mmapEngine ->
            val mmapCands = mmapEngine.getBaseCandidates("かんがいかい")
            val mmapTop2 = mmapCands.take(2).map { it.surface }
            assertTrue("Mmap Top-2 for 'かんがいかい' must include '歓迎会', got: ${mmapCands.take(5).map { it.surface }}",
                mmapTop2.contains("歓迎会"))

            val candKangei = mmapCands.first { it.surface == "歓迎会" }
            val candKangai1 = mmapCands.firstOrNull { it.surface == "灌漑会" }
                ?: ConversionCandidate("灌漑会", "かんがいかい", source = "viterbi", dictCost = 4235)
            val candKangai2 = mmapCands.firstOrNull { it.surface == "感慨会" }
                ?: ConversionCandidate("感慨会", "かんがいかい", source = "viterbi", dictCost = 4488)
            val pool = listOf(candKangai1, candKangei, candKangai2)

            // Context: "新入社員の" -> 歓迎会 must rank #1
            val rerankedCtx = LlmReranker.scoreAndRankPool(
                topPool = pool,
                scores = floatArrayOf(19.4701f, 8.0255f, 19.4319f),
                currentShownTop1 = null,
                weightAlpha = 0.20f,
                lambdaObs = 0.50f,
                gammaUser = 3.00f,
                targetInputLength = "かんがいかい".length
            )
            assertEquals("歓迎会", rerankedCtx.first().surface)

            // Empty context -> 歓迎会 must rank in Top-2
            val rerankedEmpty = LlmReranker.scoreAndRankPool(
                topPool = pool,
                scores = floatArrayOf(29.7302f, 22.5877f, 22.6602f),
                currentShownTop1 = null,
                weightAlpha = 0.20f,
                lambdaObs = 0.50f,
                gammaUser = 3.00f,
                targetInputLength = "かんがいかい".length
            )
            assertTrue("Empty context Top-2 must include '歓迎会', got: ${rerankedEmpty.map { it.surface }}",
                rerankedEmpty.take(2).any { it.surface == "歓迎会" })
        }
    }

    @Test
    fun testDiagonalAndMultiMorphemeTypoCases() {
        withMmapOnlyEngine { mmapEngine ->
            // 90-degree diagonal voiced flick typo: かんごいかい -> 歓迎会
            val kangoTop3 = mmapEngine.getBaseCandidates("かんごいかい").take(3).map { it.surface }
            assertTrue("'かんごいかい' Top-3 should include '歓迎会', got: $kangoTop3", kangoTop3.contains("歓迎会"))

            // 90-degree diagonal unvoiced flick typo: てんくがよい -> 天気が良い enters fullCands.take(8) and wins #1 under SLM reranking
            val tenkuAll = mmapEngine.getBaseCandidates("てんくがよい")
            val tenkuFull8 = tenkuAll.filter { !it.isPrediction && it.source != "viterbi_prefix" }.take(8)
            assertTrue(
                "'てんくがよい' fullCands.take(8) must include '天気が良い', got: ${tenkuFull8.map { it.surface }}",
                tenkuFull8.any { it.surface == "天気が良い" }
            )
            val candTenkiGood = tenkuFull8.first { it.surface == "天気が良い" }
            val candTankGood = tenkuFull8.firstOrNull { it.surface == "タンクが良い" }
                ?: ConversionCandidate("タンクが良い", "たんくがよい", source = "viterbi_typo", obsPenalty = 2.2f, dictCost = 2911)
            val candTenkuLit = tenkuFull8.firstOrNull { it.surface == "点句が良い" }
                ?: ConversionCandidate("点句が良い", "てんくがよい", source = "viterbi", dictCost = 2941)
            val candTenkuShop = tenkuFull8.firstOrNull { it.surface == "店句が良い" }
                ?: ConversionCandidate("店句が良い", "てんくがよい", source = "viterbi", dictCost = 2982)
            val tenkuReranked = LlmReranker.scoreAndRankPool(
                topPool = listOf(candTankGood, candTenkuLit, candTenkuShop, candTenkiGood),
                scores = floatArrayOf(30.6298f, 34.7369f, 42.0365f, 21.0020f),
                currentShownTop1 = null,
                weightAlpha = 0.20f,
                lambdaObs = 0.50f,
                gammaUser = 3.00f,
                targetInputLength = "てんくがよい".length
            )
            assertEquals("天気が良い", tenkuReranked.first().surface)

            // Multi-morpheme phrase flick typo: おつかれさみです -> お疲れ様です
            val otsuTop3 = mmapEngine.getBaseCandidates("おつかれさみです").take(3).map { it.surface }
            assertTrue("'おつかれさみです' Top-3 should include 'お疲れ様です', got: $otsuTop3", otsuTop3.contains("お疲れ様です"))

            // Multi-morpheme voicing omission: かいきがある -> 会議がある enters fullCands.take(8) and wins #1 in context "午後に"
            val kaikiFull8 = mmapEngine.getBaseCandidates("かいきがある")
                .filter { !it.isPrediction && it.source != "viterbi_prefix" }
                .take(8)
            assertTrue(
                "'かいきがある' fullCands.take(8) must include '会議がある', got: ${kaikiFull8.map { it.surface }}",
                kaikiFull8.any { it.surface == "会議がある" }
            )
            val candKaigi = kaikiFull8.first { it.surface == "会議がある" }
            val candKaiki1 = kaikiFull8.firstOrNull { it.surface == "回帰がある" }
                ?: ConversionCandidate("回帰がある", "かいきがある", source = "viterbi", dictCost = 2738)
            val candKaiki2 = kaikiFull8.firstOrNull { it.surface == "会期がある" }
                ?: ConversionCandidate("会期がある", "かいきがある", source = "viterbi", dictCost = 3183)
            val kaikiReranked = LlmReranker.scoreAndRankPool(
                topPool = listOf(candKaiki1, candKaiki2, candKaigi),
                scores = floatArrayOf(18.7973f, 16.3596f, 12.2879f),
                currentShownTop1 = null,
                weightAlpha = 0.20f,
                lambdaObs = 0.50f,
                gammaUser = 3.00f,
                targetInputLength = "かいきがある".length
            )
            assertEquals("会議がある", kaikiReranked.first().surface)
        }
    }

    @Test
    fun testTwoMoraCompoundBuhiRanksBuhiTop1AndReranksTop3() {
        // 1. TSV mode (where 'ぶひ' is absent as exact entry -> 2-mora compound synthesis)
        val tsvCands = engine.getBaseCandidates("ぶひ")
        assertTrue("TSV candidates for 'ぶひ' should not be empty", tsvCands.isNotEmpty())
        assertEquals("部費", tsvCands.first().surface)
        assertFalse("Prefix chimera '部被' must not be synthesized", tsvCands.any { it.surface == "部被" })

        // Productive 2-mora phrases in TSV mode
        assertTrue("'てが' should include '手が'", engine.getBaseCandidates("てが").take(3).any { it.surface == "手が" })
        assertTrue("'めを' should include '目を'", engine.getBaseCandidates("めを").take(3).any { it.surface == "目を" })
        assertTrue("'きに' should include '気に'", engine.getBaseCandidates("きに").take(3).any { it.surface == "気に" })
        // Common 2-char prefixes must not produce invalid 1+1 kanji chimeras
        assertFalse("'ござ' should have no junk viterbi 1+1 synthesis",
            engine.getBaseCandidates("ござ").any { it.source == "viterbi" })
        assertFalse("'くだ' should have no junk viterbi 1+1 synthesis",
            engine.getBaseCandidates("くだ").any { it.source == "viterbi" })

        // 2. Mmap-only mode (50MB unpruned) + SLM reranking
        withMmapOnlyEngine { mmapEngine ->
            val mmapCands = mmapEngine.getBaseCandidates("ぶひ")
            assertTrue("Mmap candidates for 'ぶひ' should not be empty", mmapCands.isNotEmpty())
            assertEquals("部費", mmapCands.first().surface)

            val candBuhi = mmapCands.first { it.surface == "部費" }
            val candBuhin = mmapCands.first { it.surface == "部品" }
            val candKatakana = mmapCands.first { it.surface == "ブヒ" }
            val pool = listOf(candBuhi, candBuhin, candKatakana)
            val reranked = LlmReranker.scoreAndRankPool(
                topPool = pool,
                scores = floatArrayOf(23.3713f, 17.2187f, 22.4158f),
                currentShownTop1 = null,
                weightAlpha = 0.20f,
                lambdaObs = 0.50f,
                gammaUser = 3.00f,
                targetInputLength = "ぶひ".length
            )
            assertTrue("Reranked Top-3 for 'ぶひ' must include '部費', got: ${reranked.map { it.surface }}",
                reranked.take(3).any { it.surface == "部費" })
        }
    }

    @Test
    fun testMonotonicCrfDictCostPropagationAndFullPoolTypoReranking() {
        withMmapOnlyEngine { mmapEngine ->
            // 1. Monotonic Mozc CRF dictCost propagation:
            //    見つかりません must retain low dictCost (< 4000) while chimeric/rare alternatives retain high dictCost.
            val mitsuCands = mmapEngine.getBaseCandidates("みつこりません")
            val mitsuTarget = mitsuCands.first { it.surface == "見つかりません" }
            val mitsuGarbage = mitsuCands.first { it.surface == "密こりません" }
            val koushiLima = mitsuCands.firstOrNull { it.surface == "光子リマ線" }
            assertTrue("'見つかりません' dictCost (${mitsuTarget.dictCost}) must be < 4000", mitsuTarget.dictCost < 4000)
            assertTrue("'密こりません' dictCost (${mitsuGarbage.dictCost}) must be > 7500", mitsuGarbage.dictCost > 7500)
            if (koushiLima != null) {
                assertTrue("'光子リマ線' dictCost (${koushiLima.dictCost}) must be > 9000", koushiLima.dictCost > 9000)
            }

            //    さぎょうちゅうですので: 作業中ですので must have strictly lower dictCost than 作業虫ですので
            val sagyouCands = mmapEngine.getBaseCandidates("さぎょうちゅうですので")
            val sagyouChuu = sagyouCands.first { it.surface == "作業中ですので" }
            val sagyouMushi = sagyouCands.first { it.surface == "作業虫ですので" }
            assertTrue(
                "'作業中ですので' dictCost (${sagyouChuu.dictCost}) must be < '作業虫ですので' dictCost (${sagyouMushi.dictCost})",
                sagyouChuu.dictCost < sagyouMushi.dictCost
            )

            // 2. Exact Pixel 10 Pro config reproduction (weightAlpha = 0.0f, lambdaObs = 0.5f, 16-candidate pool)
            //    Build the exact topPool partition used by LlmReranker.rerankWithStatus
            val nonHiragana = mitsuCands.filter {
                !(JapaneseKanaUtils.isAllHiragana(it.surface) && it.surface == it.reading && !it.isPrediction)
            }
            val fullCands = nonHiragana.filter { !it.isPrediction && (it.reading.isEmpty() || it.source != "viterbi_prefix") }
            val prefixCands = nonHiragana.filter { it.source == "viterbi_prefix" || it.isPrediction }
            val pool = (fullCands.take(8) + prefixCands).distinctBy { it.surface }.take(16)
            assertTrue("Pool for 'みつこりません' should trigger targeted Stage 2", LlmReranker.hasFullSpanTypoCompetition(pool))

            val stage2NllMap = mapOf(
                "見つかりません" to 21.9564f,
                "密こりません" to 37.2928f,
                "蜜こりません" to 42.1157f,
                "密凝りません" to 45.7389f,
                "みつ凝りません" to 46.3834f,
                "光子リマ線" to 45.0f,
                "見たこりません" to 35.0f,
                "見てこりません" to 36.0f,
                "光子" to 14.2226f,
                "美津子" to 24.1948f,
                "みつ子" to 27.0390f,
                "美都子" to 27.2765f
            )
            val scores = FloatArray(pool.size) { idx ->
                val c = pool[idx]
                val key = if (c.completedSurface.isNotEmpty()) c.completedSurface else c.surface
                stage2NllMap[key] ?: 40.0f
            }
            val rerankedPhoneConfig = LlmReranker.scoreAndRankPool(
                topPool = pool,
                scores = scores,
                currentShownTop1 = null,
                weightAlpha = 0.0f,
                lambdaObs = 0.5f,
                gammaUser = 3.0f,
                targetInputLength = "みつこりません".length
            )
            assertEquals(
                "With phone config (alpha=0.0, lambdaObs=0.5) and Stage-2 full NLLs, '見つかりません' must rank #1, got: ${rerankedPhoneConfig.take(5).map { it.surface }}",
                "見つかりません",
                rerankedPhoneConfig.first().surface
            )

            // 3. hasFullSpanTypoCompetition selectivity gate
            assertTrue(LlmReranker.hasFullSpanTypoCompetition(mmapEngine.getBaseCandidates("かんがいかい").take(16)))
            assertTrue(LlmReranker.hasFullSpanTypoCompetition(mmapEngine.getBaseCandidates("てんくがよい").take(16)))
            assertFalse(
                "Exact reading 'きょうはいいてんき' must not trigger Stage 2 typo gate",
                LlmReranker.hasFullSpanTypoCompetition(mmapEngine.getBaseCandidates("きょうはいいてんき").take(16))
            )
            assertFalse(
                "Exact reading 'さぎょうちゅうですので' must not trigger Stage 2 typo gate",
                LlmReranker.hasFullSpanTypoCompetition(sagyouCands.take(16))
            )
            assertFalse(
                "Short exact reading 'きょう' must not trigger Stage 2 typo gate",
                LlmReranker.hasFullSpanTypoCompetition(mmapEngine.getBaseCandidates("きょう").take(16))
            )
        }
    }
}
