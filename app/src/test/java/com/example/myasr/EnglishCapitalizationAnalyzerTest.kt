package com.example.myasr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnglishCapitalizationAnalyzerTest {

    @Test
    fun testSentenceStartDetection() {
        // Empty context (beginning of text)
        assertTrue(EnglishCapitalizationAnalyzer.isSentenceStart("hello", "hello"))

        // After period with space
        assertTrue(EnglishCapitalizationAnalyzer.isSentenceStart("world", "This is fine. world"))

        // After exclamation and question marks
        assertTrue(EnglishCapitalizationAnalyzer.isSentenceStart("how", "Great! how"))
        assertTrue(EnglishCapitalizationAnalyzer.isSentenceStart("what", "Really? what"))

        // After newline
        assertTrue(EnglishCapitalizationAnalyzer.isSentenceStart("start", "first line\nstart"))

        // Inside opening quotes at sentence start
        assertTrue(EnglishCapitalizationAnalyzer.isSentenceStart("hello", "\"hello"))
        assertTrue(EnglishCapitalizationAnalyzer.isSentenceStart("yes", "He said. \"yes"))

        // Mid-sentence should NOT be sentence start
        assertFalse(EnglishCapitalizationAnalyzer.isSentenceStart("world", "hello world"))
        assertFalse(EnglishCapitalizationAnalyzer.isSentenceStart("cat", "the black cat"))
    }

    @Test
    fun testPronounIAndContractions() {
        val resI = EnglishCapitalizationAnalyzer.analyze("i", "so i", isShiftActive = false)
        assertEquals(EnglishCapitalizationAnalyzer.CapitalizationType.PRONOUN_I, resI.type)
        assertEquals("I", resI.primaryCandidate)

        val resIm = EnglishCapitalizationAnalyzer.analyze("im", "today im", isShiftActive = false)
        assertEquals("I'm", resIm.primaryCandidate)

        val resIve = EnglishCapitalizationAnalyzer.analyze("ive", "ive", isShiftActive = false)
        assertEquals("I've", resIve.primaryCandidate)

        val resId = EnglishCapitalizationAnalyzer.analyze("id", "i think id", isShiftActive = false)
        assertEquals("I'd", resId.primaryCandidate)
        assertTrue(resId.additionalCandidates.contains("ID"))
    }

    @Test
    fun testAcronyms() {
        val resUsa = EnglishCapitalizationAnalyzer.analyze("usa", "welcome to usa", isShiftActive = false)
        assertEquals(EnglishCapitalizationAnalyzer.CapitalizationType.ALL_CAPS, resUsa.type)
        assertEquals("USA", resUsa.primaryCandidate)

        val resAi = EnglishCapitalizationAnalyzer.analyze("ai", "generative ai", isShiftActive = false)
        assertEquals(EnglishCapitalizationAnalyzer.CapitalizationType.ALL_CAPS, resAi.type)
        assertEquals("AI", resAi.primaryCandidate)

        val resFyi = EnglishCapitalizationAnalyzer.analyze("fyi", "fyi", isShiftActive = false)
        assertEquals("FYI", resFyi.primaryCandidate)
    }

    @Test
    fun testProperNouns() {
        val resMonday = EnglishCapitalizationAnalyzer.analyze("monday", "see you monday", isShiftActive = false)
        assertEquals(EnglishCapitalizationAnalyzer.CapitalizationType.PROPER_NOUN, resMonday.type)
        assertEquals("Monday", resMonday.primaryCandidate)

        val resTokyo = EnglishCapitalizationAnalyzer.analyze("tokyo", "fly to tokyo", isShiftActive = false)
        assertEquals("Tokyo", resTokyo.primaryCandidate)
    }

    @Test
    fun testSentenceStartCapitalization() {
        val res = EnglishCapitalizationAnalyzer.analyze("hello", "hello", isShiftActive = false)
        assertEquals(EnglishCapitalizationAnalyzer.CapitalizationType.SENTENCE_START, res.type)
        assertEquals("Hello", res.primaryCandidate)
    }

    @Test
    fun testShiftActiveOrAlreadyUppercase() {
        // User already typed 'H'
        val resUpper = EnglishCapitalizationAnalyzer.analyze("Hello", "Hello", isShiftActive = false)
        assertEquals(EnglishCapitalizationAnalyzer.CapitalizationType.NONE, resUpper.type)

        // Shift is active
        val resShift = EnglishCapitalizationAnalyzer.analyze("hello", "hello", isShiftActive = true)
        assertEquals(EnglishCapitalizationAnalyzer.CapitalizationType.NONE, resShift.type)
    }
}
