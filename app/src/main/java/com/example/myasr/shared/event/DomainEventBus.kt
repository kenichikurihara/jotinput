package com.example.myasr.shared.event

import com.example.myasr.context.kanakanji.application.UserHistorySyncManager

import com.example.myasr.shared.domain.InputLanguage
import com.example.myasr.shared.domain.VocabularyItem
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * ドメインイベントの基底インターフェース。
 */
interface DomainEvent {
    val occurredAtMillis: Long
}

/**
 * BC-5 (Custom Vocabulary) から発行され、BC-1 / BC-3 / BC-4 が購読する語彙変更イベント。
 */
data class UserDictionaryChangedEvent(
    val activeItems: List<VocabularyItem>,
    override val occurredAtMillis: Long = System.currentTimeMillis()
) : DomainEvent

/**
 * BC-7 (IME Session) から発行され、BC-1 (UserHistorySyncManager) 等が購読するテキスト確定イベント。
 */
data class TextCommittedEvent(
    val surface: String,
    val reading: String,
    val language: InputLanguage = InputLanguage.JAPANESE,
    override val occurredAtMillis: Long = System.currentTimeMillis()
) : DomainEvent

/**
 * BC-6 (Provisioning) から発行され、各エンジンへアセット準備完了を通知するイベント。
 */
data class AssetProvisionedEvent(
    val assetCategory: String,
    val path: String,
    override val occurredAtMillis: Long = System.currentTimeMillis()
) : DomainEvent

/**
 * コンテキスト間の疎結合連携を担うスレッドセーフなインメモリ DomainEventBus。
 */
class DomainEventBus {
    private val listeners = ConcurrentHashMap<Class<out DomainEvent>, CopyOnWriteArrayList<(DomainEvent) -> Unit>>()

    @Suppress("UNCHECKED_CAST")
    fun <T : DomainEvent> subscribe(eventType: Class<T>, listener: (T) -> Unit): () -> Unit {
        val list = listeners.computeIfAbsent(eventType) { CopyOnWriteArrayList() }
        val wrapper: (DomainEvent) -> Unit = { event -> listener(event as T) }
        list.add(wrapper)
        return { list.remove(wrapper) }
    }

    inline fun <reified T : DomainEvent> subscribe(noinline listener: (T) -> Unit): () -> Unit {
        return subscribe(T::class.java, listener)
    }

    fun publish(event: DomainEvent) {
        listeners[event::class.java]?.forEach { listener ->
            try {
                listener(event)
            } catch (_: Throwable) {
                // Keep event bus resilient against individual subscriber failures
            }
        }
    }

    fun clearAll() {
        listeners.clear()
    }

    companion object {
        val global: DomainEventBus = DomainEventBus()
    }
}
