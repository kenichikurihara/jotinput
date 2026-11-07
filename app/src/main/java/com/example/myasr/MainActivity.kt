package com.example.myasr

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
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
    private lateinit var rbEngineNano2: android.widget.RadioButton
    private lateinit var rbEngineNano1: android.widget.RadioButton
    private lateinit var rbEngineLocalSlm: android.widget.RadioButton
    private lateinit var rbEngineLocalSlm15B: android.widget.RadioButton
    private lateinit var rbEngineHybridRerank: android.widget.RadioButton
    private lateinit var rbEngineNone: android.widget.RadioButton
    private lateinit var switchSlmContextFree: com.google.android.material.switchmaterial.SwitchMaterial
    private lateinit var switchSlmMandatoryRerank: com.google.android.material.switchmaterial.SwitchMaterial
    private lateinit var switchSpeculativePrefetch: com.google.android.material.switchmaterial.SwitchMaterial
    private lateinit var sliderSlmAlpha: com.google.android.material.slider.Slider
    private lateinit var tvSlmAlphaValue: TextView
    private lateinit var tvSlmAlphaDesc: TextView
    private lateinit var sliderSlmLambda: com.google.android.material.slider.Slider
    private lateinit var tvSlmLambdaValue: TextView
    private lateinit var tvSlmLambdaDesc: TextView
    private lateinit var multiDictProgress: LinearProgressIndicator
    private lateinit var tvMultiDictStatus: TextView
    private lateinit var btnDownloadAllDicts: MaterialButton
    private lateinit var btnToggleDictDetails: MaterialButton
    private lateinit var layoutDictDetailsContainer: LinearLayout
    private lateinit var layoutDictItemsContainer: LinearLayout

    // Progressive disclosure for Advanced / Developer tools
    private lateinit var layoutAdvancedHeader: LinearLayout
    private lateinit var tvAdvancedToggleIcon: TextView
    private lateinit var layoutAdvancedContent: LinearLayout

    private lateinit var tvConnectionMatrixBadge: TextView

    private lateinit var tvEnglishSpellingSummary: TextView
    private lateinit var englishSpellingProgress: LinearProgressIndicator
    private lateinit var tvEnglishSpellingStatus: TextView
    private lateinit var btnDownloadEnglishSpelling: MaterialButton
    private lateinit var btnDeleteEnglishSpelling: MaterialButton

    private lateinit var asrManager: AsrManager
    private val kanaKanjiEngine get() = AsrApp.instance.kanaKanjiEngine
    private lateinit var dictionaryRepository: DictionaryRepository
    private lateinit var hotwordsGenerator: HotwordsGenerator

    private var currentHotwordsFile: File? = null
    private var lastActiveDictionaryCount = -1
    private var isUpdatingUiFromPrefs = false

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
        val isNano2 = RerankEngineType.isNano2Supported(this)
        val isAiCore = RerankEngineType.isAiCoreSupported(this)

        rbEngineNano2.isEnabled = isNano2
        rbEngineNano2.visibility = if (isNano2) View.VISIBLE else View.GONE
        rbEngineNano1.isEnabled = isAiCore
        rbEngineNano1.visibility = if (isAiCore) View.VISIBLE else View.GONE
        rbEngineLocalSlm.isEnabled = true
        rbEngineLocalSlm15B.isEnabled = true
        rbEngineHybridRerank.isEnabled = true
        rbEngineNone.isEnabled = true

        when (current) {
            RerankEngineType.AICORE_NANO_2 -> rbEngineNano2.isChecked = true
            RerankEngineType.AICORE_NANO_1 -> rbEngineNano1.isChecked = true
            RerankEngineType.LOCAL_SLM -> rbEngineLocalSlm.isChecked = true
            RerankEngineType.LOCAL_SLM_1_5B -> rbEngineLocalSlm15B.isChecked = true
            RerankEngineType.HYBRID_RERANK -> rbEngineHybridRerank.isChecked = true
            RerankEngineType.NONE -> rbEngineNone.isChecked = true
        }

        switchSlmRerankEnabled.isChecked = KeyboardHeightManager.isSlmRerankEnabled(this)

        rgRerankEngine.setOnCheckedChangeListener { _, checkedId ->
            if (isUpdatingUiFromPrefs) return@setOnCheckedChangeListener
            val selected = when (checkedId) {
                R.id.rbEngineNano2 -> RerankEngineType.AICORE_NANO_2
                R.id.rbEngineNano1 -> RerankEngineType.AICORE_NANO_1
                R.id.rbEngineLocalSlm15B -> RerankEngineType.LOCAL_SLM_1_5B
                R.id.rbEngineHybridRerank -> RerankEngineType.HYBRID_RERANK
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
                    if (selected == RerankEngineType.LOCAL_SLM || selected == RerankEngineType.LOCAL_SLM_1_5B || selected == RerankEngineType.HYBRID_RERANK) {
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
            val status = if (isChecked) "有効 (ON: LM-First)" else "無効 (OFF: Mozc辞書・Viterbiのみ)"
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
            RerankEngineType.AICORE_NANO_2 -> {
                tvSlmStatusBadge.text = "Nano 2 (Active)"
                tvSlmStatusBadge.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.badge_success_bg)
                )
                tvSlmStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.badge_success_text))
                tvSlmStatusDesc.text = "Android AICore: Gemini Nano 2 (3.2B • Tensor G5 NPU)"
                if (::tvCard1AiStatusBadge.isInitialized) {
                    tvCard1AiStatusBadge.text = "AI: Nano 2"
                    tvCard1AiStatusBadge.backgroundTintList = ColorStateList.valueOf(
                        ContextCompat.getColor(this, R.color.badge_success_bg)
                    )
                    tvCard1AiStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.badge_success_text))
                }
            }
            RerankEngineType.AICORE_NANO_1 -> {
                tvSlmStatusBadge.text = "Nano 1 (Active)"
                tvSlmStatusBadge.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.badge_success_bg)
                )
                tvSlmStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.badge_success_text))
                tvSlmStatusDesc.text = "Android AICore: Gemini Nano 1 (1.8B • NPU 加速)"
                if (::tvCard1AiStatusBadge.isInitialized) {
                    tvCard1AiStatusBadge.text = "AI: Nano 1"
                    tvCard1AiStatusBadge.backgroundTintList = ColorStateList.valueOf(
                        ContextCompat.getColor(this, R.color.badge_success_bg)
                    )
                    tvCard1AiStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.badge_success_text))
                }
            }
            RerankEngineType.HYBRID_RERANK -> {
                tvSlmStatusBadge.text = "リランク (Active)"
                tvSlmStatusBadge.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.badge_info_bg)
                )
                tvSlmStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.badge_info_text))
                tvSlmStatusDesc.text = "辞書優先 ＋ 後段AIリランク (従来互換モード)"
                if (::tvCard1AiStatusBadge.isInitialized) {
                    tvCard1AiStatusBadge.text = "AI: リランク"
                    tvCard1AiStatusBadge.backgroundTintList = ColorStateList.valueOf(
                        ContextCompat.getColor(this, R.color.badge_info_bg)
                    )
                    tvCard1AiStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.badge_info_text))
                }
            }
            RerankEngineType.LOCAL_SLM, RerankEngineType.LOCAL_SLM_1_5B -> {
                val modelName = if (selectedEngine == RerankEngineType.LOCAL_SLM_1_5B) "LM-First 1.5B" else "LM-First 0.5B"
                val badgeText = if (selectedEngine == RerankEngineType.LOCAL_SLM_1_5B) "LM-1.5B" else "LM-First"
                val modelFileName = selectedEngine.modelFileName
                when (reranker.state) {
                    LlmReranker.State.READY -> {
                        tvSlmStatusBadge.text = "$badgeText (Ready)"
                        tvSlmStatusBadge.backgroundTintList = ColorStateList.valueOf(
                            ContextCompat.getColor(this, R.color.badge_success_bg)
                        )
                        tvSlmStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.badge_success_text))
                        tvSlmStatusDesc.text = "$modelName 稼働中 (かな漢字・QWERTY一括直接生成)"
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
            alpha in 0.15f..0.25f -> "α = $rounded (LM-First 最適値: Top-1 90.3%)"
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
        updateKeyboardHeightUI()
        isUpdatingUiFromPrefs = true
        try {
            switchStreamingVoiceInput.isChecked = KeyboardHeightManager.isStreamingVoiceInputEnabled(this)
            switchDebugDisplay.isChecked = KeyboardHeightManager.isDebugDisplayEnabled(this)
            switchSlmRerankEnabled.isChecked = KeyboardHeightManager.isSlmRerankEnabled(this)
            switchSlmContextFree.isChecked = KeyboardHeightManager.isSlmContextFreeRerankEnabled(this)
            switchSlmMandatoryRerank.isChecked = KeyboardHeightManager.isSlmMandatoryRerankEnabled(this)
            switchSpeculativePrefetch.isChecked = KeyboardHeightManager.isSpeculativePrefetchEnabled(this)
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
        rbEngineNano2 = findViewById(R.id.rbEngineNano2)
        rbEngineNano1 = findViewById(R.id.rbEngineNano1)
        rbEngineLocalSlm = findViewById(R.id.rbEngineLocalSlm)
        rbEngineLocalSlm15B = findViewById(R.id.rbEngineLocalSlm15B)
        rbEngineHybridRerank = findViewById(R.id.rbEngineHybridRerank)
        rbEngineNone = findViewById(R.id.rbEngineNone)
        setupRerankEngineUI()
        switchSlmContextFree = findViewById(R.id.switchSlmContextFree)
        switchSlmMandatoryRerank = findViewById(R.id.switchSlmMandatoryRerank)
        switchSpeculativePrefetch = findViewById(R.id.switchSpeculativePrefetch)
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
        btnDownloadAllDicts = findViewById(R.id.btnDownloadAllDicts)
        btnToggleDictDetails = findViewById(R.id.btnToggleDictDetails)
        layoutDictDetailsContainer = findViewById(R.id.layoutDictDetailsContainer)
        layoutDictItemsContainer = findViewById(R.id.layoutDictItemsContainer)

        layoutAdvancedHeader = findViewById(R.id.layoutAdvancedHeader)
        tvAdvancedToggleIcon = findViewById(R.id.tvAdvancedToggleIcon)
        layoutAdvancedContent = findViewById(R.id.layoutAdvancedContent)

        tvConnectionMatrixBadge = findViewById(R.id.tvConnectionMatrixBadge)

        tvEnglishSpellingSummary = findViewById(R.id.tvEnglishSpellingSummary)
        englishSpellingProgress = findViewById(R.id.englishSpellingProgress)
        tvEnglishSpellingStatus = findViewById(R.id.tvEnglishSpellingStatus)
        btnDownloadEnglishSpelling = findViewById(R.id.btnDownloadEnglishSpelling)
        btnDeleteEnglishSpelling = findViewById(R.id.btnDeleteEnglishSpelling)
    }

    private fun setupListeners() {
        btnOpenDictionary.setOnClickListener {
            startActivity(Intent(this, DictionaryActivity::class.java))
        }

        setupMultiDictionaryUI()
        setupEnglishSpellingUI()

        btnToggleDictDetails.setOnClickListener {
            val isVisible = layoutDictDetailsContainer.visibility == View.VISIBLE
            layoutDictDetailsContainer.visibility = if (isVisible) View.GONE else View.VISIBLE
            btnToggleDictDetails.text = if (isVisible) {
                "個別の拡張辞書を管理 ▼"
            } else {
                "個別の拡張辞書を閉じる ▲"
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

        switchSlmContextFree.isChecked = KeyboardHeightManager.isSlmContextFreeRerankEnabled(this)
        switchSlmContextFree.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingUiFromPrefs) return@setOnCheckedChangeListener
            KeyboardHeightManager.setSlmContextFreeRerankEnabled(this, isChecked)
            val status = if (isChecked) "有効 (ON: 空文脈でもSLM再ランク)" else "無効 (OFF: 文脈ありのみ)"
            Toast.makeText(this, "文頭・単独入力時のSLM再ランク: $status", Toast.LENGTH_SHORT).show()
        }

        switchSlmMandatoryRerank.isChecked = KeyboardHeightManager.isSlmMandatoryRerankEnabled(this)
        switchSlmMandatoryRerank.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingUiFromPrefs) return@setOnCheckedChangeListener
            KeyboardHeightManager.setSlmMandatoryRerankEnabled(this, isChecked)
            val status = if (isChecked) "有効 (ON: 100msタイムアウト付きで必ず適用)" else "無効 (OFF: 即時描画+非同期)"
            Toast.makeText(this, "SLM確定リランク: $status", Toast.LENGTH_SHORT).show()
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

        switchSpeculativePrefetch.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingUiFromPrefs) return@setOnCheckedChangeListener
            KeyboardHeightManager.setSpeculativePrefetchEnabled(this, isChecked)
            val status = if (isChecked) "有効 (0ms応答)" else "無効"
            Toast.makeText(this, "投機的先行デコード: $status", Toast.LENGTH_SHORT).show()
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

    private fun checkAndInitializeModel() {
        if (asrManager.isModelAvailable()) {
            cardDownload.visibility = View.GONE
            tvModelStatus.text = "音声認識: 読込中…"

            lifecycleScope.launch(Dispatchers.Default) {
                currentHotwordsFile = hotwordsGenerator.generate(dictionaryRepository.getActive())
                val success = asrManager.initialize(currentHotwordsFile)
                withContext(Dispatchers.Main) {
                    if (success) {
                        tvModelStatus.text = "音声認識: Ready"
                    } else {
                        tvModelStatus.text = "音声認識: エラー"
                    }
                }
            }
        } else {
            // Model files missing
            cardDownload.visibility = View.VISIBLE
            tvModelStatus.text = "音声認識: 未導入"
        }
    }

    private fun startModelDownload() {
        btnDownloadModel.isEnabled = false
        downloadProgress.visibility = View.VISIBLE
        downloadProgress.progress = 0
        tvDownloadInfo.text = "Connecting to repository…"

        val downloader = ModelDownloader(asrManager.getStorageModelDir())

        lifecycleScope.launch {
            val result = downloader.download { fileName, fileIdx, totalFiles, percent ->
                lifecycleScope.launch(Dispatchers.Main) {
                    downloadProgress.progress = percent
                    tvDownloadInfo.text = "Downloading [$fileIdx/$totalFiles] $fileName ($percent%)"
                }
            }

            if (result.isSuccess) {
                hotwordsGenerator.reloadTokensSet()
                if (!kanaKanjiEngine.isBaseDictionaryDownloaded()) {
                    withContext(Dispatchers.Main) {
                        tvDownloadInfo.text = "Mozc 基本辞書 (約2MB) をダウンロード中..."
                    }
                    val baseDictResult = AsrApp.instance.baseDictionaryDownloader.download { pct, statusText ->
                        lifecycleScope.launch(Dispatchers.Main) {
                            downloadProgress.progress = pct
                            tvDownloadInfo.text = "基本辞書: $statusText"
                        }
                    }
                    if (baseDictResult.isSuccess) {
                        withContext(Dispatchers.Main) {
                            refreshMultiDictionaryUI()
                        }
                    }
                }
                Toast.makeText(this@MainActivity, "モデルと辞書のダウンロードが完了しました！", Toast.LENGTH_LONG).show()
                checkAndInitializeModel()
            } else {
                val error = result.exceptionOrNull()?.message ?: "Unknown error"
                tvDownloadInfo.text = "Download failed: $error"
                btnDownloadModel.isEnabled = true
                downloadProgress.visibility = View.GONE
                Toast.makeText(this@MainActivity, "Download failed: $error", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setupMultiDictionaryUI() {
        btnDownloadAllDicts.setOnClickListener {
            startDownloadAllDicts()
        }
        refreshMultiDictionaryUI()
    }

    private fun refreshMultiDictionaryUI() {
        val manager = AsrApp.instance.multiDictionaryManager
        val totalWords = manager.getTotalWordCount()
        val baseCount = if (kanaKanjiEngine.isBaseDictionaryDownloaded()) 131156 else 0
        val totalCombined = totalWords + baseCount
        val totalFormatted = String.format(java.util.Locale.US, "%,d", totalCombined)
        if (totalCombined > 0) {
            tvTotalDictWordsBadge.text = "辞書: $totalFormatted 語"
            tvTotalDictWordsBadge.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.badge_success_bg))
            tvTotalDictWordsBadge.setTextColor(ContextCompat.getColor(this, R.color.badge_success_text))
            btnDownloadAllDicts.text = "全拡張辞書を最新版に更新 ($totalFormatted 語)"
        } else {
            tvTotalDictWordsBadge.text = "辞書: 未取得"
            tvTotalDictWordsBadge.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.badge_neutral_bg))
            tvTotalDictWordsBadge.setTextColor(ContextCompat.getColor(this, R.color.badge_neutral_text))
            btnDownloadAllDicts.text = "推奨拡張辞書を一括ダウンロード (約400万語)"
        }

        layoutDictItemsContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)

        // 0. Base Mozc Core Dictionary (~51,000 words, ~2.0MB)
        val baseItemView = inflater.inflate(R.layout.item_multi_dict, layoutDictItemsContainer, false)
        val tvBaseTitle = baseItemView.findViewById<TextView>(R.id.tvDictTitle)
        val tvBaseBadge = baseItemView.findViewById<TextView>(R.id.tvDictStatusBadge)
        val tvBaseDesc = baseItemView.findViewById<TextView>(R.id.tvDictDescription)
        val tvBaseStats = baseItemView.findViewById<TextView>(R.id.tvDictStats)
        val btnBaseAction = baseItemView.findViewById<MaterialButton>(R.id.btnDictAction)

        val isBaseDownloaded = kanaKanjiEngine.isBaseDictionaryDownloaded()
        tvBaseTitle.text = "Mozc 基本・標準語彙 (コア辞書)"
        tvBaseDesc.text = "高速Trie・Viterbi連文節変換用の標準コア辞書 (~13.1万語)"
        tvBaseStats.text = "約13.1万語 (約5.1 MB)"
        if (isBaseDownloaded) {
            tvBaseBadge.text = "導入済 (~13.1万語)"
            tvBaseBadge.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.badge_success_bg))
            tvBaseBadge.setTextColor(ContextCompat.getColor(this, R.color.badge_success_text))
            btnBaseAction.text = "再取得"
        } else {
            tvBaseBadge.text = "未取得"
            tvBaseBadge.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.badge_neutral_bg))
            tvBaseBadge.setTextColor(ContextCompat.getColor(this, R.color.badge_neutral_text))
            btnBaseAction.text = "取得"
        }
        btnBaseAction.setOnClickListener {
            startDownloadBaseDict()
        }
        layoutDictItemsContainer.addView(baseItemView)

        for (dictType in DictionaryType.values()) {
            val itemView = inflater.inflate(R.layout.item_multi_dict, layoutDictItemsContainer, false)
            val tvTitle = itemView.findViewById<TextView>(R.id.tvDictTitle)
            val tvStatusBadge = itemView.findViewById<TextView>(R.id.tvDictStatusBadge)
            val tvDesc = itemView.findViewById<TextView>(R.id.tvDictDescription)
            val tvStats = itemView.findViewById<TextView>(R.id.tvDictStats)
            val btnAction = itemView.findViewById<MaterialButton>(R.id.btnDictAction)

            tvTitle.text = dictType.displayName
            tvDesc.text = dictType.description
            tvStats.text = "${dictType.estimatedWords} (${dictType.estimatedDownloadSize})"

            val count = manager.getWordCount(dictType)
            if (count > 0) {
                val countFormatted = String.format(java.util.Locale.US, "%,d", count)
                tvStatusBadge.text = "有効 ($countFormatted 語)"
                tvStatusBadge.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.badge_success_bg))
                tvStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.badge_success_text))
                btnAction.text = "再取得"
            } else {
                tvStatusBadge.text = "未取得"
                tvStatusBadge.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.badge_neutral_bg))
                tvStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.badge_neutral_text))
                btnAction.text = "取得"
            }

            btnAction.setOnClickListener {
                startDownloadSingleDict(dictType)
            }

            layoutDictItemsContainer.addView(itemView)
        }
    }

    private fun startDownloadSingleDict(type: DictionaryType) {
        val manager = AsrApp.instance.multiDictionaryManager
        btnDownloadAllDicts.isEnabled = false
        multiDictProgress.visibility = View.VISIBLE
        multiDictProgress.isIndeterminate = false
        multiDictProgress.progress = 0
        tvMultiDictStatus.visibility = View.VISIBLE
        tvMultiDictStatus.text = "${type.displayName} をダウンロード中..."

        lifecycleScope.launch {
            val result = manager.downloadDictionary(type) { pct, msg ->
                lifecycleScope.launch(Dispatchers.Main) {
                    multiDictProgress.progress = pct
                    tvMultiDictStatus.text = msg
                }
            }
            multiDictProgress.visibility = View.GONE
            tvMultiDictStatus.visibility = View.GONE
            btnDownloadAllDicts.isEnabled = true

            if (result.isSuccess) {
                val words = result.getOrDefault(0)
                Toast.makeText(this@MainActivity, "${type.displayName} をインストールしました (${words}語)", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@MainActivity, "ダウンロード失敗: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
            }
            refreshMultiDictionaryUI()
        }
    }

    private fun startDownloadBaseDict() {
        val downloader = AsrApp.instance.baseDictionaryDownloader
        btnDownloadAllDicts.isEnabled = false
        multiDictProgress.visibility = View.VISIBLE
        multiDictProgress.isIndeterminate = false
        multiDictProgress.progress = 0
        tvMultiDictStatus.visibility = View.VISIBLE
        tvMultiDictStatus.text = "基本辞書をダウンロード中..."

        lifecycleScope.launch {
            val result = downloader.download { pct, msg ->
                lifecycleScope.launch(Dispatchers.Main) {
                    multiDictProgress.progress = pct
                    tvMultiDictStatus.text = msg
                }
            }
            multiDictProgress.visibility = View.GONE
            tvMultiDictStatus.visibility = View.GONE
            btnDownloadAllDicts.isEnabled = true

            if (result.isSuccess) {
                val words = result.getOrDefault(0)
                Toast.makeText(this@MainActivity, "Mozc 基本辞書をインストールしました (${words}語)", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@MainActivity, "ダウンロード失敗: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
            }
            refreshMultiDictionaryUI()
        }
    }

    private fun startDownloadAllDicts() {
        val manager = AsrApp.instance.multiDictionaryManager
        val baseDownloader = AsrApp.instance.baseDictionaryDownloader
        btnDownloadAllDicts.isEnabled = false
        multiDictProgress.visibility = View.VISIBLE
        multiDictProgress.isIndeterminate = false
        multiDictProgress.progress = 0
        tvMultiDictStatus.visibility = View.VISIBLE
        tvMultiDictStatus.text = "推奨データを一括ダウンロード中..."

        lifecycleScope.launch {
            if (!kanaKanjiEngine.isBaseDictionaryDownloaded()) {
                tvMultiDictStatus.text = "基本辞書を取得中..."
                baseDownloader.download { pct, msg ->
                    lifecycleScope.launch(Dispatchers.Main) {
                        multiDictProgress.progress = (pct * 0.1).toInt()
                        tvMultiDictStatus.text = msg
                    }
                }
            }

            val result = manager.downloadAllDictionaries { pct, msg ->
                lifecycleScope.launch(Dispatchers.Main) {
                    multiDictProgress.progress = (pct).coerceIn(0, 100)
                    tvMultiDictStatus.text = msg
                }
            }
            multiDictProgress.visibility = View.GONE
            tvMultiDictStatus.visibility = View.GONE
            btnDownloadAllDicts.isEnabled = true

            if (result.isSuccess) {
                val totalWords = result.getOrDefault(0)
                Toast.makeText(this@MainActivity, "全辞書・言語データの導入が完了しました (計${totalWords}語)", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this@MainActivity, "一部の辞書ダウンロードに失敗しました: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
            }
            refreshMultiDictionaryUI()
            refreshConnectionMatrixBadge()
            refreshEnglishSpellingUI()
        }
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
            tvEnglishSpellingSummary.text = "QWERTY英語スペリングコレクション (${sizeMb} MB) が有効です。"
            btnDownloadEnglishSpelling.text = "スペルモデルを再取得"
            btnDeleteEnglishSpelling.visibility = View.VISIBLE
        } else {
            tvEnglishSpellingSummary.text = "オフライン文脈スペル補正モデル (~27MB)。QWERTYキーボードで誤字を自動補正します。"
            btnDownloadEnglishSpelling.text = "スペルモデルを取得 (~27MB)"
            btnDeleteEnglishSpelling.visibility = View.GONE
        }
    }

    private fun startDownloadEnglishSpelling() {
        val downloader = EnglishSpellDownloader(this)
        btnDownloadEnglishSpelling.isEnabled = false
        btnDeleteEnglishSpelling.isEnabled = false
        englishSpellingProgress.visibility = View.VISIBLE
        tvEnglishSpellingStatus.visibility = View.VISIBLE
        englishSpellingProgress.progress = 0
        tvEnglishSpellingStatus.text = "準備中..."

        lifecycleScope.launch {
            val result = downloader.download { currentFile, fileIdx, totalFiles, pct ->
                runOnUiThread {
                    englishSpellingProgress.progress = pct
                    tvEnglishSpellingStatus.text = "[$fileIdx/$totalFiles] $currentFile ($pct%)"
                }
            }
            englishSpellingProgress.visibility = View.GONE
            tvEnglishSpellingStatus.visibility = View.GONE
            btnDownloadEnglishSpelling.isEnabled = true
            btnDeleteEnglishSpelling.isEnabled = true

            if (result.isSuccess) {
                EnglishSpellEngine.getInstance(this@MainActivity).ensureInitialized()
                Toast.makeText(this@MainActivity, "英語スペル補正モデルのインストールが完了しました", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this@MainActivity, "ダウンロードに失敗しました: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
            }
            refreshEnglishSpellingUI()
        }
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
