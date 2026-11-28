package com.example.myasr.context.provisioning.infrastructure

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.myasr.AsrApp
import com.example.myasr.MainActivity
import com.example.myasr.R
import com.example.myasr.context.english.infrastructure.EnglishSpellEngine
import com.example.myasr.context.ime.presentation.KeyboardHeightManager
import com.example.myasr.context.kanakanji.domain.KanaKanjiEngine
import com.example.myasr.context.provisioning.domain.AppUpdateChannel
import com.example.myasr.context.provisioning.domain.AppUpdateCheckErrorKind
import com.example.myasr.context.provisioning.domain.DictUpdateState
import com.example.myasr.context.provisioning.domain.DictionaryUpdateTracker
import com.example.myasr.context.reranking.domain.RerankEngineType
import com.example.myasr.context.speech.domain.AsrLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

enum class ProvisioningTaskKind {
    JAPANESE_ASR_MODEL,
    SINGLE_DICTIONARY,
    ALL_OR_OUTDATED_DICTIONARIES,
    ENGLISH_SPELLING_MODEL,
    ENGLISH_ASR_MODEL,
    SLM_MODEL,
    APP_UPDATE_APK
}

data class ProvisioningTaskRequest(
    val kind: ProvisioningTaskKind,
    val dictId: String? = null
) {
    fun displayTitle(): String = when (kind) {
        ProvisioningTaskKind.JAPANESE_ASR_MODEL -> "日本語音声認識モデル"
        ProvisioningTaskKind.SINGLE_DICTIONARY -> DictionaryType.fromId(dictId ?: "")?.displayName ?: "拡張辞書"
        ProvisioningTaskKind.ALL_OR_OUTDATED_DICTIONARIES -> "辞書の一括ダウンロード・コンパイル"
        ProvisioningTaskKind.ENGLISH_SPELLING_MODEL -> "英語スペル補正モデル"
        ProvisioningTaskKind.ENGLISH_ASR_MODEL -> "英語音声認識モデル"
        ProvisioningTaskKind.SLM_MODEL -> {
            val engine = RerankEngineType.fromId(dictId ?: "")
            if (engine != RerankEngineType.NONE) "${engine.displayName} AIモデル" else "AI変換モデル"
        }
        ProvisioningTaskKind.APP_UPDATE_APK -> when (dictId) {
            AppUpdateChannel.RELEASE.id -> "リリース版アプリ更新"
            AppUpdateChannel.REMOTE_HEAD.id -> "リモートヘッド最新ビルド"
            else -> "アプリ最新ビルド"
        }
    }
}

data class ProvisioningEvent(
    val eventId: Long,
    val task: ProvisioningTaskRequest,
    val success: Boolean,
    val message: String,
    val errorKind: AppUpdateCheckErrorKind? = null
)

data class ProvisioningUiState(
    val isRunning: Boolean = false,
    val activeTask: ProvisioningTaskRequest? = null,
    val queuedTasks: List<ProvisioningTaskRequest> = emptyList(),
    val progress: Int = 0,
    val statusText: String = "",
    val lastEvent: ProvisioningEvent? = null
) {
    fun isTaskActiveOrQueued(kind: ProvisioningTaskKind, dictId: String? = null): Boolean {
        val matches: (ProvisioningTaskRequest) -> Boolean = { req ->
            req.kind == kind && (dictId == null || req.dictId == dictId)
        }
        return (activeTask != null && matches(activeTask)) || queuedTasks.any(matches)
    }
}

/**
 * Foreground Service (dataSync) that executes model/dictionary downloads and
 * binary dictionary compilation (MYASRDIC v1) off the Activity lifecycle so that
 * work continues uninterrupted even when another app is in the foreground.
 */
class ProvisioningForegroundService : Service() {

    companion object {
        private const val TAG = "ProvisioningFgService"
        private const val CHANNEL_ID_PROGRESS = "provisioning_background_channel"
        private const val CHANNEL_ID_COMPLETE = "provisioning_complete_channel"
        private const val PROGRESS_NOTIFICATION_ID = 4201
        private const val COMPLETE_NOTIFICATION_ID = 4202
        private const val APP_UPDATE_COMPLETE_NOTIFICATION_ID = 4203
        private const val ACTION_ENQUEUE = "com.example.myasr.action.ENQUEUE_PROVISIONING"
        private const val ACTION_CANCEL = "com.example.myasr.action.CANCEL_PROVISIONING"
        private const val ACTION_CANCEL_KIND = "com.example.myasr.action.CANCEL_PROVISIONING_KIND"
        private const val EXTRA_TASK_KIND = "extra_task_kind"
        private const val EXTRA_DICT_ID = "extra_dict_id"
        private const val WAKELOCK_TIMEOUT_MS = 30L * 60L * 1000L

        private val eventCounter = AtomicLong(0L)
        private val _state = MutableStateFlow(ProvisioningUiState())
        val state: StateFlow<ProvisioningUiState> = _state.asStateFlow()

        fun enqueue(context: Context, kind: ProvisioningTaskKind, dictId: String? = null) {
            val intent = Intent(context, ProvisioningForegroundService::class.java).apply {
                action = ACTION_ENQUEUE
                putExtra(EXTRA_TASK_KIND, kind.name)
                if (dictId != null) {
                    putExtra(EXTRA_DICT_ID, dictId)
                }
            }
            ContextCompat.startForegroundService(context.applicationContext, intent)
        }

        fun cancelKind(context: Context, kind: ProvisioningTaskKind) {
            val current = _state.value
            if (!current.isRunning && current.activeTask == null && current.queuedTasks.isEmpty()) {
                return
            }
            val intent = Intent(context, ProvisioningForegroundService::class.java).apply {
                action = ACTION_CANCEL_KIND
                putExtra(EXTRA_TASK_KIND, kind.name)
            }
            runCatching {
                context.applicationContext.startService(intent)
            }
        }

        fun cancelAll(context: Context) {
            val intent = Intent(context, ProvisioningForegroundService::class.java).apply {
                action = ACTION_CANCEL
            }
            runCatching {
                context.applicationContext.startService(intent)
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queueLock = Any()
    private val taskQueue = ArrayDeque<ProvisioningTaskRequest>()
    private var activeTask: ProvisioningTaskRequest? = null
    private var activeTaskJob: Job? = null
    private var workerJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotificationUpdateMs = 0L
    private var lastNotifiedPercent = -1

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannels()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                cancelAndStop()
                return START_NOT_STICKY
            }
            ACTION_CANCEL_KIND -> {
                val kindName = intent.getStringExtra(EXTRA_TASK_KIND)
                val kind = kindName?.let { runCatching { ProvisioningTaskKind.valueOf(it) }.getOrNull() }
                if (kind != null) {
                    cancelTaskKind(kind)
                }
                return START_NOT_STICKY
            }
            ACTION_ENQUEUE -> {
                val kindName = intent.getStringExtra(EXTRA_TASK_KIND)
                val kind = kindName?.let { runCatching { ProvisioningTaskKind.valueOf(it) }.getOrNull() }
                if (kind == null) {
                    if (activeTask == null && taskQueue.isEmpty()) {
                        stopSelf(startId)
                    }
                    return START_NOT_STICKY
                }
                val dictId = intent.getStringExtra(EXTRA_DICT_ID)
                val request = ProvisioningTaskRequest(kind, dictId)

                startForegroundWithNotification(
                    title = "${request.displayTitle()} を準備中...",
                    text = "バックグラウンド処理を開始しています",
                    progress = 0,
                    indeterminate = true
                )

                var shouldStartWorker = false
                synchronized(queueLock) {
                    val alreadyPresent = activeTask == request || taskQueue.contains(request)
                    if (!alreadyPresent) {
                        taskQueue.addLast(request)
                        updateUiStateLocked(
                            isRunning = true,
                            progress = _state.value.progress,
                            statusText = _state.value.statusText.ifEmpty { "準備中..." }
                        )
                    }
                    if (workerJob == null || workerJob?.isActive != true) {
                        shouldStartWorker = true
                    }
                }
                if (shouldStartWorker) {
                    startWorkerLoop()
                }
            }
            else -> {
                if (activeTask == null && taskQueue.isEmpty()) {
                    stopSelf(startId)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startWorkerLoop() {
        acquireWakeLock()
        workerJob = serviceScope.launch {
            try {
                while (true) {
                    val nextTask = synchronized(queueLock) {
                        val next = taskQueue.pollFirst()
                        activeTask = next
                        if (next != null) {
                            updateUiStateLocked(
                                isRunning = true,
                                progress = 0,
                                statusText = "${next.displayTitle()} を準備中..."
                            )
                        }
                        next
                    } ?: break

                    var taskEvent: ProvisioningEvent? = null
                    val job = serviceScope.launch {
                        taskEvent = executeTask(nextTask)
                    }
                    synchronized(queueLock) {
                        activeTaskJob = job
                    }
                    job.join()
                    val wasCancelled = job.isCancelled
                    synchronized(queueLock) {
                        activeTaskJob = null
                    }
                    val event = taskEvent ?: ProvisioningEvent(
                        eventId = eventCounter.incrementAndGet(),
                        task = nextTask,
                        success = false,
                        message = if (wasCancelled) {
                            "${nextTask.displayTitle()} のダウンロードをキャンセルしました"
                        } else {
                            "${nextTask.displayTitle()} の処理が中断されました"
                        }
                    )
                    synchronized(queueLock) {
                        activeTask = null
                        val hasMore = taskQueue.isNotEmpty()
                        _state.value = _state.value.copy(
                            isRunning = hasMore,
                            activeTask = null,
                            queuedTasks = taskQueue.toList(),
                            progress = if (hasMore) 0 else 100,
                            statusText = if (hasMore) "次の処理を開始しています..." else "",
                            lastEvent = event
                        )
                    }
                    if (!wasCancelled) {
                        postCompletionNotification(event)
                    }
                }
            } catch (_: CancellationException) {
                Log.i(TAG, "Provisioning worker cancelled")
            } finally {
                releaseWakeLock()
                synchronized(queueLock) {
                    activeTask = null
                    taskQueue.clear()
                    _state.value = _state.value.copy(
                        isRunning = false,
                        activeTask = null,
                        queuedTasks = emptyList()
                    )
                }
                ServiceCompat.stopForeground(this@ProvisioningForegroundService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private suspend fun executeTask(task: ProvisioningTaskRequest): ProvisioningEvent {
        Log.i(TAG, "Executing background task: $task")
        return try {
            when (task.kind) {
                ProvisioningTaskKind.JAPANESE_ASR_MODEL -> runJapaneseAsrDownload(task)
                ProvisioningTaskKind.SINGLE_DICTIONARY -> runSingleDictionaryDownload(task)
                ProvisioningTaskKind.ALL_OR_OUTDATED_DICTIONARIES -> runAllOrOutdatedDictionaries(task)
                ProvisioningTaskKind.ENGLISH_SPELLING_MODEL -> runEnglishSpellingDownload(task)
                ProvisioningTaskKind.ENGLISH_ASR_MODEL -> runEnglishAsrDownload(task)
                ProvisioningTaskKind.SLM_MODEL -> runSlmModelDownload(task)
                ProvisioningTaskKind.APP_UPDATE_APK -> runAppUpdateDownload(task)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.e(TAG, "Task failed: $task", t)
            ProvisioningEvent(
                eventId = eventCounter.incrementAndGet(),
                task = task,
                success = false,
                message = "処理に失敗しました: ${t.message ?: t.javaClass.simpleName}"
            )
        }
    }

    private suspend fun runJapaneseAsrDownload(task: ProvisioningTaskRequest): ProvisioningEvent {
        val app = AsrApp.instance
        val asrManager = app.asrManager
        val downloader = ModelDownloader(asrManager.getStorageModelDir())

        val result = downloader.download { fileName, fileIdx, totalFiles, percent ->
            reportProgress(
                task = task,
                percent = percent,
                statusText = "[$fileIdx/$totalFiles] $fileName ($percent%)",
                notifTitle = "日本語音声認識モデルをダウンロード中"
            )
        }
        if (result.isFailure) {
            val err = result.exceptionOrNull()?.message ?: "Unknown error"
            return ProvisioningEvent(eventCounter.incrementAndGet(), task, false, "ダウンロード失敗: $err")
        }

        app.hotwordsGenerator.reloadTokensSet()
        val engine = KanaKanjiEngine.getInstance(this)
        if (!engine.isBaseDictionaryDownloaded()) {
            reportProgress(
                task = task,
                percent = 0,
                statusText = "Mozc 基本辞書をダウンロード・コンパイル中...",
                notifTitle = "Mozc 基本辞書を準備中"
            )
            app.multiDictionaryManager.downloadDictionary(DictionaryType.MOZC_GENERAL) { pct, statusText ->
                reportProgress(
                    task = task,
                    percent = pct,
                    statusText = "基本辞書: $statusText",
                    notifTitle = "Mozc 基本辞書を準備中"
                )
            }
        }

        withContext(Dispatchers.Default) {
            val hotwordsFile = app.hotwordsGenerator.generate(app.dictionaryRepository.getActive())
            asrManager.reload(hotwordsFile, AsrLanguage.JAPANESE)
        }
        return ProvisioningEvent(
            eventId = eventCounter.incrementAndGet(),
            task = task,
            success = true,
            message = "モデルと辞書のダウンロードが完了しました！"
        )
    }

    private suspend fun runSingleDictionaryDownload(task: ProvisioningTaskRequest): ProvisioningEvent {
        val dictType = DictionaryType.fromId(task.dictId ?: "")
            ?: return ProvisioningEvent(eventCounter.incrementAndGet(), task, false, "不明な辞書ID: ${task.dictId}")
        val manager = AsrApp.instance.multiDictionaryManager
        val result = manager.downloadDictionary(dictType) { pct, msg ->
            reportProgress(
                task = task,
                percent = pct,
                statusText = msg,
                notifTitle = "${dictType.displayName} を処理中"
            )
        }
        return if (result.isSuccess) {
            val words = result.getOrDefault(0)
            ProvisioningEvent(
                eventId = eventCounter.incrementAndGet(),
                task = task,
                success = true,
                message = "${dictType.displayName} をインストールしました (${words}語)"
            )
        } else {
            ProvisioningEvent(
                eventId = eventCounter.incrementAndGet(),
                task = task,
                success = false,
                message = "ダウンロード失敗: ${result.exceptionOrNull()?.message ?: "Unknown error"}"
            )
        }
    }

    private suspend fun runAllOrOutdatedDictionaries(task: ProvisioningTaskRequest): ProvisioningEvent {
        val app = AsrApp.instance
        val manager = app.multiDictionaryManager
        val tracker = app.dictionaryUpdateTracker
        val outdatedStatuses = tracker.getAllStatusInfo().filter { it.state == DictUpdateState.UPDATE_AVAILABLE }

        return if (outdatedStatuses.isNotEmpty()) {
            var updatedTotalWords = 0
            val totalSteps = outdatedStatuses.size
            outdatedStatuses.forEachIndexed { idx, status ->
                val stepBase = (idx.toDouble() / totalSteps * 100).toInt()
                val stepRange = (1.0 / totalSteps * 100).toInt()
                val dictType = if (status.sourceId == DictionaryUpdateTracker.BASE_DICT_ID) {
                    DictionaryType.MOZC_GENERAL
                } else {
                    DictionaryType.fromId(status.sourceId)
                }
                if (dictType != null) {
                    val res = manager.downloadDictionary(dictType) { pct, msg ->
                        val overall = (stepBase + pct * stepRange / 100).coerceIn(0, 99)
                        reportProgress(
                            task = task,
                            percent = overall,
                            statusText = msg,
                            notifTitle = "辞書を更新中 (${idx + 1}/$totalSteps)"
                        )
                    }
                    updatedTotalWords += res.getOrDefault(0)
                }
            }
            ProvisioningEvent(
                eventId = eventCounter.incrementAndGet(),
                task = task,
                success = true,
                message = "${outdatedStatuses.size}件の辞書を最新版に更新しました (計${updatedTotalWords}語)"
            )
        } else {
            val result = manager.downloadAllDictionaries { pct, msg ->
                reportProgress(
                    task = task,
                    percent = pct.coerceIn(0, 100),
                    statusText = msg,
                    notifTitle = "辞書をダウンロード・コンパイル中"
                )
            }
            if (result.isSuccess) {
                val totalWords = result.getOrDefault(0)
                ProvisioningEvent(
                    eventId = eventCounter.incrementAndGet(),
                    task = task,
                    success = true,
                    message = "全辞書・言語データの導入が完了しました (計${totalWords}語)"
                )
            } else {
                ProvisioningEvent(
                    eventId = eventCounter.incrementAndGet(),
                    task = task,
                    success = false,
                    message = "一部の辞書ダウンロードに失敗しました: ${result.exceptionOrNull()?.message}"
                )
            }
        }
    }

    private suspend fun runEnglishSpellingDownload(task: ProvisioningTaskRequest): ProvisioningEvent {
        val downloader = EnglishSpellDownloader(this)
        val result = downloader.download { currentFile, fileIdx, totalFiles, pct ->
            reportProgress(
                task = task,
                percent = pct,
                statusText = "[$fileIdx/$totalFiles] $currentFile ($pct%)",
                notifTitle = "英語スペル補正モデルをダウンロード中"
            )
        }
        return if (result.isSuccess) {
            EnglishSpellEngine.getInstance(this).ensureInitialized()
            ProvisioningEvent(
                eventId = eventCounter.incrementAndGet(),
                task = task,
                success = true,
                message = "英語スペル補正モデルのインストールが完了しました"
            )
        } else {
            ProvisioningEvent(
                eventId = eventCounter.incrementAndGet(),
                task = task,
                success = false,
                message = "ダウンロードに失敗しました: ${result.exceptionOrNull()?.message}"
            )
        }
    }

    private suspend fun runEnglishAsrDownload(task: ProvisioningTaskRequest): ProvisioningEvent {
        val asrManager = AsrApp.instance.asrManager
        val downloader = EnglishAsrModelDownloader(asrManager.getEnglishStorageModelDir())
        val result = downloader.download { statusText, pct ->
            reportProgress(
                task = task,
                percent = pct,
                statusText = "$statusText ($pct%)",
                notifTitle = "英語音声認識モデルをダウンロード中"
            )
        }
        return if (result.isSuccess) {
            withContext(Dispatchers.Default) {
                asrManager.reload(null, AsrLanguage.ENGLISH)
            }
            ProvisioningEvent(
                eventId = eventCounter.incrementAndGet(),
                task = task,
                success = true,
                message = "英語音声認識モデルのインストールが完了しました"
            )
        } else {
            ProvisioningEvent(
                eventId = eventCounter.incrementAndGet(),
                task = task,
                success = false,
                message = "ダウンロードに失敗しました: ${result.exceptionOrNull()?.message}"
            )
        }
    }

    private suspend fun runSlmModelDownload(task: ProvisioningTaskRequest): ProvisioningEvent {
        val engine = RerankEngineType.fromId(task.dictId ?: "")
        if (engine == RerankEngineType.NONE || engine.downloadUrl.isBlank()) {
            return ProvisioningEvent(
                eventId = eventCounter.incrementAndGet(),
                task = task,
                success = false,
                message = "不明な AI モデル ID です: ${task.dictId}"
            )
        }
        val targetDir = SlmModelDownloader.getTargetModelsDir(this)
        val downloader = SlmModelDownloader(targetDir, engine)
        val result = downloader.download { statusText, pct ->
            reportProgress(
                task = task,
                percent = pct,
                statusText = "$statusText ($pct%)",
                notifTitle = "${engine.displayName} をダウンロード中"
            )
        }
        return if (result.isSuccess) {
            if (KeyboardHeightManager.isSlmRerankEnabled(this) &&
                KeyboardHeightManager.getRerankEngineType(this) == engine
            ) {
                AsrApp.instance.llmReranker.initializeIfAvailable(serviceScope, engine.modelFileName)
            }
            ProvisioningEvent(
                eventId = eventCounter.incrementAndGet(),
                task = task,
                success = true,
                message = "${engine.displayName} のダウンロードが完了しました"
            )
        } else {
            ProvisioningEvent(
                eventId = eventCounter.incrementAndGet(),
                task = task,
                success = false,
                message = "${engine.displayName} のダウンロードに失敗しました: ${result.exceptionOrNull()?.message}"
            )
        }
    }

    private suspend fun runAppUpdateDownload(task: ProvisioningTaskRequest): ProvisioningEvent {
        val repo = AsrApp.instance.appUpdateRepository
        val channel = AppUpdateChannel.fromId(task.dictId, repo.getSelectedChannel())
        val notifTitle = when (channel) {
            AppUpdateChannel.RELEASE -> "JotInput リリース版をダウンロード中"
            AppUpdateChannel.REMOTE_HEAD -> "JotInput リモートヘッドをダウンロード中"
        }
        val result = repo.downloadLatestBuild(channel) { pct, statusText ->
            reportProgress(
                task = task,
                percent = pct,
                statusText = statusText,
                notifTitle = notifTitle
            )
        }
        return if (result.isSuccess) {
            val info = result.getOrThrow()
            ProvisioningEvent(
                eventId = eventCounter.incrementAndGet(),
                task = task,
                success = true,
                message = "${channel.displayName} (${info.displayLabel}) の準備が完了しました。タップしてインストールできます。"
            )
        } else {
            val ex = result.exceptionOrNull()
            val errKind = (ex as? GitHubAppUpdateClient.DownloadHttpException)?.errorKind
                ?: AppUpdateCheckErrorKind.DOWNLOAD_FAILED
            ProvisioningEvent(
                eventId = eventCounter.incrementAndGet(),
                task = task,
                success = false,
                message = "アプリ更新取得失敗: ${ex?.message ?: "Unknown error"}",
                errorKind = errKind
            )
        }
    }

    private fun reportProgress(
        task: ProvisioningTaskRequest,
        percent: Int,
        statusText: String,
        notifTitle: String
    ) {
        val clamped = percent.coerceIn(0, 100)
        synchronized(queueLock) {
            _state.value = _state.value.copy(
                isRunning = true,
                activeTask = task,
                queuedTasks = taskQueue.toList(),
                progress = clamped,
                statusText = statusText
            )
        }
        val now = System.currentTimeMillis()
        if (now - lastNotificationUpdateMs >= 350L || kotlin.math.abs(clamped - lastNotifiedPercent) >= 5 || clamped == 100) {
            lastNotificationUpdateMs = now
            lastNotifiedPercent = clamped
            val queuedSuffix = synchronized(queueLock) {
                if (taskQueue.isNotEmpty()) " (待機中: ${taskQueue.size}件)" else ""
            }
            updateNotification(
                title = "$notifTitle$queuedSuffix",
                text = statusText,
                progress = clamped,
                indeterminate = false
            )
        }
    }

    private fun updateUiStateLocked(isRunning: Boolean, progress: Int, statusText: String) {
        _state.value = _state.value.copy(
            isRunning = isRunning,
            activeTask = activeTask,
            queuedTasks = taskQueue.toList(),
            progress = progress,
            statusText = statusText
        )
    }

    private fun cancelTaskKind(targetKind: ProvisioningTaskKind) {
        Log.i(TAG, "Cancelling background provisioning task for kind=$targetKind")
        var removedFromQueue: ProvisioningTaskRequest? = null
        var jobToCancel: Job? = null
        val remainingEmpty: Boolean
        synchronized(queueLock) {
            val it = taskQueue.iterator()
            while (it.hasNext()) {
                val req = it.next()
                if (req.kind == targetKind) {
                    if (removedFromQueue == null) {
                        removedFromQueue = req
                    }
                    it.remove()
                }
            }
            if (activeTask?.kind == targetKind) {
                jobToCancel = activeTaskJob
            }
            remainingEmpty = activeTask == null && taskQueue.isEmpty()
            val removed = removedFromQueue
            if (jobToCancel == null && removed != null) {
                _state.value = _state.value.copy(
                    isRunning = !remainingEmpty,
                    queuedTasks = taskQueue.toList(),
                    lastEvent = ProvisioningEvent(
                        eventId = eventCounter.incrementAndGet(),
                        task = removed,
                        success = false,
                        message = "${removed.displayTitle()} の待機をキャンセルしました"
                    )
                )
            } else {
                _state.value = _state.value.copy(
                    queuedTasks = taskQueue.toList()
                )
            }
        }
        if (jobToCancel != null) {
            jobToCancel?.cancel()
            return
        }
        if (remainingEmpty) {
            releaseWakeLock()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun cancelAndStop() {
        Log.i(TAG, "Cancelling background provisioning tasks by user request")
        val cancelledTask = synchronized(queueLock) {
            val current = activeTask
            taskQueue.clear()
            current
        }
        activeTaskJob?.cancel()
        workerJob?.cancel()
        if (cancelledTask != null) {
            _state.value = _state.value.copy(
                isRunning = false,
                activeTask = null,
                queuedTasks = emptyList(),
                progress = 0,
                statusText = "",
                lastEvent = ProvisioningEvent(
                    eventId = eventCounter.incrementAndGet(),
                    task = cancelledTask,
                    success = false,
                    message = "ダウンロード・コンパイルをキャンセルしました"
                )
            )
        }
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun ensureNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID_PROGRESS) == null) {
                val progressChannel = NotificationChannel(
                    CHANNEL_ID_PROGRESS,
                    "辞書・モデル バックグラウンド処理",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "辞書や音声モデルのダウンロードおよびコンパイルの進行状況"
                    setShowBadge(false)
                }
                nm.createNotificationChannel(progressChannel)
            }
            if (nm.getNotificationChannel(CHANNEL_ID_COMPLETE) == null) {
                val completeChannel = NotificationChannel(
                    CHANNEL_ID_COMPLETE,
                    "辞書・モデル 完了通知",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "辞書や音声モデルのダウンロード・コンパイル完了通知"
                }
                nm.createNotificationChannel(completeChannel)
            }
        }
    }

    private fun buildProgressNotification(
        title: String,
        text: String,
        progress: Int,
        indeterminate: Boolean
    ): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = Intent(this, ProvisioningForegroundService::class.java).apply {
            action = ACTION_CANCEL
        }
        val cancelPendingIntent = PendingIntent.getService(
            this,
            1,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID_PROGRESS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress, indeterminate)
            .setContentIntent(contentPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "キャンセル", cancelPendingIntent)
            .build()
    }

    private fun startForegroundWithNotification(
        title: String,
        text: String,
        progress: Int,
        indeterminate: Boolean
    ) {
        val notification = buildProgressNotification(title, text, progress, indeterminate)
        val fgType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(this, PROGRESS_NOTIFICATION_ID, notification, fgType)
    }

    private fun updateNotification(
        title: String,
        text: String,
        progress: Int,
        indeterminate: Boolean
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val notification = buildProgressNotification(title, text, progress, indeterminate)
        try {
            NotificationManagerCompat.from(this).notify(PROGRESS_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot post progress notification: ${e.message}")
        }
    }

    private fun postCompletionNotification(event: ProvisioningEvent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val isAppUpdateReady = event.task.kind == ProvisioningTaskKind.APP_UPDATE_APK && event.success
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (isAppUpdateReady) {
                putExtra(MainActivity.EXTRA_TRIGGER_APP_UPDATE_INSTALL, true)
                event.task.dictId?.let { putExtra(MainActivity.EXTRA_APP_UPDATE_CHANNEL, it) }
            }
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            if (isAppUpdateReady) 3 else 2,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = if (event.success) {
            "JotInput: ${event.task.displayTitle()} 完了"
        } else {
            "JotInput: ${event.task.displayTitle()} エラー"
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID_COMPLETE)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(event.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(event.message))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
        if (isAppUpdateReady) {
            builder.addAction(
                android.R.drawable.stat_sys_download_done,
                "インストール",
                pendingIntent
            )
        }
        val notificationId = if (isAppUpdateReady) {
            APP_UPDATE_COMPLETE_NOTIFICATION_ID
        } else {
            COMPLETE_NOTIFICATION_ID
        }
        try {
            NotificationManagerCompat.from(this).notify(notificationId, builder.build())
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot post completion notification: ${e.message}")
        }
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "JotInput:ProvisioningWakeLock")?.apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(WAKELOCK_TIMEOUT_MS)
            }
        } catch (e: Exception) {
            Log.w(TAG, "WakeLock acquire skipped: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "WakeLock release skipped: ${e.message}")
        }
    }

    override fun onDestroy() {
        workerJob?.cancel()
        serviceScope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }
}
