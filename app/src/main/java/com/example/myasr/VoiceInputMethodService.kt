package com.example.myasr

import com.example.myasr.context.clipboard.domain.ClipboardSuggestionPolicy
import com.example.myasr.context.clipboard.domain.ClipboardUtils
import com.example.myasr.context.clipboard.infrastructure.ClipboardHistoryItem
import com.example.myasr.context.clipboard.infrastructure.ClipboardHistoryRepository
import com.example.myasr.context.clipboard.infrastructure.RecentSuggestion
import com.example.myasr.context.clipboard.infrastructure.SensitiveClipMetadata
import com.example.myasr.context.clipboard.presentation.ClipboardHistoryView
import com.example.myasr.context.english.domain.BayesianCaseRanker
import com.example.myasr.context.english.domain.EnglishAssistJudgement
import com.example.myasr.context.english.domain.EnglishCapitalizationAnalyzer
import com.example.myasr.context.english.domain.EnglishDictionaryUtils
import com.example.myasr.context.english.domain.InputFieldCapPolicy
import com.example.myasr.context.english.domain.SentenceBoundaryDetector
import com.example.myasr.context.english.infrastructure.EnglishSpellEngine
import com.example.myasr.context.english.infrastructure.FutoSwipeEngine
import com.example.myasr.context.ime.domain.InputConnectionEditorBridge
import com.example.myasr.context.ime.domain.VoiceTouchInputArbitrator
import com.example.myasr.context.ime.presentation.CandidateStripView
import com.example.myasr.context.ime.presentation.FlickKeyboardView
import com.example.myasr.context.ime.presentation.KeyboardHeightManager
import com.example.myasr.context.kanakanji.application.UserHistorySyncManager
import com.example.myasr.context.kanakanji.domain.ConversionCandidate
import com.example.myasr.context.kanakanji.domain.JapaneseKanaUtils
import com.example.myasr.context.kanakanji.domain.KanaKanjiEngine
import com.example.myasr.context.kanakanji.domain.NextPhrasePredictor
import com.example.myasr.context.provisioning.domain.ModelWarmUpManager
import com.example.myasr.context.reranking.domain.CandidateRerankJudgement
import com.example.myasr.context.reranking.domain.ContextWindowTrimmer
import com.example.myasr.context.reranking.infrastructure.LlmReranker
import com.example.myasr.context.speech.domain.AsrLanguage
import com.example.myasr.context.speech.domain.AsrLanguagePolicy
import com.example.myasr.context.speech.domain.BoostAction
import com.example.myasr.context.speech.domain.EnglishVoicePostProcessor
import com.example.myasr.context.speech.domain.JapanesePunctuationEngine
import com.example.myasr.context.speech.domain.ProsodyBoundaryMetadata
import com.example.myasr.context.speech.domain.ProsodyPitchEstimator
import com.example.myasr.context.speech.domain.VoiceCorrectionDetector
import com.example.myasr.context.speech.domain.VoiceCorrectionSuggestion
import com.example.myasr.context.speech.infrastructure.AudioRecorder
import com.example.myasr.context.vocabulary.domain.Priority

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class VoiceInputMethodService : InputMethodService() {
    private val TAG = "VoiceIME"

    private enum class State {
        IDLE,
        RECORDING,
        DECODING
    }

    private var currentState = State.IDLE
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var rerankJob: Job? = null
    private var phase1TimerJob: Job? = null
    private var englishCandidateJob: Job? = null
    private var warmContextJob: Job? = null
    private var speculativePrewarmJob: Job? = null
    private var lastWarmedContextText: String? = null
    private var isCurrentCandidatesReranked: Boolean = false
    private val audioRecorder = AudioRecorder()
    private val asrDecodingMutex = Mutex()
    private lateinit var kanaKanjiEngine: KanaKanjiEngine
    private lateinit var nextPhrasePredictor: NextPhrasePredictor

    // Zero-Jitter SLM Reranking & Tap-Safe Window State
    private var lastKeystrokeUptimeMs: Long = 0L
    private var firstRenderUptimeMs: Long = 0L
    private var initialRenderedTop1: String? = null

    // Multimodal Voice + Touch Input Arbitrator
    private val arbitrator = VoiceTouchInputArbitrator()
    private val editorBridge = InputConnectionEditorBridge { currentInputConnection }

    // Recent auto-applied voice corrections (correctedSurface -> (originalSurface, language)) for revert detection
    private val recentAutoAppliedCorrections = object : LinkedHashMap<String, Pair<String, AsrLanguage>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<String, AsrLanguage>>?): Boolean {
            return size > 12
        }
    }
    private var activeVoiceCorrectionChipsLanguage: AsrLanguage = AsrLanguage.JAPANESE

    // Streaming voice input state
    private var isStreamingActive = false
    private var streamingJob: Job? = null
    private var isCurrentlySpeaking = false
    private var restartListeningAfterDecode = false

    // UI elements
    private var keyboardRoot: View? = null
    private var contextBarContainer: View? = null
    private var tvContextBadge: android.widget.TextView? = null
    private var tvDebugSlmBadge: android.widget.TextView? = null
    private var tvContextContent: android.widget.TextView? = null
    private var keyboardHeader: View? = null
    private var voiceToggleContainer: View? = null
    private var btnVoiceToggle: Button? = null
    private var btnHeaderDict: Button? = null
    private var btnHeaderClipboard: Button? = null
    private var candidateStripView: CandidateStripView? = null
    private var flickKeyboardView: FlickKeyboardView? = null
    private var clipboardHistoryView: ClipboardHistoryView? = null
    private var progressDecoding: ProgressBar? = null
    private var recordingIndicator: View? = null
    private var isKeyTouchActive: Boolean = false

    private var clipboardManager: android.content.ClipboardManager? = null
    private var clipboardExpirationJob: Job? = null
    private val clipboardSuggestionPolicy = ClipboardSuggestionPolicy()

    private val prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        val action = Runnable {
            applyKeyboardDimensions()
            updateContextBar()
            flickKeyboardView?.syncPreferences()
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            action.run()
        } else {
            android.os.Handler(android.os.Looper.getMainLooper()).post(action)
        }
    }

    override fun onCreate() {
        setTheme(R.style.Theme_MyASR)
        super.onCreate()
        getSharedPreferences(KeyboardHeightManager.PREFS_NAME, Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(prefsListener)

        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        ClipboardHistoryRepository.getInstance(this).onHistoryChanged = { hasNewClip ->
            if (hasNewClip) {
                clipboardSuggestionPolicy.onNewClipDiscovered(android.os.SystemClock.uptimeMillis())
            }
            checkAndRenderClipboardSuggestion()
        }

        kanaKanjiEngine = KanaKanjiEngine.getInstance(this)
        nextPhrasePredictor = NextPhrasePredictor.getInstance(this)
        kanaKanjiEngine.predictor = nextPhrasePredictor

        arbitrator.onVoiceAnchorSettled = { anchor, editedText ->
            handleVoiceAnchorSettled(anchor, editedText)
        }

        audioRecorder.onSpeechChunkCaptured = { chunk ->
            if (!isStreamingActive) {
                decodeSpeechChunkAsync(chunk)
            }
        }
        audioRecorder.onSpeechStateChanged = { isSpeaking ->
            serviceScope.launch(Dispatchers.Main) {
                isCurrentlySpeaking = isSpeaking
                if (currentState == State.RECORDING) {
                    recordingIndicator?.visibility = if (isSpeaking) View.VISIBLE else View.GONE
                    syncKeyboardState()
                    if (!arbitrator.isKeyboardComposing() && !arbitrator.isStreamingComposing()) {
                        if (isSpeaking) {
                            candidateStripView?.showEmptyStatus("音声を聞き取り中…")
                        } else if (candidateStripView?.getCandidates().isNullOrEmpty()) {
                            candidateStripView?.showEmptyStatus("話すか、キー入力してください…")
                        }
                    }
                }
            }
        }

        ModelWarmUpManager.getInstance(this).startWarmUpAsync()
    }

    override fun onEvaluateFullscreenMode(): Boolean {
        // Never allow legacy fullscreen extract mode; keep target app visible in-place
        return false
    }

    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        val root = keyboardRoot ?: return
        if (!isInputViewShown) return

        val location = IntArray(2)
        root.getLocationInWindow(location)
        val topY = location[1]

        outInsets.contentTopInsets = topY
        outInsets.visibleTopInsets = topY
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_CONTENT
    }

    override fun onCreateInputView(): View {
        val themedContext = android.view.ContextThemeWrapper(this, R.style.Theme_MyASR)
        val view = android.view.LayoutInflater.from(themedContext).inflate(R.layout.keyboard_view, null)

        keyboardRoot = view.findViewById(R.id.keyboardRoot)
        contextBarContainer = view.findViewById(R.id.contextBarContainer)
        tvContextBadge = view.findViewById(R.id.tvContextBadge)
        tvDebugSlmBadge = view.findViewById(R.id.tvDebugSlmBadge)
        tvContextContent = view.findViewById(R.id.tvContextContent)
        keyboardHeader = view.findViewById(R.id.keyboardHeader)
        voiceToggleContainer = view.findViewById(R.id.voiceToggleContainer)
        btnVoiceToggle = view.findViewById(R.id.btnVoiceToggle)
        btnHeaderDict = view.findViewById(R.id.btnHeaderDict)
        btnHeaderClipboard = view.findViewById(R.id.btnHeaderClipboard)
        candidateStripView = view.findViewById(R.id.candidateStripView)
        candidateStripView?.onEmptyStatusShown = {
            updateDebugSlmBadge(null)
        }
        flickKeyboardView = view.findViewById(R.id.flickKeyboardView)
        clipboardHistoryView = view.findViewById(R.id.clipboardHistoryView)
        clipboardHistoryView?.listener = object : ClipboardHistoryView.Listener {
            override fun onHistoryItemClicked(item: ClipboardHistoryItem) {
                pasteTextFromHistory(item.text, isSensitive = false)
                hideClipboardHistoryView()
            }

            override fun onSensitiveClipClicked(meta: SensitiveClipMetadata) {
                serviceScope.launch(Dispatchers.Main) {
                    val repo = ClipboardHistoryRepository.getInstance(this@VoiceInputMethodService)
                    val secret = kotlinx.coroutines.withContext(Dispatchers.Default) {
                        repo.consumeSensitiveClipTextForPaste(meta.id)
                    }
                    if (!secret.isNullOrEmpty()) {
                        pasteTextFromHistory(secret, isSensitive = true)
                    }
                    hideClipboardHistoryView()
                }
            }

            override fun onCloseClicked() {
                hideClipboardHistoryView()
            }
        }
        progressDecoding = view.findViewById(R.id.progressDecoding)
        recordingIndicator = view.findViewById(R.id.recordingIndicator)

        applyKeyboardDimensions()
        setupListeners()
        updateUiState(State.IDLE)
        updateClipboardButton()

        return view
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (currentState == State.RECORDING) {
            cancelRecording()
        }
        isCurrentlySpeaking = false
        restartListeningAfterDecode = false
        arbitrator.resetAll()
        hideClipboardHistoryView()
        flickKeyboardView?.syncPreferences()
        syncKeyboardState()
        candidateStripView?.showEmptyStatus(getIdleStatusPrompt())
        updateUiState(State.IDLE)
        applyKeyboardDimensions()
        updateClipboardButton()

        clipboardSuggestionPolicy.onStartInputView()
        ClipboardHistoryRepository.getInstance(this).setInputViewActive(true)
        checkAndRenderClipboardSuggestion()

        // Trigger / verify early background warm-up of all models (non-blocking) and sync user dictionary
        ModelWarmUpManager.getInstance(this).startWarmUpAsync()
        serviceScope.launch(Dispatchers.Default) {
            try {
                AsrApp.instanceOrNull?.syncAllUserDictionaries()
            } catch (e: Exception) {
                Log.w(TAG, "IME user dictionary sync error: ${e.message}")
            }
        }
        ContextWindowTrimmer.resetStickyAnchor()
        lastWarmedContextText = null
        triggerSpeculativeContextWarm(CandidateRerankJudgement.WarmTrigger.INPUT_VIEW_STARTED)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        arbitrator.settleActiveVoiceAnchorIfNeeded(editorBridge)
        hideClipboardHistoryView()
        clipboardSuggestionPolicy.onFinishInputView()
        ClipboardHistoryRepository.getInstance(this).setInputViewActive(false)
        flickKeyboardView?.dismissPopup()
        isKeyTouchActive = false
        if (currentState == State.RECORDING) {
            cancelRecording()
        }
        commitComposingText()
        updateHeaderVisibility()
    }

    private fun handleVoiceAnchorSettled(
        anchor: VoiceTouchInputArbitrator.VoiceCommitAnchor,
        editedText: String
    ) {
        val elapsedMs = (System.currentTimeMillis() - anchor.committedAtMs).coerceAtLeast(0L)
        val hasJapanese = anchor.committedText.any {
            val code = it.code
            code in 0x3040..0x30FF || code in 0x4E00..0x9FFF || code in 0xF900..0xFAFF
        } || editedText.any {
            val code = it.code
            code in 0x3040..0x30FF || code in 0x4E00..0x9FFF || code in 0xF900..0xFAFF
        }
        val lang = if (hasJapanese) AsrLanguage.JAPANESE else AsrLanguage.ENGLISH
        serviceScope.launch(Dispatchers.Default) {
            try {
                val normHint = JapaneseKanaUtils.normalizeReading(anchor.typedReadingHint)
                val readingResolver: ((String) -> String?)? = if (lang == AsrLanguage.JAPANESE && normHint.isNotEmpty()) {
                    val hintCandidates = kanaKanjiEngine.getBaseCandidates(normHint)
                    val resolver: (String) -> String? = { surface ->
                        if (hintCandidates.any { it.surface == surface || (it.surface.isNotEmpty() && surface.startsWith(it.surface)) }) {
                            normHint
                        } else {
                            null
                        }
                    }
                    resolver
                } else {
                    null
                }
                val detected = VoiceCorrectionDetector.detect(
                    beforeText = anchor.committedText,
                    afterText = editedText,
                    language = lang,
                    contextBefore = anchor.contextBefore,
                    elapsedSinceVoiceCommitMs = elapsedMs,
                    typedReadingHint = anchor.typedReadingHint,
                    readingResolver = readingResolver
                ) ?: return@launch

                val repo = AsrApp.instanceOrNull?.voiceCorrectionRepository ?: return@launch
                val revertedPair = synchronized(recentAutoAppliedCorrections) {
                    recentAutoAppliedCorrections[detected.originalSurface]
                }
                if (revertedPair != null &&
                    revertedPair.second == lang &&
                    revertedPair.first.equals(detected.correctedSurface, ignoreCase = lang == AsrLanguage.ENGLISH)
                ) {
                    repo.recordRevert(
                        language = lang,
                        originalSurface = detected.correctedSurface,
                        correctedSurface = detected.originalSurface
                    )
                    synchronized(recentAutoAppliedCorrections) {
                        recentAutoAppliedCorrections.remove(detected.originalSurface)
                    }
                    Log.d(TAG, "VoiceCorrection revert recorded: ${detected.originalSurface} -> ${detected.correctedSurface}")
                } else {
                    repo.recordCorrection(
                        language = lang,
                        originalSurface = detected.originalSurface,
                        correctedSurface = detected.correctedSurface,
                        normalizedReading = detected.normalizedReading,
                        contextTokens = detected.contextTokens
                    )
                    Log.d(TAG, "VoiceCorrection learned: ${detected.originalSurface} -> ${detected.correctedSurface} (${detected.category})")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to process voice correction anchor: ${e.message}")
            }
        }
    }

    private fun renderVoiceCorrectionSuggestions(
        suggestions: List<VoiceCorrectionSuggestion>,
        language: AsrLanguage
    ) {
        if (suggestions.isEmpty() || arbitrator.isKeyboardComposing() || arbitrator.isStreamingComposing()) {
            return
        }
        activeVoiceCorrectionChipsLanguage = language
        val candidates = suggestions.map { s ->
            ConversionCandidate(
                surface = s.correctedSurface,
                reading = s.originalSurface,
                isPrediction = true,
                source = "voice_correction_suggest",
                priority = s.score
            )
        }
        candidateStripView?.setCandidates(candidates)
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        if (currentState == State.RECORDING) {
            cancelRecording()
        }
        try {
            UserHistorySyncManager.getInstance(this).triggerOpportunisticSyncIfNeeded()
        } catch (e: Exception) {
            // Ignore if sync manager fails
        }
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        val resetTriggered = arbitrator.onExternalSelectionUpdated(
            editor = editorBridge,
            oldSelStart = oldSelStart,
            oldSelEnd = oldSelEnd,
            newSelStart = newSelStart,
            newSelEnd = newSelEnd,
            candidatesStart = candidatesStart,
            candidatesEnd = candidatesEnd,
            onResetStreamingUtterance = { resetStreamingUtteranceInRecorder() }
        )
        if (resetTriggered) {
            phase1TimerJob?.cancel()
            rerankJob?.cancel()
            isCurrentCandidatesReranked = false
            updateDebugSlmBadge(null)
            syncKeyboardState()
            candidateStripView?.showEmptyStatus(getActiveIdleStatusPrompt())
            triggerSpeculativeContextWarm(CandidateRerankJudgement.WarmTrigger.CURSOR_MOVED_EXTERNAL)
        } else {
            updateContextBar()
            if (!arbitrator.isKeyboardComposing() && !arbitrator.isStreamingComposing()) {
                triggerSpeculativeContextWarm(CandidateRerankJudgement.WarmTrigger.CURSOR_MOVED_EXTERNAL)
            }
        }
    }

    private fun getIdleStatusPrompt(): String {
        return when (flickKeyboardView?.currentMode) {
            JapaneseKanaUtils.KeyboardMode.QWERTY -> "話すか、QWERTYで英語入力"
            JapaneseKanaUtils.KeyboardMode.QWERTY_SYMBOLS,
            JapaneseKanaUtils.KeyboardMode.NUMBER -> "数字・記号を入力"
            else -> "話すか、フリックで入力"
        }
    }

    private fun getActiveIdleStatusPrompt(): String {
        return if (currentState == State.RECORDING) {
            "話すか、キー入力してください…"
        } else {
            getIdleStatusPrompt()
        }
    }

    private var lastRenderedContextText: String? = null

    private fun updateContextBar() {
        val isDebug = KeyboardHeightManager.isDebugDisplayEnabled(this)
        val container = contextBarContainer ?: return
        if (!isDebug) {
            if (container.visibility != View.GONE) {
                container.visibility = View.GONE
                applyKeyboardDimensions()
            }
            tvDebugSlmBadge?.visibility = View.GONE
            lastRenderedContextText = null
            return
        }

        if (container.visibility != View.VISIBLE) {
            container.visibility = View.VISIBLE
            applyKeyboardDimensions()
        }

        val editorInfo = currentInputEditorInfo
        val isPassword = if (editorInfo != null) {
            val variation = editorInfo.inputType and EditorInfo.TYPE_MASK_VARIATION
            variation == EditorInfo.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == EditorInfo.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                (editorInfo.inputType and EditorInfo.TYPE_MASK_CLASS) == EditorInfo.TYPE_CLASS_NUMBER &&
                (variation == EditorInfo.TYPE_NUMBER_VARIATION_PASSWORD)
        } else {
            false
        }

        val rawContext = currentInputConnection?.getTextBeforeCursor(2048, 0)?.toString() ?: ""
        if (rawContext == lastRenderedContextText) {
            return
        }
        lastRenderedContextText = rawContext

        val rawLength = rawContext.length
        if (isPassword) {
            tvContextBadge?.text = "CTX: パスワード max_ctx_len=${rawLength}"
            tvContextContent?.text = if (rawContext.isEmpty()) "(空)" else "•".repeat(rawContext.length.coerceAtMost(16))
            return
        }

        val lmContext = ContextWindowTrimmer.trimToOptimalContext(rawContext)
        val lmLength = lmContext.length
        tvContextBadge?.text = "CTX: ${lmLength}字 max_ctx_len=${rawLength}"
        if (lmLength == 0) {
            tvContextContent?.text = "(空)"
        } else {
            // Replace newlines with visual representation for single-line readability
            val sanitized = lmContext.replace("\n", "↵")
            tvContextContent?.text = sanitized
        }
    }

    private fun updateDebugSlmBadge(badge: String?) {
        val isDebug = KeyboardHeightManager.isDebugDisplayEnabled(this)
        if (isDebug && !badge.isNullOrBlank()) {
            tvDebugSlmBadge?.text = badge
            tvDebugSlmBadge?.visibility = View.VISIBLE
        } else {
            tvDebugSlmBadge?.visibility = View.GONE
        }
    }

    private fun syncKeyboardState() {
        updateContextBar()
        val hasVoiceComposing = currentState == State.RECORDING &&
            arbitrator.hasVoiceComposingForUi(isCurrentlySpeaking, audioRecorder.hasUnflushedSpeech())
        flickKeyboardView?.setComposingState(
            isComposing = arbitrator.isKeyboardComposing(),
            canUndo = arbitrator.canUndo(),
            hasVoiceComposing = hasVoiceComposing
        )
        updateHeaderVisibility()
    }

    private fun resolveCurrentAsrLanguage(): AsrLanguage {
        val mode = flickKeyboardView?.currentMode
        val isEnglishMode = mode == JapaneseKanaUtils.KeyboardMode.QWERTY ||
            mode == JapaneseKanaUtils.KeyboardMode.QWERTY_SYMBOLS ||
            mode == JapaneseKanaUtils.KeyboardMode.ALPHABET
        return AsrLanguagePolicy.resolveLanguage(
            isEnglishKeyboardMode = isEnglishMode,
            isEnglishModelAvailable = AsrApp.instanceOrNull?.asrManager?.isEnglishModelAvailable() ?: false
        )
    }

    private fun formatVoiceTextWithCorrections(
        text: String,
        textBeforeCursor: String = "",
        language: AsrLanguage = resolveCurrentAsrLanguage(),
        useSlmGate: Boolean = false
    ): com.example.myasr.context.speech.domain.VoiceCorrectionApplyResult {
        val ctx = if (textBeforeCursor.isNotEmpty()) {
            textBeforeCursor
        } else {
            currentInputConnection?.getTextBeforeCursor(500, 0)?.toString() ?: ""
        }
        val app = AsrApp.instanceOrNull
        val learnedCorrections = try {
            app?.voiceCorrectionRepository?.getActiveBoostEntries(language) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        val slmDeltaProvider: ((String, String) -> Float?)? = if (useSlmGate && app?.llmReranker?.isReady() == true) {
            { orig, corr -> app.llmReranker.scorePairDeltaNll(ctx, orig, corr) }
        } else {
            null
        }
        if (language == AsrLanguage.ENGLISH) {
            return EnglishVoicePostProcessor.formatWithCorrections(
                rawText = text,
                contextBefore = ctx,
                learnedCorrections = learnedCorrections,
                slmCoherenceDeltaProvider = slmDeltaProvider
            )
        }
        val activeEntries = try {
            app?.dictionaryRepository?.getActive() ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        val pResult = app?.contextAwareProcessor?.processWithCorrectionsAndPunctuation(
            rawText = text,
            contextBefore = ctx,
            entries = activeEntries,
            learnedCorrections = learnedCorrections,
            slmCoherenceDeltaProvider = slmDeltaProvider
        )
        return com.example.myasr.context.speech.domain.VoiceCorrectionApplyResult(
            formattedText = pResult?.formattedText ?: text,
            appliedCorrections = pResult?.appliedCorrections ?: emptyList(),
            suggestedChips = pResult?.suggestedChips ?: emptyList()
        )
    }

    private fun formatVoiceText(
        text: String,
        textBeforeCursor: String = "",
        language: AsrLanguage = resolveCurrentAsrLanguage()
    ): String {
        return formatVoiceTextWithCorrections(
            text = text,
            textBeforeCursor = textBeforeCursor,
            language = language,
            useSlmGate = false
        ).formattedText
    }

    private fun flushPreTypingSpeechIfNeeded() {
        if (currentState == State.RECORDING) {
            if (isStreamingActive) {
                if (!arbitrator.isStreamingComposing() && audioRecorder.hasUnflushedSpeech()) {
                    val snapshot = audioRecorder.getSnapshotSamples()
                    audioRecorder.resetStreamingUtterance()
                    arbitrator.resetStreamingSessionState()
                    if (snapshot.isNotEmpty()) {
                        decodeSpeechChunkAsync(snapshot)
                    }
                }
            } else {
                audioRecorder.flushPendingSpeechChunk(invokeCallback = true)
            }
        }
    }

    private fun resetStreamingUtteranceInRecorder() {
        if (currentState == State.RECORDING && isStreamingActive) {
            audioRecorder.resetStreamingUtterance()
        }
    }

    private fun openDictionaryActivity() {
        val intent = Intent(this@VoiceInputMethodService, DictionaryActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
    }

    private fun setupListeners() {
        btnVoiceToggle?.setOnClickListener {
            hideClipboardHistoryView()
            handleVoiceToggleClicked()
        }

        btnHeaderDict?.setOnClickListener {
            hideClipboardHistoryView()
            openDictionaryActivity()
        }

        btnHeaderClipboard?.setOnClickListener {
            handleClipboardClicked()
        }

        candidateStripView?.listener = object : CandidateStripView.OnCandidateClickListener {
            override fun onCandidateClick(candidate: ConversionCandidate) {
                phase1TimerJob?.cancel()
            rerankJob?.cancel()
                val prevPhrase = arbitrator.previousCommittedPhrase
                val committedSurface = candidate.surface
                val hadUnflushed = currentState == State.RECORDING && audioRecorder.hasUnflushedSpeech()

                if (candidate.source == "voice_correction_suggest") {
                    val orig = candidate.reading
                    val corr = candidate.surface
                    val ic = currentInputConnection
                    if (ic != null && orig.isNotEmpty()) {
                        val textBefore = ic.getTextBeforeCursor(160, 0)?.toString() ?: ""
                        val idx = textBefore.lastIndexOf(
                            orig,
                            ignoreCase = activeVoiceCorrectionChipsLanguage == AsrLanguage.ENGLISH
                        )
                        if (idx >= 0) {
                            val tail = textBefore.substring(idx + orig.length)
                            if (tail.length <= 32) {
                                ic.beginBatchEdit()
                                ic.deleteSurroundingText(orig.length + tail.length, 0)
                                ic.commitText(corr + tail, 1)
                                ic.endBatchEdit()
                            }
                        }
                    }
                    val lang = activeVoiceCorrectionChipsLanguage
                    serviceScope.launch(Dispatchers.Default) {
                        AsrApp.instanceOrNull?.voiceCorrectionRepository?.recordAccept(lang, orig, corr)
                    }
                    candidateStripView?.showEmptyStatus(getActiveIdleStatusPrompt())
                    return
                }

                if (candidate.source == "clipboard_sensitive") {
                    val expectedId = candidate.reading
                    serviceScope.launch(Dispatchers.Main) {
                        val repo = ClipboardHistoryRepository.getInstance(this@VoiceInputMethodService)
                        val secret = kotlinx.coroutines.withContext(Dispatchers.Default) {
                            repo.consumeSensitiveClipTextForPaste(expectedId)
                        }
                        if (!secret.isNullOrEmpty()) {
                            pasteTextFromHistory(secret, isSensitive = true)
                        }
                    }
                    candidateStripView?.showEmptyStatus(getActiveIdleStatusPrompt())
                    return
                }

                if (candidate.source == "clipboard_recent") {
                    pasteTextFromHistory(committedSurface, isSensitive = false)
                    serviceScope.launch {
                        val repo = ClipboardHistoryRepository.getInstance(this@VoiceInputMethodService)
                        val item = repo.getRecentSuggestionCandidate()
                        if (item != null) {
                            repo.markDismissedFromStrip(item.id)
                        }
                    }
                    candidateStripView?.showEmptyStatus(getActiveIdleStatusPrompt())
                    return
                }

                clipboardSuggestionPolicy.onUserTypingOrVoiceAction(android.os.SystemClock.uptimeMillis())

                if (flickKeyboardView?.currentMode == JapaneseKanaUtils.KeyboardMode.ALPHABET &&
                    candidate.source in listOf("english_typo", "english_gec", "user_english", "english_cap", "raw")
                ) {
                    val rawWord = candidate.reading
                    val replaceLength = if (candidate.source == "english_gec") {
                        val textBeforeCursor = currentInputConnection?.getTextBeforeCursor(256, 0)?.toString() ?: ""
                        val lastSentenceStart = textBeforeCursor.lastIndexOfAny(charArrayOf('.', '!', '?', '\n'))
                        val clause = if (lastSentenceStart >= 0) textBeforeCursor.substring(lastSentenceStart + 1) else textBeforeCursor
                        clause.length
                    } else {
                        rawWord.length
                    }
                    if (replaceLength > 0 || committedSurface.isNotEmpty()) {
                        arbitrator.onEnglishCandidateSelected(
                            editor = editorBridge,
                            replaceLength = replaceLength,
                            candidateSurface = committedSurface,
                            autoSpaceEnabled = KeyboardHeightManager.isEnglishAutoSpaceEnabled(this@VoiceInputMethodService),
                            bypassAutoAssist = shouldBypassEnglishAutoAssist(),
                            onFlushPreTypingSpeech = { flushPreTypingSpeechIfNeeded() },
                            onResetStreamingUtterance = { resetStreamingUtteranceInRecorder() }
                        )
                        syncKeyboardState()
                    }
                    candidateStripView?.showEmptyStatus(getActiveIdleStatusPrompt())
                    return
                }

                val isPartialCommit = arbitrator.onCandidateSelected(
                    editor = editorBridge,
                    candidateSurface = committedSurface,
                    isPrediction = candidate.isPrediction,
                    hasUnflushedSpeech = hadUnflushed,
                    candidateReading = candidate.reading,
                    onFlushPreTypingSpeech = { flushPreTypingSpeechIfNeeded() },
                    onResetStreamingUtterance = { resetStreamingUtteranceInRecorder() },
                    formatStreamingFn = { formatVoiceText(it) }
                )
                audioRecorder.notifyUserTyping(350L)

                if (!candidate.isPrediction) {
                    kanaKanjiEngine.commitCandidate(candidate)
                }
                if (prevPhrase.isNotEmpty()) {
                    nextPhrasePredictor.recordTransition(prevPhrase, committedSurface, currentReading = candidate.reading)
                }

                isCurrentCandidatesReranked = false
                syncKeyboardState()

                if (isPartialCommit) {
                    updateCandidates()
                } else if (candidate.source == "futo_swipe") {
                    candidateStripView?.showEmptyStatus(getActiveIdleStatusPrompt())
                    triggerSpeculativeContextWarm(CandidateRerankJudgement.WarmTrigger.TEXT_COMMITTED)
                } else {
                    showNextPhrasePredictions(arbitrator.previousCommittedPhrase)
                    triggerSpeculativeContextWarm(CandidateRerankJudgement.WarmTrigger.TEXT_COMMITTED)
                }
            }
        }

        flickKeyboardView?.listener = object : FlickKeyboardView.OnKeyboardActionListener {
            override fun onTextChar(text: String) {
                clipboardSuggestionPolicy.onUserTypingOrVoiceAction(android.os.SystemClock.uptimeMillis())
                if (flickKeyboardView?.currentMode == JapaneseKanaUtils.KeyboardMode.KANA) {
                    arbitrator.onTypeKana(
                        editor = editorBridge,
                        text = text,
                        onFlushPreTypingSpeech = { flushPreTypingSpeechIfNeeded() },
                        onResetStreamingUtterance = { resetStreamingUtteranceInRecorder() }
                    )
                    audioRecorder.notifyUserTyping(350L)
                    syncKeyboardState()
                    updateCandidates()
                } else {
                    phase1TimerJob?.cancel()
            rerankJob?.cancel()
                    isCurrentCandidatesReranked = false
                    val prevPhrase = arbitrator.previousCommittedPhrase
                    val hadKeyboardComposing = arbitrator.isKeyboardComposing()
                    val readingBefore = arbitrator.composingText.toString()

                    arbitrator.onTypeDirectChar(
                        editor = editorBridge,
                        text = text,
                        autoSpaceEnabled = KeyboardHeightManager.isEnglishAutoSpaceEnabled(this@VoiceInputMethodService),
                        bypassAutoAssist = shouldBypassEnglishAutoAssist(),
                        onFlushPreTypingSpeech = { flushPreTypingSpeechIfNeeded() },
                        onResetStreamingUtterance = { resetStreamingUtteranceInRecorder() }
                    )
                    audioRecorder.notifyUserTyping(350L)

                    if (hadKeyboardComposing && prevPhrase.isNotEmpty()) {
                        nextPhrasePredictor.recordTransition(prevPhrase, readingBefore)
                    }
                    syncKeyboardState()

                    // In QWERTY (ALPHABET) mode, trigger English Spelling Correction & Typo Suggestions
                    if (flickKeyboardView?.currentMode == JapaneseKanaUtils.KeyboardMode.ALPHABET) {
                        updateEnglishTypoAndSpellingCandidates(justTypedChar = text)
                    } else {
                        candidateStripView?.showEmptyStatus(getActiveIdleStatusPrompt())
                    }
                }
            }

            override fun onModifierPressed(direction: JapaneseKanaUtils.FlickDirection) {
                clipboardSuggestionPolicy.onUserTypingOrVoiceAction(android.os.SystemClock.uptimeMillis())
                audioRecorder.notifyUserTyping(350L)
                val wasStreaming = arbitrator.isStreamingComposing()
                val modifiedKeyboardComposing = arbitrator.onModifierPressed(
                    editor = editorBridge,
                    direction = direction,
                    onResetStreamingUtterance = { resetStreamingUtteranceInRecorder() }
                )
                syncKeyboardState()
                if (modifiedKeyboardComposing) {
                    updateCandidates()
                } else if (wasStreaming && !arbitrator.isStreamingComposing()) {
                    showNextPhrasePredictions(arbitrator.previousCommittedPhrase)
                }
            }

            override fun onDeletePressed(count: Int) {
                clipboardSuggestionPolicy.onUserTypingOrVoiceAction(android.os.SystemClock.uptimeMillis())
                audioRecorder.notifyUserTyping(350L)
                val outcome = arbitrator.onDeletePressed(
                    editor = editorBridge,
                    onDiscardUnflushedSpeech = {
                        if (currentState == State.RECORDING) {
                            audioRecorder.discardCurrentSpeech()
                        }
                    },
                    onResetStreamingUtterance = { resetStreamingUtteranceInRecorder() },
                    count = count
                )
                syncKeyboardState()
                when (outcome) {
                    VoiceTouchInputArbitrator.DeleteOutcome.DELETED_FROM_KEYBOARD_COMPOSING_STILL_ACTIVE -> {
                        updateCandidates()
                    }
                    VoiceTouchInputArbitrator.DeleteOutcome.DELETED_FROM_STREAMING_COMPOSING -> {
                        if (arbitrator.previousCommittedPhrase.isNotEmpty()) {
                            showNextPhrasePredictions(arbitrator.previousCommittedPhrase)
                        } else {
                            candidateStripView?.showEmptyStatus(getActiveIdleStatusPrompt())
                        }
                    }
                    VoiceTouchInputArbitrator.DeleteOutcome.DELETED_SURROUNDING_TEXT -> {
                        phase1TimerJob?.cancel()
            rerankJob?.cancel()
                        englishCandidateJob?.cancel()
                        isCurrentCandidatesReranked = false
                        if (count <= 1 && flickKeyboardView?.currentMode == JapaneseKanaUtils.KeyboardMode.ALPHABET) {
                            updateEnglishTypoAndSpellingCandidates()
                        } else {
                            candidateStripView?.showEmptyStatus(getActiveIdleStatusPrompt())
                        }
                    }
                    VoiceTouchInputArbitrator.DeleteOutcome.DELETED_FROM_KEYBOARD_COMPOSING_NOW_EMPTY,
                    VoiceTouchInputArbitrator.DeleteOutcome.DELETED_ENTIRE_SWIPE_WORD -> {
                        phase1TimerJob?.cancel()
            rerankJob?.cancel()
                        englishCandidateJob?.cancel()
                        isCurrentCandidatesReranked = false
                        candidateStripView?.showEmptyStatus(getActiveIdleStatusPrompt())
                    }
                }
            }

            override fun onSpacePressed() {
                clipboardSuggestionPolicy.onUserTypingOrVoiceAction(android.os.SystemClock.uptimeMillis())
                val hadUnflushed = currentState == State.RECORDING && audioRecorder.hasUnflushedSpeech()
                val currentMode = flickKeyboardView?.currentMode
                val spaceChar = if (currentMode == JapaneseKanaUtils.KeyboardMode.KANA) "　" else " "
                val isEnglishMode = currentMode == JapaneseKanaUtils.KeyboardMode.QWERTY ||
                    currentMode == JapaneseKanaUtils.KeyboardMode.QWERTY_SYMBOLS
                val autoPeriodEnabled = isEnglishMode &&
                    KeyboardHeightManager.isEnglishAutoPeriodEnabled(this@VoiceInputMethodService)

                val outcome = arbitrator.onSpacePressed(
                    editor = editorBridge,
                    spaceCharWhenIdle = spaceChar,
                    hasUnflushedSpeech = hadUnflushed,
                    autoPeriodEnabled = autoPeriodEnabled,
                    bypassAutoAssist = shouldBypassEnglishAutoAssist(),
                    onFlushPreTypingSpeech = { flushPreTypingSpeechIfNeeded() },
                    onResetStreamingUtterance = { resetStreamingUtteranceInRecorder() },
                    formatStreamingFn = { formatVoiceText(it) }
                )
                audioRecorder.notifyUserTyping(350L)
                phase1TimerJob?.cancel()
            rerankJob?.cancel()
                isCurrentCandidatesReranked = false
                syncKeyboardState()

                when (outcome) {
                    VoiceTouchInputArbitrator.SpaceOutcome.CONVERTED_DOUBLE_SPACE_TO_PERIOD -> {
                        candidateStripView?.showEmptyStatus(getActiveIdleStatusPrompt())
                    }
                    VoiceTouchInputArbitrator.SpaceOutcome.COMMITTED_KEYBOARD_AND_SPACE,
                    VoiceTouchInputArbitrator.SpaceOutcome.COMMITTED_STREAMING_AND_SPACE,
                    VoiceTouchInputArbitrator.SpaceOutcome.COMMITTED_IDLE_SPACE -> {
                        if (flickKeyboardView?.currentMode == JapaneseKanaUtils.KeyboardMode.ALPHABET) {
                            triggerEnglishSentenceContextCorrection()
                        } else {
                            candidateStripView?.showEmptyStatus(getActiveIdleStatusPrompt())
                        }
                    }
                }
            }

            override fun onEnterPressed() {
                clipboardSuggestionPolicy.onUserTypingOrVoiceAction(android.os.SystemClock.uptimeMillis())
                val prevPhrase = arbitrator.previousCommittedPhrase
                val hadUnflushed = currentState == State.RECORDING && audioRecorder.hasUnflushedSpeech()

                val outcome = arbitrator.onEnterPressed(
                    editor = editorBridge,
                    hasUnflushedSpeech = hadUnflushed,
                    onFlushPreTypingSpeech = { flushPreTypingSpeechIfNeeded() },
                    onResetStreamingUtterance = { resetStreamingUtteranceInRecorder() },
                    formatStreamingFn = { formatVoiceText(it) }
                )
                audioRecorder.notifyUserTyping(350L)
                phase1TimerJob?.cancel()
            rerankJob?.cancel()
                LlmReranker.getInstance(this@VoiceInputMethodService).requestAbortScoring()
                isCurrentCandidatesReranked = false
                syncKeyboardState()

                when (outcome) {
                    VoiceTouchInputArbitrator.EnterOutcome.COMMITTED_KEYBOARD_COMPOSING -> {
                        val committed = arbitrator.lastCommittedSurface.ifEmpty { arbitrator.previousCommittedPhrase }
                        if (prevPhrase.isNotEmpty() && committed.isNotEmpty()) {
                            nextPhrasePredictor.recordTransition(prevPhrase, committed)
                        }
                        showNextPhrasePredictions(arbitrator.previousCommittedPhrase)
                    }
                    VoiceTouchInputArbitrator.EnterOutcome.COMMITTED_STREAMING_COMPOSING -> {
                        if (arbitrator.previousCommittedPhrase.isNotEmpty()) {
                            showNextPhrasePredictions(arbitrator.previousCommittedPhrase)
                        } else {
                            candidateStripView?.showEmptyStatus(getActiveIdleStatusPrompt())
                        }
                    }
                    VoiceTouchInputArbitrator.EnterOutcome.FLUSHED_OR_CONFIRMED_PENDING_SPEECH -> {
                        candidateStripView?.showEmptyStatus("音声を確定中…")
                    }
                    VoiceTouchInputArbitrator.EnterOutcome.PERFORM_EDITOR_RETURN_ACTION -> {
                        handleReturnClicked()
                        candidateStripView?.showEmptyStatus(getActiveIdleStatusPrompt())
                    }
                }
            }

            override fun onCursorLeft() {
                clipboardSuggestionPolicy.onUserTypingOrVoiceAction(android.os.SystemClock.uptimeMillis())
                val hadUnflushed = currentState == State.RECORDING && audioRecorder.hasUnflushedSpeech()
                arbitrator.onBeforeNavigationOrAction(
                    editor = editorBridge,
                    isCursorMove = true,
                    hasUnflushedSpeech = hadUnflushed,
                    onFlushPreTypingSpeech = { flushPreTypingSpeechIfNeeded() },
                    onResetStreamingUtterance = { resetStreamingUtteranceInRecorder() },
                    formatStreamingFn = { formatVoiceText(it) }
                )
                audioRecorder.notifyUserTyping(350L)
                phase1TimerJob?.cancel()
            rerankJob?.cancel()
                LlmReranker.getInstance(this@VoiceInputMethodService).requestAbortScoring()
                isCurrentCandidatesReranked = false
                syncKeyboardState()
                candidateStripView?.showEmptyStatus(getActiveIdleStatusPrompt())
                sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_LEFT)
            }

            override fun onCursorRight() {
                clipboardSuggestionPolicy.onUserTypingOrVoiceAction(android.os.SystemClock.uptimeMillis())
                val hadUnflushed = currentState == State.RECORDING && audioRecorder.hasUnflushedSpeech()
                arbitrator.onBeforeNavigationOrAction(
                    editor = editorBridge,
                    isCursorMove = true,
                    hasUnflushedSpeech = hadUnflushed,
                    onFlushPreTypingSpeech = { flushPreTypingSpeechIfNeeded() },
                    onResetStreamingUtterance = { resetStreamingUtteranceInRecorder() },
                    formatStreamingFn = { formatVoiceText(it) }
                )
                audioRecorder.notifyUserTyping(350L)
                phase1TimerJob?.cancel()
            rerankJob?.cancel()
                LlmReranker.getInstance(this@VoiceInputMethodService).requestAbortScoring()
                isCurrentCandidatesReranked = false
                syncKeyboardState()
                candidateStripView?.showEmptyStatus(getActiveIdleStatusPrompt())
                sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_RIGHT)
            }

            override fun onUndoPressed() {
                clipboardSuggestionPolicy.onUserTypingOrVoiceAction(android.os.SystemClock.uptimeMillis())
                audioRecorder.notifyUserTyping(350L)
                if (arbitrator.onUndoPressed(editorBridge)) {
                    syncKeyboardState()
                    updateCandidates()
                }
            }

            override fun onGlobePressed() {
                handleGlobeClicked()
            }

            override fun onDictPressed() {
                openDictionaryActivity()
            }

            override fun onSwipeInput(trajectory: List<FutoSwipeEngine.SwipePoint>) {
                if (trajectory.size < 2) return
                clipboardSuggestionPolicy.onUserTypingOrVoiceAction(android.os.SystemClock.uptimeMillis())
                audioRecorder.notifyUserTyping(500L)
                val engine = FutoSwipeEngine.getInstance(this@VoiceInputMethodService)

                serviceScope.launch(Dispatchers.Default) {
                    if (!engine.isReady()) {
                        engine.ensureInitialized()
                    }
                    if (!engine.isReady()) return@launch
                    val textBeforeCursor = withContext(Dispatchers.Main) {
                        currentInputConnection?.getTextBeforeCursor(256, 0)?.toString() ?: ""
                    }
                    val prevTokens = textBeforeCursor.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                    val candidates = engine.decode(trajectory, prevTokens, beamWidth = 100, maxCandidates = 5)
                    if (candidates.isNotEmpty()) {
                        val isShiftActive = withContext(Dispatchers.Main) {
                            flickKeyboardView?.shiftState != FlickKeyboardView.ShiftState.OFF
                        }
                        val fieldPolicy = EnglishCapitalizationAnalyzer.resolveFieldPolicy(currentInputEditorInfo, textBeforeCursor)
                        val bosProb = if (isShiftActive || fieldPolicy == InputFieldCapPolicy.BYPASS) {
                            0.0f
                        } else {
                            SentenceBoundaryDetector.computeBosProbability("", textBeforeCursor, fieldPolicy)
                        }
                        val rankedSwipe = ArrayList<BayesianCaseRanker.RankedCaseCandidate>()
                        for ((idx, cand) in candidates.withIndex()) {
                            val swipeObsPenalty = idx * 0.08f
                            rankedSwipe.addAll(
                                BayesianCaseRanker.generateCaseVariants(
                                    baseWord = cand,
                                    bosProb = bosProb,
                                    policy = fieldPolicy,
                                    source = "futo_swipe",
                                    obsPenalty = swipeObsPenalty,
                                    isRawWord = (idx == 0)
                                )
                            )
                        }
                        val stripCandidates = BayesianCaseRanker.toConversionCandidates(
                            ranked = rankedSwipe,
                            reading = candidates[0],
                            maxCandidates = 5
                        )
                        val top1 = stripCandidates.firstOrNull()?.surface ?: candidates[0]
                        withContext(Dispatchers.Main) {
                            arbitrator.onCommitSwipeWord(
                                editor = editorBridge,
                                word = top1,
                                autoSpaceEnabled = KeyboardHeightManager.isEnglishAutoSpaceEnabled(this@VoiceInputMethodService),
                                bypassAutoAssist = shouldBypassEnglishAutoAssist(),
                                onFlushPreTypingSpeech = { flushPreTypingSpeechIfNeeded() },
                                onResetStreamingUtterance = { resetStreamingUtteranceInRecorder() }
                            )
                            syncKeyboardState()

                            updateDebugSlmBadge("FUTO")
                            candidateStripView?.setCandidates(stripCandidates)
                        }
                    }
                }
            }

            override fun onKeyTouchDown() {
                if (!isKeyTouchActive) {
                    isKeyTouchActive = true
                    updateHeaderVisibility()
                }
                triggerSpeculativeContextWarm(CandidateRerankJudgement.WarmTrigger.KEY_TOUCH_DOWN)
            }

            override fun onKeyTouchUpOrCancel() {
                if (isKeyTouchActive) {
                    isKeyTouchActive = false
                    updateHeaderVisibility()
                }
            }

            override fun onModeChanged(mode: JapaneseKanaUtils.KeyboardMode) {
                val hadUnflushed = currentState == State.RECORDING && audioRecorder.hasUnflushedSpeech()
                arbitrator.onBeforeNavigationOrAction(
                    editor = editorBridge,
                    isCursorMove = false,
                    hasUnflushedSpeech = hadUnflushed,
                    onFlushPreTypingSpeech = { flushPreTypingSpeechIfNeeded() },
                    onResetStreamingUtterance = { resetStreamingUtteranceInRecorder() },
                    formatStreamingFn = { formatVoiceText(it) }
                )
                audioRecorder.notifyUserTyping(350L)
                phase1TimerJob?.cancel()
                rerankJob?.cancel()
                LlmReranker.getInstance(this@VoiceInputMethodService).requestAbortScoring()
                isCurrentCandidatesReranked = false
                syncKeyboardState()
                candidateStripView?.showEmptyStatus(getActiveIdleStatusPrompt())
            }
        }
    }

    private fun updateCandidates() {
        if (!arbitrator.isKeyboardComposing()) {
            phase1TimerJob?.cancel()
            rerankJob?.cancel()
            speculativePrewarmJob?.cancel()
            LlmReranker.getInstance(this).requestAbortScoring()
            isCurrentCandidatesReranked = false
            updateDebugSlmBadge(null)
            candidateStripView?.showEmptyStatus(getActiveIdleStatusPrompt())
            return
        }

        val reading = arbitrator.composingText.toString()
        val textBeforeCursor = currentInputConnection?.getTextBeforeCursor(256, 0)?.toString() ?: ""

        val nowUptime = android.os.SystemClock.uptimeMillis()
        val ikiMs = if (lastKeystrokeUptimeMs > 0L) nowUptime - lastKeystrokeUptimeMs else 999L
        lastKeystrokeUptimeMs = nowUptime

        // Signal any ongoing C++ score/decode pass to abort immediately (Policy ⑥)
        LlmReranker.getInstance(this).requestAbortScoring()
        phase1TimerJob?.cancel()
        rerankJob?.cancel()
        speculativePrewarmJob?.cancel()
        isCurrentCandidatesReranked = false
        updateDebugSlmBadge(null)

        val baseCandidates = kanaKanjiEngine.getBaseCandidates(reading, contextPrefix = textBeforeCursor)

        val isRerankEnabled = KeyboardHeightManager.isSlmRerankEnabled(this)
        val isSlmReady = LlmReranker.getInstance(this).isReady()
        val execDecision = CandidateRerankJudgement.judgeExecution(
            CandidateRerankJudgement.ExecutionInput(
                isRerankEnabled = isRerankEnabled,
                candidateCount = baseCandidates.size,
                isSlmReady = isSlmReady,
                interKeyIntervalMs = ikiMs,
                readingLength = reading.length
            )
        )
        val canRerank = execDecision.shouldRerank

        // 1. FAST ZERO-JITTER PATH (< 0.1ms synchronous on Main Thread, Policies ① & ②):
        var renderedCandidates = baseCandidates
        var isFullyCompleteFromFastPath = false
        if (canRerank) {
            val fastResult = kanaKanjiEngine.tryFastRerankCandidatesWithStatus(
                contextPrefix = textBeforeCursor,
                baseCandidates = baseCandidates,
                currentShownTop1 = null,
                targetInputLength = reading.length
            )
            if (fastResult != null && fastResult.candidates.isNotEmpty()) {
                renderedCandidates = fastResult.candidates
                isFullyCompleteFromFastPath = fastResult.isFullyComplete
                if (isFullyCompleteFromFastPath) {
                    isCurrentCandidatesReranked = true
                    val engineType = KeyboardHeightManager.getRerankEngineType(this)
                    updateDebugSlmBadge(engineType.badgeLabel)
                }
            }
        }

        val isAntiJitterUiEnabled = KeyboardHeightManager.isAntiJitterCandidateUiEnabled(this)

        // If Fast-Path is 100% complete or reranking is disabled, render once immediately and return
        if (isFullyCompleteFromFastPath || !canRerank) {
            initialRenderedTop1 = renderedCandidates.firstOrNull()?.surface
            firstRenderUptimeMs = android.os.SystemClock.uptimeMillis()
            candidateStripView?.setCandidatesSinglePass(
                renderedCandidates,
                highlightFirstAsAi = isAntiJitterUiEnabled && isFullyCompleteFromFastPath
            )
            if (canRerank) {
                scheduleSpeculativeNextPhrasePrewarm(textBeforeCursor, initialRenderedTop1)
            }
            return
        }

        if (isAntiJitterUiEnabled) {
            // === NEW ANTI-JITTER PIPELINE (UI-Idea 1 + UI-Idea 2 + UI-Idea 8) ===
            val holdBudgetMs = if (ikiMs < CandidateRerankJudgement.RAPID_TYPING_IKI_THRESHOLD_MS) {
                CandidateRerankJudgement.RAPID_TYPING_HOLD_MS
            } else {
                CandidateRerankJudgement.MICRO_WAIT_HOLD_MS
            }

            var isPhase1Rendered = false
            var phase1DisplayCandidates: List<ConversionCandidate> = emptyList()
            val heroReservedCharCount = baseCandidates
                .filter { !it.isPrediction && it.reading.length == reading.length }
                .maxOfOrNull { it.surface.length }
                ?.coerceIn(2, 4) ?: 2

            phase1TimerJob = serviceScope.launch(Dispatchers.Main) {
                delay(holdBudgetMs)
                if (arbitrator.composingText.toString() == reading && !isCurrentCandidatesReranked) {
                    isPhase1Rendered = true
                    initialRenderedTop1 = renderedCandidates.firstOrNull()?.surface
                    firstRenderUptimeMs = android.os.SystemClock.uptimeMillis()
                    phase1DisplayCandidates = candidateStripView?.renderPhase1WithHeroPlaceholder(
                        baseCandidates = renderedCandidates,
                        reservedHeroCharCount = heroReservedCharCount
                    ) ?: CandidateStripView.filterAndDeduplicateDisplayCandidates(renderedCandidates)
                }
            }

            rerankJob = serviceScope.launch(Dispatchers.Default) {
                try {
                    val (reranked, slmUsed) = withTimeout(2500L) {
                        kanaKanjiEngine.rerankCandidatesWithStatus(
                            contextPrefix = textBeforeCursor,
                            baseCandidates = baseCandidates,
                            currentShownTop1 = null,
                            targetInputLength = reading.length
                        )
                    }
                    if (arbitrator.composingText.toString() == reading && reranked.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            phase1TimerJob?.cancel()
                            if (arbitrator.composingText.toString() != reading) return@withContext

                            isCurrentCandidatesReranked = true
                            val engineType = KeyboardHeightManager.getRerankEngineType(this@VoiceInputMethodService)
                            updateDebugSlmBadge(if (slmUsed) engineType.badgeLabel else null)

                            val filteredReranked = CandidateStripView.filterAndDeduplicateDisplayCandidates(reranked)
                                .ifEmpty { reranked }

                            val applyUpdate: (Boolean) -> Unit = { phase1Shown ->
                                val frozenSource = if (phase1Shown && phase1DisplayCandidates.isNotEmpty()) {
                                    phase1DisplayCandidates
                                } else {
                                    CandidateStripView.filterAndDeduplicateDisplayCandidates(renderedCandidates)
                                        .ifEmpty { renderedCandidates }
                                }
                                val visibleCount = if (phase1Shown) {
                                    candidateStripView?.computeVisibleCandidateCount(excludeHeroSlot = true)
                                        ?: CandidateRerankJudgement.DEFAULT_VISIBLE_SLOT_FALLBACK
                                } else {
                                    0
                                }
                                val decision = CandidateRerankJudgement.judgeViewportAwareOrdering(
                                    CandidateRerankJudgement.ViewportAwareOrderingInput(
                                        baseCandidates = frozenSource,
                                        rerankedCandidates = filteredReranked,
                                        isPhase1AlreadyRendered = phase1Shown,
                                        hasReservedHeroSlot = phase1Shown,
                                        frozenVisibleCount = visibleCount,
                                        isUserTouchingStrip = candidateStripView?.isUserInteracting == true
                                    )
                                )
                                when (decision.strategy) {
                                    CandidateRerankJudgement.UiRenderStrategy.DIRECT_SINGLE_PASS_SLM -> {
                                        initialRenderedTop1 = decision.finalCandidates.firstOrNull()?.surface
                                        firstRenderUptimeMs = android.os.SystemClock.uptimeMillis()
                                        candidateStripView?.setCandidatesSinglePass(
                                            decision.finalCandidates,
                                            highlightFirstAsAi = slmUsed
                                        )
                                    }
                                    CandidateRerankJudgement.UiRenderStrategy.PHASE2_POPULATE_HERO_AND_RERANK_OFFSCREEN_TAIL -> {
                                        candidateStripView?.applyPhase2NonDisruptiveUpdate(decision)
                                    }
                                    CandidateRerankJudgement.UiRenderStrategy.DEFER_WHILE_USER_TOUCHING -> {
                                        candidateStripView?.deferPhase2UpdateUntilTouchRelease {
                                            if (arbitrator.composingText.toString() == reading) {
                                                val newVisible = candidateStripView?.computeVisibleCandidateCount(excludeHeroSlot = true)
                                                    ?: CandidateRerankJudgement.DEFAULT_VISIBLE_SLOT_FALLBACK
                                                val deferredDecision = CandidateRerankJudgement.judgeViewportAwareOrdering(
                                                    CandidateRerankJudgement.ViewportAwareOrderingInput(
                                                        baseCandidates = frozenSource,
                                                        rerankedCandidates = filteredReranked,
                                                        isPhase1AlreadyRendered = true,
                                                        hasReservedHeroSlot = true,
                                                        frozenVisibleCount = newVisible,
                                                        isUserTouchingStrip = false
                                                    )
                                                )
                                                candidateStripView?.applyPhase2NonDisruptiveUpdate(deferredDecision)
                                            }
                                        }
                                    }
                                    else -> {}
                                }
                            }

                            applyUpdate(isPhase1Rendered)
                            scheduleSpeculativeNextPhrasePrewarm(
                                textBeforeCursor,
                                renderedCandidates.firstOrNull()?.surface ?: initialRenderedTop1
                            )
                        }
                    }
                } catch (e: TimeoutCancellationException) {
                    Log.w(TAG, "KanaKanji/SLM rerank timed out (>2500ms), fail-safe preserving base candidates")
                } catch (e: Exception) {
                    Log.w(TAG, "KanaKanji/SLM rerank error: ${e.message}")
                }
            }
            return
        }

        // === LEGACY PIPELINE (Anti-Jitter UI OFF) ===
        initialRenderedTop1 = renderedCandidates.firstOrNull()?.surface
        firstRenderUptimeMs = android.os.SystemClock.uptimeMillis()
        candidateStripView?.setCandidates(renderedCandidates)

        val debounceMs = execDecision.debounceMs
        val currentTop1ToPreserve = initialRenderedTop1

        rerankJob = serviceScope.launch(Dispatchers.Default) {
            try {
                delay(debounceMs)
                val (reranked, slmUsed) = withTimeout(2500L) {
                    kanaKanjiEngine.rerankCandidatesWithStatus(
                        contextPrefix = textBeforeCursor,
                        baseCandidates = baseCandidates,
                        currentShownTop1 = currentTop1ToPreserve,
                        targetInputLength = reading.length
                    )
                }
                if (arbitrator.composingText.toString() == reading && reranked.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        if (arbitrator.composingText.toString() == reading) {
                            val elapsedFromFirstRenderMs = android.os.SystemClock.uptimeMillis() - firstRenderUptimeMs
                            val isUserTouchingStrip = candidateStripView?.isUserInteracting == true

                            // Policy ⑤: Tap-Safe Window Protection via CandidateRerankJudgement
                            val tapSafeDecision = CandidateRerankJudgement.judgeTapSafeOrdering(
                                CandidateRerankJudgement.TapSafeOrderingInput(
                                    baseCandidates = baseCandidates,
                                    rerankedCandidates = reranked,
                                    initialRenderedTop1 = currentTop1ToPreserve,
                                    reading = reading,
                                    elapsedFromFirstRenderMs = elapsedFromFirstRenderMs,
                                    isUserTouchingStrip = isUserTouchingStrip
                                )
                            )

                            isCurrentCandidatesReranked = true
                            val engineType = KeyboardHeightManager.getRerankEngineType(this@VoiceInputMethodService)
                            val debugBadge = if (slmUsed) engineType.badgeLabel else null
                            updateDebugSlmBadge(debugBadge)
                            candidateStripView?.setCandidates(tapSafeDecision.finalCandidates)
                            scheduleSpeculativeNextPhrasePrewarm(
                                textBeforeCursor,
                                tapSafeDecision.finalCandidates.firstOrNull()?.surface
                            )
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                Log.w(TAG, "KanaKanji/SLM rerank timed out (>2500ms), fail-safe preserving base candidates")
            } catch (e: Exception) {
                Log.w(TAG, "KanaKanji/SLM rerank error: ${e.message}")
            }
        }
    }

    private fun scheduleSpeculativeNextPhrasePrewarm(contextBeforeCursor: String, top1Surface: String?) {
        val top1 = top1Surface?.trim().orEmpty()
        if (top1.isEmpty()) return
        val slm = LlmReranker.getInstance(this)
        val decision = CandidateRerankJudgement.judgeSpeculativeWarm(
            CandidateRerankJudgement.SpeculativeWarmInput(
                isRerankEnabled = KeyboardHeightManager.isSlmRerankEnabled(this),
                isSlmReady = slm.isReady(),
                isAlreadyComposing = true,
                trigger = CandidateRerankJudgement.WarmTrigger.COMPOSING_TOP1_STABLE
            )
        )
        if (!decision.shouldWarm) return

        val expectedReading = arbitrator.composingText.toString()
        speculativePrewarmJob?.cancel()
        speculativePrewarmJob = serviceScope.launch(Dispatchers.Default) {
            delay(120L)
            if (isActive && arbitrator.isKeyboardComposing() && arbitrator.composingText.toString() == expectedReading) {
                slm.speculativePrepareNextPhrases(contextBeforeCursor + top1, maxCandidates = 3, maxTokens = 5)
            }
        }
    }

    private fun triggerSpeculativeContextWarm(
        trigger: CandidateRerankJudgement.WarmTrigger,
        rawContext: String? = null
    ) {
        val slm = LlmReranker.getInstance(this)
        val isEnabled = KeyboardHeightManager.isSlmRerankEnabled(this)
        val isComposing = arbitrator.isKeyboardComposing() || arbitrator.isStreamingComposing()
        val decision = CandidateRerankJudgement.judgeSpeculativeWarm(
            CandidateRerankJudgement.SpeculativeWarmInput(
                isRerankEnabled = isEnabled,
                isSlmReady = slm.isReady(),
                isAlreadyComposing = isComposing,
                trigger = trigger
            )
        )
        if (!decision.shouldWarm) return

        val ctxText = rawContext ?: (currentInputConnection?.getTextBeforeCursor(250, 0)?.toString() ?: "")
        val trimmed = ContextWindowTrimmer.trimToOptimalContextSticky(ctxText)
        if (trimmed == lastWarmedContextText) return

        warmContextJob?.cancel()
        warmContextJob = serviceScope.launch(Dispatchers.Default) {
            if (slm.warmContext(trimmed)) {
                lastWarmedContextText = trimmed
            }
        }
    }

    private fun commitComposingText() {
        phase1TimerJob?.cancel()
            rerankJob?.cancel()
        LlmReranker.getInstance(this).requestAbortScoring()
        isCurrentCandidatesReranked = false
        updateDebugSlmBadge(null)
        if (arbitrator.isStreamingComposing()) {
            arbitrator.commitActiveStreamingComposing(
                editor = editorBridge,
                onResetAudioUtterance = { resetStreamingUtteranceInRecorder() },
                formatFn = { formatVoiceText(it) }
            )
            syncKeyboardState()
            triggerSpeculativeContextWarm(CandidateRerankJudgement.WarmTrigger.TEXT_COMMITTED)
        }
        if (arbitrator.isKeyboardComposing()) {
            val prevPhrase = arbitrator.previousCommittedPhrase
            val reading = arbitrator.commitKeyboardComposing(editorBridge) ?: ""
            if (prevPhrase.isNotEmpty() && reading.isNotEmpty()) {
                nextPhrasePredictor.recordTransition(prevPhrase, reading)
            }
            syncKeyboardState()
            showNextPhrasePredictions(arbitrator.previousCommittedPhrase)
        }
    }

    private fun decodeSpeechChunkAsync(samples: FloatArray) {
        if (samples.isEmpty()) return
        val decodeId = arbitrator.onSpeechChunkCapturedForDecode()
        serviceScope.launch(Dispatchers.Default) {
            asrDecodingMutex.withLock {
                val (textBeforeCursor, asrLanguage) = withContext(Dispatchers.Main) {
                    progressDecoding?.visibility = View.VISIBLE
                    syncKeyboardState()
                    Pair(
                        currentInputConnection?.getTextBeforeCursor(500, 0)?.toString() ?: "",
                        resolveCurrentAsrLanguage()
                    )
                }
                if (CandidateRerankJudgement.judgeSpeculativeWarm(
                        CandidateRerankJudgement.SpeculativeWarmInput(
                            isRerankEnabled = KeyboardHeightManager.isSlmRerankEnabled(this@VoiceInputMethodService),
                            isSlmReady = LlmReranker.getInstance(this@VoiceInputMethodService).isReady(),
                            isAlreadyComposing = false,
                            trigger = CandidateRerankJudgement.WarmTrigger.VOICE_CHUNK_DECODING
                        )
                    ).shouldWarm
                ) {
                    launch(Dispatchers.Default) {
                        val trimmed = ContextWindowTrimmer.trimToOptimalContextSticky(textBeforeCursor)
                        if (trimmed != lastWarmedContextText && LlmReranker.getInstance(this@VoiceInputMethodService).warmContext(trimmed)) {
                            lastWarmedContextText = trimmed
                        }
                    }
                }
                var recognizedText = ""
                var deletePreceding = 0
                var suggestedChips: List<VoiceCorrectionSuggestion> = emptyList()
                var appliedCorrections: List<VoiceCorrectionSuggestion> = emptyList()
                var error: String? = null
                try {
                    val app = AsrApp.instanceOrNull
                    if (app == null || !app.ensureModelInitialized(asrLanguage)) {
                        error = getString(R.string.error_model_not_initialized)
                    } else {
                        val activeEntries = if (asrLanguage == AsrLanguage.JAPANESE) {
                            app.dictionaryRepository.getActive()
                        } else {
                            emptyList()
                        }
                        val learnedCorrections = app.voiceCorrectionRepository.getActiveBoostEntries(asrLanguage)
                        val hotwordsStr = if (asrLanguage == AsrLanguage.JAPANESE) {
                            app.hotwordsGenerator.getHotwordsString(activeEntries, learnedCorrections)
                        } else {
                            ""
                        }
                        val rawText = withTimeout(3000L) {
                            app.asrManager.transcribe(samples, 16000, hotwordsStr, asrLanguage)
                        }
                        val slmDeltaProvider: ((String, String) -> Float?)? = if (app.llmReranker.isReady()) {
                            { orig, corr -> app.llmReranker.scorePairDeltaNll(textBeforeCursor, orig, corr) }
                        } else {
                            null
                        }
                        if (asrLanguage == AsrLanguage.ENGLISH) {
                            val cResult = EnglishVoicePostProcessor.formatWithCorrections(
                                rawText = rawText,
                                contextBefore = textBeforeCursor,
                                learnedCorrections = learnedCorrections,
                                slmCoherenceDeltaProvider = slmDeltaProvider
                            )
                            recognizedText = cResult.formattedText
                            suggestedChips = cResult.suggestedChips
                            appliedCorrections = cResult.appliedCorrections
                            deletePreceding = 0
                        } else {
                            val pitchSlope = ProsodyPitchEstimator.estimateTerminalPitchSlope(samples, 16000)
                            val prosodyMetadata = ProsodyBoundaryMetadata(
                                terminalPitchSlopeSemitones = pitchSlope,
                                precedingCommittedTail = textBeforeCursor
                            )
                            val pResult = app.contextAwareProcessor.processWithCorrectionsAndPunctuation(
                                rawText = rawText,
                                contextBefore = textBeforeCursor,
                                entries = activeEntries,
                                learnedCorrections = learnedCorrections,
                                metadata = prosodyMetadata,
                                slmCoherenceDeltaProvider = slmDeltaProvider
                            )
                            recognizedText = pResult.formattedText
                            deletePreceding = pResult.deletePrecedingChars
                            suggestedChips = pResult.suggestedChips
                            appliedCorrections = pResult.appliedCorrections
                        }
                        if (appliedCorrections.isNotEmpty()) {
                            synchronized(recentAutoAppliedCorrections) {
                                for (a in appliedCorrections) {
                                    recentAutoAppliedCorrections[a.correctedSurface] = Pair(a.originalSurface, asrLanguage)
                                }
                            }
                        }
                    }
                } catch (e: TimeoutCancellationException) {
                    Log.w(TAG, "Streaming chunk transcription timed out (>3000ms)")
                    error = getString(R.string.error_asr_timeout)
                } catch (e: Exception) {
                    Log.e(TAG, "Streaming chunk transcription error: ${e.message}", e)
                    error = e.message ?: "認識エラー"
                }

                withContext(Dispatchers.Main) {
                    progressDecoding?.visibility = View.GONE
                    if (error != null) {
                        arbitrator.onVoiceDecodeCancelledOrFailed(decodeId)
                        syncKeyboardState()
                        Toast.makeText(this@VoiceInputMethodService, error, Toast.LENGTH_SHORT).show()
                        if (!arbitrator.isKeyboardComposing()) {
                            candidateStripView?.showEmptyStatus(error)
                        }
                    } else if (recognizedText.isNotEmpty()) {
                        val committedImmediately = arbitrator.deliverRecognizedVoiceChunk(
                            editor = editorBridge,
                            text = recognizedText,
                            decodeId = decodeId,
                            deletePrecedingChars = deletePreceding
                        )
                        syncKeyboardState()
                        if (arbitrator.isKeyboardComposing()) {
                            // If pre-typing voice was prepended before active keyboard composing,
                            // refresh conversion candidates so SLM reranking uses the newly prepended context!
                            if (committedImmediately) {
                                updateCandidates()
                            }
                        } else if (committedImmediately) {
                            if (suggestedChips.isNotEmpty()) {
                                renderVoiceCorrectionSuggestions(suggestedChips, asrLanguage)
                            } else {
                                showNextPhrasePredictions(arbitrator.previousCommittedPhrase)
                            }
                        }
                    } else {
                        arbitrator.onVoiceDecodeCancelledOrFailed(decodeId)
                        syncKeyboardState()
                    }
                }
            }
        }
    }

    private fun shouldBypassEnglishAutoAssist(): Boolean {
        val mode = flickKeyboardView?.currentMode
        val textBeforeCursor = currentInputConnection?.getTextBeforeCursor(64, 0)?.toString() ?: ""
        val isFieldBypassed = EnglishCapitalizationAnalyzer.shouldBypassAutoAssist(currentInputEditorInfo, textBeforeCursor)
        return EnglishAssistJudgement.judgeAutoAssistBypass(mode, isFieldBypassed).decision
    }

    private fun mergeZeroQueryCandidates(
        fastPredictions: List<ConversionCandidate>,
        slmContinuations: List<ConversionCandidate>
    ): List<ConversionCandidate> {
        val combined = mutableListOf<ConversionCandidate>()
        val seenSurfaces = HashSet<String>()
        fun addCombinedCandidate(cand: ConversionCandidate) {
            val trimmedSurface = cand.surface.trim { it.isWhitespace() || it == '\u3000' }
            if (trimmedSurface.isNotEmpty() && seenSurfaces.add(trimmedSurface)) {
                combined.add(
                    if (trimmedSurface == cand.surface) cand else cand.copy(surface = trimmedSurface)
                )
            }
        }
        // 1. Priority: User history skip-bigrams and bigrams
        for (cand in fastPredictions) {
            if (cand.source == "history_skip_bigram" || cand.source == "history_bigram") {
                addCombinedCandidate(cand)
            }
        }
        // 2. Priority: Contextual SLM generation
        for (cand in slmContinuations) {
            addCombinedCandidate(cand)
        }
        // 3. Priority: Remaining canonical and particle candidates
        for (cand in fastPredictions) {
            addCombinedCandidate(cand)
        }
        return combined
    }

    private fun showNextPhrasePredictions(committedPhrase: String) {
        phase1TimerJob?.cancel()
        rerankJob?.cancel()
        warmContextJob?.cancel()
        speculativePrewarmJob?.cancel()
        updateDebugSlmBadge(null)
        val textBeforeCursor = currentInputConnection?.getTextBeforeCursor(256, 0)?.toString() ?: ""
        val suggestJpPeriod = KeyboardHeightManager.isJapanesePeriodSuggestionEnabled(this)

        // 1. FAST PATH (Instant UI update on main thread, < 0.1ms):
        // Display user history bigrams, skip-bigrams, canonical collocations, and ConnectionMatrix-ranked particles
        val committedRid = if (committedPhrase.isNotBlank()) kanaKanjiEngine.lookupSurfaceRid(committedPhrase) else 0
        val rawFastPredictions = nextPhrasePredictor.predict(
            lastPhrase = committedPhrase,
            textBeforeCursor = textBeforeCursor,
            suggestPeriod = suggestJpPeriod,
            committedRid = committedRid,
            connectionMatrix = kanaKanjiEngine.getConnectionMatrix(),
            surfaceToLid = kanaKanjiEngine::lookupLeadingLid
        )
        val fastPredictions = CandidateRerankJudgement.applyZeroQueryHorizonPairing(rawFastPredictions)

        val slm = LlmReranker.getInstance(this)
        val nextPhraseDecision = CandidateRerankJudgement.judgeNextPhraseRerank(
            CandidateRerankJudgement.NextPhraseRerankInput(
                isRerankEnabled = KeyboardHeightManager.isSlmRerankEnabled(this),
                isSlmReady = slm.isReady(),
                textBeforeCursor = textBeforeCursor
            )
        )

        // Z5: Check speculative pre-warmed zero-query cache for 0ms single-pass render on commit
        val prewarmedSlm = if (nextPhraseDecision.decision) {
            slm.getSpeculativeNextPhrases(textBeforeCursor)
        } else {
            null
        }
        if (prewarmedSlm != null && prewarmedSlm.isNotEmpty()) {
            val combined = mergeZeroQueryCandidates(fastPredictions, prewarmedSlm)
            val alpha = KeyboardHeightManager.getSlmWeightAlpha(this)
            val fastReranked = if (combined.size > 1) {
                slm.tryFastRerankWithStatus(
                    contextPrefix = textBeforeCursor,
                    candidates = combined.take(8),
                    weightAlpha = alpha,
                    targetInputLength = 0
                )
            } else {
                null
            }
            if (fastReranked != null && fastReranked.candidates.isNotEmpty()) {
                val finalZeroQuery = CandidateRerankJudgement.applyZeroQueryHorizonPairing(
                    fastReranked.candidates + combined.drop(8)
                )
                val engineType = KeyboardHeightManager.getRerankEngineType(this)
                updateDebugSlmBadge(engineType.badgeLabel)
                candidateStripView?.setCandidates(finalZeroQuery)
                if (fastReranked.isFullyComplete) {
                    return
                }
            } else {
                candidateStripView?.setCandidates(CandidateRerankJudgement.applyZeroQueryHorizonPairing(combined))
            }
        } else if (fastPredictions.isNotEmpty()) {
            candidateStripView?.setCandidates(fastPredictions)
        } else {
            candidateStripView?.showEmptyStatus(getActiveIdleStatusPrompt())
        }

        if (!nextPhraseDecision.decision) {
            return
        }

        rerankJob = serviceScope.launch(Dispatchers.Default) {
            try {
                withTimeout(2500L) {
                    // Pre-warm SLM KV cache with current context so next keystroke hits the cache instantly (<1ms)
                    slm.warmContext(textBeforeCursor)

                    // Generate next-phrase continuations directly from on-device SLM with lightweight bounds
                    val slmContinuations = prewarmedSlm
                        ?: slm.predictNextPhrases(textBeforeCursor, maxCandidates = 3, maxTokens = 5)

                    val combined = mergeZeroQueryCandidates(fastPredictions, slmContinuations)

                    // If candidates exist, rerank the top candidates using SLM log-likelihood with targetInputLength = 0 (JA_ZERO_QUERY)
                    val (reranked, slmUsed) = if (combined.size > 1) {
                        val alpha = KeyboardHeightManager.getSlmWeightAlpha(this@VoiceInputMethodService)
                        val (topReranked, used) = slm.rerankWithStatus(
                            contextPrefix = textBeforeCursor,
                            candidates = combined.take(8),
                            weightAlpha = alpha,
                            targetInputLength = 0
                        )
                        Pair(CandidateRerankJudgement.applyZeroQueryHorizonPairing(topReranked + combined.drop(8)), used)
                    } else {
                        Pair(combined, false)
                    }

                    // Verify IME state has not changed (no new keystroke or streaming text while computing)
                    if (isActive && !arbitrator.isKeyboardComposing() && !arbitrator.isStreamingComposing() && reranked.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            if (!arbitrator.isKeyboardComposing() && !arbitrator.isStreamingComposing()) {
                                val engineType = KeyboardHeightManager.getRerankEngineType(this@VoiceInputMethodService)
                                val debugBadge = if (slmUsed) engineType.badgeLabel else null
                                updateDebugSlmBadge(debugBadge)
                                candidateStripView?.setCandidates(reranked)
                            }
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                Log.w(TAG, "SLM next-phrase prediction timed out (>3000ms), fail-safe retaining fast predictions")
            } catch (e: Exception) {
                Log.w(TAG, "SLM prediction worker error: ${e.message}")
            }
        }
    }

    private fun updateEnglishTypoAndSpellingCandidates(justTypedChar: String? = null) {
        englishCandidateJob?.cancel()
        val rawTextBeforeCursor = currentInputConnection?.getTextBeforeCursor(256, 0)?.toString() ?: ""
        // Guard against async InputConnection commitText lag on the first letter of a word (e.g. sentence-start "i")
        val textBeforeCursor = if (
            justTypedChar != null &&
            justTypedChar.length == 1 &&
            justTypedChar[0].isLetter() &&
            (rawTextBeforeCursor.isEmpty() || !rawTextBeforeCursor.last().isLetter())
        ) {
            rawTextBeforeCursor + justTypedChar
        } else {
            rawTextBeforeCursor
        }
        val extracted = EnglishAssistJudgement.extractTrailingWord(textBeforeCursor)
        val rawWord = extracted.rawWord

        if (rawWord.isEmpty()) {
            updateDebugSlmBadge(null)
            candidateStripView?.showEmptyStatus(getActiveIdleStatusPrompt())
            return
        }

        val isShiftActive = (flickKeyboardView?.shiftState != FlickKeyboardView.ShiftState.OFF)
        val fieldPolicy = EnglishCapitalizationAnalyzer.resolveFieldPolicy(currentInputEditorInfo, textBeforeCursor)
        val capResult = if (fieldPolicy != InputFieldCapPolicy.BYPASS) {
            EnglishCapitalizationAnalyzer.analyze(rawWord, textBeforeCursor, isShiftActive, fieldPolicy)
        } else {
            EnglishCapitalizationAnalyzer.CapitalizationResult(EnglishCapitalizationAnalyzer.CapitalizationType.NONE, null)
        }

        // For very short words (length < 2), only show candidates if capitalization or acronym applies (e.g. "i" -> "I")
        val wordEligibility = EnglishAssistJudgement.judgeWordCandidateEligibility(
            rawWord = rawWord,
            hasPrimaryCapitalizationCandidate = capResult.primaryCandidate != null || capResult.additionalCandidates.isNotEmpty()
        )
        if (!wordEligibility.decision) {
            updateDebugSlmBadge(null)
            candidateStripView?.showEmptyStatus(getActiveIdleStatusPrompt())
            return
        }

        val spellEngine = EnglishSpellEngine.getInstance(this)

        englishCandidateJob = serviceScope.launch(Dispatchers.Default) {
            if (!spellEngine.isReady()) {
                spellEngine.ensureInitialized()
            }
            val app = AsrApp.instanceOrNull
            val activeEntries = app?.dictionaryRepository?.getActive() ?: emptyList()
            val userCandidates = EnglishDictionaryUtils.suggestFromUserDictionary(rawWord, activeEntries, maxCandidates = 4)

            val suggestions = if (rawWord.length >= 2 && spellEngine.isReady()) {
                spellEngine.suggestTypo(rawWord, maxCandidates = 6)
            } else {
                emptyList()
            }

            val bosProb = if (isShiftActive || rawWord.first().isUpperCase() || fieldPolicy == InputFieldCapPolicy.BYPASS) {
                0.0f
            } else {
                capResult.bosProbability
            }
            val isSentenceStart = bosProb >= 0.5f
            val candidateList = mutableListOf<ConversionCandidate>()
            // Exact-surface deduplication preserves distinct case variants (e.g. "US" vs "us", "May" vs "may")
            val seenSurfaces = mutableSetOf<String>()

            // 1. User dictionary entries (exact case / normalized typo / prefix)
            for (uCand in userCandidates) {
                val surface = uCand.surface
                val adapted = if (isSentenceStart && surface.all { it.isLowerCase() || !it.isLetter() }) {
                    surface.replaceFirstChar { it.uppercase() }
                } else {
                    surface
                }
                if (seenSurfaces.add(adapted)) {
                    candidateList.add(
                        ConversionCandidate(
                            surface = adapted,
                            reading = rawWord,
                            isPrediction = false,
                            source = "user_english",
                            priority = uCand.priority,
                            obsPenalty = uCand.obsPenalty
                        )
                    )
                }
            }

            // 2. Bayesian truecasing variants for rawWord + typo/completion suggestions
            val rankedPool = mutableListOf<BayesianCaseRanker.RankedCaseCandidate>()
            if (!isShiftActive && !rawWord.first().isUpperCase() && fieldPolicy != InputFieldCapPolicy.BYPASS) {
                rankedPool.addAll(
                    BayesianCaseRanker.generateCaseVariants(
                        baseWord = rawWord,
                        bosProb = bosProb,
                        policy = fieldPolicy,
                        source = "raw",
                        obsPenalty = 0.0f,
                        isRawWord = true
                    )
                )
            }

            for (sug in suggestions) {
                val isPrefix = sug.lowercase().startsWith(rawWord.lowercase())
                val isPrediction = isPrefix && sug.length > rawWord.length
                val obsPenalty = if (isPrefix) {
                    0.0f
                } else {
                    val cost = EnglishDictionaryUtils.editAndSpatialCost(rawWord.lowercase(), sug.lowercase())
                    val maxLen = maxOf(rawWord.length, sug.length)
                    if (maxLen > 0) cost / maxLen.toFloat() else 0.0f
                }
                if (!isShiftActive && !rawWord.first().isUpperCase() && fieldPolicy != InputFieldCapPolicy.BYPASS) {
                    rankedPool.addAll(
                        BayesianCaseRanker.generateCaseVariants(
                            baseWord = sug,
                            bosProb = bosProb,
                            policy = fieldPolicy,
                            source = "english_typo",
                            obsPenalty = obsPenalty,
                            isPrediction = isPrediction
                        )
                    )
                } else {
                    rankedPool.add(
                        BayesianCaseRanker.RankedCaseCandidate(
                            surface = sug,
                            baseWord = sug,
                            source = "english_typo",
                            posteriorProb = 1.0f,
                            energy = 5.0f * obsPenalty,
                            obsPenalty = obsPenalty,
                            isPrediction = isPrediction
                        )
                    )
                }
            }

            for (item in rankedPool.sortedBy { it.energy }) {
                if (seenSurfaces.add(item.surface)) {
                    candidateList.add(
                        ConversionCandidate(
                            surface = item.surface,
                            reading = rawWord,
                            isPrediction = item.isPrediction,
                            source = item.source,
                            obsPenalty = item.obsPenalty
                        )
                    )
                }
            }

            // 3. Always ensure exact raw typed word is present as an escape hatch
            if (seenSurfaces.add(rawWord)) {
                candidateList.add(
                    ConversionCandidate(
                        surface = rawWord,
                        reading = rawWord,
                        isPrediction = false,
                        source = "raw",
                        obsPenalty = 0.0f
                    )
                )
            }

            // 6. Contextual Language Model Reranking (SLM)
            val slm = app?.llmReranker
            val precedingContext = textBeforeCursor.dropLast(rawWord.length)
            val alpha = KeyboardHeightManager.getSlmWeightAlpha(this@VoiceInputMethodService)

            val (finalCandidates, slmUsed) = if (
                slm != null &&
                slm.state == LlmReranker.State.READY &&
                KeyboardHeightManager.isSlmRerankEnabled(this@VoiceInputMethodService) &&
                candidateList.size > 1
            ) {
                val targetLen = rawWord.filter { it.isLetter() }.length.let { if (it > 0) it else rawWord.length }
                slm.rerankWithStatus(precedingContext, candidateList.take(8), weightAlpha = alpha, targetInputLength = targetLen)
            } else {
                Pair(candidateList, false)
            }

            val displayList = finalCandidates.take(5)
            if (displayList.isNotEmpty()) {
                val badge = EnglishAssistJudgement.judgeCandidateBadge(
                    slmUsed = slmUsed,
                    hasUserCandidates = userCandidates.isNotEmpty(),
                    hasPrimaryCapitalization = capResult.primaryCandidate != null
                )
                Log.i(TAG, "English candidates for '$rawWord' (context='$precedingContext', slm=$slmUsed): ${displayList.map { it.surface }}")
                withContext(Dispatchers.Main) {
                    if (flickKeyboardView?.currentMode == JapaneseKanaUtils.KeyboardMode.ALPHABET) {
                        updateDebugSlmBadge(badge)
                        candidateStripView?.setCandidates(displayList)
                    }
                }
            } else {
                withContext(Dispatchers.Main) {
                    updateDebugSlmBadge(null)
                    candidateStripView?.showEmptyStatus(getActiveIdleStatusPrompt())
                }
            }
        }
    }

    private fun triggerEnglishSentenceContextCorrection() {
        val spellEngine = EnglishSpellEngine.getInstance(this)
        val textBeforeCursor = currentInputConnection?.getTextBeforeCursor(256, 0)?.toString() ?: ""
        val gecDecision = EnglishAssistJudgement.judgeGecClause(textBeforeCursor)
        if (!gecDecision.shouldCorrect) {
            candidateStripView?.showEmptyStatus(getActiveIdleStatusPrompt())
            return
        }
        val clause = gecDecision.clause

        serviceScope.launch(Dispatchers.Default) {
            if (!spellEngine.isReady()) {
                spellEngine.ensureInitialized()
            }
            if (!spellEngine.isReady()) {
                withContext(Dispatchers.Main) {
                    candidateStripView?.showEmptyStatus(getActiveIdleStatusPrompt())
                }
                return@launch
            }
            val corrected = spellEngine.correctSentence(clause, "en")
            if (corrected.isNotBlank() && corrected != clause) {
                val cand = listOf(
                    ConversionCandidate(
                        surface = corrected,
                        reading = clause,
                        isPrediction = false,
                        source = "english_gec"
                    )
                )
                withContext(Dispatchers.Main) {
                    if (flickKeyboardView?.currentMode == JapaneseKanaUtils.KeyboardMode.ALPHABET) {
                        updateDebugSlmBadge("AI-GEC")
                        candidateStripView?.setCandidates(cand)
                    }
                }
            } else {
                withContext(Dispatchers.Main) {
                    updateDebugSlmBadge(null)
                    candidateStripView?.showEmptyStatus(getActiveIdleStatusPrompt())
                }
            }
        }
    }

    private fun handleVoiceToggleClicked() {
        when (currentState) {
            State.IDLE -> startListening()
            State.RECORDING -> finishListeningAndDecode()
            State.DECODING -> {
                restartListeningAfterDecode = !restartListeningAfterDecode
            }
        }
    }

    private fun startListening() {
        clipboardSuggestionPolicy.onUserTypingOrVoiceAction(android.os.SystemClock.uptimeMillis())
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, getString(R.string.keyboard_mic_permission_needed), Toast.LENGTH_LONG).show()
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
            return
        }

        arbitrator.settleActiveVoiceAnchorIfNeeded(editorBridge)
        commitComposingText()

        val isStreaming = KeyboardHeightManager.isStreamingVoiceInputEnabled(this)
        isStreamingActive = isStreaming
        audioRecorder.isStreamingMode = isStreaming
        isCurrentlySpeaking = false
        restartListeningAfterDecode = false
        arbitrator.resetStreamingSessionState()

        val started = audioRecorder.start()
        if (started) {
            updateUiState(State.RECORDING)
            syncKeyboardState()
            if (isStreamingActive) {
                startStreamingLoop()
            }
        } else {
            val errorMsg = getString(R.string.error_mic_init_failed)
            Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show()
            candidateStripView?.showEmptyStatus(errorMsg)
            updateUiState(State.IDLE)
            syncKeyboardState()
        }
    }

    private fun startStreamingLoop() {
        streamingJob?.cancel()
        streamingJob = serviceScope.launch(Dispatchers.Default) {
            val app = AsrApp.instanceOrNull
            if (app == null || !app.ensureModelInitialized()) {
                Log.w(TAG, "Streaming loop skipped: model not initialized")
                return@launch
            }

            // Short initial delay before polling audio snapshot
            delay(150L)

            while (isActive && currentState == State.RECORDING && isStreamingActive) {
                // Suspend streaming ASR updates while user is actively composing on the keyboard
                val (isKeyComposing, textBeforeCursor, asrLanguage) = withContext(Dispatchers.Main) {
                    Triple(
                        arbitrator.isKeyboardComposing(),
                        currentInputConnection?.getTextBeforeCursor(500, 0)?.toString() ?: "",
                        resolveCurrentAsrLanguage()
                    )
                }
                if (isKeyComposing) {
                    delay(150L)
                    continue
                }

                val genId = arbitrator.streamingGenerationId
                val hasSpeech = audioRecorder.hasUnflushedSpeech() || arbitrator.isStreamingComposing()
                val samples = if (hasSpeech) audioRecorder.getSnapshotSamples() else FloatArray(0)

                if (samples.size >= 3200 && genId == arbitrator.streamingGenerationId) { // At least 200ms of audio
                    try {
                        val rawText = asrDecodingMutex.withLock {
                            if (!app.ensureModelInitialized(asrLanguage)) {
                                ""
                            } else {
                                val hotwordsStr = if (asrLanguage == AsrLanguage.JAPANESE) {
                                    val learned = app.voiceCorrectionRepository.getActiveBoostEntries(asrLanguage)
                                    app.hotwordsGenerator.getHotwordsString(app.dictionaryRepository.getActive(), learned)
                                } else {
                                    ""
                                }
                                app.asrManager.transcribe(samples, 16000, hotwordsStr, asrLanguage)
                            }
                        }

                        if (rawText.isNotBlank() && genId == arbitrator.streamingGenerationId) {
                            val formatted = formatVoiceTextWithCorrections(
                                text = rawText,
                                textBeforeCursor = textBeforeCursor,
                                language = asrLanguage,
                                useSlmGate = true
                            ).formattedText

                            withContext(Dispatchers.Main) {
                                if (currentState == State.RECORDING && isStreamingActive && genId == arbitrator.streamingGenerationId && !arbitrator.isKeyboardComposing()) {
                                    arbitrator.streamingInferenceHistory.add(formatted)
                                    if (arbitrator.streamingInferenceHistory.size > 8) {
                                        arbitrator.streamingInferenceHistory.removeAt(0)
                                    }

                                    // Hybrid Morpheme/Word-Aware Horizon partitioning:
                                    // Combines N-Step LCP stability agreement, boundary snapping,
                                    // and active horizon clamping (protects trailing ~12 chars).
                                    val (frozen, active) = if (asrLanguage == AsrLanguage.ENGLISH) {
                                        EnglishVoicePostProcessor.extractWordAwareFreezePrefix(
                                            history = arbitrator.streamingInferenceHistory,
                                            currentFormatted = formatted,
                                            minConsecutiveSteps = 3,
                                            protectTrailingChars = 12
                                        )
                                    } else {
                                        JapanesePunctuationEngine.extractMorphemeAwareFreezePrefix(
                                            history = arbitrator.streamingInferenceHistory,
                                            currentFormatted = formatted,
                                            minConsecutiveSteps = 3,
                                            protectTrailingChars = 12
                                        )
                                    }

                                    val changed = arbitrator.onStreamingStepResult(
                                        editor = editorBridge,
                                        formattedText = formatted,
                                        generationId = genId,
                                        frozenPrefix = frozen,
                                        activeSuffix = active
                                    )
                                    if (changed) {
                                        val activeComp = arbitrator.streamingLastComposingText
                                        if (activeComp.isNotEmpty()) {
                                            candidateStripView?.showEmptyStatus("音声入力中: $activeComp")
                                        }
                                        syncKeyboardState()
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Streaming step transcription exception: ${e.message}")
                    }
                }
                delay(250L)
            }
        }
    }

    private fun finishListeningAndDecode() {
        val wasStreaming = isStreamingActive
        streamingJob?.cancel()
        streamingJob = null
        isCurrentlySpeaking = false

        val textBeforeCursor = currentInputConnection?.getTextBeforeCursor(500, 0)?.toString() ?: ""
        val asrLanguage = resolveCurrentAsrLanguage()
        val hadStreamingComposing = wasStreaming && arbitrator.isStreamingComposing()
        val hadUnflushedBeforeStop = audioRecorder.hasUnflushedSpeech()

        // If streaming composing text is already on screen, commit it immediately on stop so
        // any subsequent keystrokes typed during State.DECODING appear strictly after it.
        if (hadStreamingComposing) {
            var streamingChips: List<VoiceCorrectionSuggestion> = emptyList()
            var streamingApplied: List<VoiceCorrectionSuggestion> = emptyList()
            arbitrator.commitActiveStreamingComposing(
                editor = editorBridge,
                onResetAudioUtterance = {},
                formatFn = { raw ->
                    val res = formatVoiceTextWithCorrections(raw, textBeforeCursor, asrLanguage, useSlmGate = false)
                    streamingChips = res.suggestedChips
                    streamingApplied = res.appliedCorrections
                    res.formattedText
                }
            )
            if (streamingApplied.isNotEmpty()) {
                synchronized(recentAutoAppliedCorrections) {
                    for (a in streamingApplied) {
                        recentAutoAppliedCorrections[a.correctedSurface] = Pair(a.originalSurface, asrLanguage)
                    }
                }
            }
            if (!arbitrator.isKeyboardComposing()) {
                if (streamingChips.isNotEmpty()) {
                    renderVoiceCorrectionSuggestions(streamingChips, asrLanguage)
                } else if (arbitrator.previousCommittedPhrase.isNotEmpty()) {
                    showNextPhrasePredictions(arbitrator.previousCommittedPhrase)
                }
            }
        } else if (wasStreaming) {
            arbitrator.resetStreamingSessionState()
        }

        updateUiState(State.DECODING)
        val samples = audioRecorder.stop()
        syncKeyboardState()

        // Determine if we need to run a final ASR pass on remaining samples:
        // - In non-streaming mode: whenever samples is non-empty.
        // - In streaming mode: if user spoke a short utterance (< 250ms) and tapped Stop before
        //   the first streaming loop iteration rendered streamingLastComposingText.
        val shouldDecodeFinalSamples = if (!wasStreaming) {
            samples.isNotEmpty()
        } else {
            !hadStreamingComposing && hadUnflushedBeforeStop && samples.isNotEmpty()
        }

        val decodeId = if (shouldDecodeFinalSamples) arbitrator.onSpeechChunkCapturedForDecode() else -1L

        serviceScope.launch(Dispatchers.Default) {
            asrDecodingMutex.withLock {
                try {
                    if (shouldDecodeFinalSamples) {
                        var recognizedText = ""
                        var deletePreceding = 0
                        var suggestedChips: List<VoiceCorrectionSuggestion> = emptyList()
                        var appliedCorrections: List<VoiceCorrectionSuggestion> = emptyList()
                        var error: String? = null

                        try {
                            val app = AsrApp.instanceOrNull
                            if (app == null || !app.ensureModelInitialized(asrLanguage)) {
                                error = getString(R.string.error_model_not_initialized)
                            } else {
                                val activeEntries = if (asrLanguage == AsrLanguage.JAPANESE) {
                                    app.dictionaryRepository.getActive()
                                } else {
                                    emptyList()
                                }
                                val learnedCorrections = app.voiceCorrectionRepository.getActiveBoostEntries(asrLanguage)
                                val hotwordsStr = if (asrLanguage == AsrLanguage.JAPANESE) {
                                    app.hotwordsGenerator.getHotwordsString(activeEntries, learnedCorrections)
                                } else {
                                    ""
                                }
                                val rawText = withTimeout(3000L) {
                                    app.asrManager.transcribe(samples, 16000, hotwordsStr, asrLanguage)
                                }
                                val slmDeltaProvider: ((String, String) -> Float?)? = if (app.llmReranker.isReady()) {
                                    { orig, corr -> app.llmReranker.scorePairDeltaNll(textBeforeCursor, orig, corr) }
                                } else {
                                    null
                                }
                                if (asrLanguage == AsrLanguage.ENGLISH) {
                                    val cResult = EnglishVoicePostProcessor.formatWithCorrections(
                                        rawText = rawText,
                                        contextBefore = textBeforeCursor,
                                        learnedCorrections = learnedCorrections,
                                        slmCoherenceDeltaProvider = slmDeltaProvider
                                    )
                                    recognizedText = cResult.formattedText
                                    suggestedChips = cResult.suggestedChips
                                    appliedCorrections = cResult.appliedCorrections
                                    deletePreceding = 0
                                } else {
                                    val pitchSlope = ProsodyPitchEstimator.estimateTerminalPitchSlope(samples, 16000)
                                    val prosodyMetadata = ProsodyBoundaryMetadata(
                                        terminalPitchSlopeSemitones = pitchSlope,
                                        precedingCommittedTail = textBeforeCursor
                                    )
                                    val pResult = app.contextAwareProcessor.processWithCorrectionsAndPunctuation(
                                        rawText = rawText,
                                        contextBefore = textBeforeCursor,
                                        entries = activeEntries,
                                        learnedCorrections = learnedCorrections,
                                        metadata = prosodyMetadata,
                                        slmCoherenceDeltaProvider = slmDeltaProvider
                                    )
                                    recognizedText = pResult.formattedText
                                    deletePreceding = pResult.deletePrecedingChars
                                    suggestedChips = pResult.suggestedChips
                                    appliedCorrections = pResult.appliedCorrections
                                }
                                if (appliedCorrections.isNotEmpty()) {
                                    synchronized(recentAutoAppliedCorrections) {
                                        for (a in appliedCorrections) {
                                            recentAutoAppliedCorrections[a.correctedSurface] = Pair(a.originalSurface, asrLanguage)
                                        }
                                    }
                                }
                            }
                        } catch (e: TimeoutCancellationException) {
                            Log.w(TAG, "IME Transcription timed out (>3000ms)")
                            error = getString(R.string.error_asr_timeout)
                        } catch (e: Exception) {
                            Log.e(TAG, "IME Transcription error: ${e.message}", e)
                            error = e.message ?: "認識エラー"
                        }

                        withContext(Dispatchers.Main) {
                            if (error != null) {
                                arbitrator.onVoiceDecodeCancelledOrFailed(decodeId)
                                Toast.makeText(this@VoiceInputMethodService, error, Toast.LENGTH_LONG).show()
                                if (!arbitrator.isKeyboardComposing()) {
                                    candidateStripView?.showEmptyStatus(error)
                                }
                            } else if (recognizedText.isNotEmpty()) {
                                val committedImmediately = arbitrator.deliverRecognizedVoiceChunk(
                                    editor = editorBridge,
                                    text = recognizedText,
                                    decodeId = decodeId,
                                    deletePrecedingChars = deletePreceding
                                )
                                if (arbitrator.isKeyboardComposing()) {
                                    if (committedImmediately) {
                                        updateCandidates()
                                    }
                                } else if (committedImmediately) {
                                    if (suggestedChips.isNotEmpty()) {
                                        renderVoiceCorrectionSuggestions(suggestedChips, asrLanguage)
                                    } else {
                                        showNextPhrasePredictions(arbitrator.previousCommittedPhrase)
                                    }
                                }
                            } else {
                                arbitrator.onVoiceDecodeCancelledOrFailed(decodeId)
                                if (!arbitrator.isKeyboardComposing()) {
                                    candidateStripView?.showEmptyStatus(getString(R.string.hint_no_speech_detected))
                                }
                            }
                        }
                    }

                    withContext(Dispatchers.Main) {
                        if (!arbitrator.isKeyboardComposing()) {
                            val flushed = arbitrator.flushPendingVoice(editorBridge)
                            if (!flushed.isNullOrEmpty()) {
                                showNextPhrasePredictions(arbitrator.previousCommittedPhrase)
                            }
                        }
                    }
                } finally {
                    withContext(Dispatchers.Main) {
                        updateUiState(State.IDLE)
                        syncKeyboardState()
                        if (restartListeningAfterDecode) {
                            restartListeningAfterDecode = false
                            startListening()
                        }
                    }
                }
            }
        }
    }

    private fun cancelRecording() {
        streamingJob?.cancel()
        streamingJob = null
        isCurrentlySpeaking = false
        restartListeningAfterDecode = false
        if (isStreamingActive && arbitrator.isStreamingComposing() && !arbitrator.isKeyboardComposing()) {
            currentInputConnection?.setComposingText("", 0)
        }
        arbitrator.resetStreamingSessionState()
        audioRecorder.stop()
        updateUiState(State.IDLE)
        syncKeyboardState()
    }

    private fun handleReturnClicked() {
        val conn = currentInputConnection ?: return
        val info = currentInputEditorInfo
        val action = if (info != null) info.imeOptions and EditorInfo.IME_MASK_ACTION else EditorInfo.IME_ACTION_NONE

        if (action != EditorInfo.IME_ACTION_NONE && (info?.imeOptions?.and(EditorInfo.IME_FLAG_NO_ENTER_ACTION) == 0)) {
            conn.performEditorAction(action)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
    }

    private fun handleGlobeClicked() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val switched = switchToPreviousInputMethod()
                if (!switched) {
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.showInputMethodPicker()
                }
            } else {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showInputMethodPicker()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Switch input method error: ${e.message}")
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }
    }

    private fun updateClipboardButton() {
        btnHeaderClipboard?.text = "📋"
        btnHeaderClipboard?.isEnabled = true
        btnHeaderClipboard?.alpha = 1.0f
    }

    private fun isClipboardHistoryShowing(): Boolean {
        return clipboardHistoryView?.visibility == View.VISIBLE
    }

    private fun showClipboardHistoryView() {
        clipboardHistoryView?.refreshHistory()
        clipboardHistoryView?.visibility = View.VISIBLE
        flickKeyboardView?.visibility = View.GONE
        btnHeaderClipboard?.setBackgroundResource(R.drawable.badge_background)
        updateHeaderVisibility()
    }

    private fun hideClipboardHistoryView() {
        if (clipboardHistoryView?.visibility == View.VISIBLE) {
            clipboardHistoryView?.visibility = View.GONE
            flickKeyboardView?.visibility = View.VISIBLE
            btnHeaderClipboard?.setBackgroundResource(0)
            updateHeaderVisibility()
        }
    }

    private fun toggleClipboardHistoryView() {
        if (isClipboardHistoryShowing()) {
            hideClipboardHistoryView()
        } else {
            showClipboardHistoryView()
        }
    }

    private fun isClipboardChipSource(source: String?): Boolean {
        return source == "clipboard_recent" || source == "clipboard_sensitive"
    }

    private fun canShowClipboardSuggestion(): Boolean {
        val hasNonClipboardCandidates = candidateStripView?.getCandidates()?.any { !isClipboardChipSource(it.source) } == true
        return clipboardSuggestionPolicy.shouldShowSuggestion(
            isVoiceIdle = (currentState == State.IDLE),
            isClipboardHistoryShowing = isClipboardHistoryShowing(),
            isKeyboardComposing = arbitrator.isKeyboardComposing(),
            isStreamingComposing = arbitrator.isStreamingComposing(),
            hasActiveNonClipboardCandidates = hasNonClipboardCandidates
        )
    }

    private fun checkAndRenderClipboardSuggestion() {
        val currentlyShowingClipboard = candidateStripView?.getCandidates()?.any { isClipboardChipSource(it.source) } == true
        if (!canShowClipboardSuggestion() && !currentlyShowingClipboard) {
            return
        }
        clipboardExpirationJob?.cancel()
        serviceScope.launch(Dispatchers.Main) {
            val repo = ClipboardHistoryRepository.getInstance(this@VoiceInputMethodService)
            val nowMs = System.currentTimeMillis()
            val suggestion = repo.getRecentSuggestionCandidateWithSensitivity(nowMs)
            if (suggestion != null && canShowClipboardSuggestion()) {
                val (clipCandidate, discoveredAtMs) = when (suggestion) {
                    is RecentSuggestion.Sensitive -> {
                        ConversionCandidate(
                            surface = ClipboardUtils.formatSensitiveCandidateChipPreview(),
                            reading = suggestion.metadata.id,
                            isPrediction = true,
                            source = "clipboard_sensitive"
                        ) to suggestion.metadata.discoveredAtMs
                    }
                    is RecentSuggestion.Regular -> {
                        ConversionCandidate(
                            surface = suggestion.item.text,
                            reading = suggestion.item.text,
                            isPrediction = true,
                            source = "clipboard_recent"
                        ) to suggestion.item.discoveredAtMs
                    }
                }
                candidateStripView?.setCandidates(listOf(clipCandidate))

                // Schedule auto-dismiss when 60s expires
                val remainingMs = (ClipboardHistoryRepository.CANDIDATE_SUGGEST_TTL_MS - (nowMs - discoveredAtMs)).coerceAtLeast(0L)
                clipboardExpirationJob = serviceScope.launch {
                    delay(remainingMs + 50L)
                    if (candidateStripView?.getCandidates()?.any { isClipboardChipSource(it.source) } == true) {
                        candidateStripView?.showEmptyStatus(getActiveIdleStatusPrompt())
                    }
                }
            } else if (candidateStripView?.getCandidates()?.any { isClipboardChipSource(it.source) } == true) {
                candidateStripView?.showEmptyStatus(getActiveIdleStatusPrompt())
            }
        }
    }

    private fun handleClipboardClicked() {
        toggleClipboardHistoryView()
    }

    private fun pasteTextFromHistory(text: String, isSensitive: Boolean = false) {
        if (text.isNotEmpty()) {
            clipboardSuggestionPolicy.onUserTypingOrVoiceAction(android.os.SystemClock.uptimeMillis())
            val isPasswordField = ClipboardUtils.isPasswordInputType(currentInputEditorInfo?.inputType ?: 0)
            val suppressLearning = isSensitive || isPasswordField

            val prevPhrase = arbitrator.previousCommittedPhrase
            val hadKeyboardComposing = arbitrator.isKeyboardComposing()
            val readingBefore = arbitrator.composingText.toString()
            val hadUnflushed = currentState == State.RECORDING && audioRecorder.hasUnflushedSpeech()

            arbitrator.onClipboardPaste(
                editor = editorBridge,
                clipText = text,
                hasUnflushedSpeech = hadUnflushed,
                isSensitiveOrPasswordContext = suppressLearning,
                onFlushPreTypingSpeech = { flushPreTypingSpeechIfNeeded() },
                onResetStreamingUtterance = { resetStreamingUtteranceInRecorder() },
                formatStreamingFn = { formatVoiceText(it) }
            )
            audioRecorder.notifyUserTyping(350L)
            phase1TimerJob?.cancel()
            rerankJob?.cancel()
            isCurrentCandidatesReranked = false

            if (!isSensitive) {
                serviceScope.launch {
                    val repo = ClipboardHistoryRepository.getInstance(this@VoiceInputMethodService)
                    val recent = repo.getRecentSuggestionCandidate()
                    if (recent != null && recent.text == text) {
                        repo.markDismissedFromStrip(recent.id)
                    }
                }
            }

            if (!suppressLearning && hadKeyboardComposing && prevPhrase.isNotEmpty() && readingBefore.isNotEmpty()) {
                nextPhrasePredictor.recordTransition(prevPhrase, readingBefore)
            }
            syncKeyboardState()
            if (!suppressLearning) {
                showNextPhrasePredictions(arbitrator.previousCommittedPhrase)
            } else {
                candidateStripView?.showEmptyStatus(getActiveIdleStatusPrompt())
            }
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        flickKeyboardView?.dismissPopup()
        if (isInputViewShown) {
            setInputView(onCreateInputView())
        }
    }

    private fun applyKeyboardDimensions() {
        val root = keyboardRoot ?: return
        val targetHeight = KeyboardHeightManager.calculateKeyboardHeightPx(this)
        val targetHeaderHeight = KeyboardHeightManager.calculateHeaderHeightPx(this)
        val contextBarHeight = KeyboardHeightManager.calculateContextBarHeightPx(this)

        val totalHeight = targetHeight + contextBarHeight
        val rootParams = root.layoutParams ?: android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            totalHeight
        )
        if (rootParams.height != totalHeight) {
            rootParams.height = totalHeight
            root.layoutParams = rootParams
        }

        val header = keyboardHeader
        if (header != null && header.layoutParams != null && header.layoutParams.height != targetHeaderHeight) {
            val headerParams = header.layoutParams
            headerParams.height = targetHeaderHeight
            header.layoutParams = headerParams
        }

        val bar = contextBarContainer
        if (bar != null && bar.layoutParams != null && contextBarHeight > 0 && bar.layoutParams.height != contextBarHeight) {
            val barParams = bar.layoutParams
            barParams.height = contextBarHeight
            bar.layoutParams = barParams
        }
    }

    private fun updateHeaderVisibility() {
        val voiceState = when (currentState) {
            State.IDLE -> com.example.myasr.context.ime.domain.ImeHeaderDecisionModel.VoiceState.IDLE
            State.RECORDING -> com.example.myasr.context.ime.domain.ImeHeaderDecisionModel.VoiceState.RECORDING
            State.DECODING -> com.example.myasr.context.ime.domain.ImeHeaderDecisionModel.VoiceState.DECODING
        }
        val decision = com.example.myasr.context.ime.domain.ImeHeaderDecisionModel.decideHeaderVisibility(
            com.example.myasr.context.ime.domain.ImeHeaderDecisionModel.ImeHeaderVisibilityInput(
                voiceState = voiceState,
                isKeyboardComposing = arbitrator.isKeyboardComposing(),
                isStreamingComposing = arbitrator.isStreamingComposing(),
                isKeyTouchActive = isKeyTouchActive,
                isClipboardHistoryShowing = isClipboardHistoryShowing()
            )
        )
        voiceToggleContainer?.visibility = if (decision.showVoiceToggle) View.VISIBLE else View.GONE
        btnHeaderDict?.visibility = if (decision.showDictionaryButton) View.VISIBLE else View.GONE
        btnHeaderClipboard?.visibility = if (decision.showClipboardButton) View.VISIBLE else View.GONE
    }

    private fun updateUiState(state: State) {
        currentState = state
        when (state) {
            State.IDLE -> {
                btnVoiceToggle?.visibility = View.VISIBLE
                btnVoiceToggle?.isEnabled = true
                btnVoiceToggle?.text = "🎙 話す"
                btnVoiceToggle?.setBackgroundResource(R.drawable.bg_voice_toggle_idle)
                btnVoiceToggle?.setTextColor(ContextCompat.getColor(this, R.color.voice_toggle_idle_text))
                progressDecoding?.visibility = View.GONE
                recordingIndicator?.visibility = View.GONE
            }
            State.RECORDING -> {
                btnVoiceToggle?.visibility = View.VISIBLE
                btnVoiceToggle?.isEnabled = true
                btnVoiceToggle?.text = "⏹ 停止"
                btnVoiceToggle?.setBackgroundResource(R.drawable.bg_voice_toggle_recording)
                btnVoiceToggle?.setTextColor(ContextCompat.getColor(this, R.color.voice_toggle_recording_text))
                progressDecoding?.visibility = View.GONE
                recordingIndicator?.visibility = View.VISIBLE
                if (!arbitrator.isKeyboardComposing()) {
                    candidateStripView?.showEmptyStatus("話すか、キー入力してください…")
                }
            }
            State.DECODING -> {
                btnVoiceToggle?.visibility = View.VISIBLE
                progressDecoding?.visibility = View.VISIBLE
                recordingIndicator?.visibility = View.GONE
            }
        }
        updateHeaderVisibility()
    }

    override fun onDestroy() {
        clipboardExpirationJob?.cancel()
        phase1TimerJob?.cancel()
            rerankJob?.cancel()
        englishCandidateJob?.cancel()
        streamingJob?.cancel()
        getSharedPreferences(KeyboardHeightManager.PREFS_NAME, Context.MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(prefsListener)
        flickKeyboardView?.dismissPopup()
        super.onDestroy()
        serviceScope.cancel()
        audioRecorder.stop()
    }
}
