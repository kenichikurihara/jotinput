package com.example.myasr.context.kanakanji.application

import com.example.myasr.context.kanakanji.domain.KanaKanjiEngine
import com.example.myasr.context.vocabulary.domain.DictionaryEntry
import com.example.myasr.context.vocabulary.domain.Priority
import com.example.myasr.context.vocabulary.domain.VocabularyMapper
import com.example.myasr.shared.domain.InputLanguage
import com.example.myasr.shared.event.DomainEventBus
import com.example.myasr.shared.event.UserDictionaryChangedEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VocabularyMapperAndSyncTest {

    @Test
    fun `VocabularyMapper preserves Japanese and English semantics across context boundary`() {
        val jpEntry = DictionaryEntry(
            id = "jp-1",
            word = "中村",
            reading = "なかむら",
            priority = Priority.HIGH,
            isEnabled = true
        )
        val publishedJp = VocabularyMapper.toPublishedItem(jpEntry)
        assertEquals(InputLanguage.JAPANESE, publishedJp.language)
        val restoredJp = VocabularyMapper.toDomainEntry(publishedJp)
        assertEquals("中村", restoredJp.word)
        assertEquals("なかむら", restoredJp.reading)
        assertEquals(Priority.HIGH, restoredJp.priority)

        val enEntry = DictionaryEntry(
            id = "en-1",
            word = "Antigravity",
            reading = "antigravity",
            priority = Priority.NORMAL,
            isEnabled = true
        )
        val publishedEn = VocabularyMapper.toPublishedItem(enEntry)
        assertEquals(InputLanguage.ENGLISH, publishedEn.language)
    }

    @Test
    fun `KanaKanjiVocabularySyncAdapter syncs engine on UserDictionaryChangedEvent`() {
        val engine = KanaKanjiEngine(context = null)
        val bus = DomainEventBus()
        val adapter = KanaKanjiVocabularySyncAdapter(engine, bus)
        adapter.register()

        val entry = DictionaryEntry(
            id = "sync-1",
            word = "境界づけられた文脈",
            reading = "きょうかいづけられたぶんみゃく",
            priority = Priority.HIGH,
            isEnabled = true
        )
        bus.publish(UserDictionaryChangedEvent(listOf(VocabularyMapper.toPublishedItem(entry))))

        val candidates = engine.getCandidates("きょうかいづけられたぶんみゃく")
        assertTrue(
            "Expected synced custom vocabulary candidate in KanaKanjiEngine",
            candidates.any { it.surface == "境界づけられた文脈" }
        )

        adapter.unregister()
    }
}
