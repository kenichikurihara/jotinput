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
import com.example.myasr.context.speech.domain.AsrLanguage
import com.example.myasr.context.speech.domain.ContextAwareProcessor
import com.example.myasr.context.speech.infrastructure.AsrManager
import com.example.myasr.context.speech.infrastructure.VoiceCorrectionRepository
import com.example.myasr.context.vocabulary.domain.DictionaryEntry
import com.example.myasr.context.vocabulary.domain.Priority
import com.example.myasr.context.vocabulary.domain.VocabularyMapper
import com.example.myasr.context.vocabulary.infrastructure.DictionaryRepository
import com.example.myasr.shared.domain.InputLanguage
import com.example.myasr.shared.event.DomainEventBus
import com.example.myasr.shared.event.UserDictionaryChangedEvent
import com.example.myasr.shared.event.VoiceCorrectionLearnedEvent

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
    lateinit var voiceCorrectionRepository: VoiceCorrectionRepository
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
        voiceCorrectionRepository = VoiceCorrectionRepository(this, eventBus)
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

        eventBus.subscribe<VoiceCorrectionLearnedEvent> { event ->
            CoroutineScope(Dispatchers.Default).launch {
                if (event.language == InputLanguage.JAPANESE) {
                    val activeEntries = dictionaryRepository.getActive()
                    val learnedJp = voiceCorrectionRepository.getActiveBoostEntries(AsrLanguage.JAPANESE)
                    hotwordsGenerator.generate(activeEntries, learnedJp)
                } else if (event.language == InputLanguage.ENGLISH) {
                    syncEnglishEnginesWithLearnedCorrections()
                }
            }
        }

        // Asynchronously preload and warm up all core engines (KanaKanji, SLM, ASR) off the UI main thread
        modelWarmUpManager.startWarmUpAsync()

        // Ensure OS periodic background Google Drive sync matches current user & permission state
        com.example.myasr.context.kanakanji.application.UserHistorySyncManager
            .getInstance(this)
            .ensurePeriodicSyncSchedule()

        Log.i(TAG, "AsrApp initialized. Shared ASR, IME & SLM components ready.")
    }

    @Synchronized
    fun ensureModelInitialized(language: AsrLanguage = AsrLanguage.JAPANESE): Boolean {
        if (!asrManager.isModelAvailable(language)) {
            Log.w(TAG, "ASR Model ($language) files not available yet")
            return false
        }
        if (language == AsrLanguage.ENGLISH) {
            return asrManager.initialize(null, language)
        }
        val activeEntries = dictionaryRepository.getActive()
        val learnedJp = voiceCorrectionRepository.getActiveBoostEntries(AsrLanguage.JAPANESE)
        val hotwordsFile = hotwordsGenerator.generate(activeEntries, learnedJp)
        return asrManager.initialize(hotwordsFile, language)
    }

    @Volatile
    private var lastSyncedDictionarySignature: Int? = null

    private fun buildCombinedEnglishEntries(activeEntries: List<DictionaryEntry>): List<DictionaryEntry> {
        val learnedEn = voiceCorrectionRepository.getActiveBoostEntries(AsrLanguage.ENGLISH)
        if (learnedEn.isEmpty()) return activeEntries
        val virtualEntries = learnedEn.map { corr ->
            val prio = when {
                corr.netCount() >= 3 -> Priority.HIGH
                corr.netCount() >= 2 -> Priority.NORMAL
                else -> Priority.LOW
            }
            DictionaryEntry(
                id = "vc_${corr.id}",
                word = corr.correctedSurface,
                reading = corr.normalizedReading.ifEmpty { corr.correctedSurface.lowercase() },
                priority = prio,
                isEnabled = true,
                updatedAtMillis = corr.lastCorrectedAtMs
            )
        }
        return activeEntries + virtualEntries
    }

    private fun syncEnglishEnginesWithLearnedCorrections() {
        val combined = buildCombinedEnglishEntries(dictionaryRepository.getActive())
        try {
            val swipeEngine = FutoSwipeEngine.getInstance(this)
            if (swipeEngine.isReady()) {
                swipeEngine.syncUserDictionary(combined)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Sync learned corrections to FutoSwipeEngine skipped: ${e.message}")
        }
        try {
            val spellEngine = EnglishSpellEngine.getInstance(this)
            if (spellEngine.isReady()) {
                spellEngine.syncUserDictionary(combined)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Sync learned corrections to EnglishSpellEngine skipped: ${e.message}")
        }
    }

    fun syncAllUserDictionaries(force: Boolean = false) {
        val activeEntries = dictionaryRepository.getActive()
        val learnedJp = voiceCorrectionRepository.getActiveBoostEntries(AsrLanguage.JAPANESE)
        val learnedEn = voiceCorrectionRepository.getActiveBoostEntries(AsrLanguage.ENGLISH)
        val signature = Pair(
            activeEntries.map { Triple(it.word, it.reading, it.priority.score) },
            (learnedJp + learnedEn).map { Triple(it.originalSurface, it.correctedSurface, it.netCount()) }
        ).hashCode()
        if (!force && signature == lastSyncedDictionarySignature) {
            return
        }
        lastSyncedDictionarySignature = signature

        val hotwordsFile = hotwordsGenerator.generate(activeEntries, learnedJp)
        val publishedItems = activeEntries.map { VocabularyMapper.toPublishedItem(it) }
        eventBus.publish(UserDictionaryChangedEvent(publishedItems))
        syncEnglishEnginesWithLearnedCorrections()
        if (asrManager.isInitialized(AsrLanguage.JAPANESE)) {
            CoroutineScope(Dispatchers.Default).launch {
                try {
                    asrManager.reload(hotwordsFile)
                } catch (e: Exception) {
                    Log.w(TAG, "Reload ASR hotwords skipped: ${e.message}")
                }
            }
        }
    }
}
