package com.example.myasr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class LongPhraseConversionTest {

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

        val binFile = listOf(
            File("/tmp/connection_data.bin"),
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
    fun testRealDictionaryLongPhraseMultiBunsetsu() {
        // Test 1: "きょうはいいてんき" -> "今日はいい天気" / "今日は良い天気"
        val candidates1 = engine.getBaseCandidates("きょうはいいてんき")
        assertTrue("Candidates should not be empty for きょうはいいてんき", candidates1.isNotEmpty())
        val top1 = candidates1.first().surface
        assertTrue("Expected '今日はいい天気' or '今日は良い天気', but was: $top1",
            top1 == "今日はいい天気" || top1 == "今日は良い天気")
        assertFalse("Should not contain homophone trap '派'", top1.contains("派"))
        assertFalse("Should not contain homophone trap '共'", top1.contains("共"))

        // Test 2: "さぎょうちゅうですので" -> "作業中ですので"
        val candidates2 = engine.getBaseCandidates("さぎょうちゅうですので")
        assertTrue("Candidates should not be empty for さぎょうちゅうですので", candidates2.isNotEmpty())
        val top2 = candidates2.first().surface
        assertEquals("作業中ですので", top2)
        assertFalse("Should not choose net-slang trap '厨'", top2.contains("厨"))
        assertFalse("Should not choose katakana trap 'デス'", top2.contains("デス"))

        // Test 3: "つとめております" -> "努めております"
        val candidates3 = engine.getBaseCandidates("つとめております")
        assertTrue("Candidates should not be empty for つとめております", candidates3.isNotEmpty())
        val top3 = candidates3.first().surface
        assertEquals("努めております", top3)
        assertFalse("Should not choose obscure trap '折'", top3.contains("折"))
        assertFalse("Should not choose obscure trap '摩す'", top3.contains("摩す"))

        // Test 4: "おもっているのに" -> "思っているのに"
        val candidates4 = engine.getBaseCandidates("おもっているのに")
        assertTrue("Candidates should not be empty for おもっているのに", candidates4.isNotEmpty())
        val top4 = candidates4.first().surface
        assertEquals("思っているのに", top4)

        // Test 5: "ちゅうがたばすです" -> "中型バスです"
        val candidates5 = engine.getBaseCandidates("ちゅうがたばすです")
        assertTrue("Candidates should not be empty for ちゅうがたばすです", candidates5.isNotEmpty())
        val top5 = candidates5.first().surface
        assertEquals("中型バスです", top5)

        // Test 6: "かぐとうかいぼうしの" -> "家具倒壊防止の"
        val candidates6 = engine.getBaseCandidates("かぐとうかいぼうしの")
        assertTrue("Candidates should not be empty for かぐとうかいぼうしの", candidates6.isNotEmpty())
        assertTrue("Expected '家具倒壊防止の' in top candidates, but was: ${candidates6.take(3).map { it.surface }}",
            candidates6.take(2).any { it.surface == "家具倒壊防止の" })
    }

    @Test
    fun testColloquialVerbContractionsAndTeruTrapElimination() {
        // Test 1: "むかってる" -> "向かってる" (Eradicate "向っテル")
        val cands1 = engine.getBaseCandidates("むかってる")
        assertTrue("Candidates should not be empty for むかってる", cands1.isNotEmpty())
        val top1 = cands1.first().surface
        assertEquals("向かってる", top1)
        assertFalse("Should not choose bizarre katakana trap 'テル'", top1.contains("テル"))
        assertFalse("Should not choose archaic kanji trap '照'", top1.contains("照"))

        // Test 2: "むかっている" -> "向かっている"
        val cands2 = engine.getBaseCandidates("むかっている")
        assertTrue("Candidates should not be empty for むかっている", cands2.isNotEmpty())
        val top2 = cands2.first().surface
        assertEquals("向かっている", top2)
        assertFalse("Should not choose bizarre '無買'", top2.contains("無買"))

        // Test 3: "むかって" -> "向かって" (Eradicate "無買って")
        val cands3 = engine.getBaseCandidates("むかって")
        assertTrue("Candidates should not be empty for むかって", cands3.isNotEmpty())
        val top3 = cands3.first().surface
        assertEquals("向かって", top3)
        assertFalse("Should not choose bizarre '無買って'", top3.contains("無買"))

        // Test 4: "むかった" -> "向かった" (Eradicate "無買った" and "向っ多")
        val cands4 = engine.getBaseCandidates("むかった")
        assertTrue("Candidates should not be empty for むかった", cands4.isNotEmpty())
        val top4 = cands4.first().surface
        assertEquals("向かった", top4)
        assertFalse("Should not choose kanji '多'", top4.contains("多"))
        assertFalse("Should not choose kanji '他'", top4.contains("他"))

        // Test 5: "しってる" -> "知ってる" (Eradicate "知っテル")
        val cands5 = engine.getBaseCandidates("しってる")
        assertTrue("Candidates should not be empty for しってる", cands5.isNotEmpty())
        assertEquals("知ってる", cands5.first().surface)

        // Test 6: "はしってる" -> "走ってる" (Eradicate "走っテル")
        val cands6 = engine.getBaseCandidates("はしってる")
        assertTrue("Candidates should not be empty for はしってる", cands6.isNotEmpty())
        assertEquals("走ってる", cands6.first().surface)

        // Test 7: "みてる" -> "見てる" (Length 3 Viterbi lattice support)
        val cands7 = engine.getBaseCandidates("みてる")
        assertTrue("Candidates should not be empty for みてる", cands7.isNotEmpty())
        assertEquals("見てる", cands7.first().surface)

        // Test 8: "もってる" -> "持ってる" (Eradicate "保っテル")
        val cands8 = engine.getBaseCandidates("もってる")
        assertTrue("Candidates should not be empty for もってる", cands8.isNotEmpty())
        assertEquals("持ってる", cands8.first().surface)

        // Test 9: "でんわしています" -> "電話しています" (Eradicate "電話四ています")
        val cands9 = engine.getBaseCandidates("でんわしています")
        assertTrue("Candidates should not be empty for でんわしています", cands9.isNotEmpty())
        assertEquals("電話しています", cands9.first().surface)
        assertFalse("Should not choose numeric kanji '四'", cands9.first().surface.contains("四"))

        // Test 10: "れんらくをおまちしています" -> "連絡をお待ちしています"
        val cands10 = engine.getBaseCandidates("れんらくをおまちしています")
        assertTrue("Candidates should not be empty for れんらくをおまちしています", cands10.isNotEmpty())
        assertEquals("連絡をお待ちしています", cands10.first().surface)
    }

    @Test
    fun testRealDictionaryLatticeSearchPerformanceBudget() {
        val reading = "きょうはいいてんき"

        // Warm up JIT
        for (i in 0 until 20) {
            engine.getBaseCandidates(reading)
        }

        val startTime = System.nanoTime()
        val results = engine.getBaseCandidates(reading)
        val elapsedMs = (System.nanoTime() - startTime) / 1_000_000.0

        assertTrue("Viterbi lattice search should run in < 15ms (was $elapsedMs ms)", elapsedMs < 15.0)
        assertTrue(results.isNotEmpty())
    }

    @Test
    fun testNoAllHiraganaCandidatesInOutput() {
        val testReadings = listOf(
            "きょう",
            "むかってる",
            "さぎょうちゅうですので",
            "ことなります",
            "つとめております"
        )
        for (r in testReadings) {
            val cands = engine.getBaseCandidates(r)
            for (c in cands) {
                // All-hiragana candidates identical to raw reading should be excluded
                // because pressing commit directly inputs the un-converted Hiragana.
                // Mistype-corrected Hiragana candidates (e.g. from flick errors) are permitted.
                val isRedundantHiragana = JapaneseKanaUtils.isAllHiragana(c.surface) && c.surface == r
                org.junit.Assert.assertFalse(
                    "Candidate '${c.surface}' for reading '$r' should not match raw composing Hiragana identically",
                    isRedundantHiragana
                )
            }
        }
    }

    @Test
    fun testJapaneseBracketCandidatesFromFlickAndReading() {
        // Flick 'や' left produces '（'
        val openCands = engine.getBaseCandidates("（").map { it.surface }
        assertTrue("Opening bracket candidates should not be empty", openCands.isNotEmpty())
        assertEquals("Primary opening bracket candidate should be full-width '（'", "（", openCands.first())
        assertTrue("Should contain '「'", openCands.contains("「"))
        assertTrue("Should contain '『'", openCands.contains("『"))
        assertTrue("Should contain '【'", openCands.contains("【"))
        assertTrue("Should contain pair '（）'", openCands.contains("（）"))
        assertTrue("Should contain pair '「」'", openCands.contains("「」"))
        assertTrue("Should contain '［'", openCands.contains("［"))
        assertTrue("Should contain '〔'", openCands.contains("〔"))
        assertTrue("Should contain '｛'", openCands.contains("｛"))
        assertTrue("Should contain '〈'", openCands.contains("〈"))
        assertTrue("Should contain '《'", openCands.contains("《"))
        assertTrue("Should contain '('", openCands.contains("("))

        // Flick 'や' right produces '）'
        val closeCands = engine.getBaseCandidates("）").map { it.surface }
        assertTrue("Closing bracket candidates should not be empty", closeCands.isNotEmpty())
        assertEquals("Primary closing bracket candidate should be full-width '）'", "）", closeCands.first())
        assertTrue("Should contain '」'", closeCands.contains("」"))
        assertTrue("Should contain '』'", closeCands.contains("』"))
        assertTrue("Should contain '】'", closeCands.contains("】"))
        assertTrue("Should contain '］'", closeCands.contains("］"))
        assertTrue("Should contain '〕'", closeCands.contains("〕"))
        assertTrue("Should contain '｝'", closeCands.contains("｝"))
        assertTrue("Should contain '〉'", closeCands.contains("〉"))
        assertTrue("Should contain '》'", closeCands.contains("》"))
        assertTrue("Should contain ')'", closeCands.contains(")"))

        // Reading 'かっこ' produces paired brackets
        val kakkoCands = engine.getBaseCandidates("かっこ").map { it.surface }
        assertTrue("Kakko candidates should not be empty", kakkoCands.isNotEmpty())
        assertTrue("Should contain '（）'", kakkoCands.contains("（）"))
        assertTrue("Should contain '「」'", kakkoCands.contains("「」"))
        assertTrue("Should contain '『』'", kakkoCands.contains("『』"))
        assertTrue("Should contain '【】'", kakkoCands.contains("【】"))
    }

    @Test
    fun testUserDictionaryInCompoundPhrases() {
        val entry = DictionaryEntry(word = "道子", reading = "みちこ", priority = Priority.NORMAL)
        engine.syncUserDictionary(listOf(entry))

        // 1. Exact match reading "みちこ" should prioritize "道子"
        val candsMichiko = engine.getBaseCandidates("みちこ")
        assertTrue("Candidates should not be empty for みちこ", candsMichiko.isNotEmpty())
        assertEquals("道子", candsMichiko.first().surface)
        assertEquals("user", candsMichiko.first().source)

        // 2. Compound phrase "みちこに" should prioritize "道子に" over homophone "美智子に"
        val candsMichikoni = engine.getBaseCandidates("みちこに")
        assertTrue("Candidates should not be empty for みちこに", candsMichikoni.isNotEmpty())
        val topMichikoni = candsMichikoni.first()
        assertEquals("道子に", topMichikoni.surface)
        assertEquals("user", topMichikoni.source)

        // 3. Other particles: "みちこは", "みちこが", "みちこと", "みちこから"
        val particles = listOf("は", "が", "と", "から", "で", "の", "も")
        for (p in particles) {
            val cands = engine.getBaseCandidates("みちこ$p")
            assertTrue("Candidates should not be empty for みちこ$p", cands.isNotEmpty())
            val top = cands.first()
            assertEquals("道子$p", top.surface)
            assertEquals("user", top.source)
        }

        // 4. In longer phrase: "きょうみちこにあいました"
        val candsLong = engine.getBaseCandidates("きょうみちこにあった")
        assertTrue("Candidates should not be empty for きょうみちこにあった", candsLong.isNotEmpty())
        val topLong = candsLong.first().surface
        assertTrue("Long sentence should contain user dict '道子' instead of '美智子': was $topLong",
            topLong.contains("道子") && !topLong.contains("美智子"))
    }

    @Test
    fun testNannoAndKouhonashiConversion() {
        // Test: "なんの" -> "何の"
        val candsNanno = engine.getBaseCandidates("なんの")
        assertTrue("Candidates should not be empty for なんの", candsNanno.isNotEmpty())
        assertTrue("Expected '何の' in candidates for 'なんの', was: ${candsNanno.map { it.surface }}",
            candsNanno.any { it.surface == "何の" })
        assertEquals("何の", candsNanno.first().surface)

        // Test: "こうほなし" -> "候補なし" / "候補無し"
        val candsKouho = engine.getBaseCandidates("こうほなし")
        assertTrue("Candidates should not be empty for こうほなし", candsKouho.isNotEmpty())
        assertTrue("Expected '候補なし' in candidates for 'こうほなし', was: ${candsKouho.map { it.surface }}",
            candsKouho.any { it.surface == "候補なし" })
        assertEquals("候補なし", candsKouho.first().surface)
    }
}
