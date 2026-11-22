package com.example.myasr

import com.example.myasr.context.english.domain.BayesianCaseRanker
import com.example.myasr.context.english.domain.EnglishCapitalizationAnalyzer
import com.example.myasr.context.english.domain.EnglishCaseModel
import com.example.myasr.context.english.domain.InputFieldCapPolicy
import com.example.myasr.context.english.domain.SentenceBoundaryDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Quantitative evaluation & unit test suite for the algorithmic English Truecasing
 * & Capitalization pipeline (EnglishCaseModel + SentenceBoundaryDetector + BayesianCaseRanker).
 */
class EnglishTruecasingEvaluationTest {

    @Test
    fun `corpus-derived model loads cleanly with expected lexicon, vocab, char LM, and abbreviations`() {
        EnglishCaseModel.ensureLoaded()
        assertTrue("Expected 'i' in case lexicon", EnglishCaseModel.lookupLexicon("i") != null)
        assertTrue("Expected 'usa' in case lexicon", EnglishCaseModel.lookupLexicon("usa") != null)
        assertTrue("Expected 'tokyo' in case lexicon", EnglishCaseModel.lookupLexicon("tokyo") != null)
        assertTrue("Expected 'apple' in lower vocab", EnglishCaseModel.isKnownLowerWord("apple"))
        assertTrue("Expected 'mr' in abbrev EOS table", EnglishCaseModel.abbreviationEosProbability("mr") != null)
    }

    @Test
    fun `Punkt-style sentence boundary detector disambiguates abbreviations from true sentence ends`() {
        // True sentence starts
        assertTrue(SentenceBoundaryDetector.computeBosProbability("hello", "hello") >= 0.9f)
        assertTrue(SentenceBoundaryDetector.computeBosProbability("world", "Done. world") >= 0.9f)
        assertTrue(SentenceBoundaryDetector.computeBosProbability("how", "Great! how") >= 0.9f)
        assertTrue(SentenceBoundaryDetector.computeBosProbability("what", "Really? what") >= 0.9f)
        assertTrue(SentenceBoundaryDetector.computeBosProbability("first", "1. first") >= 0.85f)
        assertTrue(SentenceBoundaryDetector.computeBosProbability("yes", "He said. \"yes") >= 0.9f)

        // Non-sentence-starts: abbreviations and internal-period tokens
        assertTrue(SentenceBoundaryDetector.computeBosProbability("cats", "e.g. cats") < 0.2f)
        assertTrue(SentenceBoundaryDetector.computeBosProbability("dogs", "i.e. dogs") < 0.2f)
        assertTrue(SentenceBoundaryDetector.computeBosProbability("apples", "oranges vs. apples") < 0.2f)
        assertTrue(SentenceBoundaryDetector.computeBosProbability("and", "fruits, etc. and") < 0.2f)
        assertTrue(SentenceBoundaryDetector.computeBosProbability("rowling", "J. K. rowling") < 0.2f)
        assertTrue(SentenceBoundaryDetector.computeBosProbability("shows", "See Fig. 1. shows") < 0.25f)
        assertTrue(SentenceBoundaryDetector.computeBosProbability("smith", "Mr. smith") < 0.2f)
    }

    @Test
    fun `OOV character bigram LM detects unseen acronyms without false positives on common words`() {
        val oovAcronyms = listOf("fyi", "sdk", "vpn", "tpu", "usb", "hdmi", "nvme", "rsvp", "tldr", "nasa", "llm", "sql", "grpc", "rlhf", "vram")
        for (acro in oovAcronyms) {
            val prob = EnglishCaseModel.oovAcronymProbability(acro)
            assertTrue("Expected OOV acronym '$acro' to have P_OOV(UPPER) >= 0.50, got $prob", prob >= 0.50f)
        }

        val normalWords = listOf("apple", "cat", "dog", "time", "code", "hello", "world", "good", "book", "the", "and")
        for (word in normalWords) {
            val prob = EnglishCaseModel.oovAcronymProbability(word)
            assertTrue("Expected normal word '$word' NOT to trigger OOV acronym, got $prob", prob < 0.50f)
        }
    }

    @Test
    fun `ambiguous words preserve both capitalized and lowercase variants in candidate strip`() {
        // "us" mid-sentence: both "us" and "US" present
        val usCands = BayesianCaseRanker.generateCaseVariants(
            baseWord = "us",
            bosProb = 0.0f,
            policy = InputFieldCapPolicy.STANDARD,
            source = "raw",
            isRawWord = true
        ).map { it.surface }
        assertTrue("Expected 'us' in $usCands", "us" in usCands)
        assertTrue("Expected 'US' in $usCands", "US" in usCands)

        // "may" mid-sentence: both "may" and "May" present
        val mayMid = BayesianCaseRanker.generateCaseVariants(
            baseWord = "may",
            bosProb = 0.0f,
            policy = InputFieldCapPolicy.STANDARD,
            source = "raw",
            isRawWord = true
        ).map { it.surface }
        assertTrue("Expected 'may' in $mayMid", "may" in mayMid)
        assertTrue("Expected 'May' in $mayMid", "May" in mayMid)

        // "may" at sentence start: "May" is ranked #1
        val mayBos = BayesianCaseRanker.generateCaseVariants(
            baseWord = "may",
            bosProb = 1.0f,
            policy = InputFieldCapPolicy.STANDARD,
            source = "raw",
            isRawWord = true
        ).map { it.surface }
        assertEquals("May", mayBos.first())
        assertTrue("may" in mayBos)

        // "id" mid-sentence: "I'd", "ID", "id" all present
        val idCands = BayesianCaseRanker.generateCaseVariants(
            baseWord = "id",
            bosProb = 0.0f,
            policy = InputFieldCapPolicy.STANDARD,
            source = "raw",
            isRawWord = true
        ).map { it.surface }
        assertTrue("Expected \"I'd\" in $idCands", "I'd" in idCands)
        assertTrue("Expected \"ID\" in $idCands", "ID" in idCands)
        assertTrue("Expected \"id\" in $idCands", "id" in idCands)
    }

    @Test
    fun `explicit InputFieldCapPolicy modes (CAP_CHARACTERS, CAP_WORDS, BYPASS) behave deterministically`() {
        val capChars = BayesianCaseRanker.generateCaseVariants(
            baseWord = "abc",
            bosProb = 0.0f,
            policy = InputFieldCapPolicy.CAP_CHARACTERS,
            source = "raw",
            isRawWord = true
        ).map { it.surface }
        assertEquals("ABC", capChars.first())

        val capWords = BayesianCaseRanker.generateCaseVariants(
            baseWord = "smith",
            bosProb = 0.0f,
            policy = InputFieldCapPolicy.CAP_WORDS,
            source = "raw",
            isRawWord = true
        ).map { it.surface }
        assertEquals("Smith", capWords.first())

        val bypassed = EnglishCapitalizationAnalyzer.analyze(
            rawWord = "usa",
            textBeforeCursor = "usa",
            isShiftActive = false,
            policy = InputFieldCapPolicy.BYPASS
        )
        assertEquals(EnglishCapitalizationAnalyzer.CapitalizationType.NONE, bypassed.type)
        assertEquals(null, bypassed.primaryCandidate)
    }

    @Test
    fun `typo and swipe candidates are truecased independently of rawWord`() {
        // E.g. user typed "tkyo" -> typo engine suggests "tokyo" -> ranker produces "Tokyo"
        val typoTruecased = BayesianCaseRanker.generateCaseVariants(
            baseWord = "tokyo",
            bosProb = 0.0f,
            policy = InputFieldCapPolicy.STANDARD,
            source = "english_typo",
            obsPenalty = 0.15f
        ).map { it.surface }
        assertEquals("Tokyo", typoTruecased.first())

        // E.g. swipe beam returns "usa" mid-sentence -> ranker produces "USA"
        val swipeTruecased = BayesianCaseRanker.generateCaseVariants(
            baseWord = "usa",
            bosProb = 0.0f,
            policy = InputFieldCapPolicy.STANDARD,
            source = "futo_swipe",
            obsPenalty = 0.0f,
            isRawWord = true
        ).map { it.surface }
        assertEquals("USA", swipeTruecased.first())
    }

    @Test
    fun `quantitative 60-case truecasing benchmark achieves at least 90pct Top-1 and 98pct Top-3 under 1ms latency`() {
        data class EvalCase(val category: String, val textBeforeCursor: String, val rawWord: String, val expectedSurface: String)

        val cases = listOf(
            // Sentence Start (10)
            EvalCase("BOS", "hello", "hello", "Hello"),
            EvalCase("BOS", "Done. world", "world", "World"),
            EvalCase("BOS", "Wow! amazing", "amazing", "Amazing"),
            EvalCase("BOS", "Why? because", "because", "Because"),
            EvalCase("BOS", "line1\nsecond", "second", "Second"),
            EvalCase("BOS", "\"quote", "quote", "Quote"),
            EvalCase("BOS", "(parenthesis", "parenthesis", "Parenthesis"),
            EvalCase("BOS", "1. first", "first", "First"),
            EvalCase("BOS", "Ok. may", "may", "May"),
            EvalCase("BOS", "He left. she", "she", "She"),

            // Pronoun I & Contractions (8)
            EvalCase("PRONOUN", "so i", "i", "I"),
            EvalCase("PRONOUN", "i", "i", "I"),
            EvalCase("PRONOUN", "today im", "im", "I'm"),
            EvalCase("PRONOUN", "think ive", "ive", "I've"),
            EvalCase("PRONOUN", "yes id", "id", "I'd"),
            EvalCase("PRONOUN", "when ill", "ill", "I'll"),
            EvalCase("PRONOUN", "so i'm", "i'm", "I'm"),
            EvalCase("PRONOUN", "think i've", "i've", "I've"),

            // Corpus Acronyms (10)
            EvalCase("ACRO_CORPUS", "in the usa", "usa", "USA"),
            EvalCase("ACRO_CORPUS", "from the uk", "uk", "UK"),
            EvalCase("ACRO_CORPUS", "the un", "un", "UN"),
            EvalCase("ACRO_CORPUS", "call the fbi", "fbi", "FBI"),
            EvalCase("ACRO_CORPUS", "at ibm", "ibm", "IBM"),
            EvalCase("ACRO_CORPUS", "new api", "api", "API"),
            EvalCase("ACRO_CORPUS", "watch tv", "tv", "TV"),
            EvalCase("ACRO_CORPUS", "is ok", "ok", "OK"),
            EvalCase("ACRO_CORPUS", "that is ai", "ai", "AI"),
            EvalCase("ACRO_CORPUS", "check id", "id", "ID"),

            // OOV Acronyms via Character Bigram LM (10)
            EvalCase("ACRO_OOV", "fast cpu", "cpu", "CPU"),
            EvalCase("ACRO_OOV", "new gpu", "gpu", "GPU"),
            EvalCase("ACRO_OOV", "android sdk", "sdk", "SDK"),
            EvalCase("ACRO_OOV", "send pdf", "pdf", "PDF"),
            EvalCase("ACRO_OOV", "use vpn", "vpn", "VPN"),
            EvalCase("ACRO_OOV", "edit html", "html", "HTML"),
            EvalCase("ACRO_OOV", "via https", "https", "HTTPS"),
            EvalCase("ACRO_OOV", "parse xml", "xml", "XML"),
            EvalCase("ACRO_OOV", "run sql", "sql", "SQL"),
            EvalCase("ACRO_OOV", "at nasa", "nasa", "NASA"),

            // Proper Nouns (8)
            EvalCase("PROPER", "fly to tokyo", "tokyo", "Tokyo"),
            EvalCase("PROPER", "see you monday", "monday", "Monday"),
            EvalCase("PROPER", "in september", "september", "September"),
            EvalCase("PROPER", "speak english", "english", "English"),
            EvalCase("PROPER", "visit london", "london", "London"),
            EvalCase("PROPER", "in california", "california", "California"),
            EvalCase("PROPER", "in march", "march", "March"),
            EvalCase("PROPER", "use itunes", "itunes", "iTunes"),

            // Mid-Sentence & Non-BOS Abbreviations (14 — must stay lowercase!)
            EvalCase("LOWER_SAFE", "the cat", "cat", "cat"),
            EvalCase("LOWER_SAFE", "good morning", "morning", "morning"),
            EvalCase("LOWER_SAFE", "write code", "code", "code"),
            EvalCase("LOWER_SAFE", "eat an apple", "apple", "apple"),
            EvalCase("LOWER_SAFE", "give to us", "us", "us"),
            EvalCase("LOWER_SAFE", "you may", "may", "may"),
            EvalCase("LOWER_SAFE", "e.g. cats", "cats", "cats"),
            EvalCase("LOWER_SAFE", "i.e. dogs", "dogs", "dogs"),
            EvalCase("LOWER_SAFE", "apples vs. oranges", "oranges", "oranges"),
            EvalCase("LOWER_SAFE", "etc. and", "and", "and"),
            EvalCase("LOWER_SAFE", "J. K. rowling", "rowling", "rowling"),
            EvalCase("LOWER_SAFE", "user@example", "example", "example"),
            EvalCase("LOWER_SAFE", "path/to/file", "file", "file"),
            EvalCase("LOWER_SAFE", "my_var", "var", "var")
        )

        // Warm-up loader before timing
        EnglishCaseModel.ensureLoaded()

        var top1Hits = 0
        var top3Hits = 0
        val startNs = System.nanoTime()

        for (c in cases) {
            val policy = EnglishCapitalizationAnalyzer.resolveFieldPolicy(null, c.textBeforeCursor)
            val bosProb = SentenceBoundaryDetector.computeBosProbability(c.rawWord, c.textBeforeCursor, policy)
            val ranked = BayesianCaseRanker.generateCaseVariants(
                baseWord = c.rawWord,
                bosProb = bosProb,
                policy = policy,
                source = "raw",
                isRawWord = true
            ).map { it.surface }

            if (ranked.firstOrNull() == c.expectedSurface) {
                top1Hits++
            } else {
                println("MISS [${c.category}] ctx='${c.textBeforeCursor}' raw='${c.rawWord}' expected='${c.expectedSurface}' got=$ranked")
            }
            if (ranked.take(3).contains(c.expectedSurface)) {
                top3Hits++
            }
        }

        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0
        val avgMs = elapsedMs / cases.size
        val top1Acc = top1Hits.toDouble() / cases.size
        val top3Recall = top3Hits.toDouble() / cases.size

        println(
            "EnglishTruecasingEvaluationTest: ${cases.size} cases | " +
                "Top-1=${top1Hits}/${cases.size} (${String.format("%.1f", top1Acc * 100)}%) | " +
                "Top-3=${top3Hits}/${cases.size} (${String.format("%.1f", top3Recall * 100)}%) | " +
                "AvgLatency=${String.format("%.3f", avgMs)}ms"
        )

        assertTrue("Expected Top-1 accuracy >= 95%, got ${top1Acc * 100}%", top1Acc >= 0.95)
        assertTrue("Expected Top-3 recall >= 98%, got ${top3Recall * 100}%", top3Recall >= 0.98)
        assertTrue("Expected avg latency < 1.0ms, got ${avgMs}ms", avgMs < 1.0)
    }

    @Test
    fun `prefix completion and typo lexicon suggests Tailscale for tailsc and modern tech terms at Rank 1`() {
        fun rankEnglish(rawWord: String, isShiftActive: Boolean = false, bosProb: Float = 0.0f): List<String> {
            val suggestions = EnglishCaseModel.suggestPrefixAndTypo(rawWord, maxCandidates = 6)
            return BayesianCaseRanker.buildEnglishCandidates(
                rawWord = rawWord,
                bosProb = bosProb,
                fieldPolicy = InputFieldCapPolicy.STANDARD,
                isShiftActive = isShiftActive,
                userCandidates = emptyList(),
                lexiconSuggestions = suggestions
            ).map { it.surface }
        }

        // CUJ-1: Modern proper nouns & mixed-case tech terms
        val prefixCases = listOf(
            "tailsc" to "Tailscale",
            "kubern" to "Kubernetes",
            "postgr" to "PostgreSQL",
            "typesc" to "TypeScript",
            "wiregu" to "WireGuard",
            "cloudfl" to "Cloudflare",
            "javascr" to "JavaScript",
            "pytor" to "PyTorch",
            "tensorf" to "TensorFlow",
            "fastap" to "FastAPI",
            "githu" to "GitHub",
            "gitla" to "GitLab",
            "terraf" to "Terraform",
            "promet" to "Prometheus",
            "grafan" to "Grafana",
            "webass" to "WebAssembly"
        )
        for ((typed, expected) in prefixCases) {
            val ranked = rankEnglish(typed)
            assertEquals("Expected '$expected' at Rank 1 for '$typed', got $ranked", expected, ranked.firstOrNull())
            assertTrue("Expected raw escape hatch '$typed' in $ranked", typed in ranked)
        }

        // CUJ-2: Progressive typing from known short word ("tail" -> "tails" -> "tailsc")
        val tailRanked = rankEnglish("tail")
        assertEquals("Known word 'tail' must remain Rank 1, got $tailRanked", "tail", tailRanked.firstOrNull())

        val tailsRanked = rankEnglish("tails")
        assertEquals("Known word 'tails' must remain Rank 1, got $tailsRanked", "tails", tailsRanked.firstOrNull())
        assertTrue("Expected 'Tailscale' in Top-3 for 'tails', got $tailsRanked", "Tailscale" in tailsRanked.take(3))

        val tailscRanked = rankEnglish("tailsc")
        assertEquals("Tailscale", tailscRanked.firstOrNull())
        assertTrue("Expected 'Cloudflare' in Top-2 for 'cloudf'", "Cloudflare" in rankEnglish("cloudf").take(2))

        // CUJ-3: Explicit Shift / TitleCase / ALL-CAPS prefix completion
        assertEquals("Tailscale", rankEnglish("Tailsc").firstOrNull())
        assertEquals("Example", rankEnglish("Examp").firstOrNull())
        assertEquals("TAILSCALE", rankEnglish("TAILSC", isShiftActive = true).firstOrNull())

        // CUJ-4: Spatial typo & fuzzy prefix recovery
        assertEquals("the", rankEnglish("teh").firstOrNull())
        assertTrue("Expected 'Tailscale' in Top-3 for fuzzy typo 'tailscle'", "Tailscale" in rankEnglish("tailscle").take(3))
        assertTrue("Expected 'Kubernetes' in Top-3 for fuzzy typo 'kuberntes'", "Kubernetes" in rankEnglish("kuberntes").take(3))

        // Latency guard (< 0.5ms average for prefixes, < 0.8ms average including fuzzy typos)
        // Warm up JVM C1/C2 JIT paths before measuring steady-state latency
        repeat(10) {
            for ((typed, _) in prefixCases) {
                rankEnglish(typed)
            }
        }
        val typoQueries = listOf("teh", "tailscle", "kuberntes", "helo", "alredy")
        repeat(10) {
            for (q in typoQueries) {
                rankEnglish(q)
            }
        }

        val timedRounds = 5
        val startNs = System.nanoTime()
        repeat(timedRounds) {
            for ((typed, _) in prefixCases) {
                rankEnglish(typed)
            }
        }
        val avgMs = (System.nanoTime() - startNs) / 1_000_000.0 / (prefixCases.size * timedRounds)
        assertTrue("Expected avg prefix lookup latency < 0.5ms, got ${avgMs}ms", avgMs < 0.5)

        val typoStartNs = System.nanoTime()
        repeat(timedRounds) {
            for (q in typoQueries) {
                rankEnglish(q)
            }
        }
        val avgTypoMs = (System.nanoTime() - typoStartNs) / 1_000_000.0 / (typoQueries.size * timedRounds)
        assertTrue("Expected avg typo bucket lookup latency < 0.8ms, got ${avgTypoMs}ms", avgTypoMs < 0.8)
    }

    @Test
    fun `sentence-start pronoun i and apostrophe contractions like lets rank I and Let's at top`() {
        // 1. Sentence-start 'i' (including after Japanese punctuation '。')
        for (ctx in listOf("i", "Hello. i", "\ni", "こんにちは。i")) {
            val res = EnglishCapitalizationAnalyzer.analyze("i", ctx, isShiftActive = false)
            assertEquals("Expected 'I' as primaryCandidate for ctx='$ctx'", "I", res.primaryCandidate)
        }

        // 2. Sentence-start 'lets' -> "Let's" Rank 1, "Lets" and "lets" also present
        val letsBos = BayesianCaseRanker.generateCaseVariants(
            baseWord = "lets",
            bosProb = SentenceBoundaryDetector.computeBosProbability("lets", "lets"),
            policy = InputFieldCapPolicy.STANDARD,
            source = "raw",
            isRawWord = true
        ).map { it.surface }
        assertEquals("Expected \"Let's\" at Rank 1 for sentence-start 'lets', got $letsBos", "Let's", letsBos.first())
        assertTrue("Expected \"Lets\" in $letsBos", "Lets" in letsBos)
        assertTrue("Expected \"lets\" in $letsBos", "lets" in letsBos)

        // 3. Mid-sentence contractions: "lets" -> "let's", "dont" -> "don't", "cant" -> "can't"
        val letsMid = BayesianCaseRanker.generateCaseVariants(
            baseWord = "lets",
            bosProb = SentenceBoundaryDetector.computeBosProbability("lets", "he lets"),
            policy = InputFieldCapPolicy.STANDARD,
            source = "raw",
            isRawWord = true
        ).map { it.surface }
        assertEquals("let's", letsMid.first())
        assertTrue("lets" in letsMid)

        val dontBos = BayesianCaseRanker.generateCaseVariants(
            baseWord = "dont",
            bosProb = 1.0f,
            policy = InputFieldCapPolicy.STANDARD,
            source = "raw",
            isRawWord = true
        ).map { it.surface }
        assertEquals("Don't", dontBos.first())

        val cantBos = BayesianCaseRanker.generateCaseVariants(
            baseWord = "cant",
            bosProb = 1.0f,
            policy = InputFieldCapPolicy.STANDARD,
            source = "raw",
            isRawWord = true
        ).map { it.surface }
        assertEquals("Can't", cantBos.first())
    }
}
