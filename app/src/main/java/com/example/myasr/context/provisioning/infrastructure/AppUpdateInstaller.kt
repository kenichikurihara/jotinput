package com.example.myasr.context.provisioning.infrastructure

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.example.myasr.context.provisioning.domain.AppUpdateDecisionModel
import com.example.myasr.context.provisioning.domain.AppUpdateDecisionModel.ApkInstallCompatibilityDecision
import com.example.myasr.context.provisioning.domain.AppUpdateDecisionModel.ApkInstallCompatibilityInput
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * Helper for verifying APK compatibility (package ID, versionCode, signing certificate SHA-256),
 * checking `REQUEST_INSTALL_PACKAGES` permission, and launching Android's package installer
 * via [FileProvider] (debug builds only).
 */
object AppUpdateInstaller {

    data class ApkInstallInspectionResult(
        val decision: ApkInstallCompatibilityDecision,
        val installedPackageName: String,
        val archivePackageName: String?,
        val installedVersionCode: Long,
        val archiveVersionCode: Long?,
        val installedCertSha256s: Set<String>,
        val archiveCertSha256s: Set<String>
    ) {
        fun primaryInstalledCertShort(): String? =
            installedCertSha256s.firstOrNull()?.let { formatShortFingerprint(it) }

        fun primaryArchiveCertShort(): String? =
            archiveCertSha256s.firstOrNull()?.let { formatShortFingerprint(it) }
    }

    fun canRequestPackageInstalls(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(false)
        } else {
            true
        }
    }

    fun buildUnknownSourcesSettingsIntent(context: Context): Intent {
        return Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        )
    }

    fun buildInstallIntent(context: Context, apkFile: File): Intent {
        val contentUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun inspectApkCompatibility(context: Context, apkFile: File): ApkInstallInspectionResult {
        val pm = context.packageManager
        val installedPkgName = context.packageName

        val installedInfo = getInstalledPackageInfo(pm, installedPkgName)
        val installedVersionCode = installedInfo?.let { PackageInfoCompat.getLongVersionCode(it) } ?: 0L
        val installedCerts = extractCertSha256s(installedInfo)

        if (!apkFile.exists() || !apkFile.isFile || !apkFile.canRead()) {
            val decision = AppUpdateDecisionModel.evaluateApkInstallCompatibility(
                ApkInstallCompatibilityInput(
                    apkReadable = false,
                    installedPackageName = installedPkgName,
                    archivePackageName = null,
                    installedVersionCode = installedVersionCode,
                    archiveVersionCode = null,
                    installedSigningCertSha256s = installedCerts,
                    archiveSigningCertSha256s = emptySet()
                )
            )
            return ApkInstallInspectionResult(
                decision = decision,
                installedPackageName = installedPkgName,
                archivePackageName = null,
                installedVersionCode = installedVersionCode,
                archiveVersionCode = null,
                installedCertSha256s = installedCerts,
                archiveCertSha256s = emptySet()
            )
        }

        val archiveInfo = getArchivePackageInfo(pm, apkFile.absolutePath)
        val archivePkgName = archiveInfo?.packageName
        val archiveVersionCode = archiveInfo?.let { PackageInfoCompat.getLongVersionCode(it) }
        val archiveCerts = extractCertSha256s(archiveInfo)

        val decision = AppUpdateDecisionModel.evaluateApkInstallCompatibility(
            ApkInstallCompatibilityInput(
                apkReadable = archiveInfo != null,
                installedPackageName = installedPkgName,
                archivePackageName = archivePkgName,
                installedVersionCode = installedVersionCode,
                archiveVersionCode = archiveVersionCode,
                installedSigningCertSha256s = installedCerts,
                archiveSigningCertSha256s = archiveCerts
            )
        )
        return ApkInstallInspectionResult(
            decision = decision,
            installedPackageName = installedPkgName,
            archivePackageName = archivePkgName,
            installedVersionCode = installedVersionCode,
            archiveVersionCode = archiveVersionCode,
            installedCertSha256s = installedCerts,
            archiveCertSha256s = archiveCerts
        )
    }

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            sb.append(String.format(Locale.US, "%02x", b.toInt() and 0xFF))
        }
        return sb.toString()
    }

    fun formatShortFingerprint(sha256Hex: String): String {
        val cleaned = sha256Hex.trim().replace(":", "").uppercase(Locale.US)
        if (cleaned.isEmpty()) return "unknown"
        return cleaned.take(16).chunked(4).joinToString(":")
    }

    private fun getInstalledPackageInfo(pm: PackageManager, packageName: String): PackageInfo? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val primary = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageInfo(
                        packageName,
                        PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
                    )
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                }
            }.getOrNull()
            if (primary != null && extractCertSha256s(primary).isNotEmpty()) {
                return primary
            }
        }
        return runCatching {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNATURES.toLong())
                )
            } else {
                pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
            }
        }.getOrNull()
    }

    private fun getArchivePackageInfo(pm: PackageManager, archiveFilePath: String): PackageInfo? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val primary = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageArchiveInfo(
                        archiveFilePath,
                        PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
                    )
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageArchiveInfo(archiveFilePath, PackageManager.GET_SIGNING_CERTIFICATES)
                }
            }.getOrNull()
            if (primary != null && extractCertSha256s(primary).isNotEmpty()) {
                return primary
            }
        }
        return runCatching {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageArchiveInfo(
                    archiveFilePath,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNATURES.toLong())
                )
            } else {
                pm.getPackageArchiveInfo(archiveFilePath, PackageManager.GET_SIGNATURES)
            }
        }.getOrNull()
    }

    private fun extractCertSha256s(packageInfo: PackageInfo?): Set<String> {
        if (packageInfo == null) return emptySet()
        val certs = linkedSetOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = packageInfo.signingInfo
            if (signingInfo != null) {
                val signers = if (signingInfo.hasMultipleSigners()) {
                    signingInfo.apkContentsSigners
                } else {
                    signingInfo.signingCertificateHistory
                }
                signers?.forEach { sig ->
                    certs.add(sha256Hex(sig.toByteArray()))
                }
                if (certs.isEmpty()) {
                    signingInfo.apkContentsSigners?.forEach { sig ->
                        certs.add(sha256Hex(sig.toByteArray()))
                    }
                }
            }
        }
        if (certs.isEmpty()) {
            @Suppress("DEPRECATION")
            packageInfo.signatures?.forEach { sig ->
                certs.add(sha256Hex(sig.toByteArray()))
            }
        }
        return certs
    }
}
