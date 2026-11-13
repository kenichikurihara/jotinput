package com.example.myasr.context.kanakanji.application

import com.example.myasr.context.kanakanji.domain.KanaKanjiEngine
import com.example.myasr.context.vocabulary.domain.VocabularyMapper
import com.example.myasr.shared.domain.VocabularyItem
import com.example.myasr.shared.event.DomainEventBus
import com.example.myasr.shared.event.UserDictionaryChangedEvent

/**
 * Anti-Corruption Layer (ACL):
 * BC-5 (Custom Vocabulary) の Published Language (VocabularyItem) を
 * BC-1 (Kana-Kanji Conversion) の内部語彙モデルへ変換して同期するアダプター。
 */
class KanaKanjiVocabularySyncAdapter(
    private val kanaKanjiEngine: KanaKanjiEngine,
    private val eventBus: DomainEventBus = DomainEventBus.global
) {
    private var unsubscribe: (() -> Unit)? = null

    fun register() {
        unsubscribe?.invoke()
        unsubscribe = eventBus.subscribe<UserDictionaryChangedEvent> { event ->
            sync(event.activeItems)
        }
    }

    fun unregister() {
        unsubscribe?.invoke()
        unsubscribe = null
    }

    fun sync(activeItems: List<VocabularyItem>) {
        val entries = activeItems.map { VocabularyMapper.toDomainEntry(it) }
        kanaKanjiEngine.syncUserDictionary(entries)
    }
}
