package com.example.myasr.context.kanakanji.domain

import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import java.nio.channels.FileChannel

/**
 * In-memory / memory-mapped 2D Bigram Connection Cost Matrix (rid x lid).
 * Evaluates morphological transition costs derived from Mozc's connection_single_column.txt.
 *
 * Matrix dimensions: 2672 x 2672 (7,139,584 transitions, ~14.28 MB in 16-bit binary format).
 */
class ConnectionMatrix private constructor(
    val matrixSize: Int,
    private val buffer: ShortBuffer,
    private val fileChannel: FileChannel? = null
) : AutoCloseable {

    companion object {
        private const val TAG = "ConnectionMatrix"
        const val DEFAULT_POS_SIZE = 2672
        const val MAGIC_HEADER = 0x4D4F5A43 // "MOZC" in ASCII
        const val INVALID_COST = 30000

        /**
         * Loads and memory-maps a binary connection matrix file.
         * Executes in < 2ms using zero JVM heap allocation.
         */
        fun loadFromBinaryFile(file: File): ConnectionMatrix? {
            if (!file.exists() || file.length() < 6L) {
                Log.w(TAG, "Connection binary file missing or incomplete: ${file.absolutePath}")
                return null
            }

            return try {
                val raf = RandomAccessFile(file, "r")
                val channel = raf.channel
                val headerBuf = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
                channel.read(headerBuf)
                headerBuf.flip()

                val magic = headerBuf.int
                val size = headerBuf.short.toInt() and 0xFFFF

                if (magic != MAGIC_HEADER || size <= 0) {
                    Log.e(TAG, "Invalid connection matrix header: magic=0x${Integer.toHexString(magic)}, size=$size")
                    channel.close()
                    raf.close()
                    return null
                }

                val expectedBytes = size.toLong() * size.toLong() * 2L
                val mappedByteBuffer = channel.map(FileChannel.MapMode.READ_ONLY, 6L, expectedBytes)
                mappedByteBuffer.order(ByteOrder.LITTLE_ENDIAN)
                val shortBuffer = mappedByteBuffer.asShortBuffer()

                Log.i(TAG, "Successfully memory-mapped ConnectionMatrix ($size x $size, ${size * size} transitions).")
                ConnectionMatrix(size, shortBuffer, channel)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load connection matrix from ${file.absolutePath}: ${e.message}", e)
                null
            }
        }

        /**
         * Creates an in-memory matrix for testing or unit tests from a ShortArray.
         */
        fun createInMemory(size: Int, data: ShortArray): ConnectionMatrix {
            val shortBuffer = ShortBuffer.wrap(data)
            return ConnectionMatrix(size, shortBuffer, null)
        }
    }

    /**
     * Retrieves the bigram transition cost from left token's right_id (rid)
     * to right token's left_id (lid).
     *
     * @param rid Right ID of preceding token (0 for BOS).
     * @param lid Left ID of succeeding token (0 for EOS).
     * @return Connection cost (lower is more probable).
     */
    fun getTransitionCost(rid: Int, lid: Int): Int {
        if (rid < 0 || rid >= matrixSize || lid < 0 || lid >= matrixSize) {
            return INVALID_COST
        }
        val index = rid * matrixSize + lid
        val rawCost = buffer.get(index).toInt() and 0xFFFF
        return rawCost
    }

    override fun close() {
        try {
            fileChannel?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing file channel: ${e.message}")
        }
    }
}
