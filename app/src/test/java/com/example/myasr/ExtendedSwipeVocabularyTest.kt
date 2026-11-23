package com.example.myasr

import com.example.myasr.context.english.domain.BayesianCaseRanker
import com.example.myasr.context.english.domain.EnglishCaseModel
import com.example.myasr.context.english.domain.InputFieldCapPolicy
import com.example.myasr.context.english.domain.SwipeVocabularyJudgement
import com.example.myasr.context.english.infrastructure.SwipeAdaptiveCacheRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files

class ExtendedSwipeVocabularyTest {

    @After
    fun tearDown() {
        EnglishCaseModel.reloadWithOtaOverlay(null)
    }

    @Test
    fun `DMN SwipeExtendedPass2TriggerPolicy enforces zero-cost gate and skips missing lexicon`() {
        val noLex = SwipeVocabularyJudgement.tracePass2Trigger(
            hasExtendedLexicon = false,
            bestBaseCtcLogProb = -15.0f
        )
        assertEquals("SWIPE_EXT_R1", noLex.selectedRuleId)
        assertEquals(SwipeVocabularyJudgement.Pass2TriggerAction.SKIP_NO_LEXICON, noLex.result.decision)

        val confidentBase = SwipeVocabularyJudgement.tracePass2Trigger(
            hasExtendedLexicon = true,
            bestBaseCtcLogProb = -0.10f
        )
        assertEquals("SWIPE_EXT_R2", confidentBase.selectedRuleId)
        assertEquals(SwipeVocabularyJudgement.Pass2TriggerAction.SKIP_BASE_CONFIDENT, confidentBase.result.decision)

        val oovTrace = SwipeVocabularyJudgement.tracePass2Trigger(
            hasExtendedLexicon = true,
            bestBaseCtcLogProb = -8.40f
        )
        assertEquals("SWIPE_EXT_DEFAULT", oovTrace.selectedRuleId)
        assertEquals(SwipeVocabularyJudgement.Pass2TriggerAction.RUN_EXTENDED_PASS2, oovTrace.result.decision)
    }

    @Test
    fun `DMN SwipeAdaptiveCacheAdmissionPolicy enforces privacy, typo guard, and base isolation`() {
        val sensitive = SwipeVocabularyJudgement.traceAdaptiveCacheAdmission(
            SwipeVocabularyJudgement.AdaptiveCacheAdmissionInput(
                isSensitiveOrPasswordField = true,
                isValidSwipeToken = true,
                isExplicitUserDictWord = false,
                isAlreadyInBase32kVocab = false,
                isKnownInExtendedLexicon = false,
                commitSource = SwipeVocabularyJudgement.SwipeLearnSource.TAP_CANDIDATE_SELECTED,
                observedCount = 3
            )
        )
        assertEquals("SWIPE_CACHE_R1", sensitive.selectedRuleId)
        assertEquals(
            SwipeVocabularyJudgement.AdaptiveCacheAdmissionAction.REJECT_SENSITIVE_OR_BYPASS,
            sensitive.result.decision
        )

        val invalid = SwipeVocabularyJudgement.judgeAdaptiveCacheAdmission(
            SwipeVocabularyJudgement.AdaptiveCacheAdmissionInput(
                isSensitiveOrPasswordField = false,
                isValidSwipeToken = false,
                isExplicitUserDictWord = false,
                isAlreadyInBase32kVocab = false,
                isKnownInExtendedLexicon = false,
                commitSource = SwipeVocabularyJudgement.SwipeLearnSource.TAP_CANDIDATE_SELECTED,
                observedCount = 1
            )
        )
        assertEquals(SwipeVocabularyJudgement.AdaptiveCacheAdmissionAction.REJECT_INVALID_TOKEN, invalid.decision)

        val userDict = SwipeVocabularyJudgement.judgeAdaptiveCacheAdmission(
            SwipeVocabularyJudgement.AdaptiveCacheAdmissionInput(
                isSensitiveOrPasswordField = false,
                isValidSwipeToken = true,
                isExplicitUserDictWord = true,
                isAlreadyInBase32kVocab = false,
                isKnownInExtendedLexicon = false,
                commitSource = SwipeVocabularyJudgement.SwipeLearnSource.TAP_CANDIDATE_SELECTED,
                observedCount = 1
            )
        )
        assertEquals(SwipeVocabularyJudgement.AdaptiveCacheAdmissionAction.SKIP_COVERED_BY_USER_DICT, userDict.decision)

        val base32k = SwipeVocabularyJudgement.judgeAdaptiveCacheAdmission(
            SwipeVocabularyJudgement.AdaptiveCacheAdmissionInput(
                isSensitiveOrPasswordField = false,
                isValidSwipeToken = true,
                isExplicitUserDictWord = false,
                isAlreadyInBase32kVocab = true,
                isKnownInExtendedLexicon = true,
                commitSource = SwipeVocabularyJudgement.SwipeLearnSource.TAP_CANDIDATE_SELECTED,
                observedCount = 1
            )
        )
        assertEquals(SwipeVocabularyJudgement.AdaptiveCacheAdmissionAction.SKIP_COVERED_BY_BASE_VOCAB, base32k.decision)

        val singleRawTypo = SwipeVocabularyJudgement.judgeAdaptiveCacheAdmission(
            SwipeVocabularyJudgement.AdaptiveCacheAdmissionInput(
                isSensitiveOrPasswordField = false,
                isValidSwipeToken = true,
                isExplicitUserDictWord = false,
                isAlreadyInBase32kVocab = false,
                isKnownInExtendedLexicon = false,
                commitSource = SwipeVocabularyJudgement.SwipeLearnSource.TAP_SPACE_COMMITTED,
                observedCount = 1
            )
        )
        assertEquals(
            SwipeVocabularyJudgement.AdaptiveCacheAdmissionAction.DEFER_UNVERIFIED_SINGLE_TYPO_GUARD,
            singleRawTypo.decision
        )

        val repeatedRaw = SwipeVocabularyJudgement.judgeAdaptiveCacheAdmission(
            SwipeVocabularyJudgement.AdaptiveCacheAdmissionInput(
                isSensitiveOrPasswordField = false,
                isValidSwipeToken = true,
                isExplicitUserDictWord = false,
                isAlreadyInBase32kVocab = false,
                isKnownInExtendedLexicon = false,
                commitSource = SwipeVocabularyJudgement.SwipeLearnSource.TAP_SPACE_COMMITTED,
                observedCount = 2
            )
        )
        assertEquals(SwipeVocabularyJudgement.AdaptiveCacheAdmissionAction.ADMIT_TO_ADAPTIVE_CACHE, repeatedRaw.decision)
    }

    @Test
    fun `token validation and URL or code extraction filters noise cleanly`() {
        assertTrue(SwipeVocabularyJudgement.isValidAdaptiveSwipeToken("Anthropic"))
        assertTrue(SwipeVocabularyJudgement.isValidAdaptiveSwipeToken("don't"))
        assertTrue(SwipeVocabularyJudgement.isValidAdaptiveSwipeToken("Wi-Fi"))
        assertFalse(SwipeVocabularyJudgement.isValidAdaptiveSwipeToken("go"))
        assertFalse(SwipeVocabularyJudgement.isValidAdaptiveSwipeToken("v123"))
        assertFalse(SwipeVocabularyJudgement.isValidAdaptiveSwipeToken("SUPERLONGUPPERCASECONST"))

        val extracted = SwipeVocabularyJudgement.extractEnglishTokensForLearning(
            "Use Anthropic & Supabase via https://tailscale.com/docs user@example.com snake_case foo=bar, ok!"
        )
        assertEquals(listOf("Use", "Anthropic", "Supabase", "via"), extracted)
    }

    @Test
    fun `EnglishCaseModel exports extended swipe lexicon with canonical casing, fast skip, and OTA overlay`() {
        val outFile = Files.createTempFile("jotinput_swipe_ext_test_", ".tsv").toFile()
        val otaFile = Files.createTempFile("jotinput_swipe_ota_src_", ".tsv").toFile()
        try {
            val count = EnglishCaseModel.exportSwipeExtendedLexiconIfNeeded(outFile, force = true)
            assertTrue("Expected >= 80,000 pure ASCII extended words, got $count", count >= 80_000)
            val lines = outFile.readLines(Charsets.UTF_8)
            assertTrue(lines.first().startsWith("# jotinput_swipe_ext_v1 count=$count ota=0"))
            assertTrue(lines.any { it.startsWith("tailscale\tTailscale\t") })
            assertTrue(lines.any { it.startsWith("kubernetes\tKubernetes\t") })
            assertTrue(lines.any { it.startsWith("postgresql\tPostgreSQL\t") })

            // Header-match fast skip leaves mtime untouched
            val mtimeBefore = outFile.lastModified()
            val skippedCount = EnglishCaseModel.exportSwipeExtendedLexiconIfNeeded(outFile, force = false)
            assertEquals(count, skippedCount)
            assertEquals(mtimeBefore, outFile.lastModified())

            // Adding an OTA overlay updates exported swipe lexicon
            otaFile.writeText("zenotapkg\t3.900\tZenOtaPkg:1.0000\n", Charsets.UTF_8)
            val admitted = EnglishCaseModel.reloadWithOtaOverlay(otaFile)
            assertEquals(1, admitted)

            val updatedCount = EnglishCaseModel.exportSwipeExtendedLexiconIfNeeded(outFile, force = false)
            assertEquals(count + 1, updatedCount)
            val updatedText = outFile.readText(Charsets.UTF_8)
            assertTrue(updatedText.startsWith("# jotinput_swipe_ext_v1 count=$updatedCount ota=1"))
            assertTrue(updatedText.contains("zenotapkg\tZenOtaPkg\t3.900"))
        } finally {
            outFile.delete()
            otaFile.delete()
        }
    }

    @Test
    fun `BayesianCaseRanker buildSwipeCandidates preserves Rank 1 and unknown OOV mixed case`() {
        // Low-frequency Rank 1 OOV word must never be overtaken by high-frequency Rank 2 common word
        val tailscaleRanked = BayesianCaseRanker.buildSwipeCandidates(
            swipeCandidates = listOf("Tailscale", "table", "tales"),
            bosProb = 0.0f,
            fieldPolicy = InputFieldCapPolicy.STANDARD,
            isShiftActive = false
        )
        assertEquals("Tailscale", tailscaleRanked.first().surface)
        assertTrue(tailscaleRanked.map { it.surface }.containsAll(listOf("Tailscale", "table", "tales")))

        // Adaptive cache OOV word absent from EnglishCaseModel must keep exact mixed-case spelling
        val adaptiveOov = BayesianCaseRanker.buildSwipeCandidates(
            swipeCandidates = listOf("JotInput", "horrible", "horribly"),
            bosProb = 0.0f,
            fieldPolicy = InputFieldCapPolicy.STANDARD,
            isShiftActive = false
        )
        assertEquals("JotInput", adaptiveOov.first().surface)

        // Sentence-start BOS capitalizes lowercase words and offers dual case option for acronyms when appropriate
        val bosHello = BayesianCaseRanker.buildSwipeCandidates(
            swipeCandidates = listOf("hello", "help"),
            bosProb = 1.0f,
            fieldPolicy = InputFieldCapPolicy.STANDARD,
            isShiftActive = false
        )
        assertEquals("Hello", bosHello.first().surface)

        // Explicit Shift capitalizes common lowercase word
        val shifted = BayesianCaseRanker.buildSwipeCandidates(
            swipeCandidates = listOf("neovim", "moving"),
            bosProb = 0.0f,
            fieldPolicy = InputFieldCapPolicy.STANDARD,
            isShiftActive = true
        )
        assertEquals("Neovim", shifted.first().surface)

        // Bypass mode keeps exact verbatim strings in exact order
        val bypass = BayesianCaseRanker.buildSwipeCandidates(
            swipeCandidates = listOf("hello", "PostgreSQL"),
            bosProb = 1.0f,
            fieldPolicy = InputFieldCapPolicy.BYPASS,
            isShiftActive = false
        )
        assertEquals(listOf("hello", "PostgreSQL"), bypass.map { it.surface })
    }

    @Test
    fun `SwipeAdaptiveCacheRepository enforces 1-shot typo guard, sensitive exclusion, LRU eviction, and persistence`() {
        val tempDir = Files.createTempDirectory("jotinput_adaptive_cache_test_").toFile()
        val cacheFile = java.io.File(tempDir, SwipeAdaptiveCacheRepository.FILE_NAME)
        val customDictFile = java.io.File(tempDir, "custom_dictionary.json")
        customDictFile.writeText("[{\"word\":\"MyCustomTerm\",\"reading\":\"mycustomterm\"}]", Charsets.UTF_8)
        try {
            val repo = SwipeAdaptiveCacheRepository(storageFile = cacheFile, maxEntries = 3)

            // 1. Sensitive password field never caches
            val sensAct = repo.recordCommittedWord(
                surface = "SecretBrand",
                source = SwipeVocabularyJudgement.SwipeLearnSource.TAP_CANDIDATE_SELECTED,
                isSensitiveOrPasswordField = true,
                isExplicitUserDictWord = false,
                isAlreadyInBase32kVocab = false
            )
            assertEquals(SwipeVocabularyJudgement.AdaptiveCacheAdmissionAction.REJECT_SENSITIVE_OR_BYPASS, sensAct)
            assertEquals(0, repo.getActiveCount())

            // 2. Single raw space tap of unknown word -> deferred (1-shot typo guard)
            val deferAct = repo.recordCommittedWord(
                surface = "tailscalx",
                source = SwipeVocabularyJudgement.SwipeLearnSource.TAP_SPACE_COMMITTED,
                isSensitiveOrPasswordField = false,
                isExplicitUserDictWord = false,
                isAlreadyInBase32kVocab = false
            )
            assertEquals(SwipeVocabularyJudgement.AdaptiveCacheAdmissionAction.DEFER_UNVERIFIED_SINGLE_TYPO_GUARD, deferAct)
            assertEquals(0, repo.getActiveCount())

            // 3. Second commit of unknown word promotes it to active cache
            val promoteAct = repo.recordCommittedWord(
                surface = "Anthropic",
                source = SwipeVocabularyJudgement.SwipeLearnSource.TAP_SPACE_COMMITTED,
                isSensitiveOrPasswordField = false,
                isExplicitUserDictWord = false,
                isAlreadyInBase32kVocab = false
            )
            assertEquals(SwipeVocabularyJudgement.AdaptiveCacheAdmissionAction.DEFER_UNVERIFIED_SINGLE_TYPO_GUARD, promoteAct)
            val promoteAct2 = repo.recordCommittedWord(
                surface = "Anthropic",
                source = SwipeVocabularyJudgement.SwipeLearnSource.TAP_SPACE_COMMITTED,
                isSensitiveOrPasswordField = false,
                isExplicitUserDictWord = false,
                isAlreadyInBase32kVocab = false
            )
            assertEquals(SwipeVocabularyJudgement.AdaptiveCacheAdmissionAction.ADMIT_TO_ADAPTIVE_CACHE, promoteAct2)
            assertEquals(listOf("Anthropic"), repo.getActiveSurfaces())

            // 4. Candidate selection / voice / known extended word admits immediately on 1st shot and resolves canonical case
            repo.recordCommittedWord(
                surface = "tailscale",
                source = SwipeVocabularyJudgement.SwipeLearnSource.TAP_SPACE_COMMITTED,
                isSensitiveOrPasswordField = false,
                isExplicitUserDictWord = false,
                isAlreadyInBase32kVocab = false
            )
            assertTrue(repo.getActiveSurfaces().contains("Tailscale"))

            // 5. Explicit user dict & base 32k words never consume adaptive LRU slots
            val baseSkip = repo.recordCommittedWord(
                surface = "hello",
                source = SwipeVocabularyJudgement.SwipeLearnSource.TAP_CANDIDATE_SELECTED,
                isSensitiveOrPasswordField = false,
                isExplicitUserDictWord = false,
                isAlreadyInBase32kVocab = true
            )
            assertEquals(SwipeVocabularyJudgement.AdaptiveCacheAdmissionAction.SKIP_COVERED_BY_BASE_VOCAB, baseSkip)

            // 6. LRU capacity cap = 3 evicts oldest ("Anthropic") after adding Supabase and Konsist
            val mutated = repo.recordCommittedText(
                text = "Supabase and Konsist with MyCustomTerm https://skip.me",
                source = SwipeVocabularyJudgement.SwipeLearnSource.VOICE_ENGLISH_COMMITTED,
                isSensitiveOrPasswordField = false,
                explicitUserDictSwipeKeys = setOf("mycustomterm"),
                isInBase32kFn = { it == "and" || it == "with" }
            )
            assertTrue(mutated)
            assertEquals(3, repo.getActiveCount())
            assertEquals(listOf("Konsist", "Supabase", "Tailscale"), repo.getActiveSurfaces())

            // 7. Persistence round-trip & isolation from custom_dictionary.json
            val reloaded = SwipeAdaptiveCacheRepository(storageFile = cacheFile, maxEntries = 3)
            assertEquals(listOf("Konsist", "Supabase", "Tailscale"), reloaded.getActiveSurfaces())
            assertTrue(customDictFile.readText(Charsets.UTF_8).contains("MyCustomTerm"))

            // Clearing cache does not affect custom_dictionary.json
            reloaded.clear()
            assertEquals(0, reloaded.getActiveCount())
            assertTrue(customDictFile.readText(Charsets.UTF_8).contains("MyCustomTerm"))
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
