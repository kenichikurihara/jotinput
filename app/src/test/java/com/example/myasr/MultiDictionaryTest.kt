package com.example.myasr

import com.example.myasr.context.provisioning.infrastructure.DictionaryType

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiDictionaryTest {

    @Test
    fun testDictionaryTypesConfigured() {
        val types = DictionaryType.values()
        assertEquals(6, types.size)

        val mozcGeneral = DictionaryType.MOZC_GENERAL
        assertEquals(10, mozcGeneral.downloadUrls.size)
        assertTrue(mozcGeneral.downloadUrls[0].endsWith("dictionary00.txt"))
        assertTrue(mozcGeneral.downloadUrls[9].endsWith("dictionary09.txt"))

        val sudachidict = DictionaryType.SUDACHIDICT
        assertTrue(sudachidict.isBz2)
        assertEquals(1, sudachidict.downloadUrls.size)
        assertTrue(sudachidict.downloadUrls[0].contains("mozcdic-ut-sudachidict"))

        val jawiki = DictionaryType.JAWIKI
        assertTrue(jawiki.isBz2)

        val places = DictionaryType.PLACE_NAMES
        assertTrue(places.isBz2)

        val names = DictionaryType.PERSONAL_NAMES
        assertTrue(names.isBz2)

        val emoji = DictionaryType.EMOJI_SYMBOLS
        assertTrue(emoji.isCustomParser)
        assertEquals(4, emoji.downloadUrls.size)
    }

    @Test
    fun testDictionaryTypeFromId() {
        assertNotNull(DictionaryType.fromId("mozc_general"))
        assertNotNull(DictionaryType.fromId("sudachidict"))
        assertNotNull(DictionaryType.fromId("jawiki"))
        assertNotNull(DictionaryType.fromId("place_names"))
        assertNotNull(DictionaryType.fromId("personal_names"))
        assertNotNull(DictionaryType.fromId("emoji_symbols"))
    }
}
