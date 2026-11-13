package com.example.myasr.context.speech.domain

import java.util.regex.Pattern

/**
 * Acoustic & prosodic boundary metadata associated with speech audio and cursor context.
 */
data class ProsodyBoundaryMetadata(
    val trailingPauseMs: Long = 0L,              // 無音ポーズ長 (ms)
    val isPacingForcedFlush: Boolean = false,     // 2400ms連続発話ペーシングによる分割か
    val terminalPitchSlopeSemitones: Float = 0f, // 末尾200msのF0ピッチ勾配 (半音単位, >= +3.0 で疑問符)
    val precedingCommittedTail: String = ""      // カーソル直前の確定済みテキスト末尾
)

/**
 * Result of punctuation formatting, including text and retroactive edit instructions.
 */
data class PunctuationFormatResult(
    val formattedText: String,
    val deletePrecedingChars: Int = 0            // 直前チャンク末尾の「。」等を遡及削除する文字数
)

/**
 * High-performance, ASR-model-agnostic Japanese punctuation restoration engine.
 *
 * Capabilities:
 * 1. ASR Output Canonicalizer (Stage 0):
 *    - Normalizes half/full-width comma/period artifacts (,, ., ，, ．, ...) across CTC/Whisper/Seq2Seq models.
 *    - Collapses unnatural CJK whitespace while preserving alphanumeric/Latin boundaries.
 * 2. Spoken Punctuation Commands (音声記号コマンド):
 *    - "まる" / "くてん" -> "。"
 *    - "てん" / "とうてん" -> "、"
 *    - "かいぎょう" -> "\n"
 *    - "はてな" / "ぎもんふ" -> "？"
 *    - "びっくりまーく" / "かんたんふ" -> "！"
 *    - Strict context validation to prevent false positives (e.g. てんき, まるで, 満点, 交差点, 一丸, 弾丸).
 * 3. Cross-Chunk Retroactive Boundary Linker (Stage 2):
 *    - Links continuous speech chunks by retroactively eliminating premature periods ("。")
 *      when followed by subordinate particles (e.g. "確認しました。" + "が..." -> "確認しましたが、...").
 * 4. Automatic 句点 (Period: '。') & 疑問符 ('？') Insertion (Stage 3):
 *    - Grammatical sentence-ending copulas & auxiliary verbs (です, ます, でした, ました, ません, である, だ).
 *    - Sentence-final particles (ね, よ, よね, わ, かな, なあ).
 *    - Polite request forms (ください, してください, お願いします).
 *    - Conversational greetings & closures (お疲れ様です, ありがとうございます, こんにちは, 失礼します).
 *    - Intonation-aware question mark promotion (terminal pitch slope >= +3.0 semitones).
 *    - Noun endings with full pauses (体言止め + >=500ms silence).
 * 5. Automatic 読点 (Comma: '、') Spacing & Rhythm Budget (Stage 4):
 *    - Subordinate conjunctive particles (ので, から, けど, けれど, けれども, のに, たら, なら).
 *    - Connective sentence/clause starters (しかし, そして, また, ただし, そのため, したがって, さらに, ちなみに, ところで, さて, まず, 次に, 最後に, 実は, もちろん).
 *    - Anti-overpunctuation distance budget (<5 chars) and long-span breathing comma (>=18 chars).
 * 6. Sentence Boundary Detection & Progressive Committing.
 */
object JapanesePunctuationEngine {

    // Words that contain "まる" or "てん" as sub-morphemes and must NOT be replaced with punctuation
    private val PRESERVED_WORDS = setOf(
        "てんき", "天気", "てんねん", "天然", "てんさい", "天才", "てんぼう", "展望",
        "てんとう", "点灯", "てんかい", "展開", "てんすう", "点数", "てんち", "天地",
        "てんけん", "点検", "てんぷ", "添付", "てんぽ", "店舗", "てんかん", "転換",
        "てんし", "天使", "てんごく", "天国", "てんぷら", "天ぷら", "てんどん", "天丼",
        "じてんしゃ", "自転車", "うんてん", "運転", "まんてん", "満点", "こうさてん", "交差点",
        "きってん", "起点", "しゅうてん", "終点", "じゅうてん", "重点", "ろんてん", "論点",
        "かんてん", "観点", "してん", "視点", "しょうてん", "焦点", "けってん", "欠点",
        "りてん", "利点", "とくてん", "得点", "さいてん", "採点", "げんてん", "原点", "減点",
        "まるで", "まるごと", "丸ごと", "まるい", "丸い", "まるみ", "丸み", "まるまる",
        "日の丸", "丸投げ", "丸出し", "丸焼き", "丸太", "丸一日", "丸見え", "丸腰", "丸刈り",
        "弾丸", "一丸", "本丸", "丸文字",
        "ですから", "ですので", "ですが", "ですし", "ますます", "益々", "ますと"
    )

    /**
     * Formats raw speech text by converting spoken punctuation commands and inserting
     * natural commas ('、') and periods ('。').
     */
    fun formatPunctuation(text: String): String {
        return formatPunctuation(text, metadata = null).formattedText
    }

    /**
     * Formats raw speech text using ASR-model-agnostic canonicalization, cross-chunk retroactive linking,
     * prosody metadata (pause duration & F0 pitch slope), and rhythm-aware punctuation insertion.
     */
    fun formatPunctuation(
        text: String,
        metadata: ProsodyBoundaryMetadata? = null
    ): PunctuationFormatResult {
        if (text.isBlank()) return PunctuationFormatResult(text, 0)

        val leadingSpaces = text.takeWhile { it.isWhitespace() }
        var result = text.trim()

        // Stage 0: Canonicalize ASR variations (half-width, full-width, CJK whitespace, dots)
        result = canonicalizeAsrArtifacts(result)

        // Stage 1: Spoken Punctuation Commands with PRESERVED_WORDS protection
        result = convertSpokenPunctuationCommands(result)

        // Stage 2: Cross-Chunk Retroactive Boundary Linker
        val (linkedText, deletePreceding) = applyCrossChunkRetroactiveLink(result, metadata)
        result = linkedText

        // Stage 3: Automatic Sentence Endings (句点 '。' and 疑問符 '？' with F0 pitch contour)
        result = insertSentenceEndingPeriods(result, metadata)

        // Stage 4: Automatic Clause Boundaries (読点 '、') with distance/rhythm budget
        result = insertClauseCommas(result, metadata)

        // Stage 5: Cleanup redundant or adjacent punctuation marks
        result = cleanupPunctuation(result)

        return PunctuationFormatResult(
            formattedText = leadingSpaces + result,
            deletePrecedingChars = deletePreceding
        )
    }

    /**
     * Checks if the given text ends with a completed sentence boundary ('。', '？', '！', '\n' or terminal predicate).
     */
    fun isSentenceEnding(text: String, metadata: ProsodyBoundaryMetadata? = null): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        val lastChar = trimmed.last()
        if (lastChar == '。' || lastChar == '？' || lastChar == '！' || lastChar == '\n') {
            return true
        }

        val terminalEndings = listOf(
            "です", "ます", "でした", "ました", "ません", "ませんでした", "ましょう", "でしょう",
            "である", "であった", "ください", "してください", "お願いします", "いたします",
            "ね", "よ", "よね", "かな", "わ", "ありがとう", "ありがとうございました", "お疲れ様でした", "お疲れ様です"
        )
        if (terminalEndings.any { trimmed.endsWith(it) }) return true

        // Pause >= 500ms allows noun-ending sentence boundaries (体言止め)
        if (metadata != null && metadata.trailingPauseMs >= 500L) {
            val nounEndings = listOf("以上", "件", "予定", "報告", "完了", "終了", "結果", "方針", "対策")
            if (nounEndings.any { trimmed.endsWith(it) }) return true
        }

        return false
    }

    /**
     * Splits a text into completed sentences and in-flight trailing text.
     * @return Pair(completedText, inFlightText)
     */
    fun splitCompletedSentences(text: String): Pair<String, String> {
        val lastBoundaryIndex = maxOf(
            text.lastIndexOf('。'),
            text.lastIndexOf('？'),
            text.lastIndexOf('！'),
            text.lastIndexOf('\n')
        )

        return if (lastBoundaryIndex >= 0) {
            val completed = text.substring(0, lastBoundaryIndex + 1)
            val inFlight = text.substring(lastBoundaryIndex + 1).trimStart()
            Pair(completed, inFlight)
        } else {
            Pair("", text)
        }
    }

    /**
     * Splits a multi-sentence text into frozen historical sentences (ready for permanent commit)
     * and the active revision horizon (which remains in Composing text for dynamic prefix rewriting).
     *
     * @param text The full recognized & formatted speech text.
     * @param keepTrailingSentences Number of recent sentences to keep active in Composing text (default: 1).
     * @return Pair(frozenPrefix, activeHorizonSuffix)
     */
    fun splitHorizonSentences(text: String, keepTrailingSentences: Int = 1): Pair<String, String> {
        val boundaries = mutableListOf<Int>()
        for (i in text.indices) {
            val c = text[i]
            if (c == '。' || c == '？' || c == '！' || c == '\n') {
                boundaries.add(i)
            }
        }

        if (boundaries.size <= keepTrailingSentences) {
            return Pair("", text)
        }

        val splitBoundaryIndex = boundaries[boundaries.size - keepTrailingSentences - 1]
        val frozen = text.substring(0, splitBoundaryIndex + 1)
        val active = text.substring(splitBoundaryIndex + 1).trimStart()
        return Pair(frozen, active)
    }

    // Particles and subordinate conjunctives after which a phrase/clause can be safely frozen
    private val SAFE_PARTICLE_ENDINGS = listOf(
        "ので", "から", "けど", "けれど", "けれども", "のに", "たら", "なら", "して",
        "ですが", "ですし", "ため", "より", "では", "には", "とは", "について", "において",
        "であり", "であって", "によって", "として"
    )

    /**
     * Calculates the Longest Common Prefix (LCP) across a list of inference candidate strings.
     */
    fun calculateLongestCommonPrefix(history: List<String>): String {
        if (history.isEmpty()) return ""
        if (history.size == 1) return history[0]

        val first = history[0]
        var lcpLen = first.length

        for (k in 1 until history.size) {
            val s = history[k]
            var currentMatch = 0
            val maxLen = minOf(lcpLen, s.length)
            while (currentMatch < maxLen && first[currentMatch] == s[currentMatch]) {
                currentMatch++
            }
            lcpLen = currentMatch
            if (lcpLen == 0) break
        }
        return first.substring(0, lcpLen)
    }

    private enum class ScriptType {
        KANJI, HIRAGANA, KATAKANA, ALPHANUMERIC, PUNCTUATION, OTHER
    }

    private fun getScriptType(c: Char): ScriptType {
        return when {
            c == '。' || c == '、' || c == '？' || c == '！' || c == '\n' || c == ' ' || c == '　' || c == '，' || c == '．' -> ScriptType.PUNCTUATION
            c in '\u4E00'..'\u9FFF' || c in '\u3400'..'\u4DBF' -> ScriptType.KANJI
            c in '\u3040'..'\u309F' -> ScriptType.HIRAGANA
            c in '\u30A0'..'\u30FF' || c == 'ー' -> ScriptType.KATAKANA
            c in '0'..'9' || c in '０'..'９' || c in 'a'..'z' || c in 'A'..'Z' || c in 'ａ'..'ｚ' || c in 'Ａ'..'Ｚ' -> ScriptType.ALPHANUMERIC
            else -> ScriptType.OTHER
        }
    }

    /**
     * Finds the closest safe freeze boundary index (<= candidateLength) to prevent morpheme splitting.
     * Boundaries are prioritized:
     * 1. Punctuation / newline boundary (。、？！\n)
     * 2. Subordinate conjunctive particle endings (ので, から, けど, して, etc.)
     * 3. Script transition boundary (Hiragana -> Kanji, Katakana -> Hiragana/Kanji, etc.)
     *
     * @return Safe boundary end index (substring end), or 0 if no safe boundary is found.
     */
    fun findSafeFreezeBoundary(text: String, candidateLength: Int): Int {
        val clampedLen = minOf(candidateLength, text.length)
        if (clampedLen <= 0) return 0

        var bestPunctuationIndex = 0
        var bestParticleIndex = 0
        var bestScriptTransitionIndex = 0

        // Scan from candidateLength down to 1 (prefer longest safe prefix)
        for (i in clampedLen downTo 1) {
            val prefix = text.substring(0, i)
            val lastChar = text[i - 1]

            // 1. Punctuation boundary
            if (lastChar == '。' || lastChar == '、' || lastChar == '？' || lastChar == '！' || lastChar == '\n') {
                if (bestPunctuationIndex == 0) bestPunctuationIndex = i
            }

            // 2. Safe particle ending
            if (bestParticleIndex == 0) {
                if (SAFE_PARTICLE_ENDINGS.any { prefix.endsWith(it) }) {
                    bestParticleIndex = i
                }
            }

            // 3. Script transition boundary (safe split between words/morphemes)
            if (bestScriptTransitionIndex == 0 && i < text.length) {
                val currType = getScriptType(text[i - 1])
                val nextType = getScriptType(text[i])

                val isSafeTransition = when {
                    // Hiragana ending before Kanji (e.g. "〜は(かな) 今日(漢字)", "〜ので(かな) 私(漢字)")
                    currType == ScriptType.HIRAGANA && nextType == ScriptType.KANJI -> true
                    // Katakana word ending before Hiragana/Kanji (e.g. "ピクニック(カナ) を(かな)")
                    currType == ScriptType.KATAKANA && (nextType == ScriptType.HIRAGANA || nextType == ScriptType.KANJI) -> true
                    // Hiragana/Kanji ending before Katakana loanword (e.g. "の(かな) カフェ(カナ)")
                    (currType == ScriptType.HIRAGANA || currType == ScriptType.KANJI) && nextType == ScriptType.KATAKANA -> true
                    // Alphanumeric word ending before Japanese
                    currType == ScriptType.ALPHANUMERIC && nextType != ScriptType.ALPHANUMERIC -> true
                    else -> false
                }

                if (isSafeTransition) {
                    bestScriptTransitionIndex = i
                }
            }
        }

        return when {
            bestPunctuationIndex > 0 -> bestPunctuationIndex
            bestParticleIndex > 0 -> bestParticleIndex
            bestScriptTransitionIndex > 0 -> bestScriptTransitionIndex
            else -> 0
        }
    }

    /**
     * Extracts a stable, morpheme-aligned frozen prefix from inference history using a hybrid of
     * N-Step LCP stability agreement, clause/morpheme boundary snapping, and active horizon clamping.
     *
     * @param history List of recent formatted inference strings.
     * @param currentFormatted The latest formatted speech string.
     * @param minConsecutiveSteps Number of consistent steps required for LCP freeze (default: 3).
     * @param protectTrailingChars Number of trailing characters kept in active Composing text (default: 12).
     * @return Pair(frozenPrefix, activeHorizonSuffix)
     */
    fun extractMorphemeAwareFreezePrefix(
        history: List<String>,
        currentFormatted: String,
        minConsecutiveSteps: Int = 3,
        protectTrailingChars: Int = 12
    ): Pair<String, String> {
        if (currentFormatted.isBlank()) {
            return Pair("", currentFormatted)
        }

        // 1. Sentence-level terminal boundary (句点 '。' etc.) takes precedence
        val (sentenceFrozen, sentenceActive) = splitHorizonSentences(currentFormatted, keepTrailingSentences = 1)

        if (history.size < minConsecutiveSteps) {
            return Pair(sentenceFrozen, sentenceActive)
        }

        // 2. N-Step Longest Common Prefix (LCP)
        val recentSteps = history.takeLast(minConsecutiveSteps)
        val lcp = calculateLongestCommonPrefix(recentSteps)
        if (lcp.isEmpty()) {
            return Pair(sentenceFrozen, sentenceActive)
        }

        // 3. Active Horizon Clamp: protect the trailing N characters for dynamic right-context revision
        val maxAllowedFreezeLen = maxOf(0, currentFormatted.length - protectTrailingChars)
        val effectiveCandidateLen = minOf(lcp.length, maxAllowedFreezeLen)

        if (effectiveCandidateLen <= sentenceFrozen.length) {
            return Pair(sentenceFrozen, sentenceActive)
        }

        // 4. Snapping to safe morpheme / clause / punctuation boundary
        val safeIndex = findSafeFreezeBoundary(currentFormatted, effectiveCandidateLen)
        val finalFrozenIndex = maxOf(sentenceFrozen.length, safeIndex)

        if (finalFrozenIndex > 0) {
            val frozen = currentFormatted.substring(0, finalFrozenIndex)
            val active = currentFormatted.substring(finalFrozenIndex).trimStart()
            return Pair(frozen, active)
        }

        return Pair(sentenceFrozen, sentenceActive)
    }

    // =========================================================================
    // Internal Punctuation Rules & Replacements
    // =========================================================================

    /**
     * Stage 0: Canonicalizes ASR model-specific variations, whitespace, and punctuation artifacts.
     */
    fun canonicalizeAsrArtifacts(text: String): String {
        var res = text

        // 1. Full-width / half-width punctuation standardization
        res = res.replace("，", "、")
        res = res.replace("．", "。")

        // 2. Normalize half-width commas and periods (e.g. Whisper / Seq2Seq outputs)
        // Protect comma and dot between digits: e.g. "1,000" or "3.14"
        res = res.replace(Regex("(?<=\\d),(?=\\d)"), "\uE102")
        res = res.replace(Regex("(?<=\\d)\\.(?=\\d)"), "\uE103")

        // Repeated dots e.g. "..." -> "…"
        res = res.replace(Regex("\\.{2,}"), "…")

        // Standalone commas and dots
        res = res.replace(",", "、")
        res = res.replace(".", "。")

        // Restore protected numeric separators
        res = res.replace("\uE102", ",")
        res = res.replace("\uE103", ".")

        // Half-width ? and ! to full-width ？ and ！
        res = res.replace("?", "？")
        res = res.replace("!", "！")

        // 3. Remove whitespace between CJK characters (Hiragana, Katakana, Kanji)
        // e.g. "今日 は 良い 天気 です" -> "今日は良い天気です"
        res = res.replace(
            Regex("(?<=[\\u3040-\\u309F\\u30A0-\\u30FF\\u4E00-\\u9FFF])\\s+(?=[\\u3040-\\u309F\\u30A0-\\u30FF\\u4E00-\\u9FFF])"),
            ""
        )

        return res
    }

    private fun convertSpokenPunctuationCommands(text: String): String {
        var res = text

        // Protect known false-positive words by masking
        val masks = mutableListOf<Pair<String, String>>()
        for ((idx, word) in PRESERVED_WORDS.withIndex()) {
            if (res.contains(word)) {
                val placeholder = "\uE100_${idx}_\uE101"
                masks.add(Pair(placeholder, word))
                res = res.replace(word, placeholder)
            }
        }

        // 1. Spoken newlines: "かいぎょう" / "改行"
        res = res.replace(Regex("\\s*(?:かいぎょう|改行)"), "\n")

        // 2. Spoken question mark: "はてな" / "ぎもんふ" / "疑問符"
        res = res.replace(Regex("\\s*(?:はてな|ぎもんふ|疑問符)"), "？")

        // 3. Spoken exclamation mark: "びっくりまーく" / "びっくりマーク" / "かんたんふ" / "感嘆符"
        res = res.replace(Regex("\\s*(?:びっくりまーく|びっくりマーク|かんたんふ|感嘆符)"), "！")

        // 4. Spoken period: "くてん" / "句点" (Run before "てん" to prevent "くてん" -> "く、")
        res = res.replace(Regex("\\s*(?:くてん|句点)"), "。")

        // 5. Spoken comma: "とうてん" / "読点"
        res = res.replace(Regex("\\s*(?:とうてん|読点)"), "、")

        // 6. Spoken comma "てん" / "点":
        // Must NOT be preceded by Kanji (e.g. 満点, 交差点, 視点) or digits (e.g. 10点)
        res = res.replace(Regex("(?<![\\p{IsHan}0-9０-９一二三四五六七八九〇零])\\s*(?:てん|点)(?=$|[^\u3040-\u309F]|\\s|[ぁ-ん])"), "、")

        // 7. Spoken period "まる" / "丸":
        // Must NOT be preceded by Kanji (e.g. 一丸, 弾丸, 日の丸) or digits
        res = res.replace(Regex("(?<![\\p{IsHan}0-9０-９一二三四五六七八九〇零])\\s*(?:まる|丸)(?=$|[^\u3040-\u309F]|\\s|[あ-ん])"), "。")

        // Restore masked preserved words
        for ((placeholder, word) in masks) {
            res = res.replace(placeholder, word)
        }

        return res
    }

    private fun applyCrossChunkRetroactiveLink(
        text: String,
        metadata: ProsodyBoundaryMetadata?
    ): Pair<String, Int> {
        if (metadata == null || metadata.precedingCommittedTail.isEmpty()) {
            return Pair(text, 0)
        }

        val tail = metadata.precedingCommittedTail.trimEnd()
        if (!tail.endsWith("。")) {
            return Pair(text, 0)
        }

        // Check if text begins with subordinate conjunctives or connective particles
        // e.g. "が", "ので", "から", "けど", "けれど", "けれども", "のに", "し", "ため"
        val connectiveRegex = Regex("^(が|ので|から|けど|けれど|けれども|のに|し|ため)(?=[^ぁ-んァ-ン]|$)")
        val match = connectiveRegex.find(text)
        if (match != null) {
            val particle = match.groupValues[1]
            val remainder = text.substring(particle.length).trimStart()
            val linkedText = if (remainder.startsWith("、") || remainder.startsWith("。")) {
                "$particle$remainder"
            } else {
                "$particle、$remainder"
            }
            return Pair(linkedText, 1)
        }

        return Pair(text, 0)
    }

    private fun insertSentenceEndingPeriods(
        text: String,
        metadata: ProsodyBoundaryMetadata? = null
    ): String {
        var res = text
        val isRisingPitch = metadata != null && metadata.terminalPitchSlopeSemitones >= 3.0f
        val terminalMark = if (isRisingPitch) "？" else "。"

        // Sentence-ending markers:
        // Group A: Copula and auxiliary verbs
        // e.g. "です", "でした", "でしょう", "ます", "ました", "ません", "である", "であった", "ください", "してください", "お願いします", "お願いいたします"
        // Avoid "ですから", "ですので", "ですが", "ですし", "ますます"
        // Must NOT already be followed by punctuation: (?![、。！？\n])
        val copulaPattern = Pattern.compile(
            "([でま]す|[でま]した|[でま]せん|ましょう|でしょう|である|であった|ください|してください|お願いします|お願いいたします)" +
            "(?![、。！？\n])(?=(?:しかし|そして|また|ただし|そのため|したがって|ですから|ですが|さらに|ちなみに|ところで|さて|まず|次に|最後に|実は|もちろん|明日|今日|昨日|私|僕|今|これから|今後|それでは|では|\\s|$))"
        )
        val m1 = copulaPattern.matcher(res)
        val sb1 = StringBuffer()
        while (m1.find()) {
            val matched = m1.group(1)
            m1.appendReplacement(sb1, java.util.regex.Matcher.quoteReplacement("$matched$terminalMark"))
        }
        m1.appendTail(sb1)
        res = sb1.toString()

        // Group B: Sentence-final particles
        val particlePattern = Pattern.compile(
            "([でま]す(?:ね|よ|よね)|[だ好楽]よ|[だ好]ね|ですよね|かな|なあ)" +
            "(?![、。！？\n])(?=(?:しかし|そして|また|ただし|そのため|したがって|ですから|ですが|さらに|ちなみに|ところで|さて|まず|次に|最後に|実は|もちろん|明日|今日|昨日|私|僕|今|これから|今後|それでは|では|\\s|$))"
        )
        val m2 = particlePattern.matcher(res)
        val sb2 = StringBuffer()
        while (m2.find()) {
            val matched = m2.group(1)
            m2.appendReplacement(sb2, java.util.regex.Matcher.quoteReplacement("$matched$terminalMark"))
        }
        m2.appendTail(sb2)
        res = sb2.toString()

        // Group C: Conversational greetings and closures
        val greetingPattern = Pattern.compile(
            "(お疲れ様でした|お疲れ様です|ありがとうございます|ありがとうございました|よろしくお願い[い致]します|宜しくお願いします|よろしくおねがいします|こんにちは|こんばんは|おはようございます|失礼します|失礼しました)" +
            "(?![、。！？\n])(?=(?:しかし|そして|また|ただし|そのため|したがって|ですから|ですが|さらに|ちなみに|ところで|さて|まず|次に|最後に|実は|もちろん|明日|今日|昨日|私|僕|今|これから|今後|それでは|では|\\s|$))"
        )
        val m3 = greetingPattern.matcher(res)
        val sb3 = StringBuffer()
        while (m3.find()) {
            val matched = m3.group(1)
            m3.appendReplacement(sb3, java.util.regex.Matcher.quoteReplacement("$matched$terminalMark"))
        }
        m3.appendTail(sb3)
        res = sb3.toString()

        // Group D: Rising intonation interrogation on expressive phrases (e.g. 本当, 大丈夫)
        if (isRisingPitch) {
            val expressivePattern = Pattern.compile(
                "(^|[\\p{IsHan}\\u3040-\\u309F\\u30A0-\\u30FF\\s])(本当|大丈夫|平気|いい)(?![、。！？\n])(?=(?:\\s|$))"
            )
            val mExp = expressivePattern.matcher(res)
            val sbExp = StringBuffer()
            while (mExp.find()) {
                val prefix = mExp.group(1) ?: ""
                val matched = mExp.group(2) ?: ""
                mExp.appendReplacement(sbExp, java.util.regex.Matcher.quoteReplacement("$prefix$matched？"))
            }
            mExp.appendTail(sbExp)
            res = sbExp.toString()
        }

        // Group E: Noun endings with full pauses (体言止め + >=500ms silence)
        if (metadata != null && metadata.trailingPauseMs >= 500L) {
            val nounPattern = Pattern.compile(
                "(^|[\\p{IsHan}\\u3040-\\u309F\\u30A0-\\u30FF\\s])(以上|予定|報告|完了|終了|方針|対策|こと)(?![、。！？\n])(?=(?:\\s|$))"
            )
            val mNoun = nounPattern.matcher(res)
            val sbNoun = StringBuffer()
            while (mNoun.find()) {
                val prefix = mNoun.group(1) ?: ""
                val matched = mNoun.group(2) ?: ""
                mNoun.appendReplacement(sbNoun, java.util.regex.Matcher.quoteReplacement("$prefix$matched。"))
            }
            mNoun.appendTail(sbNoun)
            res = sbNoun.toString()
        }

        return res
    }

    private fun insertClauseCommas(
        text: String,
        metadata: ProsodyBoundaryMetadata? = null
    ): String {
        var res = text

        // 1. Connective words at clause or sentence start
        // e.g. "しかし", "そして", "また", "まず", "次に", "最後に", "実は", "もちろん", "そのため", "したがって", "ですから", "ですが", "さらに"
        val connectivesPattern = Pattern.compile(
            "(^|[。！？\n\\s])(しかし|そして|また|ただし|そのため|したがって|ですから|ですが|さらに|ちなみに|ところで|さて|まず|次に|最後に|実は|もちろん|幸い|あいにく|例えば|はい|いいえ|ええ)(?=[^、。！？\n])"
        )
        val m1 = connectivesPattern.matcher(res)
        val sb1 = StringBuffer()
        while (m1.find()) {
            val prefix = m1.group(1)
            val word = m1.group(2)
            m1.appendReplacement(sb1, java.util.regex.Matcher.quoteReplacement("$prefix$word、"))
        }
        m1.appendTail(sb1)
        res = sb1.toString()

        // 2. Subordinate clause conjunctive particles: "ので", "から", "けど", "けれど", "けれども", "のに", "たら", "なら"
        // Only insert comma when preceded by Hiragana predicate endings (e.g. 降るから, だったから),
        // avoiding nominal case particles (e.g. 結論から, 東京から, 9時から).
        val clausePattern = Pattern.compile(
            "(?<=[\\u3040-\\u309F])(?<!で)(ので|から|けれど|けれども|けど|のに|たら|なら)(?=[^、。！？\n])"
        )
        val m2 = clausePattern.matcher(res)
        val sb2 = StringBuffer()
        var lastCommaPos = -1
        while (m2.find()) {
            val particle = m2.group(1)
            val pos = m2.start()
            // Anti-overpunctuation: If another comma was placed within 5 chars, suppress redundant comma
            // unless separated by significant pause (>= 250ms)
            val tooClose = lastCommaPos >= 0 && (pos - lastCommaPos) < 5
            val hasPause = metadata != null && metadata.trailingPauseMs >= 250L
            if (tooClose && !hasPause) {
                m2.appendReplacement(sb2, java.util.regex.Matcher.quoteReplacement(particle ?: ""))
            } else {
                m2.appendReplacement(sb2, java.util.regex.Matcher.quoteReplacement("$particle、"))
                lastCommaPos = pos
            }
        }
        m2.appendTail(sb2)
        res = sb2.toString()

        // 3. Long-Span Breathing Comma (>=18 characters without punctuation)
        res = insertLongSpanBreathingCommas(res)

        return res
    }

    private fun insertLongSpanBreathingCommas(text: String): String {
        val spanBoundaryRegex = Regex("[、。！？\n]")
        val segments = text.split(spanBoundaryRegex)
        var res = text

        val topicMarkers = listOf("については", "に関しては", "に対しては", "においては")
        for (seg in segments) {
            if (seg.length >= 18) {
                for (marker in topicMarkers) {
                    if (seg.contains(marker) && !seg.contains("$marker、")) {
                        res = res.replace(marker, "$marker、")
                        break
                    }
                }
            }
        }
        return res
    }

    private fun cleanupPunctuation(text: String): String {
        var res = text
        // Deduplicate repeated punctuation marks
        res = res.replace(Regex("、+"), "、")
        res = res.replace(Regex("。+"), "。")
        res = res.replace(Regex("、。"), "。")
        res = res.replace(Regex("。、"), "。")
        res = res.replace(Regex("？。"), "？")
        res = res.replace(Regex("！。"), "！")
        res = res.replace(Regex("、\n"), "\n")
        res = res.replace(Regex("。\n+"), "。\n")
        res = res.replace(Regex("\n+"), "\n")
        // Remove whitespace before and after Japanese punctuation marks and newlines
        res = res.replace(Regex("\\s+([、。！？\n])"), "$1")
        res = res.replace(Regex("([、。！？\n])\\s+"), "$1")
        return res
    }
}
