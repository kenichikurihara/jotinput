package com.example.myasr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class MmapDictionaryTest {

    private lateinit var dict: MmapDictionary

    @Before
    fun setUp() {
        val binFile = listOf(
            File("tools/mozc_dict/system_dictionary.bin"),
            File("app/src/test/resources/mozc/system_dictionary.bin"),
            File("src/test/resources/mozc/system_dictionary.bin"),
            File("../app/src/test/resources/mozc/system_dictionary.bin"),
            File("../tools/mozc_dict/system_dictionary.bin")
        ).firstOrNull { it.exists() && it.length() > 64 }

        requireNotNull(binFile) { "system_dictionary.bin not found for test" }
        val loaded = MmapDictionary.loadFromFile(binFile)
        requireNotNull(loaded) { "Failed to load MmapDictionary" }
        dict = loaded
    }

    @Test
    fun testExactLookup() {
        val tokensKyou = dict.getExactTokens("きょう")
        assertNotNull(tokensKyou)
        assertTrue("Tokens for きょう should not be empty", tokensKyou!!.isNotEmpty())
        assertTrue("Tokens should contain 今日", tokensKyou.any { it.surface == "今日" })

        val tokensNan = dict.getExactTokens("なん")
        assertNotNull(tokensNan)
        assertTrue("Tokens should contain 何 with lid=1906", tokensNan!!.any { it.surface == "何" && it.lid == 1906 })

        val tokensKouho = dict.getExactTokens("こうほ")
        assertNotNull(tokensKouho)
        assertTrue("Tokens should contain 候補 with lid=1851", tokensKouho!!.any { it.surface == "候補" && it.lid == 1851 })

        val tokensNashi = dict.getExactTokens("なし")
        assertNotNull(tokensNashi)
        assertTrue("Tokens should contain なし with lid=1975", tokensNashi!!.any { it.surface == "なし" && it.lid == 1975 })

        val tokensIketa = dict.getExactTokens("いけた")
        assertNotNull(tokensIketa)
        assertTrue("Tokens should contain 行けた in unpruned dictionary", tokensIketa!!.any { it.surface == "行けた" })
    }

    @Test
    fun testPrefixLookup() {
        val prefixes = dict.getPrefixTokens("とうきょう", maxCount = 10)
        assertTrue("Prefix matches for とうきょう should not be empty", prefixes.isNotEmpty())
        assertTrue("Prefix matches should start with とうきょう", prefixes.all { it.first.startsWith("とうきょう") })
    }

    @Test
    fun testHasAnyPrefix() {
        assertTrue("Should have prefix for とうきょう", dict.hasAnyPrefix("とうきょう"))
        assertTrue("Should have prefix for いけ", dict.hasAnyPrefix("いけ"))
        assertTrue("Should not have prefix for non-existent reading", !dict.hasAnyPrefix("んんんんんん"))
    }
}
