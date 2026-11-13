package com.example.myasr.context.vocabulary.domain

import com.example.myasr.shared.domain.InputLanguage
import com.example.myasr.shared.domain.VocabularyItem
import com.example.myasr.shared.domain.VocabularyPriority

/**
 * BC-5 (Custom Vocabulary) の集約ルート DictionaryEntry を
 * Published Language (VocabularyItem) へ変換するマッパー。
 */
object VocabularyMapper {
    fun toPublishedItem(entry: DictionaryEntry): VocabularyItem {
        val lang = when {
            entry.isJapaneseEntry -> InputLanguage.JAPANESE
            entry.isEnglishOnlyEntry || entry.hasEnglishSurface -> InputLanguage.ENGLISH
            else -> InputLanguage.OTHER
        }
        val prio = when (entry.priority) {
            Priority.LOW -> VocabularyPriority.LOW
            Priority.NORMAL -> VocabularyPriority.NORMAL
            Priority.HIGH -> VocabularyPriority.HIGH
        }
        return VocabularyItem(
            id = entry.id,
            word = entry.word,
            reading = entry.reading,
            priority = prio,
            isEnabled = entry.isEnabled,
            language = lang
        )
    }

    fun toDomainEntry(item: VocabularyItem): DictionaryEntry {
        val prio = when (item.priority) {
            VocabularyPriority.LOW -> Priority.LOW
            VocabularyPriority.NORMAL -> Priority.NORMAL
            VocabularyPriority.HIGH -> Priority.HIGH
        }
        return DictionaryEntry(
            id = item.id,
            word = item.word,
            reading = item.reading,
            priority = prio,
            isEnabled = item.isEnabled
        )
    }
}
