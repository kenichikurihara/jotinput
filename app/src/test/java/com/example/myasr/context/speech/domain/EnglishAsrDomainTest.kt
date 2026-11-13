package com.example.myasr.context.speech.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class EnglishAsrDomainTest {

    @Test
    fun `AsrLanguagePolicy selects ENGLISH only when both English mode and model are available`() {
        assertEquals(
            AsrLanguage.ENGLISH,
            AsrLanguagePolicy.resolveLanguage(isEnglishKeyboardMode = true, isEnglishModelAvailable = true)
        )
        assertEquals(
            AsrLanguage.JAPANESE,
            AsrLanguagePolicy.resolveLanguage(isEnglishKeyboardMode = true, isEnglishModelAvailable = false)
        )
        assertEquals(
            AsrLanguage.JAPANESE,
            AsrLanguagePolicy.resolveLanguage(isEnglishKeyboardMode = false, isEnglishModelAvailable = true)
        )
        assertEquals(
            AsrLanguage.JAPANESE,
            AsrLanguagePolicy.resolveLanguage(isEnglishKeyboardMode = false, isEnglishModelAvailable = false)
        )
    }

    @Test
    fun `EnglishVoicePostProcessor preserves native punctuation and capitalization`() {
        assertEquals(
            "Hello, world! How are you?",
            EnglishVoicePostProcessor.format("  Hello,   world!  How are you?  ", "")
        )
    }

    @Test
    fun `EnglishVoicePostProcessor inserts leading space after word or punctuation without trailing space`() {
        assertEquals(
            " Next sentence.",
            EnglishVoicePostProcessor.format("Next sentence.", "Hello world.")
        )
        assertEquals(
            "world",
            EnglishVoicePostProcessor.format("world", "Hello ")
        )
        assertEquals(
            " world",
            EnglishVoicePostProcessor.format("world", "Hello")
        )
        assertEquals(
            "",
            EnglishVoicePostProcessor.format("   ", "Hello")
        )
    }

    @Test
    fun `EnglishVoicePostProcessor extractWordAwareFreezePrefix snaps to word boundaries`() {
        val history = listOf(
            "Hello world this is a test",
            "Hello world this is a testing",
            "Hello world this is a test case"
        )
        // Length = 31; 31 - 12 = 19 lands on the space after "is"
        val (frozen, active) = EnglishVoicePostProcessor.extractWordAwareFreezePrefix(
            history = history,
            currentFormatted = "Hello world this is a test case",
            minConsecutiveSteps = 3,
            protectTrailingChars = 12
        )
        assertEquals("Hello world this is", frozen)
        assertEquals(" a test case", active)
        assertEquals("Hello world this is a test case", frozen + active)

        // 31 - 8 = 23 lands mid-word inside "test"; must snap back to space after "a"
        val (frozenMid, activeMid) = EnglishVoicePostProcessor.extractWordAwareFreezePrefix(
            history = history,
            currentFormatted = "Hello world this is a test case",
            minConsecutiveSteps = 3,
            protectTrailingChars = 8
        )
        assertEquals("Hello world this is a ", frozenMid)
        assertEquals("test case", activeMid)
        assertEquals("Hello world this is a test case", frozenMid + activeMid)
    }
}
