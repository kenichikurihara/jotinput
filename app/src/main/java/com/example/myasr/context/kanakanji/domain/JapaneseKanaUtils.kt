package com.example.myasr.context.kanakanji.domain

object JapaneseKanaUtils {

    enum class FlickDirection {
        CENTER,
        LEFT,
        UP,
        RIGHT,
        DOWN
    }

    enum class KeyboardMode {
        KANA,
        QWERTY,
        QWERTY_SYMBOLS,
        NUMBER;

        // Backward-compatibility alias for existing references
        companion object {
            val ALPHABET = QWERTY
        }
    }

    /**
     * Converts Katakana characters in the string to Hiragana.
     * Leaves other characters (such as chōonpu 'ー') untouched.
     */
    fun katakanaToHiragana(text: String): String {
        return text.map { ch ->
            if (ch in '\u30A1'..'\u30F6') (ch - 0x60) else ch
        }.joinToString("")
    }

    /**
     * Converts Hiragana characters in the string to Katakana.
     * Leaves other characters untouched.
     */
    fun hiraganaToKatakana(text: String): String {
        return text.map { ch ->
            if (ch in '\u3041'..'\u3096') (ch + 0x60) else ch
        }.joinToString("")
    }

    /**
     * Checks if the given reading is composed of valid Japanese Kana (Hiragana, Katakana, chōonpu 'ー').
     */
    fun isValidKana(text: String): Boolean {
        if (text.isBlank()) return false
        val trimmed = text.trim()
        return trimmed.all { ch ->
            (ch in '\u3041'..'\u3096') || // Hiragana
            (ch in '\u30A1'..'\u30F6') || // Katakana
            ch == 'ー' || ch == '・' || ch == ' ' || ch == '　'
        }
    }

    /**
     * Checks if the given text consists entirely of Hiragana characters (including chōonpu 'ー').
     * Such candidates do not need to be displayed or ranked in IME candidate lists since
     * pressing the commit/enter button directly commits the un-converted Hiragana composing text.
     */
    fun isAllHiragana(text: String): Boolean {
        if (text.isBlank()) return false
        val trimmed = text.trim()
        return trimmed.all { ch ->
            (ch in '\u3041'..'\u309F') || ch == 'ー'
        }
    }

    /**
     * Standard Japanese case particles and functional connection particles.
     * When entered as 1-character composing text, these are legitimate grammatical words
     * and must be preserved in candidate suggestions rather than discarded as redundant composing hiragana.
     */
    val COMMON_CASE_PARTICLES: Set<String> = setOf(
        "は", "が", "を", "に", "で", "の", "と", "も", "へ", "から", "まで", "より", "ので"
    )

    fun isCommonParticle(text: String): Boolean {
        return COMMON_CASE_PARTICLES.contains(text.trim())
    }

    /**
     * Extracts the trailing particle or word from contextPrefix to use as a prediction trigger.
     * For example:
     * - "資料を" -> "を"
     * - "会議に" -> "に"
     * - "準備が" -> "が"
     * - "よろしく" -> "よろしく"
     * - "お疲れ様です。" -> "" (punctuation ends sentence context)
     */
    fun extractLastWordOrParticle(contextPrefix: String): String {
        if (contextPrefix.isBlank()) return ""
        val trimmed = contextPrefix.trimEnd()
        if (trimmed.isEmpty()) return ""

        val lastChar = trimmed.last()
        if (lastChar in listOf('。', '、', '！', '？', '!', '?', '\n', '.', ',')) {
            return ""
        }

        // 1. Check if trailing 2 characters match a known case particle
        if (trimmed.length >= 2) {
            val twoCharParticle = trimmed.takeLast(2)
            if (twoCharParticle in listOf("ので", "から", "まで", "より")) {
                return twoCharParticle
            }
        }
        val oneChar = lastChar.toString()
        if (isCommonParticle(oneChar)) {
            return oneChar
        }

        // 2. Otherwise extract trailing chunk of Japanese word (up to 8 chars)
        val sb = StringBuilder()
        for (i in trimmed.length - 1 downTo 0) {
            val ch = trimmed[i]
            if (ch.isWhitespace() || ch in listOf('。', '、', '！', '？', '!', '?', '\n', '.', ',')) {
                break
            }
            sb.append(ch)
            if (sb.length >= 8) break
        }
        return sb.reverse().toString()
    }

    /**
     * Normalizes a user-inputted reading into canonical Hiragana.
     */
    fun normalizeReading(text: String): String {
        val trimmed = text.trim()
        return katakanaToHiragana(trimmed)
    }

    private val HIRAGANA_VOICING_EXPANSION_MAP: Map<Char, List<Char>> = mapOf(
        'か' to listOf('か', 'が'),
        'き' to listOf('き', 'ぎ'),
        'く' to listOf('く', 'ぐ'),
        'け' to listOf('け', 'げ'),
        'こ' to listOf('こ', 'ご'),

        'さ' to listOf('さ', 'ざ'),
        'し' to listOf('し', 'じ'),
        'す' to listOf('す', 'ず'),
        'せ' to listOf('せ', 'ぜ'),
        'そ' to listOf('そ', 'ぞ'),

        'た' to listOf('た', 'だ'),
        'ち' to listOf('ち', 'ぢ'),
        'つ' to listOf('つ', 'づ', 'っ'),
        'っ' to listOf('っ', 'つ'),
        'づ' to listOf('づ', 'つ'),
        'て' to listOf('て', 'で'),
        'と' to listOf('と', 'ど'),

        'は' to listOf('は', 'ば', 'ぱ'),
        'ひ' to listOf('ひ', 'び', 'ぴ'),
        'ふ' to listOf('ふ', 'ぶ', 'ぷ'),
        'へ' to listOf('へ', 'べ', 'ぺ'),
        'ほ' to listOf('ほ', 'ぼ', 'ぽ'),

        // 小書き仮名（拗音・促音・小書き母音）双方向展開
        'あ' to listOf('あ', 'ぁ'),
        'ぁ' to listOf('ぁ', 'あ'),
        'い' to listOf('い', 'ぃ'),
        'ぃ' to listOf('ぃ', 'い'),
        'う' to listOf('う', 'ゔ', 'ぅ'),
        'ぅ' to listOf('ぅ', 'う'),
        'ゔ' to listOf('ゔ', 'う'),
        'え' to listOf('え', 'ぇ'),
        'ぇ' to listOf('ぇ', 'え'),
        'お' to listOf('お', 'ぉ'),
        'ぉ' to listOf('ぉ', 'お'),

        'や' to listOf('や', 'ゃ'),
        'ゃ' to listOf('ゃ', 'や'),
        'ゆ' to listOf('ゆ', 'ゅ'),
        'ゅ' to listOf('ゅ', 'ゆ'),
        'よ' to listOf('よ', 'ょ'),
        'ょ' to listOf('ょ', 'よ'),

        'わ' to listOf('わ', 'ゎ'),
        'ゎ' to listOf('ゎ', 'わ')
    )

    /**
     * Expands a Hiragana character to its phonetically modified variants:
     * 1. Voiced (濁音) and semi-voiced (半濁音) variants (e.g. 'か' -> ['か', 'が'], 'は' -> ['は', 'ば', 'ぱ']).
     * 2. Small-kana / Youon / Sokuon variants (e.g. 'よ' -> ['よ', 'ょ'], 'つ' -> ['つ', 'づ', 'っ']).
     * If the character does not support modifier expansion, returns a singleton list containing the character itself.
     */
    fun getVoicingExpansions(char: Char): List<Char> {
        return HIRAGANA_VOICING_EXPANSION_MAP[char] ?: listOf(char)
    }

    /**
     * Flick typing error expansions:
     * 1. Incomplete flick (falling back to Center "a" row, e.g. 'さ' when intending 'し', 'す', 'せ', 'そ')
     * 2. Key-adjacent taps (neighboring 12-key buttons, e.g. 'さ' <-> 'か', 'は')
     */
    data class FlickExpansion(val char: Char, val costPenalty: Int)

    private val FLICK_TYPO_EXPANSION_MAP: Map<Char, List<FlickExpansion>> = mapOf(
        // あ column (Key 0): Center 'あ', Left 'い', Up 'う', Right 'え', Down 'お'
        // Neighbors: か(1), た(3)
        'あ' to listOf(
            FlickExpansion('い', 2200), FlickExpansion('う', 2200), FlickExpansion('え', 2200), FlickExpansion('お', 2200),
            FlickExpansion('か', 3200), FlickExpansion('た', 3200)
        ),
        'い' to listOf(FlickExpansion('あ', 2200)),
        'う' to listOf(FlickExpansion('あ', 2200)),
        'え' to listOf(FlickExpansion('あ', 2200)),
        'お' to listOf(FlickExpansion('あ', 2200)),

        // か column (Key 1): Center 'か', Left 'き', Up 'く', Right 'け', Down 'こ'
        // Neighbors: あ(0), さ(2), な(4)
        'か' to listOf(
            FlickExpansion('き', 2200), FlickExpansion('く', 2200), FlickExpansion('け', 2200), FlickExpansion('こ', 2200),
            FlickExpansion('あ', 3200), FlickExpansion('さ', 3200), FlickExpansion('な', 3200)
        ),
        'き' to listOf(FlickExpansion('か', 2200), FlickExpansion('し', 3200)),
        'く' to listOf(FlickExpansion('か', 2200), FlickExpansion('す', 3200)),
        'け' to listOf(FlickExpansion('か', 2200), FlickExpansion('せ', 3200)),
        'こ' to listOf(FlickExpansion('か', 2200), FlickExpansion('そ', 3200)),

        // さ column (Key 2): Center 'さ', Left 'し', Up 'す', Right 'せ', Down 'そ'
        // Neighbors: か(1), は(5)
        'さ' to listOf(
            FlickExpansion('し', 2200), FlickExpansion('す', 2200), FlickExpansion('せ', 2200), FlickExpansion('そ', 2200),
            FlickExpansion('か', 3200), FlickExpansion('は', 3200)
        ),
        'し' to listOf(FlickExpansion('さ', 2200), FlickExpansion('き', 3200)),
        'す' to listOf(FlickExpansion('さ', 2200), FlickExpansion('く', 3200)),
        'せ' to listOf(FlickExpansion('さ', 2200), FlickExpansion('け', 3200)),
        'そ' to listOf(FlickExpansion('さ', 2200), FlickExpansion('こ', 3200)),

        // た column (Key 3): Center 'た', Left 'ち', Up 'つ', Right 'て', Down 'と'
        // Neighbors: あ(0), な(4), ま(6)
        'た' to listOf(
            FlickExpansion('ち', 2200), FlickExpansion('つ', 2200), FlickExpansion('て', 2200), FlickExpansion('と', 2200),
            FlickExpansion('あ', 3200), FlickExpansion('な', 3200), FlickExpansion('ま', 3200)
        ),
        'ち' to listOf(FlickExpansion('た', 2200)),
        'つ' to listOf(FlickExpansion('た', 2200)),
        'て' to listOf(FlickExpansion('た', 2200)),
        'と' to listOf(FlickExpansion('た', 2200)),

        // な column (Key 4): Center 'な', Left 'に', Up 'ぬ', Right 'ね', Down 'の'
        // Neighbors: か(1), た(3), は(5), や(7)
        'な' to listOf(
            FlickExpansion('に', 2200), FlickExpansion('ぬ', 2200), FlickExpansion('ね', 2200), FlickExpansion('の', 2200),
            FlickExpansion('か', 3200), FlickExpansion('た', 3200), FlickExpansion('は', 3200), FlickExpansion('や', 3200)
        ),
        'に' to listOf(FlickExpansion('な', 2200)),
        'ぬ' to listOf(FlickExpansion('な', 2200)),
        'ね' to listOf(FlickExpansion('な', 2200)),
        'の' to listOf(FlickExpansion('な', 2200)),

        // は column (Key 5): Center 'は', Left 'ひ', Up 'ふ', Right 'へ', Down 'ほ'
        // Neighbors: さ(2), な(4), ら(8)
        'は' to listOf(
            FlickExpansion('ひ', 2200), FlickExpansion('ふ', 2200), FlickExpansion('へ', 2200), FlickExpansion('ほ', 2200),
            FlickExpansion('さ', 3200), FlickExpansion('な', 3200), FlickExpansion('ら', 3200)
        ),
        'ひ' to listOf(FlickExpansion('は', 2200)),
        'ふ' to listOf(FlickExpansion('は', 2200)),
        'へ' to listOf(FlickExpansion('は', 2200)),
        'ほ' to listOf(FlickExpansion('は', 2200)),

        // ま column (Key 6): Center 'ま', Left 'み', Up 'む', Right 'め', Down 'も'
        // Neighbors: た(3), や(7)
        'ま' to listOf(
            FlickExpansion('み', 2200), FlickExpansion('む', 2200), FlickExpansion('め', 2200), FlickExpansion('も', 2200),
            FlickExpansion('た', 3200), FlickExpansion('や', 3200)
        ),
        'み' to listOf(FlickExpansion('ま', 2200)),
        'む' to listOf(FlickExpansion('ま', 2200)),
        'め' to listOf(FlickExpansion('ま', 2200)),
        'も' to listOf(FlickExpansion('ま', 2200)),

        // や column (Key 7): Center 'や', Left '（', Up 'ゆ', Right '）', Down 'よ'
        // Neighbors: な(4), ま(6), ら(8), わ(10)
        'や' to listOf(
            FlickExpansion('ゆ', 2200), FlickExpansion('よ', 2200),
            FlickExpansion('な', 3200), FlickExpansion('ま', 3200), FlickExpansion('ら', 3200)
        ),
        'ゆ' to listOf(FlickExpansion('や', 2200)),
        'よ' to listOf(FlickExpansion('や', 2200)),

        // ら column (Key 8): Center 'ら', Left 'り', Up 'る', Right 'れ', Down 'ろ'
        // Neighbors: は(5), や(7)
        'ら' to listOf(
            FlickExpansion('り', 2200), FlickExpansion('る', 2200), FlickExpansion('れ', 2200), FlickExpansion('ろ', 2200),
            FlickExpansion('は', 3200), FlickExpansion('や', 3200)
        ),
        'り' to listOf(FlickExpansion('ら', 2200)),
        'る' to listOf(FlickExpansion('ら', 2200)),
        'れ' to listOf(FlickExpansion('ら', 2200)),
        'ろ' to listOf(FlickExpansion('ら', 2200)),

        // わ column (Key 10): Center 'わ', Left 'を', Up 'ん', Right 'ー'
        'わ' to listOf(
            FlickExpansion('を', 2200), FlickExpansion('ん', 2200), FlickExpansion('ー', 2200)
        )
    )

    fun getFlickTypoExpansions(char: Char): List<FlickExpansion> {
        return FLICK_TYPO_EXPANSION_MAP[char] ?: emptyList()
    }

    private val DAKUTEN_SMALL_MAP = mapOf(
        // Hiragana
        'か' to 'が', 'が' to 'か',
        'き' to 'ぎ', 'ぎ' to 'き',
        'く' to 'ぐ', 'ぐ' to 'く',
        'け' to 'げ', 'げ' to 'け',
        'こ' to 'ご', 'ご' to 'こ',

        'さ' to 'ざ', 'ざ' to 'さ',
        'し' to 'じ', 'じ' to 'し',
        'す' to 'ず', 'ず' to 'す',
        'せ' to 'ぜ', 'ぜ' to 'せ',
        'そ' to 'ぞ', 'ぞ' to 'そ',

        'た' to 'だ', 'だ' to 'た',
        'ち' to 'ぢ', 'ぢ' to 'ち',
        'つ' to 'っ', 'っ' to 'づ', 'づ' to 'つ',
        'て' to 'で', 'で' to 'て',
        'と' to 'ど', 'ど' to 'と',

        'は' to 'ば', 'ば' to 'ぱ', 'ぱ' to 'は',
        'ひ' to 'び', 'び' to 'ぴ', 'ぴ' to 'ひ',
        'ふ' to 'ぶ', 'ぶ' to 'ぷ', 'ぷ' to 'ふ',
        'へ' to 'べ', 'べ' to 'ぺ', 'ぺ' to 'へ',
        'ほ' to 'ぼ', 'ぼ' to 'ぽ', 'ぽ' to 'ほ',

        'あ' to 'ぁ', 'ぁ' to 'あ',
        'い' to 'ぃ', 'ぃ' to 'い',
        'う' to 'ぅ', 'ぅ' to 'ゔ', 'ゔ' to 'う',
        'え' to 'ぇ', 'ぇ' to 'え',
        'お' to 'ぉ', 'ぉ' to 'お',

        'や' to 'ゃ', 'ゃ' to 'や',
        'ゆ' to 'ゅ', 'ゅ' to 'ゆ',
        'よ' to 'ょ', 'ょ' to 'よ',
        'わ' to 'ゎ', 'ゎ' to 'わ',

        // Katakana
        'カ' to 'ガ', 'ガ' to 'カ',
        'キ' to 'ギ', 'ギ' to 'キ',
        'ク' to 'グ', 'グ' to 'ク',
        'ケ' to 'ゲ', 'ゲ' to 'ケ',
        'コ' to 'ゴ', 'ゴ' to 'コ',

        'サ' to 'ザ', 'ザ' to 'サ',
        'シ' to 'ジ', 'ジ' to 'シ',
        'ス' to 'ズ', 'ズ' to 'ス',
        'セ' to 'ゼ', 'ゼ' to 'セ',
        'ソ' to 'ゾ', 'ゾ' to 'ソ',

        'タ' to 'ダ', 'ダ' to 'タ',
        'チ' to 'ヂ', 'ヂ' to 'チ',
        'ツ' to 'ッ', 'ッ' to 'ヅ', 'ヅ' to 'ツ',
        'テ' to 'デ', 'デ' to 'テ',
        'ト' to 'ド', 'ド' to 'ト',

        'ハ' to 'バ', 'バ' to 'パ', 'パ' to 'ハ',
        'ヒ' to 'ビ', 'ビ' to 'ピ', 'ピ' to 'ヒ',
        'フ' to 'ブ', 'ブ' to 'プ', 'プ' to 'フ',
        'ヘ' to 'ベ', 'ベ' to 'ペ', 'ペ' to 'ヘ',
        'ホ' to 'ボ', 'ボ' to 'ポ', 'ポ' to 'ホ',

        'ア' to 'ァ', 'ァ' to 'ア',
        'イ' to 'ィ', 'ィ' to 'イ',
        'ウ' to 'ゥ', 'ゥ' to 'ヴ', 'ヴ' to 'ウ',
        'エ' to 'ェ', 'ェ' to 'エ',
        'オ' to 'ォ', 'ォ' to 'オ',

        'ヤ' to 'ャ', 'ャ' to 'ヤ',
        'ユ' to 'ュ', 'ュ' to 'ユ',
        'ヨ' to 'ョ', 'ョ' to 'ヨ',
        'ワ' to 'ヮ', 'ヮ' to 'ワ'
    )

    private val DAKUTEN_DIRECT_MAP = mapOf(
        'か' to 'が', 'が' to 'か', 'き' to 'ぎ', 'ぎ' to 'き', 'く' to 'ぐ', 'ぐ' to 'く', 'け' to 'げ', 'げ' to 'け', 'こ' to 'ご', 'ご' to 'こ',
        'さ' to 'ざ', 'ざ' to 'さ', 'し' to 'じ', 'じ' to 'し', 'す' to 'ず', 'ず' to 'す', 'せ' to 'ぜ', 'ぜ' to 'せ', 'そ' to 'ぞ', 'ぞ' to 'そ',
        'た' to 'だ', 'だ' to 'た', 'ち' to 'ぢ', 'ぢ' to 'ち', 'つ' to 'づ', 'っ' to 'づ', 'づ' to 'つ', 'て' to 'で', 'で' to 'て', 'と' to 'ど', 'ど' to 'と',
        'は' to 'ば', 'ば' to 'は', 'ぱ' to 'ば', 'ひ' to 'び', 'び' to 'ひ', 'ぴ' to 'び', 'ふ' to 'ぶ', 'ぶ' to 'ふ', 'ぷ' to 'ぶ', 'へ' to 'べ', 'べ' to 'へ', 'ぺ' to 'べ', 'ほ' to 'ぼ', 'ぼ' to 'ほ', 'ぽ' to 'ぼ',
        'う' to 'ゔ', 'ぅ' to 'ゔ', 'ゔ' to 'う',
        'カ' to 'ガ', 'ガ' to 'カ', 'キ' to 'ギ', 'ギ' to 'キ', 'ク' to 'グ', 'グ' to 'ク', 'ケ' to 'ゲ', 'ゲ' to 'ケ', 'コ' to 'ゴ', 'ゴ' to 'コ',
        'サ' to 'ザ', 'ザ' to 'サ', 'シ' to 'ジ', 'ジ' to 'シ', 'ス' to 'ズ', 'ズ' to 'ス', 'セ' to 'ゼ', 'ゼ' to 'セ', 'ソ' to 'ゾ', 'ゾ' to 'ソ',
        'タ' to 'ダ', 'ダ' to 'タ', 'チ' to 'ヂ', 'ヂ' to 'チ', 'ツ' to 'ヅ', 'ッ' to 'ヅ', 'ヅ' to 'ツ', 'テ' to 'デ', 'デ' to 'テ', 'ト' to 'ド', 'ド' to 'ト',
        'ハ' to 'バ', 'バ' to 'ハ', 'パ' to 'バ', 'ヒ' to 'ビ', 'ビ' to 'ヒ', 'ピ' to 'ビ', 'フ' to 'ブ', 'ブ' to 'フ', 'プ' to 'ブ', 'ヘ' to 'ベ', 'ベ' to 'ヘ', 'ペ' to 'ベ', 'ホ' to 'ボ', 'ボ' to 'ホ', 'ポ' to 'ボ',
        'ウ' to 'ヴ', 'ゥ' to 'ヴ', 'ヴ' to 'ウ'
    )

    private val HANDAKUTEN_DIRECT_MAP = mapOf(
        'は' to 'ぱ', 'ば' to 'ぱ', 'ぱ' to 'は',
        'ひ' to 'ぴ', 'び' to 'ぴ', 'ぴ' to 'ひ',
        'ふ' to 'ぷ', 'ぶ' to 'ぷ', 'ぷ' to 'ふ',
        'へ' to 'ぺ', 'べ' to 'ぺ', 'ぺ' to 'へ',
        'ほ' to 'ぽ', 'ぼ' to 'ぽ', 'ぽ' to 'ほ',
        'ハ' to 'パ', 'バ' to 'パ', 'パ' to 'ハ',
        'ヒ' to 'ピ', 'ビ' to 'ピ', 'ピ' to 'ヒ',
        'フ' to 'プ', 'ブ' to 'プ', 'プ' to 'フ',
        'ヘ' to 'ペ', 'ベ' to 'ペ', 'ペ' to 'ヘ',
        'ホ' to 'ポ', 'ボ' to 'ポ', 'ポ' to 'ホ'
    )

    private val SMALL_DIRECT_MAP = mapOf(
        'あ' to 'ぁ', 'ぁ' to 'あ', 'い' to 'ぃ', 'ぃ' to 'い', 'う' to 'ぅ', 'ぅ' to 'う', 'ゔ' to 'ぅ', 'え' to 'ぇ', 'ぇ' to 'え', 'お' to 'ぉ', 'ぉ' to 'お',
        'つ' to 'っ', 'っ' to 'つ', 'づ' to 'っ',
        'や' to 'ゃ', 'ゃ' to 'や', 'ゆ' to 'ゅ', 'ゅ' to 'ゆ', 'よ' to 'ょ', 'ょ' to 'よ',
        'わ' to 'ゎ', 'ゎ' to 'わ', 'か' to 'ゕ', 'ゕ' to 'か', 'け' to 'ゖ', 'ゖ' to 'け',
        'ア' to 'ァ', 'ァ' to 'ア', 'イ' to 'ィ', 'ィ' to 'イ', 'ウ' to 'ゥ', 'ゥ' to 'ウ', 'ヴ' to 'ゥ', 'エ' to 'ェ', 'ェ' to 'エ', 'オ' to 'ォ', 'ォ' to 'オ',
        'ツ' to 'ッ', 'ッ' to 'ツ', 'ヅ' to 'ッ',
        'ヤ' to 'ャ', 'ャ' to 'ヤ', 'ユ' to 'ュ', 'ュ' to 'ユ', 'ヨ' to 'ョ', 'ョ' to 'ヨ',
        'ワ' to 'ヮ', 'ヮ' to 'ワ', 'カ' to 'ヵ', 'ヵ' to 'カ', 'ケ' to 'ヶ', 'ヶ' to 'ケ'
    )

    /**
     * Cycles the given character through its dakuten / handakuten / small variants.
     */
    fun toggleDakutenHandakutenSmall(char: Char): Char {
        return DAKUTEN_SMALL_MAP[char] ?: char
    }

    /**
     * Applies Gboard-style directional modifier flicks:
     * - CENTER: cycles dakuten -> handakuten -> small -> base
     * - LEFT: applies dakuten directly (゛)
     * - RIGHT: applies handakuten directly (゜)
     * - UP / DOWN: toggles small/large kana directly (大⇔小)
     */
    fun applyDirectionalModifier(char: Char, direction: FlickDirection): Char {
        return when (direction) {
            FlickDirection.CENTER -> toggleDakutenHandakutenSmall(char)
            FlickDirection.LEFT -> DAKUTEN_DIRECT_MAP[char] ?: toggleDakutenHandakutenSmall(char)
            FlickDirection.RIGHT -> HANDAKUTEN_DIRECT_MAP[char] ?: toggleDakutenHandakutenSmall(char)
            FlickDirection.UP, FlickDirection.DOWN -> SMALL_DIRECT_MAP[char] ?: toggleDakutenHandakutenSmall(char)
        }
    }

    private val HALF_WIDTH_KATAKANA_MAP = mapOf(
        'ァ' to "ｧ", 'ィ' to "ｨ", 'ゥ' to "ｩ", 'ェ' to "ｪ", 'ォ' to "ｫ",
        'ャ' to "ｬ", 'ュ' to "ｭ", 'ョ' to "ｮ", 'ッ' to "ｯ",
        'ア' to "ｱ", 'イ' to "ｲ", 'ウ' to "ｳ", 'エ' to "ｴ", 'オ' to "ｵ",
        'カ' to "ｶ", 'キ' to "ｷ", 'ク' to "ｸ", 'ケ' to "ｹ", 'コ' to "ｺ",
        'サ' to "ｻ", 'シ' to "ｼ", 'ス' to "ｽ", 'セ' to "ｾ", 'ソ' to "ｿ",
        'タ' to "ﾀ", 'チ' to "ﾁ", 'ツ' to "ﾂ", 'テ' to "ﾃ", 'ト' to "ﾄ",
        'ナ' to "ﾅ", 'ニ' to "ﾆ", 'ヌ' to "ﾇ", 'ネ' to "ﾈ", 'ノ' to "ﾉ",
        'ハ' to "ﾊ", 'ヒ' to "ﾋ", 'フ' to "ﾌ", 'ヘ' to "ﾍ", 'ホ' to "ﾎ",
        'マ' to "ﾏ", 'ミ' to "ﾐ", 'ム' to "ﾑ", 'メ' to "ﾒ", 'モ' to "ﾓ",
        'ヤ' to "ﾔ", 'ユ' to "ﾕ", 'ヨ' to "ﾖ",
        'ラ' to "ﾗ", 'リ' to "ﾘ", 'ル' to "ﾙ", 'レ' to "ﾚ", 'ロ' to "ﾛ",
        'ワ' to "ﾜ", 'ヲ' to "ｦ", 'ン' to "ﾝ",
        'ガ' to "ｶﾞ", 'ギ' to "ｷﾞ", 'グ' to "ｸﾞ", 'ゲ' to "ｹﾞ", 'ゴ' to "ｺﾞ",
        'ザ' to "ｻﾞ", 'ジ' to "ｼﾞ", 'ズ' to "ｽﾞ", 'ゼ' to "ｾﾞ", 'ゾ' to "ｿﾞ",
        'ダ' to "ﾀﾞ", 'ヂ' to "ﾁﾞ", 'ヅ' to "ﾂﾞ", 'デ' to "ﾃﾞ", 'ド' to "ﾄﾞ",
        'バ' to "ﾊﾞ", 'ビ' to "ﾋﾞ", 'ブ' to "ﾌﾞ", 'ベ' to "ﾍﾞ", 'ボ' to "ﾎﾞ",
        'パ' to "ﾊﾟ", 'ピ' to "ﾋﾟ", 'プ' to "ﾌﾟ", 'ペ' to "ﾍﾟ", 'ポ' to "ﾎﾟ",
        'ヴ' to "ｳﾞ", 'ー' to "ｰ"
    )

    /**
     * Converts a string of Kana into Half-width Katakana.
     */
    fun toHalfWidthKatakana(text: String): String {
        val katakana = hiraganaToKatakana(text)
        val sb = StringBuilder()
        for (ch in katakana) {
            val hw = HALF_WIDTH_KATAKANA_MAP[ch]
            if (hw != null) {
                sb.append(hw)
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    // 12-key Flick definitions for Kana (Gboard Flick-Only Layout)
    // Index 0..11:
    // 0: あ, 1: か, 2: さ
    // 3: た, 4: な, 5: は
    // 6: ま, 7: や, 8: ら
    // 9: ゛゜大⇔小, 10: わ, 11: 、。？！…
    val KANA_FLICK_MAP = arrayOf(
        // 0: あ
        arrayOf("あ", "い", "う", "え", "お"),
        // 1: か
        arrayOf("か", "き", "く", "け", "こ"),
        // 2: さ
        arrayOf("さ", "し", "す", "せ", "そ"),
        // 3: た
        arrayOf("た", "ち", "つ", "て", "と"),
        // 4: な
        arrayOf("な", "に", "ぬ", "ね", "の"),
        // 5: は
        arrayOf("は", "ひ", "ふ", "へ", "ほ"),
        // 6: ま
        arrayOf("ま", "み", "む", "め", "も"),
        // 7: や
        arrayOf("や", "（", "ゆ", "）", "よ"),
        // 8: ら
        arrayOf("ら", "り", "る", "れ", "ろ"),
        // 9: modifier (゛゜大⇔小)
        arrayOf("゛゜", "゛", "小", "゜", "小"),
        // 10: わ
        arrayOf("わ", "を", "ん", "ー", "〜"),
        // 11: 記号 (Gboard 5-dir punctuation: center 、, left 。, up ？, right ！, down …)
        arrayOf("、", "。", "？", "！", "…")
    )

    // 12-key Flick definitions for Number (Gboard 12-key Numpad)
    val NUMBER_FLICK_MAP = arrayOf(
        arrayOf("1", "", "", "", ""),
        arrayOf("2", "", "", "", ""),
        arrayOf("3", "", "", "", ""),
        arrayOf("4", "", "", "", ""),
        arrayOf("5", "", "", "", ""),
        arrayOf("6", "", "", "", ""),
        arrayOf("7", "", "", "", ""),
        arrayOf("8", "", "", "", ""),
        arrayOf("9", "", "", "", ""),
        arrayOf(",", "!", "?", "#", ""),
        arrayOf("0", "", "", "", ""),
        arrayOf(".", "=", "-", "+", "")
    )

    // Gboard QWERTY Rows and Top-Right Sub-Labels
    val QWERTY_NUMBER_ROW = arrayOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
    val QWERTY_ROW_1 = arrayOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p")
    val QWERTY_ROW_1_SUB_WITH_NUM = arrayOf("%", "\\", "|", "=", "[", "]", "<", ">", "{", "}")
    val QWERTY_ROW_1_SUB_NO_NUM = arrayOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")

    val QWERTY_ROW_2 = arrayOf("a", "s", "d", "f", "g", "h", "j", "k", "l")
    val QWERTY_ROW_2_SUB = arrayOf("@", "#", "¥", "_", "&", "-", "+", "(", ")")

    val QWERTY_ROW_3 = arrayOf("z", "x", "c", "v", "b", "n", "m")
    val QWERTY_ROW_3_SUB = arrayOf("*", "\"", "'", ":", ";", "!", "?")

    // Gboard QWERTY Symbols Layer (?123)
    val QWERTY_SYM_ROW_0 = arrayOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
    val QWERTY_SYM_ROW_1 = arrayOf("@", "#", "¥", "%", "&", "-", "+", "(", ")", "/")
    val QWERTY_SYM_ROW_2 = arrayOf("*", "\"", "'", ":", ";", "!", "?")

    fun getFlickString(keyIndex: Int, direction: FlickDirection, mode: KeyboardMode): String {
        val map = when (mode) {
            KeyboardMode.KANA -> KANA_FLICK_MAP
            KeyboardMode.NUMBER -> NUMBER_FLICK_MAP
            else -> KANA_FLICK_MAP
        }
        if (keyIndex !in map.indices) return ""
        val dirIndex = when (direction) {
            FlickDirection.CENTER -> 0
            FlickDirection.LEFT -> 1
            FlickDirection.UP -> 2
            FlickDirection.RIGHT -> 3
            FlickDirection.DOWN -> 4
        }
        val options = map[keyIndex]
        return if (dirIndex in options.indices) options[dirIndex] else ""
    }
}
