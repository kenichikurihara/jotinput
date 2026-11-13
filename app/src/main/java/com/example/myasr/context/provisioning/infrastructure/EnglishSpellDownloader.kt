package com.example.myasr.context.provisioning.infrastructure

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads English Spelling & Autocorrect models to external storage on-demand.
 * Target path: context.getExternalFilesDir(null)/english_spelling/
 */
class EnglishSpellDownloader(private val context: Context) {
    private val TAG = "EnglishSpellDownloader"

    private val targetDir = File(context.getExternalFilesDir(null), "english_spelling")

    private val MODEL_DOWNLOAD_ITEMS = listOf(
        "gec-encoder-int8.onnx" to "https://huggingface.co/Loke-60000/gl-keyboard-correction/resolve/main/gec-encoder-int8.onnx",
        "gec-decoder-int8.onnx" to "https://huggingface.co/Loke-60000/gl-keyboard-correction/resolve/main/gec-decoder-int8.onnx",
        "spm_unigram.bin" to "https://huggingface.co/Loke-60000/gl-keyboard-correction/resolve/main/spm.model"
    )

    private val ESTIMATED_TOTAL_BYTES = 27_500_000L

    fun isDownloaded(): Boolean {
        for ((fileName, _) in MODEL_DOWNLOAD_ITEMS) {
            val f = File(targetDir, fileName)
            if (!f.exists() || f.length() == 0L) return false
        }
        return true
    }

    suspend fun download(
        onProgress: (currentFile: String, fileIndex: Int, totalFiles: Int, percent: Int) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }

            var totalBytesDownloadedOverall = 0L

            for ((index, item) in MODEL_DOWNLOAD_ITEMS.withIndex()) {
                val fileName = item.first
                val fileUrl = item.second
                val destFile = File(targetDir, fileName)
                val tempFile = File(targetDir, "$fileName.tmp")

                if (destFile.exists() && destFile.length() > 0) {
                    totalBytesDownloadedOverall += destFile.length()
                    val pct = ((totalBytesDownloadedOverall.toDouble() / ESTIMATED_TOTAL_BYTES) * 100).toInt().coerceIn(0, 100)
                    onProgress(fileName, index + 1, MODEL_DOWNLOAD_ITEMS.size, pct)
                    continue
                }

                Log.i(TAG, "Starting download: $fileName from $fileUrl")
                val url = URL(fileUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 30000
                connection.instanceFollowRedirects = true

                var redirectedConn = connection
                var redirectCount = 0
                while (redirectedConn.responseCode in listOf(HttpURLConnection.HTTP_MOVED_PERM, HttpURLConnection.HTTP_MOVED_TEMP, 307, 308) && redirectCount < 5) {
                    val newUrl = redirectedConn.getHeaderField("Location")
                    redirectedConn.disconnect()
                    val nextUrl = URL(newUrl)
                    redirectedConn = nextUrl.openConnection() as HttpURLConnection
                    redirectedConn.connectTimeout = 15000
                    redirectedConn.readTimeout = 30000
                    redirectCount++
                }

                if (redirectedConn.responseCode != HttpURLConnection.HTTP_OK) {
                    return@withContext Result.failure(
                        Exception("HTTP ${redirectedConn.responseCode}: ${redirectedConn.responseMessage} for $fileName")
                    )
                }

                val totalSize = redirectedConn.contentLengthLong
                var bytesReadTotal = 0L

                var inputStream: InputStream? = null
                var outputStream: FileOutputStream? = null

                try {
                    inputStream = redirectedConn.inputStream
                    outputStream = FileOutputStream(tempFile)

                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var lastNotifiedTime = System.currentTimeMillis()

                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                        bytesReadTotal += bytesRead
                        totalBytesDownloadedOverall += bytesRead

                        val now = System.currentTimeMillis()
                        if (now - lastNotifiedTime > 150 || bytesReadTotal == totalSize) {
                            lastNotifiedTime = now
                            val pct = ((totalBytesDownloadedOverall.toDouble() / ESTIMATED_TOTAL_BYTES) * 100).toInt().coerceIn(0, 100)
                            onProgress(fileName, index + 1, MODEL_DOWNLOAD_ITEMS.size, pct)
                        }
                    }
                    outputStream.flush()
                } finally {
                    outputStream?.close()
                    inputStream?.close()
                    redirectedConn.disconnect()
                }

                if (tempFile.exists()) {
                    if (destFile.exists()) {
                        destFile.delete()
                    }
                    if (!tempFile.renameTo(destFile)) {
                        return@withContext Result.failure(Exception("Failed to rename $tempFile to $destFile"))
                    }
                }
                Log.i(TAG, "Completed download: $fileName (${destFile.length()} bytes)")
            }

            onProgress("Complete", MODEL_DOWNLOAD_ITEMS.size, MODEL_DOWNLOAD_ITEMS.size, 100)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed downloading English spelling models: ${e.message}", e)
            Result.failure(e)
        }
    }
}
