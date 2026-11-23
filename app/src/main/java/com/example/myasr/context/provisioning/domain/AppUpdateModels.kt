package com.example.myasr.context.provisioning.domain

/**
 * Metadata describing the currently running JotInput APK build, embedded at compile time via BuildConfig.
 */
data class InstalledAppBuildInfo(
    val versionName: String,
    val versionCode: Int,
    val gitSha: String,
    val commitEpochMs: Long,
    val githubRunId: Long,
    val isDebugBuild: Boolean
) {
    val shortSha: String
        get() = gitSha.trim().takeIf { it.isNotEmpty() && !it.equals("unknown", ignoreCase = true) }?.take(7) ?: "unknown"
}

/**
 * Metadata for a GitHub Actions build artifact (`app-debug`) fetched from the GitHub REST API.
 */
data class RemoteBuildArtifact(
    val artifactId: Long,
    val name: String,
    val sizeInBytes: Long,
    val archiveDownloadUrl: String,
    val createdAtMs: Long,
    val expiresAtMs: Long,
    val expired: Boolean,
    val runId: Long,
    val headSha: String,
    val headBranch: String
) {
    val shortSha: String
        get() = headSha.trim().takeIf { it.isNotEmpty() }?.take(7) ?: "unknown"
}

/**
 * Metadata for an already-downloaded and extracted APK staged in app-private storage.
 */
data class DownloadedApkInfo(
    val artifactId: Long,
    val headSha: String,
    val runId: Long,
    val createdAtMs: Long,
    val filePath: String,
    val sizeInBytes: Long
) {
    val shortSha: String
        get() = headSha.trim().takeIf { it.isNotEmpty() }?.take(7) ?: "unknown"
}

enum class BuildFreshnessStatus {
    UPDATE_AVAILABLE,
    UP_TO_DATE,
    NO_ARTIFACT
}

enum class AppUpdateCheckErrorKind {
    TOKEN_REQUIRED,
    AUTH_FAILED,
    NETWORK_ERROR,
    DOWNLOAD_FAILED
}

sealed class AppUpdateCheckResult {
    data class Checked(
        val artifact: RemoteBuildArtifact?,
        val freshness: BuildFreshnessStatus,
        val checkedAtMs: Long
    ) : AppUpdateCheckResult()

    data class Failure(
        val errorKind: AppUpdateCheckErrorKind,
        val message: String,
        val httpStatusCode: Int? = null
    ) : AppUpdateCheckResult()
}

enum class AppUpdateCardState {
    HIDDEN,
    DOWNLOADING,
    QUEUED,
    CHECKING,
    READY_TO_INSTALL,
    TOKEN_MISSING,
    AUTH_ERROR,
    NETWORK_ERROR,
    UPDATE_AVAILABLE,
    UP_TO_DATE,
    NO_ARTIFACT,
    IDLE
}

enum class AppUpdatePrimaryAction {
    NONE,
    CANCEL_DOWNLOAD,
    CHECK_UPDATE,
    INSTALL_APK,
    CONFIGURE_TOKEN,
    DOWNLOAD_UPDATE
}

enum class AppUpdateBadgeTone {
    SUCCESS,
    INFO,
    WARNING,
    NEUTRAL
}
