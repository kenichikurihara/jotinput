package com.example.myasr.context.provisioning.infrastructure

import android.util.Log
import com.example.myasr.context.provisioning.domain.AppUpdateCheckErrorKind
import com.example.myasr.context.provisioning.domain.RemoteBuildArtifact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

/**
 * Infrastructure client for querying GitHub Actions artifacts and safely downloading +
 * extracting `app-debug.apk` in the background.
 *
 * Security & fail-safe guarantees:
 * - Only sends `Authorization: Bearer <token>` to `https://api.github.com`.
 * - Manually resolves HTTP 301/302/303/307/308 redirects so the Authorization header is
 *   stripped before contacting Azure Blob Storage SAS download URLs.
 * - Rejects non-HTTPS download or redirect targets.
 * - Zip-Slip immune APK extraction (writes exclusively to fixed `destApkFile.tmp`, validates
 *   `PK\x03\x04` magic header and byte bounds before atomic rename).
 */
class GitHubArtifactUpdateClient {

    sealed class FetchResult {
        data class Success(val artifact: RemoteBuildArtifact?) : FetchResult()
        data class Error(
            val errorKind: AppUpdateCheckErrorKind,
            val message: String,
            val httpStatusCode: Int? = null
        ) : FetchResult()
    }

    class DownloadHttpException(
        val httpStatusCode: Int,
        val errorKind: AppUpdateCheckErrorKind,
        message: String
    ) : IOException(message)

    companion object {
        private const val TAG = "GitHubArtifactClient"
        private const val API_HOST = "api.github.com"
        private const val API_VERSION = "2022-11-28"
        private const val MAX_REDIRECTS = 5
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000

        const val MIN_VALID_APK_BYTES = 1L * 1024L * 1024L
        const val MAX_VALID_APK_BYTES = 220L * 1024L * 1024L

        fun isGitHubApiHost(urlStr: String): Boolean {
            return try {
                val url = URL(urlStr)
                url.protocol.equals("https", ignoreCase = true) &&
                    url.host.equals(API_HOST, ignoreCase = true)
            } catch (_: Exception) {
                false
            }
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

        fun parseArtifactsJson(jsonText: String): List<RemoteBuildArtifact> {
            val root = JSONObject(jsonText)
            val array = root.optJSONArray("artifacts") ?: return emptyList()
            val results = ArrayList<RemoteBuildArtifact>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val workflowRun = obj.optJSONObject("workflow_run")
                results.add(
                    RemoteBuildArtifact(
                        artifactId = obj.optLong("id", 0L),
                        name = obj.optString("name", ""),
                        sizeInBytes = obj.optLong("size_in_bytes", 0L),
                        archiveDownloadUrl = obj.optString("archive_download_url", ""),
                        createdAtMs = parseIso8601Millis(obj.optString("created_at", "")),
                        expiresAtMs = parseIso8601Millis(obj.optString("expires_at", "")),
                        expired = obj.optBoolean("expired", false),
                        runId = workflowRun?.optLong("id", 0L) ?: 0L,
                        headSha = workflowRun?.optString("head_sha", "") ?: "",
                        headBranch = workflowRun?.optString("head_branch", "") ?: ""
                    )
                )
            }
            return results
        }

        fun selectLatestValidArtifact(
            artifacts: List<RemoteBuildArtifact>,
            artifactName: String,
            targetBranch: String,
            nowMs: Long = 0L
        ): RemoteBuildArtifact? {
            return artifacts
                .asSequence()
                .filter { !it.expired }
                .filter { it.artifactId > 0L }
                .filter { it.name == artifactName }
                .filter { it.headBranch == targetBranch }
                .filter { isGitHubApiHost(it.archiveDownloadUrl) }
                .filter { nowMs <= 0L || it.expiresAtMs <= 0L || it.expiresAtMs > nowMs }
                .maxWithOrNull(compareBy<RemoteBuildArtifact>({ it.createdAtMs }, { it.artifactId }))
        }

        /**
         * Safely extracts the `.apk` entry from a GitHub Actions artifact ZIP into [destApkFile].
         * Immune to Zip-Slip because entry names are never used for output path resolution.
         */
        fun extractApkFromZip(
            zipFile: File,
            destApkFile: File,
            minApkBytes: Long = MIN_VALID_APK_BYTES,
            maxApkBytes: Long = MAX_VALID_APK_BYTES
        ): Result<File> {
            if (!zipFile.exists() || zipFile.length() <= 0L) {
                return Result.failure(IllegalStateException("ZIP file missing or empty: ${zipFile.name}"))
            }
            val parentDir = destApkFile.parentFile
                ?: return Result.failure(IllegalArgumentException("Invalid destination path"))
            if (!parentDir.exists()) {
                parentDir.mkdirs()
            }
            val tempApk = File(parentDir, "${destApkFile.name}.tmp")
            var extractedSuccess = false
            try {
                if (tempApk.exists()) {
                    tempApk.delete()
                }
                var foundApkEntry = false
                ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
                    while (true) {
                        val entry = zis.nextEntry ?: break
                        try {
                            if (entry.isDirectory) continue
                            val entryName = entry.name.substringAfterLast('/')
                            if (!entryName.endsWith(".apk", ignoreCase = true)) {
                                continue
                            }
                            foundApkEntry = true
                            var totalWritten = 0L
                            val headerBytes = ByteArray(4)
                            var headerCaptured = 0

                            FileOutputStream(tempApk).use { out ->
                                val buffer = ByteArray(65_536)
                                while (true) {
                                    val read = zis.read(buffer)
                                    if (read <= 0) break
                                    totalWritten += read
                                    if (totalWritten > maxApkBytes) {
                                        throw IllegalStateException("Extracted APK exceeds size limit ($maxApkBytes bytes)")
                                    }
                                    if (headerCaptured < 4) {
                                        val copyLen = minOf(read, 4 - headerCaptured)
                                        System.arraycopy(buffer, 0, headerBytes, headerCaptured, copyLen)
                                        headerCaptured += copyLen
                                    }
                                    out.write(buffer, 0, read)
                                }
                                out.flush()
                            }

                            if (totalWritten < minApkBytes) {
                                throw IllegalStateException("Extracted APK is smaller than minimum threshold ($totalWritten bytes)")
                            }
                            if (headerCaptured < 4 ||
                                headerBytes[0] != 0x50.toByte() ||
                                headerBytes[1] != 0x4B.toByte() ||
                                headerBytes[2] != 0x03.toByte() ||
                                headerBytes[3] != 0x04.toByte()
                            ) {
                                throw IllegalStateException("Extracted file is not a valid APK/ZIP archive")
                            }
                            break
                        } finally {
                            zis.closeEntry()
                        }
                    }
                }

                if (!foundApkEntry) {
                    throw IllegalStateException("ZIP アーカイブ内に APK ファイルが見つかりません")
                }

                if (destApkFile.exists() && !destApkFile.delete()) {
                    throw IllegalStateException("Failed to overwrite destination APK: ${destApkFile.name}")
                }
                if (!tempApk.renameTo(destApkFile)) {
                    throw IllegalStateException("Failed to finalize APK file: ${destApkFile.name}")
                }
                extractedSuccess = true
                return Result.success(destApkFile)
            } catch (e: Exception) {
                Log.w(TAG, "APK extraction failed: ${e.message}")
                return Result.failure(e)
            } finally {
                if (!extractedSuccess && tempApk.exists()) {
                    tempApk.delete()
                }
            }
        }
    }

    suspend fun fetchLatestArtifact(
        owner: String,
        repo: String,
        artifactName: String,
        targetBranch: String,
        token: String
    ): FetchResult = withContext(Dispatchers.IO) {
        val trimmedToken = token.trim()
        if (trimmedToken.isEmpty()) {
            return@withContext FetchResult.Error(
                errorKind = AppUpdateCheckErrorKind.TOKEN_REQUIRED,
                message = "GitHub トークンが未設定です"
            )
        }

        val encodedName = URLEncoder.encode(artifactName, "UTF-8")
        val urlStr = "https://$API_HOST/repos/$owner/$repo/actions/artifacts?per_page=20&name=$encodedName"
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
                setRequestProperty("Authorization", "Bearer $trimmedToken")
                setRequestProperty("User-Agent", "JotInput-Android/1.0")
            }

            val code = conn.responseCode
            when (code) {
                HttpURLConnection.HTTP_OK -> {
                    val body = readResponseBody(conn.inputStream)
                    val parsed = parseArtifactsJson(body)
                    val selected = selectLatestValidArtifact(
                        artifacts = parsed,
                        artifactName = artifactName,
                        targetBranch = targetBranch,
                        nowMs = System.currentTimeMillis()
                    )
                    FetchResult.Success(selected)
                }
                HttpURLConnection.HTTP_UNAUTHORIZED -> {
                    FetchResult.Error(
                        errorKind = AppUpdateCheckErrorKind.AUTH_FAILED,
                        message = "GitHub トークンが無効または期限切れです (HTTP 401)",
                        httpStatusCode = code
                    )
                }
                HttpURLConnection.HTTP_FORBIDDEN -> {
                    val remaining = conn.getHeaderField("X-RateLimit-Remaining")
                    if (remaining == "0") {
                        FetchResult.Error(
                            errorKind = AppUpdateCheckErrorKind.NETWORK_ERROR,
                            message = "GitHub API レート制限に達しました (HTTP 403)",
                            httpStatusCode = code
                        )
                    } else {
                        FetchResult.Error(
                            errorKind = AppUpdateCheckErrorKind.AUTH_FAILED,
                            message = "GitHub トークン権限 (Actions: Read-only) が不足しています (HTTP 403)",
                            httpStatusCode = code
                        )
                    }
                }
                HttpURLConnection.HTTP_NOT_FOUND -> {
                    FetchResult.Error(
                        errorKind = AppUpdateCheckErrorKind.AUTH_FAILED,
                        message = "リポジトリまたは Actions にアクセスできません (HTTP 404)",
                        httpStatusCode = code
                    )
                }
                else -> {
                    FetchResult.Error(
                        errorKind = AppUpdateCheckErrorKind.NETWORK_ERROR,
                        message = "GitHub API エラー (HTTP $code)",
                        httpStatusCode = code
                    )
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "GitHub artifact check failed safely: ${e.message}")
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

    suspend fun downloadArtifactZip(
        downloadUrl: String,
        token: String,
        destinationZip: File,
        expectedSizeBytes: Long,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val trimmedToken = token.trim()
        if (trimmedToken.isEmpty()) {
            return@withContext Result.failure(
                DownloadHttpException(0, AppUpdateCheckErrorKind.TOKEN_REQUIRED, "GitHub トークンが未設定です")
            )
        }
        if (!isGitHubApiHost(downloadUrl)) {
            return@withContext Result.failure(
                IllegalArgumentException("Refusing non-GitHub-API artifact URL: $downloadUrl")
            )
        }

        val parentDir = destinationZip.parentFile
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs()
        }
        val tempZip = File(parentDir, "${destinationZip.name}.tmp")
        var connection: HttpURLConnection? = null
        var inputStream: InputStream? = null
        var outputStream: FileOutputStream? = null
        var success = false

        try {
            if (tempZip.exists()) {
                tempZip.delete()
            }

            var currentUrl = downloadUrl
            var redirectCount = 0

            while (true) {
                coroutineContext.ensureActive()
                val parsedUrl = URL(currentUrl)
                if (!parsedUrl.protocol.equals("https", ignoreCase = true)) {
                    throw SecurityException("Refusing non-HTTPS redirect target")
                }

                val isApiTarget = isGitHubApiHost(currentUrl)
                val conn = (parsedUrl.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = 45_000
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "JotInput-Android/1.0")
                    if (isApiTarget) {
                        // Never leak GitHub PAT to redirected Azure Blob Storage SAS hosts.
                        setRequestProperty("Authorization", "Bearer $trimmedToken")
                        setRequestProperty("Accept", "application/vnd.github+json")
                        setRequestProperty("X-GitHub-Api-Version", API_VERSION)
                    }
                }
                connection = conn

                val code = conn.responseCode
                if (code == HttpURLConnection.HTTP_MOVED_PERM ||
                    code == HttpURLConnection.HTTP_MOVED_TEMP ||
                    code == HttpURLConnection.HTTP_SEE_OTHER ||
                    code == 307 ||
                    code == 308
                ) {
                    val nextLocation = conn.getHeaderField("Location")
                    conn.disconnect()
                    connection = null
                    if (nextLocation.isNullOrBlank()) {
                        throw IOException("Redirect without Location header (HTTP $code)")
                    }
                    if (redirectCount >= MAX_REDIRECTS) {
                        throw IOException("Too many redirects while downloading artifact")
                    }
                    currentUrl = URL(parsedUrl, nextLocation).toString()
                    redirectCount++
                    continue
                }

                when (code) {
                    HttpURLConnection.HTTP_OK -> break
                    HttpURLConnection.HTTP_UNAUTHORIZED,
                    HttpURLConnection.HTTP_FORBIDDEN,
                    HttpURLConnection.HTTP_NOT_FOUND -> {
                        throw DownloadHttpException(
                            httpStatusCode = code,
                            errorKind = AppUpdateCheckErrorKind.AUTH_FAILED,
                            message = "アーティファクトの取得権限エラー (HTTP $code)"
                        )
                    }
                    else -> {
                        throw DownloadHttpException(
                            httpStatusCode = code,
                            errorKind = AppUpdateCheckErrorKind.DOWNLOAD_FAILED,
                            message = "ダウンロードに失敗しました (HTTP $code)"
                        )
                    }
                }
            }

            val conn = connection ?: throw IOException("Connection missing after redirect resolution")
            val contentLength = conn.contentLengthLong.takeIf { it > 0L } ?: expectedSizeBytes
            inputStream = conn.inputStream
            outputStream = FileOutputStream(tempZip)

            val buffer = ByteArray(65_536)
            var totalRead = 0L
            var lastReported = 0L
            onProgress(0L, contentLength)

            while (true) {
                coroutineContext.ensureActive()
                val read = inputStream.read(buffer)
                if (read == -1) break
                outputStream.write(buffer, 0, read)
                totalRead += read
                if (totalRead - lastReported >= 131_072L) {
                    lastReported = totalRead
                    onProgress(totalRead, contentLength)
                }
            }
            outputStream.flush()
            outputStream.close()
            outputStream = null
            onProgress(totalRead, contentLength)

            if (totalRead <= 0L) {
                throw IOException("Downloaded artifact ZIP is empty")
            }
            if (destinationZip.exists() && !destinationZip.delete()) {
                throw IOException("Could not replace existing ZIP file: ${destinationZip.name}")
            }
            if (!tempZip.renameTo(destinationZip)) {
                throw IOException("Could not finalize ZIP file: ${destinationZip.name}")
            }
            success = true
            Result.success(destinationZip)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Artifact ZIP download failed: ${e.message}")
            Result.failure(e)
        } finally {
            try {
                inputStream?.close()
            } catch (_: Exception) {
            }
            try {
                outputStream?.close()
            } catch (_: Exception) {
            }
            try {
                connection?.disconnect()
            } catch (_: Exception) {
            }
            if (!success && tempZip.exists()) {
                tempZip.delete()
            }
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
