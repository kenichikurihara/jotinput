package com.example.myasr.context.english.domain

import android.view.inputmethod.EditorInfo

/**
 * Algorithmic analyzer for English QWERTY and Swipe input that determines
 * truecasing and capitalization candidates (Sentence Start, Pronoun 'I' and
 * contractions, Corpus Acronyms, OOV Acronyms, Proper Nouns, Mixed-Case brands)
 * via [SentenceBoundaryDetector], [EnglishCaseModel], and [BayesianCaseRanker].
 */
object EnglishCapitalizationAnalyzer {

    enum class CapitalizationType {
        NONE,
        SENTENCE_START,
        PRONOUN_I,
        ALL_CAPS,
        PROPER_NOUN
    }

    data class CapitalizationResult(
        val type: CapitalizationType,
        val primaryCandidate: String?,
        val additionalCandidates: List<String> = emptyList(),
        val bosProbability: Float = 0.0f
    )

    /**
     * Maps Android [EditorInfo] and pre-cursor symbols to a pure domain [InputFieldCapPolicy].
     */
    fun resolveFieldPolicy(editorInfo: EditorInfo?, textBeforeCursor: String): InputFieldCapPolicy {
        if (shouldBypassCapitalization(editorInfo, textBeforeCursor)) {
            return InputFieldCapPolicy.BYPASS
        }
        if (editorInfo != null) {
            if ((editorInfo.inputType and EditorInfo.TYPE_TEXT_FLAG_CAP_CHARACTERS) != 0) {
                return InputFieldCapPolicy.CAP_CHARACTERS
            }
            if ((editorInfo.inputType and EditorInfo.TYPE_TEXT_FLAG_CAP_WORDS) != 0) {
                return InputFieldCapPolicy.CAP_WORDS
            }
            if ((editorInfo.inputType and EditorInfo.TYPE_TEXT_FLAG_CAP_SENTENCES) != 0) {
                return InputFieldCapPolicy.CAP_SENTENCES
            }
        }
        return InputFieldCapPolicy.STANDARD
    }

    /**
     * Determines whether capitalization suggestions should be completely bypassed
     * based on field characteristics (passwords, emails, URIs, code symbols).
     */
    fun shouldBypassCapitalization(editorInfo: EditorInfo?, textBeforeCursor: String): Boolean {
        if (editorInfo != null) {
            val variation = editorInfo.inputType and EditorInfo.TYPE_MASK_VARIATION
            if (variation == EditorInfo.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == EditorInfo.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                variation == EditorInfo.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                variation == EditorInfo.TYPE_TEXT_VARIATION_URI
            ) {
                return true
            }
            if ((editorInfo.inputType and EditorInfo.TYPE_CLASS_NUMBER) != 0 &&
                variation == EditorInfo.TYPE_NUMBER_VARIATION_PASSWORD
            ) {
                return true
            }
            if ((editorInfo.inputType and EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0) {
                return true
            }
        }

        // Check if preceding character is a programming, mention, or path identifier symbol
        val rawPrefix = textBeforeCursor.dropLastWhile { it.isLetter() || it == '\'' }
        if (rawPrefix.isNotEmpty()) {
            val lastChar = rawPrefix.last()
            if (lastChar == '@' || lastChar == '/' || lastChar == '#' || lastChar == '$' ||
                lastChar == '_' || lastChar == '\\'
            ) {
                return true
            }
        }

        return false
    }

    /**
     * Analyzes context and current word to produce algorithmic truecasing candidates.
     */
    fun analyze(
        rawWord: String,
        textBeforeCursor: String,
        isShiftActive: Boolean,
        policy: InputFieldCapPolicy = InputFieldCapPolicy.STANDARD
    ): CapitalizationResult {
        if (rawWord.isEmpty() || policy == InputFieldCapPolicy.BYPASS) {
            return CapitalizationResult(CapitalizationType.NONE, null)
        }

        // Preserve user's explicit Shift or manual uppercase intent
        if (isShiftActive || rawWord.first().isUpperCase()) {
            return CapitalizationResult(CapitalizationType.NONE, null)
        }

        val bosProb = SentenceBoundaryDetector.computeBosProbability(rawWord, textBeforeCursor, policy)
        val ranked = BayesianCaseRanker.generateCaseVariants(
            baseWord = rawWord,
            bosProb = bosProb,
            policy = policy,
            source = "english_cap",
            isRawWord = true
        )

        if (ranked.isEmpty()) {
            return CapitalizationResult(CapitalizationType.NONE, null, bosProbability = bosProb)
        }

        val top = ranked.first()
        // If the top-ranked candidate is identical to rawWord, check if any secondary capitalized variant exists
        val capitalizedVariants = ranked.filter { it.surface != rawWord }.map { it.surface }
        if (top.surface == rawWord) {
            if (capitalizedVariants.isEmpty()) {
                return CapitalizationResult(CapitalizationType.NONE, null, bosProbability = bosProb)
            }
            // Ambiguous word where lowercase is slightly more frequent (e.g. "us" -> ["us", "US"]),
            // still expose the secondary capitalized candidate in additionalCandidates!
            return CapitalizationResult(
                type = CapitalizationType.NONE,
                primaryCandidate = null,
                additionalCandidates = capitalizedVariants,
                bosProbability = bosProb
            )
        }

        val type = classifyCapitalizationType(top.surface, bosProb)
        return CapitalizationResult(
            type = type,
            primaryCandidate = top.surface,
            additionalCandidates = capitalizedVariants.drop(1),
            bosProbability = bosProb
        )
    }

    /**
     * Inspects preceding text before rawWord to detect sentence start via
     * Punkt-style probabilistic boundary detection.
     */
    fun isSentenceStart(rawWord: String, textBeforeCursor: String): Boolean {
        return SentenceBoundaryDetector.computeBosProbability(rawWord, textBeforeCursor) >= 0.5f
    }

    private fun classifyCapitalizationType(surface: String, bosProb: Float): CapitalizationType {
        if (surface == "I" || surface.startsWith("I'")) {
            return CapitalizationType.PRONOUN_I
        }
        return when (BayesianCaseRanker.classifyShape(surface)) {
            BayesianCaseRanker.CaseShape.UPPER -> CapitalizationType.ALL_CAPS
            BayesianCaseRanker.CaseShape.TITLE,
            BayesianCaseRanker.CaseShape.SPECIAL -> {
                if (bosProb >= 0.5f) CapitalizationType.SENTENCE_START else CapitalizationType.PROPER_NOUN
            }
            BayesianCaseRanker.CaseShape.LOWER -> CapitalizationType.NONE
        }
    }
}
