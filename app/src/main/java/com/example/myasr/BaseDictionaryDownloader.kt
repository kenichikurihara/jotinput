package com.example.myasr

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads the Mozc Base Dictionary (~51,000 core words, ~2.0 MB)
 * directly to device storage so that the APK remains lightweight.
 */
class BaseDictionaryDownloader(private val context: Context) {

    companion object {
        private const val TAG = "BaseDictDownloader"
        const val BASE_DICT_URL =
            "https://gist.githubusercontent.com/kenichikurihara/39665c6e7ba6e63d6fe86c4c4b1bbd42/raw/base_dictionary.tsv"
    }

    suspend fun download(
        customUrl: String? = null,
        onProgress: (percent: Int, statusText: String) -> Unit
    ): Result<Int> = withContext(Dispatchers.IO) {
        val targetFile = KanaKanjiEngine.getInstance(context).getBaseDictionaryFile()
        val parentDir = targetFile.parentFile ?: context.filesDir
        if (!parentDir.exists()) {
            parentDir.mkdirs()
        }
        val tempFile = File(parentDir, "base_dictionary.tsv.tmp")
        val urlStr = customUrl ?: BASE_DICT_URL

        var connection: HttpURLConnection? = null
        var inputStream: InputStream? = null
        var outputStream: FileOutputStream? = null

        try {
            Log.i(TAG, "Starting base dictionary download from $urlStr to ${targetFile.absolutePath}")
            onProgress(5, "基本辞書サーバーへ接続中...")

            var currentUrl = urlStr
            var redirectCount = 0
            while (redirectCount < 5) {
                val url = URL(currentUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                conn.setRequestProperty("User-Agent", "JotInput-Android/1.0")

                val status = conn.responseCode
                if (status in 300..399) {
                    val redirectUrl = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (redirectUrl != null) {
                        currentUrl = redirectUrl
                        redirectCount++
                        continue
                    }
                }
                connection = conn
                break
            }

            val conn = connection ?: throw IllegalStateException("Could not establish connection to $urlStr")
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("HTTP Error ${conn.responseCode}: ${conn.responseMessage}")
            }

            val contentLength = conn.contentLengthLong
            val totalBytesExpected = if (contentLength > 0) contentLength else 5360000L

            inputStream = conn.inputStream
            outputStream = FileOutputStream(tempFile)

            val buffer = ByteArray(16384)
            var bytesRead: Int
            var totalBytesRead = 0L

            onProgress(15, "基本辞書データを受信中...")

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                totalBytesRead += bytesRead

                val pct = ((totalBytesRead.toDouble() / totalBytesExpected) * 75).toInt() + 15
                onProgress(pct.coerceIn(15, 90), "受信中: ${totalBytesRead / 1024} KB...")
            }

            outputStream.flush()
            outputStream.close()
            outputStream = null
            inputStream.close()
            inputStream = null
            conn.disconnect()

            onProgress(92, "辞書整合性を確認中...")

            // Count words in downloaded file
            var wordCount = 0
            tempFile.bufferedReader(Charsets.UTF_8).use { reader ->
                while (reader.readLine() != null) {
                    wordCount++
                }
            }

            if (wordCount < 100) {
                tempFile.delete()
                throw IllegalStateException("Downloaded dictionary is empty or corrupted ($wordCount lines)")
            }

            if (targetFile.exists()) {
                targetFile.delete()
            }
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }

            onProgress(97, "変換エンジンへ登録中...")
            KanaKanjiEngine.getInstance(context).reloadDictionaries()

            onProgress(100, "基本辞書完了 (${wordCount}語)")
            Log.i(TAG, "Base dictionary successfully installed: $wordCount words (${targetFile.length()} bytes)")
            Result.success(wordCount)
        } catch (e: Exception) {
            Log.e(TAG, "Base dictionary download failed: ${e.message}", e)
            if (tempFile.exists()) {
                tempFile.delete()
            }
            Result.failure(e)
        } finally {
            try {
                outputStream?.close()
                inputStream?.close()
                connection?.disconnect()
            } catch (_: Exception) {}
        }
    }
}
