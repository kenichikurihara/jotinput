package com.example.myasr.context.speech.domain

/**
 * Supported on-device speech recognition languages.
 */
enum class AsrLanguage {
    JAPANESE,
    ENGLISH
}

/**
 * Pure domain policy resolving which ASR model language should be used
 * for the current keyboard mode and model availability.
 */
object AsrLanguagePolicy {
    fun resolveLanguage(
        isEnglishKeyboardMode: Boolean,
        isEnglishModelAvailable: Boolean
    ): AsrLanguage {
        return if (isEnglishKeyboardMode && isEnglishModelAvailable) {
            AsrLanguage.ENGLISH
        } else {
            AsrLanguage.JAPANESE
        }
    }
}
