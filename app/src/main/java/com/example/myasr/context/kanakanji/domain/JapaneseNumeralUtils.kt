package com.example.myasr.context.kanakanji.domain

import java.util.regex.Pattern

/**
 * High-performance Japanese numeral normalization and candidate generation utility.
 *
 * Capabilities:
 * 1. Converts Kanji and full-width numerals followed by measurement units, calendar years,
 *    dates, times, currency, percentages, and counters into standard half-width Arabic numerals.
 * 2. Safely preserves Japanese idioms, four-character idioms (四字熟語), and fixed non-numeric phrases
 *    (e.g., 一期一会, 一石二鳥, 四字熟語, 三日月, 七転び八起き, 五里霧中, 十人十色, 一生懸命, etc.).
 * 3. Phonetically synthesizes Arabic numeral candidates for IME Kana-Kanji conversion
 *    (e.g., "ごきろ" -> "5キロ", "にせんにじゅうろくねん" -> "2026年", "さんじゅっぷん" -> "30分").
 */
object JapaneseNumeralUtils {

    // Preserved Japanese idioms and fixed expressions where Kanji numbers must NOT be converted
    private val PRESERVED_IDIOMS = setOf(
        "一期一会", "一石二鳥", "四字熟語", "三日月", "七転び八起き", "五里霧中",
        "九死に一生", "十人十色", "一生懸命", "五分五分", "二日酔い", "三角関係",
        "千差万別", "百発百中", "万が一", "一般人", "一部分", "一番", "一概に",
        "一息", "四方八方", "危機一髪", "一朝一夕", "三日坊主", "百聞は一見に如かず",
        "一刀両断", "一心同体", "一喜一憂", "二律背反", "三人寄れば文殊の知恵",
        "二者択一", "百人力", "千載一遇", "天下統一", "唯一無二", "四苦八苦",
        "七味唐辛子", "十全", "五十歩百歩", "一網打尽", "一進一退", "一心不乱",
        "一挙両得", "千里の道も一歩から", "一枚岩", "一大事", "一幕", "一抹",
        "一律", "一人前", "一人暮らし", "一目惚れ", "一面", "一理", "一周忌",
        "三本締め", "五重塔", "二枚目", "三枚目", "十八番", "三文判"
    )

    // Recognized measurement units, calendar/time suffixes, currency, and counter words (助数詞)
    private val UNIT_WORDS = listOf(
        // Weight
        "キログラム", "キロ", "グラム", "トン", "ミリグラム", "ポンド", "オンス", "kg", "g", "mg", "t",
        // Length & Distance
        "キロメートル", "メートル", "センチメートル", "センチ", "ミリメートル", "ミリ", "ヤード", "インチ", "フィート", "マイル", "km", "m", "cm", "mm",
        // Era / Year / Date
        "年代", "年", "月", "日",
        // Time
        "時間半", "時間", "時間目", "日間", "週間", "ヶ月", "ヵ月", "か月", "年間", "分間", "秒間", "時半", "時", "分", "秒",
        // Temperature
        "度", "℃", "℉", "カロリー", "キロカロリー", "kcal", "cal",
        // Area / Volume
        "平方キロメートル", "平方メートル", "平方センチメートル", "坪", "ヘクタール", "リットル", "ミリリットル", "シーシー", "cc", "L", "ml", "mL", "dl", "dL", "㎡", "㎢", "㎥",
        // Speed / Frequency
        "キロ/時", "km/h", "ヘルツ", "Hz", "kHz", "MHz", "GHz", "fps", "bps", "Mbps", "Gbps",
        // Currency
        "億円", "万円", "兆円", "円", "ドル", "ユーロ", "セント", "ポンド", "￥", "$",
        // Ratio & Percentage
        "パーセント", "％", "%", "割引き", "割引", "割",
        // Counters (助数詞)
        "個", "本", "枚", "台", "人", "匹", "頭", "冊", "通", "着", "軒", "棟", "階", "点", "票", "杯", "粒", "丁", "隻", "便", "件", "組", "足", "歳", "才", "回", "丁目", "番地", "番", "号", "勝", "敗", "戦", "話", "曲", "局", "発", "門"
    )

    // Regex matching Kanji or Full-width / Half-width digits followed by recognized units
    // Sorted by unit length descending so longer compound units match first (e.g. 平方メートル before メートル)
    private val SORTED_UNITS = UNIT_WORDS.sortedByDescending { it.length }
    private val UNIT_REGEX_PATTERN = run {
        val unitGroup = SORTED_UNITS.joinToString("|") { Pattern.quote(it) }
        // Match numbers (including decimals with 点) directly followed by a unit
        Pattern.compile("([一二三四五六七八九〇零壱弐参十拾百千万億兆０-９0-9]+(?:点[一二三四五六七八九〇零０-９0-9]+)?)($unitGroup)")
    }

    /**
     * Normalizes Kanji and full-width numerals followed by units into Arabic numerals,
     * while preserving idioms and fixed expressions.
     */
    fun normalizeNumeralsWithUnits(text: String): String {
        if (text.isBlank()) return text

        // Check if entire text or substrings match preserved idioms
        for (idiom in PRESERVED_IDIOMS) {
            if (text == idiom) {
                return text
            }
        }

        // We temporarily protect preserved idioms from replacement by masking them
        var maskedText = text
        val masks = mutableListOf<Pair<String, String>>()
        for ((idx, idiom) in PRESERVED_IDIOMS.withIndex()) {
            if (maskedText.contains(idiom)) {
                val placeholder = "\uE000_${idx}_\uE001"
                masks.add(Pair(placeholder, idiom))
                maskedText = maskedText.replace(idiom, placeholder)
            }
        }

        val matcher = UNIT_REGEX_PATTERN.matcher(maskedText)
        val sb = StringBuffer()
        while (matcher.find()) {
            val numStr = matcher.group(1) ?: ""
            val unitStr = matcher.group(2) ?: ""

            val arabicNum = convertKanjiOrFullWidthToArabic(numStr)
            if (arabicNum != null) {
                matcher.appendReplacement(sb, MatcherQuoteReplacement(arabicNum + unitStr))
            } else {
                matcher.appendReplacement(sb, MatcherQuoteReplacement(matcher.group(0) ?: ""))
            }
        }
        matcher.appendTail(sb)

        var result = sb.toString()
        // Restore protected idioms
        for ((placeholder, idiom) in masks) {
            result = result.replace(placeholder, idiom)
        }
        return result
    }

    private fun MatcherQuoteReplacement(s: String): String {
        return java.util.regex.Matcher.quoteReplacement(s)
    }

    /**
     * Converts a Kanji or full-width numeral string (with optional decimal point '点')
     * into a standard half-width Arabic numeral string.
     */
    fun convertKanjiOrFullWidthToArabic(numStr: String): String? {
        if (numStr.isEmpty()) return null

        if (numStr.contains("点")) {
            val parts = numStr.split("点", limit = 2)
            val integerVal = parseKanjiNumber(parts[0]) ?: return null
            val fractionalDigits = parts[1].map { kanjiDigitToChar(it) }
            if (fractionalDigits.any { it == null }) return null
            return "$integerVal.${fractionalDigits.joinToString("")}"
        }

        val parsedVal = parseKanjiNumber(numStr)
        return parsedVal?.toString()
    }

    private fun kanjiDigitToChar(ch: Char): Char? {
        return when (ch) {
            in '0'..'9' -> ch
            in '０'..'９' -> '0' + (ch - '０')
            '〇', '零' -> '0'
            '一', '壱' -> '1'
            '二', '弐' -> '2'
            '三', '参' -> '3'
            '四' -> '4'
            '五' -> '5'
            '六' -> '6'
            '七' -> '7'
            '八' -> '8'
            '九' -> '9'
            else -> null
        }
    }

    /**
     * Parses a Japanese Kanji numeral string (both positional and multiplicative notation) into a Long.
     */
    fun parseKanjiNumber(s: String): Long? {
        if (s.isEmpty()) return null

        // 1. Check if it's already purely digits
        if (s.all { it in '0'..'9' }) {
            return s.toLongOrNull()
        }

        // 2. Normalize full-width digits and individual Kanji digits
        val normalized = s.map { ch ->
            when (ch) {
                in '０'..'９' -> '0' + (ch - '０')
                '〇', '零' -> '0'
                '一', '壱' -> '1'
                '二', '弐' -> '2'
                '三', '参' -> '3'
                '四' -> '4'
                '五' -> '5'
                '六' -> '6'
                '七' -> '7'
                '八' -> '8'
                '九' -> '9'
                else -> ch
            }
        }.joinToString("")

        // If purely digits after digit normalization (e.g. "二〇二六" -> "2026", "一九九九" -> "1999")
        if (normalized.all { it in '0'..'9' }) {
            return normalized.toLongOrNull()
        }

        // 3. Multiplicative place-value parsing (十, 百, 千, 万, 億, 兆)
        var total = 0L
        var currentManPart = 0L
        var currentSmallNum = 0L

        for (ch in normalized) {
            when (ch) {
                in '0'..'9' -> {
                    val digit = (ch - '0').toLong()
                    currentSmallNum = currentSmallNum * 10 + digit
                }
                '十', '拾' -> {
                    val factor = if (currentSmallNum == 0L) 1L else currentSmallNum
                    currentManPart += factor * 10L
                    currentSmallNum = 0L
                }
                '百' -> {
                    val factor = if (currentSmallNum == 0L) 1L else currentSmallNum
                    currentManPart += factor * 100L
                    currentSmallNum = 0L
                }
                '千' -> {
                    val factor = if (currentSmallNum == 0L) 1L else currentSmallNum
                    currentManPart += factor * 1000L
                    currentSmallNum = 0L
                }
                '万' -> {
                    currentManPart += currentSmallNum
                    total += (if (currentManPart == 0L) 1L else currentManPart) * 10000L
                    currentManPart = 0L
                    currentSmallNum = 0L
                }
                '億' -> {
                    currentManPart += currentSmallNum
                    total += (if (currentManPart == 0L) 1L else currentManPart) * 100000000L
                    currentManPart = 0L
                    currentSmallNum = 0L
                }
                '兆' -> {
                    currentManPart += currentSmallNum
                    total += (if (currentManPart == 0L) 1L else currentManPart) * 1000000000000L
                    currentManPart = 0L
                    currentSmallNum = 0L
                }
                else -> return null
            }
        }
        currentManPart += currentSmallNum
        total += currentManPart
        return total
    }

    // =========================================================================
    // IME Phonetic Kana Numeral Parsing & Candidate Generation
    // =========================================================================

    private val KANA_UNITS = mapOf(
        // Weight
        "きろ" to "キロ",
        "きろぐらむ" to "キログラム",
        "ぐらむ" to "グラム",
        "とん" to "トン",
        "みりぐらむ" to "ミリグラム",
        // Length
        "きろめーとる" to "キロメートル",
        "めーとる" to "メートル",
        "せんちめーとる" to "センチメートル",
        "せんち" to "センチ",
        "みりめーとる" to "ミリメートル",
        "みり" to "ミリ",
        // Year / Date
        "ねんだい" to "年代",
        "ねん" to "年",
        "がつ" to "月",
        "にち" to "日",
        // Time
        "じかん" to "時間",
        "にちかん" to "日間",
        "しゅうかん" to "週間",
        "かげつ" to "か月",
        "ねんかん" to "年間",
        "びょう" to "秒",
        "じ" to "時",
        "ふん" to "分",
        "ぷん" to "分",
        // Currency
        "えん" to "円",
        "どる" to "ドル",
        // Temperature
        "ど" to "度",
        // Area / Volume
        "へいほうめーとる" to "平方メートル",
        "りっとる" to "リットル",
        "みりりっとる" to "ミリリットル",
        // Ratio
        "ぱーせんと" to "パーセント",
        "わり" to "割",
        // Counters
        "こ" to "個",
        "ほん" to "本",
        "ぼん" to "本",
        "ぽん" to "本",
        "まい" to "枚",
        "だい" to "台",
        "さつ" to "冊",
        "つう" to "通",
        "かい" to "回",
        "がい" to "階",
        "にん" to "人",
        "ひき" to "匹",
        "びき" to "匹",
        "ぴき" to "匹",
        "さい" to "歳"
    )

    private val SORTED_KANA_UNITS = KANA_UNITS.keys.sortedByDescending { it.length }

    /**
     * Synthesizes Arabic numeral candidates from phonetic Kana readings
     * (e.g. "ごきろ" -> ["5キロ"], "にせんにじゅうろくねん" -> ["2026年"]).
     */
    fun generateNumeralCandidates(reading: String): List<ConversionCandidate> {
        if (reading.length < 2) return emptyList()

        val results = mutableListOf<ConversionCandidate>()

        // Try matching a unit suffix from the end of the reading
        for (kanaUnit in SORTED_KANA_UNITS) {
            if (reading.endsWith(kanaUnit) && reading.length > kanaUnit.length) {
                val numPart = reading.substring(0, reading.length - kanaUnit.length)
                val parsedNum = parseKanaToNumber(numPart) ?: continue
                val unitSurface = KANA_UNITS[kanaUnit] ?: kanaUnit

                // Primary half-width Arabic candidate
                results.add(
                    ConversionCandidate(
                        surface = "$parsedNum$unitSurface",
                        reading = reading,
                        isPrediction = false,
                        source = "numeral"
                    )
                )

                // Optional percentage symbol shortcut for パーセント
                if (kanaUnit == "ぱーせんと") {
                    results.add(
                        ConversionCandidate(
                            surface = "$parsedNum%",
                            reading = reading,
                            isPrediction = false,
                            source = "numeral"
                        )
                    )
                }

                // If found, stop checking shorter unit prefixes
                break
            }
        }

        return results
    }

    /**
     * Parses phonetic Kana numerals (up to 9999) into an integer.
     */
    fun parseKanaToNumber(s: String): Long? {
        if (s.isEmpty()) return null

        var rem = s
        var total = 0L

        // 1. Thousands (千の位)
        val thousandsMap = listOf(
            Pair("きゅうせん", 9000L),
            Pair("はっせん", 8000L),
            Pair("ななせん", 7000L),
            Pair("ろくせん", 6000L),
            Pair("ごせん", 5000L),
            Pair("よんせん", 4000L),
            Pair("さんぜん", 3000L),
            Pair("にせん", 2000L),
            Pair("いっせん", 1000L),
            Pair("せん", 1000L)
        )
        for ((k, v) in thousandsMap) {
            if (rem.startsWith(k)) {
                total += v
                rem = rem.substring(k.length)
                break
            }
        }

        // 2. Hundreds (百の位)
        val hundredsMap = listOf(
            Pair("きゅうひゃく", 900L),
            Pair("はっぴゃく", 800L),
            Pair("ななひゃく", 700L),
            Pair("ろっぴゃく", 600L),
            Pair("ごひゃく", 500L),
            Pair("よんひゃく", 400L),
            Pair("さんびゃく", 300L),
            Pair("にひゃく", 200L),
            Pair("いっぴゃく", 100L),
            Pair("ひゃく", 100L)
        )
        for ((k, v) in hundredsMap) {
            if (rem.startsWith(k)) {
                total += v
                rem = rem.substring(k.length)
                break
            }
        }

        // 3. Tens (十の位)
        val tensMap = listOf(
            Pair("きゅうじゅう", 90L), Pair("きゅうじゅっ", 90L), Pair("きゅうじっ", 90L),
            Pair("はちじゅう", 80L), Pair("はちじゅっ", 80L), Pair("はちじっ", 80L),
            Pair("ななじゅう", 70L), Pair("ななじゅっ", 70L), Pair("ななじっ", 70L),
            Pair("ろくじゅう", 60L), Pair("ろくじゅっ", 60L), Pair("ろくじっ", 60L),
            Pair("ごじゅう", 50L), Pair("ごじゅっ", 50L), Pair("ごじっ", 50L),
            Pair("よんじゅう", 40L), Pair("よんじゅっ", 40L), Pair("よんじっ", 40L),
            Pair("さんじゅう", 30L), Pair("さんじゅっ", 30L), Pair("さんじっ", 30L),
            Pair("にじゅう", 20L), Pair("にじゅっ", 20L), Pair("にじっ", 20L),
            Pair("じゅう", 10L), Pair("じゅっ", 10L), Pair("じっ", 10L)
        )
        for ((k, v) in tensMap) {
            if (rem.startsWith(k)) {
                total += v
                rem = rem.substring(k.length)
                break
            }
        }

        // 4. Units (一の位)
        val unitsMap = listOf(
            Pair("きゅう", 9L), Pair("く", 9L),
            Pair("はち", 8L), Pair("はっ", 8L),
            Pair("なな", 7L), Pair("しち", 7L),
            Pair("ろく", 6L), Pair("ろっ", 6L),
            Pair("ご", 5L),
            Pair("よん", 4L), Pair("し", 4L), Pair("よ", 4L),
            Pair("さん", 3L),
            Pair("に", 2L),
            Pair("いち", 1L), Pair("いっ", 1L),
            Pair("ぜろ", 0L), Pair("れい", 0L)
        )
        for ((k, v) in unitsMap) {
            if (rem.startsWith(k)) {
                total += v
                rem = rem.substring(k.length)
                break
            }
        }

        // If all characters in rem were parsed, return total
        if (rem.isEmpty() && total > 0L) {
            return total
        }

        return null
    }
}
