package com.example.myasr

import com.example.myasr.context.english.domain.BayesianCaseRanker
import com.example.myasr.context.english.domain.EnglishCaseModel
import com.example.myasr.context.english.domain.InputFieldCapPolicy
import com.example.myasr.context.provisioning.infrastructure.EnglishOtaLexiconCompiler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files

class EnglishOtaOverlayTest {

    @After
    fun tearDown() {
        // Always restore bundled-only snapshot so no OTA test state leaks across tests
        EnglishCaseModel.reloadWithOtaOverlay(null)
    }

    private fun rankEnglish(rawWord: String): List<String> {
        val suggestions = EnglishCaseModel.suggestPrefixAndTypo(rawWord, maxCandidates = 6)
        return BayesianCaseRanker.buildEnglishCandidates(
            rawWord = rawWord,
            bosProb = 0.0f,
            fieldPolicy = InputFieldCapPolicy.STANDARD,
            isShiftActive = false,
            userCandidates = emptyList(),
            lexiconSuggestions = suggestions
        ).map { it.surface }
    }

    @Test
    fun `streaming Homebrew compiler extracts modern OOV brand terms, strips noise, and hot-reloads into Rank 1`() {
        val formulaJson = """
            [
              {
                "name": "opentofu",
                "desc": "Declarative tool compatible with ZenQloProvisioner infrastructure.",
                "urls": {"stable": {"name": "ShouldIgnoreNestedKey"}}
              },
              {
                "name": "tail",
                "desc": "SHOWS ONLY TRAILING LINES IN ALL CAPS HEADLINE."
              }
            ]
        """.trimIndent()

        val caskJson = """
            [
              {
                "token": "zyxorbstack",
                "name": ["ZyxOrbStack"],
                "desc": "Run fast Linux machines alongside ZyxOrbStack desktop tooling."
              },
              {
                "token": "zyxqwiktool",
                "name": ["ZyxQwikTool"],
                "desc": "Collaborate smoothly with ZyxQwikTool CLI and https://example.com/IgnoredPath."
              },
              {
                "token": "the",
                "name": ["The"],
                "desc": "Attempt to hijack a short core English word."
              }
            ]
        """.trimIndent()

        val otaFile = Files.createTempFile("jotinput_ota_test_", ".tsv").toFile()
        try {
            val writtenCount = EnglishOtaLexiconCompiler.compileFromHomebrewStreams(
                formulaStream = ByteArrayInputStream(formulaJson.toByteArray(Charsets.UTF_8)),
                caskStream = ByteArrayInputStream(caskJson.toByteArray(Charsets.UTF_8)),
                outputFile = otaFile
            )
            assertTrue("Expected compiled OTA entries >= 2, got $writtenCount", writtenCount >= 2)

            val content = otaFile.readText(Charsets.UTF_8)
            assertTrue("Expected zyxorbstack in compiled OTA TSV", content.contains("zyxorbstack\t"))
            assertTrue("Expected ZyxOrbStack surface in compiled OTA TSV", content.contains("ZyxOrbStack:"))
            assertTrue("Expected zyxqwiktool in compiled OTA TSV", content.contains("zyxqwiktool\t"))
            // Noise guards: nested URL key, URL paths, and core short word must never enter OTA TSV
            assertTrue("Nested JSON key must not leak", !content.contains("shouldignorenestedkey"))
            assertTrue("URL path token must not leak", !content.contains("ignoredpath"))
            assertTrue("Core short word 'the' must not leak", !content.lines().any { it.startsWith("the\t") })

            val admitted = EnglishCaseModel.reloadWithOtaOverlay(otaFile)
            assertTrue("Expected admitted OTA count >= 2, got $admitted", admitted >= 2)
            assertEquals(admitted, EnglishCaseModel.getOtaOverlayEntryCount())

            // Hot-reloaded OOV terms immediately promote to Rank 1 on prefix input
            assertEquals("ZyxOrbStack", rankEnglish("zyxorbs").firstOrNull())
            assertEquals("ZyxQwikTool", rankEnglish("zyxqwik").firstOrNull())

            // Deleting / clearing OTA cleanly reverts to bundled lexicon
            assertTrue(otaFile.delete())
            val afterDelete = EnglishCaseModel.reloadWithOtaOverlay(null)
            assertEquals(0, afterDelete)
            assertEquals(0, EnglishCaseModel.getOtaOverlayEntryCount())
            assertNull(EnglishCaseModel.lookupLexicon("zyxorbstack"))
        } finally {
            otaFile.delete()
        }
    }

    @Test
    fun `OTA prebuilt TSV admission protects bundled core short words and ignores malformed lines`() {
        val rawTsv = """
            # Comment line
            badline_missing_cols
            a	9.000	A:1.0000
            tail	9.500	TAIL:1.0000
            the	12.000	THE:1.0000
            qzxvortex	3.500	QzxVortex:1.0000
            badfreq	-1.0	BadFreq:1.0000
        """.trimIndent()

        val otaFile = Files.createTempFile("jotinput_ota_tsv_", ".tsv").toFile()
        try {
            val installedCount = EnglishOtaLexiconCompiler.installPrebuiltTsvStream(
                ByteArrayInputStream(rawTsv.toByteArray(Charsets.UTF_8)),
                otaFile
            )
            assertTrue("Expected valid TSV rows installed", installedCount >= 3)
            assertEquals(installedCount, EnglishOtaLexiconCompiler.countValidEntries(otaFile))

            val admitted = EnglishCaseModel.reloadWithOtaOverlay(otaFile)
            assertEquals("Only non-core valid OOV word should be admitted", 1, admitted)

            // Verify new OOV term is Rank 1
            assertEquals("QzxVortex", rankEnglish("qzxvor").firstOrNull())

            // Core short words 'tail' and 'the' must NOT be hijacked by OTA
            assertEquals("tail", rankEnglish("tail").firstOrNull())
            assertEquals("the", rankEnglish("the").firstOrNull())
            assertNotEquals("TAIL", EnglishCaseModel.lookupLexicon("tail")?.variants?.firstOrNull()?.surface)
        } finally {
            otaFile.delete()
        }
    }
}
