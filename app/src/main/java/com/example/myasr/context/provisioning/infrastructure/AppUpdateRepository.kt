package com.example.myasr.context.provisioning.infrastructure

import android.content.Context
import android.util.Log
import com.example.myasr.BuildConfig
import com.example.myasr.context.provisioning.domain.AppUpdateChannel
import com.example.myasr.context.provisioning.domain.AppUpdateCheckErrorKind
import com.example.myasr.context.provisioning.domain.AppUpdateCheckResult
import com.example.myasr.context.provisioning.domain.AppUpdateDecisionModel
import com.example.myasr.context.provisioning.domain.AppUpdateDecisionModel.BuildFreshnessInput
import com.example.myasr.context.provisioning.domain.BuildFreshnessStatus
import com.example.myasr.context.provisioning.domain.DownloadedApkInfo
import com.example.myasr.context.provisioning.domain.InstalledAppBuildInfo
import com.example.myasr.context.provisioning.domain.RemoteBuildArtifact
import java.io.File
import java.util.Locale

/**
 * Coordinates tokenless multi-channel (Stable Release / Remote HEAD) update checks,
 * per-channel metadata caching, direct HTTPS APK downloads with SHA-256 verification,
 * and automatic cleanup of obsolete staged APKs and legacy PAT storage.
 */
class AppUpdateRepository(
    private val context: Context,
    private val client: GitHubAppUpdateClient = GitHubAppUpdateClient(),
    private val isDebugBuild: Boolean = BuildConfig.DEBUG
) {
    companion object {
        private const val TAG = "AppUpdateRepository"
        private const val PREFS_NAME = "app_update_metadata"
        private const val LEGACY_PAT_FILE = "github_actions_pat.dat"

        private const val KEY_SELECTED_CHANNEL = "selected_update_channel"

        private const val SUFFIX_LAST_CHECK_MS = "last_check_epoch_ms"
        private const val SUFFIX_REMOTE_ID = "remote_artifact_id"
        private const val SUFFIX_REMOTE_TAG = "remote_tag_name"
        private const val SUFFIX_REMOTE_VERSION_NAME = "remote_version_name"
        private const val SUFFIX_REMOTE_VERSION_CODE = "remote_version_code"
        private const val SUFFIX_REMOTE_NAME = "remote_name"
        private const val SUFFIX_REMOTE_SIZE = "remote_size_bytes"
        private const val SUFFIX_REMOTE_URL = "remote_download_url"
        private const val SUFFIX_REMOTE_SHA256 = "remote_sha256"
        private const val SUFFIX_REMOTE_CREATED_MS = "remote_created_at_ms"
        private const val SUFFIX_REMOTE_COMMIT_MS = "remote_commit_epoch_ms"
        private const val SUFFIX_REMOTE_RUN_ID = "remote_run_id"
        private const val SUFFIX_REMOTE_HEAD_SHA = "remote_head_sha"
        private const val SUFFIX_REMOTE_HEAD_BRANCH = "remote_head_branch"
        private const val SUFFIX_REMOTE_IS_DEBUG = "remote_is_debug_variant"

        private const val KEY_DL_CHANNEL = "dl_channel"
        private const val KEY_DL_ARTIFACT_ID = "dl_artifact_id"
        private const val KEY_DL_TAG_NAME = "dl_tag_name"
        private const val KEY_DL_VERSION_NAME = "dl_version_name"
        private const val KEY_DL_VERSION_CODE = "dl_version_code"
        private const val KEY_DL_HEAD_SHA = "dl_head_sha"
        private const val KEY_DL_RUN_ID = "dl_run_id"
        private const val KEY_DL_COMMIT_MS = "dl_commit_epoch_ms"
        private const val KEY_DL_CREATED_MS = "dl_created_at_ms"
        private const val KEY_DL_FILE_PATH = "dl_file_path"
        private const val KEY_DL_SIZE_BYTES = "dl_size_bytes"
        private const val KEY_DL_SHA256 = "dl_sha256"

        private const val APK_PREFIX = "jotinput-"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        cleanupLegacyTokenFiles()
    }

    fun getInstalledBuildInfo(): InstalledAppBuildInfo = InstalledAppBuildInfo(
        versionName = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE,
        gitSha = BuildConfig.BUILD_GIT_SHA,
        commitEpochMs = BuildConfig.BUILD_GIT_COMMIT_EPOCH_MS,
        githubRunId = BuildConfig.BUILD_GITHUB_RUN_ID,
        isDebugBuild = isDebugBuild
    )

    fun getDefaultChannel(): AppUpdateChannel =
        if (isDebugBuild) AppUpdateChannel.REMOTE_HEAD else AppUpdateChannel.RELEASE

    fun getSelectedChannel(): AppUpdateChannel =
        AppUpdateChannel.fromId(prefs.getString(KEY_SELECTED_CHANNEL, null), getDefaultChannel())

    fun setSelectedChannel(channel: AppUpdateChannel) {
        prefs.edit().putString(KEY_SELECTED_CHANNEL, channel.id).apply()
    }

    fun getUpdatesDir(): File {
        val dir = context.getExternalFilesDir("updates") ?: File(context.filesDir, "updates")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getLastCheckEpochMs(channel: AppUpdateChannel = getSelectedChannel()): Long =
        prefs.getLong(channelKey(channel, SUFFIX_LAST_CHECK_MS), 0L)

    fun getCachedRemoteArtifact(channel: AppUpdateChannel = getSelectedChannel()): RemoteBuildArtifact? {
        val id = prefs.getLong(channelKey(channel, SUFFIX_REMOTE_ID), 0L)
        val url = prefs.getString(channelKey(channel, SUFFIX_REMOTE_URL), "") ?: ""
        if (id <= 0L || url.isBlank()) return null

        val cachedDebugVariant = prefs.getBoolean(channelKey(channel, SUFFIX_REMOTE_IS_DEBUG), isDebugBuild)
        if (channel == AppUpdateChannel.REMOTE_HEAD && cachedDebugVariant != isDebugBuild) {
            return null
        }

        return RemoteBuildArtifact(
            channel = channel,
            artifactId = id,
            tagName = prefs.getString(channelKey(channel, SUFFIX_REMOTE_TAG), "") ?: "",
            versionName = prefs.getString(channelKey(channel, SUFFIX_REMOTE_VERSION_NAME), "") ?: "",
            versionCode = prefs.getInt(channelKey(channel, SUFFIX_REMOTE_VERSION_CODE), 0),
            name = prefs.getString(channelKey(channel, SUFFIX_REMOTE_NAME), "") ?: "",
            sizeInBytes = prefs.getLong(channelKey(channel, SUFFIX_REMOTE_SIZE), 0L),
            downloadUrl = url,
            sha256 = prefs.getString(channelKey(channel, SUFFIX_REMOTE_SHA256), "") ?: "",
            createdAtMs = prefs.getLong(channelKey(channel, SUFFIX_REMOTE_CREATED_MS), 0L),
            commitEpochMs = prefs.getLong(channelKey(channel, SUFFIX_REMOTE_COMMIT_MS), 0L),
            runId = prefs.getLong(channelKey(channel, SUFFIX_REMOTE_RUN_ID), 0L),
            headSha = prefs.getString(channelKey(channel, SUFFIX_REMOTE_HEAD_SHA), "") ?: "",
            headBranch = prefs.getString(channelKey(channel, SUFFIX_REMOTE_HEAD_BRANCH), BuildConfig.GITHUB_TARGET_BRANCH)
                ?: BuildConfig.GITHUB_TARGET_BRANCH,
            isDebugVariant = cachedDebugVariant,
            expired = false
        )
    }

    fun getCurrentFreshnessStatus(channel: AppUpdateChannel = getSelectedChannel()): BuildFreshnessStatus? {
        if (getLastCheckEpochMs(channel) <= 0L) return null
        val cached = getCachedRemoteArtifact(channel) ?: return BuildFreshnessStatus.NO_ARTIFACT
        return AppUpdateDecisionModel.evaluateBuildFreshness(
            BuildFreshnessInput(
                installed = getInstalledBuildInfo(),
                remoteArtifact = cached
            )
        ).status
    }

    fun getMatchingDownloadedApk(
        channel: AppUpdateChannel = getSelectedChannel(),
        latestRemote: RemoteBuildArtifact? = getCachedRemoteArtifact(channel)
    ): DownloadedApkInfo? {
        val artifactId = prefs.getLong(KEY_DL_ARTIFACT_ID, 0L)
        val path = prefs.getString(KEY_DL_FILE_PATH, null)
        if (artifactId <= 0L || path.isNullOrBlank()) {
            return null
        }
        val file = File(path)
        if (!file.exists() || file.length() < GitHubAppUpdateClient.MIN_VALID_APK_BYTES) {
            clearDownloadedApkMetadata()
            return null
        }

        val dlChannel = AppUpdateChannel.fromId(
            prefs.getString(KEY_DL_CHANNEL, null),
            AppUpdateChannel.REMOTE_HEAD
        )
        val info = DownloadedApkInfo(
            channel = dlChannel,
            artifactId = artifactId,
            tagName = prefs.getString(KEY_DL_TAG_NAME, "") ?: "",
            versionName = prefs.getString(KEY_DL_VERSION_NAME, "") ?: "",
            versionCode = prefs.getInt(KEY_DL_VERSION_CODE, 0),
            headSha = prefs.getString(KEY_DL_HEAD_SHA, "") ?: "",
            runId = prefs.getLong(KEY_DL_RUN_ID, 0L),
            commitEpochMs = prefs.getLong(KEY_DL_COMMIT_MS, 0L),
            createdAtMs = prefs.getLong(KEY_DL_CREATED_MS, 0L),
            filePath = path,
            sizeInBytes = prefs.getLong(KEY_DL_SIZE_BYTES, file.length()),
            sha256 = prefs.getString(KEY_DL_SHA256, "") ?: ""
        )

        val syntheticArtifact = RemoteBuildArtifact(
            channel = info.channel,
            artifactId = info.artifactId,
            tagName = info.tagName,
            versionName = info.versionName,
            versionCode = info.versionCode,
            name = file.name,
            sizeInBytes = info.sizeInBytes,
            downloadUrl = "https://github.com/kenichikurihara/jotinput/releases/download/placeholder/app.apk",
            sha256 = info.sha256,
            createdAtMs = info.createdAtMs,
            commitEpochMs = info.commitEpochMs,
            runId = info.runId,
            headSha = info.headSha,
            headBranch = BuildConfig.GITHUB_TARGET_BRANCH,
            isDebugVariant = isDebugBuild,
            expired = false
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

        // Strict cross-channel isolation: never surface a staged APK from another channel.
        if (info.channel != channel) {
            return null
        }

        if (latestRemote != null && latestRemote.channel == channel) {
            when (channel) {
                AppUpdateChannel.RELEASE -> {
                    if (latestRemote.tagName.isNotBlank() &&
                        info.tagName.isNotBlank() &&
                        latestRemote.tagName != info.tagName
                    ) {
                        return null
                    }
                }
                AppUpdateChannel.REMOTE_HEAD -> {
                    if (latestRemote.headSha.isNotBlank() &&
                        info.headSha.isNotBlank() &&
                        !latestRemote.headSha.equals(info.headSha, ignoreCase = true)
                    ) {
                        return null
                    }
                    if (latestRemote.runId > 0L && info.runId > 0L && latestRemote.runId != info.runId) {
                        return null
                    }
                }
            }
        }
        return info
    }

    suspend fun checkLatestBuild(
        channel: AppUpdateChannel = getSelectedChannel()
    ): AppUpdateCheckResult {
        val res = when (channel) {
            AppUpdateChannel.RELEASE -> client.fetchLatestStableRelease(
                owner = BuildConfig.GITHUB_REPO_OWNER,
                repo = BuildConfig.GITHUB_REPO_NAME
            )
            AppUpdateChannel.REMOTE_HEAD -> client.fetchLatestRemoteHead(
                owner = BuildConfig.GITHUB_REPO_OWNER,
                repo = BuildConfig.GITHUB_REPO_NAME,
                headTag = BuildConfig.GITHUB_HEAD_RELEASE_TAG,
                metadataAsset = BuildConfig.GITHUB_HEAD_METADATA_ASSET,
                preferDebugVariant = isDebugBuild
            )
        }
        return when (res) {
            is GitHubAppUpdateClient.FetchResult.Success -> {
                val now = System.currentTimeMillis()
                saveCachedRemoteArtifact(channel, res.artifact, now)
                val freshness = AppUpdateDecisionModel.evaluateBuildFreshness(
                    BuildFreshnessInput(
                        installed = getInstalledBuildInfo(),
                        remoteArtifact = res.artifact
                    )
                ).status
                AppUpdateCheckResult.Checked(
                    channel = channel,
                    artifact = res.artifact,
                    freshness = freshness,
                    checkedAtMs = now
                )
            }
            is GitHubAppUpdateClient.FetchResult.Error -> {
                AppUpdateCheckResult.Failure(
                    channel = channel,
                    errorKind = res.errorKind,
                    message = res.message,
                    httpStatusCode = res.httpStatusCode
                )
            }
        }
    }

    suspend fun downloadLatestBuild(
        channel: AppUpdateChannel = getSelectedChannel(),
        onProgress: (percent: Int, statusText: String) -> Unit
    ): Result<DownloadedApkInfo> {
        onProgress(0, "最新ビルド情報を確認中...")
        val targetArtifact = when (val check = checkLatestBuild(channel)) {
            is AppUpdateCheckResult.Failure -> {
                return Result.failure(
                    GitHubAppUpdateClient.DownloadHttpException(
                        check.httpStatusCode ?: 0,
                        check.errorKind,
                        check.message
                    )
                )
            }
            is AppUpdateCheckResult.Checked -> {
                if (check.artifact == null || check.freshness != BuildFreshnessStatus.UPDATE_AVAILABLE) {
                    return Result.failure(IllegalStateException("ダウンロード可能な新しい更新が見つかりませんでした"))
                }
                check.artifact
            }
        }

        val updatesDir = getUpdatesDir()
        cleanupArtifacts(updatesDir, keepApkName = null)

        val apkFile = File(updatesDir, "$APK_PREFIX${channel.id}-${targetArtifact.artifactId}.apk")
        return try {
            val dlRes = client.downloadApkFile(
                downloadUrl = targetArtifact.downloadUrl,
                destinationApk = apkFile,
                expectedSizeBytes = targetArtifact.sizeInBytes,
                expectedSha256 = targetArtifact.sha256
            ) { bytesRead, totalBytes ->
                val denom = if (totalBytes > 0L) totalBytes else targetArtifact.sizeInBytes.coerceAtLeast(1L)
                val dlPct = ((bytesRead.toDouble() / denom) * 98.0).toInt().coerceIn(0, 98)
                val readMb = String.format(Locale.US, "%.1f", bytesRead / (1024.0 * 1024.0))
                val totalMb = String.format(Locale.US, "%.1f", denom / (1024.0 * 1024.0))
                onProgress(dlPct, "APK をダウンロード中... (${readMb}MB / ${totalMb}MB)")
            }
            if (dlRes.isFailure) {
                return Result.failure(
                    dlRes.exceptionOrNull() ?: IllegalStateException("APK のダウンロードに失敗しました")
                )
            }

            onProgress(99, "SHA-256 を検証完了...")
            val verified = dlRes.getOrThrow()
            cleanupArtifacts(updatesDir, keepApkName = apkFile.name)

            val downloadedInfo = DownloadedApkInfo(
                channel = channel,
                artifactId = targetArtifact.artifactId,
                tagName = targetArtifact.tagName,
                versionName = targetArtifact.versionName,
                versionCode = targetArtifact.versionCode,
                headSha = targetArtifact.headSha,
                runId = targetArtifact.runId,
                commitEpochMs = targetArtifact.commitEpochMs,
                createdAtMs = targetArtifact.createdAtMs,
                filePath = apkFile.absolutePath,
                sizeInBytes = verified.sizeInBytes,
                sha256 = verified.sha256
            )
            saveDownloadedApkInfo(downloadedInfo)
            onProgress(100, "ダウンロード・検証完了")
            Result.success(downloadedInfo)
        } finally {
            File(updatesDir, "${apkFile.name}.tmp").delete()
        }
    }

    fun clearStagedDownloadedApk() {
        val stagedPath = prefs.getString(KEY_DL_FILE_PATH, null)
        if (!stagedPath.isNullOrBlank()) {
            runCatching { File(stagedPath).delete() }
        }
        runCatching { cleanupArtifacts(getUpdatesDir(), keepApkName = null) }
        clearDownloadedApkMetadata()
    }

    fun clearCachedRemoteState(channel: AppUpdateChannel? = null) {
        val targets = if (channel != null) listOf(channel) else AppUpdateChannel.entries
        val editor = prefs.edit()
        for (target in targets) {
            removeChannelRemoteKeys(editor, target)
            editor.remove(channelKey(target, SUFFIX_LAST_CHECK_MS))
        }
        editor.apply()
    }

    private fun channelKey(channel: AppUpdateChannel, suffix: String): String =
        "${channel.id}_$suffix"

    private fun saveCachedRemoteArtifact(
        channel: AppUpdateChannel,
        artifact: RemoteBuildArtifact?,
        checkedAtMs: Long
    ) {
        val editor = prefs.edit().putLong(channelKey(channel, SUFFIX_LAST_CHECK_MS), checkedAtMs)
        if (artifact == null) {
            removeChannelRemoteKeys(editor, channel)
        } else {
            editor
                .putLong(channelKey(channel, SUFFIX_REMOTE_ID), artifact.artifactId)
                .putString(channelKey(channel, SUFFIX_REMOTE_TAG), artifact.tagName)
                .putString(channelKey(channel, SUFFIX_REMOTE_VERSION_NAME), artifact.versionName)
                .putInt(channelKey(channel, SUFFIX_REMOTE_VERSION_CODE), artifact.versionCode)
                .putString(channelKey(channel, SUFFIX_REMOTE_NAME), artifact.name)
                .putLong(channelKey(channel, SUFFIX_REMOTE_SIZE), artifact.sizeInBytes)
                .putString(channelKey(channel, SUFFIX_REMOTE_URL), artifact.downloadUrl)
                .putString(channelKey(channel, SUFFIX_REMOTE_SHA256), artifact.sha256)
                .putLong(channelKey(channel, SUFFIX_REMOTE_CREATED_MS), artifact.createdAtMs)
                .putLong(channelKey(channel, SUFFIX_REMOTE_COMMIT_MS), artifact.commitEpochMs)
                .putLong(channelKey(channel, SUFFIX_REMOTE_RUN_ID), artifact.runId)
                .putString(channelKey(channel, SUFFIX_REMOTE_HEAD_SHA), artifact.headSha)
                .putString(channelKey(channel, SUFFIX_REMOTE_HEAD_BRANCH), artifact.headBranch)
                .putBoolean(channelKey(channel, SUFFIX_REMOTE_IS_DEBUG), artifact.isDebugVariant)
        }
        editor.apply()
    }

    private fun removeChannelRemoteKeys(
        editor: android.content.SharedPreferences.Editor,
        channel: AppUpdateChannel
    ) {
        editor
            .remove(channelKey(channel, SUFFIX_REMOTE_ID))
            .remove(channelKey(channel, SUFFIX_REMOTE_TAG))
            .remove(channelKey(channel, SUFFIX_REMOTE_VERSION_NAME))
            .remove(channelKey(channel, SUFFIX_REMOTE_VERSION_CODE))
            .remove(channelKey(channel, SUFFIX_REMOTE_NAME))
            .remove(channelKey(channel, SUFFIX_REMOTE_SIZE))
            .remove(channelKey(channel, SUFFIX_REMOTE_URL))
            .remove(channelKey(channel, SUFFIX_REMOTE_SHA256))
            .remove(channelKey(channel, SUFFIX_REMOTE_CREATED_MS))
            .remove(channelKey(channel, SUFFIX_REMOTE_COMMIT_MS))
            .remove(channelKey(channel, SUFFIX_REMOTE_RUN_ID))
            .remove(channelKey(channel, SUFFIX_REMOTE_HEAD_SHA))
            .remove(channelKey(channel, SUFFIX_REMOTE_HEAD_BRANCH))
            .remove(channelKey(channel, SUFFIX_REMOTE_IS_DEBUG))
    }

    private fun saveDownloadedApkInfo(info: DownloadedApkInfo) {
        prefs.edit()
            .putString(KEY_DL_CHANNEL, info.channel.id)
            .putLong(KEY_DL_ARTIFACT_ID, info.artifactId)
            .putString(KEY_DL_TAG_NAME, info.tagName)
            .putString(KEY_DL_VERSION_NAME, info.versionName)
            .putInt(KEY_DL_VERSION_CODE, info.versionCode)
            .putString(KEY_DL_HEAD_SHA, info.headSha)
            .putLong(KEY_DL_RUN_ID, info.runId)
            .putLong(KEY_DL_COMMIT_MS, info.commitEpochMs)
            .putLong(KEY_DL_CREATED_MS, info.createdAtMs)
            .putString(KEY_DL_FILE_PATH, info.filePath)
            .putLong(KEY_DL_SIZE_BYTES, info.sizeInBytes)
            .putString(KEY_DL_SHA256, info.sha256)
            .apply()
    }

    private fun clearDownloadedApkMetadata() {
        prefs.edit()
            .remove(KEY_DL_CHANNEL)
            .remove(KEY_DL_ARTIFACT_ID)
            .remove(KEY_DL_TAG_NAME)
            .remove(KEY_DL_VERSION_NAME)
            .remove(KEY_DL_VERSION_CODE)
            .remove(KEY_DL_HEAD_SHA)
            .remove(KEY_DL_RUN_ID)
            .remove(KEY_DL_COMMIT_MS)
            .remove(KEY_DL_CREATED_MS)
            .remove(KEY_DL_FILE_PATH)
            .remove(KEY_DL_SIZE_BYTES)
            .remove(KEY_DL_SHA256)
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

    private fun cleanupLegacyTokenFiles() {
        val dirs = listOfNotNull(
            runCatching { context.noBackupFilesDir }.getOrNull(),
            runCatching { context.filesDir }.getOrNull()
        ).distinctBy { it.absolutePath }
        for (dir in dirs) {
            runCatching {
                val patFile = File(dir, LEGACY_PAT_FILE)
                if (patFile.exists()) {
                    patFile.delete()
                }
                val patTmp = File(dir, "$LEGACY_PAT_FILE.tmp")
                if (patTmp.exists()) {
                    patTmp.delete()
                }
            }
        }
    }
}
