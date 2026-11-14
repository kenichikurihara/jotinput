package com.example.myasr.context.speech.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCorrectionScoringTest {

    @Test
    fun `non-ambiguous orthographic and proper noun fixes auto-replace on 1-shot`() {
        val entry = VoiceCorrectionEntry(
            language = AsrLanguage.JAPANESE,
            originalSurface = "山崎",
            correctedSurface = "山﨑",
            normalizedReading = "やまざき",
            correctionCount = 1
        )
        val result = VoiceCorrectionScoring.applyCorrections(
            text = "山崎さんに連絡します。",
            contextBefore = "",
            entries = listOf(entry),
            language = AsrLanguage.JAPANESE
        )
        assertEquals("山﨑さんに連絡します。", result.formattedText)
        assertEquals(1, result.appliedCorrections.size)
        assertTrue(result.suggestedChips.isEmpty())
    }

    @Test
    fun `English 1-shot proper noun and compound fixes apply cleanly`() {
        val entry = VoiceCorrectionEntry(
            language = AsrLanguage.ENGLISH,
            originalSurface = "pie torch",
            correctedSurface = "PyTorch",
            normalizedReading = "pytorch",
            correctionCount = 1
        )
        val result = VoiceCorrectionScoring.applyCorrections(
            text = "We trained the model with pie torch.",
            contextBefore = "",
            entries = listOf(entry),
            language = AsrLanguage.ENGLISH
        )
        assertEquals("We trained the model with PyTorch.", result.formattedText)
        assertEquals(1, result.appliedCorrections.size)
    }

    @Test
    fun `ambiguous homophone uses 1-shot chip and SLM coherence gate blocks wrong context`() {
        val entry = VoiceCorrectionEntry(
            language = AsrLanguage.JAPANESE,
            originalSurface = "記者",
            correctedSurface = "貴社",
            normalizedReading = "きしゃ",
            correctionCount = 3
        )
        // Adversarial context: SLM strongly prefers original "記者" (high deltaNll = +8.5 nats)
        val gatedResult = VoiceCorrectionScoring.applyCorrections(
            text = "新聞の記者が取材に来ました。",
            contextBefore = "",
            entries = listOf(entry),
            language = AsrLanguage.JAPANESE,
            slmCoherenceDeltaProvider = { _, _ -> 8.5f }
        )
        assertEquals("新聞の記者が取材に来ました。", gatedResult.formattedText)
        assertTrue(gatedResult.appliedCorrections.isEmpty())
        assertEquals(1, gatedResult.suggestedChips.size)
        assertEquals(BoostAction.GATE_OFF_KEEP_ORIGINAL_WITH_CHIP, gatedResult.suggestedChips.first().action)

        // Coherent business context: SLM favors "貴社" (deltaNll = -2.0 nats)
        val coherentResult = VoiceCorrectionScoring.applyCorrections(
            text = "記者のご提案を拝見しました。",
            contextBefore = "お世話になっております。",
            entries = listOf(entry),
            language = AsrLanguage.JAPANESE,
            slmCoherenceDeltaProvider = { _, _ -> -2.0f }
        )
        assertEquals("貴社のご提案を拝見しました。", coherentResult.formattedText)
        assertEquals(1, coherentResult.appliedCorrections.size)
    }

    @Test
    fun `revert feedback self-heals and suppresses future replacements`() {
        val revertedEntry = VoiceCorrectionEntry(
            language = AsrLanguage.ENGLISH,
            originalSurface = "write",
            correctedSurface = "right",
            normalizedReading = "right",
            correctionCount = 2,
            acceptCount = 0,
            revertCount = 1 // netCount = 2 + 0 - 2*1 = 0
        )
        assertEquals(0, revertedEntry.netCount())
        assertEquals(0f, revertedEntry.computeHotwordScore())

        val result = VoiceCorrectionScoring.applyCorrections(
            text = "Please write the code.",
            contextBefore = "",
            entries = listOf(revertedEntry),
            language = AsrLanguage.ENGLISH
        )
        assertEquals("Please write the code.", result.formattedText)
        assertTrue(result.appliedCorrections.isEmpty())
        assertTrue(result.suggestedChips.isEmpty())
    }
}
