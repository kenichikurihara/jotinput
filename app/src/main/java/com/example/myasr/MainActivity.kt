package com.example.myasr

import com.example.myasr.context.english.infrastructure.EnglishSpellEngine
import com.example.myasr.context.ime.presentation.KeyboardHeightManager
import com.example.myasr.context.kanakanji.domain.KanaKanjiEngine
import com.example.myasr.context.provisioning.domain.DictUpdateState
import com.example.myasr.context.provisioning.domain.DictionaryStatusInfo
import com.example.myasr.context.provisioning.domain.DictionaryUpdateTracker
import com.example.myasr.context.provisioning.infrastructure.DictionaryType
import com.example.myasr.context.provisioning.infrastructure.EnglishAsrModelDownloader
import com.example.myasr.context.provisioning.infrastructure.EnglishSpellDownloader
import com.example.myasr.context.provisioning.infrastructure.ProvisioningForegroundService
import com.example.myasr.context.provisioning.infrastructure.ProvisioningTaskKind
import com.example.myasr.context.provisioning.infrastructure.ProvisioningUiState
import com.example.myasr.context.reranking.domain.RerankEngineType
import com.example.myasr.context.reranking.infrastructure.LlmReranker
import com.example.myasr.context.reranking.infrastructure.PowerBenchmarkRunner
import com.example.myasr.context.speech.application.HotwordsGenerator
import com.example.myasr.context.speech.domain.AsrLanguage
import com.example.myasr.context.speech.infrastructure.AsrManager
import com.example.myasr.context.vocabulary.infrastructure.DictionaryRepository

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var tvModelStatus: TextView
    private lateinit var tvDictionaryStatus: TextView
    private lateinit var btnOpenDictionary: MaterialButton
    private lateinit var tvOssLicenses: TextView

    // Keyboard controls
    private lateinit var tvImeStatusBadge: TextView
    private lateinit var tvCard1AiStatusBadge: TextView
    private lateinit var btnEnableIme: MaterialButton
    private lateinit var btnSelectIme: MaterialButton
    private lateinit var etKeyboardTest: EditText

    // Keyboard Height & QWERTY Number Row widgets
    private lateinit var rgKeyboardHeight: android.widget.RadioGroup
    private lateinit var rbHeightCompact: android.widget.RadioButton
    private lateinit var rbHeightShort: android.widget.RadioButton
    private lateinit var rbHeightNormal: android.widget.RadioButton
    private lateinit var rbHeightTall: android.widget.RadioButton
    private lateinit var rbHeightExtraTall: android.widget.RadioButton
    private lateinit var switchQwertyNumberRow: com.google.android.material.switchmaterial.SwitchMaterial
    private lateinit var switchQwertySwipeTyping: com.google.android.material.switchmaterial.SwitchMaterial
    private lateinit var switchStreamingVoiceInput: com.google.android.material.switchmaterial.SwitchMaterial
    private lateinit var switchDebugDisplay: com.google.android.material.switchmaterial.SwitchMaterial

    // Downloader card widgets
    private lateinit var cardDownload: MaterialCardView
    private lateinit var tvDownloadInfo: TextView
    private lateinit var downloadProgress: LinearProgressIndicator
    private lateinit var btnDownloadModel: MaterialButton

    // Multi-Source Japanese Dictionary widgets
    private lateinit var tvTotalDictWordsBadge: TextView
    private lateinit var tvMultiDictSummary: TextView
    private lateinit var tvSlmStatusBadge: TextView
    private lateinit var tvSlmStatusDesc: TextView
    private lateinit var switchSlmRerankEnabled: com.google.android.material.switchmaterial.SwitchMaterial
    private lateinit var layoutRerankEngineSelector: LinearLayout
    private lateinit var rgRerankEngine: android.widget.RadioGroup
    private lateinit var rbEngineLocalSlm: android.widget.RadioButton
    private lateinit var rbEngineLocalSlm15B: android.widget.RadioButton
    private lateinit var rbEngineNone: android.widget.RadioButton
    private lateinit var sliderSlmAlpha: com.google.android.material.slider.Slider
    private lateinit var tvSlmAlphaValue: TextView
    private lateinit var tvSlmAlphaDesc: TextView
    private lateinit var sliderSlmLambda: com.google.android.material.slider.Slider
    private lateinit var tvSlmLambdaValue: TextView
    private lateinit var tvSlmLambdaDesc: TextView
    private lateinit var multiDictProgress: LinearProgressIndicator
    private lateinit var tvMultiDictStatus: TextView
    private lateinit var layoutDictUpdateBanner: LinearLayout
    private lateinit var tvDictUpdateBanner: TextView
    private lateinit var btnCheckDictUpdates: TextView
    private lateinit var btnDownloadAllDicts: MaterialButton
    private lateinit var btnToggleDictDetails: MaterialButton
    private lateinit var layoutDictDetailsContainer: LinearLayout
    private lateinit var layoutDictItemsContainer: LinearLayout

    // Progressive disclosure for Advanced / Developer tools
    private lateinit var layoutAdvancedHeader: LinearLayout
    private lateinit var tvAdvancedToggleIcon: TextView
    private lateinit var layoutAdvancedContent: LinearLayout

    private lateinit var tvConnectionMatrixBadge: TextView

    private lateinit var tvJapaneseAsrStatusBadge: TextView
    private lateinit var tvJapaneseAsrStats: TextView
    private lateinit var japaneseAsrProgress: LinearProgressIndicator
    private lateinit var tvJapaneseAsrDownloadStatus: TextView
    private lateinit var btnDownloadJapaneseAsr: MaterialButton

    private lateinit var tvEnglishSpellingStatusBadge: TextView
    private lateinit var tvEnglishSpellingSummary: TextView
    private lateinit var tvEnglishSpellingStats: TextView
    private lateinit var englishSpellingProgress: LinearProgressIndicator
    private lateinit var tvEnglishSpellingStatus: TextView
    private lateinit var btnDownloadEnglishSpelling: MaterialButton
    private lateinit var btnDeleteEnglishSpelling: MaterialButton

    private lateinit var tvEnglishAsrStatusBadge: TextView
    private lateinit var tvEnglishAsrSummary: TextView
    private lateinit var tvEnglishAsrStats: TextView
    private lateinit var englishAsrProgress: LinearProgressIndicator
    private lateinit var tvEnglishAsrStatus: TextView
    private lateinit var btnDownloadEnglishAsr: MaterialButton
    private lateinit var btnDeleteEnglishAsr: MaterialButton

    private lateinit var asrManager: AsrManager
    private val kanaKanjiEngine get() = AsrApp.instance.kanaKanjiEngine
    private lateinit var dictionaryRepository: DictionaryRepository
    private lateinit var hotwordsGenerator: HotwordsGenerator

    private var currentHotwordsFile: File? = null
    private var lastActiveDictionaryCount = -1
    private var isUpdatingUiFromPrefs = false
    private var lastHandledProvisioningEventId: Long =
        ProvisioningForegroundService.state.value.lastEvent?.eventId ?: 0L

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (!isGranted) {
            Toast.makeText(this, "音声入力を使用するにはマイク権限が必要です", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val app = AsrApp.instance
        asrManager = app.asrManager
        dictionaryRepository = app.dictionaryRepository
        hotwordsGenerator = app.hotwordsGenerator

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        initViews()
        setupListeners()
        observeProvisioningState()
        updateDictionaryBadge()
        updateImeStatusBadge()
        refreshMultiDictionaryUI()
        refreshConnectionMatrixBadge()
        updateKeyboardHeightUI()
        checkAndInitializeModel()
        checkBenchmarkIntent(intent)

        // Verify On-Device SLM Re-ranking with the user's motivating case
        lifecycleScope.launch(Dispatchers.Default) {
            while (AsrApp.instance.llmReranker.state == LlmReranker.State.INITIALIZING) {
                kotlinx.coroutines.delay(100)
            }
            if (AsrApp.instance.llmReranker.state == LlmReranker.State.READY) {
                val ctx = "Mozcは私の直感と挙動が"
                val reading = "ことなります"
                val cands = AsrApp.instance.kanaKanjiEngine.getCandidates(reading, ctx)
                Log.i("MainActivity", "=== ON-DEVICE SLM VERIFICATION ===")
                Log.i("MainActivity", "Context: '$ctx' + Reading: '$reading'")
                cands.take(5).forEachIndexed { idx, c ->
                    Log.i("MainActivity", "  Top-${idx + 1}: '${c.surface}' (source: ${c.source})")
                }

                val ctx2 = "事故の目撃者から事故直後の"
                val reading2 = "じょうきょう"
                val cands2 = AsrApp.instance.kanaKanjiEngine.getCandidates(reading2, ctx2)
                Log.i("MainActivity", "=== ON-DEVICE SLM TEST 2 ===")
                Log.i("MainActivity", "Context: '$ctx2' + Reading: '$reading2'")
                cands2.take(3).forEachIndexed { idx, c ->
                    Log.i("MainActivity", "  Top-${idx + 1}: '${c.surface}' (source: ${c.source})")
                }

                // Test 3: Empty Context with Context-Free SLM enabled
                val reading3 = "きょう"
                val cands3 = AsrApp.instance.kanaKanjiEngine.getCandidates(reading3, "")
                Log.i("MainActivity", "=== ON-DEVICE CONTEXT-FREE SLM TEST ===")
                Log.i("MainActivity", "Context: '' + Reading: '$reading3'")
                cands3.take(4).forEachIndexed { idx, c ->
                    Log.i("MainActivity", "  Top-${idx + 1}: '${c.surface}' (source: ${c.source})")
                }
            }
            withContext(Dispatchers.Main) {
                updateSlmStatusBadge()
            }
        }
    }

    private fun setupRerankEngineUI() {
        val current = KeyboardHeightManager.getRerankEngineType(this)

        rbEngineLocalSlm.isEnabled = true
        rbEngineLocalSlm15B.isEnabled = true
        rbEngineNone.isEnabled = true

        when (current) {
            RerankEngineType.LOCAL_SLM -> rbEngineLocalSlm.isChecked = true
            RerankEngineType.LOCAL_SLM_1_5B -> rbEngineLocalSlm15B.isChecked = true
            RerankEngineType.NONE -> rbEngineNone.isChecked = true
        }

        switchSlmRerankEnabled.isChecked = KeyboardHeightManager.isSlmRerankEnabled(this)

        rgRerankEngine.setOnCheckedChangeListener { _, checkedId ->
            if (isUpdatingUiFromPrefs) return@setOnCheckedChangeListener
            val selected = when (checkedId) {
                R.id.rbEngineLocalSlm15B -> RerankEngineType.LOCAL_SLM_1_5B
                R.id.rbEngineNone -> RerankEngineType.NONE
                else -> RerankEngineType.LOCAL_SLM
            }
            KeyboardHeightManager.setRerankEngineType(this, selected)
            isUpdatingUiFromPrefs = true
            try {
                if (selected == RerankEngineType.NONE) {
                    KeyboardHeightManager.setSlmRerankEnabled(this, false)
                    if (switchSlmRerankEnabled.isChecked) {
                        switchSlmRerankEnabled.isChecked = false
                    }
                } else {
                    KeyboardHeightManager.setSlmRerankEnabled(this, true)
                    if (!switchSlmRerankEnabled.isChecked) {
                        switchSlmRerankEnabled.isChecked = true
                    }
                    if (selected == RerankEngineType.LOCAL_SLM || selected == RerankEngineType.LOCAL_SLM_1_5B) {
                        AsrApp.instance.llmReranker.initializeIfAvailable(lifecycleScope, selected.modelFileName) {
                            runOnUiThread { updateSlmStatusBadge() }
                        }
                    }
                }
            } finally {
                isUpdatingUiFromPrefs = false
            }
            updateSlmStatusBadge()
            Toast.makeText(this, "AI 変換エンジン: ${selected.displayName} を選択しました", Toast.LENGTH_SHORT).show()
        }

        switchSlmRerankEnabled.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingUiFromPrefs) return@setOnCheckedChangeListener
            KeyboardHeightManager.setSlmRerankEnabled(this, isChecked)
            isUpdatingUiFromPrefs = true
            try {
                if (!isChecked) {
                    if (!rbEngineNone.isChecked) {
                        rbEngineNone.isChecked = true
                    }
                } else if (KeyboardHeightManager.getRerankEngineType(this) == RerankEngineType.NONE) {
                    val defaultEngine = RerankEngineType.LOCAL_SLM
                    KeyboardHeightManager.setRerankEngineType(this, defaultEngine)
                    rbEngineLocalSlm.isChecked = true
                    AsrApp.instance.llmReranker.initializeIfAvailable(lifecycleScope, defaultEngine.modelFileName) {
                        runOnUiThread { updateSlmStatusBadge() }
                    }
                }
            } finally {
                isUpdatingUiFromPrefs = false
            }
            updateSlmStatusBadge()
            val status = if (isChecked) "有効 (ON)" else "無効 (OFF: Mozc辞書・Viterbiのみ)"
            Toast.makeText(this, "AI 変換エンジン: $status", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateSlmStatusBadge() {
        val isRerankEnabled = KeyboardHeightManager.isSlmRerankEnabled(this)
        val selectedEngine = KeyboardHeightManager.getRerankEngineType(this)
        val reranker = AsrApp.instance.llmReranker

        if (!isRerankEnabled || selectedEngine == RerankEngineType.NONE) {
            layoutRerankEngineSelector.visibility = View.GONE
            tvSlmStatusBadge.text = "オフ (OFF)"
            tvSlmStatusBadge.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.badge_neutral_bg)
            )
            tvSlmStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.badge_neutral_text))
            tvSlmStatusDesc.text = "AI 変換エンジンは無効です (Mozc辞書・Viterbiのみ使用)"
            if (::tvCard1AiStatusBadge.isInitialized) {
                tvCard1AiStatusBadge.text = "AI: オフ (Viterbi)"
                tvCard1AiStatusBadge.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.badge_neutral_bg)
                )
                tvCard1AiStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.badge_neutral_text))
            }
            return
        }

        layoutRerankEngineSelector.visibility = View.VISIBLE

        when (selectedEngine) {
            RerankEngineType.NONE -> {}
            RerankEngineType.LOCAL_SLM, RerankEngineType.LOCAL_SLM_1_5B -> {
                val modelName = if (selectedEngine == RerankEngineType.LOCAL_SLM_1_5B) "Qwen2.5-1.5B" else "Qwen2.5-0.5B"
                val badgeText = if (selectedEngine == RerankEngineType.LOCAL_SLM_1_5B) "Qwen 1.5B" else "Qwen 0.5B"
                val modelFileName = selectedEngine.modelFileName
                when (reranker.state) {
                    LlmReranker.State.READY -> {
                        tvSlmStatusBadge.text = "$badgeText (Ready)"
                        tvSlmStatusBadge.backgroundTintList = ColorStateList.valueOf(
                            ContextCompat.getColor(this, R.color.badge_success_bg)
                        )
                        tvSlmStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.badge_success_text))
                        tvSlmStatusDesc.text = "$modelName 稼働中 (文脈補正・かな漢字変換)"
                        if (::tvCard1AiStatusBadge.isInitialized) {
                            tvCard1AiStatusBadge.text = "AI: $badgeText"
                            tvCard1AiStatusBadge.backgroundTintList = ColorStateList.valueOf(
                                ContextCompat.getColor(this, R.color.badge_success_bg)
                            )
                            tvCard1AiStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.badge_success_text))
                        }
                    }
                    LlmReranker.State.INITIALIZING -> {
                        tvSlmStatusBadge.text = "読込中..."
                        tvSlmStatusBadge.backgroundTintList = ColorStateList.valueOf(
                            ContextCompat.getColor(this, R.color.badge_neutral_bg)
                        )
                        tvSlmStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.badge_neutral_text))
                        tvSlmStatusDesc.text = "$modelName モデルをメモリにロードしています..."
                        if (::tvCard1AiStatusBadge.isInitialized) {
                            tvCard1AiStatusBadge.text = "AI: 読込中…"
                            tvCard1AiStatusBadge.backgroundTintList = ColorStateList.valueOf(
                                ContextCompat.getColor(this, R.color.badge_neutral_bg)
                            )
                            tvCard1AiStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.badge_neutral_text))
                        }
                    }
                    else -> {
                        if (reranker.isModelInstalled(modelFileName)) {
                            tvSlmStatusBadge.text = "待機中"
                            tvSlmStatusDesc.text = "$modelName 検出済み。タップで初期化"
                        } else {
                            tvSlmStatusBadge.text = "未導入"
                            tvSlmStatusDesc.text = "モデル未配置 (models/$modelFileName)"
                        }
                        tvSlmStatusBadge.backgroundTintList = ColorStateList.valueOf(
                            ContextCompat.getColor(this, R.color.badge_neutral_bg)
                        )
                        tvSlmStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.badge_neutral_text))
                        if (::tvCard1AiStatusBadge.isInitialized) {
                            tvCard1AiStatusBadge.text = "AI: $badgeText (待機中)"
                            tvCard1AiStatusBadge.backgroundTintList = ColorStateList.valueOf(
                                ContextCompat.getColor(this, R.color.badge_neutral_bg)
                            )
                            tvCard1AiStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.badge_neutral_text))
                        }
                    }
                }
            }
        }
    }

    private fun updateAlphaDisplay(alpha: Float) {
        val rounded = String.format(java.util.Locale.US, "%.2f", alpha)
        tvSlmAlphaValue.text = when {
            alpha in 0.15f..0.25f -> "α = $rounded (推奨値: Top-1 90.3%)"
            alpha == 0.00f -> "α = $rounded (純粋SLM)"
            alpha <= 0.10f -> "α = $rounded (SLM重視 + 辞書タイブレーク)"
            alpha <= 0.30f -> "α = $rounded (ハイブリッド標準)"
            alpha == 1.00f -> "α = $rounded (純粋Mozc)"
            else -> "α = $rounded"
        }
        tvSlmAlphaDesc.text = when {
            alpha in 0.15f..0.25f -> "言語モデル事前分布とMozc辞書コストの最適バランス (Top-1 90.3%)"
            alpha == 0.00f -> "SLMの対数尤度を100%信頼"
            alpha <= 0.10f -> "高精度を維持しつつ同スコア時にMozc辞書順位で補正"
            alpha == 1.00f -> "SLMスコアを無視し、従来のMozc順位のみを使用"
            else -> "Mozc順位 ${(alpha * 100).toInt()}% / SLM尤度 ${((1 - alpha) * 100).toInt()}% の配分"
        }
    }

    private fun updateLambdaDisplay(lambda: Float) {
        val rounded = String.format(java.util.Locale.US, "%.2f", lambda)
        tvSlmLambdaValue.text = when {
            lambda in 0.45f..0.55f -> "λ = $rounded (最適値: Top-1 90.3%)"
            lambda <= 0.30f -> "λ = $rounded (言語モデル事前確率重視)"
            lambda >= 1.50f -> "λ = $rounded (入力キータッチ厳密一致重視)"
            else -> "λ = $rounded (バランス)"
        }
        tvSlmLambdaDesc.text = when {
            lambda in 0.45f..0.55f -> "タッチ座標・濁音落ちの観測ペナルティと言語モデル尤度の最適バランス (最適値: 0.50)"
            lambda <= 0.30f -> "多少の入力ミスがあっても文脈に合った単語を強力に推論"
            lambda >= 1.50f -> "入力された打鍵座標に厳密に従い、勝手な修正を抑制"
            else -> "入力忠実度と文脈補正のハイブリッド"
        }
    }

    override fun onResume() {
        super.onResume()
        updateDictionaryBadge()
        updateImeStatusBadge()
        refreshMultiDictionaryUI()
        refreshConnectionMatrixBadge()
        refreshJapaneseAsrUI()
        refreshEnglishSpellingUI()
        refreshEnglishAsrUI()
        updateModelStatusBadge()
        updateKeyboardHeightUI()
        isUpdatingUiFromPrefs = true
        try {
            switchStreamingVoiceInput.isChecked = KeyboardHeightManager.isStreamingVoiceInputEnabled(this)
            switchDebugDisplay.isChecked = KeyboardHeightManager.isDebugDisplayEnabled(this)
            switchSlmRerankEnabled.isChecked = KeyboardHeightManager.isSlmRerankEnabled(this)
            val alpha = KeyboardHeightManager.getSlmWeightAlpha(this)
            sliderSlmAlpha.value = alpha.coerceIn(0.00f, 1.00f)
            updateAlphaDisplay(alpha)
            val lambda = KeyboardHeightManager.getSlmLambdaObs(this)
            sliderSlmLambda.value = lambda.coerceIn(0.10f, 2.00f)
            updateLambdaDisplay(lambda)
        } finally {
            isUpdatingUiFromPrefs = false
        }
        updateSlmStatusBadge()
        val currentActiveCount = dictionaryRepository.getActive().size
        if (lastActiveDictionaryCount != -1 && lastActiveDictionaryCount != currentActiveCount) {
            // Dictionary updated, reload hotwords into ASR engine
            lastActiveDictionaryCount = currentActiveCount
            currentHotwordsFile = hotwordsGenerator.generate(dictionaryRepository.getActive())
            lifecycleScope.launch(Dispatchers.Default) {
                asrManager.reload(currentHotwordsFile)
            }
        }
        lifecycleScope.launch(Dispatchers.Default) {
            val engine = KanaKanjiEngine.getInstance(this@MainActivity)
            val testPhrases = listOf("むかってる", "きょうはいいてんき", "さぎょうちゅうですので", "つとめております")
            for (p in testPhrases) {
                val cands = engine.getBaseCandidates(p)
                Log.i("KanaKanjiOnDevice", "ON-DEVICE VERIFY: '$p' -> ${cands.map { "${it.surface}(${it.source})" }}")
            }
        }
    }

    private fun updateDictionaryBadge() {
        val activeCount = dictionaryRepository.getActive().size
        lastActiveDictionaryCount = activeCount
        tvDictionaryStatus.text = "登録単語: ${activeCount}語"
    }

    private fun updateImeStatusBadge() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        val enabledMethods = imm.enabledInputMethodList
        val isEnabled = enabledMethods.any { it.packageName == packageName }

        if (isEnabled) {
            tvImeStatusBadge.text = "IME: 有効"
            tvImeStatusBadge.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.badge_success_bg)
            )
            tvImeStatusBadge.setTextColor(
                ContextCompat.getColor(this, R.color.badge_success_text)
            )
        } else {
            tvImeStatusBadge.text = "IME: 未有効"
            tvImeStatusBadge.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.badge_neutral_bg)
            )
            tvImeStatusBadge.setTextColor(
                ContextCompat.getColor(this, R.color.badge_neutral_text)
            )
        }
    }

    private fun initViews() {
        tvModelStatus = findViewById(R.id.tvModelStatus)
        tvDictionaryStatus = findViewById(R.id.tvDictionaryStatus)
        tvCard1AiStatusBadge = findViewById(R.id.tvCard1AiStatusBadge)
        btnOpenDictionary = findViewById(R.id.btnOpenDictionary)
        tvOssLicenses = findViewById(R.id.tvOssLicenses)

        tvImeStatusBadge = findViewById(R.id.tvImeStatusBadge)
        btnEnableIme = findViewById(R.id.btnEnableIme)
        btnSelectIme = findViewById(R.id.btnSelectIme)
        etKeyboardTest = findViewById(R.id.etKeyboardTest)

        rgKeyboardHeight = findViewById(R.id.rgKeyboardHeight)
        rbHeightCompact = findViewById(R.id.rbHeightCompact)
        rbHeightShort = findViewById(R.id.rbHeightShort)
        rbHeightNormal = findViewById(R.id.rbHeightNormal)
        rbHeightTall = findViewById(R.id.rbHeightTall)
        rbHeightExtraTall = findViewById(R.id.rbHeightExtraTall)
        switchQwertyNumberRow = findViewById(R.id.switchQwertyNumberRow)
        switchQwertySwipeTyping = findViewById(R.id.switchQwertySwipeTyping)
        switchStreamingVoiceInput = findViewById(R.id.switchStreamingVoiceInput)
        switchDebugDisplay = findViewById(R.id.switchDebugDisplay)

        cardDownload = findViewById(R.id.cardDownload)
        tvDownloadInfo = findViewById(R.id.tvDownloadInfo)
        downloadProgress = findViewById(R.id.downloadProgress)
        btnDownloadModel = findViewById(R.id.btnDownloadModel)

        tvTotalDictWordsBadge = findViewById(R.id.tvTotalDictWordsBadge)
        tvMultiDictSummary = findViewById(R.id.tvMultiDictSummary)
        tvSlmStatusBadge = findViewById(R.id.tvSlmStatusBadge)
        tvSlmStatusDesc = findViewById(R.id.tvSlmStatusDesc)
        switchSlmRerankEnabled = findViewById(R.id.switchSlmRerankEnabled)
        layoutRerankEngineSelector = findViewById(R.id.layoutRerankEngineSelector)
        rgRerankEngine = findViewById(R.id.rgRerankEngine)
        rbEngineLocalSlm = findViewById(R.id.rbEngineLocalSlm)
        rbEngineLocalSlm15B = findViewById(R.id.rbEngineLocalSlm15B)
        rbEngineNone = findViewById(R.id.rbEngineNone)
        setupRerankEngineUI()
        sliderSlmAlpha = findViewById(R.id.sliderSlmAlpha)
        tvSlmAlphaValue = findViewById(R.id.tvSlmAlphaValue)
        tvSlmAlphaDesc = findViewById(R.id.tvSlmAlphaDesc)
        val currentAlpha = KeyboardHeightManager.getSlmWeightAlpha(this)
        sliderSlmAlpha.value = currentAlpha.coerceIn(0.00f, 1.00f)
        updateAlphaDisplay(currentAlpha)

        sliderSlmLambda = findViewById(R.id.sliderSlmLambda)
        tvSlmLambdaValue = findViewById(R.id.tvSlmLambdaValue)
        tvSlmLambdaDesc = findViewById(R.id.tvSlmLambdaDesc)
        val currentLambda = KeyboardHeightManager.getSlmLambdaObs(this)
        sliderSlmLambda.value = currentLambda.coerceIn(0.10f, 2.00f)
        updateLambdaDisplay(currentLambda)
        multiDictProgress = findViewById(R.id.multiDictProgress)
        tvMultiDictStatus = findViewById(R.id.tvMultiDictStatus)
        layoutDictUpdateBanner = findViewById(R.id.layoutDictUpdateBanner)
        tvDictUpdateBanner = findViewById(R.id.tvDictUpdateBanner)
        btnCheckDictUpdates = findViewById(R.id.btnCheckDictUpdates)
        btnDownloadAllDicts = findViewById(R.id.btnDownloadAllDicts)
        btnToggleDictDetails = findViewById(R.id.btnToggleDictDetails)
        layoutDictDetailsContainer = findViewById(R.id.layoutDictDetailsContainer)
        layoutDictItemsContainer = findViewById(R.id.layoutDictItemsContainer)

        layoutAdvancedHeader = findViewById(R.id.layoutAdvancedHeader)
        tvAdvancedToggleIcon = findViewById(R.id.tvAdvancedToggleIcon)
        layoutAdvancedContent = findViewById(R.id.layoutAdvancedContent)

        tvConnectionMatrixBadge = findViewById(R.id.tvConnectionMatrixBadge)

        tvJapaneseAsrStatusBadge = findViewById(R.id.tvJapaneseAsrStatusBadge)
        tvJapaneseAsrStats = findViewById(R.id.tvJapaneseAsrStats)
        japaneseAsrProgress = findViewById(R.id.japaneseAsrProgress)
        tvJapaneseAsrDownloadStatus = findViewById(R.id.tvJapaneseAsrDownloadStatus)
        btnDownloadJapaneseAsr = findViewById(R.id.btnDownloadJapaneseAsr)

        tvEnglishSpellingStatusBadge = findViewById(R.id.tvEnglishSpellingStatusBadge)
        tvEnglishSpellingSummary = findViewById(R.id.tvEnglishSpellingSummary)
        tvEnglishSpellingStats = findViewById(R.id.tvEnglishSpellingStats)
        englishSpellingProgress = findViewById(R.id.englishSpellingProgress)
        tvEnglishSpellingStatus = findViewById(R.id.tvEnglishSpellingStatus)
        btnDownloadEnglishSpelling = findViewById(R.id.btnDownloadEnglishSpelling)
        btnDeleteEnglishSpelling = findViewById(R.id.btnDeleteEnglishSpelling)

        tvEnglishAsrStatusBadge = findViewById(R.id.tvEnglishAsrStatusBadge)
        tvEnglishAsrSummary = findViewById(R.id.tvEnglishAsrSummary)
        tvEnglishAsrStats = findViewById(R.id.tvEnglishAsrStats)
        englishAsrProgress = findViewById(R.id.englishAsrProgress)
        tvEnglishAsrStatus = findViewById(R.id.tvEnglishAsrStatus)
        btnDownloadEnglishAsr = findViewById(R.id.btnDownloadEnglishAsr)
        btnDeleteEnglishAsr = findViewById(R.id.btnDeleteEnglishAsr)
    }

    private fun setupListeners() {
        btnOpenDictionary.setOnClickListener {
            startActivity(Intent(this, DictionaryActivity::class.java))
        }

        setupMultiDictionaryUI()
        setupJapaneseAsrUI()
        setupEnglishSpellingUI()
        setupEnglishAsrUI()

        btnToggleDictDetails.setOnClickListener {
            val isVisible = layoutDictDetailsContainer.visibility == View.VISIBLE
            layoutDictDetailsContainer.visibility = if (isVisible) View.GONE else View.VISIBLE
            btnToggleDictDetails.text = if (isVisible) {
                "個別の拡張辞書・音声モデルを管理 ▼"
            } else {
                "個別の拡張辞書・音声モデルを閉じる ▲"
            }
        }

        layoutAdvancedHeader.setOnClickListener {
            val isVisible = layoutAdvancedContent.visibility == View.VISIBLE
            layoutAdvancedContent.visibility = if (isVisible) View.GONE else View.VISIBLE
            tvAdvancedToggleIcon.text = if (isVisible) "▼" else "▲"
        }

        rgKeyboardHeight.setOnCheckedChangeListener { _, checkedId ->
            if (isUpdatingUiFromPrefs) return@setOnCheckedChangeListener
            val preset = when (checkedId) {
                R.id.rbHeightCompact -> KeyboardHeightManager.HeightPreset.COMPACT
                R.id.rbHeightShort -> KeyboardHeightManager.HeightPreset.SHORT
                R.id.rbHeightNormal -> KeyboardHeightManager.HeightPreset.NORMAL
                R.id.rbHeightTall -> KeyboardHeightManager.HeightPreset.TALL
                R.id.rbHeightExtraTall -> KeyboardHeightManager.HeightPreset.EXTRA_TALL
                else -> KeyboardHeightManager.HeightPreset.NORMAL
            }
            KeyboardHeightManager.setPreset(this, preset)
            Toast.makeText(this, "キーボードの高さを「${preset.displayName}」に設定しました", Toast.LENGTH_SHORT).show()
        }

        switchQwertyNumberRow.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingUiFromPrefs) return@setOnCheckedChangeListener
            KeyboardHeightManager.setQwertyNumberRowEnabled(this, isChecked)
            val status = if (isChecked) "表示 (ON)" else "非表示 (OFF)"
            Toast.makeText(this, "英語QWERTYの数字行: $status", Toast.LENGTH_SHORT).show()
        }

        switchQwertySwipeTyping.isChecked = KeyboardHeightManager.isQwertySwipeTypingEnabled(this)
        switchQwertySwipeTyping.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingUiFromPrefs) return@setOnCheckedChangeListener
            KeyboardHeightManager.setQwertySwipeTypingEnabled(this, isChecked)
            val status = if (isChecked) "有効 (ON: FUTO Swipe)" else "無効 (OFF)"
            Toast.makeText(this, "英語QWERTYスワイプ入力: $status", Toast.LENGTH_SHORT).show()
        }

        switchStreamingVoiceInput.isChecked = KeyboardHeightManager.isStreamingVoiceInputEnabled(this)
        switchStreamingVoiceInput.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingUiFromPrefs) return@setOnCheckedChangeListener
            KeyboardHeightManager.setStreamingVoiceInputEnabled(this, isChecked)
            val status = if (isChecked) "有効 (ON: リアルタイム逐次入力 / ポーズ確定OFF)" else "無効 (OFF: 従来のポーズ確定入力)"
            Toast.makeText(this, "ストリーミング音声入力: $status", Toast.LENGTH_SHORT).show()
        }

        switchDebugDisplay.isChecked = KeyboardHeightManager.isDebugDisplayEnabled(this)
        switchDebugDisplay.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingUiFromPrefs) return@setOnCheckedChangeListener
            KeyboardHeightManager.setDebugDisplayEnabled(this, isChecked)
            val status = if (isChecked) "有効 (ON: SLMリランク時にデバッグバーにバッジ表示)" else "無効 (OFF)"
            Toast.makeText(this, "デバッグ表示: $status", Toast.LENGTH_SHORT).show()
        }

        sliderSlmAlpha.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                KeyboardHeightManager.setSlmWeightAlpha(this, value)
                updateAlphaDisplay(value)
            }
        }

        sliderSlmLambda.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                KeyboardHeightManager.setSlmLambdaObs(this, value)
                updateLambdaDisplay(value)
            }
        }

        btnEnableIme.setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }

        btnSelectIme.setOnClickListener {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showInputMethodPicker()
        }

        btnDownloadModel.setOnClickListener {
            startModelDownload()
        }

        tvOssLicenses.setOnClickListener {
            showOssLicensesDialog()
        }
    }

    private fun updateModelStatusBadge(japaneseReady: Boolean? = null) {
        val jaReady = japaneseReady ?: asrManager.isModelAvailable(AsrLanguage.JAPANESE)
        val enReady = asrManager.isEnglishModelAvailable()
        tvModelStatus.text = when {
            jaReady && enReady -> "音声認識: 日・英 Ready"
            jaReady && !enReady -> "音声認識: 日Ready / 英未導入"
            !jaReady && enReady -> "音声認識: 日未導入 / 英Ready"
            else -> "音声認識: 未導入"
        }
    }

    private fun setupJapaneseAsrUI() {
        btnDownloadJapaneseAsr.setOnClickListener {
            startModelDownload()
        }
        refreshJapaneseAsrUI()
    }

    private fun refreshJapaneseAsrUI() {
        val isInstalled = asrManager.isModelAvailable(AsrLanguage.JAPANESE)
        val dir = asrManager.getStorageModelDir()
        if (isInstalled) {
            val totalSize = (dir.listFiles()?.sumOf { it.length() } ?: 0L)
            val sizeMb = String.format(java.util.Locale.US, "%.1f", totalSize / (1024.0 * 1024.0))
            tvJapaneseAsrStatusBadge.text = "導入済"
            tvJapaneseAsrStatusBadge.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.badge_success_bg))
            tvJapaneseAsrStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.badge_success_text))
            tvJapaneseAsrStats.text = "約160MB (導入済: ${sizeMb} MB, Apache-2.0)"
            btnDownloadJapaneseAsr.text = "再取得"
        } else {
            tvJapaneseAsrStatusBadge.text = "未取得"
            tvJapaneseAsrStatusBadge.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.badge_neutral_bg))
            tvJapaneseAsrStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.badge_neutral_text))
            tvJapaneseAsrStats.text = "約160MB (Apache-2.0)"
            btnDownloadJapaneseAsr.text = "取得"
        }
    }

    private fun checkAndInitializeModel() {
        refreshJapaneseAsrUI()
        if (asrManager.isModelAvailable()) {
            cardDownload.visibility = View.GONE
            tvModelStatus.text = "音声認識: 読込中…"

            lifecycleScope.launch(Dispatchers.Default) {
                currentHotwordsFile = hotwordsGenerator.generate(dictionaryRepository.getActive())
                val success = asrManager.initialize(currentHotwordsFile)
                withContext(Dispatchers.Main) {
                    if (success) {
                        updateModelStatusBadge(japaneseReady = true)
                    } else {
                        tvModelStatus.text = "音声認識: エラー"
                    }
                    refreshJapaneseAsrUI()
                }
            }
        } else {
            // Model files missing
            cardDownload.visibility = View.VISIBLE
            updateModelStatusBadge(japaneseReady = false)
        }
    }

    private fun startBackgroundProvisioning(kind: ProvisioningTaskKind, dictId: String? = null) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1002)
        }
        ProvisioningForegroundService.enqueue(this, kind, dictId)
    }

    private fun observeProvisioningState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ProvisioningForegroundService.state.collect { state ->
                    renderProvisioningState(state)
                }
            }
        }
    }

    private fun renderProvisioningState(state: ProvisioningUiState) {
        val active = state.activeTask

        // 1. Japanese ASR model
        val jaActive = active?.kind == ProvisioningTaskKind.JAPANESE_ASR_MODEL
        val jaQueued = state.isTaskActiveOrQueued(ProvisioningTaskKind.JAPANESE_ASR_MODEL)
        btnDownloadModel.isEnabled = !jaQueued
        btnDownloadJapaneseAsr.isEnabled = !jaQueued
        if (jaActive) {
            downloadProgress.visibility = View.VISIBLE
            downloadProgress.progress = state.progress
            tvDownloadInfo.text = state.statusText.ifEmpty { "バックグラウンドで準備中..." }
            japaneseAsrProgress.visibility = View.VISIBLE
            japaneseAsrProgress.progress = state.progress
            tvJapaneseAsrDownloadStatus.visibility = View.VISIBLE
            tvJapaneseAsrDownloadStatus.text = state.statusText.ifEmpty { "バックグラウンドで準備中..." }
        } else if (jaQueued) {
            downloadProgress.visibility = View.VISIBLE
            downloadProgress.progress = 0
            tvDownloadInfo.text = "待機中 (他の処理の完了後に開始)..."
            japaneseAsrProgress.visibility = View.VISIBLE
            japaneseAsrProgress.progress = 0
            tvJapaneseAsrDownloadStatus.visibility = View.VISIBLE
            tvJapaneseAsrDownloadStatus.text = "待機中..."
        } else {
            downloadProgress.visibility = View.GONE
            japaneseAsrProgress.visibility = View.GONE
            tvJapaneseAsrDownloadStatus.visibility = View.GONE
        }

        // 2. Multi-dictionary download & compile
        val dictActive = active?.kind == ProvisioningTaskKind.SINGLE_DICTIONARY ||
            active?.kind == ProvisioningTaskKind.ALL_OR_OUTDATED_DICTIONARIES
        val dictQueued = state.isTaskActiveOrQueued(ProvisioningTaskKind.SINGLE_DICTIONARY) ||
            state.isTaskActiveOrQueued(ProvisioningTaskKind.ALL_OR_OUTDATED_DICTIONARIES)
        btnDownloadAllDicts.isEnabled = !dictQueued
        if (dictActive) {
            multiDictProgress.visibility = View.VISIBLE
            multiDictProgress.isIndeterminate = false
            multiDictProgress.progress = state.progress
            tvMultiDictStatus.visibility = View.VISIBLE
            tvMultiDictStatus.text = state.statusText.ifEmpty { "バックグラウンドで処理中..." }
        } else if (dictQueued) {
            multiDictProgress.visibility = View.VISIBLE
            multiDictProgress.isIndeterminate = true
            tvMultiDictStatus.visibility = View.VISIBLE
            tvMultiDictStatus.text = "待機中 (他の処理の完了後に開始)..."
        } else {
            multiDictProgress.visibility = View.GONE
            tvMultiDictStatus.visibility = View.GONE
        }

        // 3. English Spelling model
        val enSpellActive = active?.kind == ProvisioningTaskKind.ENGLISH_SPELLING_MODEL
        val enSpellQueued = state.isTaskActiveOrQueued(ProvisioningTaskKind.ENGLISH_SPELLING_MODEL)
        btnDownloadEnglishSpelling.isEnabled = !enSpellQueued
        btnDeleteEnglishSpelling.isEnabled = !enSpellQueued
        if (enSpellActive) {
            englishSpellingProgress.visibility = View.VISIBLE
            englishSpellingProgress.progress = state.progress
            tvEnglishSpellingStatus.visibility = View.VISIBLE
            tvEnglishSpellingStatus.text = state.statusText.ifEmpty { "バックグラウンドで準備中..." }
        } else if (enSpellQueued) {
            englishSpellingProgress.visibility = View.VISIBLE
            englishSpellingProgress.progress = 0
            tvEnglishSpellingStatus.visibility = View.VISIBLE
            tvEnglishSpellingStatus.text = "待機中..."
        } else {
            englishSpellingProgress.visibility = View.GONE
            tvEnglishSpellingStatus.visibility = View.GONE
        }

        // 4. English ASR model
        val enAsrActive = active?.kind == ProvisioningTaskKind.ENGLISH_ASR_MODEL
        val enAsrQueued = state.isTaskActiveOrQueued(ProvisioningTaskKind.ENGLISH_ASR_MODEL)
        btnDownloadEnglishAsr.isEnabled = !enAsrQueued
        btnDeleteEnglishAsr.isEnabled = !enAsrQueued
        if (enAsrActive) {
            englishAsrProgress.visibility = View.VISIBLE
            englishAsrProgress.progress = state.progress
            tvEnglishAsrStatus.visibility = View.VISIBLE
            tvEnglishAsrStatus.text = state.statusText.ifEmpty { "バックグラウンドで準備中..." }
        } else if (enAsrQueued) {
            englishAsrProgress.visibility = View.VISIBLE
            englishAsrProgress.progress = 0
            tvEnglishAsrStatus.visibility = View.VISIBLE
            tvEnglishAsrStatus.text = "待機中..."
        } else {
            englishAsrProgress.visibility = View.GONE
            tvEnglishAsrStatus.visibility = View.GONE
        }

        // 5. Handle completion / error event once
        val event = state.lastEvent
        if (event != null && event.eventId > lastHandledProvisioningEventId) {
            lastHandledProvisioningEventId = event.eventId
            Toast.makeText(this, event.message, Toast.LENGTH_LONG).show()
            when (event.task.kind) {
                ProvisioningTaskKind.JAPANESE_ASR_MODEL -> {
                    if (event.success) {
                        checkAndInitializeModel()
                        refreshMultiDictionaryUI()
                    } else {
                        tvDownloadInfo.text = event.message
                    }
                }
                ProvisioningTaskKind.SINGLE_DICTIONARY,
                ProvisioningTaskKind.ALL_OR_OUTDATED_DICTIONARIES -> {
                    refreshMultiDictionaryUI()
                    refreshConnectionMatrixBadge()
                    refreshEnglishSpellingUI()
                }
                ProvisioningTaskKind.ENGLISH_SPELLING_MODEL -> {
                    refreshEnglishSpellingUI()
                }
                ProvisioningTaskKind.ENGLISH_ASR_MODEL -> {
                    refreshEnglishAsrUI()
                }
            }
        }
    }

    private fun startModelDownload() {
        startBackgroundProvisioning(ProvisioningTaskKind.JAPANESE_ASR_MODEL)
    }

    private fun setupMultiDictionaryUI() {
        btnDownloadAllDicts.setOnClickListener {
            startDownloadAllDicts()
        }
        btnCheckDictUpdates.setOnClickListener {
            triggerDictionaryUpdateCheck(force = true)
        }
        refreshMultiDictionaryUI()
        triggerDictionaryUpdateCheck(force = false)
    }

    private fun triggerDictionaryUpdateCheck(force: Boolean) {
        if (force) {
            tvDictUpdateBanner.text = "ネット上の最新辞書更新日を確認中..."
            btnCheckDictUpdates.isEnabled = false
        }
        lifecycleScope.launch {
            val statuses = AsrApp.instance.dictionaryUpdateTracker.checkRemoteUpdates(
                force = force,
                postSystemNotification = true
            )
            withContext(Dispatchers.Main) {
                btnCheckDictUpdates.isEnabled = true
                refreshMultiDictionaryUI()
                if (force) {
                    val outdatedCount = statuses.count { it.state == DictUpdateState.UPDATE_AVAILABLE }
                    val msg = if (outdatedCount > 0) {
                        "🔔 ${outdatedCount}件の辞書に新しい更新が見つかりました"
                    } else {
                        "辞書の更新日確認が完了しました（導入済みの辞書は最新です）"
                    }
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun bindDictItemDatesAndStatus(
        statusInfo: DictionaryStatusInfo,
        wordCountLabel: String,
        tvStatusBadge: TextView,
        tvDates: TextView,
        btnAction: MaterialButton
    ) {
        tvDates.text = statusInfo.formatComparisonLine()
        when (statusInfo.state) {
            DictUpdateState.UPDATE_AVAILABLE -> {
                tvStatusBadge.text = "更新あり ($wordCountLabel)"
                tvStatusBadge.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.badge_warning_bg))
                tvStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.badge_warning_text))
                tvDates.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.badge_warning_bg))
                tvDates.setTextColor(ContextCompat.getColor(this, R.color.badge_warning_text))
                btnAction.text = "更新"
            }
            DictUpdateState.UP_TO_DATE -> {
                tvStatusBadge.text = "最新 ($wordCountLabel)"
                tvStatusBadge.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.badge_success_bg))
                tvStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.badge_success_text))
                tvDates.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.badge_info_bg))
                tvDates.setTextColor(ContextCompat.getColor(this, R.color.badge_info_text))
                btnAction.text = "再取得"
            }
            DictUpdateState.INSTALLED_REMOTE_UNKNOWN -> {
                tvStatusBadge.text = "導入済 ($wordCountLabel)"
                tvStatusBadge.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.badge_success_bg))
                tvStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.badge_success_text))
                tvDates.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.badge_neutral_bg))
                tvDates.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                btnAction.text = "再取得"
            }
            DictUpdateState.NOT_INSTALLED -> {
                tvStatusBadge.text = "未取得"
                tvStatusBadge.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.badge_neutral_bg))
                tvStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.badge_neutral_text))
                tvDates.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.badge_neutral_bg))
                tvDates.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                btnAction.text = "取得"
            }
        }
    }

    private fun refreshMultiDictionaryUI() {
        val manager = AsrApp.instance.multiDictionaryManager
        val tracker = AsrApp.instance.dictionaryUpdateTracker
        val allStatuses = tracker.getAllStatusInfo()
        val outdatedList = allStatuses.filter { it.state == DictUpdateState.UPDATE_AVAILABLE }
        val installedList = allStatuses.filter { it.isInstalled }

        val totalCombined = manager.getTotalWordCount()
        val totalFormatted = String.format(java.util.Locale.US, "%,d", totalCombined)

        if (outdatedList.isNotEmpty()) {
            val latestRemoteStr = DictionaryUpdateTracker.formatDateMillis(outdatedList.maxOf { it.remoteUpdatedAtMillis })
            tvDictUpdateBanner.text = "🔔 ${outdatedList.size}件の辞書に更新があります (ネット最新: $latestRemoteStr)"
            layoutDictUpdateBanner.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.badge_warning_bg))
            tvDictUpdateBanner.setTextColor(ContextCompat.getColor(this, R.color.badge_warning_text))
        } else if (installedList.isNotEmpty() && installedList.all { it.state == DictUpdateState.UP_TO_DATE }) {
            val latestRemoteStr = DictionaryUpdateTracker.formatDateMillis(allStatuses.maxOfOrNull { it.remoteUpdatedAtMillis } ?: 0L)
            tvDictUpdateBanner.text = "✓ 導入済みの辞書はすべて最新です (ネット最新: $latestRemoteStr)"
            layoutDictUpdateBanner.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.badge_success_bg))
            tvDictUpdateBanner.setTextColor(ContextCompat.getColor(this, R.color.badge_success_text))
        } else {
            val maxRemote = allStatuses.maxOfOrNull { it.remoteUpdatedAtMillis } ?: 0L
            if (maxRemote > 0L) {
                val latestRemoteStr = DictionaryUpdateTracker.formatDateMillis(maxRemote)
                tvDictUpdateBanner.text = "ネット上の最新辞書公開日: $latestRemoteStr"
            } else {
                tvDictUpdateBanner.text = "ネット上の辞書更新日を確認中..."
            }
            layoutDictUpdateBanner.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.badge_neutral_bg))
            tvDictUpdateBanner.setTextColor(ContextCompat.getColor(this, R.color.badge_neutral_text))
        }

        if (totalCombined > 0) {
            if (outdatedList.isNotEmpty()) {
                tvTotalDictWordsBadge.text = "更新あり: ${outdatedList.size}件"
                tvTotalDictWordsBadge.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.badge_warning_bg))
                tvTotalDictWordsBadge.setTextColor(ContextCompat.getColor(this, R.color.badge_warning_text))
                btnDownloadAllDicts.text = "更新がある辞書を一括アップデート (${outdatedList.size}件)"
            } else {
                tvTotalDictWordsBadge.text = "辞書: $totalFormatted 語"
                tvTotalDictWordsBadge.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.badge_success_bg))
                tvTotalDictWordsBadge.setTextColor(ContextCompat.getColor(this, R.color.badge_success_text))
                btnDownloadAllDicts.text = "全拡張辞書を最新版に更新 ($totalFormatted 語)"
            }
        } else {
            tvTotalDictWordsBadge.text = "辞書: 未取得"
            tvTotalDictWordsBadge.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.badge_neutral_bg))
            tvTotalDictWordsBadge.setTextColor(ContextCompat.getColor(this, R.color.badge_neutral_text))
            btnDownloadAllDicts.text = "推奨拡張辞書を一括ダウンロード (約400万語)"
        }

        layoutDictItemsContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)

        for (dictType in DictionaryType.values()) {
            val itemView = inflater.inflate(R.layout.item_multi_dict, layoutDictItemsContainer, false)
            val tvTitle = itemView.findViewById<TextView>(R.id.tvDictTitle)
            val tvStatusBadge = itemView.findViewById<TextView>(R.id.tvDictStatusBadge)
            val tvDesc = itemView.findViewById<TextView>(R.id.tvDictDescription)
            val tvDates = itemView.findViewById<TextView>(R.id.tvDictDates)
            val tvStats = itemView.findViewById<TextView>(R.id.tvDictStats)
            val btnAction = itemView.findViewById<MaterialButton>(R.id.btnDictAction)

            tvTitle.text = dictType.displayName
            tvDesc.text = dictType.description
            tvStats.text = "${dictType.estimatedWords} (${dictType.estimatedDownloadSize})"

            val statusInfo = tracker.getStatusInfo(dictType.id)
            val countFormatted = String.format(java.util.Locale.US, "%,d語", statusInfo.wordCount)
            bindDictItemDatesAndStatus(
                statusInfo = statusInfo,
                wordCountLabel = countFormatted,
                tvStatusBadge = tvStatusBadge,
                tvDates = tvDates,
                btnAction = btnAction
            )

            btnAction.setOnClickListener {
                startDownloadSingleDict(dictType)
            }

            layoutDictItemsContainer.addView(itemView)
        }
    }

    private fun startDownloadSingleDict(type: DictionaryType) {
        startBackgroundProvisioning(ProvisioningTaskKind.SINGLE_DICTIONARY, type.id)
    }

    private fun startDownloadBaseDict() {
        startDownloadSingleDict(DictionaryType.MOZC_GENERAL)
    }

    private fun startDownloadAllDicts() {
        startBackgroundProvisioning(ProvisioningTaskKind.ALL_OR_OUTDATED_DICTIONARIES)
    }

    private fun refreshConnectionMatrixBadge() {
        val manager = AsrApp.instance.connectionDataManager
        val isInstalled = manager.isInstalled()
        if (isInstalled) {
            tvConnectionMatrixBadge.text = "連接: 導入済"
            tvConnectionMatrixBadge.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.badge_success_bg))
            tvConnectionMatrixBadge.setTextColor(ContextCompat.getColor(this, R.color.badge_success_text))
        } else {
            tvConnectionMatrixBadge.text = "連接: 未導入"
            tvConnectionMatrixBadge.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.badge_neutral_bg))
            tvConnectionMatrixBadge.setTextColor(ContextCompat.getColor(this, R.color.badge_neutral_text))
        }
    }

    private fun setupEnglishSpellingUI() {
        btnDownloadEnglishSpelling.setOnClickListener {
            startDownloadEnglishSpelling()
        }
        btnDeleteEnglishSpelling.setOnClickListener {
            val dir = File(getExternalFilesDir(null), "english_spelling")
            if (dir.exists()) {
                dir.deleteRecursively()
            }
            Toast.makeText(this, "英語スペル補正モデルを削除しました", Toast.LENGTH_SHORT).show()
            refreshEnglishSpellingUI()
        }
        refreshEnglishSpellingUI()
    }

    private fun refreshEnglishSpellingUI() {
        val downloader = EnglishSpellDownloader(this)
        val isInstalled = downloader.isDownloaded()
        val dir = File(getExternalFilesDir(null), "english_spelling")
        if (isInstalled) {
            val totalSize = (dir.listFiles()?.sumOf { it.length() } ?: 0L)
            val sizeMb = String.format(java.util.Locale.US, "%.1f", totalSize / (1024.0 * 1024.0))
            tvEnglishSpellingStatusBadge.text = "導入済"
            tvEnglishSpellingStatusBadge.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.badge_success_bg))
            tvEnglishSpellingStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.badge_success_text))
            tvEnglishSpellingStats.text = "約27MB (導入済: ${sizeMb} MB)"
            btnDownloadEnglishSpelling.text = "再取得"
            btnDeleteEnglishSpelling.visibility = View.VISIBLE
        } else {
            tvEnglishSpellingStatusBadge.text = "未取得"
            tvEnglishSpellingStatusBadge.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.badge_neutral_bg))
            tvEnglishSpellingStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.badge_neutral_text))
            tvEnglishSpellingStats.text = "約27MB"
            btnDownloadEnglishSpelling.text = "取得"
            btnDeleteEnglishSpelling.visibility = View.GONE
        }
    }

    private fun startDownloadEnglishSpelling() {
        startBackgroundProvisioning(ProvisioningTaskKind.ENGLISH_SPELLING_MODEL)
    }

    private fun setupEnglishAsrUI() {
        btnDownloadEnglishAsr.setOnClickListener {
            startDownloadEnglishAsr()
        }
        btnDeleteEnglishAsr.setOnClickListener {
            asrManager.release(AsrLanguage.ENGLISH)
            EnglishAsrModelDownloader(asrManager.getEnglishStorageModelDir()).deleteModel()
            Toast.makeText(this, "英語音声認識モデルを削除しました", Toast.LENGTH_SHORT).show()
            refreshEnglishAsrUI()
        }
        refreshEnglishAsrUI()
    }

    private fun refreshEnglishAsrUI() {
        val isInstalled = asrManager.isEnglishModelAvailable()
        val dir = asrManager.getEnglishStorageModelDir()
        if (isInstalled) {
            val totalSize = (dir.listFiles()?.sumOf { it.length() } ?: 0L)
            val sizeMb = String.format(java.util.Locale.US, "%.1f", totalSize / (1024.0 * 1024.0))
            tvEnglishAsrStatusBadge.text = "導入済"
            tvEnglishAsrStatusBadge.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.badge_success_bg))
            tvEnglishAsrStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.badge_success_text))
            tvEnglishAsrStats.text = "約126MB (導入済: ${sizeMb} MB, CC-BY-4.0)"
            btnDownloadEnglishAsr.text = "再取得"
            btnDeleteEnglishAsr.visibility = View.VISIBLE
        } else {
            tvEnglishAsrStatusBadge.text = "未取得"
            tvEnglishAsrStatusBadge.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.badge_neutral_bg))
            tvEnglishAsrStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.badge_neutral_text))
            tvEnglishAsrStats.text = "約126MB (CC-BY-4.0)"
            btnDownloadEnglishAsr.text = "取得"
            btnDeleteEnglishAsr.visibility = View.GONE
        }
        updateModelStatusBadge()
    }

    private fun startDownloadEnglishAsr() {
        startBackgroundProvisioning(ProvisioningTaskKind.ENGLISH_ASR_MODEL)
    }

    private fun updateKeyboardHeightUI() {
        isUpdatingUiFromPrefs = true
        try {
            val current = KeyboardHeightManager.getCurrentPreset(this)
            when (current) {
                KeyboardHeightManager.HeightPreset.COMPACT -> rbHeightCompact.isChecked = true
                KeyboardHeightManager.HeightPreset.SHORT -> rbHeightShort.isChecked = true
                KeyboardHeightManager.HeightPreset.NORMAL -> rbHeightNormal.isChecked = true
                KeyboardHeightManager.HeightPreset.TALL -> rbHeightTall.isChecked = true
                KeyboardHeightManager.HeightPreset.EXTRA_TALL -> rbHeightExtraTall.isChecked = true
            }
            switchQwertyNumberRow.isChecked = KeyboardHeightManager.isQwertyNumberRowEnabled(this)
            switchQwertySwipeTyping.isChecked = KeyboardHeightManager.isQwertySwipeTypingEnabled(this)
            switchStreamingVoiceInput.isChecked = KeyboardHeightManager.isStreamingVoiceInputEnabled(this)
            switchDebugDisplay.isChecked = KeyboardHeightManager.isDebugDisplayEnabled(this)
        } finally {
            isUpdatingUiFromPrefs = false
        }
    }

    private fun showOssLicensesDialog() {
        val licenseNotice = """
            本アプリは、以下のオープンソースソフトウェアおよび事前学習済みモデルを利用しています。
            This application incorporates the following open-source components and models:

            ━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            ■ ReazonSpeech Zipformer Transducer Model
            ━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            Copyright (c) 2023-2024 Reazon Human Interaction Lab
            (Reazon Holdings, Inc.)
            License: Apache License, Version 2.0
            https://github.com/reazon-research/ReazonSpeech

            ━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            ■ NVIDIA Parakeet TDT-CTC 110M (English ASR)
            ━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            Copyright (c) NVIDIA Corporation
            License: Creative Commons Attribution 4.0 (CC-BY-4.0)
            https://huggingface.co/nvidia/parakeet-tdt_ctc-110m

            ━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            ■ sherpa-onnx Offline Speech Recognition Engine
            ━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            Copyright (c) 2022-2024 Next-gen Kaldi / K2 FSA Team
            License: Apache License, Version 2.0
            https://github.com/k2-fsa/sherpa-onnx

            ━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            ■ sherpa-onnx-zipformer-ja-reazonspeech Packaging
            ━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            Packaged by Taisei Ozaki / DeL
            License: Apache License, Version 2.0
            https://huggingface.co/DeL-TaiseiOzaki/sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01

            ━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            ■ FUTO Swipe Neural Gesture Typing Technology
            ━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            Powered by FUTO Swipe
            Copyright (c) 2026 FUTO Holdings Inc.
            License: FUTO Model Weights License 1.0 (Attribution Required)
            https://gitlab.futo.org/keyboard/swipe-library
            https://huggingface.co/futo-org/futo-swipe

            ━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            ■ Android Jetpack & Material Components
            ━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            Copyright (c) The Android Open Source Project
            License: Apache License, Version 2.0

            ============================================
            Apache License, Version 2.0
            ============================================
            Licensed under the Apache License, Version 2.0 (the "License");
            you may not use this file except in compliance with the License.
            You may obtain a copy of the License at:

                http://www.apache.org/licenses/LICENSE-2.0

            Unless required by applicable law or agreed to in writing, software
            distributed under the License is distributed on an "AS IS" BASIS,
            WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
            See the License for the specific language governing permissions and
            limitations under the License.
        """.trimIndent()

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.oss_licenses_dialog_title)
            .setMessage(licenseNotice)
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        checkBenchmarkIntent(intent)
    }

    private fun checkBenchmarkIntent(intent: Intent?) {
        val action = intent?.getStringExtra("bench_action") ?: return
        val engine = intent.getStringExtra("bench_engine") ?: "local_slm"
        val workload = intent.getStringExtra("bench_workload") ?: "miss"
        val iters = intent.getIntExtra("bench_iters", 200)
        PowerBenchmarkRunner.handleIntent(this, lifecycleScope, action, engine, workload, iters)
    }

    override fun onDestroy() {
        super.onDestroy()
        asrManager.release()
    }
}
