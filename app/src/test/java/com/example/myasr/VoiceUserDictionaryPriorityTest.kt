package com.example.myasr

import com.example.myasr.context.kanakanji.domain.KanaKanjiEngine
import com.example.myasr.context.speech.application.DictionaryPostProcessor
import com.example.myasr.context.speech.application.HotwordsGenerator
import com.example.myasr.context.speech.domain.ContextAwareProcessor
import com.example.myasr.context.vocabulary.domain.DictionaryEntry
import com.example.myasr.context.vocabulary.domain.Priority

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Regression tests for ASR language model in-beam hotword biasing and Channel 2 phonetic substitution (ADR-004).
 *
 * Verifies that:
 * 1. Registered dictionary entries (e.g. 幸太朗, よみ: こうたろう) are expanded into space-separated CJK tokens
 *    for Sherpa-ONNX modified_beam_search FST biasing.
 * 2. Phonetic readings (Hiragana & Katakana) emitted by ASR beam decoding are deterministically substituted
 *    with the registered surface form.
 * 3. Contextual orthographic cues harmonize variants without brute-force unigram cost hacking.
 */
class VoiceUserDictionaryPriorityTest {

    private lateinit var kanaEngine: KanaKanjiEngine
    private lateinit var processorWithEngine: ContextAwareProcessor
    private val standaloneProcessor = ContextAwareProcessor()

    @Before
    fun setUp() {
        val tsvFile = listOf(
            File("tools/mozc_dict/base_dictionary.tsv"),
            File("../tools/mozc_dict/base_dictionary.tsv")
        ).firstOrNull { it.exists() }
        requireNotNull(tsvFile) { "base_dictionary.tsv not found for test" }

        kanaEngine = KanaKanjiEngine(null)
        tsvFile.inputStream().use { stream ->
            kanaEngine.initializeWithStream(stream)
        }

        processorWithEngine = ContextAwareProcessor { kanaEngine }
    }

    @Test
    fun testKotaroHotwordsTokenSeparationAndRules() {
        val generator = HotwordsGenerator(android.content.ContextWrapper(null))
        val entries = listOf(
            DictionaryEntry(
                word = "幸太朗",
                reading = "こうたろう",
                priority = Priority.HIGH
            )
        )

        val rules = generator.buildHotwordRules(entries)
        assertTrue("Hotwords rules must not be empty", rules.isNotEmpty())

        // Verify CJK character space separation required by Sherpa-ONNX cjkchar modeling unit
        val surfaceRule = rules.firstOrNull { it.startsWith("幸 太 朗") }
        val hiraganaRule = rules.firstOrNull { it.startsWith("こ う た ろ う") }
        val katakanaRule = rules.firstOrNull { it.startsWith("コ ウ タ ロ ウ") }

        requireNotNull(surfaceRule) { "Must contain space-separated surface tokens: '幸 太 朗'" }
        requireNotNull(hiraganaRule) { "Must contain space-separated hiragana tokens: 'こ う た ろ う'" }
        requireNotNull(katakanaRule) { "Must contain space-separated katakana tokens: 'コ ウ タ ロ ウ'" }

        val surfaceScore = surfaceRule.substringAfter(":").toFloat()
        val hiraganaScore = hiraganaRule.substringAfter(":").toFloat()
        val katakanaScore = katakanaRule.substringAfter(":").toFloat()

        assertEquals(4.5f, surfaceScore, 0.01f)
        assertEquals(4.5f, hiraganaScore, 0.01f)
        assertEquals(4.5f, katakanaScore, 0.01f)

        // Verify formatted hotword string for createStream(hotwords)
        val hotwordsStr = generator.getHotwordsString(entries)
        assertTrue(hotwordsStr.contains("幸 太 朗 :4.5"))
        assertTrue(hotwordsStr.contains("こ う た ろ う :4.5"))
        assertTrue(hotwordsStr.contains("コ ウ タ ロ ウ :4.5"))
        assertTrue(hotwordsStr.contains("/"))
    }

    @Test
    fun testChannel2PhoneticSubstitutionForKotaro() {
        val entries = listOf(
            DictionaryEntry(
                word = "幸太朗",
                reading = "こうたろう",
                priority = Priority.HIGH
            )
        )

        // 1. When ASR beam search outputs pure Hiragana phonetic tokens
        val rawAsr1 = "こうたろうに電話して"
        val out1 = processorWithEngine.process(rawAsr1, "", entries)
        assertEquals("幸太朗に電話して", out1)

        // 2. When ASR beam search outputs Katakana phonetic tokens
        val rawAsr2 = "コウタロウの件です"
        val out2 = processorWithEngine.process(rawAsr2, "", entries)
        assertEquals("幸太朗の件です。", out2)

        // 3. When context already establishes target person name and baseline outputs variant
        val rawAsr3 = "幸太郎さんに直接指示を仰ぎました"
        val contextWithCue = "本件は幸太朗が担当します。"
        val out3 = processorWithEngine.process(rawAsr3, contextWithCue, entries)
        assertEquals("幸太朗さんに直接指示を仰ぎました。", out3)
    }

    @Test
    fun testDictionaryPostProcessorDelegation() {
        val postProcessor = DictionaryPostProcessor { kanaEngine }
        val entries = listOf(
            DictionaryEntry(word = "幸太朗", reading = "こうたろう", priority = Priority.HIGH)
        )

        // Without punctuation modification
        val processed = postProcessor.process("こうたろうとコウタロウ", entries)
        assertEquals("幸太朗と幸太朗", processed)
    }

    @Test
    fun testBrandAndAcronymNormalization() {
        val postProcessor = DictionaryPostProcessor { kanaEngine }
        val entries = listOf(
            DictionaryEntry(word = "ReazonSpeech", reading = "れいぞんすぴーち", priority = Priority.HIGH),
            DictionaryEntry(word = "DX", reading = "でぃーえっくす", priority = Priority.NORMAL)
        )

        val processed1 = postProcessor.process("reazonspeechモデルの認識精度", entries)
        assertEquals("ReazonSpeechモデルの認識精度", processed1)

        val processed2 = postProcessor.process("れいぞんすぴーちを活用する", entries)
        assertEquals("ReazonSpeechを活用する", processed2)
    }

    @Test
    fun testAsciiWordWithReadingForJapaneseVoiceAndConversion() {
        val generator = HotwordsGenerator(android.content.ContextWrapper(null))
        val entries = listOf(
            DictionaryEntry(word = "Claude", reading = "くろーど", priority = Priority.HIGH),
            DictionaryEntry(word = "API", reading = "えーぴーあい", priority = Priority.HIGH),
            DictionaryEntry(word = "AI", reading = "えーあい", priority = Priority.NORMAL),
            DictionaryEntry(word = "C++", reading = "しーぷらぷら", priority = Priority.HIGH),
            DictionaryEntry(word = "C\$", reading = "しーどる", priority = Priority.NORMAL)
        )

        // 1. HotwordsGenerator emits Hiragana & Katakana token rules for Japanese ASR biasing
        val rules = generator.buildHotwordRules(entries)
        assertTrue(rules.any { it.startsWith("く ろ ー ど") })
        assertTrue(rules.any { it.startsWith("ク ロ ー ド") })
        assertTrue(rules.any { it.startsWith("え ー ぴ ー あ い") })
        assertTrue(rules.any { it.startsWith("し ー ぷ ら ぷ ら") })

        // 2. ContextAwareProcessor deterministically converts Japanese ASR phonetic output to ASCII surface
        val postProcessor = DictionaryPostProcessor { kanaEngine }
        assertEquals("Claudeに質問する", postProcessor.process("クロードに質問する", entries))
        assertEquals("APIを呼ぶ", postProcessor.process("えーぴーあいを呼ぶ", entries))
        assertEquals("C++で書く", postProcessor.process("シープラプラで書く", entries))
        assertEquals("C\$を使う", postProcessor.process("c\$を使う", entries))

        // 3. Boundary-safe alphanumeric normalization must NOT corrupt substrings inside unrelated English words
        assertEquals("rapidに開発する", postProcessor.process("rapidに開発する", entries))
        assertEquals("Emailを送る", postProcessor.process("Emailを送る", entries))

        // 4. KanaKanjiEngine returns ASCII surfaces for Japanese readings
        kanaEngine.syncUserDictionary(entries)
        val claudeCands = kanaEngine.getBaseCandidates("くろーど")
        assertTrue("Expected Claude for reading くろーど", claudeCands.any { it.surface == "Claude" })
        val apiCands = kanaEngine.getBaseCandidates("えーぴーあい")
        assertTrue("Expected API for reading えーぴーあい", apiCands.any { it.surface == "API" })
    }
}
