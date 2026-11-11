package com.example.myasr

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * On-device compiler that builds a zero-heap, memory-mapped binary dictionary
 * in MYASRDIC v1.0 format from parsed token records or Mozc-UT TSV streams.
 */
object MmapDictionaryCompiler {

    private const val TAG = "MmapDictCompiler"

    data class RawToken(
        val surface: String,
        val cost: Int,
        val lid: Int,
        val rid: Int
    )

    /**
     * Compiles a map of reading -> List<RawToken> directly to a MYASRDIC v1.0 binary file.
     * Uses atomic file replacement (temp file -> target).
     */
    fun compileToFile(
        readingMap: Map<String, List<RawToken>>,
        targetFile: File
    ): Result<Int> {
        val tempFile = File(targetFile.parentFile ?: return Result.failure(IllegalStateException("No parent dir")), "${targetFile.name}.tmp")
        return try {
            val sortedReadings = readingMap.keys.sortedWith { a, b ->
                compareUtf8Bytes(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
            }

            val numReadings = sortedReadings.size
            var totalTokens = 0
            for (r in sortedReadings) {
                totalTokens += readingMap[r]?.size ?: 0
            }

            Log.i(TAG, "Compiling $numReadings readings, $totalTokens tokens to ${targetFile.name}...")

            val stringPoolStream = ByteArrayOutputStream(1024 * 1024)
            val stringOffsetMap = HashMap<String, Pair<Int, Int>>(numReadings + totalTokens)

            fun getStringOffset(s: String): Pair<Int, Int> {
                val cached = stringOffsetMap[s]
                if (cached != null) return cached
                val bytes = s.toByteArray(Charsets.UTF_8)
                val offset = stringPoolStream.size()
                stringPoolStream.write(bytes)
                val len = bytes.size
                val pair = Pair(offset, len)
                stringOffsetMap[s] = pair
                return pair
            }

            val readingEntrySize = MmapDictionary.READING_ENTRY_SIZE // 12
            val tokenEntrySize = MmapDictionary.TOKEN_ENTRY_SIZE     // 12
            val headerSize = 64

            val readingTableBytes = ByteBuffer.allocate(numReadings * readingEntrySize).order(ByteOrder.LITTLE_ENDIAN)
            val tokenTableBytes = ByteBuffer.allocate(totalTokens * tokenEntrySize).order(ByteOrder.LITTLE_ENDIAN)

            var tokenCounter = 0

            for (reading in sortedReadings) {
                val (rOffset, rLen) = getStringOffset(reading)
                val tokens = readingMap[reading]?.sortedBy { it.cost } ?: emptyList()
                val tokenCount = tokens.size
                val tokenStartIdx = tokenCounter

                // Reading Record: reading_offset(u32), reading_len(u16), token_count(u16), token_start_idx(u32) = 12 bytes
                readingTableBytes.putInt(rOffset)
                readingTableBytes.putShort(rLen.toShort())
                readingTableBytes.putShort(tokenCount.toShort())
                readingTableBytes.putInt(tokenStartIdx)

                for (t in tokens) {
                    val (sOffset, sLen) = getStringOffset(t.surface)
                    // Token Record: surface_offset(u32), surface_len(u16), cost(i16), lid(u16), rid(u16) = 12 bytes
                    tokenTableBytes.putInt(sOffset)
                    tokenTableBytes.putShort(sLen.toShort())
                    tokenTableBytes.putShort(t.cost.toShort())
                    tokenTableBytes.putShort(t.lid.toShort())
                    tokenTableBytes.putShort(t.rid.toShort())
                    tokenCounter++
                }
            }

            val readingTableOffset = headerSize
            val tokenTableOffset = readingTableOffset + readingTableBytes.position()
            val stringPoolOffset = tokenTableOffset + tokenTableBytes.position()
            val stringPoolBytes = stringPoolStream.size()

            val headerBytes = ByteBuffer.allocate(headerSize).order(ByteOrder.LITTLE_ENDIAN)
            headerBytes.putInt(MmapDictionary.MAGIC_1)
            headerBytes.putInt(MmapDictionary.MAGIC_2)
            headerBytes.putShort(MmapDictionary.VERSION.toShort())
            headerBytes.putShort(0.toShort()) // flags
            headerBytes.putInt(numReadings)
            headerBytes.putInt(totalTokens)
            headerBytes.putInt(readingTableOffset)
            headerBytes.putInt(tokenTableOffset)
            headerBytes.putInt(stringPoolOffset)
            headerBytes.putInt(stringPoolBytes)
            // Remaining 28 bytes zero-padded
            while (headerBytes.position() < headerSize) {
                headerBytes.put(0.toByte())
            }

            FileOutputStream(tempFile).use { fos ->
                fos.write(headerBytes.array())
                fos.write(readingTableBytes.array(), 0, readingTableBytes.position())
                fos.write(tokenTableBytes.array(), 0, tokenTableBytes.position())
                stringPoolStream.writeTo(fos)
                fos.flush()
            }

            if (targetFile.exists()) {
                targetFile.delete()
            }
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }

            Log.i(TAG, "Successfully compiled ${targetFile.name} (${targetFile.length() / 1024} KB)")
            Result.success(totalTokens)
        } catch (e: Exception) {
            Log.e(TAG, "Failed compiling ${targetFile.name}: ${e.message}", e)
            if (tempFile.exists()) tempFile.delete()
            Result.failure(e)
        }
    }

    private fun compareUtf8Bytes(a: ByteArray, b: ByteArray): Int {
        val minLen = minOf(a.size, b.size)
        for (i in 0 until minLen) {
            val b1 = a[i].toInt() and 0xFF
            val b2 = b[i].toInt() and 0xFF
            if (b1 != b2) return b1 - b2
        }
        return a.size - b.size
    }
}
