package com.example.myasr

import com.example.myasr.context.speech.domain.JapanesePunctuationEngine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit & simulation tests for confidence-based streaming prefix freeze (Morpheme-Aware Hybrid LCP).
 */
class StreamingPrefixFreezeEvaluationTest {

    @Test
    fun testCalculateLongestCommonPrefix() {
        val history1 = listOf(
            "今日は天気が良いです",
            "今日は天気が良いですから",
            "今日は天気が良いですし"
        )
        val lcp1 = JapanesePunctuationEngine.calculateLongestCommonPrefix(history1)
        assertEquals("今日は天気が良いです", lcp1)

        val history2 = listOf(
            "東京",
            "京都",
            "大阪"
        )
        val lcp2 = JapanesePunctuationEngine.calculateLongestCommonPrefix(history2)
        assertEquals("", lcp2)

        val history3 = listOf("単一テキスト")
        val lcp3 = JapanesePunctuationEngine.calculateLongestCommonPrefix(history3)
        assertEquals("単一テキスト", lcp3)

        assertEquals("", JapanesePunctuationEngine.calculateLongestCommonPrefix(emptyList()))
    }

    @Test
    fun testFindSafeFreezeBoundaryPunctuation() {
        // Punctuation (Level 3) boundary
        val text = "昨日は天気が良かったので、家族と一緒に公園に行きました。"
        // Candidate up to "家族と" (length 17)
        val boundary = JapanesePunctuationEngine.findSafeFreezeBoundary(text, candidateLength = 17)
        // Must snap to the comma after "良かったので、" (length 13)
        assertEquals(13, boundary)
        assertEquals("昨日は天気が良かったので、", text.substring(0, boundary))
    }

    @Test
    fun testFindSafeFreezeBoundaryParticle() {
        // Particle (Level 2) boundary without punctuation
        val text = "昨日は天気が良かったので家族と一緒に公園に行きました"
        // Candidate length 15: "昨日は天気が良かったので家族"
        val boundary = JapanesePunctuationEngine.findSafeFreezeBoundary(text, candidateLength = 15)
        // Should snap to "ので" ending (length 12: "昨日は天気が良かったので")
        assertEquals(12, boundary)
        assertEquals("昨日は天気が良かったので", text.substring(0, boundary))
    }

    @Test
    fun testFindSafeFreezeBoundaryScriptTransition() {
        // Script transition (Level 1): Hiragana -> Kanji
        val text = "昨日は天気がよい"
        // Candidate length 5: "昨日は天" (middle of Kanji "天気")
        // Must NOT split Kanji! It must snap back to Hiragana "は" (length 3: "昨日は")
        val boundary = JapanesePunctuationEngine.findSafeFreezeBoundary(text, candidateLength = 5)
        assertEquals(3, boundary)
        assertEquals("昨日は", text.substring(0, boundary))

        // Katakana -> Hiragana transition
        val loanwordText = "ピクニックに行く予定です"
        // Candidate length 6: "ピクニックに"
        val loanwordBoundary = JapanesePunctuationEngine.findSafeFreezeBoundary(loanwordText, candidateLength = 6)
        // Should safely snap at the Katakana word boundary or particle
        assertTrue("Boundary should be at least at loanword boundary", loanwordBoundary >= 5)
    }

    @Test
    fun testCatA_CompoundSentenceGradualFreeze() {
        // Simulation of prolonged multi-clause compound sentence
        // Step 1: initial clause
        val step1 = "昨日は天気が良かったので、家族と"
        // Step 2: continued speaking
        val step2 = "昨日は天気が良かったので、家族と一緒に近くの公園に"
        // Step 3: further continued
        val step3 = "昨日は天気が良かったので、家族と一緒に近くの公園に行って遊びました"

        val history = listOf(step1, step2, step3)

        val (frozen, active) = JapanesePunctuationEngine.extractMorphemeAwareFreezePrefix(
            history = history,
            currentFormatted = step3,
            minConsecutiveSteps = 3,
            protectTrailingChars = 12
        )

        // The stable older clause "昨日は天気が良かったので、" must be frozen for commit
        assertEquals("昨日は天気が良かったので、", frozen)
        // The active remainder stays in composing text
        assertEquals("家族と一緒に近くの公園に行って遊びました", active)
        // Full text is preserved without duplication or omission
        assertEquals(step3, frozen + active)
    }

    @Test
    fun testCatB_UnpunctuatedLongPhraseScriptSnap() {
        // Long noun-modifying phrase with no commas
        val step1 = "緑豊かな公園の噴水の前で"
        val step2 = "緑豊かな公園の噴水の前で撮った記念写真"
        val step3 = "緑豊かな公園の噴水の前で撮った記念写真を見せてもらいました"

        val history = listOf(step1, step2, step3)

        val (frozen, active) = JapanesePunctuationEngine.extractMorphemeAwareFreezePrefix(
            history = history,
            currentFormatted = step3,
            minConsecutiveSteps = 3,
            protectTrailingChars = 12
        )

        // Must freeze at a safe script transition (e.g. before "撮った" or "噴水の")
        assertTrue("Must freeze stable prefix", frozen.isNotEmpty())
        assertTrue("Frozen text must start with the beginning", frozen.startsWith("緑豊かな"))
        assertEquals("Recombined text matches full input", step3, frozen + active)
        // Ensure no split in middle of a Kanji word
        assertFalse("Should not cut middle of 記念写真", frozen.endsWith("記") || frozen.endsWith("記念"))
    }

    @Test
    fun testCatC_RightContextPreservation() {
        // Short ambiguous input where right context later refines the meaning:
        // "きょうはてん" -> "今日は、" (tentative comma)
        val step1 = "今日は、"
        val step2 = "今日は天気"
        val step3 = "今日は天気が良いです。"

        val history = listOf(step1, step2, step3)

        // During short utterance (< 12 chars), Active Horizon Clamping must protect the whole text
        val (frozen, active) = JapanesePunctuationEngine.extractMorphemeAwareFreezePrefix(
            history = history,
            currentFormatted = step2,
            minConsecutiveSteps = 3,
            protectTrailingChars = 12
        )

        // Must NOT freeze prematurely! Text must remain 100% active in Composing
        assertEquals("No premature freeze", "", frozen)
        assertEquals("Composing holds full active text", step2, active)
    }

    @Test
    fun testCatD_ShortConversationalSentence() {
        val input = "承知いたしました。"
        val history = listOf("承知", "承知いたしました", "承知いたしました。")

        val (frozen, active) = JapanesePunctuationEngine.extractMorphemeAwareFreezePrefix(
            history = history,
            currentFormatted = input,
            minConsecutiveSteps = 3,
            protectTrailingChars = 12
        )

        // Sentence-level terminal '。' is handled by sentence split or finalization
        assertEquals("", frozen)
        assertEquals(input, active)
    }
}
