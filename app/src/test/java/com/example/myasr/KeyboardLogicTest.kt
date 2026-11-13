package com.example.myasr

import com.example.myasr.context.kanakanji.domain.JapaneseKanaUtils
import com.example.myasr.context.speech.application.DictionaryPostProcessor
import com.example.myasr.context.vocabulary.domain.DictionaryEntry
import com.example.myasr.context.vocabulary.domain.Priority

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardLogicTest {

    @Test
    fun testPunctuationLogic() {
        val comma = "、"
        val period = "。"
        val space = " "
        val baseText = "こんにちは"

        assertEquals("こんにちは、", baseText + comma)
        assertEquals("こんにちは。", baseText + period)
        assertEquals("こんにちは ", baseText + space)
    }

    @Test
    fun testPostProcessingInKeyboardFlow() {
        val postProcessor = DictionaryPostProcessor()
        val entries = listOf(
            DictionaryEntry(word = "渡邊", reading = "わたなべ", priority = Priority.HIGH),
            DictionaryEntry(word = "ReazonSpeech", reading = "れいぞんすぴーち", priority = Priority.HIGH)
        )

        // Raw phonetic transcription
        val raw1 = "わたなべさんが来ました"
        val processed1 = postProcessor.process(raw1, entries)
        assertEquals("渡邊さんが来ました", processed1)

        // Lowercase brand name transcription
        val raw2 = "reazonspeechのモデルです"
        val processed2 = postProcessor.process(raw2, entries)
        assertEquals("ReazonSpeechのモデルです", processed2)
    }

    @Test
    fun testKanaConversion() {
        assertEquals("わたなべ", JapaneseKanaUtils.normalizeReading("ワタナベ"))
        assertEquals("れいぞんすぴーち", JapaneseKanaUtils.normalizeReading("レイゾンスピーチ"))
        assertEquals("ワタナベ", JapaneseKanaUtils.hiraganaToKatakana("わたなべ"))
    }

    @Test
    fun testDakutenHandakutenSmallToggle() {
        // Unvoiced -> Voiced -> Unvoiced
        assertEquals('が', JapaneseKanaUtils.toggleDakutenHandakutenSmall('か'))
        assertEquals('か', JapaneseKanaUtils.toggleDakutenHandakutenSmall('が'))

        // は -> ば -> ぱ -> は
        assertEquals('ば', JapaneseKanaUtils.toggleDakutenHandakutenSmall('は'))
        assertEquals('ぱ', JapaneseKanaUtils.toggleDakutenHandakutenSmall('ば'))
        assertEquals('は', JapaneseKanaUtils.toggleDakutenHandakutenSmall('ぱ'))

        // つ -> っ -> づ -> つ
        assertEquals('っ', JapaneseKanaUtils.toggleDakutenHandakutenSmall('つ'))
        assertEquals('づ', JapaneseKanaUtils.toggleDakutenHandakutenSmall('っ'))
        assertEquals('つ', JapaneseKanaUtils.toggleDakutenHandakutenSmall('づ'))

        // Small vowels
        assertEquals('ぁ', JapaneseKanaUtils.toggleDakutenHandakutenSmall('あ'))
        assertEquals('あ', JapaneseKanaUtils.toggleDakutenHandakutenSmall('ぁ'))

        // Katakana
        assertEquals('ガ', JapaneseKanaUtils.toggleDakutenHandakutenSmall('カ'))
        assertEquals('パ', JapaneseKanaUtils.toggleDakutenHandakutenSmall('バ'))
    }

    @Test
    fun testHalfWidthKatakana() {
        assertEquals("ﾄｳｷｮｳ", JapaneseKanaUtils.toHalfWidthKatakana("とうきょう"))
        assertEquals("ｱﾌﾟﾘ", JapaneseKanaUtils.toHalfWidthKatakana("あぷり"))
        assertEquals("ﾗｰﾒﾝ", JapaneseKanaUtils.toHalfWidthKatakana("らーめん"))
    }

    @Test
    fun testFlickDirectionLookup() {
        // Key 0: あ行
        assertEquals("あ", JapaneseKanaUtils.getFlickString(0, JapaneseKanaUtils.FlickDirection.CENTER, JapaneseKanaUtils.KeyboardMode.KANA))
        assertEquals("い", JapaneseKanaUtils.getFlickString(0, JapaneseKanaUtils.FlickDirection.LEFT, JapaneseKanaUtils.KeyboardMode.KANA))
        assertEquals("う", JapaneseKanaUtils.getFlickString(0, JapaneseKanaUtils.FlickDirection.UP, JapaneseKanaUtils.KeyboardMode.KANA))
        assertEquals("え", JapaneseKanaUtils.getFlickString(0, JapaneseKanaUtils.FlickDirection.RIGHT, JapaneseKanaUtils.KeyboardMode.KANA))
        assertEquals("お", JapaneseKanaUtils.getFlickString(0, JapaneseKanaUtils.FlickDirection.DOWN, JapaneseKanaUtils.KeyboardMode.KANA))

        // Key 1: か行
        assertEquals("か", JapaneseKanaUtils.getFlickString(1, JapaneseKanaUtils.FlickDirection.CENTER, JapaneseKanaUtils.KeyboardMode.KANA))
        assertEquals("き", JapaneseKanaUtils.getFlickString(1, JapaneseKanaUtils.FlickDirection.LEFT, JapaneseKanaUtils.KeyboardMode.KANA))
        assertEquals("く", JapaneseKanaUtils.getFlickString(1, JapaneseKanaUtils.FlickDirection.UP, JapaneseKanaUtils.KeyboardMode.KANA))
        assertEquals("け", JapaneseKanaUtils.getFlickString(1, JapaneseKanaUtils.FlickDirection.RIGHT, JapaneseKanaUtils.KeyboardMode.KANA))
        assertEquals("こ", JapaneseKanaUtils.getFlickString(1, JapaneseKanaUtils.FlickDirection.DOWN, JapaneseKanaUtils.KeyboardMode.KANA))

        // Key 10: わ行
        assertEquals("わ", JapaneseKanaUtils.getFlickString(10, JapaneseKanaUtils.FlickDirection.CENTER, JapaneseKanaUtils.KeyboardMode.KANA))
        assertEquals("を", JapaneseKanaUtils.getFlickString(10, JapaneseKanaUtils.FlickDirection.LEFT, JapaneseKanaUtils.KeyboardMode.KANA))
        assertEquals("ん", JapaneseKanaUtils.getFlickString(10, JapaneseKanaUtils.FlickDirection.UP, JapaneseKanaUtils.KeyboardMode.KANA))
        assertEquals("ー", JapaneseKanaUtils.getFlickString(10, JapaneseKanaUtils.FlickDirection.RIGHT, JapaneseKanaUtils.KeyboardMode.KANA))
    }

    @Test
    fun testTopRowKeysFlickDefinitions() {
        // Top row in Kana mode: あ (0), か (1), さ (2) + row 1 た (3)
        // Key 2: さ行
        assertEquals("さ", JapaneseKanaUtils.getFlickString(2, JapaneseKanaUtils.FlickDirection.CENTER, JapaneseKanaUtils.KeyboardMode.KANA))
        assertEquals("し", JapaneseKanaUtils.getFlickString(2, JapaneseKanaUtils.FlickDirection.LEFT, JapaneseKanaUtils.KeyboardMode.KANA))
        assertEquals("す", JapaneseKanaUtils.getFlickString(2, JapaneseKanaUtils.FlickDirection.UP, JapaneseKanaUtils.KeyboardMode.KANA))
        assertEquals("せ", JapaneseKanaUtils.getFlickString(2, JapaneseKanaUtils.FlickDirection.RIGHT, JapaneseKanaUtils.KeyboardMode.KANA))
        assertEquals("そ", JapaneseKanaUtils.getFlickString(2, JapaneseKanaUtils.FlickDirection.DOWN, JapaneseKanaUtils.KeyboardMode.KANA))

        // Key 3: た行
        assertEquals("た", JapaneseKanaUtils.getFlickString(3, JapaneseKanaUtils.FlickDirection.CENTER, JapaneseKanaUtils.KeyboardMode.KANA))
        assertEquals("ち", JapaneseKanaUtils.getFlickString(3, JapaneseKanaUtils.FlickDirection.LEFT, JapaneseKanaUtils.KeyboardMode.KANA))
        assertEquals("つ", JapaneseKanaUtils.getFlickString(3, JapaneseKanaUtils.FlickDirection.UP, JapaneseKanaUtils.KeyboardMode.KANA))
        assertEquals("て", JapaneseKanaUtils.getFlickString(3, JapaneseKanaUtils.FlickDirection.RIGHT, JapaneseKanaUtils.KeyboardMode.KANA))
        assertEquals("と", JapaneseKanaUtils.getFlickString(3, JapaneseKanaUtils.FlickDirection.DOWN, JapaneseKanaUtils.KeyboardMode.KANA))

        // Top row in Number mode: 1 (0), 2 (1), 3 (2) and QWERTY row 1
        assertEquals("1", JapaneseKanaUtils.getFlickString(0, JapaneseKanaUtils.FlickDirection.CENTER, JapaneseKanaUtils.KeyboardMode.NUMBER))
        assertEquals("2", JapaneseKanaUtils.getFlickString(1, JapaneseKanaUtils.FlickDirection.CENTER, JapaneseKanaUtils.KeyboardMode.NUMBER))
        assertEquals("q", JapaneseKanaUtils.QWERTY_ROW_1[0])
        assertEquals("w", JapaneseKanaUtils.QWERTY_ROW_1[1])
    }
}
