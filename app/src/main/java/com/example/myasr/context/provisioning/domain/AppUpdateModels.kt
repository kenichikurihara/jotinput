package com.example.myasr.context.provisioning.domain

/**
 * Selectable update distribution channel for tokenless public GitHub updates.
 */
enum class AppUpdateChannel(val id: String, val displayName: String) {
    RELEASE("release", "リリース版"),
    REMOTE_HEAD("remote_head", "リモートヘッド");

    companion object {
        fun fromId(id: String?, defaultChannel: AppUpdateChannel): AppUpdateChannel {
            return entries.firstOrNull { it.id == id } ?: defaultChannel
        }
    }
}

/**
 * Metadata describing the currently running JotInput APK build, embedded at build time via
 * BuildConfig and generated `assets/build_metadata.properties`.
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
 * Metadata for a downloadable public GitHub Release or `main-head` prerelease APK.
 */
data class RemoteBuildArtifact(
    val channel: AppUpdateChannel,
    val artifactId: Long,
    val tagName: String,
    val versionName: String,
    val versionCode: Int,
    val name: String,
    val sizeInBytes: Long,
    val downloadUrl: String,
    val sha256: String,
    val createdAtMs: Long,
    val commitEpochMs: Long,
    val runId: Long,
    val headSha: String,
    val headBranch: String,
    val isDebugVariant: Boolean,
    val expired: Boolean = false
) {
    val shortSha: String
        get() = headSha.trim().takeIf { it.isNotEmpty() && !it.equals("unknown", ignoreCase = true) }?.take(7) ?: "unknown"

    val effectiveTimestampMs: Long
        get() = if (commitEpochMs > 0L) commitEpochMs else createdAtMs

    val displayLabel: String
        get() = when (channel) {
            AppUpdateChannel.RELEASE -> tagName.trim().ifEmpty {
                versionName.trim().takeIf { it.isNotEmpty() }?.let { "v$it" } ?: name
            }
            AppUpdateChannel.REMOTE_HEAD -> shortSha
        }
}

/**
 * Metadata for an already-downloaded and SHA-256 verified APK staged in app-private storage.
 */
data class DownloadedApkInfo(
    val channel: AppUpdateChannel,
    val artifactId: Long,
    val tagName: String,
    val versionName: String,
    val versionCode: Int,
    val headSha: String,
    val runId: Long,
    val commitEpochMs: Long,
    val createdAtMs: Long,
    val filePath: String,
    val sizeInBytes: Long,
    val sha256: String
) {
    val shortSha: String
        get() = headSha.trim().takeIf { it.isNotEmpty() && !it.equals("unknown", ignoreCase = true) }?.take(7) ?: "unknown"

    val displayLabel: String
        get() = when (channel) {
            AppUpdateChannel.RELEASE -> tagName.trim().ifEmpty {
                versionName.trim().takeIf { it.isNotEmpty() }?.let { "v$it" } ?: "release"
            }
            AppUpdateChannel.REMOTE_HEAD -> shortSha
        }
}

enum class BuildFreshnessStatus {
    UPDATE_AVAILABLE,
    UP_TO_DATE,
    NO_ARTIFACT
}

enum class AppUpdateCheckErrorKind {
    NETWORK_ERROR,
    RATE_LIMITED,
    DOWNLOAD_FAILED,
    INTEGRITY_CHECK_FAILED
}

sealed class AppUpdateCheckResult {
    data class Checked(
        val channel: AppUpdateChannel,
        val artifact: RemoteBuildArtifact?,
        val freshness: BuildFreshnessStatus,
        val checkedAtMs: Long
    ) : AppUpdateCheckResult()

    data class Failure(
        val channel: AppUpdateChannel,
        val errorKind: AppUpdateCheckErrorKind,
        val message: String,
        val httpStatusCode: Int? = null
    ) : AppUpdateCheckResult()
}

enum class AppUpdateCardState {
    DOWNLOADING,
    QUEUED,
    CHECKING,
    READY_TO_INSTALL,
    ERROR,
    UPDATE_AVAILABLE,
    UP_TO_DATE,
    NO_ARTIFACT,
    IDLE
}

enum class AppUpdatePrimaryAction {
    CANCEL_DOWNLOAD,
    CHECK_UPDATE,
    INSTALL_APK,
    DOWNLOAD_UPDATE
}

enum class AppUpdateBadgeTone {
    SUCCESS,
    INFO,
    WARNING,
    NEUTRAL
}

enum class ApkInstallCompatibilityStatus {
    COMPATIBLE,
    UNREADABLE_ARCHIVE,
    PACKAGE_NAME_MISMATCH,
    VERSION_DOWNGRADE,
    SIGNATURE_MISMATCH,
    SIGNATURE_UNVERIFIABLE
}
