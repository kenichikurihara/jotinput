package com.example.myasr.context.provisioning.infrastructure

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class ModelDownloader(private val targetDir: File) {
    private val TAG = "ModelDownloader"

    private val BASE_URL = "https://huggingface.co/DeL-TaiseiOzaki/sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01/resolve/main"

    private val MODEL_FILES = listOf(
        "tokens.txt",
        "decoder-epoch-99-avg-1.onnx",
        "joiner-epoch-99-avg-1.onnx",
        "encoder-epoch-99-avg-1.int8.onnx"
    )

    // Approximate total size is ~169MB
    private val ESTIMATED_TOTAL_BYTES = 169L * 1024 * 1024

    suspend fun download(
        onProgress: (currentFile: String, fileIndex: Int, totalFiles: Int, percent: Int) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }

            var totalBytesDownloadedOverall = 0L

            for ((index, fileName) in MODEL_FILES.withIndex()) {
                val destFile = File(targetDir, fileName)
                val tempFile = File(targetDir, "$fileName.tmp")

                if (destFile.exists() && destFile.length() > 0) {
                    totalBytesDownloadedOverall += destFile.length()
                    val pct = ((totalBytesDownloadedOverall.toDouble() / ESTIMATED_TOTAL_BYTES) * 100).toInt().coerceIn(0, 100)
                    onProgress(fileName, index + 1, MODEL_FILES.size, pct)
                    continue
                }

                val fileUrl = "$BASE_URL/$fileName"
                Log.i(TAG, "Downloading $fileName from $fileUrl ...")
                onProgress(fileName, index + 1, MODEL_FILES.size, ((totalBytesDownloadedOverall.toDouble() / ESTIMATED_TOTAL_BYTES) * 100).toInt().coerceIn(0, 100))

                downloadFile(fileUrl, tempFile) { bytesReadInThisFile ->
                    val overall = totalBytesDownloadedOverall + bytesReadInThisFile
                    val pct = ((overall.toDouble() / ESTIMATED_TOTAL_BYTES) * 100).toInt().coerceIn(0, 100)
                    onProgress(fileName, index + 1, MODEL_FILES.size, pct)
                }

                if (tempFile.renameTo(destFile)) {
                    totalBytesDownloadedOverall += destFile.length()
                } else {
                    throw IllegalStateException("Failed to rename temporary file to $fileName")
                }
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Download failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    private fun downloadFile(urlStr: String, destination: File, onFileProgress: (Long) -> Unit) {
        var connection: HttpURLConnection? = null
        var inputStream: InputStream? = null
        var outputStream: FileOutputStream? = null

        try {
            var currentUrl = urlStr
            var redirectCount = 0

            while (redirectCount < 5) {
                val url = URL(currentUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                conn.setRequestProperty("User-Agent", "JotInput-Android/1.0")
                connection = conn

                val responseCode = conn.responseCode
                if (responseCode == HttpURLConnection.HTTP_MOVED_PERM ||
                    responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
                    responseCode == 307 || responseCode == 308) {
                    val newUrl = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (newUrl != null) {
                        currentUrl = newUrl
                        redirectCount++
                        continue
                    }
                }

                if (responseCode != HttpURLConnection.HTTP_OK) {
                    throw IllegalStateException("HTTP error $responseCode: ${conn.responseMessage}")
                }
                break
            }

            val conn = connection ?: throw IllegalStateException("Could not establish connection")
            inputStream = conn.inputStream
            outputStream = FileOutputStream(destination)

            val buffer = ByteArray(8192)
            var bytesRead: Int
            var totalRead = 0L

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                totalRead += bytesRead
                onFileProgress(totalRead)
            }

            outputStream.flush()
        } finally {
            try { outputStream?.close() } catch (_: Exception) {}
            try { inputStream?.close() } catch (_: Exception) {}
            connection?.disconnect()
        }
    }
}
