package com.example.myasr

import com.example.myasr.context.clipboard.infrastructure.ClipboardHistoryRepository
import com.example.myasr.context.english.infrastructure.EnglishSpellEngine
import com.example.myasr.context.english.infrastructure.FutoSwipeEngine
import com.example.myasr.context.kanakanji.application.KanaKanjiVocabularySyncAdapter
import com.example.myasr.context.kanakanji.domain.KanaKanjiEngine
import com.example.myasr.context.kanakanji.domain.NextPhrasePredictor
import com.example.myasr.context.provisioning.domain.DictionaryUpdateTracker
import com.example.myasr.context.provisioning.domain.ModelWarmUpManager
import com.example.myasr.context.provisioning.infrastructure.BaseDictionaryDownloader
import com.example.myasr.context.provisioning.infrastructure.ConnectionDataManager
import com.example.myasr.context.provisioning.infrastructure.MultiDictionaryManager
import com.example.myasr.context.reranking.infrastructure.LlmReranker
import com.example.myasr.context.speech.application.DictionaryPostProcessor
import com.example.myasr.context.speech.application.HotwordsGenerator
import com.example.myasr.context.speech.domain.ContextAwareProcessor
import com.example.myasr.context.speech.infrastructure.AsrManager
import com.example.myasr.context.vocabulary.domain.VocabularyMapper
import com.example.myasr.context.vocabulary.infrastructure.DictionaryRepository
import com.example.myasr.shared.event.DomainEventBus
import com.example.myasr.shared.event.UserDictionaryChangedEvent

import android.app.Application
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AsrApp : Application() {
    private val TAG = "AsrApp"

    val eventBus: DomainEventBus = DomainEventBus.global
    lateinit var dictionaryRepository: DictionaryRepository
        private set
    lateinit var hotwordsGenerator: HotwordsGenerator
        private set
    lateinit var postProcessor: DictionaryPostProcessor
        private set
    lateinit var contextAwareProcessor: ContextAwareProcessor
        private set
    lateinit var asrManager: AsrManager
        private set
    lateinit var baseDictionaryDownloader: BaseDictionaryDownloader
        private set
    lateinit var multiDictionaryManager: MultiDictionaryManager
        private set
    lateinit var dictionaryUpdateTracker: DictionaryUpdateTracker
        private set
    lateinit var connectionDataManager: ConnectionDataManager
        private set
    lateinit var kanaKanjiEngine: KanaKanjiEngine
        private set
    lateinit var kanaKanjiVocabularySyncAdapter: KanaKanjiVocabularySyncAdapter
        private set
    lateinit var nextPhrasePredictor: NextPhrasePredictor
        private set
    lateinit var llmReranker: LlmReranker
        private set
    lateinit var modelWarmUpManager: ModelWarmUpManager
        private set
    lateinit var clipboardHistoryRepository: ClipboardHistoryRepository
        private set

    companion object {
        lateinit var instance: AsrApp
            private set

        val instanceOrNull: AsrApp?
            get() = if (::instance.isInitialized) instance else null
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        dictionaryRepository = DictionaryRepository(this)
        hotwordsGenerator = HotwordsGenerator(this)
        kanaKanjiEngine = KanaKanjiEngine.getInstance(this)
        kanaKanjiVocabularySyncAdapter = KanaKanjiVocabularySyncAdapter(kanaKanjiEngine, eventBus).apply {
            register()
        }
        nextPhrasePredictor = NextPhrasePredictor.getInstance(this)
        postProcessor = DictionaryPostProcessor { kanaKanjiEngine }
        contextAwareProcessor = ContextAwareProcessor { kanaKanjiEngine }
        asrManager = AsrManager(this)
        baseDictionaryDownloader = BaseDictionaryDownloader(this)
        multiDictionaryManager = MultiDictionaryManager(this)
        dictionaryUpdateTracker = DictionaryUpdateTracker(this)
        connectionDataManager = ConnectionDataManager(this)
        llmReranker = LlmReranker.getInstance(this)
        modelWarmUpManager = ModelWarmUpManager.getInstance(this)
        clipboardHistoryRepository = ClipboardHistoryRepository.getInstance(this)

        // Asynchronously preload and warm up all core engines (KanaKanji, SLM, ASR) off the UI main thread
        modelWarmUpManager.startWarmUpAsync()

        Log.i(TAG, "AsrApp initialized. Shared ASR, IME & SLM components ready.")
    }

    @Synchronized
    fun ensureModelInitialized(): Boolean {
        if (!asrManager.isModelAvailable()) {
            Log.w(TAG, "ASR Model files not available yet")
            return false
        }
        val activeEntries = dictionaryRepository.getActive()
        val hotwordsFile = hotwordsGenerator.generate(activeEntries)
        return asrManager.initialize(hotwordsFile)
    }

    fun syncAllUserDictionaries() {
        val activeEntries = dictionaryRepository.getActive()
        val hotwordsFile = hotwordsGenerator.generate(activeEntries)
        val publishedItems = activeEntries.map { VocabularyMapper.toPublishedItem(it) }
        eventBus.publish(UserDictionaryChangedEvent(publishedItems))
        try {
            FutoSwipeEngine.getInstance(this).syncUserDictionary(activeEntries)
        } catch (e: Exception) {
            Log.w(TAG, "Sync to FutoSwipeEngine skipped: ${e.message}")
        }
        try {
            EnglishSpellEngine.getInstance(this).syncUserDictionary(activeEntries)
        } catch (e: Exception) {
            Log.w(TAG, "Sync to EnglishSpellEngine skipped: ${e.message}")
        }
        CoroutineScope(Dispatchers.Default).launch {
            try {
                asrManager.reload(hotwordsFile)
            } catch (e: Exception) {
                Log.w(TAG, "Reload ASR hotwords skipped: ${e.message}")
            }
        }
    }
}
