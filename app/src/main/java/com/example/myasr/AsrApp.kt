package com.example.myasr

import android.app.Application
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AsrApp : Application() {
    private val TAG = "AsrApp"

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
    lateinit var llmReranker: LlmReranker
        private set
    lateinit var modelWarmUpManager: ModelWarmUpManager
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
        postProcessor = DictionaryPostProcessor { kanaKanjiEngine }
        contextAwareProcessor = ContextAwareProcessor { kanaKanjiEngine }
        asrManager = AsrManager(this)
        baseDictionaryDownloader = BaseDictionaryDownloader(this)
        multiDictionaryManager = MultiDictionaryManager(this)
        dictionaryUpdateTracker = DictionaryUpdateTracker(this)
        connectionDataManager = ConnectionDataManager(this)
        llmReranker = LlmReranker.getInstance(this)
        modelWarmUpManager = ModelWarmUpManager.getInstance(this)

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
        kanaKanjiEngine.syncUserDictionary(activeEntries)
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
