package com.example.myasr.context.provisioning.infrastructure

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads and extracts the NVIDIA Parakeet TDT-CTC 110M INT8 English ASR model
 * (`sherpa-onnx-nemo-parakeet_tdt_ctc_110m-en-36000-int8`) from the official
 * k2-fsa/sherpa-onnx release archive into external app storage.
 *
 * License: CC-BY-4.0 (NVIDIA NeMo) + Apache-2.0 (sherpa-onnx export).
 * Extracted files: `model.int8.onnx` (~126 MB) and `tokens.txt` (~9.8 KB).
 */
class EnglishAsrModelDownloader(private val targetDir: File) {
    private val TAG = "EnglishAsrDownloader"

    companion object {
        const val ARCHIVE_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-parakeet_tdt_ctc_110m-en-36000-int8.tar.bz2"
        const val ESTIMATED_ARCHIVE_BYTES = 115L * 1024 * 1024
        private val REQUIRED_FILES = setOf("model.int8.onnx", "tokens.txt")
    }

    suspend fun download(
        onProgress: (statusText: String, percent: Int) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val archiveFile = File(targetDir, "parakeet_en_110m.tar.bz2.tmp")
        try {
            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }

            onProgress("接続中…", 0)
            Log.i(TAG, "Downloading English ASR model archive from $ARCHIVE_URL ...")

            downloadFile(ARCHIVE_URL, archiveFile) { bytesRead, contentLength ->
                val denom = if (contentLength > 0) contentLength else ESTIMATED_ARCHIVE_BYTES
                val pct = ((bytesRead.toDouble() / denom) * 90.0).toInt().coerceIn(0, 90)
                val mb = bytesRead / (1024 * 1024)
                onProgress("ダウンロード中… (${mb}MB)", pct)
            }

            onProgress("モデルを展開中…", 92)
            extractModelFiles(archiveFile)
            onProgress("完了", 100)

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "English ASR download failed: ${e.message}", e)
            Result.failure(e)
        } finally {
            if (archiveFile.exists()) {
                archiveFile.delete()
            }
        }
    }

    fun deleteModel(): Boolean {
        return try {
            REQUIRED_FILES.forEach { name ->
                val f = File(targetDir, name)
                if (f.exists()) f.delete()
                val tmp = File(targetDir, "$name.tmp")
                if (tmp.exists()) tmp.delete()
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete English ASR model files: ${e.message}")
            false
        }
    }

    private fun extractModelFiles(archiveFile: File) {
        val extracted = mutableSetOf<String>()
        TarArchiveInputStream(
            BZip2CompressorInputStream(
                BufferedInputStream(FileInputStream(archiveFile), 65536)
            )
        ).use { tarIn ->
            var entry = tarIn.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val baseName = File(entry.name).name
                    if (baseName in REQUIRED_FILES) {
                        val tmpDest = File(targetDir, "$baseName.tmp")
                        val finalDest = File(targetDir, baseName)
                        FileOutputStream(tmpDest).use { out ->
                            val buf = ByteArray(65536)
                            var len: Int
                            while (tarIn.read(buf).also { len = it } != -1) {
                                out.write(buf, 0, len)
                            }
                            out.flush()
                        }
                        if (finalDest.exists()) finalDest.delete()
                        if (!tmpDest.renameTo(finalDest)) {
                            throw IllegalStateException("Failed to rename $baseName.tmp")
                        }
                        extracted.add(baseName)
                        Log.i(TAG, "Extracted $baseName (${finalDest.length()} bytes)")
                    }
                }
                entry = tarIn.nextEntry
            }
        }
        val missing = REQUIRED_FILES - extracted
        if (missing.isNotEmpty()) {
            throw IllegalStateException("Missing files in archive: $missing")
        }
    }

    private fun downloadFile(
        urlStr: String,
        destination: File,
        onFileProgress: (bytesRead: Long, contentLength: Long) -> Unit
    ) {
        var connection: HttpURLConnection? = null
        var inputStream: InputStream? = null
        var outputStream: FileOutputStream? = null

        try {
            var currentUrl = urlStr
            var redirectCount = 0

            while (redirectCount < 8) {
                val url = URL(currentUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                conn.setRequestProperty("User-Agent", "JotInput-Android/1.0")
                connection = conn

                val responseCode = conn.responseCode
                if (responseCode == HttpURLConnection.HTTP_MOVED_PERM ||
                    responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
                    responseCode == 303 ||
                    responseCode == 307 ||
                    responseCode == 308
                ) {
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
            val contentLength = conn.contentLengthLong
            inputStream = conn.inputStream
            outputStream = FileOutputStream(destination)

            val buffer = ByteArray(16384)
            var bytesRead: Int
            var totalRead = 0L

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                totalRead += bytesRead
                onFileProgress(totalRead, contentLength)
            }

            outputStream.flush()
        } finally {
            try { outputStream?.close() } catch (_: Exception) {}
            try { inputStream?.close() } catch (_: Exception) {}
            connection?.disconnect()
        }
    }
}
