package com.example.myasr.context.speech.application

import com.example.myasr.context.kanakanji.domain.KanaKanjiEngine
import com.example.myasr.context.speech.domain.ContextAwareProcessor
import com.example.myasr.context.vocabulary.domain.DictionaryEntry

import android.util.Log

class DictionaryPostProcessor(
    private val kanaEngineProvider: (() -> KanaKanjiEngine?)? = null
) {
    private val TAG = "DictionaryPostProc"
    private val contextAwareProcessor = ContextAwareProcessor(kanaEngineProvider)

    /**
     * Applies dictionary post-processing to the decoded ASR transcription.
     * Replaces phonetic Kana readings, character variants, competing homophones,
     * and case variations with the target surface word.
     */
    fun process(rawText: String, entries: List<DictionaryEntry>): String {
        if (rawText.isBlank() || entries.isEmpty()) {
            return rawText
        }
        val result = contextAwareProcessor.process(rawText, "", entries, formatPunctuation = false)
        if (result != rawText) {
            Log.d(TAG, "Post-processed transcription: '$rawText' -> '$result'")
        }
        return result
    }
}
