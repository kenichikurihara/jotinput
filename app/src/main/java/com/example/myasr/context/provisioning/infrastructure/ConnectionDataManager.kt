package com.example.myasr.context.provisioning.infrastructure

import com.example.myasr.context.kanakanji.domain.ConnectionMatrix
import com.example.myasr.context.kanakanji.domain.KanaKanjiEngine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Manages downloading, binary indexing, and lifecycle of Mozc's connection_single_column.txt.
 * Converts the 36.4MB text column into a 14.28MB zero-copy memory-mapped binary matrix.
 */
class ConnectionDataManager(private val context: Context) {

    companion object {
        private const val TAG = "ConnectionDataManager"
        const val DOWNLOAD_URL =
            "https://raw.githubusercontent.com/google/mozc/master/src/data/dictionary_oss/connection_single_column.txt"
        const val BINARY_FILENAME = "connection_data.bin"
        const val TEXT_FILENAME = "connection_single_column.txt"
        const val BUNDLED_BINARY_SIZE_BYTES = 14_279_174L

        @Volatile
        private var instance: ConnectionDataManager? = null

        fun getInstance(context: Context): ConnectionDataManager {
            return instance ?: synchronized(this) {
                instance ?: ConnectionDataManager(context.applicationContext).also { instance = it }
            }
        }

        /**
         * Compiles an InputStream of connection_single_column.txt into the compact binary format.
         */
        fun compileStreamToBinary(
            inputStream: InputStream,
            destBinaryFile: File,
            onProgress: ((percent: Int, status: String) -> Unit)? = null
        ) {
            val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8), 65536)
            val outStream = BufferedOutputStream(FileOutputStream(destBinaryFile), 65536)

            try {
                val headerLine = reader.readLine() ?: throw IllegalStateException("Empty connection file")
                val matrixSize = headerLine.trim().toIntOrNull() ?: ConnectionMatrix.DEFAULT_POS_SIZE
                val totalTransitions = matrixSize * matrixSize

                // 1. Write Header: 4 bytes Magic (0x4D4F5A43) + 2 bytes matrixSize (Little Endian)
                val headerBytes = ByteArray(6)
                headerBytes[0] = (ConnectionMatrix.MAGIC_HEADER and 0xFF).toByte()
                headerBytes[1] = ((ConnectionMatrix.MAGIC_HEADER ushr 8) and 0xFF).toByte()
                headerBytes[2] = ((ConnectionMatrix.MAGIC_HEADER ushr 16) and 0xFF).toByte()
                headerBytes[3] = ((ConnectionMatrix.MAGIC_HEADER ushr 24) and 0xFF).toByte()
                headerBytes[4] = (matrixSize and 0xFF).toByte()
                headerBytes[5] = ((matrixSize ushr 8) and 0xFF).toByte()
                outStream.write(headerBytes)

                // 2. Stream transition lines and write 2-byte little endian shorts
                val chunkBuffer = ByteArray(8192)
                var chunkIdx = 0
                var count = 0
                var line: String?

                while (reader.readLine().also { line = it } != null) {
                    val cost = line!!.toIntOrNull() ?: 0
                    val clampedCost = cost.coerceIn(0, 32767)

                    chunkBuffer[chunkIdx++] = (clampedCost and 0xFF).toByte()
                    chunkBuffer[chunkIdx++] = ((clampedCost ushr 8) and 0xFF).toByte()

                    if (chunkIdx >= chunkBuffer.size) {
                        outStream.write(chunkBuffer, 0, chunkIdx)
                        chunkIdx = 0
                    }

                    count++
                    if (count % 200000 == 0) {
                        val pct = 5 + ((count.toDouble() / totalTransitions) * 88).toInt().coerceIn(0, 88)
                        onProgress?.invoke(pct, "接続行列を生成中... ($count / $totalTransitions)")
                    }
                }

                if (chunkIdx > 0) {
                    outStream.write(chunkBuffer, 0, chunkIdx)
                }
                outStream.flush()
            } finally {
                outStream.close()
                reader.close()
            }
        }
    }

    private val mozcDir = File(context.filesDir, "mozc").apply {
        if (!exists()) mkdirs()
    }

    val binaryFile = File(mozcDir, BINARY_FILENAME)
    val textFile = File(mozcDir, TEXT_FILENAME)

    @Volatile
    private var cachedMatrix: ConnectionMatrix? = null

    fun isInstalled(): Boolean {
        if (binaryFile.exists() && binaryFile.length() >= 14_000_000L) {
            return true
        }
        return try {
            context.assets.list("mozc")?.contains(BINARY_FILENAME) == true
        } catch (e: Exception) {
            false
        }
    }

    fun getInstalledSizeBytes(): Long {
        if (binaryFile.exists()) return binaryFile.length()
        return if (isInstalled()) BUNDLED_BINARY_SIZE_BYTES else 0L
    }

    @Synchronized
    fun getConnectionMatrix(): ConnectionMatrix? {
        if (cachedMatrix != null) return cachedMatrix
        if (!binaryFile.exists() || binaryFile.length() < 14_000_000L) {
            // Provision bundled ConnectionMatrix from APK assets on first launch
            try {
                context.assets.open("mozc/$BINARY_FILENAME").use { input ->
                    val tempFile = File(mozcDir, "$BINARY_FILENAME.tmp")
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                    if (!tempFile.renameTo(binaryFile)) {
                        tempFile.copyTo(binaryFile, overwrite = true)
                        tempFile.delete()
                    }
                    Log.i(TAG, "Bundled ConnectionMatrix provisioned from assets to ${binaryFile.absolutePath} (${binaryFile.length()} bytes)")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Bundled ConnectionMatrix asset provision skipped or unavailable: ${e.message}")
            }
        }
        if (binaryFile.exists() && binaryFile.length() >= 14_000_000L) {
            cachedMatrix = ConnectionMatrix.loadFromBinaryFile(binaryFile)
        }
        return cachedMatrix
    }

    /**
     * Downloads connection_single_column.txt from GitHub, streams line-by-line,
     * and compiles directly into binary connection_data.bin.
     */
    suspend fun downloadAndCompile(
        onProgress: (percent: Int, status: String) -> Unit
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Starting download and compilation of Mozc connection matrix from $DOWNLOAD_URL")
            onProgress(2, "接続サーバーに接続中...")

            val url = URL(DOWNLOAD_URL)
            val conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 25000
            conn.readTimeout = 60000
            conn.setRequestProperty("User-Agent", "JotInput-Android/1.0")

            val responseCode = conn.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("HTTP $responseCode: ${conn.responseMessage}")
            }

            val inputStream = conn.inputStream

            val tempBinFile = File(mozcDir, "connection_data.bin.tmp")
            if (tempBinFile.exists()) tempBinFile.delete()

            compileStreamToBinary(inputStream, tempBinFile, onProgress)

            if (!tempBinFile.renameTo(binaryFile)) {
                tempBinFile.copyTo(binaryFile, overwrite = true)
                tempBinFile.delete()
            }

            Log.i(TAG, "Compilation complete: ${binaryFile.length()} bytes written.")
            onProgress(95, "メモリマップ接続行列を展開中...")

            // Release old matrix and reload
            cachedMatrix?.close()
            cachedMatrix = ConnectionMatrix.loadFromBinaryFile(binaryFile)
            KanaKanjiEngine.getInstance(context).setConnectionMatrix(cachedMatrix)

            onProgress(100, "インストール完了 (14.3MB, 714万遷移)")
            Result.success(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed downloading/compiling connection data: ${e.message}", e)
            Result.failure(e)
        }
    }

    @Synchronized
    fun deleteConnectionData(): Boolean {
        cachedMatrix?.close()
        cachedMatrix = null
        KanaKanjiEngine.getInstance(context).setConnectionMatrix(null)
        var success = true
        if (binaryFile.exists()) {
            success = binaryFile.delete() && success
        }
        if (textFile.exists()) {
            success = textFile.delete() && success
        }
        return success
    }
}
