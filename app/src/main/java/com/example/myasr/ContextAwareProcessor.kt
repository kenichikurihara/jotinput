package com.example.myasr

import android.util.Log
import java.util.regex.Pattern

/**
 * ContextAwareProcessor: Enhances speech recognition output using preceding text box context (textBeforeCursor).
 *
 * Capabilities:
 * 1. Disambiguates Kanji orthographic variants (e.g. 渡辺 -> 渡邊, 斉藤 -> 齋藤, 高橋 -> 髙橋)
 *    when established by the surrounding context or custom dictionary.
 * 2. Resolves semantic homophones (e.g. 貴社 vs 記者 vs 帰社, 科学 vs 化学, 回答 vs 解答)
 *    based on domain keywords in textBeforeCursor.
 * 3. Enforces user dictionary terms and surface forms.
 * 4. Applies smart boundary formatting (e.g. spacing between Latin words).
 */
class ContextAwareProcessor(
    private val kanaEngineProvider: (() -> KanaKanjiEngine?)? = null
) {
    private val TAG = "ContextAwareProcessor"

    private fun getKanaEngine(): KanaKanjiEngine? {
        kanaEngineProvider?.invoke()?.let { return it }
        return try {
            AsrApp.instanceOrNull?.kanaKanjiEngine
        } catch (e: Throwable) {
            null
        }
    }

    // Common Japanese surname & orthographic variants: mapping common baseline model output -> rare variants
    private val KANJI_VARIANTS: Map<String, List<String>> = mapOf(
        "渡辺" to listOf("渡邊", "渡邉"),
        "斎藤" to listOf("齋藤", "齊藤"),
        "斉藤" to listOf("齋藤", "齊藤"),
        "高橋" to listOf("髙橋"),
        "山崎" to listOf("山﨑"),
        "佐々木" to listOf("佐佐木"),
        "広瀬" to listOf("廣瀬"),
        "島田" to listOf("嶋田"),
        "桜井" to listOf("櫻井"),
        "浜田" to listOf("濱田"),
        "黒田" to listOf("黑田")
    )

    // Semantic cue keywords for homophone disambiguation
    private val HOMOPHONE_CUES: Map<String, List<String>> = mapOf(
        "貴社" to listOf("お世話", "御中", "ご発展", "平素", "拝読", "案件", "取引", "請求", "見積", "契約", "提案"),
        "記者" to listOf("新聞", "記事", "取材", "テレビ", "会見", "報道", "コラム", "雑誌", "出版", "メディア", "朝刊"),
        "帰社" to listOf("出張", "会社", "外出", "戻り", "営業", "電車", "移動", "戻る", "オフィス"),
        "汽車" to listOf("蒸気", "機関車", "線路", "博物館", "煙", "レール"),
        "化学" to listOf("実験", "新素材", "合成", "薬品", "高分子", "有機", "無機", "分子", "反応", "試薬"),
        "科学" to listOf("宇宙", "素粒子", "自然", "学術", "ドキュメンタリー", "先端", "技術"),
        "市立" to listOf("市役所", "市民", "地域", "自治体", "公立", "市長"),
        "私立" to listOf("学校法人", "受験", "独自", "学費", "高校", "大学", "進学"),
        "回答" to listOf("アンケート", "集計", "顧客満足度", "問い合わせ", "窓口", "返信", "回答"),
        "解答" to listOf("試験", "採点", "正答", "過去問", "模範", "問題集", "テスト"),
        "異動" to listOf("辞令", "組織改編", "人事", "四半期", "部署", "配属", "昇進"),
        "移動" to listOf("新幹線", "在来線", "交通", "デスク", "レイアウト", "歩行", "所要時間")
    )

    /**
     * Process raw ASR text using preceding text box context and active dictionary entries.
     */
    fun process(
        rawText: String,
        contextBefore: String,
        entries: List<DictionaryEntry>,
        formatPunctuation: Boolean = true
    ): String {
        if (rawText.isBlank()) return rawText

        var result = rawText
        val activeEntries = entries.filter { it.isEnabled && it.word.isNotBlank() }
        val protectedReplacements = mutableListOf<String>()

        // Step 1: Context-Driven Kanji Variant Disambiguation
        result = resolveKanjiVariants(result, contextBefore, activeEntries)

        // Step 2: Context-Driven Semantic Homophone Disambiguation
        result = resolveSemanticHomophones(result, contextBefore, activeEntries, protectedReplacements)

        // Step 3: Context-Driven Person Name Resolution (e.g. 健一さん -> 賢一さん when 賢一 is in context)
        result = resolveContextualPersonNames(result, contextBefore, activeEntries, protectedReplacements)

        // Step 4: Context-Driven Tech Term Disambiguation (e.g. グラフ九L -> GraphQL when GraphQL is in context)
        result = resolveContextualTechTerms(result, contextBefore, activeEntries, protectedReplacements)

        // Step 5: Highest-Priority User Dictionary Substitution (Phonetic Kana, Character Variants & Homophones)
        result = applyDictionarySubstitutions(result, activeEntries, protectedReplacements)

        // Step 6: Latin & Brand Case Normalization
        result = applyAlphanumericNormalization(result, activeEntries)

        // Step 6.5: Arabic Numeral Normalization for Units & Quantities (e.g. 五キロ -> 5キロ, 西暦二千二十六年 -> 西暦2026年)
        result = JapaneseNumeralUtils.normalizeNumeralsWithUnits(result)

        // Step 7: Smart Spacing with Preceding Context
        result = formatBoundarySpacing(result, contextBefore)

        // Step 8: Spoken Punctuation Commands & Natural Japanese Punctuation Restoration (まる/てん/文末/読点)
        if (formatPunctuation) {
            result = JapanesePunctuationEngine.formatPunctuation(result)
        }

        if (result != rawText) {
            Log.d(TAG, "Context-Aware processed: '$rawText' -> '$result' (ctx len=${contextBefore.length})")
        }
        return result
    }

    private fun resolveKanjiVariants(
        text: String,
        contextBefore: String,
        entries: List<DictionaryEntry>
    ): String {
        var res = text
        for (entry in entries) {
            val target = entry.word.trim()
            // Check if this dictionary entry is one of the rare variants
            for ((baselineVariant, rareVariants) in KANJI_VARIANTS) {
                if (rareVariants.contains(target) && res.contains(baselineVariant)) {
                    // If target variant is in context, or context has honorific/title cues for people
                    val inContext = contextBefore.contains(target)
                    val hasPersonCue = contextBefore.contains("さん") || contextBefore.contains("様") ||
                            contextBefore.contains("リーダー") || contextBefore.contains("担当") ||
                            contextBefore.contains("部") || contextBefore.contains("氏") ||
                            contextBefore.contains("先生")

                    if (inContext || hasPersonCue || entry.priority == Priority.HIGH) {
                        res = res.replace(baselineVariant, target)
                    }
                }
            }
        }
        return res
    }

    private fun resolveSemanticHomophones(
        text: String,
        contextBefore: String,
        entries: List<DictionaryEntry>,
        protectedReplacements: MutableList<String>
    ): String {
        if (contextBefore.isBlank()) return text
        var res = text

        for (entry in entries) {
            val target = entry.word.trim()
            val cues = HOMOPHONE_CUES[target] ?: continue

            // Count cue keyword matches in context
            val cueMatchCount = cues.count { contextBefore.contains(it) }
            if (cueMatchCount > 0) {
                // If a competing homophone with the same reading is present in the text, replace it
                val normalizedReading = JapaneseKanaUtils.normalizeReading(entry.reading)
                val competitors = HOMOPHONE_CUES.keys.filter {
                    it != target && JapaneseKanaUtils.normalizeReading(it) == normalizedReading
                }

                for (comp in competitors) {
                    if (res.contains(comp)) {
                        val placeholder = "\uE002_${protectedReplacements.size}_\uE003"
                        protectedReplacements.add(target)
                        res = res.replace(comp, placeholder)
                    }
                }

                // Also replace phonetic Kana reading if present
                if (normalizedReading.isNotEmpty() && res.contains(normalizedReading)) {
                    val placeholder = "\uE002_${protectedReplacements.size}_\uE003"
                    protectedReplacements.add(target)
                    res = res.replace(normalizedReading, placeholder)
                }
            }
        }
        return res
    }

    private fun resolveContextualPersonNames(
        text: String,
        contextBefore: String,
        entries: List<DictionaryEntry>,
        protectedReplacements: MutableList<String>
    ): String {
        if (contextBefore.isBlank()) return text
        var res = text

        val honorifics = listOf("さん", "様", "くん", "君", "氏", "ちゃん")
        for (entry in entries) {
            val target = entry.word.trim()
            if (target.length in 2..4 && contextBefore.contains(target)) {
                // If the target person's name is in context, look for competing kanji names with same length followed by honorific
                for (hon in honorifics) {
                    val pattern = Pattern.compile("([\\p{IsHan}]{${target.length}})$hon")
                    val matcher = pattern.matcher(res)
                    if (matcher.find()) {
                        val competitor = matcher.group(1)
                        if (competitor != null && competitor != target) {
                            val placeholder = "\uE002_${protectedReplacements.size}_\uE003"
                            protectedReplacements.add("$target$hon")
                            res = res.replace("$competitor$hon", placeholder)
                        }
                    }
                }
            }
        }
        return res
    }

    private val TECH_PHONETIC_VARIANTS: Map<String, List<String>> = mapOf(
        "GraphQL" to listOf("グラフ九L", "グラフ9L", "グラフキュール"),
        "Apache" to listOf("アパッシュ", "アパッチ"),
        "Datadog" to listOf("データドック", "データドッグ"),
        "Ollama" to listOf("オーラマ", "オラマ"),
        "TensorRT" to listOf("テンソルRT", "テンソルアールティー"),
        "WebSocket" to listOf("Webソケット", "ウェブソケット"),
        "CI/CD" to listOf("CICD", "シーアイシーディー"),
        "Tokenization" to listOf("トークニゼーション"),
        "Quantization" to listOf("空アンチゼーション", "クオンタイゼーション")
    )

    private fun resolveContextualTechTerms(
        text: String,
        contextBefore: String,
        entries: List<DictionaryEntry>,
        protectedReplacements: MutableList<String>
    ): String {
        if (contextBefore.isBlank()) return text
        var res = text

        for (entry in entries) {
            val target = entry.word.trim()
            if (contextBefore.contains(target)) {
                val variants = TECH_PHONETIC_VARIANTS[target]
                if (variants != null) {
                    for (variant in variants) {
                        if (res.contains(variant)) {
                            val placeholder = "\uE002_${protectedReplacements.size}_\uE003"
                            protectedReplacements.add(target)
                            res = res.replace(variant, placeholder)
                        }
                    }
                }
            }
        }
        return res
    }

    /**
     * Channel 2 Deterministic Phonetic Substitution (ADR-004):
     * Replaces phonetic Kana readings (Hiragana & Katakana) emitted by ASR
     * with the user's registered dictionary entry with HIGHEST priority.
     * Protects replaced tokens using placeholder tokens to prevent nested corruptions.
     */
    private fun applyDictionarySubstitutions(
        text: String,
        entries: List<DictionaryEntry>,
        protectedReplacements: MutableList<String>
    ): String {
        if (text.isBlank() || entries.isEmpty()) return text

        var res = text
        val sortedEntries = entries
            .filter { it.isEnabled && it.isJapaneseEntry && it.word.isNotBlank() }
            .sortedWith(
                compareByDescending<DictionaryEntry> { it.priority.score }
                    .thenByDescending { maxOf(it.reading.length, it.word.length) }
            )

        for (entry in sortedEntries) {
            val target = entry.word.trim()
            val hiragana = JapaneseKanaUtils.normalizeReading(entry.reading)
            val katakana = JapaneseKanaUtils.hiraganaToKatakana(hiragana)

            val phoneticReadings = LinkedHashSet<String>()

            // Phonetic Kana readings (Hiragana & Katakana)
            if (hiragana.isNotEmpty() && hiragana != target) {
                phoneticReadings.add(hiragana)
            }
            if (katakana.isNotEmpty() && katakana != target) {
                phoneticReadings.add(katakana)
            }

            // Longest phonetic match first
            val sortedReadings = phoneticReadings.sortedByDescending { it.length }
            for (kana in sortedReadings) {
                if (res.contains(kana)) {
                    val placeholder = "\uE002_${protectedReplacements.size}_\uE003"
                    protectedReplacements.add(target)
                    res = res.replace(kana, placeholder)
                }
            }
        }

        // Restore all protected placeholders
        for (i in protectedReplacements.indices) {
            val placeholder = "\uE002_${i}_\uE003"
            res = res.replace(placeholder, protectedReplacements[i])
        }

        return res
    }

    private fun applyAlphanumericNormalization(
        text: String,
        entries: List<DictionaryEntry>
    ): String {
        var res = text
        for (entry in entries) {
            val target = entry.word.trim()
            if (target.any { it in 'a'..'z' || it in 'A'..'Z' }) {
                val pattern = Pattern.compile(Pattern.quote(target), Pattern.CASE_INSENSITIVE)
                res = pattern.matcher(res).replaceAll(target)
            }
        }
        return res
    }

    private fun formatBoundarySpacing(text: String, contextBefore: String): String {
        if (contextBefore.isEmpty() || text.isEmpty()) return text

        val lastChar = contextBefore.last()
        val firstChar = text.first()

        // If context ends in ASCII alphanumeric and text starts with ASCII alphanumeric, insert space
        if (lastChar.isLetterOrDigit() && lastChar < 128.toChar() &&
            firstChar.isLetterOrDigit() && firstChar < 128.toChar()) {
            return " $text"
        }

        return text
    }
}
