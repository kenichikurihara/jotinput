package com.example.myasr.shared.event

import com.example.myasr.shared.domain.InputLanguage
import com.example.myasr.shared.domain.VocabularyItem
import com.example.myasr.shared.domain.VocabularyPriority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainEventBusTest {

    @Test
    fun `publish delivers event to subscribed listeners and supports unsubscribe`() {
        val bus = DomainEventBus()
        val received = mutableListOf<UserDictionaryChangedEvent>()

        val unsubscribe = bus.subscribe<UserDictionaryChangedEvent> { event ->
            received.add(event)
        }

        val item = VocabularyItem(
            id = "1",
            word = "中村",
            reading = "なかむら",
            priority = VocabularyPriority.HIGH,
            isEnabled = true,
            language = InputLanguage.JAPANESE
        )
        bus.publish(UserDictionaryChangedEvent(listOf(item)))
        assertEquals(1, received.size)
        assertEquals("中村", received[0].activeItems[0].word)

        unsubscribe()
        bus.publish(UserDictionaryChangedEvent(emptyList()))
        assertEquals(1, received.size)
    }

    @Test
    fun `subscriber exception does not prevent other subscribers from receiving events`() {
        val bus = DomainEventBus()
        var secondCalled = false

        bus.subscribe<TextCommittedEvent> {
            throw RuntimeException("simulated subscriber failure")
        }
        bus.subscribe<TextCommittedEvent> {
            secondCalled = true
        }

        bus.publish(TextCommittedEvent(surface = "テスト", reading = "てすと"))
        assertTrue(secondCalled)
    }
}
