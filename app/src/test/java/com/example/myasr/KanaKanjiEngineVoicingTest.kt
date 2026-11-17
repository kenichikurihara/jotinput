package com.example.myasr

import com.example.myasr.context.kanakanji.domain.JapaneseKanaUtils
import com.example.myasr.context.kanakanji.domain.KanaKanjiEngine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.system.measureNanoTime

class KanaKanjiEngineVoicingTest {

    private lateinit var engine: KanaKanjiEngine

    @Before
    fun setUp() {
        engine = KanaKanjiEngine(null)
        val tsvFile = listOf(
            File("src/test/resources/mozc/base_dictionary.tsv"),
            File("app/src/test/resources/mozc/base_dictionary.tsv"),
            File("tools/mozc_dict/base_dictionary.tsv"),
            File("../tools/mozc_dict/base_dictionary.tsv"),
            File("src/main/assets/mozc/base_dictionary.tsv"),
            File("app/src/main/assets/mozc/base_dictionary.tsv")
        ).firstOrNull { it.exists() }
        requireNotNull(tsvFile) { "base_dictionary.tsv not found for unit test" }
        tsvFile.inputStream().use { stream ->
            engine.initializeWithStream(stream)
        }
    }

    @Test
    fun testVoicingExpansionMapIntegrity() {
        // か行 -> か + が
        assertEquals(listOf('か', 'が'), JapaneseKanaUtils.getVoicingExpansions('か'))
        assertEquals(listOf('き', 'ぎ'), JapaneseKanaUtils.getVoicingExpansions('き'))
        // は行 -> は + ば + ぱ
        assertEquals(listOf('は', 'ば', 'ぱ'), JapaneseKanaUtils.getVoicingExpansions('は'))
        assertEquals(listOf('ひ', 'び', 'ぴ'), JapaneseKanaUtils.getVoicingExpansions('ひ'))
        // さ行, た行
        assertEquals(listOf('す', 'ず'), JapaneseKanaUtils.getVoicingExpansions('す'))
        assertEquals(listOf('て', 'で'), JapaneseKanaUtils.getVoicingExpansions('て'))
        // う -> う + ゔ
        assertEquals(listOf('う', 'ゔ'), JapaneseKanaUtils.getVoicingExpansions('う'))

        // Asymmetric: already voiced characters should NOT expand back
        assertEquals(listOf('が'), JapaneseKanaUtils.getVoicingExpansions('が'))
        assertEquals(listOf('ぎ'), JapaneseKanaUtils.getVoicingExpansions('ぎ'))
        assertEquals(listOf('ぱ'), JapaneseKanaUtils.getVoicingExpansions('ぱ'))
        assertEquals(listOf('ば'), JapaneseKanaUtils.getVoicingExpansions('ば'))
        assertEquals(listOf('ー'), JapaneseKanaUtils.getVoicingExpansions('ー'))
    }

    @Test
    fun testPhoneticModifierExpansionsIntegrity() {
        // Voiced & semi-voiced
        assertEquals(listOf('か', 'が'), JapaneseKanaUtils.getPhoneticModifierExpansions('か'))
        assertEquals(listOf('は', 'ば', 'ぱ'), JapaneseKanaUtils.getPhoneticModifierExpansions('は'))
        // Small kana / youon / sokuon
        assertEquals(listOf('よ', 'ょ'), JapaneseKanaUtils.getPhoneticModifierExpansions('よ'))
        assertEquals(listOf('や', 'ゃ'), JapaneseKanaUtils.getPhoneticModifierExpansions('や'))
        assertEquals(listOf('ゆ', 'ゅ'), JapaneseKanaUtils.getPhoneticModifierExpansions('ゆ'))
        assertEquals(listOf('つ', 'づ', 'っ'), JapaneseKanaUtils.getPhoneticModifierExpansions('つ'))
        assertEquals(listOf('う', 'ゔ', 'ぅ'), JapaneseKanaUtils.getPhoneticModifierExpansions('う'))
    }

    @Test
    fun testKaikiSuggestsKaigi() {
        // User inputs "かいき" (unvoiced); "会議" (かいぎ) must be suggested
        val candidates = engine.getBaseCandidates("かいき")
        assertTrue("Candidates for 'かいき' must not be empty", candidates.isNotEmpty())

        val kaigi = candidates.firstOrNull { it.surface == "会議" }
        assertNotNull("Candidates for 'かいき' must include '会議'", kaigi)
        assertEquals("かいぎ", kaigi?.reading)
        assertTrue("Voiced fuzzy match must be flagged as prediction", kaigi?.isPrediction == true)
    }

    @Test
    fun testSuhaSuggestsSuper() {
        // User inputs "すーは"; "スーパー" (すーぱー) must be suggested as top candidate
        val candidates = engine.getBaseCandidates("すーは")
        assertTrue("Candidates for 'すーは' must not be empty", candidates.isNotEmpty())

        val top = candidates.first()
        assertEquals("Top candidate for 'すーは' must be 'スーパー'", "スーパー", top.surface)
        assertEquals("すーぱー", top.reading)
        assertTrue("Voiced predictive candidate must have isPrediction = true", top.isPrediction)

        // Must also suggest longer compounds like "スーパーボウル"
        val superBowl = candidates.firstOrNull { it.surface == "スーパーボウル" }
        assertNotNull("Candidates for 'すーは' should also include 'スーパーボウル'", superBowl)
    }

    @Test
    fun testHaSuggestsPanAndBaai() {
        // User inputs "は"; semi-voiced "パン" (ぱん), "パソコン" (ぱそこん), and voiced "場合" (ばあい) must be suggested
        val candidates = engine.getBaseCandidates("は")
        val surfaces = candidates.map { it.surface }

        assertTrue("Candidates for 'は' should include 'パン'", surfaces.contains("パン"))
        assertTrue("Candidates for 'は' should include '場合' or '場面'", surfaces.contains("場合") || surfaces.contains("場面"))
    }

    @Test
    fun testKohanSuggestsGohan() {
        // User inputs "こはん"; voiced "ご飯" / "ゴハン" (ごはん) must be suggested
        val candidates = engine.getBaseCandidates("こはん")
        val surfaces = candidates.map { it.surface }

        assertTrue("Candidates for 'こはん' should include 'ご飯' or 'ゴハン'", surfaces.contains("ご飯") || surfaces.contains("ゴハン"))
    }

    @Test
    fun testTenkiPreservesBothTenkiAndDenki() {
        // User inputs "てんき"; both exact "天気" and voiced "電気" must be suggested
        val candidates = engine.getBaseCandidates("てんき")
        val surfaces = candidates.map { it.surface }

        assertTrue("Candidates for 'てんき' must include '天気'", surfaces.contains("天気"))
        assertTrue("Candidates for 'てんき' must include '電気'", surfaces.contains("電気"))

        // Exact match "天気" should appear before voiced match "電気"
        val tenkiIndex = surfaces.indexOf("天気")
        val denkiIndex = surfaces.indexOf("電気")
        assertTrue("Exact match '天気' ($tenkiIndex) must precede voiced match '電気' ($denkiIndex)", tenkiIndex < denkiIndex)
    }

    @Test
    fun testHyouinSuggestsByouin() {
        // User inputs "ひょういん"; "病院" (びょういん) must be suggested
        val candidates = engine.getBaseCandidates("ひょういん")
        val surfaces = candidates.map { it.surface }

        assertTrue("Candidates for 'ひょういん' must include '病院'", surfaces.contains("病院"))
    }

    @Test
    fun testExplicitVoicedInputNonRegression() {
        // Explicitly typed "かいぎ" should return "会議" with isPrediction = false
        val candidates = engine.getBaseCandidates("かいぎ")
        val top = candidates.firstOrNull { it.surface == "会議" }
        assertNotNull("Exact 'かいぎ' must suggest '会議'", top)
        assertFalse("Explicit match '会議' should not be isPrediction", top!!.isPrediction)
    }

    @Test
    fun testExplicitSemiVoicedInputNonRegression() {
        // Explicitly typed "ぱ" should return "パン"
        val candidates = engine.getBaseCandidates("ぱ")
        val surfaces = candidates.map { it.surface }
        assertTrue("Explicit 'ぱ' must suggest 'パン'", surfaces.contains("パン"))
    }

    @Test
    fun testSingleCharKaSuggestsGamenAndKasoSuggestsGazo() {
        // "か" should suggest common unvoiced completions (like "顔" or "会") and voiced "画面" / "外部"
        val candidatesKa = engine.getBaseCandidates("か")
        val surfacesKa = candidatesKa.map { it.surface }

        assertTrue("Candidates for 'か' should include '顔' or '会'", surfacesKa.contains("顔") || surfacesKa.contains("会"))
        assertTrue("Candidates for 'か' should include voiced '画面' or '外部'", surfacesKa.contains("画面") || surfacesKa.contains("外部"))

        // "かい" should suggest "会社"
        val candidatesKai = engine.getBaseCandidates("かい")
        val surfacesKai = candidatesKai.map { it.surface }
        assertTrue("Candidates for 'かい' should include '会社'", surfacesKai.contains("会社"))

        // Unvoiced input "かそ" (two unvoiced characters for "がぞ") should suggest "画像" (がぞう)
        val candidatesKaso = engine.getBaseCandidates("かそ")
        val surfacesKaso = candidatesKaso.map { it.surface }
        assertTrue("Candidates for 'かそ' should include '画像'", surfacesKaso.contains("画像"))
    }

    @Test
    fun testBoundaryEmptyInputs() {
        assertTrue(engine.getBaseCandidates("").isEmpty())
        assertTrue(engine.getBaseCandidates("   ").isEmpty())
    }

    @Test
    fun testLookupLatencyBenchmark() {
        // Warm up cache
        engine.getBaseCandidates("かいき")
        engine.getBaseCandidates("すーは")

        val testInputs = listOf(
            "かいき", "すーは", "は", "てんき", "こはん", "とうきょう", "か", "すー", "ひょういん", "ふらんす"
        )

        // Clear queryCache to measure pure Trie lookup latency
        engine.clearMemoryCache()

        val iterations = 1000
        val totalNanos = measureNanoTime {
            for (i in 0 until iterations) {
                val input = testInputs[i % testInputs.size]
                val result = engine.getBaseCandidates(input)
                assertTrue(result.isNotEmpty())
            }
        }

        val avgMs = (totalNanos / iterations.toDouble()) / 1_000_000.0
        println("Average lookup latency over $iterations iterations: $avgMs ms")
        assertTrue("Average lookup latency must be well below 1.0ms (was $avgMs ms)", avgMs < 1.0)
    }
}
