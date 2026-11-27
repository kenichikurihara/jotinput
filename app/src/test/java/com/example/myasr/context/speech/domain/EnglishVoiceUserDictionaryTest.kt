package com.example.myasr.context.speech.domain

import com.example.myasr.context.english.domain.EnglishCaseModel
import com.example.myasr.context.reranking.infrastructure.LlmReranker
import com.example.myasr.context.vocabulary.domain.DictionaryEntry
import com.example.myasr.context.vocabulary.domain.Priority
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EnglishVoiceUserDictionaryTest {

    private fun makeEntry(
        word: String,
        reading: String = word.lowercase(),
        priority: Priority = Priority.NORMAL,
        isEnabled: Boolean = true
    ) = DictionaryEntry(
        word = word,
        reading = reading,
        priority = priority,
        isEnabled = isEnabled
    )

    private fun resolveRepoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "eval/english_voice/user_dict_dataset.json").isFile) {
                return dir
            }
            dir = dir.parentFile
        }
        throw IllegalStateException("Cannot locate repo root from ${System.getProperty("user.dir")}")
    }

    @Test
    fun `DMN EnglishVoiceUserDictResolutionPolicy covers all rules and traces cleanly`() {
        fun input(
            isEnabledEntry: Boolean = true,
            matchKind: EnglishUserDictMatchKind = EnglishUserDictMatchKind.EXACT_NORMALIZED,
            isAlreadyExactSurface: Boolean = false,
            isShortTarget: Boolean = false,
            isCommonWordCollision: Boolean = false,
            isCaseOnlyCommonWord: Boolean = false,
            isRevertedPair: Boolean = false,
            inPrecedingContext: Boolean = false,
            slmAvailable: Boolean = false,
            slmCoherencePassed: Boolean = false,
            isHighPriority: Boolean = false
        ) = EnglishUserDictDecisionModel.EnglishVoiceUserDictResolutionInput(
            isEnabledEntry = isEnabledEntry,
            matchKind = matchKind,
            isAlreadyExactSurface = isAlreadyExactSurface,
            isShortTarget = isShortTarget,
            isCommonWordCollision = isCommonWordCollision,
            isCaseOnlyCommonWord = isCaseOnlyCommonWord,
            isRevertedPair = isRevertedPair,
            inPrecedingContext = inPrecedingContext,
            slmAvailable = slmAvailable,
            slmCoherencePassed = slmCoherencePassed,
            isHighPriority = isHighPriority
        )

        val disabled = EnglishUserDictDecisionModel.evaluateResolutionPolicy(input(isEnabledEntry = false))
        assertEquals("EN_UD_DISABLED_OR_EXACT", disabled.selectedRuleId)
        assertEquals(EnglishUserDictAction.NO_OP, disabled.result)

        val exactAlready = EnglishUserDictDecisionModel.evaluateResolutionPolicy(input(isAlreadyExactSurface = true))
        assertEquals("EN_UD_DISABLED_OR_EXACT", exactAlready.selectedRuleId)
        assertEquals(EnglishUserDictAction.NO_OP, exactAlready.result)

        val noMatch = EnglishUserDictDecisionModel.evaluateResolutionPolicy(input(matchKind = EnglishUserDictMatchKind.NONE))
        assertEquals("EN_UD_NO_MATCH", noMatch.selectedRuleId)
        assertEquals(EnglishUserDictAction.NO_OP, noMatch.result)

        val shortNonExact = EnglishUserDictDecisionModel.evaluateResolutionPolicy(
            input(matchKind = EnglishUserDictMatchKind.PHONETIC_EXACT, isShortTarget = true)
        )
        assertEquals("EN_UD_SHORT_NON_EXACT", shortNonExact.selectedRuleId)
        assertEquals(EnglishUserDictAction.KEEP_ORIGINAL, shortNonExact.result)

        val revertedNonExact = EnglishUserDictDecisionModel.evaluateResolutionPolicy(
            input(matchKind = EnglishUserDictMatchKind.PHONETIC_NEAR_MISS, isRevertedPair = true)
        )
        assertEquals("EN_UD_REVERTED_NON_EXACT", revertedNonExact.selectedRuleId)
        assertEquals(EnglishUserDictAction.SUGGEST_CHIP, revertedNonExact.result)

        val ctxConfirmed = EnglishUserDictDecisionModel.evaluateResolutionPolicy(
            input(
                matchKind = EnglishUserDictMatchKind.PHONETIC_NEAR_MISS,
                isCommonWordCollision = true,
                inPrecedingContext = true
            )
        )
        assertEquals("EN_UD_CONTEXT_CONFIRMED", ctxConfirmed.selectedRuleId)
        assertEquals(EnglishUserDictAction.AUTO_REPLACE, ctxConfirmed.result)

        val exactNonCollision = EnglishUserDictDecisionModel.evaluateResolutionPolicy(
            input(matchKind = EnglishUserDictMatchKind.EXACT_NORMALIZED, isCommonWordCollision = false)
        )
        assertEquals("EN_UD_EXACT_NON_COLLISION", exactNonCollision.selectedRuleId)
        assertEquals(EnglishUserDictAction.AUTO_REPLACE, exactNonCollision.result)

        val nonCommonPhonetic = EnglishUserDictDecisionModel.evaluateResolutionPolicy(
            input(matchKind = EnglishUserDictMatchKind.PHONETIC_NEAR_MISS, isCommonWordCollision = false)
        )
        assertEquals("EN_UD_NON_COMMON_PHONETIC", nonCommonPhonetic.selectedRuleId)
        assertEquals(EnglishUserDictAction.AUTO_REPLACE, nonCommonPhonetic.result)

        val slmPassed = EnglishUserDictDecisionModel.evaluateResolutionPolicy(
            input(
                matchKind = EnglishUserDictMatchKind.PHONETIC_NEAR_MISS,
                isCommonWordCollision = true,
                slmAvailable = true,
                slmCoherencePassed = true
            )
        )
        assertEquals("EN_UD_COMMON_SLM_PASSED", slmPassed.selectedRuleId)
        assertEquals(EnglishUserDictAction.AUTO_REPLACE, slmPassed.result)

        val caseOnlyKeep = EnglishUserDictDecisionModel.evaluateResolutionPolicy(
            input(
                matchKind = EnglishUserDictMatchKind.EXACT_NORMALIZED,
                isCommonWordCollision = true,
                isCaseOnlyCommonWord = true
            )
        )
        assertEquals("EN_UD_CASE_ONLY_COMMON_KEEP", caseOnlyKeep.selectedRuleId)
        assertEquals(EnglishUserDictAction.KEEP_ORIGINAL, caseOnlyKeep.result)

        val commonFallbackChip = EnglishUserDictDecisionModel.evaluateResolutionPolicy(
            input(
                matchKind = EnglishUserDictMatchKind.PHONETIC_NEAR_MISS,
                isCommonWordCollision = true,
                slmAvailable = true,
                slmCoherencePassed = false
            )
        )
        assertEquals("EN_UD_COMMON_FALLBACK_CHIP", commonFallbackChip.selectedRuleId)
        assertEquals(EnglishUserDictAction.SUGGEST_CHIP, commonFallbackChip.result)
    }

    @Test
    fun `resolves mixed case, bilingual Kana readings, spoken symbols, acronyms, cardinals, and possessives`() {
        val entries = listOf(
            makeEntry("JotInput", reading = "じょっといんぷっと", priority = Priority.HIGH),
            makeEntry("Nakamura", reading = "なかむら", priority = Priority.HIGH),
            makeEntry("CI/CD", reading = "しーあいしーでぃー", priority = Priority.HIGH),
            makeEntry("ReazonSpeech", priority = Priority.HIGH),
            makeEntry("PyTorch", priority = Priority.HIGH),
            makeEntry("GraphQL"),
            makeEntry("gRPC"),
            makeEntry("Tailscale", priority = Priority.HIGH),
            makeEntry("FastConformer"),
            makeEntry("Zipformer"),
            makeEntry("Datadog"),
            makeEntry("LangChain"),
            makeEntry("SymSpell"),
            makeEntry("Supabase"),
            makeEntry("KanaKanji"),
            makeEntry("SudachiDict"),
            makeEntry("vLLM"),
            makeEntry("NixOS"),
            makeEntry("Antigravity"),
            makeEntry("Qwen", priority = Priority.HIGH),
            makeEntry("llama.cpp", priority = Priority.HIGH),
            makeEntry("Node.js"),
            makeEntry("C++"),
            makeEntry("SLM", priority = Priority.HIGH),
            makeEntry("VAD"),
            makeEntry("IME"),
            makeEntry("UI"),
            makeEntry("kubectl", priority = Priority.HIGH),
            makeEntry("Google DeepMind", priority = Priority.HIGH),
            makeEntry("Pixel 10 Pro", priority = Priority.HIGH),
            makeEntry("DisabledTerm", isEnabled = false)
        )

        val cases = listOf(
            "I use Jot input on my Android phone." to "I use JotInput on my Android phone.",
            "Jot inputs voice recognition runs locally." to "JotInputs voice recognition runs locally.",
            "Please send the report to Nakamora before noon." to "Please send the report to Nakamura before noon.",
            "Nacamura's pull request fixed it." to "Nakamura's pull request fixed it.",
            "Check the CI slash CD logs." to "Check the CI/CD logs.",
            "Reaisan speech transcribes audio." to "ReazonSpeech transcribes audio.",
            "We trained with pytorch." to "We trained with PyTorch.",
            "PyTorch's eager mode rocks." to "PyTorch's eager mode rocks.",
            "Expose a unified graphQL schema." to "Expose a unified GraphQL schema.",
            "Communicate over GRPC streams." to "Communicate over gRPC streams.",
            "Connected via tailscale." to "Connected via Tailscale.",
            "The fast conformer encoder runs fast." to "The FastConformer encoder runs fast.",
            "The zip forer transducer works." to "The Zipformer transducer works.",
            "Dataog alerts spiked." to "Datadog alerts spiked.",
            "Built an agent with Lang Chain." to "Built an agent with LangChain.",
            "Relies on Sim spell." to "Relies on SymSpell.",
            "Hosted on Superabase." to "Hosted on Supabase.",
            "The Konickoni lattice search runs fast." to "The KanaKanji lattice search runs fast.",
            "Loaded Sadaci Dict lexicon." to "Loaded SudachiDict lexicon.",
            "Benchmarked VLLM on Linux." to "Benchmarked vLLM on Linux.",
            "Our build runs NXOS." to "Our build runs NixOS.",
            "Run inside Antiravity." to "Run inside Antigravity.",
            "The reranker uses Quenn for scoring." to "The reranker uses Qwen for scoring.",
            "Inference runs via lamma. cPp." to "Inference runs via llama.cpp.",
            "Requires node.js twenty." to "Requires Node.js twenty.",
            "Written in C plus plus." to "Written in C++.",
            "Our S L M reranker runs offline." to "Our SLM reranker runs offline.",
            "Researchers at Google Deepmind published it." to "Researchers at Google DeepMind published it.",
            "Verified on Pixel ten Pro." to "Verified on Pixel 10 Pro.",
            "Run Kuber CTL apply now." to "Run kubectl apply now.",
            "The disabled term was ignored." to "The disabled term was ignored."
        )

        for ((raw, expected) in cases) {
            val res = EnglishVoicePostProcessor.formatWithCorrections(
                rawText = raw,
                contextBefore = "",
                userDictionaryEntries = entries,
                wordLogFreqProvider = EnglishCaseModel::getWordLogFreq
            )
            assertEquals("Mismatch for raw='$raw'", expected, res.formattedText)
        }
    }

    @Test
    fun `protects common English words and adversarial lookalikes from false positives`() {
        val entries = listOf(
            makeEntry("Konsist"),
            makeEntry("Mozc", priority = Priority.HIGH),
            makeEntry("Node.js"),
            makeEntry("NixOS"),
            makeEntry("Tailscale", priority = Priority.HIGH),
            makeEntry("PyTorch", priority = Priority.HIGH),
            makeEntry("LangChain"),
            makeEntry("VAD"),
            makeEntry("IME"),
            makeEntry("Go"),
            makeEntry("OAuth"),
            makeEntry("JotInput", reading = "じょっといんぷっと", priority = Priority.HIGH),
            makeEntry("Supabase"),
            makeEntry("Qwen", priority = Priority.HIGH)
        )

        // Adversarial sentences without SLM (common collisions must NOT auto-replace)
        val negatives = listOf(
            "The tests consist of three parts.",
            "We visited the Blue Mosque in Istanbul.",
            "A graceful fallback when models are missing.",
            "Update the release notes with bug fixes.",
            "Migrating to local offline processing.",
            "He ate apple pie by the torch.",
            "This caused a long chain of events.",
            "That was a bad idea.",
            "I am happy to help.",
            "Let's go home after we finish.",
            "He took an oath to tell the truth.",
            "Meet me at the train station.",
            "Please jot down your input before the meeting.",
            "The super base coat dried very quickly.",
            "My friend Quinn lives in Seattle."
        )

        for (sentence in negatives) {
            val res = EnglishVoicePostProcessor.formatWithCorrections(
                rawText = sentence,
                contextBefore = "",
                userDictionaryEntries = entries,
                wordLogFreqProvider = EnglishCaseModel::getWordLogFreq
            )
            assertEquals("Adversarial sentence was mutated without SLM: '$sentence'", sentence, res.formattedText)
        }
    }

    @Test
    fun `SLM gate, context confirmation, revert suppression, protected ranges, and idempotency work end-to-end`() {
        val entries = listOf(
            makeEntry("Mozc", priority = Priority.HIGH),
            makeEntry("JotInput", reading = "じょっといんぷっと", priority = Priority.HIGH),
            makeEntry("Nakamura", reading = "なかむら", priority = Priority.HIGH)
        )

        // 1. Common-word collision with SLM rejecting -> stays original and emits 1-tap chip
        val slmRejectRes = EnglishVoicePostProcessor.formatWithCorrections(
            rawText = "We visited the Blue Mosque in Istanbul.",
            contextBefore = "",
            userDictionaryEntries = entries,
            wordLogFreqProvider = EnglishCaseModel::getWordLogFreq,
            slmCoherenceDeltaProvider = { _, _ -> 28.0f }
        )
        assertEquals("We visited the Blue Mosque in Istanbul.", slmRejectRes.formattedText)
        assertTrue(slmRejectRes.appliedCorrections.isEmpty())
        assertEquals(1, slmRejectRes.suggestedChips.size)
        assertEquals("Mozc", slmRejectRes.suggestedChips.first().correctedSurface)

        // 2. Common-word collision with SLM accepting -> auto-replaces cleanly
        val slmAcceptRes = EnglishVoicePostProcessor.formatWithCorrections(
            rawText = "We updated the Mosque dictionary.",
            contextBefore = "Working on Japanese IME conversion.",
            userDictionaryEntries = entries,
            wordLogFreqProvider = EnglishCaseModel::getWordLogFreq,
            slmCoherenceDeltaProvider = { _, _ -> -2.9f }
        )
        assertEquals(" We updated the Mozc dictionary.", slmAcceptRes.formattedText)
        assertEquals(1, slmAcceptRes.appliedCorrections.size)

        // 3. Preceding context confirmation auto-replaces even without SLM
        val ctxRes = EnglishVoicePostProcessor.formatWithCorrections(
            rawText = "We updated the Mosque dictionary.",
            contextBefore = "Mozc conversion engine: ",
            userDictionaryEntries = entries,
            wordLogFreqProvider = EnglishCaseModel::getWordLogFreq
        )
        assertEquals("We updated the Mozc dictionary.", ctxRes.formattedText)

        // 4. Reverted non-exact pair downgrades to 1-tap chip even on OOV phonetic match
        val revertedRes = EnglishVoicePostProcessor.formatWithCorrections(
            rawText = "Send it to Nakamora.",
            contextBefore = "",
            userDictionaryEntries = entries,
            wordLogFreqProvider = EnglishCaseModel::getWordLogFreq,
            suppressedPairs = setOf("nakamora" to "Nakamura")
        )
        assertEquals("Send it to Nakamora.", revertedRes.formattedText)
        assertTrue(revertedRes.appliedCorrections.isEmpty())
        assertEquals(1, revertedRes.suggestedChips.size)
        assertEquals("Nakamura", revertedRes.suggestedChips.first().correctedSurface)

        // 5. Idempotency across repeated streaming formatting passes
        val firstPass = EnglishVoicePostProcessor.formatWithCorrections(
            rawText = "I use Jot input on my phone.",
            contextBefore = "Hello.",
            userDictionaryEntries = entries,
            wordLogFreqProvider = EnglishCaseModel::getWordLogFreq
        )
        assertEquals(" I use JotInput on my phone.", firstPass.formattedText)
        val secondPass = EnglishVoicePostProcessor.formatWithCorrections(
            rawText = firstPass.formattedText.trim(),
            contextBefore = "Hello.",
            userDictionaryEntries = entries,
            wordLogFreqProvider = EnglishCaseModel::getWordLogFreq
        )
        assertEquals(firstPass.formattedText, secondPass.formattedText)
        assertTrue(secondPass.appliedCorrections.isEmpty())
    }

    @Test
    fun `LlmReranker extractDivergingPairWindow isolates diverging span and caps trailing words`() {
        val (ctx1, o1, c1) = LlmReranker.extractDivergingPairWindow(
            contextPrefix = "Verify the DDD bounded contexts. ",
            origText = "Run consists architecture tests on every build in CI",
            corrText = "Run Konsist architecture tests on every build in CI",
            maxTrailingWords = 4
        )
        assertTrue(ctx1.endsWith("Run"))
        assertEquals(" consists architecture tests on every", o1)
        assertEquals(" Konsist architecture tests on every", c1)

        // Mid-word common prefix ("Q" shared between "Quinn" and "Qwen") must snap back to space boundary
        val (ctx2, o2, c2) = LlmReranker.extractDivergingPairWindow(
            contextPrefix = "",
            origText = "My friend Quinn lives in Seattle.",
            corrText = "My friend Qwen lives in Seattle."
        )
        assertEquals("My friend", ctx2)
        assertEquals(" Quinn lives in Seattle.", o2)
        assertEquals(" Qwen lives in Seattle.", c2)

        // Japanese prefix without spaces stays cleanly anchored at diverging kanji
        val (ctx3, o3, c3) = LlmReranker.extractDivergingPairWindow(
            contextPrefix = "",
            origText = "デザインドキュメントを描いて",
            corrText = "デザインドキュメントを書いて"
        )
        assertEquals("デザインドキュメントを", ctx3)
        assertEquals("描いて", o3)
        assertEquals("書いて", c3)
    }

    @Test
    fun `Kotlin resolver matches real Parakeet evaluation TWRR and zero Adversarial FPR`() {
        val root = resolveRepoRoot()
        val datasetJson = JSONObject(File(root, "eval/english_voice/user_dict_dataset.json").readText())
        val evalJson = JSONObject(File(root, "eval/english_voice/user_dict_eval_results.json").readText())

        val dictArr = datasetJson.getJSONArray("dictionary_entries")
        val entries = (0 until dictArr.length()).map { idx ->
            val obj = dictArr.getJSONObject(idx)
            DictionaryEntry(
                id = obj.getString("id"),
                word = obj.getString("word"),
                reading = obj.getString("reading"),
                priority = Priority.fromString(obj.getString("priority")),
                isEnabled = obj.getBoolean("is_enabled")
            )
        }

        val slmCacheJson = evalJson.getJSONObject("slm_delta_cache")
        val slmCache = HashMap<String, Float>()
        val keys = slmCacheJson.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            slmCache[k] = slmCacheJson.getDouble(k).toFloat()
        }

        val posArr = evalJson.getJSONArray("pos_slm_on")
        var posHitSlmOff = 0
        var posHitSlmOn = 0
        for (i in 0 until posArr.length()) {
            val item = posArr.getJSONObject(i)
            val raw = item.getString("raw")
            val ctx = item.optString("context_before", "")
            val target = item.getString("target")

            val resOff = EnglishVoiceUserDictionaryResolver.apply(
                text = raw,
                contextBefore = ctx,
                entries = entries,
                wordLogFreqProvider = EnglishCaseModel::getWordLogFreq
            )
            if (resOff.formattedText.contains(target)) {
                posHitSlmOff++
            }

            val resOn = EnglishVoiceUserDictionaryResolver.apply(
                text = raw,
                contextBefore = ctx,
                entries = entries,
                wordLogFreqProvider = EnglishCaseModel::getWordLogFreq,
                slmCoherenceDeltaProvider = { orig, corr ->
                    val (wCtx, wOrig, wCorr) = LlmReranker.extractDivergingPairWindow(ctx, orig, corr)
                    val cacheCtx = if (wCtx == "<s>") "" else wCtx
                    slmCache["$cacheCtx|||$wOrig|||$wCorr"]
                }
            )
            if (resOn.formattedText.contains(target)) {
                posHitSlmOn++
            }
        }

        val negArr = evalJson.getJSONArray("neg_slm_on_all")
        var negFpSlmOff = 0
        var negFpSlmOn = 0
        for (i in 0 until negArr.length()) {
            val item = negArr.getJSONObject(i)
            val raw = item.getString("raw")

            val resOff = EnglishVoiceUserDictionaryResolver.apply(
                text = raw,
                contextBefore = "",
                entries = entries,
                wordLogFreqProvider = EnglishCaseModel::getWordLogFreq
            )
            if (resOff.formattedText != raw) {
                negFpSlmOff++
            }

            val resOn = EnglishVoiceUserDictionaryResolver.apply(
                text = raw,
                contextBefore = "",
                entries = entries,
                wordLogFreqProvider = EnglishCaseModel::getWordLogFreq,
                slmCoherenceDeltaProvider = { orig, corr ->
                    val (wCtx, wOrig, wCorr) = LlmReranker.extractDivergingPairWindow("", orig, corr)
                    val cacheCtx = if (wCtx == "<s>") "" else wCtx
                    slmCache["$cacheCtx|||$wOrig|||$wCorr"]
                }
            )
            if (resOn.formattedText != raw) {
                negFpSlmOn++
            }
        }

        val totalPos = posArr.length()
        val twrrOff = posHitSlmOff.toFloat() / totalPos.toFloat()
        val twrrOn = posHitSlmOn.toFloat() / totalPos.toFloat()

        assertTrue("Expected Kotlin SLM-OFF TWRR >= 0.85, got $twrrOff ($posHitSlmOff/$totalPos)", twrrOff >= 0.85f)
        assertTrue("Expected Kotlin SLM-ON TWRR >= 0.90, got $twrrOn ($posHitSlmOn/$totalPos)", twrrOn >= 0.90f)
        assertEquals("Expected 0 adversarial false positives in SLM-OFF", 0, negFpSlmOff)
        assertEquals("Expected 0 adversarial false positives in SLM-ON", 0, negFpSlmOn)
        assertFalse(EnglishVoiceUserDictionaryResolver.hasCjkOrKana("JotInput"))
    }
}
