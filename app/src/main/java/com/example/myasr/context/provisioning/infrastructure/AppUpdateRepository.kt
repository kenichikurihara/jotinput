package com.example.myasr.context.provisioning.infrastructure

import android.content.Context
import android.util.Log
import com.example.myasr.BuildConfig
import com.example.myasr.context.provisioning.domain.AppUpdateCheckErrorKind
import com.example.myasr.context.provisioning.domain.AppUpdateCheckResult
import com.example.myasr.context.provisioning.domain.AppUpdateDecisionModel
import com.example.myasr.context.provisioning.domain.AppUpdateDecisionModel.BuildFreshnessInput
import com.example.myasr.context.provisioning.domain.BuildFreshnessStatus
import com.example.myasr.context.provisioning.domain.DownloadedApkInfo
import com.example.myasr.context.provisioning.domain.InstalledAppBuildInfo
import com.example.myasr.context.provisioning.domain.RemoteBuildArtifact
import java.io.File

/**
 * Coordinates GitHub Actions artifact freshness caching, fail-safe update checks,
 * background ZIP download + APK extraction, and automatic cleanup of obsolete staged APKs.
 */
class AppUpdateRepository(
    private val context: Context,
    val tokenStore: GitHubTokenStore = GitHubTokenStore(context),
    private val client: GitHubArtifactUpdateClient = GitHubArtifactUpdateClient()
) {
    companion object {
        private const val TAG = "AppUpdateRepository"
        private const val PREFS_NAME = "app_update_metadata"

        private const val KEY_REMOTE_ID = "remote_artifact_id"
        private const val KEY_REMOTE_NAME = "remote_name"
        private const val KEY_REMOTE_SIZE = "remote_size_bytes"
        private const val KEY_REMOTE_URL = "remote_download_url"
        private const val KEY_REMOTE_CREATED_MS = "remote_created_at_ms"
        private const val KEY_REMOTE_EXPIRES_MS = "remote_expires_at_ms"
        private const val KEY_REMOTE_EXPIRED = "remote_expired"
        private const val KEY_REMOTE_RUN_ID = "remote_run_id"
        private const val KEY_REMOTE_HEAD_SHA = "remote_head_sha"
        private const val KEY_REMOTE_HEAD_BRANCH = "remote_head_branch"
        private const val KEY_LAST_CHECK_MS = "last_check_epoch_ms"

        private const val KEY_DL_ARTIFACT_ID = "dl_artifact_id"
        private const val KEY_DL_HEAD_SHA = "dl_head_sha"
        private const val KEY_DL_RUN_ID = "dl_run_id"
        private const val KEY_DL_CREATED_MS = "dl_created_at_ms"
        private const val KEY_DL_FILE_PATH = "dl_file_path"
        private const val KEY_DL_SIZE_BYTES = "dl_size_bytes"

        private const val APK_PREFIX = "jotinput-update-"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getInstalledBuildInfo(): InstalledAppBuildInfo = InstalledAppBuildInfo(
        versionName = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE,
        gitSha = BuildConfig.BUILD_GIT_SHA,
        commitEpochMs = BuildConfig.BUILD_GIT_COMMIT_EPOCH_MS,
        githubRunId = BuildConfig.BUILD_GITHUB_RUN_ID,
        isDebugBuild = BuildConfig.DEBUG
    )

    fun getUpdatesDir(): File {
        val dir = context.getExternalFilesDir("updates") ?: File(context.filesDir, "updates")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun hasToken(): Boolean = tokenStore.hasToken()

    fun getLastCheckEpochMs(): Long = prefs.getLong(KEY_LAST_CHECK_MS, 0L)

    fun getCachedRemoteArtifact(): RemoteBuildArtifact? {
        val id = prefs.getLong(KEY_REMOTE_ID, 0L)
        if (id <= 0L) return null
        val url = prefs.getString(KEY_REMOTE_URL, "") ?: ""
        if (url.isBlank()) return null
        return RemoteBuildArtifact(
            artifactId = id,
            name = prefs.getString(KEY_REMOTE_NAME, BuildConfig.GITHUB_ARTIFACT_NAME) ?: BuildConfig.GITHUB_ARTIFACT_NAME,
            sizeInBytes = prefs.getLong(KEY_REMOTE_SIZE, 0L),
            archiveDownloadUrl = url,
            createdAtMs = prefs.getLong(KEY_REMOTE_CREATED_MS, 0L),
            expiresAtMs = prefs.getLong(KEY_REMOTE_EXPIRES_MS, 0L),
            expired = prefs.getBoolean(KEY_REMOTE_EXPIRED, false),
            runId = prefs.getLong(KEY_REMOTE_RUN_ID, 0L),
            headSha = prefs.getString(KEY_REMOTE_HEAD_SHA, "") ?: "",
            headBranch = prefs.getString(KEY_REMOTE_HEAD_BRANCH, BuildConfig.GITHUB_TARGET_BRANCH)
                ?: BuildConfig.GITHUB_TARGET_BRANCH
        )
    }

    fun getCurrentFreshnessStatus(nowMs: Long = System.currentTimeMillis()): BuildFreshnessStatus? {
        if (getLastCheckEpochMs() <= 0L) return null
        val cached = getCachedRemoteArtifact() ?: return BuildFreshnessStatus.NO_ARTIFACT
        val isNowExpired = cached.expired || (cached.expiresAtMs > 0L && nowMs >= cached.expiresAtMs)
        val effective = if (isNowExpired) cached.copy(expired = true) else cached
        return AppUpdateDecisionModel.evaluateBuildFreshness(
            BuildFreshnessInput(
                installed = getInstalledBuildInfo(),
                remoteArtifact = effective
            )
        ).status
    }

    fun getMatchingDownloadedApk(
        latestRemote: RemoteBuildArtifact? = getCachedRemoteArtifact()
    ): DownloadedApkInfo? {
        val artifactId = prefs.getLong(KEY_DL_ARTIFACT_ID, 0L)
        val path = prefs.getString(KEY_DL_FILE_PATH, null)
        if (artifactId <= 0L || path.isNullOrBlank()) {
            return null
        }
        val file = File(path)
        if (!file.exists() || file.length() < GitHubArtifactUpdateClient.MIN_VALID_APK_BYTES) {
            clearDownloadedApkMetadata()
            return null
        }

        val info = DownloadedApkInfo(
            artifactId = artifactId,
            headSha = prefs.getString(KEY_DL_HEAD_SHA, "") ?: "",
            runId = prefs.getLong(KEY_DL_RUN_ID, 0L),
            createdAtMs = prefs.getLong(KEY_DL_CREATED_MS, 0L),
            filePath = path,
            sizeInBytes = prefs.getLong(KEY_DL_SIZE_BYTES, file.length())
        )

        val syntheticArtifact = RemoteBuildArtifact(
            artifactId = info.artifactId,
            name = BuildConfig.GITHUB_ARTIFACT_NAME,
            sizeInBytes = info.sizeInBytes,
            archiveDownloadUrl = "https://api.github.com/repos/placeholder",
            createdAtMs = info.createdAtMs,
            expiresAtMs = 0L,
            expired = false,
            runId = info.runId,
            headSha = info.headSha,
            headBranch = BuildConfig.GITHUB_TARGET_BRANCH
        )
        val freshness = AppUpdateDecisionModel.evaluateBuildFreshness(
            BuildFreshnessInput(
                installed = getInstalledBuildInfo(),
                remoteArtifact = syntheticArtifact
            )
        ).status

        if (freshness != BuildFreshnessStatus.UPDATE_AVAILABLE) {
            Log.i(TAG, "Cleaning up obsolete downloaded APK (${info.filePath}, status=$freshness)")
            file.delete()
            clearDownloadedApkMetadata()
            return null
        }

        if (latestRemote != null && !latestRemote.expired && latestRemote.artifactId != info.artifactId) {
            return null
        }
        return info
    }

    suspend fun checkLatestBuild(): AppUpdateCheckResult {
        val token = tokenStore.getToken()
        if (token.isNullOrBlank()) {
            return AppUpdateCheckResult.Failure(
                errorKind = AppUpdateCheckErrorKind.TOKEN_REQUIRED,
                message = "GitHub トークンが未設定です"
            )
        }

        return when (
            val res = client.fetchLatestArtifact(
                owner = BuildConfig.GITHUB_REPO_OWNER,
                repo = BuildConfig.GITHUB_REPO_NAME,
                artifactName = BuildConfig.GITHUB_ARTIFACT_NAME,
                targetBranch = BuildConfig.GITHUB_TARGET_BRANCH,
                token = token
            )
        ) {
            is GitHubArtifactUpdateClient.FetchResult.Success -> {
                val now = System.currentTimeMillis()
                saveCachedRemoteArtifact(res.artifact, now)
                val freshness = AppUpdateDecisionModel.evaluateBuildFreshness(
                    BuildFreshnessInput(
                        installed = getInstalledBuildInfo(),
                        remoteArtifact = res.artifact
                    )
                ).status
                AppUpdateCheckResult.Checked(
                    artifact = res.artifact,
                    freshness = freshness,
                    checkedAtMs = now
                )
            }
            is GitHubArtifactUpdateClient.FetchResult.Error -> {
                AppUpdateCheckResult.Failure(
                    errorKind = res.errorKind,
                    message = res.message,
                    httpStatusCode = res.httpStatusCode
                )
            }
        }
    }

    suspend fun downloadLatestArtifact(
        onProgress: (percent: Int, statusText: String) -> Unit
    ): Result<DownloadedApkInfo> {
        val token = tokenStore.getToken()
        if (token.isNullOrBlank()) {
            return Result.failure(
                GitHubArtifactUpdateClient.DownloadHttpException(
                    0,
                    AppUpdateCheckErrorKind.TOKEN_REQUIRED,
                    "GitHub トークンが未設定です"
                )
            )
        }

        onProgress(0, "最新ビルド情報を確認中...")
        val targetArtifact = when (val check = checkLatestBuild()) {
            is AppUpdateCheckResult.Failure -> {
                return Result.failure(
                    GitHubArtifactUpdateClient.DownloadHttpException(
                        check.httpStatusCode ?: 0,
                        check.errorKind,
                        check.message
                    )
                )
            }
            is AppUpdateCheckResult.Checked -> {
                if (check.artifact == null || check.freshness != BuildFreshnessStatus.UPDATE_AVAILABLE) {
                    return Result.failure(IllegalStateException("ダウンロード可能な新しいビルドが見つかりませんでした"))
                }
                check.artifact
            }
        }

        val updatesDir = getUpdatesDir()
        cleanupArtifacts(updatesDir, keepApkName = null)

        val zipFile = File(updatesDir, "artifact-${targetArtifact.artifactId}.zip")
        val apkFile = File(updatesDir, "$APK_PREFIX${targetArtifact.artifactId}.apk")

        return try {
            val zipRes = client.downloadArtifactZip(
                downloadUrl = targetArtifact.archiveDownloadUrl,
                token = token,
                destinationZip = zipFile,
                expectedSizeBytes = targetArtifact.sizeInBytes
            ) { bytesRead, totalBytes ->
                val denom = if (totalBytes > 0L) totalBytes else targetArtifact.sizeInBytes.coerceAtLeast(1L)
                val dlPct = ((bytesRead.toDouble() / denom) * 90.0).toInt().coerceIn(0, 90)
                val readMb = String.format(java.util.Locale.US, "%.1f", bytesRead / (1024.0 * 1024.0))
                val totalMb = String.format(java.util.Locale.US, "%.1f", denom / (1024.0 * 1024.0))
                onProgress(dlPct, "ビルドを取得中... (${readMb}MB / ${totalMb}MB)")
            }
            if (zipRes.isFailure) {
                return Result.failure(
                    zipRes.exceptionOrNull() ?: IllegalStateException("アーティファクトの取得に失敗しました")
                )
            }

            onProgress(92, "APK を展開・検証中...")
            val extractRes = GitHubArtifactUpdateClient.extractApkFromZip(zipFile, apkFile)
            if (extractRes.isFailure) {
                return Result.failure(
                    extractRes.exceptionOrNull() ?: IllegalStateException("APK の展開に失敗しました")
                )
            }

            cleanupArtifacts(updatesDir, keepApkName = apkFile.name)

            val downloadedInfo = DownloadedApkInfo(
                artifactId = targetArtifact.artifactId,
                headSha = targetArtifact.headSha,
                runId = targetArtifact.runId,
                createdAtMs = targetArtifact.createdAtMs,
                filePath = apkFile.absolutePath,
                sizeInBytes = apkFile.length()
            )
            saveDownloadedApkInfo(downloadedInfo)
            onProgress(100, "ダウンロード・展開完了")
            Result.success(downloadedInfo)
        } finally {
            if (zipFile.exists()) {
                zipFile.delete()
            }
            File(updatesDir, "${zipFile.name}.tmp").delete()
            File(updatesDir, "${apkFile.name}.tmp").delete()
        }
    }

    fun clearCachedRemoteState() {
        prefs.edit()
            .remove(KEY_REMOTE_ID)
            .remove(KEY_REMOTE_NAME)
            .remove(KEY_REMOTE_SIZE)
            .remove(KEY_REMOTE_URL)
            .remove(KEY_REMOTE_CREATED_MS)
            .remove(KEY_REMOTE_EXPIRES_MS)
            .remove(KEY_REMOTE_EXPIRED)
            .remove(KEY_REMOTE_RUN_ID)
            .remove(KEY_REMOTE_HEAD_SHA)
            .remove(KEY_REMOTE_HEAD_BRANCH)
            .remove(KEY_LAST_CHECK_MS)
            .apply()
    }

    private fun saveCachedRemoteArtifact(artifact: RemoteBuildArtifact?, checkedAtMs: Long) {
        val editor = prefs.edit().putLong(KEY_LAST_CHECK_MS, checkedAtMs)
        if (artifact == null) {
            editor
                .remove(KEY_REMOTE_ID)
                .remove(KEY_REMOTE_NAME)
                .remove(KEY_REMOTE_SIZE)
                .remove(KEY_REMOTE_URL)
                .remove(KEY_REMOTE_CREATED_MS)
                .remove(KEY_REMOTE_EXPIRES_MS)
                .remove(KEY_REMOTE_EXPIRED)
                .remove(KEY_REMOTE_RUN_ID)
                .remove(KEY_REMOTE_HEAD_SHA)
                .remove(KEY_REMOTE_HEAD_BRANCH)
        } else {
            editor
                .putLong(KEY_REMOTE_ID, artifact.artifactId)
                .putString(KEY_REMOTE_NAME, artifact.name)
                .putLong(KEY_REMOTE_SIZE, artifact.sizeInBytes)
                .putString(KEY_REMOTE_URL, artifact.archiveDownloadUrl)
                .putLong(KEY_REMOTE_CREATED_MS, artifact.createdAtMs)
                .putLong(KEY_REMOTE_EXPIRES_MS, artifact.expiresAtMs)
                .putBoolean(KEY_REMOTE_EXPIRED, artifact.expired)
                .putLong(KEY_REMOTE_RUN_ID, artifact.runId)
                .putString(KEY_REMOTE_HEAD_SHA, artifact.headSha)
                .putString(KEY_REMOTE_HEAD_BRANCH, artifact.headBranch)
        }
        editor.apply()
    }

    private fun saveDownloadedApkInfo(info: DownloadedApkInfo) {
        prefs.edit()
            .putLong(KEY_DL_ARTIFACT_ID, info.artifactId)
            .putString(KEY_DL_HEAD_SHA, info.headSha)
            .putLong(KEY_DL_RUN_ID, info.runId)
            .putLong(KEY_DL_CREATED_MS, info.createdAtMs)
            .putString(KEY_DL_FILE_PATH, info.filePath)
            .putLong(KEY_DL_SIZE_BYTES, info.sizeInBytes)
            .apply()
    }

    private fun clearDownloadedApkMetadata() {
        prefs.edit()
            .remove(KEY_DL_ARTIFACT_ID)
            .remove(KEY_DL_HEAD_SHA)
            .remove(KEY_DL_RUN_ID)
            .remove(KEY_DL_CREATED_MS)
            .remove(KEY_DL_FILE_PATH)
            .remove(KEY_DL_SIZE_BYTES)
            .apply()
    }

    private fun cleanupArtifacts(dir: File, keepApkName: String?) {
        val files = dir.listFiles() ?: return
        for (file in files) {
            val name = file.name
            val shouldDelete = when {
                name.endsWith(".tmp") || name.endsWith(".zip") -> true
                name.startsWith(APK_PREFIX) && name.endsWith(".apk") -> name != keepApkName
                else -> false
            }
            if (shouldDelete) {
                file.delete()
            }
        }
    }
}
