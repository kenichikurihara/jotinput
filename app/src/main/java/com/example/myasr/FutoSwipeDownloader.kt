package com.example.myasr

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
 * Downloads FUTO Swipe neural models and vocabulary to external app storage on-demand.
 * Target path: context.getExternalFilesDir(null)/futo_swipe/
 */
class FutoSwipeDownloader(private val context: Context) {
    private val TAG = "FutoSwipeDownloader"

    private val targetDir = File(context.getExternalFilesDir(null), "futo_swipe")

    private val MODEL_DOWNLOAD_ITEMS = listOf(
        "encoder.onnx" to "https://huggingface.co/telosnex/fonnx/resolve/main/futo_swipe_encoder_honorable_sturgeon.onnx",
        "decoder.onnx" to "https://huggingface.co/telosnex/fonnx/resolve/main/futo_swipe_decoder_magic_macaw.onnx",
        "context_lm.onnx" to "https://huggingface.co/telosnex/fonnx/resolve/main/futo_swipe_context_lm_hungry_jellyfish.onnx",
        "embeddings.onnx" to "https://huggingface.co/telosnex/fonnx/resolve/main/futo_swipe_embeddings_hungry_jellyfish.onnx",
        "vocab.txt" to "https://huggingface.co/futo-org/futo-swipe/resolve/main/hungry_jellyfish/vocab.txt"
    )

    // Total size is ~14.5 MB
    private val ESTIMATED_TOTAL_BYTES = 14500000L

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

                onProgress(fileName, index + 1, MODEL_DOWNLOAD_ITEMS.size, ((totalBytesDownloadedOverall.toDouble() / ESTIMATED_TOTAL_BYTES) * 100).toInt().coerceIn(0, 100))

                downloadFile(fileUrl, tempFile) { bytesReadInThisFile ->
                    val overall = totalBytesDownloadedOverall + bytesReadInThisFile
                    val pct = ((overall.toDouble() / ESTIMATED_TOTAL_BYTES) * 100).toInt().coerceIn(0, 100)
                    onProgress(fileName, index + 1, MODEL_DOWNLOAD_ITEMS.size, pct)
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
            var totalBytesReadInFile = 0L

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                totalBytesReadInFile += bytesRead
                onFileProgress(totalBytesReadInFile)
            }
            outputStream.flush()
        } finally {
            try { outputStream?.close() } catch (ignored: Exception) {}
            try { inputStream?.close() } catch (ignored: Exception) {}
            try { connection?.disconnect() } catch (ignored: Exception) {}
        }
    }
}
