package com.example.myasr

import com.example.myasr.context.kanakanji.domain.ConversionCandidate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Unit and integration tests for English Neural Spelling Correction & Typo Correction.
 */
class EnglishSpellEngineTest {

    @Test
    fun testModelFileExistenceAndIntegrityInTools() {
        val candidates = listOf(
            File("tools/models/english_spelling"),
            File("../tools/models/english_spelling")
        )
        val modelDir = candidates.firstOrNull { it.exists() }
        assertTrue("Model dir must exist in tools/ or ../tools/", modelDir != null && modelDir.exists())

        val encFile = File(modelDir!!, "gec-encoder-int8.onnx")
        val decFile = File(modelDir, "gec-decoder-int8.onnx")
        val spmFile = File(modelDir, "spm_unigram.bin")

        assertTrue("Encoder must exist", encFile.exists() && encFile.length() > 5_000_000L)
        assertTrue("Decoder must exist", decFile.exists() && decFile.length() > 10_000_000L)
        assertTrue("Tokenizer binary must exist", spmFile.exists() && spmFile.length() > 200_000L)
    }

    @Test
    fun testCandidateTypoCreation() {
        val cand = ConversionCandidate(
            surface = "the",
            reading = "teh",
            isPrediction = false,
            source = "english_typo"
        )
        assertEquals("the", cand.surface)
        assertEquals("teh", cand.reading)
        assertFalse(cand.isPrediction)
        assertEquals("english_typo", cand.source)
    }

    @Test
    fun testLongWordCompletionCandidateCreation() {
        // Test example completion: examp -> example
        val cand = ConversionCandidate(
            surface = "example",
            reading = "examp",
            isPrediction = false,
            source = "english_typo"
        )
        assertEquals("example", cand.surface)
        assertEquals("examp", cand.reading)
        assertTrue(cand.surface.length > cand.reading.length)
        assertTrue(cand.surface.startsWith(cand.reading))
    }

    @Test
    fun testCandidateGecCreation() {
        val cand = ConversionCandidate(
            surface = "I can't believe it's already Friday.",
            reading = "i cant beleive its alredy friday",
            isPrediction = false,
            source = "english_gec"
        )
        assertEquals("I can't believe it's already Friday.", cand.surface)
        assertEquals("english_gec", cand.source)
    }
}
