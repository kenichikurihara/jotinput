package com.example.myasr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Comprehensive evaluation benchmark and test suite for Arabic numeral normalization
 * across measurement units, calendar years, dates, currency, counters, and negative idiom cases.
 */
class JapaneseNumeralEvaluationTest {

    data class TestCase(
        val category: String,
        val input: String,
        val expected: String
    )

    private val evaluationCases = listOf(
        // ==========================================
        // 1. Weight (重さ)
        // ==========================================
        TestCase("Weight", "重さは五キロです", "重さは5キロです"),
        TestCase("Weight", "五百グラムの牛肉を買う", "500グラムの牛肉を買う"),
        TestCase("Weight", "最大積載量は三トンです", "最大積載量は3トンです"),
        TestCase("Weight", "五十ミリグラムを処方する", "50ミリグラムを処方する"),
        TestCase("Weight", "体重は八十キログラムです", "体重は80キログラムです"),

        // ==========================================
        // 2. Length & Distance (長さ・距離)
        // ==========================================
        TestCase("Length", "長さは十メートルあります", "長さは10メートルあります"),
        TestCase("Length", "幅は三センチです", "幅は3センチです"),
        TestCase("Length", "五キロメートル走った", "5キロメートル走った"),
        TestCase("Length", "厚さは十五ミリです", "厚さは15ミリです"),
        TestCase("Length", "百メートル走の選手", "100メートル走の選手"),

        // ==========================================
        // 3. Calendar Year & Era (年号・西暦・年代)
        // ==========================================
        TestCase("Year/Era", "西暦二千二十六年", "西暦2026年"),
        TestCase("Year/Era", "二千二十四年の夏", "2024年の夏"),
        TestCase("Year/Era", "千九百九十九年の出来事", "1999年の出来事"),
        TestCase("Year/Era", "二千年代初頭の流行", "2000年代初頭の流行"),
        TestCase("Year/Era", "一九八〇年代の音楽", "1980年代の音楽"),
        TestCase("Year/Era", "昭和六十四年一月", "昭和64年1月"),
        TestCase("Year/Era", "平成三十年四月", "平成30年4月"),
        TestCase("Year/Era", "令和八年の予定", "令和8年の予定"),

        // ==========================================
        // 4. Time & Date (時間・日時)
        // ==========================================
        TestCase("Time/Date", "午後三時三十分の集合", "午後3時30分の集合"),
        TestCase("Time/Date", "十月二十五日", "10月25日"),
        TestCase("Time/Date", "五分前に出発した", "5分前に出発した"),
        TestCase("Time/Date", "二十四時間営業", "24時間営業"),
        TestCase("Time/Date", "三百六十五日年中無休", "365日年中無休"),
        TestCase("Time/Date", "一時間半のドライブ", "1時間半のドライブ"),
        TestCase("Time/Date", "十二月三十一日", "12月31日"),

        // ==========================================
        // 5. Temperature, Area & Volume (温度・面積・体積)
        // ==========================================
        TestCase("Physical", "気温は二十五度です", "気温は25度です"),
        TestCase("Physical", "体温は三十六度五分です", "体温は36度5分です"),
        TestCase("Physical", "百平方メートルの土地", "100平方メートルの土地"),
        TestCase("Physical", "二リットルの水筒", "2リットルの水筒"),
        TestCase("Physical", "五百ミリリットルの牛乳", "500ミリリットルの牛乳"),
        TestCase("Physical", "零点五秒の遅延", "0.5秒の遅延"),

        // ==========================================
        // 6. Money, Ratio & Counters (金額・割合・助数詞)
        // ==========================================
        TestCase("Money/Counter", "価格は千五百円です", "価格は1500円です"),
        TestCase("Money/Counter", "三万五千円の費用", "35000円の費用"),
        TestCase("Money/Counter", "百ドル札を両替する", "100ドル札を両替する"),
        TestCase("Money/Counter", "達成率は八十パーセントです", "達成率は80パーセントです"),
        TestCase("Money/Counter", "二割引きセール中", "2割引きセール中"),
        TestCase("Money/Counter", "りんごを三個買う", "りんごを3個買う"),
        TestCase("Money/Counter", "鉛筆が五本ある", "鉛筆が5本ある"),
        TestCase("Money/Counter", "用紙を二枚印刷する", "用紙を2枚印刷する"),
        TestCase("Money/Counter", "トラックが四台並ぶ", "トラックが4台並ぶ"),
        TestCase("Money/Counter", "ビルの八階に行く", "ビルの8階に行く"),
        TestCase("Money/Counter", "三丁目の五番地", "3丁目の5番地"),
        TestCase("Money/Counter", "参加者は五十人です", "参加者は50人です"),

        // ==========================================
        // 7. Negative Cases: Preserved Idioms & Fixed Terms (四字熟語・慣用句などの非変換)
        // ==========================================
        TestCase("Idiom/Negative", "一期一会を大切にする", "一期一会を大切にする"),
        TestCase("Idiom/Negative", "一石二鳥の効果を狙う", "一石二鳥の効果を狙う"),
        TestCase("Idiom/Negative", "四字熟語を覚える", "四字熟語を覚える"),
        TestCase("Idiom/Negative", "夜空に三日月が浮かぶ", "夜空に三日月が浮かぶ"),
        TestCase("Idiom/Negative", "七転び八起きの精神", "七転び八起きの精神"),
        TestCase("Idiom/Negative", "五里霧中の状態が続く", "五里霧中の状態が続く"),
        TestCase("Idiom/Negative", "九死に一生を得る", "九死に一生を得る"),
        TestCase("Idiom/Negative", "十人十色の個性", "十人十色の個性"),
        TestCase("Idiom/Negative", "一生懸命に頑張る", "一生懸命に頑張る"),
        TestCase("Idiom/Negative", "勝率は五分五分だ", "勝率は五分五分だ"),
        TestCase("Idiom/Negative", "二日酔いで頭が痛い", "二日酔いで頭が痛い"),
        TestCase("Idiom/Negative", "三角関係に悩む", "三角関係に悩む"),
        TestCase("Idiom/Negative", "千差万別の意見", "千差万別の意見"),
        TestCase("Idiom/Negative", "百発百中の命中率", "百発百中の命中率"),
        TestCase("Idiom/Negative", "万が一の事態に備える", "万が一の事態に備える"),
        TestCase("Idiom/Negative", "一般人の感覚から離れる", "一般人の感覚から離れる"),
        TestCase("Idiom/Negative", "予算の一部を使う", "予算の一部を使う")
    )

    @Test
    fun evaluateTextNormalizationDataset() {
        var passedCount = 0
        val failures = mutableListOf<String>()

        for (tc in evaluationCases) {
            val normalized = JapaneseNumeralUtils.normalizeNumeralsWithUnits(tc.input)
            if (normalized == tc.expected) {
                passedCount++
            } else {
                failures.add("[${tc.category}] Input: '${tc.input}' -> Got: '$normalized', Expected: '${tc.expected}'")
            }
        }

        val accuracy = (passedCount.toDouble() / evaluationCases.size) * 100.0
        println("=== NUMERAL NORMALIZATION EVALUATION REPORT ===")
        println("Total Evaluation Cases: ${evaluationCases.size}")
        println("Passed: $passedCount / ${evaluationCases.size}")
        println("Accuracy: ${String.format("%.2f", accuracy)}%")

        if (failures.isNotEmpty()) {
            println("--- FAILURES (${failures.size}) ---")
            failures.forEach { println("  $it") }
        }
        println("================================================")

        assertEquals("All ${evaluationCases.size} numeral test cases must pass 100%", 0, failures.size)
    }

    @Test
    fun evaluateImePhoneticNumeralConversion() {
        val imeTestCases = listOf(
            Pair("ごきろ", "5キロ"),
            Pair("ごひゃくぐらむ", "500グラム"),
            Pair("にせんにじゅうろくねん", "2026年"),
            Pair("さんじゅっぷん", "30分"),
            Pair("じゅっせんち", "10センチ"),
            Pair("じゅうめーとる", "10メートル"),
            Pair("ひゃくえん", "100円"),
            Pair("にじゅうごど", "25度"),
            Pair("ごじゅっぱーせんと", "50パーセント"),
            Pair("さんぼん", "3本"),
            Pair("ごこ", "5個"),
            Pair("にせんにじゅうよんねん", "2024年"),
            Pair("せんきゅうひゃくきゅうじゅうきゅうねん", "1999年"),
            Pair("にじゅうよじかん", "24時間"),
            Pair("さんびゃくろくじゅうごにち", "365日"),
            Pair("じゅうにがつ", "12月"),
            Pair("さんじゅうにち", "30日")
        )

        val tsvFile = listOf(
            File("src/test/resources/mozc/base_dictionary.tsv"),
            File("app/src/test/resources/mozc/base_dictionary.tsv"),
            File("tools/mozc_dict/base_dictionary.tsv"),
            File("../tools/mozc_dict/base_dictionary.tsv"),
            File("src/main/assets/mozc/base_dictionary.tsv"),
            File("app/src/main/assets/mozc/base_dictionary.tsv")
        ).firstOrNull { it.exists() }
        requireNotNull(tsvFile) { "base_dictionary.tsv not found for test" }

        val engine = KanaKanjiEngine(null)
        tsvFile.inputStream().use { stream ->
            engine.initializeWithStream(stream)
        }

        var passedIme = 0
        val imeFailures = mutableListOf<String>()

        for ((reading, expectedSurface) in imeTestCases) {
            val candidates = engine.getBaseCandidates(reading)
            val surfaces = candidates.map { it.surface }
            val topCandidates = surfaces.take(3)
            if (topCandidates.contains(expectedSurface)) {
                passedIme++
            } else {
                imeFailures.add("Reading: '$reading' -> Expected top candidate '$expectedSurface', but got: ${topCandidates.joinToString(", ")}")
            }
        }

        val imeAccuracy = (passedIme.toDouble() / imeTestCases.size) * 100.0
        println("=== IME NUMERAL CONVERSION EVALUATION REPORT ===")
        println("Total IME Cases: ${imeTestCases.size}")
        println("Passed: $passedIme / ${imeTestCases.size}")
        println("Accuracy: ${String.format("%.2f", imeAccuracy)}%")

        if (imeFailures.isNotEmpty()) {
            println("--- IME FAILURES (${imeFailures.size}) ---")
            imeFailures.forEach { println("  $it") }
        }
        println("================================================")

        assertEquals("All ${imeTestCases.size} IME phonetic cases must have Arabic numeral candidate in top 3", 0, imeFailures.size)
    }
}
