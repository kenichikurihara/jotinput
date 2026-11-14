package com.example.myasr

import com.example.myasr.context.ime.presentation.CandidateStripView
import com.example.myasr.context.kanakanji.domain.ConversionCandidate
import org.junit.Assert.assertEquals
import org.junit.Test

class CandidateStripViewDeduplicationTest {

    @Test
    fun testFilterAndDeduplicateDisplayCandidatesRemovesWhitespaceVariantPeriods() {
        val raw = listOf(
            ConversionCandidate(surface = "。", reading = "。", isPrediction = true, source = "punctuation_suggestion"),
            ConversionCandidate(surface = "。\n", reading = "。\n", isPrediction = true, source = "slm"),
            ConversionCandidate(surface = " 。", reading = " 。", isPrediction = true, source = "slm"),
            ConversionCandidate(surface = "　。 ", reading = "　。 ", isPrediction = true, source = "slm"),
            ConversionCandidate(surface = "また、", reading = "また、", isPrediction = true, source = "slm"),
            ConversionCandidate(surface = "また、\n", reading = "また、\n", isPrediction = true, source = "slm"),
            ConversionCandidate(surface = "   ", reading = "   ", isPrediction = true, source = "slm")
        )

        val filtered = CandidateStripView.filterAndDeduplicateDisplayCandidates(raw)
        assertEquals(listOf("。", "また、"), filtered.map { it.surface })
    }
}
