package com.example.myasr

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.myasr.context.ime.domain.MainAppUiDecisionModel
import com.example.myasr.context.ime.domain.MainAppUiDecisionModel.ProvisioningBannerInput
import com.example.myasr.context.ime.domain.MainAppUiDecisionModel.StatusTone
import com.example.myasr.context.ime.domain.OnboardingDecisionModel
import com.example.myasr.context.ime.domain.OnboardingDecisionModel.DriveRestoreInput
import com.example.myasr.context.ime.domain.OnboardingDecisionModel.OnboardingMode
import com.example.myasr.context.ime.domain.OnboardingDecisionModel.OnboardingStep
import com.example.myasr.context.ime.domain.OnboardingDecisionModel.StepGuidanceInput
import com.example.myasr.context.ime.infrastructure.OnboardingStateStore
import com.example.myasr.context.kanakanji.application.UserHistorySyncManager
import com.example.myasr.context.provisioning.domain.DictUpdateState
import com.example.myasr.context.provisioning.infrastructure.ProvisioningForegroundService
import com.example.myasr.context.provisioning.infrastructure.ProvisioningTaskKind
import com.example.myasr.context.provisioning.infrastructure.ProvisioningUiState
import com.example.myasr.context.speech.domain.AsrLanguage
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Non-blocking Onboarding Wizard supporting:
 * - CUJ-OB1: Fresh Install
 * - CUJ-OB2: Reinstall (including Android Auto Backup permission-loss recovery)
 * - CUJ-OB3: Google Drive History Restore
 * - CUJ-OB4: Background provisioning continuation & interruption recovery
 */
class OnboardingActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SUGGESTED_MODE = "extra_suggested_mode"
        const val EXTRA_REINSTALL_HINT = "extra_reinstall_hint"
        const val EXTRA_FORCE_LAUNCH = "extra_force_launch"

        private const val KEY_STATE_MODE = "state_mode"
        private const val KEY_STATE_STEP = "state_step"
    }

    private lateinit var stateStore: OnboardingStateStore
    private lateinit var syncManager: UserHistorySyncManager

    // Header & Global Banner
    private lateinit var tvStepCounter: TextView
    private lateinit var stepProgressIndicator: LinearProgressIndicator
    private lateinit var layoutOnboardingProvisioningBanner: LinearLayout
    private lateinit var tvOnboardingProvisioningStatus: TextView
    private lateinit var onboardingProvisioningProgress: LinearProgressIndicator
    private lateinit var tvStepTitle: TextView
    private lateinit var tvStepSubtitle: TextView

    // Step Containers
    private lateinit var layoutStepWelcome: LinearLayout
    private lateinit var layoutStepDataPrep: LinearLayout
    private lateinit var layoutStepDriveRestore: LinearLayout
    private lateinit var layoutStepPermissionsIme: LinearLayout
    private lateinit var layoutStepComplete: LinearLayout

    // Step 0: Welcome
    private lateinit var layoutReinstallHintBanner: LinearLayout
    private lateinit var tvReinstallHintTitle: TextView
    private lateinit var tvReinstallHintDesc: TextView
    private lateinit var cardModeFresh: MaterialCardView
    private lateinit var cardModeDriveRestore: MaterialCardView
    private lateinit var cardModeReinstall: MaterialCardView

    // Step 1: Data Prep
    private lateinit var tvObAsrBadge: TextView
    private lateinit var tvObDictBadge: TextView
    private lateinit var btnObStartDownloads: MaterialButton
    private lateinit var layoutObDownloadError: LinearLayout
    private lateinit var tvObDownloadErrorMsg: TextView

    // Step 2: Drive Restore
    private lateinit var tvObDriveBadge: TextView
    private lateinit var tvObDriveDescription: TextView
    private lateinit var btnObDrivePrimary: MaterialButton
    private lateinit var btnObDriveCreateNew: MaterialButton

    // Step 3: Permissions & IME
    private lateinit var tvObMicStatus: TextView
    private lateinit var btnObGrantMic: MaterialButton
    private lateinit var cardObNotificationPerm: MaterialCardView
    private lateinit var tvObNotifStatus: TextView
    private lateinit var btnObGrantNotif: MaterialButton
    private lateinit var tvObImeEnabledStatus: TextView
    private lateinit var btnObEnableIme: MaterialButton
    private lateinit var tvObImeSelectedStatus: TextView
    private lateinit var btnObSelectIme: MaterialButton

    // Step 4: Complete & Test
    private lateinit var layoutObCompleteBgInfo: LinearLayout
    private lateinit var tvObCompleteBgStatus: TextView
    private lateinit var btnObClearTest: TextView
    private lateinit var etOnboardingTest: EditText

    // Bottom Bar
    private lateinit var btnObBack: MaterialButton
    private lateinit var btnObNext: MaterialButton

    // Flow State
    private var currentMode: OnboardingMode = OnboardingMode.FRESH_INSTALL
    private var currentStep: OnboardingStep = OnboardingStep.WELCOME
    private var showReinstallHint: Boolean = false

    // Drive Restore Transient UI State
    private var isSyncingDrive = false
    private var lastSyncSuccess = false
    private var lastRestoredCount = 0
    private var lastDriveError: String? = null

    // Provisioning State Tracking
    private var lastDownloadErrorMessage: String? = null
    private var lastHandledProvisioningEventId: Long =
        ProvisioningForegroundService.state.value.lastEvent?.eventId ?: 0L

    private val requestMicPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, "音声入力にはマイク権限が必要です（設定から後で許可できます）", Toast.LENGTH_SHORT).show()
        }
        renderCurrentStep()
    }

    private val requestNotifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        renderCurrentStep()
    }

    private val selectSyncFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            handleDriveUriSelected(uri)
        }
    }

    private val createSyncFileLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null) {
            handleDriveUriSelected(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        stateStore = OnboardingStateStore(this)
        syncManager = UserHistorySyncManager.getInstance(this)
        showReinstallHint = intent.getBooleanExtra(EXTRA_REINSTALL_HINT, false)

        restoreFlowState(savedInstanceState)
        initViews()
        setupListeners()
        observeProvisioningState()
        renderCurrentStep()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                navigateBack()
            }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_STATE_MODE, currentMode.name)
        outState.putString(KEY_STATE_STEP, currentStep.name)
    }

    override fun onResume() {
        super.onResume()
        renderCurrentStep()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            renderCurrentStep()
        }
    }

    private fun restoreFlowState(savedInstanceState: Bundle?) {
        val forceLaunch = intent.getBooleanExtra(EXTRA_FORCE_LAUNCH, false)
        val suggestedModeName = intent.getStringExtra(EXTRA_SUGGESTED_MODE)
        val suggestedMode = suggestedModeName?.let {
            runCatching { OnboardingMode.valueOf(it) }.getOrNull()
        }

        if (savedInstanceState != null) {
            currentMode = savedInstanceState.getString(KEY_STATE_MODE)
                ?.let { runCatching { OnboardingMode.valueOf(it) }.getOrNull() }
                ?: OnboardingMode.FRESH_INSTALL
            currentStep = savedInstanceState.getString(KEY_STATE_STEP)
                ?.let { runCatching { OnboardingStep.valueOf(it) }.getOrNull() }
                ?: OnboardingStep.WELCOME
        } else if (!forceLaunch && !stateStore.isCompleted()) {
            currentMode = stateStore.getSavedMode() ?: suggestedMode ?: OnboardingMode.FRESH_INSTALL
            currentStep = stateStore.getSavedStep() ?: OnboardingStep.WELCOME
        } else {
            currentMode = suggestedMode ?: OnboardingMode.FRESH_INSTALL
            currentStep = OnboardingStep.WELCOME
        }
    }

    private fun initViews() {
        tvStepCounter = findViewById(R.id.tvStepCounter)
        stepProgressIndicator = findViewById(R.id.stepProgressIndicator)
        layoutOnboardingProvisioningBanner = findViewById(R.id.layoutOnboardingProvisioningBanner)
        tvOnboardingProvisioningStatus = findViewById(R.id.tvOnboardingProvisioningStatus)
        onboardingProvisioningProgress = findViewById(R.id.onboardingProvisioningProgress)
        tvStepTitle = findViewById(R.id.tvStepTitle)
        tvStepSubtitle = findViewById(R.id.tvStepSubtitle)

        layoutStepWelcome = findViewById(R.id.layoutStepWelcome)
        layoutStepDataPrep = findViewById(R.id.layoutStepDataPrep)
        layoutStepDriveRestore = findViewById(R.id.layoutStepDriveRestore)
        layoutStepPermissionsIme = findViewById(R.id.layoutStepPermissionsIme)
        layoutStepComplete = findViewById(R.id.layoutStepComplete)

        layoutReinstallHintBanner = findViewById(R.id.layoutReinstallHintBanner)
        tvReinstallHintTitle = findViewById(R.id.tvReinstallHintTitle)
        tvReinstallHintDesc = findViewById(R.id.tvReinstallHintDesc)
        cardModeFresh = findViewById(R.id.cardModeFresh)
        cardModeDriveRestore = findViewById(R.id.cardModeDriveRestore)
        cardModeReinstall = findViewById(R.id.cardModeReinstall)

        tvObAsrBadge = findViewById(R.id.tvObAsrBadge)
        tvObDictBadge = findViewById(R.id.tvObDictBadge)
        btnObStartDownloads = findViewById(R.id.btnObStartDownloads)
        layoutObDownloadError = findViewById(R.id.layoutObDownloadError)
        tvObDownloadErrorMsg = findViewById(R.id.tvObDownloadErrorMsg)

        tvObDriveBadge = findViewById(R.id.tvObDriveBadge)
        tvObDriveDescription = findViewById(R.id.tvObDriveDescription)
        btnObDrivePrimary = findViewById(R.id.btnObDrivePrimary)
        btnObDriveCreateNew = findViewById(R.id.btnObDriveCreateNew)

        tvObMicStatus = findViewById(R.id.tvObMicStatus)
        btnObGrantMic = findViewById(R.id.btnObGrantMic)
        cardObNotificationPerm = findViewById(R.id.cardObNotificationPerm)
        tvObNotifStatus = findViewById(R.id.tvObNotifStatus)
        btnObGrantNotif = findViewById(R.id.btnObGrantNotif)
        tvObImeEnabledStatus = findViewById(R.id.tvObImeEnabledStatus)
        btnObEnableIme = findViewById(R.id.btnObEnableIme)
        tvObImeSelectedStatus = findViewById(R.id.tvObImeSelectedStatus)
        btnObSelectIme = findViewById(R.id.btnObSelectIme)

        layoutObCompleteBgInfo = findViewById(R.id.layoutObCompleteBgInfo)
        tvObCompleteBgStatus = findViewById(R.id.tvObCompleteBgStatus)
        btnObClearTest = findViewById(R.id.btnObClearTest)
        etOnboardingTest = findViewById(R.id.etOnboardingTest)

        btnObBack = findViewById(R.id.btnObBack)
        btnObNext = findViewById(R.id.btnObNext)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            cardObNotificationPerm.visibility = View.GONE
        }
    }

    private fun setupListeners() {
        cardModeFresh.setOnClickListener {
            currentMode = OnboardingMode.FRESH_INSTALL
            stateStore.saveProgress(currentMode, currentStep)
            renderModeSelectionCards()
        }
        cardModeDriveRestore.setOnClickListener {
            currentMode = OnboardingMode.DRIVE_RESTORE
            stateStore.saveProgress(currentMode, currentStep)
            renderModeSelectionCards()
        }
        cardModeReinstall.setOnClickListener {
            currentMode = OnboardingMode.REINSTALL
            stateStore.saveProgress(currentMode, currentStep)
            renderModeSelectionCards()
        }

        btnObStartDownloads.setOnClickListener {
            enqueueRequiredBackgroundDownloads()
        }

        btnObDrivePrimary.setOnClickListener {
            val hasUri = syncManager.getSyncUri() != null
            val hasPerm = syncManager.hasValidSyncPermission()
            if (hasUri && hasPerm && lastDriveError == null) {
                executeDriveSync(syncManager.getSyncUri()!!)
            } else {
                selectSyncFileLauncher.launch(arrayOf("application/json", "*/*"))
            }
        }
        btnObDriveCreateNew.setOnClickListener {
            createSyncFileLauncher.launch("jotinput_history_sync.json")
        }

        btnObGrantMic.setOnClickListener {
            requestMicPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
        btnObGrantNotif.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestNotifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        btnObEnableIme.setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        btnObSelectIme.setOnClickListener {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showInputMethodPicker()
        }

        btnObClearTest.setOnClickListener {
            etOnboardingTest.setText("")
        }

        btnObBack.setOnClickListener {
            navigateBack()
        }
        btnObNext.setOnClickListener {
            navigateNext()
        }
    }

    private fun enqueueRequiredBackgroundDownloads() {
        lastDownloadErrorMessage = null
        val app = AsrApp.instance
        val provState = ProvisioningForegroundService.state.value
        var enqueuedAny = false

        if (!app.asrManager.isModelAvailable(AsrLanguage.JAPANESE) &&
            !provState.isTaskActiveOrQueued(ProvisioningTaskKind.JAPANESE_ASR_MODEL)
        ) {
            ProvisioningForegroundService.enqueue(this, ProvisioningTaskKind.JAPANESE_ASR_MODEL)
            enqueuedAny = true
        }

        val outdatedCount = app.dictionaryUpdateTracker.getAllStatusInfo()
            .count { it.state == DictUpdateState.UPDATE_AVAILABLE }
        val totalWords = app.multiDictionaryManager.getTotalWordCount()
        if ((totalWords <= 0 || outdatedCount > 0) &&
            !provState.isTaskActiveOrQueued(ProvisioningTaskKind.ALL_OR_OUTDATED_DICTIONARIES)
        ) {
            ProvisioningForegroundService.enqueue(this, ProvisioningTaskKind.ALL_OR_OUTDATED_DICTIONARIES)
            enqueuedAny = true
        }

        if (enqueuedAny) {
            Toast.makeText(
                this,
                "バックグラウンドでダウンロードを開始しました。このまま次へ進めます。",
                Toast.LENGTH_SHORT
            ).show()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            Toast.makeText(this, "すでに準備済みまたはバックグラウンド実行中です。", Toast.LENGTH_SHORT).show()
        }
        renderCurrentStep()
    }

    private fun handleDriveUriSelected(uri: Uri) {
        try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: Exception) {
            // Some SAF providers only grant temporary access; sync still attempts read/write.
        }
        syncManager.setSyncUri(uri)
        lastDriveError = null
        executeDriveSync(uri)
    }

    private fun executeDriveSync(uri: Uri) {
        isSyncingDrive = true
        lastDriveError = null
        renderCurrentStep()

        lifecycleScope.launch {
            val result = syncManager.performSync(uri, trigger = "onboarding_restore")
            isSyncingDrive = false
            if (result.success) {
                lastSyncSuccess = true
                lastRestoredCount = result.mergedCustomDictCount + result.mergedUnigramCount
                lastDriveError = null
                withContext(Dispatchers.Default) {
                    AsrApp.instance.syncAllUserDictionaries()
                }
            } else {
                lastSyncSuccess = false
                lastDriveError = result.message
            }
            renderCurrentStep()
        }
    }

    private fun observeProvisioningState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ProvisioningForegroundService.state.collect { state ->
                    handleProvisioningUpdate(state)
                }
            }
        }
    }

    private fun handleProvisioningUpdate(state: ProvisioningUiState) {
        val bannerDecision = MainAppUiDecisionModel.evaluateProvisioningBanner(
            ProvisioningBannerInput(
                hasActiveTask = state.activeTask != null,
                queuedCount = state.queuedTasks.size,
                progress = state.progress,
                statusText = state.statusText
            )
        )

        if (bannerDecision.isVisible) {
            layoutOnboardingProvisioningBanner.visibility = View.VISIBLE
            tvOnboardingProvisioningStatus.text = bannerDecision.title
            onboardingProvisioningProgress.isIndeterminate = bannerDecision.isIndeterminate
            if (!bannerDecision.isIndeterminate) {
                onboardingProvisioningProgress.progress = bannerDecision.progress
            }
        } else {
            layoutOnboardingProvisioningBanner.visibility = View.GONE
        }

        val event = state.lastEvent
        if (event != null && event.eventId > lastHandledProvisioningEventId) {
            lastHandledProvisioningEventId = event.eventId
            if (event.success) {
                lastDownloadErrorMessage = null
                if (event.task.kind == ProvisioningTaskKind.JAPANESE_ASR_MODEL) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        AsrApp.instance.ensureModelInitialized(AsrLanguage.JAPANESE)
                        withContext(Dispatchers.Main) {
                            renderCurrentStep()
                        }
                    }
                }
            } else {
                lastDownloadErrorMessage = event.message
            }
        }
        renderCurrentStep()
    }

    private fun navigateNext() {
        val steps = OnboardingDecisionModel.stepsForMode(currentMode)
        val idx = steps.indexOf(currentStep)
        if (idx >= 0 && idx < steps.size - 1) {
            currentStep = steps[idx + 1]
            stateStore.saveProgress(currentMode, currentStep)
            renderCurrentStep()
        } else {
            stateStore.markCompleted()
            setResult(RESULT_OK)
            finish()
        }
    }

    private fun navigateBack() {
        val steps = OnboardingDecisionModel.stepsForMode(currentMode)
        val idx = steps.indexOf(currentStep)
        if (idx > 0) {
            currentStep = steps[idx - 1]
            stateStore.saveProgress(currentMode, currentStep)
            renderCurrentStep()
        } else {
            // Allow leaving onboarding gracefully if user presses system back on Step 0
            finish()
        }
    }

    private fun renderCurrentStep() {
        val steps = OnboardingDecisionModel.stepsForMode(currentMode)
        val stepIndex = steps.indexOf(currentStep).coerceAtLeast(0)
        tvStepCounter.text = "Step ${stepIndex + 1} / ${steps.size}"
        stepProgressIndicator.progress = (((stepIndex + 1).toFloat() / steps.size) * 100).toInt()
        btnObBack.visibility = if (stepIndex > 0) View.VISIBLE else View.GONE

        layoutStepWelcome.visibility = if (currentStep == OnboardingStep.WELCOME) View.VISIBLE else View.GONE
        layoutStepDataPrep.visibility = if (currentStep == OnboardingStep.DATA_PREP) View.VISIBLE else View.GONE
        layoutStepDriveRestore.visibility = if (currentStep == OnboardingStep.DRIVE_RESTORE) View.VISIBLE else View.GONE
        layoutStepPermissionsIme.visibility = if (currentStep == OnboardingStep.PERMISSIONS_AND_IME) View.VISIBLE else View.GONE
        layoutStepComplete.visibility = if (currentStep == OnboardingStep.COMPLETE_AND_TEST) View.VISIBLE else View.GONE

        val app = AsrApp.instance
        val asrReady = app.asrManager.isModelAvailable(AsrLanguage.JAPANESE)
        val totalDictWords = app.multiDictionaryManager.getTotalWordCount()
        val provState = ProvisioningForegroundService.state.value
        val provActive = provState.isRunning || provState.activeTask != null || provState.queuedTasks.isNotEmpty()

        val driveDecision = OnboardingDecisionModel.evaluateDriveRestoreStatus(
            DriveRestoreInput(
                isSyncing = isSyncingDrive,
                hasStoredUri = syncManager.getSyncUri() != null,
                hasValidPermission = syncManager.hasValidSyncPermission(),
                lastSyncSuccess = lastSyncSuccess,
                lastRestoredCount = lastRestoredCount,
                lastErrorMessage = lastDriveError
            )
        )

        val guidance = OnboardingDecisionModel.evaluateStepGuidance(
            StepGuidanceInput(
                step = currentStep,
                mode = currentMode,
                provisioningActive = provActive,
                provisioningProgress = provState.progress,
                provisioningFailed = !provActive && lastDownloadErrorMessage != null,
                japaneseAsrReady = asrReady,
                totalDictWords = totalDictWords,
                imeEnabled = isImeEnabled(),
                imeSelected = isImeSelected(),
                micGranted = isMicGranted(),
                driveStatus = driveDecision.uiState
            )
        )

        tvStepTitle.text = guidance.stepTitle
        tvStepSubtitle.text = guidance.stepSubtitle
        btnObNext.text = guidance.nextButtonLabel
        btnObNext.isEnabled = guidance.nextButtonEnabled

        when (currentStep) {
            OnboardingStep.WELCOME -> renderWelcomeStep()
            OnboardingStep.DATA_PREP -> renderDataPrepStep(asrReady, totalDictWords, provState)
            OnboardingStep.DRIVE_RESTORE -> renderDriveRestoreStep(driveDecision)
            OnboardingStep.PERMISSIONS_AND_IME -> renderPermissionsAndImeStep()
            OnboardingStep.COMPLETE_AND_TEST -> renderCompleteStep(asrReady, totalDictWords, provState)
        }
    }

    private fun renderWelcomeStep() {
        val hasLocalDict = AsrApp.instance.dictionaryRepository.getAll().isNotEmpty()
        val hasDriveUri = syncManager.getSyncUri() != null
        if (showReinstallHint || hasLocalDict || hasDriveUri) {
            layoutReinstallHintBanner.visibility = View.VISIBLE
            tvReinstallHintTitle.text = "以前の登録データ・連携設定が見つかりました"
            tvReinstallHintDesc.text = if (hasDriveUri) {
                "Google Drive の同期設定が引き継がれています。「Google Drive の履歴から復元」を選ぶとスムーズに復旧できます。"
            } else {
                "ローカル辞書が復元されています。不足している音声モデル・拡張辞書とキーボード設定を補完しましょう。"
            }
        } else {
            layoutReinstallHintBanner.visibility = View.GONE
        }
        renderModeSelectionCards()
    }

    private fun renderModeSelectionCards() {
        val primaryColor = ContextCompat.getColor(this, R.color.primary)
        val borderColor = ContextCompat.getColor(this, R.color.border)
        val dp2 = (2 * resources.displayMetrics.density).toInt()
        val dp1 = (1 * resources.displayMetrics.density).toInt()

        fun styleCard(card: MaterialCardView, selected: Boolean) {
            card.strokeColor = if (selected) primaryColor else borderColor
            card.strokeWidth = if (selected) dp2 else dp1
        }
        styleCard(cardModeFresh, currentMode == OnboardingMode.FRESH_INSTALL)
        styleCard(cardModeDriveRestore, currentMode == OnboardingMode.DRIVE_RESTORE)
        styleCard(cardModeReinstall, currentMode == OnboardingMode.REINSTALL)
    }

    private fun renderDataPrepStep(
        asrReady: Boolean,
        totalDictWords: Int,
        provState: ProvisioningUiState
    ) {
        val asrQueued = provState.isTaskActiveOrQueued(ProvisioningTaskKind.JAPANESE_ASR_MODEL)
        when {
            asrReady -> {
                tvObAsrBadge.text = "導入済み ✓"
                applyBadgeTone(tvObAsrBadge, StatusTone.SUCCESS)
            }
            asrQueued -> {
                tvObAsrBadge.text = "取得中..."
                applyBadgeTone(tvObAsrBadge, StatusTone.INFO)
            }
            else -> {
                tvObAsrBadge.text = "未取得"
                applyBadgeTone(tvObAsrBadge, StatusTone.NEUTRAL)
            }
        }

        val dictQueued = provState.isTaskActiveOrQueued(ProvisioningTaskKind.ALL_OR_OUTDATED_DICTIONARIES) ||
            provState.isTaskActiveOrQueued(ProvisioningTaskKind.SINGLE_DICTIONARY)
        when {
            totalDictWords > 0 -> {
                tvObDictBadge.text = "導入済み (${String.format(java.util.Locale.US, "%,d", totalDictWords)}語)"
                applyBadgeTone(tvObDictBadge, StatusTone.SUCCESS)
            }
            dictQueued -> {
                tvObDictBadge.text = "取得中..."
                applyBadgeTone(tvObDictBadge, StatusTone.INFO)
            }
            else -> {
                tvObDictBadge.text = "未取得"
                applyBadgeTone(tvObDictBadge, StatusTone.NEUTRAL)
            }
        }

        val bothReady = asrReady && totalDictWords > 0
        val bothQueued = asrQueued && dictQueued
        btnObStartDownloads.isEnabled = !bothReady && !bothQueued && !(asrReady && dictQueued) && !(asrQueued && totalDictWords > 0)
        btnObStartDownloads.text = when {
            bothReady -> "必要なデータはすべて導入済みです ✓"
            asrQueued || dictQueued -> "バックグラウンドで取得中..."
            else -> "バックグラウンドでまとめて取得"
        }

        if (!lastDownloadErrorMessage.isNullOrBlank()) {
            layoutObDownloadError.visibility = View.VISIBLE
            tvObDownloadErrorMsg.text = "$lastDownloadErrorMessage\n（※ このまま先へ進んで、後から「データ」タブで再試行できます）"
        } else {
            layoutObDownloadError.visibility = View.GONE
        }
    }

    private fun renderDriveRestoreStep(decision: OnboardingDecisionModel.DriveRestoreDecision) {
        tvObDriveBadge.text = decision.badgeText
        applyBadgeTone(tvObDriveBadge, decision.tone)
        tvObDriveDescription.text = decision.descriptionText
        btnObDrivePrimary.text = decision.primaryButtonLabel
        btnObDrivePrimary.isEnabled = !isSyncingDrive
        btnObDriveCreateNew.isEnabled = !isSyncingDrive
    }

    private fun renderPermissionsAndImeStep() {
        val micGranted = isMicGranted()
        tvObMicStatus.text = if (micGranted) "許可済み ✓" else "未許可（音声入力に必要）"
        btnObGrantMic.isEnabled = !micGranted
        btnObGrantMic.text = if (micGranted) "許可済み" else "許可する"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val notifGranted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            tvObNotifStatus.text = if (notifGranted) "許可済み ✓" else "未許可（バックグラウンド進捗通知用・任意）"
            btnObGrantNotif.isEnabled = !notifGranted
            btnObGrantNotif.text = if (notifGranted) "許可済み" else "許可する"
        }

        val imeEnabled = isImeEnabled()
        tvObImeEnabledStatus.text = if (imeEnabled) "JotInput は有効です ✓" else "Android 設定で JotInput をオンにしてください"
        btnObEnableIme.text = if (imeEnabled) "設定済み ✓" else "設定を開く"

        val imeSelected = isImeSelected()
        tvObImeSelectedStatus.text = if (imeSelected) "JotInput が選択されています ✓" else "入力方法として JotInput を選択してください"
        btnObSelectIme.isEnabled = imeEnabled
        btnObSelectIme.text = if (imeSelected) "選択済み ✓" else "切り替える"
    }

    private fun renderCompleteStep(
        asrReady: Boolean,
        totalDictWords: Int,
        provState: ProvisioningUiState
    ) {
        val provActive = provState.isRunning || provState.activeTask != null || provState.queuedTasks.isNotEmpty()
        when {
            provActive -> {
                layoutObCompleteBgInfo.backgroundTintList =
                    ColorStateList.valueOf(ContextCompat.getColor(this, R.color.badge_info_bg))
                tvObCompleteBgStatus.setTextColor(ContextCompat.getColor(this, R.color.badge_info_text))
                tvObCompleteBgStatus.text =
                    "バックグラウンドでデータ取得中 (${provState.progress}%)。\nフリック・QWERTY入力は今すぐ試せます。「JotInput を始める」でホームへ移動してもダウンロードは継続します。"
            }
            asrReady && totalDictWords > 0 -> {
                layoutObCompleteBgInfo.backgroundTintList =
                    ColorStateList.valueOf(ContextCompat.getColor(this, R.color.badge_success_bg))
                tvObCompleteBgStatus.setTextColor(ContextCompat.getColor(this, R.color.badge_success_text))
                tvObCompleteBgStatus.text = "音声認識モデル・拡張辞書の準備も完了しています ✓"
            }
            else -> {
                layoutObCompleteBgInfo.backgroundTintList =
                    ColorStateList.valueOf(ContextCompat.getColor(this, R.color.badge_neutral_bg))
                tvObCompleteBgStatus.setTextColor(ContextCompat.getColor(this, R.color.badge_neutral_text))
                tvObCompleteBgStatus.text = "音声モデルや拡張辞書が未取得でも、基本キーボードはすぐ使えます。後から「データ」タブで取得できます。"
            }
        }
    }

    private fun isMicGranted(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun isImeEnabled(): Boolean {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return false
        return imm.enabledInputMethodList.any { it.packageName == packageName }
    }

    private fun isImeSelected(): Boolean {
        val currentIme = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: return false
        return currentIme.startsWith("$packageName/") || currentIme == packageName
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
}
