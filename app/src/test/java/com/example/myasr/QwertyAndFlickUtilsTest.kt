package com.example.myasr

import com.example.myasr.context.kanakanji.domain.JapaneseKanaUtils

import org.junit.Assert.assertEquals
import org.junit.Test

class QwertyAndFlickUtilsTest {

    @Test
    fun testDirectionalModifierFlicksMatchGboard() {
        // 'は' -> Left: 'ば', Right: 'ぱ', Center: 'ば' -> 'ぱ' -> 'は'
        assertEquals('ば', JapaneseKanaUtils.applyDirectionalModifier('は', JapaneseKanaUtils.FlickDirection.LEFT))
        assertEquals('ぱ', JapaneseKanaUtils.applyDirectionalModifier('は', JapaneseKanaUtils.FlickDirection.RIGHT))
        assertEquals('ぱ', JapaneseKanaUtils.applyDirectionalModifier('ば', JapaneseKanaUtils.FlickDirection.RIGHT))

        // 'つ' -> Up/Down: 'っ', Left: 'づ'
        assertEquals('っ', JapaneseKanaUtils.applyDirectionalModifier('つ', JapaneseKanaUtils.FlickDirection.UP))
        assertEquals('っ', JapaneseKanaUtils.applyDirectionalModifier('つ', JapaneseKanaUtils.FlickDirection.DOWN))
        assertEquals('づ', JapaneseKanaUtils.applyDirectionalModifier('つ', JapaneseKanaUtils.FlickDirection.LEFT))

        // 'や' -> Up: 'ゃ', Up again: 'や'
        assertEquals('ゃ', JapaneseKanaUtils.applyDirectionalModifier('や', JapaneseKanaUtils.FlickDirection.UP))
        assertEquals('や', JapaneseKanaUtils.applyDirectionalModifier('ゃ', JapaneseKanaUtils.FlickDirection.UP))
    }

    @Test
    fun testGboardPunctuationFlickKeyMapping() {
        // Key index 11 is Gboard's 5-direction punctuation key: center 、, left 。, up ？, right ！, down …
        assertEquals("、", JapaneseKanaUtils.getFlickString(11, JapaneseKanaUtils.FlickDirection.CENTER, JapaneseKanaUtils.KeyboardMode.KANA))
        assertEquals("。", JapaneseKanaUtils.getFlickString(11, JapaneseKanaUtils.FlickDirection.LEFT, JapaneseKanaUtils.KeyboardMode.KANA))
        assertEquals("？", JapaneseKanaUtils.getFlickString(11, JapaneseKanaUtils.FlickDirection.UP, JapaneseKanaUtils.KeyboardMode.KANA))
        assertEquals("！", JapaneseKanaUtils.getFlickString(11, JapaneseKanaUtils.FlickDirection.RIGHT, JapaneseKanaUtils.KeyboardMode.KANA))
        assertEquals("…", JapaneseKanaUtils.getFlickString(11, JapaneseKanaUtils.FlickDirection.DOWN, JapaneseKanaUtils.KeyboardMode.KANA))
    }

    @Test
    fun testQwertyRowsAndSubLabels() {
        assertEquals(10, JapaneseKanaUtils.QWERTY_NUMBER_ROW.size)
        assertEquals(10, JapaneseKanaUtils.QWERTY_ROW_1.size)
        assertEquals(10, JapaneseKanaUtils.QWERTY_ROW_1_SUB_WITH_NUM.size)
        assertEquals(10, JapaneseKanaUtils.QWERTY_ROW_1_SUB_NO_NUM.size)
        assertEquals(9, JapaneseKanaUtils.QWERTY_ROW_2.size)
        assertEquals(9, JapaneseKanaUtils.QWERTY_ROW_2_SUB.size)
        assertEquals(7, JapaneseKanaUtils.QWERTY_ROW_3.size)
        assertEquals(7, JapaneseKanaUtils.QWERTY_ROW_3_SUB.size)

        assertEquals("q", JapaneseKanaUtils.QWERTY_ROW_1[0])
        assertEquals("%", JapaneseKanaUtils.QWERTY_ROW_1_SUB_WITH_NUM[0])
        assertEquals("1", JapaneseKanaUtils.QWERTY_ROW_1_SUB_NO_NUM[0])

        // Gboard alignment: key 'f' (index 3) is '_', key 'h' (index 5) is '-'
        assertEquals("f", JapaneseKanaUtils.QWERTY_ROW_2[3])
        assertEquals("_", JapaneseKanaUtils.QWERTY_ROW_2_SUB[3])
        assertEquals("h", JapaneseKanaUtils.QWERTY_ROW_2[5])
        assertEquals("-", JapaneseKanaUtils.QWERTY_ROW_2_SUB[5])
    }

    @Test
    fun testIsAllHiragana() {
        // True for all-Hiragana strings
        org.junit.Assert.assertTrue(JapaneseKanaUtils.isAllHiragana("きょう"))
        org.junit.Assert.assertTrue(JapaneseKanaUtils.isAllHiragana("さぎょうちゅうですので"))
        org.junit.Assert.assertTrue(JapaneseKanaUtils.isAllHiragana("むかってる"))
        org.junit.Assert.assertTrue(JapaneseKanaUtils.isAllHiragana("ことなります"))
        org.junit.Assert.assertTrue(JapaneseKanaUtils.isAllHiragana("らーめん"))
        org.junit.Assert.assertTrue(JapaneseKanaUtils.isAllHiragana("ぁぃぅぇぉっ"))

        // False for strings containing Kanji, Katakana, English, or non-Hiragana
        org.junit.Assert.assertFalse(JapaneseKanaUtils.isAllHiragana("今日"))
        org.junit.Assert.assertFalse(JapaneseKanaUtils.isAllHiragana("キョウ"))
        org.junit.Assert.assertFalse(JapaneseKanaUtils.isAllHiragana("ｷｮｳ"))
        org.junit.Assert.assertFalse(JapaneseKanaUtils.isAllHiragana("作業中ですので"))
        org.junit.Assert.assertFalse(JapaneseKanaUtils.isAllHiragana("向かってる"))
        org.junit.Assert.assertFalse(JapaneseKanaUtils.isAllHiragana("1on1"))
        org.junit.Assert.assertFalse(JapaneseKanaUtils.isAllHiragana(""))
    }
}
