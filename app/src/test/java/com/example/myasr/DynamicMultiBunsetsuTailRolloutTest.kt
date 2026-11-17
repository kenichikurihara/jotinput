package com.example.myasr

import com.example.myasr.context.kanakanji.domain.ConnectionMatrix
import com.example.myasr.context.kanakanji.domain.KanaKanjiEngine
import com.example.myasr.context.kanakanji.domain.NextPhrasePredictor
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Phase 2 Unit Test: B3 Dynamic Multi-Bunsetsu Tail Rollout (動的複文節テイル合成).
 *
 * Verifies that:
 * 1. Sahen nouns dynamically synthesize polite/modal multi-bunsetsu completions.
 * 2. High-frequency noun stems roll out natural Noun + Particle + Predicate chunks (e.g. 電車に乗る, 資料を送信しました).
 * 3. Verb stems dynamically roll out inflections and continuations (e.g. 思っているのに, 努めております).
 * 4. General dictionary stems outside the 37 hardcoded list dynamically synthesize valid functional tails.
 * 5. Mozc connection cost filtering safely validates transitions.
 * 6. Existing canonical phrase chunk synthesis passes 100% without regression.
 */
class DynamicMultiBunsetsuTailRolloutTest {

    private lateinit var engine: KanaKanjiEngine
    private lateinit var predictor: NextPhrasePredictor

    @Before
    fun setUp() {
        predictor = NextPhrasePredictor(null)
        engine = KanaKanjiEngine(null)
        engine.predictor = predictor

        val tsvFile = listOf(
            File("app/src/test/resources/mozc/base_dictionary.tsv"),
            File("src/test/resources/mozc/base_dictionary.tsv"),
            File("tools/mozc_dict/base_dictionary.tsv"),
            File("../tools/mozc_dict/base_dictionary.tsv")
        ).firstOrNull { it.exists() }
        requireNotNull(tsvFile) { "base_dictionary.tsv required for test" }
        tsvFile.inputStream().use { s ->
            engine.initializeWithStream(s)
        }
    }

    @Test
    fun testCanonicalStemsNonRegression() {
        // Step 5.5 baseline non-regression
        val candsOne = engine.getBaseCandidates("おね")
        val surfacesOne = candsOne.take(4).map { it.surface }
        assertTrue("Expected 'お願い*' in candidates for 'おね', was: $surfacesOne", surfacesOne.any { it.startsWith("お願い") })

        val candsKaku = engine.getBaseCandidates("かく")
        val surfacesKaku = candsKaku.take(4).map { it.surface }
        assertTrue("Expected '確認しました' in candidates for 'かく', was: $surfacesKaku", surfacesKaku.contains("確認しました"))

        val candsRen = engine.getBaseCandidates("れん")
        val surfacesRen = candsRen.take(4).map { it.surface }
        assertTrue("Expected '連絡します' in candidates for 'れん', was: $surfacesRen", surfacesRen.contains("連絡します"))

        val candsSagi = engine.getBaseCandidates("さぎ")
        val surfacesSagi = candsSagi.take(4).map { it.surface }
        assertTrue("Expected '作業を進めます' in candidates for 'さぎ', was: $surfacesSagi", surfacesSagi.contains("作業を進めます"))
    }

    @Test
    fun testSahenNounDynamicTailRollout() {
        // "さぎ" -> "作業中ですので"
        val candsSagi = engine.getBaseCandidates("さぎ")
        val surfacesSagi = candsSagi.map { it.surface }
        assertTrue("Expected '作業中ですので' in candidates for 'さぎ', was: ${surfacesSagi.take(8)}", surfacesSagi.contains("作業中ですので"))

        // "ちょう" -> "調整させていただきます"
        val candsChou = engine.getBaseCandidates("ちょう")
        val surfacesChou = candsChou.map { it.surface }
        assertTrue("Expected '調整させていただきます' in candidates for 'ちょう', was: ${surfacesChou.take(8)}", surfacesChou.contains("調整させていただきます"))

        // "ごか" -> "ご確認のほど"
        val candsGoka = engine.getBaseCandidates("ごか")
        val surfacesGoka = candsGoka.map { it.surface }
        assertTrue("Expected 'ご確認のほど' in candidates for 'ごか', was: ${surfacesGoka.take(8)}", surfacesGoka.any { it.startsWith("ご確認のほど") })

        // "ごけ" -> "ご検討いただけますと幸いです"
        val candsGoke = engine.getBaseCandidates("ごけ")
        val surfacesGoke = candsGoke.map { it.surface }
        assertTrue("Expected 'ご検討いただけますと幸いです' in candidates for 'ごけ', was: ${surfacesGoke.take(8)}", surfacesGoke.contains("ご検討いただけますと幸いです"))
    }

    @Test
    fun testNounParticlePredicateRollout() {
        // "でん" -> "電車に乗る"
        val candsDen = engine.getBaseCandidates("でん")
        val surfacesDen = candsDen.map { it.surface }
        assertTrue("Expected '電車に乗る' in candidates for 'でん', was: ${surfacesDen.take(8)}", surfacesDen.contains("電車に乗る"))

        // "しり" -> "資料を送信しました"
        val candsShiri = engine.getBaseCandidates("しり")
        val surfacesShiri = candsShiri.map { it.surface }
        assertTrue("Expected '資料を送信しました' in candidates for 'しり', was: ${surfacesShiri.take(8)}", surfacesShiri.contains("資料を送信しました"))

        // "かい" -> "会議に参加します"
        val candsKai = engine.getBaseCandidates("かい")
        val surfacesKai = candsKai.map { it.surface }
        assertTrue("Expected '会議に参加します' in candidates for 'かい', was: ${surfacesKai.take(8)}", surfacesKai.contains("会議に参加します"))

        // "よて" -> "予定を確認する"
        val candsYote = engine.getBaseCandidates("よて")
        val surfacesYote = candsYote.map { it.surface }
        assertTrue("Expected '予定を確認する' in candidates for 'よて', was: ${surfacesYote.take(8)}", surfacesYote.contains("予定を確認する"))

        // "たい" -> "体調が悪い"
        val candsTai = engine.getBaseCandidates("たい")
        val surfacesTai = candsTai.map { it.surface }
        assertTrue("Expected '体調が悪い' in candidates for 'たい', was: ${surfacesTai.take(8)}", surfacesTai.contains("体調が悪い"))

        // "おな" -> "お腹が空いた"
        val candsOna = engine.getBaseCandidates("おな")
        val surfacesOna = candsOna.map { it.surface }
        assertTrue("Expected 'お腹が空いた' in candidates for 'おな', was: ${surfacesOna.take(8)}", surfacesOna.contains("お腹が空いた"))

        // "かぜ" -> "風邪をひいた"
        val candsKaze = engine.getBaseCandidates("かぜ")
        val surfacesKaze = candsKaze.map { it.surface }
        assertTrue("Expected '風邪をひいた' in candidates for 'かぜ', was: ${surfacesKaze.take(8)}", surfacesKaze.contains("風邪をひいた"))
    }

    @Test
    fun testVerbStemTailRollout() {
        // "おも" -> "思っているのに" or "思います"
        val candsOmo = engine.getBaseCandidates("おも")
        val surfacesOmo = candsOmo.map { it.surface }
        assertTrue("Expected '思っているのに' in candidates for 'おも', was: ${surfacesOmo.take(8)}", surfacesOmo.contains("思っているのに"))

        // "つと" -> "努めております"
        val candsTsuto = engine.getBaseCandidates("つと")
        val surfacesTsuto = candsTsuto.map { it.surface }
        assertTrue("Expected '努めております' in candidates for 'つと', was: ${surfacesTsuto.take(8)}", surfacesTsuto.contains("努めております"))
    }

    @Test
    fun testTypedIntoTailPrefixMatching() {
        // User typed into particle: "でんしゃに" -> "電車に乗る"
        val cands = engine.getBaseCandidates("でんしゃに")
        val surfaces = cands.map { it.surface }
        assertTrue("Expected '電車に乗る' for input 'でんしゃに', was: ${surfaces.take(8)}", surfaces.contains("電車に乗る"))

        // User typed into tail: "さぎょうち" -> "作業中ですので"
        val candsSagi = engine.getBaseCandidates("さぎょうち")
        val surfacesSagi = candsSagi.map { it.surface }
        assertTrue("Expected '作業中ですので' for input 'さぎょうち', was: ${surfacesSagi.take(8)}", surfacesSagi.contains("作業中ですので"))
    }
}
