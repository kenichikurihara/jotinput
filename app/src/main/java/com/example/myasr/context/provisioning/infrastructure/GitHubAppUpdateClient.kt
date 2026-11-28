package com.example.myasr.context.provisioning.infrastructure

import android.util.Log
import com.example.myasr.context.provisioning.domain.AppUpdateChannel
import com.example.myasr.context.provisioning.domain.AppUpdateCheckErrorKind
import com.example.myasr.context.provisioning.domain.RemoteBuildArtifact
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.coroutines.coroutineContext

/**
 * Unauthenticated (tokenless) public GitHub Releases & `main-head` prerelease update client.
 *
 * Security & fail-safe guarantees:
 * - Zero tokens / zero `Authorization` headers.
 * - Accepts initial download URLs only from trusted HTTPS GitHub/GitHubusercontent hosts.
 * - Manually resolves HTTP 301/302/303/307/308 redirects while rejecting non-HTTPS targets.
 * - Validates APK ZIP header (`PK\x03\x04`), min/max byte bounds, and SHA-256 digest before
 *   atomically finalizing the downloaded APK file.
 */
class GitHubAppUpdateClient {

    sealed class FetchResult {
        data class Success(val artifact: RemoteBuildArtifact?) : FetchResult()
        data class Error(
            val errorKind: AppUpdateCheckErrorKind,
            val message: String,
            val httpStatusCode: Int? = null
        ) : FetchResult()
    }

    data class ApkDownloadVerification(
        val file: File,
        val sizeInBytes: Long,
        val sha256: String
    )

    class DownloadHttpException(
        val httpStatusCode: Int,
        val errorKind: AppUpdateCheckErrorKind,
        message: String
    ) : IOException(message)

    companion object {
        private const val TAG = "GitHubAppUpdateClient"
        private const val API_HOST = "api.github.com"
        private const val WEB_HOST = "github.com"
        private const val API_VERSION = "2022-11-28"
        private const val MAX_REDIRECTS = 5
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val DOWNLOAD_READ_TIMEOUT_MS = 45_000
        private const val USER_AGENT = "JotInput-Android/1.0"

        const val MIN_VALID_APK_BYTES = 1L * 1024L * 1024L
        const val MAX_VALID_APK_BYTES = 220L * 1024L * 1024L

        private val SHA256_HEX_REGEX = Regex("^[0-9a-f]{64}$")

        fun isTrustedGitHubDownloadHost(urlStr: String): Boolean {
            return try {
                val url = URL(urlStr)
                if (!url.protocol.equals("https", ignoreCase = true)) {
                    return false
                }
                val host = url.host.lowercase(Locale.US)
                host == WEB_HOST ||
                    host == API_HOST ||
                    host == "objects.githubusercontent.com" ||
                    host == "release-assets.githubusercontent.com" ||
                    host.endsWith(".githubusercontent.com")
            } catch (_: Exception) {
                false
            }
        }

        fun normalizeSha256(raw: String?): String {
            val cleaned = raw?.trim()
                ?.removePrefix("sha256:")
                ?.removePrefix("SHA256:")
                ?.trim()
                ?.lowercase(Locale.US)
                .orEmpty()
            return if (SHA256_HEX_REGEX.matches(cleaned)) cleaned else ""
        }

        fun parseIso8601Millis(isoString: String?): Long {
            val trimmed = isoString?.trim().orEmpty()
            if (trimmed.isEmpty()) return 0L

            val patterns = arrayOf(
                "yyyy-MM-dd'T'HH:mm:ss'Z'",
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                "yyyy-MM-dd'T'HH:mm:ssXXX",
                "yyyy-MM-dd'T'HH:mm:ss.SSSXXX"
            )
            for (pattern in patterns) {
                try {
                    val sdf = SimpleDateFormat(pattern, Locale.US).apply {
                        timeZone = TimeZone.getTimeZone("UTC")
                    }
                    val parsed = sdf.parse(trimmed)
                    if (parsed != null && parsed.time > 0L) {
                        return parsed.time
                    }
                } catch (_: Exception) {
                }
            }
            return 0L
        }

        fun parseStableReleaseJson(jsonText: String): RemoteBuildArtifact? {
            val root = JSONObject(jsonText)
            if (root.optBoolean("draft", false) || root.optBoolean("prerelease", false)) {
                return null
            }
            val tagName = root.optString("tag_name", "").trim()
            val versionName = tagName.removePrefix("v").removePrefix("V").trim()
            val publishedAtMs = parseIso8601Millis(
                root.optString("published_at", "").ifBlank { root.optString("created_at", "") }
            )
            val targetBranch = root.optString("target_commitish", "main").trim().ifEmpty { "main" }
            val assets = root.optJSONArray("assets") ?: return null

            data class CandidateAsset(
                val id: Long,
                val name: String,
                val size: Long,
                val url: String,
                val sha256: String
            )

            val apkAssets = ArrayList<CandidateAsset>(assets.length())
            for (i in 0 until assets.length()) {
                val obj = assets.optJSONObject(i) ?: continue
                val name = obj.optString("name", "").trim()
                val url = obj.optString("browser_download_url", "").trim()
                val size = obj.optLong("size", 0L)
                if (!name.endsWith(".apk", ignoreCase = true)) continue
                if (size <= 0L || !isTrustedGitHubDownloadHost(url)) continue
                val id = obj.optLong("id", 0L).takeIf { it > 0L } ?: (i + 1L)
                val sha256 = normalizeSha256(obj.optString("digest", ""))
                apkAssets.add(CandidateAsset(id, name, size, url, sha256))
            }
            val chosen = apkAssets.firstOrNull { it.name.endsWith("-release.apk", ignoreCase = true) }
                ?: apkAssets.firstOrNull()
                ?: return null

            return RemoteBuildArtifact(
                channel = AppUpdateChannel.RELEASE,
                artifactId = chosen.id,
                tagName = tagName,
                versionName = versionName,
                versionCode = 0,
                name = chosen.name,
                sizeInBytes = chosen.size,
                downloadUrl = chosen.url,
                sha256 = chosen.sha256,
                createdAtMs = publishedAtMs,
                commitEpochMs = 0L,
                runId = 0L,
                headSha = "",
                headBranch = targetBranch,
                isDebugVariant = false,
                expired = false
            )
        }

        fun parseHeadMetadataJson(jsonText: String, preferDebugVariant: Boolean): RemoteBuildArtifact? {
            val root = JSONObject(jsonText)
            val gitSha = root.optString("gitSha", "").trim()
            if (gitSha.isEmpty()) return null

            val variantKey = if (preferDebugVariant) "debugApk" else "releaseApk"
            val variantObj = root.optJSONObject(variantKey) ?: return null
            val name = variantObj.optString("name", "").trim()
            val downloadUrl = variantObj.optString("downloadUrl", "").trim()
            val sizeBytes = variantObj.optLong("sizeBytes", 0L)
            if (name.isEmpty() || sizeBytes <= 0L || !isTrustedGitHubDownloadHost(downloadUrl)) {
                return null
            }

            val tag = root.optString("tag", "main-head").trim().ifEmpty { "main-head" }
            val branch = root.optString("branch", "main").trim().ifEmpty { "main" }
            val commitEpochMs = root.optLong("commitEpochMs", 0L)
            val publishedAtMs = root.optLong("publishedAtMs", 0L).takeIf { it > 0L } ?: commitEpochMs
            val runId = root.optLong("runId", 0L)
            val versionName = root.optString("versionName", "").trim()
            val versionCode = root.optInt("versionCode", 0)
            val sha256 = normalizeSha256(variantObj.optString("sha256", ""))
            val artifactId = variantObj.optLong("assetId", 0L).takeIf { it > 0L }
                ?: runId.takeIf { it > 0L }
                ?: commitEpochMs.coerceAtLeast(1L)

            return RemoteBuildArtifact(
                channel = AppUpdateChannel.REMOTE_HEAD,
                artifactId = artifactId,
                tagName = tag,
                versionName = versionName,
                versionCode = versionCode,
                name = name,
                sizeInBytes = sizeBytes,
                downloadUrl = downloadUrl,
                sha256 = sha256,
                createdAtMs = publishedAtMs,
                commitEpochMs = commitEpochMs,
                runId = runId,
                headSha = gitSha,
                headBranch = branch,
                isDebugVariant = preferDebugVariant,
                expired = false
            )
        }

        /**
         * Streams an APK payload into [destApkFile] safely via a `.tmp` staging file while
         * verifying `PK\x03\x04` magic bytes, size bounds, and optional SHA-256 digest.
         */
        fun streamAndVerifyApk(
            inputStream: InputStream,
            destApkFile: File,
            expectedSizeBytes: Long,
            expectedSha256: String,
            minApkBytes: Long = MIN_VALID_APK_BYTES,
            maxApkBytes: Long = MAX_VALID_APK_BYTES,
            onProgress: (bytesRead: Long, totalBytes: Long) -> Unit = { _, _ -> },
            checkActive: () -> Unit = {}
        ): Result<ApkDownloadVerification> {
            val parentDir = destApkFile.parentFile
                ?: return Result.failure(IllegalArgumentException("Invalid destination path"))
            if (!parentDir.exists()) {
                parentDir.mkdirs()
            }
            val tempApk = File(parentDir, "${destApkFile.name}.tmp")
            val normalizedExpectedSha = normalizeSha256(expectedSha256)
            var outputStream: FileOutputStream? = null
            var success = false
            try {
                if (tempApk.exists()) {
                    tempApk.delete()
                }
                outputStream = FileOutputStream(tempApk)
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(65_536)
                val headerBytes = ByteArray(4)
                var headerCaptured = 0
                var totalRead = 0L
                var lastReported = 0L

                onProgress(0L, expectedSizeBytes)
                while (true) {
                    checkActive()
                    val read = inputStream.read(buffer)
                    if (read == -1) break
                    totalRead += read
                    if (totalRead > maxApkBytes) {
                        throw DownloadHttpException(
                            0,
                            AppUpdateCheckErrorKind.INTEGRITY_CHECK_FAILED,
                            "ダウンロード APK のサイズが上限 (${maxApkBytes / (1024 * 1024)}MB) を超えています"
                        )
                    }
                    if (headerCaptured < 4) {
                        val copyLen = minOf(read, 4 - headerCaptured)
                        System.arraycopy(buffer, 0, headerBytes, headerCaptured, copyLen)
                        headerCaptured += copyLen
                    }
                    digest.update(buffer, 0, read)
                    outputStream.write(buffer, 0, read)
                    if (totalRead - lastReported >= 131_072L) {
                        lastReported = totalRead
                        onProgress(totalRead, expectedSizeBytes)
                    }
                }
                outputStream.flush()
                outputStream.close()
                outputStream = null
                onProgress(totalRead, expectedSizeBytes)

                if (totalRead < minApkBytes) {
                    throw DownloadHttpException(
                        0,
                        AppUpdateCheckErrorKind.INTEGRITY_CHECK_FAILED,
                        "ダウンロードされた APK のサイズが小さすぎます (${totalRead} bytes)"
                    )
                }
                if (headerCaptured < 4 ||
                    headerBytes[0] != 0x50.toByte() ||
                    headerBytes[1] != 0x4B.toByte() ||
                    headerBytes[2] != 0x03.toByte() ||
                    headerBytes[3] != 0x04.toByte()
                ) {
                    throw DownloadHttpException(
                        0,
                        AppUpdateCheckErrorKind.INTEGRITY_CHECK_FAILED,
                        "ダウンロードされたファイルが有効な APK アーカイブではありません"
                    )
                }

                val actualSha256 = toHexLower(digest.digest())
                if (normalizedExpectedSha.isNotEmpty() &&
                    !actualSha256.equals(normalizedExpectedSha, ignoreCase = true)
                ) {
                    throw DownloadHttpException(
                        0,
                        AppUpdateCheckErrorKind.INTEGRITY_CHECK_FAILED,
                        "APK の SHA-256 検証に失敗しました（破損または不整合）"
                    )
                }

                if (destApkFile.exists() && !destApkFile.delete()) {
                    throw IOException("既存のステージ済み APK を削除できません: ${destApkFile.name}")
                }
                if (!tempApk.renameTo(destApkFile)) {
                    throw IOException("APK ファイルの保存を完了できません: ${destApkFile.name}")
                }
                success = true
                return Result.success(
                    ApkDownloadVerification(
                        file = destApkFile,
                        sizeInBytes = totalRead,
                        sha256 = actualSha256
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "APK stream/verification failed: ${e.message}")
                return Result.failure(e)
            } finally {
                try {
                    outputStream?.close()
                } catch (_: Exception) {
                }
                if (!success && tempApk.exists()) {
                    tempApk.delete()
                }
            }
        }

        private fun toHexLower(bytes: ByteArray): String {
            val sb = StringBuilder(bytes.size * 2)
            for (b in bytes) {
                sb.append(String.format(Locale.US, "%02x", b.toInt() and 0xFF))
            }
            return sb.toString()
        }
    }

    suspend fun fetchLatestStableRelease(
        owner: String,
        repo: String
    ): FetchResult = withContext(Dispatchers.IO) {
        val urlStr = "https://$API_HOST/repos/$owner/$repo/releases/latest"
        var conn: HttpURLConnection? = null
        try {
            coroutineContext.ensureActive()
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", API_VERSION)
                setRequestProperty("User-Agent", USER_AGENT)
            }
            val code = conn.responseCode
            when (code) {
                HttpURLConnection.HTTP_OK -> {
                    val body = readResponseBody(conn.inputStream)
                    FetchResult.Success(parseStableReleaseJson(body))
                }
                HttpURLConnection.HTTP_NOT_FOUND -> FetchResult.Success(null)
                HttpURLConnection.HTTP_FORBIDDEN -> {
                    val remaining = conn.getHeaderField("X-RateLimit-Remaining")
                    if (remaining == "0") {
                        FetchResult.Error(
                            errorKind = AppUpdateCheckErrorKind.RATE_LIMITED,
                            message = "GitHub API のレート制限に達しました (HTTP 403)",
                            httpStatusCode = code
                        )
                    } else {
                        FetchResult.Error(
                            errorKind = AppUpdateCheckErrorKind.NETWORK_ERROR,
                            message = "GitHub Releases へアクセスできません (HTTP 403)",
                            httpStatusCode = code
                        )
                    }
                }
                429 -> FetchResult.Error(
                    errorKind = AppUpdateCheckErrorKind.RATE_LIMITED,
                    message = "GitHub API のレート制限に達しました (HTTP 429)",
                    httpStatusCode = code
                )
                else -> FetchResult.Error(
                    errorKind = AppUpdateCheckErrorKind.NETWORK_ERROR,
                    message = "GitHub Releases 確認エラー (HTTP $code)",
                    httpStatusCode = code
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Stable release check failed safely: ${e.message}")
            FetchResult.Error(
                errorKind = AppUpdateCheckErrorKind.NETWORK_ERROR,
                message = "GitHub へ接続できません (${e.message ?: e.javaClass.simpleName})"
            )
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    suspend fun fetchLatestRemoteHead(
        owner: String,
        repo: String,
        headTag: String,
        metadataAsset: String,
        preferDebugVariant: Boolean
    ): FetchResult = withContext(Dispatchers.IO) {
        val encodedTag = URLEncoder.encode(headTag, "UTF-8").replace("+", "%20")
        val encodedAsset = URLEncoder.encode(metadataAsset, "UTF-8").replace("+", "%20")
        val initialUrl = "https://$WEB_HOST/$owner/$repo/releases/download/$encodedTag/$encodedAsset"
        var conn: HttpURLConnection? = null
        try {
            val resolved = openFollowingHttpsRedirects(initialUrl, readTimeoutMs = READ_TIMEOUT_MS)
            conn = resolved.connection
            when (val code = resolved.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    val body = readResponseBody(conn.inputStream)
                    FetchResult.Success(parseHeadMetadataJson(body, preferDebugVariant))
                }
                HttpURLConnection.HTTP_NOT_FOUND -> FetchResult.Success(null)
                429 -> FetchResult.Error(
                    errorKind = AppUpdateCheckErrorKind.RATE_LIMITED,
                    message = "GitHub へのアクセスが一時的に制限されています (HTTP 429)",
                    httpStatusCode = code
                )
                else -> FetchResult.Error(
                    errorKind = AppUpdateCheckErrorKind.NETWORK_ERROR,
                    message = "リモートヘッド情報の取得に失敗しました (HTTP $code)",
                    httpStatusCode = code
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Remote HEAD check failed safely: ${e.message}")
            FetchResult.Error(
                errorKind = AppUpdateCheckErrorKind.NETWORK_ERROR,
                message = "GitHub へ接続できません (${e.message ?: e.javaClass.simpleName})"
            )
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    suspend fun downloadApkFile(
        downloadUrl: String,
        destinationApk: File,
        expectedSizeBytes: Long,
        expectedSha256: String,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit
    ): Result<ApkDownloadVerification> = withContext(Dispatchers.IO) {
        if (!isTrustedGitHubDownloadHost(downloadUrl)) {
            return@withContext Result.failure(
                IllegalArgumentException("Refusing untrusted download URL: $downloadUrl")
            )
        }

        var conn: HttpURLConnection? = null
        var inputStream: InputStream? = null
        try {
            val resolved = openFollowingHttpsRedirects(downloadUrl, readTimeoutMs = DOWNLOAD_READ_TIMEOUT_MS)
            conn = resolved.connection
            val code = resolved.responseCode
            when {
                code == HttpURLConnection.HTTP_OK -> Unit
                code == 429 || (code == HttpURLConnection.HTTP_FORBIDDEN &&
                    conn.getHeaderField("X-RateLimit-Remaining") == "0") -> {
                    throw DownloadHttpException(
                        httpStatusCode = code,
                        errorKind = AppUpdateCheckErrorKind.RATE_LIMITED,
                        message = "GitHub のアクセス制限によりダウンロードできません (HTTP $code)"
                    )
                }
                else -> {
                    throw DownloadHttpException(
                        httpStatusCode = code,
                        errorKind = AppUpdateCheckErrorKind.DOWNLOAD_FAILED,
                        message = "APK のダウンロードに失敗しました (HTTP $code)"
                    )
                }
            }

            val contentLength = conn.contentLengthLong.takeIf { it > 0L } ?: expectedSizeBytes
            inputStream = conn.inputStream
            val ctx = coroutineContext
            streamAndVerifyApk(
                inputStream = inputStream,
                destApkFile = destinationApk,
                expectedSizeBytes = contentLength,
                expectedSha256 = expectedSha256,
                onProgress = onProgress,
                checkActive = { ctx.ensureActive() }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Direct APK download failed: ${e.message}")
            Result.failure(e)
        } finally {
            try {
                inputStream?.close()
            } catch (_: Exception) {
            }
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    private data class ResolvedConnection(
        val connection: HttpURLConnection,
        val responseCode: Int
    )

    private suspend fun openFollowingHttpsRedirects(
        initialUrl: String,
        readTimeoutMs: Int
    ): ResolvedConnection {
        var currentUrl = initialUrl
        var redirectCount = 0
        while (true) {
            coroutineContext.ensureActive()
            val parsedUrl = URL(currentUrl)
            if (!parsedUrl.protocol.equals("https", ignoreCase = true)) {
                throw SecurityException("Refusing non-HTTPS redirect target")
            }
            val conn = (parsedUrl.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = readTimeoutMs
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
            }
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_MOVED_PERM ||
                code == HttpURLConnection.HTTP_MOVED_TEMP ||
                code == HttpURLConnection.HTTP_SEE_OTHER ||
                code == 307 ||
                code == 308
            ) {
                val nextLocation = conn.getHeaderField("Location")
                conn.disconnect()
                if (nextLocation.isNullOrBlank()) {
                    throw IOException("Redirect without Location header (HTTP $code)")
                }
                if (redirectCount >= MAX_REDIRECTS) {
                    throw IOException("Too many redirects while resolving GitHub download")
                }
                currentUrl = URL(parsedUrl, nextLocation).toString()
                redirectCount++
                continue
            }
            return ResolvedConnection(conn, code)
        }
    }

    private fun readResponseBody(stream: InputStream): String {
        val sb = StringBuilder()
        BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { reader ->
            val buf = CharArray(8192)
            while (true) {
                val read = reader.read(buf)
                if (read == -1) break
                sb.append(buf, 0, read)
            }
        }
        return sb.toString()
    }
}
