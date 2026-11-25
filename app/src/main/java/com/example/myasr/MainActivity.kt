package com.example.myasr

import com.example.myasr.context.ime.domain.MainAppUiDecisionModel
import com.example.myasr.context.ime.domain.MainAppUiDecisionModel.AiEngineStatusInput
import com.example.myasr.context.ime.domain.MainAppUiDecisionModel.AiRuntimeState
import com.example.myasr.context.ime.domain.MainAppUiDecisionModel.HeroActionKind
import com.example.myasr.context.ime.domain.MainAppUiDecisionModel.HomeHeroInput
import com.example.myasr.context.ime.domain.MainAppUiDecisionModel.ProvisioningBannerInput
import com.example.myasr.context.ime.domain.MainAppUiDecisionModel.RerankEngineSelectionDecision
import com.example.myasr.context.ime.domain.MainAppUiDecisionModel.RerankEngineSelectionInput
import com.example.myasr.context.ime.domain.MainAppUiDecisionModel.SlmDownloadPromptInput
import com.example.myasr.context.ime.domain.MainAppUiDecisionModel.StatusTone
import com.example.myasr.context.ime.domain.OnboardingDecisionModel
import com.example.myasr.context.ime.domain.OnboardingDecisionModel.LaunchAction
import com.example.myasr.context.ime.domain.OnboardingDecisionModel.OnboardingLaunchInput
import com.example.myasr.context.ime.infrastructure.OnboardingStateStore
import com.example.myasr.context.ime.presentation.KeyboardHeightManager
import com.example.myasr.context.kanakanji.application.UserHistorySyncManager
import com.example.myasr.context.kanakanji.domain.HistorySyncDecisionModel
import com.example.myasr.context.kanakanji.domain.HistorySyncDecisionModel.SettingsSyncCardInput
import com.example.myasr.context.kanakanji.domain.HistorySyncDecisionModel.SyncBadgeTone
import com.example.myasr.context.kanakanji.domain.HistorySyncDecisionModel.SyncCardState
import com.example.myasr.context.kanakanji.domain.KanaKanjiEngine
import com.example.myasr.context.provisioning.domain.AppUpdateBadgeTone
import com.example.myasr.context.provisioning.domain.AppUpdateCardState
import com.example.myasr.context.provisioning.domain.AppUpdateCheckErrorKind
import com.example.myasr.context.provisioning.domain.AppUpdateCheckResult
import com.example.myasr.context.provisioning.domain.AppUpdateDecisionModel
import com.example.myasr.context.provisioning.domain.AppUpdatePrimaryAction
import com.example.myasr.context.provisioning.domain.DictUpdateState
import com.example.myasr.context.provisioning.domain.DictionaryStatusInfo
import com.example.myasr.context.provisioning.domain.DictionaryUpdateTracker
import com.example.myasr.context.provisioning.infrastructure.AppUpdateInstaller
import com.example.myasr.context.provisioning.infrastructure.DictionaryType
import com.example.myasr.context.provisioning.infrastructure.EnglishAsrModelDownloader
import com.example.myasr.context.provisioning.infrastructure.EnglishSpellDownloader
import com.example.myasr.context.provisioning.infrastructure.ProvisioningForegroundService
import com.example.myasr.context.provisioning.infrastructure.ProvisioningTaskKind
import com.example.myasr.context.provisioning.infrastructure.ProvisioningUiState
import com.example.myasr.context.provisioning.infrastructure.SlmModelDownloader
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TRIGGER_APP_UPDATE_INSTALL = "extra_trigger_app_update_install"
    }

    // Shell Navigation & Global Provisioning Banner
    private lateinit var coordinatorRoot: View
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var dividerBottomNav: View
    private lateinit var tabHome: View
    private lateinit var tabSettings: View
    private lateinit var tabData: View
    private lateinit var layoutGlobalProvisioningBanner: LinearLayout
    private lateinit var tvGlobalProvisioningStatus: TextView
    private lateinit var globalProvisioningProgress: LinearProgressIndicator

    // Home Tab: DMN Smart Guide & Playground
    private lateinit var tvHeroBadge: TextView
    private lateinit var tvHeroTitle: TextView
    private lateinit var tvHeroDescription: TextView
    private lateinit var btnHeroCta: MaterialButton
    private lateinit var tvModelStatus: TextView
    private lateinit var tvDictionaryStatus: TextView
    private lateinit var tvImeStatusBadge: TextView
    private lateinit var tvCard1AiStatusBadge: TextView
    private lateinit var btnClearTestInput: TextView
    private lateinit var etKeyboardTest: EditText
    private lateinit var cardQuickDictionary: View
    private lateinit var cardQuickSwitchIme: View
    private lateinit var cardAppUpdate: View
    private lateinit var tvAppUpdateBadge: TextView
    private lateinit var btnAppUpdateToken: TextView
    private lateinit var tvAppUpdateSummary: TextView
    private lateinit var appUpdateProgress: LinearProgressIndicator
    private lateinit var btnAppUpdatePrimary: MaterialButton
    private lateinit var btnAppUpdateSecondary: MaterialButton
    private lateinit var btnEnableIme: MaterialButton
    private lateinit var tvOssLicenses: TextView

    // Settings Tab: Keyboard, Input Assist, AI Engine, Advanced
    private lateinit var rgKeyboardHeight: android.widget.RadioGroup
    private lateinit var rbHeightCompact: android.widget.RadioButton
    private lateinit var rbHeightShort: android.widget.RadioButton
    private lateinit var rbHeightNormal: android.widget.RadioButton
    private lateinit var rbHeightTall: android.widget.RadioButton
    private lateinit var rbHeightExtraTall: android.widget.RadioButton
    private lateinit var switchQwertyNumberRow: com.google.android.material.switchmaterial.SwitchMaterial
    private lateinit var rgQwertyCursorMode: android.widget.RadioGroup
    private lateinit var rbCursorArrowKeys: android.widget.RadioButton
    private lateinit var rbCursorSpaceSwipe: android.widget.RadioButton
    private lateinit var switchQwertySwipeTyping: com.google.android.material.switchmaterial.SwitchMaterial
    private lateinit var switchStreamingVoiceInput: com.google.android.material.switchmaterial.SwitchMaterial
    private lateinit var switchDebugDisplay: com.google.android.material.switchmaterial.SwitchMaterial
    private lateinit var switchAntiJitterCandidateUi: com.google.android.material.switchmaterial.SwitchMaterial

    private lateinit var tvSlmStatusBadge: TextView
    private lateinit var tvSlmStatusDesc: TextView
    private lateinit var switchSlmRerankEnabled: com.google.android.material.switchmaterial.SwitchMaterial
    private lateinit var layoutRerankEngineSelector: LinearLayout
    private lateinit var rgRerankEngine: android.widget.RadioGroup
    private lateinit var rbEngineLocalSlm35_08B: android.widget.RadioButton
    private lateinit var rbEngineLocalSlm35_2B: android.widget.RadioButton
    private lateinit var rbEngineNone: android.widget.RadioButton

    private lateinit var layoutAdvancedHeader: LinearLayout
    private lateinit var tvAdvancedToggleIcon: TextView
    private lateinit var layoutAdvancedContent: LinearLayout
    private lateinit var sliderSlmAlpha: com.google.android.material.slider.Slider
    private lateinit var tvSlmAlphaValue: TextView
    private lateinit var tvSlmAlphaDesc: TextView
    private lateinit var sliderSlmLambda: com.google.android.material.slider.Slider
    private lateinit var tvSlmLambdaValue: TextView
    private lateinit var tvSlmLambdaDesc: TextView

    // Data Tab: Dictionaries & On-Device Models
    private lateinit var tvTotalDictWordsBadge: TextView
    private lateinit var tvConnectionMatrixBadge: TextView
    private lateinit var multiDictProgress: LinearProgressIndicator
    private lateinit var tvMultiDictStatus: TextView
    private lateinit var layoutDictUpdateBanner: LinearLayout
    private lateinit var tvDictUpdateBanner: TextView
    private lateinit var btnCheckDictUpdates: TextView
    private lateinit var btnDownloadAllDicts: MaterialButton
    private lateinit var btnToggleDictDetails: MaterialButton
    private lateinit var layoutDictDetailsContainer: LinearLayout
    private lateinit var layoutDictItemsContainer: LinearLayout

    private lateinit var tvJapaneseAsrStatusBadge: TextView
    private lateinit var tvJapaneseAsrStats: TextView
    private lateinit var japaneseAsrProgress: LinearProgressIndicator
    private lateinit var tvJapaneseAsrDownloadStatus: TextView
    private lateinit var btnDownloadJapaneseAsr: MaterialButton

    private lateinit var tvEnglishSpellingStatusBadge: TextView
    private lateinit var tvEnglishSpellingStats: TextView
    private lateinit var englishSpellingProgress: LinearProgressIndicator
    private lateinit var tvEnglishSpellingStatus: TextView
    private lateinit var btnDownloadEnglishSpelling: MaterialButton
    private lateinit var btnDeleteEnglishSpelling: MaterialButton

    private lateinit var tvEnglishAsrStatusBadge: TextView
    private lateinit var tvEnglishAsrStats: TextView
    private lateinit var englishAsrProgress: LinearProgressIndicator
    private lateinit var tvEnglishAsrStatus: TextView
    private lateinit var btnDownloadEnglishAsr: MaterialButton
    private lateinit var btnDeleteEnglishAsr: MaterialButton

    private lateinit var btnSettingsDownloadSlm: MaterialButton

    private data class SlmModelRowViews(
        val engine: RerankEngineType,
        val badge: TextView,
        val stats: TextView,
        val progress: LinearProgressIndicator,
        val status: TextView,
        val btnDownload: MaterialButton,
        val btnDelete: MaterialButton
    )

    private lateinit var slmModelRows: List<SlmModelRowViews>

    private lateinit var asrManager: AsrManager
    private val kanaKanjiEngine get() = AsrApp.instance.kanaKanjiEngine
    private lateinit var dictionaryRepository: DictionaryRepository
    private lateinit var hotwordsGenerator: HotwordsGenerator

    private var currentHotwordsFile: File? = null
    private var lastActiveDictionaryCount = -1
    private var isUpdatingUiFromPrefs = false
    private var lastHandledProvisioningEventId: Long =
        ProvisioningForegroundService.state.value.lastEvent?.eventId ?: 0L

    private lateinit var tvSettingsSyncBadge: TextView
    private lateinit var tvSettingsSyncSummary: TextView
    private lateinit var tvSettingsSyncStats: TextView
    private lateinit var btnSettingsSyncPrimary: MaterialButton
    private lateinit var layoutSettingsSyncSecondaryActions: LinearLayout
    private lateinit var btnSettingsSyncChange: MaterialButton
    private lateinit var btnSettingsSyncUnlink: MaterialButton
    private lateinit var layoutSettingsAutoSyncControls: LinearLayout
    private lateinit var switchSettingsAutoSync: com.google.android.material.switchmaterial.SwitchMaterial
    private lateinit var rgSettingsSyncInterval: android.widget.RadioGroup
    private lateinit var rbSyncInterval15m: android.widget.RadioButton
    private lateinit var rbSyncInterval60m: android.widget.RadioButton
    private lateinit var rbSyncInterval360m: android.widget.RadioButton

    private var isSettingsSyncing = false
    private var lastSettingsSyncError: String? = null

    private var isCheckingAppUpdate = false
    private var lastAppUpdateErrorKind: AppUpdateCheckErrorKind? = null
    private var lastAppUpdateErrorMessage: String? = null
    private var pendingInstallAfterPermissionGrant = false

    private val unknownSourcesPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (pendingInstallAfterPermissionGrant) {
            pendingInstallAfterPermissionGrant = false
            if (AppUpdateInstaller.canRequestPackageInstalls(this)) {
                triggerInstallDownloadedApk()
            } else {
                Toast.makeText(
                    this,
                    "インストール権限が許可されませんでした（設定から後から有効にできます）",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private val selectSettingsSyncFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: android.net.Uri? ->
        if (uri != null) {
            handleSettingsSyncFileSelected(uri)
        }
    }

    private val createSettingsSyncFileLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: android.net.Uri? ->
        if (uri != null) {
            handleSettingsSyncFileSelected(uri)
        }
    }

    private lateinit var btnLaunchOnboarding: MaterialButton
    private val onboardingLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        updateImeStatusBadge()
        updateDictionaryBadge()
        refreshMultiDictionaryUI()
        refreshSlmModelsUI()
        updateSlmStatusBadge()
        checkAndInitializeModel()
        refreshHomeHeroCard()
    }

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

        initViews()
        setupListeners()
        observeProvisioningState()
        updateDictionaryBadge()
        updateImeStatusBadge()
        refreshMultiDictionaryUI()
        refreshConnectionMatrixBadge()
        updateKeyboardHeightUI()
        checkAndInitializeModel()
        refreshHomeHeroCard()
        checkBenchmarkIntent(intent)
        handleAppUpdateInstallIntent(intent)

        val launchedOnboarding = maybeLaunchOnboarding(savedInstanceState)
        if (!launchedOnboarding &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

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

                val reading3 = "きょう"
                val cands3 = AsrApp.instance.kanaKanjiEngine.getCandidates(reading3, "")
                Log.i("MainActivity", "=== ON-DEVICE CONTEXT-FREE SLM TEST ===")
                Log.i("MainActivity", "Context: '' + Reading: '$reading3'")
                cands3.take(4).forEachIndexed { idx, c ->
                    Log.i("MainActivity", "  Top-${idx + 1}: '${c.surface}' (source: ${c.source})")
                }

                val typoChecks = listOf(
                    "" to "みつこりません",
                    "鍵が" to "みつこりません",
                    "" to "かんがいかい",
                    "新入社員の" to "かんがいかい",
                    "" to "ぶひ"
                )
                for ((tCtx, tReading) in typoChecks) {
                    val tStartMs = android.os.SystemClock.elapsedRealtime()
                    val tCands = AsrApp.instance.kanaKanjiEngine.getCandidates(tReading, tCtx)
                    val tElapsedMs = android.os.SystemClock.elapsedRealtime() - tStartMs
                    Log.i(
                        "MainActivity",
                        "SLM_TYPO_VERIFY (${tElapsedMs}ms) ctx='$tCtx' reading='$tReading' -> ${tCands.take(5).map { "${it.surface}(${it.source})" }}"
                    )
                }
            }
            withContext(Dispatchers.Main) {
                updateSlmStatusBadge()
            }
        }
    }

    private fun applyBadgeTone(badge: TextView, tone: StatusTone) {
        val (bgRes, textRes) = when (tone) {
            StatusTone.SUCCESS -> R.color.badge_success_bg to R.color.badge_success_text
            StatusTone.INFO -> R.color.badge_info_bg to R.color.badge_info_text
            StatusTone.WARNING -> R.color.badge_warning_bg to R.color.badge_warning_text
            StatusTone.NEUTRAL -> R.color.badge_neutral_bg to R.color.badge_neutral_text
        }
        badge.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, bgRes))
        badge.setTextColor(ContextCompat.getColor(this, textRes))
    }

    private fun isImeEnabled(): Boolean {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return false
        return imm.enabledInputMethodList.any { it.packageName == packageName }
    }

    private fun isImeSelectedAsCurrent(): Boolean {
        val defaultIme = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: ""
        return defaultIme.startsWith("$packageName/") || defaultIme.contains(packageName)
    }

    private fun refreshHomeHeroCard() {
        val manager = AsrApp.instance.multiDictionaryManager
        val tracker = AsrApp.instance.dictionaryUpdateTracker
        val outdatedCount = tracker.getAllStatusInfo().count { it.state == DictUpdateState.UPDATE_AVAILABLE }

        val decision = MainAppUiDecisionModel.evaluateHomeHero(
            HomeHeroInput(
                imeEnabled = isImeEnabled(),
                imeSelected = isImeSelectedAsCurrent(),
                japaneseAsrReady = asrManager.isModelAvailable(AsrLanguage.JAPANESE),
                totalDictWords = manager.getTotalWordCount(),
                outdatedDictCount = outdatedCount
            )
        )

        tvHeroBadge.text = decision.badgeText
        applyBadgeTone(tvHeroBadge, decision.tone)
        tvHeroTitle.text = decision.title
        tvHeroDescription.text = decision.description

        if (decision.ctaLabel != null) {
            btnHeroCta.visibility = View.VISIBLE
            btnHeroCta.text = decision.ctaLabel
            btnHeroCta.setOnClickListener {
                when (decision.actionKind) {
                    HeroActionKind.ENABLE_IME -> startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                    HeroActionKind.SELECT_IME -> {
                        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                        imm?.showInputMethodPicker()
                    }
                    HeroActionKind.DOWNLOAD_JA_ASR -> {
                        startModelDownload()
                        bottomNav.selectedItemId = R.id.nav_data
                    }
                    HeroActionKind.DOWNLOAD_DICTS,
                    HeroActionKind.UPDATE_DICTS -> {
                        startDownloadAllDicts()
                        bottomNav.selectedItemId = R.id.nav_data
                    }
                    HeroActionKind.READY -> {}
                }
            }
        } else {
            btnHeroCta.visibility = View.GONE
            btnHeroCta.setOnClickListener(null)
        }
    }

    private fun setupRerankEngineUI() {
        rbEngineLocalSlm35_08B.isEnabled = true
        rbEngineLocalSlm35_2B.isEnabled = true
        rbEngineNone.isEnabled = true

        val initialEnabled = KeyboardHeightManager.isSlmRerankEnabled(this)
        val initialEngine = KeyboardHeightManager.getRerankEngineType(this)
        val initialDecision = MainAppUiDecisionModel.evaluateRerankEngineSelection(
            RerankEngineSelectionInput(
                rerankEnabled = initialEnabled,
                currentEngine = initialEngine
            )
        )
        applyRerankDecision(initialDecision, notifyToast = false)

        rgRerankEngine.setOnCheckedChangeListener { _, checkedId ->
            if (isUpdatingUiFromPrefs) return@setOnCheckedChangeListener
            val selected = when (checkedId) {
                R.id.rbEngineLocalSlm35_2B -> RerankEngineType.LOCAL_SLM_3_5_2B
                R.id.rbEngineNone -> RerankEngineType.NONE
                else -> RerankEngineType.LOCAL_SLM_3_5_0_8B
            }
            val isEnabled = selected != RerankEngineType.NONE
            val decision = MainAppUiDecisionModel.evaluateRerankEngineSelection(
                RerankEngineSelectionInput(
                    rerankEnabled = isEnabled,
                    currentEngine = selected
                )
            )
            applyRerankDecision(decision, notifyToast = true)
        }

        switchSlmRerankEnabled.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingUiFromPrefs) return@setOnCheckedChangeListener
            val currentEngine = KeyboardHeightManager.getRerankEngineType(this)
            val decision = MainAppUiDecisionModel.evaluateRerankEngineSelection(
                RerankEngineSelectionInput(
                    rerankEnabled = isChecked,
                    currentEngine = currentEngine
                )
            )
            applyRerankDecision(decision, notifyToast = true)
        }
    }

    private fun applyRerankDecision(
        decision: RerankEngineSelectionDecision,
        notifyToast: Boolean
    ) {
        KeyboardHeightManager.setSlmRerankEnabled(this, decision.effectiveRerankEnabled)
        KeyboardHeightManager.setRerankEngineType(this, decision.selectedEngine)

        isUpdatingUiFromPrefs = true
        try {
            switchSlmRerankEnabled.isChecked = decision.effectiveRerankEnabled
            when (decision.selectedEngine) {
                RerankEngineType.LOCAL_SLM_3_5_0_8B -> rbEngineLocalSlm35_08B.isChecked = true
                RerankEngineType.LOCAL_SLM_3_5_2B -> rbEngineLocalSlm35_2B.isChecked = true
                RerankEngineType.NONE -> rbEngineNone.isChecked = true
            }
            if (decision.shouldInitializeModel) {
                AsrApp.instance.llmReranker.initializeIfAvailable(lifecycleScope, decision.selectedEngine.modelFileName) {
                    runOnUiThread { updateSlmStatusBadge() }
                }
            }
        } finally {
            isUpdatingUiFromPrefs = false
        }
        updateSlmStatusBadge()
        if (notifyToast) {
            val toastMsg = if (decision.effectiveRerankEnabled) {
                "AI 変換エンジン: ${decision.selectedEngine.displayName} を選択しました"
            } else {
                "AI 変換エンジン: 無効 (OFF: Mozc辞書・Viterbiのみ)"
            }
            Toast.makeText(this, toastMsg, Toast.LENGTH_SHORT).show()
            maybePromptSlmModelDownload(decision)
        }
    }

    private fun maybePromptSlmModelDownload(decision: RerankEngineSelectionDecision) {
        val engine = decision.selectedEngine
        val reranker = AsrApp.instance.llmReranker
        val installed = engine != RerankEngineType.NONE && reranker.isModelInstalled(engine.modelFileName)
        val isDownloading = ProvisioningForegroundService.state.value
            .isTaskActiveOrQueued(ProvisioningTaskKind.SLM_MODEL, engine.id)

        val promptDecision = MainAppUiDecisionModel.evaluateSlmDownloadPrompt(
            SlmDownloadPromptInput(
                isUserSelection = true,
                slmEnabled = decision.effectiveRerankEnabled,
                selectedEngine = engine,
                modelInstalled = installed,
                isDownloading = isDownloading
            )
        )
        val target = promptDecision.targetEngine
        if (!promptDecision.shouldPromptDownload || target == null) return

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("${target.displayName} をダウンロードしますか？")
            .setMessage(
                "選択した AI 変換モデル（${target.sizeLabel} • Apache-2.0）が端末にまだありません。\n" +
                    "バックグラウンドでダウンロードを開始しますか？（完了後に自動で有効化されます）"
            )
            .setPositiveButton("ダウンロード開始") { _, _ ->
                startDownloadSlmModel(target)
            }
            .setNegativeButton("あとで", null)
            .show()
    }


    private fun updateSlmStatusBadge() {
        val isRerankEnabled = KeyboardHeightManager.isSlmRerankEnabled(this)
        val selectedEngine = KeyboardHeightManager.getRerankEngineType(this)
        val reranker = AsrApp.instance.llmReranker

        val runtimeState = when (reranker.state) {
            LlmReranker.State.READY -> AiRuntimeState.READY
            LlmReranker.State.INITIALIZING -> AiRuntimeState.INITIALIZING
            else -> AiRuntimeState.IDLE_OR_ERROR
        }
        val modelInstalled = selectedEngine != RerankEngineType.NONE &&
            reranker.isModelInstalled(selectedEngine.modelFileName)
        val isDownloading = selectedEngine != RerankEngineType.NONE &&
            ProvisioningForegroundService.state.value
                .isTaskActiveOrQueued(ProvisioningTaskKind.SLM_MODEL, selectedEngine.id)

        val decision = MainAppUiDecisionModel.evaluateAiEngineStatus(
            AiEngineStatusInput(
                slmEnabled = isRerankEnabled,
                engineType = selectedEngine,
                runtimeState = runtimeState,
                modelInstalled = modelInstalled,
                isDownloading = isDownloading
            )
        )

        layoutRerankEngineSelector.visibility = if (decision.showEngineSelector) View.VISIBLE else View.GONE
        tvSlmStatusBadge.text = decision.badgeText
        applyBadgeTone(tvSlmStatusBadge, decision.tone)
        tvSlmStatusDesc.text = decision.descriptionText

        if (decision.showDownloadCta && selectedEngine != RerankEngineType.NONE) {
            btnSettingsDownloadSlm.visibility = View.VISIBLE
            btnSettingsDownloadSlm.isEnabled = !isDownloading
            btnSettingsDownloadSlm.text = "${selectedEngine.displayName} をダウンロード (${selectedEngine.sizeLabel})"
        } else {
            btnSettingsDownloadSlm.visibility = View.GONE
        }

        tvCard1AiStatusBadge.text = "AI: ${decision.badgeText}"
        applyBadgeTone(tvCard1AiStatusBadge, decision.tone)
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
        refreshSlmModelsUI()
        updateModelStatusBadge()
        updateKeyboardHeightUI()
        refreshHomeHeroCard()
        refreshAppUpdateCard()
        isUpdatingUiFromPrefs = true
        try {
            switchStreamingVoiceInput.isChecked = KeyboardHeightManager.isStreamingVoiceInputEnabled(this)
            switchDebugDisplay.isChecked = KeyboardHeightManager.isDebugDisplayEnabled(this)
            switchAntiJitterCandidateUi.isChecked = KeyboardHeightManager.isAntiJitterCandidateUiEnabled(this)
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
        refreshSettingsSyncCard()
        val currentActiveCount = dictionaryRepository.getActive().size
        if (lastActiveDictionaryCount != -1 && lastActiveDictionaryCount != currentActiveCount) {
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
        val isEnabled = isImeEnabled()
        val isSelected = isImeSelectedAsCurrent()

        when {
            isEnabled && isSelected -> {
                tvImeStatusBadge.text = "IME: 使用中"
                applyBadgeTone(tvImeStatusBadge, StatusTone.SUCCESS)
            }
            isEnabled -> {
                tvImeStatusBadge.text = "IME: 有効 (未選択)"
                applyBadgeTone(tvImeStatusBadge, StatusTone.INFO)
            }
            else -> {
                tvImeStatusBadge.text = "IME: 未有効"
                applyBadgeTone(tvImeStatusBadge, StatusTone.NEUTRAL)
            }
        }
    }

    private fun initViews() {
        coordinatorRoot = findViewById(R.id.coordinatorRoot)
        bottomNav = findViewById(R.id.bottomNav)
        dividerBottomNav = findViewById(R.id.dividerBottomNav)
        tabHome = findViewById(R.id.tabHome)
        tabSettings = findViewById(R.id.tabSettings)
        tabData = findViewById(R.id.tabData)
        layoutGlobalProvisioningBanner = findViewById(R.id.layoutGlobalProvisioningBanner)
        tvGlobalProvisioningStatus = findViewById(R.id.tvGlobalProvisioningStatus)
        globalProvisioningProgress = findViewById(R.id.globalProvisioningProgress)

        tvHeroBadge = findViewById(R.id.tvHeroBadge)
        tvHeroTitle = findViewById(R.id.tvHeroTitle)
        tvHeroDescription = findViewById(R.id.tvHeroDescription)
        btnHeroCta = findViewById(R.id.btnHeroCta)
        tvModelStatus = findViewById(R.id.tvModelStatus)
        tvDictionaryStatus = findViewById(R.id.tvDictionaryStatus)
        tvCard1AiStatusBadge = findViewById(R.id.tvCard1AiStatusBadge)
        tvImeStatusBadge = findViewById(R.id.tvImeStatusBadge)
        btnClearTestInput = findViewById(R.id.btnClearTestInput)
        etKeyboardTest = findViewById(R.id.etKeyboardTest)
        cardQuickDictionary = findViewById(R.id.cardQuickDictionary)
        cardQuickSwitchIme = findViewById(R.id.cardQuickSwitchIme)
        cardAppUpdate = findViewById(R.id.cardAppUpdate)
        tvAppUpdateBadge = findViewById(R.id.tvAppUpdateBadge)
        btnAppUpdateToken = findViewById(R.id.btnAppUpdateToken)
        tvAppUpdateSummary = findViewById(R.id.tvAppUpdateSummary)
        appUpdateProgress = findViewById(R.id.appUpdateProgress)
        btnAppUpdatePrimary = findViewById(R.id.btnAppUpdatePrimary)
        btnAppUpdateSecondary = findViewById(R.id.btnAppUpdateSecondary)
        btnEnableIme = findViewById(R.id.btnEnableIme)
        tvSettingsSyncBadge = findViewById(R.id.tvSettingsSyncBadge)
        tvSettingsSyncSummary = findViewById(R.id.tvSettingsSyncSummary)
        tvSettingsSyncStats = findViewById(R.id.tvSettingsSyncStats)
        btnSettingsSyncPrimary = findViewById(R.id.btnSettingsSyncPrimary)
        layoutSettingsSyncSecondaryActions = findViewById(R.id.layoutSettingsSyncSecondaryActions)
        btnSettingsSyncChange = findViewById(R.id.btnSettingsSyncChange)
        btnSettingsSyncUnlink = findViewById(R.id.btnSettingsSyncUnlink)
        layoutSettingsAutoSyncControls = findViewById(R.id.layoutSettingsAutoSyncControls)
        switchSettingsAutoSync = findViewById(R.id.switchSettingsAutoSync)
        rgSettingsSyncInterval = findViewById(R.id.rgSettingsSyncInterval)
        rbSyncInterval15m = findViewById(R.id.rbSyncInterval15m)
        rbSyncInterval60m = findViewById(R.id.rbSyncInterval60m)
        rbSyncInterval360m = findViewById(R.id.rbSyncInterval360m)

        btnLaunchOnboarding = findViewById(R.id.btnLaunchOnboarding)
        tvOssLicenses = findViewById(R.id.tvOssLicenses)

        rgKeyboardHeight = findViewById(R.id.rgKeyboardHeight)
        rbHeightCompact = findViewById(R.id.rbHeightCompact)
        rbHeightShort = findViewById(R.id.rbHeightShort)
        rbHeightNormal = findViewById(R.id.rbHeightNormal)
        rbHeightTall = findViewById(R.id.rbHeightTall)
        rbHeightExtraTall = findViewById(R.id.rbHeightExtraTall)
        switchQwertyNumberRow = findViewById(R.id.switchQwertyNumberRow)
        rgQwertyCursorMode = findViewById(R.id.rgQwertyCursorMode)
        rbCursorArrowKeys = findViewById(R.id.rbCursorArrowKeys)
        rbCursorSpaceSwipe = findViewById(R.id.rbCursorSpaceSwipe)
        switchQwertySwipeTyping = findViewById(R.id.switchQwertySwipeTyping)
        switchStreamingVoiceInput = findViewById(R.id.switchStreamingVoiceInput)
        switchDebugDisplay = findViewById(R.id.switchDebugDisplay)
        switchAntiJitterCandidateUi = findViewById(R.id.switchAntiJitterCandidateUi)

        tvTotalDictWordsBadge = findViewById(R.id.tvTotalDictWordsBadge)
        tvSlmStatusBadge = findViewById(R.id.tvSlmStatusBadge)
        tvSlmStatusDesc = findViewById(R.id.tvSlmStatusDesc)
        switchSlmRerankEnabled = findViewById(R.id.switchSlmRerankEnabled)
        layoutRerankEngineSelector = findViewById(R.id.layoutRerankEngineSelector)
        rgRerankEngine = findViewById(R.id.rgRerankEngine)
        rbEngineLocalSlm35_08B = findViewById(R.id.rbEngineLocalSlm35_08B)
        rbEngineLocalSlm35_2B = findViewById(R.id.rbEngineLocalSlm35_2B)
        rbEngineNone = findViewById(R.id.rbEngineNone)

        sliderSlmAlpha = findViewById(R.id.sliderSlmAlpha)
        tvSlmAlphaValue = findViewById(R.id.tvSlmAlphaValue)
        tvSlmAlphaDesc = findViewById(R.id.tvSlmAlphaDesc)

        sliderSlmLambda = findViewById(R.id.sliderSlmLambda)
        tvSlmLambdaValue = findViewById(R.id.tvSlmLambdaValue)
        tvSlmLambdaDesc = findViewById(R.id.tvSlmLambdaDesc)

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
        tvEnglishSpellingStats = findViewById(R.id.tvEnglishSpellingStats)
        englishSpellingProgress = findViewById(R.id.englishSpellingProgress)
        tvEnglishSpellingStatus = findViewById(R.id.tvEnglishSpellingStatus)
        btnDownloadEnglishSpelling = findViewById(R.id.btnDownloadEnglishSpelling)
        btnDeleteEnglishSpelling = findViewById(R.id.btnDeleteEnglishSpelling)

        tvEnglishAsrStatusBadge = findViewById(R.id.tvEnglishAsrStatusBadge)
        tvEnglishAsrStats = findViewById(R.id.tvEnglishAsrStats)
        englishAsrProgress = findViewById(R.id.englishAsrProgress)
        tvEnglishAsrStatus = findViewById(R.id.tvEnglishAsrStatus)
        btnDownloadEnglishAsr = findViewById(R.id.btnDownloadEnglishAsr)
        btnDeleteEnglishAsr = findViewById(R.id.btnDeleteEnglishAsr)

        btnSettingsDownloadSlm = findViewById(R.id.btnSettingsDownloadSlm)
        slmModelRows = listOf(
            SlmModelRowViews(
                engine = RerankEngineType.LOCAL_SLM_3_5_0_8B,
                badge = findViewById(R.id.tvSlm35_08BStatusBadge),
                stats = findViewById(R.id.tvSlm35_08BStats),
                progress = findViewById(R.id.slm35_08BProgress),
                status = findViewById(R.id.tvSlm35_08BStatus),
                btnDownload = findViewById(R.id.btnDownloadSlm35_08B),
                btnDelete = findViewById(R.id.btnDeleteSlm35_08B)
            ),
            SlmModelRowViews(
                engine = RerankEngineType.LOCAL_SLM_3_5_2B,
                badge = findViewById(R.id.tvSlm35_2BStatusBadge),
                stats = findViewById(R.id.tvSlm35_2BStats),
                progress = findViewById(R.id.slm35_2BProgress),
                status = findViewById(R.id.tvSlm35_2BStatus),
                btnDownload = findViewById(R.id.btnDownloadSlm35_2B),
                btnDelete = findViewById(R.id.btnDeleteSlm35_2B)
            )
        )

        setupRerankEngineUI()

        val currentAlpha = KeyboardHeightManager.getSlmWeightAlpha(this)
        sliderSlmAlpha.value = currentAlpha.coerceIn(0.00f, 1.00f)
        updateAlphaDisplay(currentAlpha)

        val currentLambda = KeyboardHeightManager.getSlmLambdaObs(this)
        sliderSlmLambda.value = currentLambda.coerceIn(0.10f, 2.00f)
        updateLambdaDisplay(currentLambda)
    }

    private fun switchToTab(itemId: Int) {
        tabHome.visibility = if (itemId == R.id.nav_home) View.VISIBLE else View.GONE
        tabSettings.visibility = if (itemId == R.id.nav_settings) View.VISIBLE else View.GONE
        tabData.visibility = if (itemId == R.id.nav_data) View.VISIBLE else View.GONE
    }

    private fun setupListeners() {
        ViewCompat.setOnApplyWindowInsetsListener(coordinatorRoot) { _, insets ->
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
            val isImeVisible = insets.isVisible(WindowInsetsCompat.Type.ime()) || imeInsets.bottom > 0
            val navDecision = MainAppUiDecisionModel.evaluateBottomNavVisibility(
                MainAppUiDecisionModel.BottomNavVisibilityInput(isImeVisible = isImeVisible)
            )
            val vis = if (navDecision.visibility == MainAppUiDecisionModel.NavigationVisibility.VISIBLE) {
                View.VISIBLE
            } else {
                View.GONE
            }
            bottomNav.visibility = vis
            dividerBottomNav.visibility = vis
            insets
        }

        bottomNav.setOnItemSelectedListener { item ->
            switchToTab(item.itemId)
            true
        }

        layoutGlobalProvisioningBanner.setOnClickListener {
            val activeKind = ProvisioningForegroundService.state.value.activeTask?.kind
            bottomNav.selectedItemId = if (activeKind == ProvisioningTaskKind.APP_UPDATE_APK) {
                R.id.nav_home
            } else {
                R.id.nav_data
            }
        }

        btnClearTestInput.setOnClickListener {
            etKeyboardTest.text?.clear()
            etKeyboardTest.requestFocus()
        }

        cardQuickDictionary.setOnClickListener {
            startActivity(Intent(this, DictionaryActivity::class.java))
        }

        cardQuickSwitchIme.setOnClickListener {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showInputMethodPicker()
        }

        setupMultiDictionaryUI()
        setupJapaneseAsrUI()
        setupEnglishSpellingUI()
        setupEnglishAsrUI()
        setupSlmModelsUI()
        setupAppUpdateUI()
        btnSettingsDownloadSlm.setOnClickListener {
            val selected = KeyboardHeightManager.getRerankEngineType(this)
            if (selected != RerankEngineType.NONE) {
                startDownloadSlmModel(selected)
            }
        }

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

        rgQwertyCursorMode.setOnCheckedChangeListener { _, checkedId ->
            if (isUpdatingUiFromPrefs) return@setOnCheckedChangeListener
            val mode = when (checkedId) {
                R.id.rbCursorSpaceSwipe -> KeyboardHeightManager.QwertyCursorControlMode.SPACEBAR_SWIPE
                else -> KeyboardHeightManager.QwertyCursorControlMode.ARROW_KEYS
            }
            KeyboardHeightManager.setQwertyCursorControlMode(this, mode)
            Toast.makeText(this, "英語QWERTYカーソル移動: ${mode.displayName}", Toast.LENGTH_SHORT).show()
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

        switchAntiJitterCandidateUi.isChecked = KeyboardHeightManager.isAntiJitterCandidateUiEnabled(this)
        switchAntiJitterCandidateUi.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingUiFromPrefs) return@setOnCheckedChangeListener
            KeyboardHeightManager.setAntiJitterCandidateUiEnabled(this, isChecked)
            val status = if (isChecked) "有効 (ON: 50ms待機+AI予約枠+画面外リランク)" else "無効 (OFF: 従来方式)"
            Toast.makeText(this, "ゼロ・ジッター候補UI: $status", Toast.LENGTH_SHORT).show()
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

        setupSettingsSyncUI()

        btnLaunchOnboarding.setOnClickListener {
            launchOnboarding(
                forceLaunch = true,
                suggestedMode = null,
                reinstallHint = false
            )
        }

        tvOssLicenses.setOnClickListener {
            showOssLicensesDialog()
        }
    }

    private fun setupSettingsSyncUI() {
        btnSettingsSyncPrimary.setOnClickListener {
            val syncManager = UserHistorySyncManager.getInstance(this)
            val uri = syncManager.getSyncUri()
            val hasPerm = syncManager.hasValidSyncPermission()
            if (uri != null && hasPerm) {
                executeSettingsSync(uri)
            } else {
                promptChooseOrCreateSettingsSyncFile()
            }
        }
        btnSettingsSyncChange.setOnClickListener {
            promptChooseOrCreateSettingsSyncFile()
        }
        btnSettingsSyncUnlink.setOnClickListener {
            promptUnlinkSettingsSync()
        }
        switchSettingsAutoSync.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingUiFromPrefs) return@setOnCheckedChangeListener
            UserHistorySyncManager.getInstance(this).setAutoSyncEnabled(isChecked)
            refreshSettingsSyncCard()
            val msg = if (isChecked) "Google Drive 自動・定期同期をオンにしました" else "自動・定期同期をオフにしました（手動のみ）"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
        rgSettingsSyncInterval.setOnCheckedChangeListener { _, checkedId ->
            if (isUpdatingUiFromPrefs) return@setOnCheckedChangeListener
            val minutes = when (checkedId) {
                R.id.rbSyncInterval15m -> 15L
                R.id.rbSyncInterval360m -> 360L
                else -> 60L
            }
            UserHistorySyncManager.getInstance(this).setSyncIntervalMinutes(minutes)
            val label = when (minutes) {
                15L -> "15分ごと"
                360L -> "6時間ごと"
                else -> "1時間ごと"
            }
            Toast.makeText(this, "定期同期間隔を $label に設定しました", Toast.LENGTH_SHORT).show()
        }
        refreshSettingsSyncCard()
    }

    private fun refreshSettingsSyncCard() {
        val syncManager = UserHistorySyncManager.getInstance(this)
        val stats = syncManager.getSyncStats()
        val hasUri = stats.syncUriString != null
        val decision = HistorySyncDecisionModel.evaluateSettingsSyncCard(
            SettingsSyncCardInput(
                isSyncing = isSettingsSyncing,
                hasStoredUri = hasUri,
                hasValidPermission = stats.hasValidPermission,
                autoSyncEnabled = stats.isAutoSyncEnabled,
                lastSyncTimestamp = stats.lastSyncTimestamp,
                lastSyncError = lastSettingsSyncError
            )
        )

        tvSettingsSyncBadge.text = decision.badgeText
        val tone = when (decision.tone) {
            SyncBadgeTone.SUCCESS -> StatusTone.SUCCESS
            SyncBadgeTone.INFO -> StatusTone.INFO
            SyncBadgeTone.WARNING -> StatusTone.WARNING
            SyncBadgeTone.NEUTRAL -> StatusTone.NEUTRAL
        }
        applyBadgeTone(tvSettingsSyncBadge, tone)

        tvSettingsSyncSummary.text = when (decision.cardState) {
            SyncCardState.SYNCING -> "Google Drive と安全にマージ同期しています..."
            SyncCardState.ERROR -> "${lastSettingsSyncError ?: "同期エラー"}（ローカルデータは保護されています）"
            SyncCardState.PERMISSION_LOST -> "再インストール等により Google Drive ファイルへのアクセス権限が失効しています。ファイルを選択して再連携してください。"
            SyncCardState.CONNECTED -> {
                val dateStr = if (stats.lastSyncTimestamp > 0L) {
                    java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.getDefault())
                        .format(java.util.Date(stats.lastSyncTimestamp))
                } else {
                    "未同期"
                }
                val summary = stats.lastSyncSummary ?: "準備完了"
                "最終同期: $dateStr ($summary)"
            }
            SyncCardState.NOT_CONNECTED -> "Google Drive の同期ファイルを選択すると、学習履歴と単語辞書を定期的・安全に同期できます。"
        }

        tvSettingsSyncStats.text = "登録単語 ${stats.customDictCount}件 / 変換語 ${stats.unigramCount} / 連接 ${stats.bigramPairCount}"
        btnSettingsSyncPrimary.text = decision.primaryButtonLabel
        btnSettingsSyncPrimary.isEnabled = decision.primaryButtonEnabled
        layoutSettingsSyncSecondaryActions.visibility =
            if (decision.showChangeAndUnlinkButtons) View.VISIBLE else View.GONE
        layoutSettingsAutoSyncControls.visibility =
            if (decision.showAutoSyncControls) View.VISIBLE else View.GONE

        isUpdatingUiFromPrefs = true
        try {
            switchSettingsAutoSync.isChecked = stats.isAutoSyncEnabled
            when (stats.syncIntervalMinutes) {
                15L -> rbSyncInterval15m.isChecked = true
                360L -> rbSyncInterval360m.isChecked = true
                else -> rbSyncInterval60m.isChecked = true
            }
        } finally {
            isUpdatingUiFromPrefs = false
        }
    }

    private fun promptChooseOrCreateSettingsSyncFile() {
        val options = arrayOf("Google Drive 上の既存ファイルを選択", "Google Drive 上に新規作成 (推奨)")
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Google Drive 同期ファイルの設定")
            .setItems(options) { _, which ->
                if (which == 0) {
                    selectSettingsSyncFileLauncher.launch(arrayOf("application/json", "*/*"))
                } else {
                    createSettingsSyncFileLauncher.launch("jotinput_history_sync.json")
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun handleSettingsSyncFileSelected(uri: android.net.Uri) {
        try {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            contentResolver.takePersistableUriPermission(uri, takeFlags)
        } catch (_: Exception) {
        }
        UserHistorySyncManager.getInstance(this).setSyncUri(uri)
        lastSettingsSyncError = null
        executeSettingsSync(uri)
    }

    private fun executeSettingsSync(uri: android.net.Uri) {
        isSettingsSyncing = true
        lastSettingsSyncError = null
        refreshSettingsSyncCard()

        lifecycleScope.launch {
            val result = UserHistorySyncManager.getInstance(this@MainActivity)
                .performSync(uri, trigger = "settings_manual")
            isSettingsSyncing = false
            if (result.success) {
                lastSettingsSyncError = null
                withContext(Dispatchers.Default) {
                    AsrApp.instance.syncAllUserDictionaries()
                }
                updateDictionaryBadge()
            } else {
                lastSettingsSyncError = result.message
            }
            refreshSettingsSyncCard()
            Toast.makeText(this@MainActivity, result.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun promptUnlinkSettingsSync() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Google Drive 連携を解除")
            .setMessage("Google Drive との自動同期・定期同期を解除しますか？\n（※端末内の単語辞書や学習履歴、Drive 上のファイル自体は削除されません）")
            .setPositiveButton("解除する") { _, _ ->
                UserHistorySyncManager.getInstance(this).clearSyncUri()
                lastSettingsSyncError = null
                refreshSettingsSyncCard()
                Toast.makeText(this, "Google Drive との連携を解除しました", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun maybeLaunchOnboarding(savedInstanceState: Bundle?): Boolean {
        if (savedInstanceState != null) return false
        if (intent?.getStringExtra("bench_action") != null) return false

        val stateStore = OnboardingStateStore(this)
        val syncManager = UserHistorySyncManager.getInstance(this)
        val input = OnboardingLaunchInput(
            forceLaunch = false,
            onboardingCompleted = stateStore.isCompleted(),
            imeEnabled = isImeEnabled(),
            japaneseAsrReady = asrManager.isModelAvailable(AsrLanguage.JAPANESE),
            totalDictWords = AsrApp.instance.multiDictionaryManager.getTotalWordCount(),
            hasLocalCustomDict = dictionaryRepository.getAll().isNotEmpty(),
            hasStoredDriveUri = syncManager.getSyncUri() != null
        )
        val decision = OnboardingDecisionModel.evaluateLaunch(input)
        if (decision.shouldMarkCompletedSilently) {
            stateStore.markCompleted()
        }
        if (decision.shouldShowOnboarding) {
            val reinstallHint = decision.action == LaunchAction.LAUNCH_REINSTALL ||
                decision.action == LaunchAction.LAUNCH_REINSTALL_DRIVE_HINT
            launchOnboarding(
                forceLaunch = false,
                suggestedMode = decision.suggestedMode.name,
                reinstallHint = reinstallHint
            )
            return true
        }
        return false
    }

    private fun launchOnboarding(
        forceLaunch: Boolean,
        suggestedMode: String?,
        reinstallHint: Boolean
    ) {
        val intent = Intent(this, OnboardingActivity::class.java).apply {
            putExtra(OnboardingActivity.EXTRA_FORCE_LAUNCH, forceLaunch)
            if (suggestedMode != null) {
                putExtra(OnboardingActivity.EXTRA_SUGGESTED_MODE, suggestedMode)
            }
            putExtra(OnboardingActivity.EXTRA_REINSTALL_HINT, reinstallHint)
        }
        onboardingLauncher.launch(intent)
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
        val tone = when {
            jaReady -> StatusTone.SUCCESS
            enReady -> StatusTone.INFO
            else -> StatusTone.NEUTRAL
        }
        applyBadgeTone(tvModelStatus, tone)
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
            applyBadgeTone(tvJapaneseAsrStatusBadge, StatusTone.SUCCESS)
            tvJapaneseAsrStats.text = "約160MB (導入済: ${sizeMb} MB, Apache-2.0)"
            btnDownloadJapaneseAsr.text = "再取得"
        } else {
            tvJapaneseAsrStatusBadge.text = "未取得"
            applyBadgeTone(tvJapaneseAsrStatusBadge, StatusTone.NEUTRAL)
            tvJapaneseAsrStats.text = "約160MB (Apache-2.0)"
            btnDownloadJapaneseAsr.text = "取得"
        }
    }

    private fun checkAndInitializeModel() {
        refreshJapaneseAsrUI()
        if (asrManager.isModelAvailable()) {
            tvModelStatus.text = "音声認識: 読込中…"

            lifecycleScope.launch(Dispatchers.Default) {
                currentHotwordsFile = hotwordsGenerator.generate(dictionaryRepository.getActive())
                val success = asrManager.initialize(currentHotwordsFile)
                withContext(Dispatchers.Main) {
                    if (success) {
                        updateModelStatusBadge(japaneseReady = true)
                    } else {
                        tvModelStatus.text = "音声認識: エラー"
                        applyBadgeTone(tvModelStatus, StatusTone.WARNING)
                    }
                    refreshJapaneseAsrUI()
                    refreshHomeHeroCard()
                }
            }
        } else {
            updateModelStatusBadge(japaneseReady = false)
            refreshHomeHeroCard()
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

        // 0. Global Cross-Tab Provisioning Banner (DMN-driven)
        val bannerDecision = MainAppUiDecisionModel.evaluateProvisioningBanner(
            ProvisioningBannerInput(
                hasActiveTask = active != null,
                queuedCount = state.queuedTasks.size,
                progress = state.progress,
                statusText = state.statusText
            )
        )
        if (bannerDecision.isVisible) {
            layoutGlobalProvisioningBanner.visibility = View.VISIBLE
            tvGlobalProvisioningStatus.text = bannerDecision.title
            globalProvisioningProgress.isIndeterminate = bannerDecision.isIndeterminate
            if (!bannerDecision.isIndeterminate) {
                globalProvisioningProgress.progress = bannerDecision.progress
            }
        } else {
            layoutGlobalProvisioningBanner.visibility = View.GONE
        }

        // 1. Japanese ASR model
        val jaActive = active?.kind == ProvisioningTaskKind.JAPANESE_ASR_MODEL
        val jaQueued = state.isTaskActiveOrQueued(ProvisioningTaskKind.JAPANESE_ASR_MODEL)
        btnDownloadJapaneseAsr.isEnabled = !jaQueued
        if (jaActive) {
            japaneseAsrProgress.visibility = View.VISIBLE
            japaneseAsrProgress.progress = state.progress
            tvJapaneseAsrDownloadStatus.visibility = View.VISIBLE
            tvJapaneseAsrDownloadStatus.text = state.statusText.ifEmpty { "バックグラウンドで準備中..." }
        } else if (jaQueued) {
            japaneseAsrProgress.visibility = View.VISIBLE
            japaneseAsrProgress.progress = 0
            tvJapaneseAsrDownloadStatus.visibility = View.VISIBLE
            tvJapaneseAsrDownloadStatus.text = "待機中..."
        } else {
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

        // 5. SLM language models (Qwen3.5 / Qwen2.5)
        for (row in slmModelRows) {
            val slmActive = active?.kind == ProvisioningTaskKind.SLM_MODEL && active.dictId == row.engine.id
            val slmQueued = state.isTaskActiveOrQueued(ProvisioningTaskKind.SLM_MODEL, row.engine.id)
            row.btnDownload.isEnabled = !slmQueued
            row.btnDelete.isEnabled = !slmQueued
            if (slmActive) {
                row.progress.visibility = View.VISIBLE
                row.progress.progress = state.progress
                row.status.visibility = View.VISIBLE
                row.status.text = state.statusText.ifEmpty { "バックグラウンドで準備中..." }
            } else if (slmQueued) {
                row.progress.visibility = View.VISIBLE
                row.progress.progress = 0
                row.status.visibility = View.VISIBLE
                row.status.text = "待機中..."
            } else {
                row.progress.visibility = View.GONE
                row.status.visibility = View.GONE
            }
        }
        updateSlmStatusBadge()

        // 5.5 App build update card (debug-only GitHub artifact check / download)
        refreshAppUpdateCard(state)

        // 6. Handle completion / error event once
        val event = state.lastEvent
        if (event != null && event.eventId > lastHandledProvisioningEventId) {
            lastHandledProvisioningEventId = event.eventId
            Toast.makeText(this, event.message, Toast.LENGTH_LONG).show()
            when (event.task.kind) {
                ProvisioningTaskKind.JAPANESE_ASR_MODEL -> {
                    if (event.success) {
                        checkAndInitializeModel()
                        refreshMultiDictionaryUI()
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
                ProvisioningTaskKind.SLM_MODEL -> {
                    refreshSlmModelsUI()
                    if (event.success) {
                        val downloadedEngine = RerankEngineType.fromId(event.task.dictId ?: "")
                        if (KeyboardHeightManager.isSlmRerankEnabled(this) &&
                            KeyboardHeightManager.getRerankEngineType(this) == downloadedEngine
                        ) {
                            AsrApp.instance.llmReranker.initializeIfAvailable(
                                lifecycleScope,
                                downloadedEngine.modelFileName
                            ) {
                                runOnUiThread { updateSlmStatusBadge() }
                            }
                        }
                    }
                    updateSlmStatusBadge()
                }
                ProvisioningTaskKind.APP_UPDATE_APK -> {
                    if (event.success || event.message.contains("キャンセル")) {
                        lastAppUpdateErrorKind = null
                        lastAppUpdateErrorMessage = null
                    } else {
                        lastAppUpdateErrorKind = event.errorKind ?: AppUpdateCheckErrorKind.DOWNLOAD_FAILED
                        lastAppUpdateErrorMessage = event.message
                    }
                    refreshAppUpdateCard(state)
                }
            }
            refreshHomeHeroCard()
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
                refreshHomeHeroCard()
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
                applyBadgeTone(tvStatusBadge, StatusTone.WARNING)
                tvDates.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.badge_warning_bg))
                tvDates.setTextColor(ContextCompat.getColor(this, R.color.badge_warning_text))
                btnAction.text = "更新"
            }
            DictUpdateState.UP_TO_DATE -> {
                tvStatusBadge.text = "最新 ($wordCountLabel)"
                applyBadgeTone(tvStatusBadge, StatusTone.SUCCESS)
                tvDates.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.badge_info_bg))
                tvDates.setTextColor(ContextCompat.getColor(this, R.color.badge_info_text))
                btnAction.text = "再取得"
            }
            DictUpdateState.INSTALLED_REMOTE_UNKNOWN -> {
                tvStatusBadge.text = "導入済 ($wordCountLabel)"
                applyBadgeTone(tvStatusBadge, StatusTone.SUCCESS)
                tvDates.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.badge_neutral_bg))
                tvDates.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                btnAction.text = "再取得"
            }
            DictUpdateState.NOT_INSTALLED -> {
                tvStatusBadge.text = "未取得"
                applyBadgeTone(tvStatusBadge, StatusTone.NEUTRAL)
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
                applyBadgeTone(tvTotalDictWordsBadge, StatusTone.WARNING)
                btnDownloadAllDicts.text = "更新がある辞書を一括アップデート (${outdatedList.size}件)"
            } else {
                tvTotalDictWordsBadge.text = "辞書: $totalFormatted 語"
                applyBadgeTone(tvTotalDictWordsBadge, StatusTone.SUCCESS)
                btnDownloadAllDicts.text = "全拡張辞書を最新版に更新 ($totalFormatted 語)"
            }
        } else {
            tvTotalDictWordsBadge.text = "辞書: 未取得"
            applyBadgeTone(tvTotalDictWordsBadge, StatusTone.NEUTRAL)
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

    private fun startDownloadAllDicts() {
        startBackgroundProvisioning(ProvisioningTaskKind.ALL_OR_OUTDATED_DICTIONARIES)
    }

    private fun refreshConnectionMatrixBadge() {
        val manager = AsrApp.instance.connectionDataManager
        val isInstalled = manager.isInstalled()
        if (isInstalled) {
            tvConnectionMatrixBadge.text = "連接: 導入済"
            applyBadgeTone(tvConnectionMatrixBadge, StatusTone.SUCCESS)
        } else {
            tvConnectionMatrixBadge.text = "連接: 未導入"
            applyBadgeTone(tvConnectionMatrixBadge, StatusTone.NEUTRAL)
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
            applyBadgeTone(tvEnglishSpellingStatusBadge, StatusTone.SUCCESS)
            tvEnglishSpellingStats.text = "約27MB (導入済: ${sizeMb} MB)"
            btnDownloadEnglishSpelling.text = "再取得"
            btnDeleteEnglishSpelling.visibility = View.VISIBLE
        } else {
            tvEnglishSpellingStatusBadge.text = "未取得"
            applyBadgeTone(tvEnglishSpellingStatusBadge, StatusTone.NEUTRAL)
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
            applyBadgeTone(tvEnglishAsrStatusBadge, StatusTone.SUCCESS)
            tvEnglishAsrStats.text = "約126MB (導入済: ${sizeMb} MB, CC-BY-4.0)"
            btnDownloadEnglishAsr.text = "再取得"
            btnDeleteEnglishAsr.visibility = View.VISIBLE
        } else {
            tvEnglishAsrStatusBadge.text = "未取得"
            applyBadgeTone(tvEnglishAsrStatusBadge, StatusTone.NEUTRAL)
            tvEnglishAsrStats.text = "約126MB (CC-BY-4.0)"
            btnDownloadEnglishAsr.text = "取得"
            btnDeleteEnglishAsr.visibility = View.GONE
        }
        updateModelStatusBadge()
    }

    private fun startDownloadEnglishAsr() {
        startBackgroundProvisioning(ProvisioningTaskKind.ENGLISH_ASR_MODEL)
    }

    private fun setupSlmModelsUI() {
        SlmModelDownloader.cleanupLegacyModels(this)

        for (row in slmModelRows) {
            row.btnDownload.setOnClickListener {
                startDownloadSlmModel(row.engine)
            }
            row.btnDelete.setOnClickListener {
                val reranker = AsrApp.instance.llmReranker
                if (reranker.getTargetModelFileName() == row.engine.modelFileName &&
                    reranker.state == LlmReranker.State.READY
                ) {
                    reranker.release()
                }
                SlmModelDownloader.deleteModel(this, row.engine)
                Toast.makeText(this, "${row.engine.displayName} を削除しました", Toast.LENGTH_SHORT).show()
                refreshSlmModelsUI()
                updateSlmStatusBadge()
            }
        }
        refreshSlmModelsUI()
    }

    private fun refreshSlmModelsUI() {
        val reranker = AsrApp.instance.llmReranker
        for (row in slmModelRows) {
            val isInstalled = reranker.isModelInstalled(row.engine.modelFileName)
            if (isInstalled) {
                val modelFile = reranker.getModelFile(row.engine.modelFileName)
                val sizeMb = String.format(java.util.Locale.US, "%.0f", modelFile.length() / (1024.0 * 1024.0))
                row.badge.text = "導入済"
                applyBadgeTone(row.badge, StatusTone.SUCCESS)
                row.stats.text = "${row.engine.sizeLabel} (導入済: ${sizeMb} MB, Apache-2.0)"
                row.btnDownload.text = "再取得"
                row.btnDelete.visibility = View.VISIBLE
            } else {
                row.badge.text = "未取得"
                applyBadgeTone(row.badge, StatusTone.NEUTRAL)
                row.stats.text = "${row.engine.sizeLabel} (Apache-2.0)"
                row.btnDownload.text = "取得"
                row.btnDelete.visibility = View.GONE
            }
        }
    }

    private fun startDownloadSlmModel(engine: RerankEngineType) {
        if (engine == RerankEngineType.NONE) return
        startBackgroundProvisioning(ProvisioningTaskKind.SLM_MODEL, engine.id)
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
            when (KeyboardHeightManager.getQwertyCursorControlMode(this)) {
                KeyboardHeightManager.QwertyCursorControlMode.ARROW_KEYS -> rbCursorArrowKeys.isChecked = true
                KeyboardHeightManager.QwertyCursorControlMode.SPACEBAR_SWIPE -> rbCursorSpaceSwipe.isChecked = true
            }
            switchQwertySwipeTyping.isChecked = KeyboardHeightManager.isQwertySwipeTypingEnabled(this)
            switchStreamingVoiceInput.isChecked = KeyboardHeightManager.isStreamingVoiceInputEnabled(this)
            switchDebugDisplay.isChecked = KeyboardHeightManager.isDebugDisplayEnabled(this)
            switchAntiJitterCandidateUi.isChecked = KeyboardHeightManager.isAntiJitterCandidateUiEnabled(this)
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
        handleAppUpdateInstallIntent(intent)
    }

    private fun setupAppUpdateUI() {
        btnAppUpdateToken.setOnClickListener {
            showGitHubTokenDialog()
        }
        refreshAppUpdateCard()
    }

    private fun refreshAppUpdateCard(
        provisioningState: ProvisioningUiState = ProvisioningForegroundService.state.value
    ) {
        val repo = AsrApp.instance.appUpdateRepository
        val installed = repo.getInstalledBuildInfo()
        val activeKind = provisioningState.activeTask?.kind
        val isDownloadActive = activeKind == ProvisioningTaskKind.APP_UPDATE_APK
        val isDownloadQueued = !isDownloadActive &&
            provisioningState.isTaskActiveOrQueued(ProvisioningTaskKind.APP_UPDATE_APK)
        val cachedRemote = repo.getCachedRemoteArtifact()
        val freshness = repo.getCurrentFreshnessStatus()
        val downloadedApk = repo.getMatchingDownloadedApk(cachedRemote)

        val decision = AppUpdateDecisionModel.evaluateUi(
            AppUpdateDecisionModel.AppUpdateUiInput(
                isDebugBuild = installed.isDebugBuild,
                hasGitHubToken = repo.hasToken(),
                isChecking = isCheckingAppUpdate,
                isDownloadActive = isDownloadActive,
                isDownloadQueued = isDownloadQueued,
                freshnessStatus = freshness,
                hasMatchingDownloadedApk = downloadedApk != null,
                lastErrorKind = lastAppUpdateErrorKind
            )
        )

        cardAppUpdate.visibility = if (decision.isCardVisible) View.VISIBLE else View.GONE
        if (!decision.isCardVisible) return

        tvAppUpdateBadge.text = decision.badgeText
        val tone = when (decision.badgeTone) {
            AppUpdateBadgeTone.SUCCESS -> StatusTone.SUCCESS
            AppUpdateBadgeTone.INFO -> StatusTone.INFO
            AppUpdateBadgeTone.WARNING -> StatusTone.WARNING
            AppUpdateBadgeTone.NEUTRAL -> StatusTone.NEUTRAL
        }
        applyBadgeTone(tvAppUpdateBadge, tone)

        btnAppUpdateToken.text = if (repo.hasToken()) "トークン変更" else "トークン設定"
        appUpdateProgress.visibility = if (decision.showProgressBar) View.VISIBLE else View.GONE
        if (decision.showProgressBar) {
            appUpdateProgress.isIndeterminate = isDownloadQueued
            if (!isDownloadQueued) {
                appUpdateProgress.progress = provisioningState.progress
            }
        }

        val localRunSuffix = if (installed.githubRunId > 0L) " #${installed.githubRunId}" else ""
        val localLine = "現在: v${installed.versionName} (${installed.shortSha})$localRunSuffix"
        val dateFormat = java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.getDefault())

        tvAppUpdateSummary.text = when (decision.cardState) {
            AppUpdateCardState.HIDDEN -> ""
            AppUpdateCardState.DOWNLOADING ->
                provisioningState.statusText.ifEmpty { "バックグラウンドで最新ビルドを取得中..." }
            AppUpdateCardState.QUEUED ->
                "他の処理の完了後にバックグラウンド取得を開始します..."
            AppUpdateCardState.CHECKING ->
                "GitHub Actions の最新ビルドを確認中..."
            AppUpdateCardState.READY_TO_INSTALL -> {
                val dlMb = String.format(
                    java.util.Locale.US,
                    "%.1f",
                    (downloadedApk?.sizeInBytes ?: 0L) / (1024.0 * 1024.0)
                )
                val sha = downloadedApk?.shortSha ?: "unknown"
                "展開済み APK 準備完了 ($sha / ${dlMb} MB)。タップして更新できます。\n$localLine"
            }
            AppUpdateCardState.TOKEN_MISSING ->
                "GitHub 未連携です (Fail-Safe)。確認するには開発用トークン (Actions: Read-only) を設定してください。\n$localLine"
            AppUpdateCardState.AUTH_ERROR ->
                "${lastAppUpdateErrorMessage ?: "GitHub 認証に失敗しました"}。\nトークン権限 (Actions: Read-only) を確認してください。"
            AppUpdateCardState.NETWORK_ERROR ->
                "${lastAppUpdateErrorMessage ?: "GitHub へ接続できませんでした"}。\n$localLine"
            AppUpdateCardState.UPDATE_AVAILABLE -> {
                val sizeMb = String.format(
                    java.util.Locale.US,
                    "%.1f",
                    (cachedRemote?.sizeInBytes ?: 0L) / (1024.0 * 1024.0)
                )
                val remoteTime = cachedRemote?.createdAtMs?.takeIf { it > 0L }
                    ?.let { dateFormat.format(java.util.Date(it)) } ?: "時刻不明"
                "新しいビルドがあります: ${cachedRemote?.shortSha ?: "unknown"} ($remoteTime / ZIP ${sizeMb} MB)\n$localLine"
            }
            AppUpdateCardState.UP_TO_DATE -> {
                val checkedStr = repo.getLastCheckEpochMs().takeIf { it > 0L }
                    ?.let { dateFormat.format(java.util.Date(it)) } ?: "たった今"
                "実行中のビルドは最新です (最終確認: $checkedStr)\n$localLine"
            }
            AppUpdateCardState.NO_ARTIFACT ->
                "main ブランチに有効な最新ビルドが見つかりません (期限切れ等)。\n$localLine"
            AppUpdateCardState.IDLE ->
                "main ブランチの最新ビルドが GitHub にあるか確認できます。\n$localLine"
        }

        btnAppUpdatePrimary.text = decision.primaryButtonLabel
        btnAppUpdatePrimary.isEnabled = decision.primaryButtonEnabled
        btnAppUpdatePrimary.setOnClickListener {
            when (decision.primaryAction) {
                AppUpdatePrimaryAction.CONFIGURE_TOKEN -> showGitHubTokenDialog()
                AppUpdatePrimaryAction.CHECK_UPDATE -> triggerAppUpdateCheck()
                AppUpdatePrimaryAction.DOWNLOAD_UPDATE -> startAppUpdateDownload()
                AppUpdatePrimaryAction.CANCEL_DOWNLOAD ->
                    ProvisioningForegroundService.cancelKind(this, ProvisioningTaskKind.APP_UPDATE_APK)
                AppUpdatePrimaryAction.INSTALL_APK -> triggerInstallDownloadedApk()
                AppUpdatePrimaryAction.NONE -> {}
            }
        }

        btnAppUpdateSecondary.visibility = if (decision.showSecondaryCheckButton) View.VISIBLE else View.GONE
        btnAppUpdateSecondary.setOnClickListener {
            triggerAppUpdateCheck()
        }
    }

    private fun triggerAppUpdateCheck() {
        val repo = AsrApp.instance.appUpdateRepository
        if (!repo.hasToken()) {
            lastAppUpdateErrorKind = AppUpdateCheckErrorKind.TOKEN_REQUIRED
            lastAppUpdateErrorMessage = "GitHub トークンが未設定です"
            refreshAppUpdateCard()
            showGitHubTokenDialog()
            return
        }
        if (isCheckingAppUpdate) return

        isCheckingAppUpdate = true
        lastAppUpdateErrorKind = null
        lastAppUpdateErrorMessage = null
        refreshAppUpdateCard()

        lifecycleScope.launch {
            try {
                when (val result = repo.checkLatestBuild()) {
                    is AppUpdateCheckResult.Checked -> {
                        lastAppUpdateErrorKind = null
                        lastAppUpdateErrorMessage = null
                        val toastMsg = when (result.freshness) {
                            com.example.myasr.context.provisioning.domain.BuildFreshnessStatus.UPDATE_AVAILABLE ->
                                "新しいビルド (${result.artifact?.shortSha ?: ""}) が見つかりました"
                            com.example.myasr.context.provisioning.domain.BuildFreshnessStatus.UP_TO_DATE ->
                                "実行中のアプリは最新ビルドです"
                            com.example.myasr.context.provisioning.domain.BuildFreshnessStatus.NO_ARTIFACT ->
                                "有効な最新アーティファクトが見つかりませんでした"
                        }
                        Toast.makeText(this@MainActivity, toastMsg, Toast.LENGTH_SHORT).show()
                    }
                    is AppUpdateCheckResult.Failure -> {
                        lastAppUpdateErrorKind = result.errorKind
                        lastAppUpdateErrorMessage = result.message
                        Toast.makeText(this@MainActivity, result.message, Toast.LENGTH_LONG).show()
                    }
                }
            } finally {
                isCheckingAppUpdate = false
                refreshAppUpdateCard()
            }
        }
    }

    private fun startAppUpdateDownload() {
        val repo = AsrApp.instance.appUpdateRepository
        if (!repo.hasToken()) {
            showGitHubTokenDialog()
            return
        }
        lastAppUpdateErrorKind = null
        lastAppUpdateErrorMessage = null
        startBackgroundProvisioning(ProvisioningTaskKind.APP_UPDATE_APK)
    }

    private fun showGitHubTokenDialog() {
        val repo = AsrApp.instance.appUpdateRepository
        val masked = repo.tokenStore.getMaskedPreview()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }
        val input = EditText(this).apply {
            hint = "github_pat_..."
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        container.addView(input)

        val currentLine = if (masked != null) "\n現在設定中: $masked" else ""
        val msg = "kenichikurihara/jotinput の Fine-Grained PAT (Actions: Read-only) を入力してください。\n" +
            "Android Keystore で暗号化保存され、未設定・アクセス不可時は安全に Fail-Safe します。" +
            currentLine

        val builder = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("開発用 GitHub トークン設定")
            .setMessage(msg)
            .setView(container)
            .setPositiveButton("保存して確認") { _, _ ->
                val raw = input.text?.toString()?.trim().orEmpty()
                if (raw.isEmpty()) {
                    Toast.makeText(this, "トークンが空のため保存しませんでした", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (repo.tokenStore.saveToken(raw)) {
                    lastAppUpdateErrorKind = null
                    lastAppUpdateErrorMessage = null
                    refreshAppUpdateCard()
                    triggerAppUpdateCheck()
                } else {
                    Toast.makeText(this, "トークンの保存に失敗しました", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("キャンセル", null)

        if (repo.hasToken()) {
            builder.setNeutralButton("削除") { _, _ ->
                repo.tokenStore.clearToken()
                repo.clearCachedRemoteState()
                lastAppUpdateErrorKind = null
                lastAppUpdateErrorMessage = null
                refreshAppUpdateCard()
                Toast.makeText(this, "GitHub トークンを削除しました", Toast.LENGTH_SHORT).show()
            }
        }
        builder.show()
    }

    private fun triggerInstallDownloadedApk() {
        val repo = AsrApp.instance.appUpdateRepository
        val downloaded = repo.getMatchingDownloadedApk()
        if (downloaded == null) {
            Toast.makeText(this, "インストール可能な更新 APK が見つかりません", Toast.LENGTH_SHORT).show()
            refreshAppUpdateCard()
            return
        }
        val apkFile = File(downloaded.filePath)
        if (!AppUpdateInstaller.canRequestPackageInstalls(this)) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("アプリのインストール許可が必要です")
                .setMessage("ダウンロード済み APK を更新インストールするため、このアプリの「不明なアプリのインストール」を許可してください。")
                .setPositiveButton("設定を開く") { _, _ ->
                    pendingInstallAfterPermissionGrant = true
                    unknownSourcesPermissionLauncher.launch(
                        AppUpdateInstaller.buildUnknownSourcesSettingsIntent(this)
                    )
                }
                .setNegativeButton("あとで", null)
                .show()
            return
        }
        val launchRes = runCatching {
            startActivity(AppUpdateInstaller.buildInstallIntent(this, apkFile))
        }
        if (launchRes.isFailure) {
            Toast.makeText(
                this,
                "インストーラの起動に失敗しました: ${launchRes.exceptionOrNull()?.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun handleAppUpdateInstallIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_TRIGGER_APP_UPDATE_INSTALL, false) == true) {
            intent.removeExtra(EXTRA_TRIGGER_APP_UPDATE_INSTALL)
            bottomNav.selectedItemId = R.id.nav_home
            triggerInstallDownloadedApk()
        }
    }

    private fun checkBenchmarkIntent(intent: Intent?) {
        val action = intent?.getStringExtra("bench_action") ?: return
        val engine = intent.getStringExtra("bench_engine") ?: "local_slm_3_5_0_8b"
        val workload = intent.getStringExtra("bench_workload") ?: "miss"
        val iters = intent.getIntExtra("bench_iters", 200)
        PowerBenchmarkRunner.handleIntent(this, lifecycleScope, action, engine, workload, iters)
    }

    override fun onDestroy() {
        super.onDestroy()
        asrManager.release()
    }
}
