package com.example.myasr.context.provisioning.infrastructure

import android.content.Context
import android.util.Log
import com.example.myasr.context.reranking.domain.RerankEngineType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * Downloads quantized GGUF on-device SLM models (Qwen3.5-0.8B / 2B, Qwen2.5-0.5B / 1.5B)
 * from Hugging Face into app-specific external storage (`models/`), matching
 * `LlmReranker.getModelFile(...)` and `tools/push_model_to_device.sh`.
 *
 * All supported models are licensed under Apache-2.0.
 */
class SlmModelDownloader(
    private val targetDir: File,
    private val engine: RerankEngineType
) {
    private val TAG = "SlmModelDownloader"

    companion object {
        private const val MIN_VALID_MODEL_BYTES = 10L * 1024L * 1024L

        fun getTargetModelsDir(context: Context): File {
            val external = context.getExternalFilesDir("models")
            val dir = external ?: File(context.filesDir, "models")
            if (!dir.exists()) {
                dir.mkdirs()
            }
            return dir
        }

        fun deleteModel(context: Context, engine: RerankEngineType): Boolean {
            if (engine.modelFileName.isBlank()) return false
            var deleted = false
            val candidateDirs = listOfNotNull(
                File(context.filesDir, "models"),
                context.getExternalFilesDir("models")
            )
            for (dir in candidateDirs) {
                val file = File(dir, engine.modelFileName)
                if (file.exists() && file.delete()) {
                    deleted = true
                }
                val tmp = File(dir, "${engine.modelFileName}.tmp")
                if (tmp.exists()) {
                    tmp.delete()
                }
            }
            return deleted
        }
    }

    suspend fun download(
        onProgress: (statusText: String, percent: Int) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (engine == RerankEngineType.NONE || engine.downloadUrl.isBlank() || engine.modelFileName.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Unsupported SLM engine: ${engine.id}"))
        }

        val destFile = File(targetDir, engine.modelFileName)
        val tempFile = File(targetDir, "${engine.modelFileName}.tmp")

        try {
            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }

            onProgress("接続中…", 0)
            Log.i(TAG, "Downloading ${engine.displayName} from ${engine.downloadUrl} ...")

            downloadFile(engine.downloadUrl, tempFile) { bytesRead, contentLength ->
                val denom = when {
                    contentLength > 0L -> contentLength
                    engine.estimatedSizeBytes > 0L -> engine.estimatedSizeBytes
                    else -> 500L * 1024L * 1024L
                }
                val pct = ((bytesRead.toDouble() / denom) * 100.0).toInt().coerceIn(0, 99)
                val readMb = bytesRead / (1024L * 1024L)
                val totalMb = denom / (1024L * 1024L)
                onProgress("ダウンロード中… (${readMb}MB / ${totalMb}MB)", pct)
            }

            if (tempFile.length() < MIN_VALID_MODEL_BYTES) {
                throw IllegalStateException("Downloaded file is too small (${tempFile.length()} bytes)")
            }

            if (destFile.exists() && !destFile.delete()) {
                throw IllegalStateException("Failed to replace existing model file ${destFile.name}")
            }
            if (!tempFile.renameTo(destFile)) {
                throw IllegalStateException("Failed to finalize model file ${destFile.name}")
            }

            onProgress("完了", 100)
            Log.i(TAG, "Successfully downloaded ${engine.displayName} (${destFile.length()} bytes)")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "SLM download failed for ${engine.displayName}: ${e.message}", e)
            if (tempFile.exists()) {
                tempFile.delete()
            }
            Result.failure(e)
        }
    }

    private suspend fun downloadFile(
        urlStr: String,
        destination: File,
        onProgress: (bytesRead: Long, contentLength: Long) -> Unit
    ) {
        var connection: HttpURLConnection? = null
        var inputStream: InputStream? = null
        var outputStream: FileOutputStream? = null

        try {
            var currentUrl = urlStr
            var redirectCount = 0

            while (redirectCount < 6) {
                coroutineContext.ensureActive()
                val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 20_000
                    readTimeout = 45_000
                    setRequestProperty("User-Agent", "JotInput-Android/1.0")
                }
                connection = conn

                val code = conn.responseCode
                if (code == HttpURLConnection.HTTP_MOVED_PERM ||
                    code == HttpURLConnection.HTTP_MOVED_TEMP ||
                    code == HttpURLConnection.HTTP_SEE_OTHER ||
                    code == 307 ||
                    code == 308
                ) {
                    val nextUrl = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (!nextUrl.isNullOrBlank()) {
                        currentUrl = URL(URL(currentUrl), nextUrl).toString()
                        redirectCount++
                        continue
                    }
                }

                if (code != HttpURLConnection.HTTP_OK) {
                    throw IllegalStateException("HTTP error $code: ${conn.responseMessage}")
                }
                break
            }

            val conn = connection ?: throw IllegalStateException("Could not establish connection")
            val contentLength = conn.contentLengthLong
            inputStream = conn.inputStream
            outputStream = FileOutputStream(destination)

            val buffer = ByteArray(65_536)
            var bytesRead: Int
            var totalRead = 0L
            var lastReportedBytes = 0L

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                coroutineContext.ensureActive()
                outputStream.write(buffer, 0, bytesRead)
                totalRead += bytesRead
                if (totalRead - lastReportedBytes >= 262_144L) { // update every 256 KB
                    lastReportedBytes = totalRead
                    onProgress(totalRead, contentLength)
                }
            }
            outputStream.flush()
            onProgress(totalRead, contentLength)
        } finally {
            try {
                inputStream?.close()
            } catch (_: Exception) {
            }
            try {
                outputStream?.close()
            } catch (_: Exception) {
            }
            connection?.disconnect()
        }
    }
}
