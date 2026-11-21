package com.example.myasr

import com.example.myasr.context.kanakanji.domain.KanaKanjiEngine
import com.example.myasr.context.kanakanji.domain.MozcPosCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MozcPosCatalogTest {

    @Test
    fun testOutOfBoundsPosIdsReturnFalse() {
        for (id in listOf(-1, -100, MozcPosCatalog.POS_SIZE, 9999)) {
            assertFalse(MozcPosCatalog.isContentWord(id))
            assertFalse(MozcPosCatalog.isSuffix(id))
            assertFalse(MozcPosCatalog.isFunctionalWord(id))
            assertFalse(MozcPosCatalog.isAuxiliaryVerb(id))
            assertFalse(MozcPosCatalog.isPredicatePos(id))
            assertFalse(MozcPosCatalog.isProperNoun(id))
            assertFalse(MozcPosCatalog.isSahenNoun(id))
            assertFalse(MozcPosCatalog.isInNounRange(id))
        }
    }

    @Test
    fun testBoundaryPosRangesAndAnchors() {
        // Functional & auxiliary
        assertTrue(MozcPosCatalog.isFunctionalWord(29))
        assertTrue(MozcPosCatalog.isFunctionalWord(433))
        assertFalse(MozcPosCatalog.isFunctionalWord(28))
        assertFalse(MozcPosCatalog.isFunctionalWord(434))
        assertTrue(MozcPosCatalog.isAuxiliaryVerb(MozcPosCatalog.POS_AUX_DESU))
        assertFalse(MozcPosCatalog.isAuxiliaryVerb(268))

        // Predicate (verbs + adjectives)
        assertTrue(MozcPosCatalog.isPredicatePos(434))
        assertTrue(MozcPosCatalog.isPredicatePos(MozcPosCatalog.POS_SAHEN_SURU_MASU))
        assertTrue(MozcPosCatalog.isPredicatePos(1840))
        assertFalse(MozcPosCatalog.isPredicatePos(1841))
        assertTrue(MozcPosCatalog.isPredicatePos(2196))
        assertTrue(MozcPosCatalog.isPredicatePos(2590))
        assertFalse(MozcPosCatalog.isPredicatePos(2591))

        // Nouns, proper nouns, suffixes, sahen
        assertTrue(MozcPosCatalog.isSahenNoun(1841))
        assertTrue(MozcPosCatalog.isSahenNoun(2035))
        assertTrue(MozcPosCatalog.isSahenNoun(2150))
        assertFalse(MozcPosCatalog.isSahenNoun(MozcPosCatalog.POS_NOUN_GENERAL))

        assertTrue(MozcPosCatalog.isProperNoun(1917))
        assertTrue(MozcPosCatalog.isProperNoun(1940))
        assertFalse(MozcPosCatalog.isProperNoun(1916))
        assertFalse(MozcPosCatalog.isProperNoun(1941))

        assertTrue(MozcPosCatalog.isSuffix(MozcPosCatalog.POS_SUFFIX_CHUU))
        assertFalse("Suffix must not be counted as content word", MozcPosCatalog.isContentWord(MozcPosCatalog.POS_SUFFIX_CHUU))
        assertTrue(MozcPosCatalog.isContentWord(MozcPosCatalog.POS_NOUN_GENERAL))
        assertFalse(MozcPosCatalog.isContentWord(MozcPosCatalog.POS_SYMBOL))
    }

    @Test
    fun testComputeLengthBonusInvariants() {
        val engine = KanaKanjiEngine()
        // 1-char reading gets 0 bonus
        assertEquals(
            0,
            engine.computeLengthBonus("あ", 3000, MozcPosCatalog.POS_NOUN_GENERAL, false, false, false)
        )
        // User/canonical gets full (spanLen - 1) * bonusPerChar
        assertEquals(
            2 * KanaKanjiEngine.DEFAULT_LENGTH_BONUS_PER_CHAR,
            engine.computeLengthBonus("きょう", 9000, MozcPosCatalog.POS_NOUN_GENERAL, true, false, false)
        )
        // Risky over-span touching trailing particle boundary gets clamped confidence (<= 0.25f)
        val clamped = engine.computeLengthBonus("きょうは", 6000, MozcPosCatalog.POS_NOUN_GENERAL, false, false, false)
        assertEquals((3 * KanaKanjiEngine.DEFAULT_LENGTH_BONUS_PER_CHAR * 0.25f).toInt(), clamped)
    }
}
