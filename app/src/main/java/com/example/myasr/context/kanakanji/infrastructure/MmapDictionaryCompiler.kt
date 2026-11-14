package com.example.myasr.context.kanakanji.infrastructure

import com.example.myasr.context.kanakanji.domain.MmapDictionary

import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * On-device compiler that builds a zero-heap, memory-mapped binary dictionary
 * in MYASRDIC v1.0 format from parsed token records or Mozc-UT TSV streams.
 */
object MmapDictionaryCompiler {

    private const val TAG = "MmapDictCompiler"
    private const val IO_BUFFER_SIZE = 65536

    data class RawToken(
        val surface: String,
        val cost: Int,
        val lid: Int,
        val rid: Int
    )

    /**
     * Low-memory streaming builder that stores UTF-8 strings in chunked byte arrays
     * and token metadata in flat primitive arrays, avoiding millions of String/ArrayList/HashMap
     * heap allocations when compiling 1M+ word dictionaries (e.g., SudachiDict, JaWiki, Mozc).
     */
    class StreamingBuilder(initialCapacity: Int = 4096) {
        companion object {
            private const val CHUNK_SHIFT = 22 // 4 MB chunks
            private const val CHUNK_SIZE = 1 shl CHUNK_SHIFT
            private const val CHUNK_MASK = CHUNK_SIZE - 1
        }

        private val chunks = ArrayList<ByteArray>()
        private var currentChunk = ByteArray(CHUNK_SIZE)
        private var currentChunkPos = 0

        init {
            chunks.add(currentChunk)
        }

        var tokenCount: Int = 0
            private set

        private var capacity = initialCapacity.coerceAtLeast(64)
        private var rOffsets = IntArray(capacity)
        private var rLengths = ShortArray(capacity)
        private var sOffsets = IntArray(capacity)
        private var sLengths = ShortArray(capacity)
        private var costs = ShortArray(capacity)
        private var lids = ShortArray(capacity)
        private var rids = ShortArray(capacity)

        private var lastReadingBytes: ByteArray? = null
        private var lastReadingOffset: Int = 0

        private fun appendBytes(bytes: ByteArray): Int {
            val len = bytes.size
            if (currentChunkPos + len > CHUNK_SIZE) {
                currentChunk = ByteArray(CHUNK_SIZE)
                chunks.add(currentChunk)
                currentChunkPos = 0
            }
            val globalOffset = ((chunks.size - 1) shl CHUNK_SHIFT) or currentChunkPos
            System.arraycopy(bytes, 0, currentChunk, currentChunkPos, len)
            currentChunkPos += len
            return globalOffset
        }

        private fun ensureCapacity() {
            if (tokenCount < capacity) return
            val newCap = capacity + (capacity shr 1)
            rOffsets = rOffsets.copyOf(newCap)
            rLengths = rLengths.copyOf(newCap)
            sOffsets = sOffsets.copyOf(newCap)
            sLengths = sLengths.copyOf(newCap)
            costs = costs.copyOf(newCap)
            lids = lids.copyOf(newCap)
            rids = rids.copyOf(newCap)
            capacity = newCap
        }

        fun addToken(reading: String, surface: String, cost: Int, lid: Int, rid: Int) {
            if (reading.isEmpty() || surface.isEmpty()) return
            val rBytes = reading.toByteArray(Charsets.UTF_8)
            val sBytes = surface.toByteArray(Charsets.UTF_8)
            if (rBytes.isEmpty() || rBytes.size > 65535 || sBytes.isEmpty() || sBytes.size > 65535) return

            val rOff = if (lastReadingBytes != null && rBytes.contentEquals(lastReadingBytes)) {
                lastReadingOffset
            } else {
                val off = appendBytes(rBytes)
                lastReadingBytes = rBytes
                lastReadingOffset = off
                off
            }
            val sOff = appendBytes(sBytes)

            ensureCapacity()
            val idx = tokenCount++
            rOffsets[idx] = rOff
            rLengths[idx] = rBytes.size.toShort()
            sOffsets[idx] = sOff
            sLengths[idx] = sBytes.size.toShort()
            costs[idx] = cost.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            lids[idx] = lid.toShort()
            rids[idx] = rid.toShort()
        }

        private fun compareReadingAndCost(idxA: Int, idxB: Int): Int {
            val cmp = compareReadings(idxA, idxB)
            if (cmp != 0) return cmp
            return costs[idxA].toInt() - costs[idxB].toInt()
        }

        private fun compareReadings(idxA: Int, idxB: Int): Int {
            val offA = rOffsets[idxA]
            val offB = rOffsets[idxB]
            val lenA = rLengths[idxA].toInt() and 0xFFFF
            val lenB = rLengths[idxB].toInt() and 0xFFFF
            if (offA == offB && lenA == lenB) return 0

            val chunkA = chunks[offA ushr CHUNK_SHIFT]
            val baseA = offA and CHUNK_MASK
            val chunkB = chunks[offB ushr CHUNK_SHIFT]
            val baseB = offB and CHUNK_MASK

            val minLen = minOf(lenA, lenB)
            for (i in 0 until minLen) {
                val b1 = chunkA[baseA + i].toInt() and 0xFF
                val b2 = chunkB[baseB + i].toInt() and 0xFF
                if (b1 != b2) return b1 - b2
            }
            return lenA - lenB
        }

        private fun sortIndices(indices: IntArray, isCancelled: () -> Boolean) {
            val n = indices.size
            if (n <= 1) return
            val maxDepth = 2 * (32 - Integer.numberOfLeadingZeros(n))
            introsort(indices, 0, n - 1, maxDepth, isCancelled)
        }

        private fun introsort(arr: IntArray, lo: Int, hi: Int, depthLimit: Int, isCancelled: () -> Boolean) {
            var low = lo
            var high = hi
            var depth = depthLimit
            while (high - low > 16) {
                if (isCancelled() || Thread.currentThread().isInterrupted) {
                    throw java.util.concurrent.CancellationException("Compilation cancelled")
                }
                if (depth == 0) {
                    heapsort(arr, low, high)
                    return
                }
                depth--
                val mid = (low + high) ushr 1
                // Median of 3 pivot
                if (compareReadingAndCost(arr[mid], arr[low]) < 0) swap(arr, mid, low)
                if (compareReadingAndCost(arr[high], arr[low]) < 0) swap(arr, high, low)
                if (compareReadingAndCost(arr[high], arr[mid]) < 0) swap(arr, high, mid)
                val pivot = arr[mid]

                var i = low
                var j = high
                while (i <= j) {
                    while (compareReadingAndCost(arr[i], pivot) < 0) i++
                    while (compareReadingAndCost(arr[j], pivot) > 0) j--
                    if (i <= j) {
                        swap(arr, i, j)
                        i++
                        j--
                    }
                }
                if (j - low < high - i) {
                    if (low < j) introsort(arr, low, j, depth, isCancelled)
                    low = i
                } else {
                    if (i < high) introsort(arr, i, high, depth, isCancelled)
                    high = j
                }
            }
            insertionSort(arr, low, high)
        }

        private fun insertionSort(arr: IntArray, lo: Int, hi: Int) {
            for (i in (lo + 1)..hi) {
                val key = arr[i]
                var j = i - 1
                while (j >= lo && compareReadingAndCost(arr[j], key) > 0) {
                    arr[j + 1] = arr[j]
                    j--
                }
                arr[j + 1] = key
            }
        }

        private fun heapsort(arr: IntArray, lo: Int, hi: Int) {
            val n = hi - lo + 1
            for (i in n / 2 - 1 downTo 0) {
                siftDown(arr, lo, i, n)
            }
            for (i in n - 1 downTo 1) {
                swap(arr, lo, lo + i)
                siftDown(arr, lo, 0, i)
            }
        }

        private fun siftDown(arr: IntArray, offset: Int, root: Int, size: Int) {
            var r = root
            while (true) {
                var largest = r
                val left = 2 * r + 1
                val right = 2 * r + 2
                if (left < size && compareReadingAndCost(arr[offset + left], arr[offset + largest]) > 0) {
                    largest = left
                }
                if (right < size && compareReadingAndCost(arr[offset + right], arr[offset + largest]) > 0) {
                    largest = right
                }
                if (largest == r) break
                swap(arr, offset + r, offset + largest)
                r = largest
            }
        }

        private fun swap(arr: IntArray, i: Int, j: Int) {
            val t = arr[i]
            arr[i] = arr[j]
            arr[j] = t
        }

        private fun writeFromPool(out: OutputStream, globalOffset: Int, len: Int) {
            val chunk = chunks[globalOffset ushr CHUNK_SHIFT]
            val base = globalOffset and CHUNK_MASK
            out.write(chunk, base, len)
        }

        fun compileToFile(
            targetFile: File,
            isCancelled: () -> Boolean = { false }
        ): Result<Int> {
            val parentDir = targetFile.parentFile ?: return Result.failure(IllegalStateException("No parent dir"))
            if (!parentDir.exists()) parentDir.mkdirs()

            val tempFile = File(parentDir, "${targetFile.name}.tmp")
            val rtTemp = File(parentDir, "${targetFile.name}.rt.tmp")
            val ttTemp = File(parentDir, "${targetFile.name}.tt.tmp")
            val strTemp = File(parentDir, "${targetFile.name}.str.tmp")

            return try {
                if (isCancelled() || Thread.currentThread().isInterrupted) {
                    throw java.util.concurrent.CancellationException("Compilation cancelled")
                }

                lastReadingBytes = null
                val totalRaw = tokenCount
                Log.i(TAG, "Sorting and compiling $totalRaw tokens to ${targetFile.name}...")

                val indices = IntArray(totalRaw) { it }
                sortIndices(indices, isCancelled)

                var numReadings = 0
                var writtenTokens = 0
                var stringPoolOffset = 0

                val recBuf = ByteArray(12)

                BufferedOutputStream(FileOutputStream(rtTemp), IO_BUFFER_SIZE).use { rtOut ->
                    BufferedOutputStream(FileOutputStream(ttTemp), IO_BUFFER_SIZE).use { ttOut ->
                        BufferedOutputStream(FileOutputStream(strTemp), IO_BUFFER_SIZE).use { strOut ->
                            var groupStart = 0
                            while (groupStart < totalRaw) {
                                if ((numReadings and 0xFFF) == 0 && (isCancelled() || Thread.currentThread().isInterrupted)) {
                                    throw java.util.concurrent.CancellationException("Compilation cancelled")
                                }
                                val firstIdx = indices[groupStart]
                                var groupEnd = groupStart + 1
                                while (groupEnd < totalRaw && compareReadings(firstIdx, indices[groupEnd]) == 0) {
                                    groupEnd++
                                }

                                val rOff = rOffsets[firstIdx]
                                val rLen = rLengths[firstIdx].toInt() and 0xFFFF
                                val rPoolOff = stringPoolOffset
                                writeFromPool(strOut, rOff, rLen)
                                stringPoolOffset += rLen

                                val groupCount = minOf(groupEnd - groupStart, 65535)
                                val tokenStartIdx = writtenTokens

                                putIntLE(recBuf, 0, rPoolOff)
                                putShortLE(recBuf, 4, rLen)
                                putShortLE(recBuf, 6, groupCount)
                                putIntLE(recBuf, 8, tokenStartIdx)
                                rtOut.write(recBuf, 0, 12)
                                numReadings++

                                for (k in groupStart until (groupStart + groupCount)) {
                                    val tokIdx = indices[k]
                                    val sOff = sOffsets[tokIdx]
                                    val sLen = sLengths[tokIdx].toInt() and 0xFFFF
                                    val sPoolOff = stringPoolOffset
                                    writeFromPool(strOut, sOff, sLen)
                                    stringPoolOffset += sLen

                                    putIntLE(recBuf, 0, sPoolOff)
                                    putShortLE(recBuf, 4, sLen)
                                    putShortLE(recBuf, 6, costs[tokIdx].toInt())
                                    putShortLE(recBuf, 8, lids[tokIdx].toInt())
                                    putShortLE(recBuf, 10, rids[tokIdx].toInt())
                                    ttOut.write(recBuf, 0, 12)
                                    writtenTokens++
                                }

                                groupStart = groupEnd
                            }
                            strOut.flush()
                        }
                        ttOut.flush()
                    }
                    rtOut.flush()
                }

                assembleBinaryFile(
                    tempFile = tempFile,
                    targetFile = targetFile,
                    rtTemp = rtTemp,
                    ttTemp = ttTemp,
                    strTemp = strTemp,
                    numReadings = numReadings,
                    totalTokens = writtenTokens,
                    stringPoolBytes = stringPoolOffset,
                    isCancelled = isCancelled
                )

                Log.i(TAG, "Successfully compiled ${targetFile.name}: $numReadings readings, $writtenTokens tokens (${targetFile.length() / 1024} KB)")
                Result.success(writtenTokens)
            } catch (e: Exception) {
                Log.e(TAG, "Failed compiling ${targetFile.name}: ${e.message}", e)
                tempFile.delete()
                Result.failure(e)
            } finally {
                rtTemp.delete()
                ttTemp.delete()
                strTemp.delete()
            }
        }
    }

    /**
     * Compiles a map of reading -> List<RawToken> directly to a MYASRDIC v1.0 binary file.
     * Uses streaming temp files and atomic file replacement (temp file -> target).
     */
    fun compileToFile(
        readingMap: Map<String, List<RawToken>>,
        targetFile: File,
        isCancelled: () -> Boolean = { false }
    ): Result<Int> {
        val parentDir = targetFile.parentFile ?: return Result.failure(IllegalStateException("No parent dir"))
        if (!parentDir.exists()) parentDir.mkdirs()

        val tempFile = File(parentDir, "${targetFile.name}.tmp")
        val rtTemp = File(parentDir, "${targetFile.name}.rt.tmp")
        val ttTemp = File(parentDir, "${targetFile.name}.tt.tmp")
        val strTemp = File(parentDir, "${targetFile.name}.str.tmp")

        return try {
            if (isCancelled() || Thread.currentThread().isInterrupted) {
                throw java.util.concurrent.CancellationException("Compilation cancelled")
            }
            val sortedReadings = readingMap.keys.sortedWith { a, b -> compareUtf8Strings(a, b) }
            val numReadings = sortedReadings.size
            var totalTokens = 0
            var stringPoolOffset = 0
            val recBuf = ByteArray(12)

            BufferedOutputStream(FileOutputStream(rtTemp), IO_BUFFER_SIZE).use { rtOut ->
                BufferedOutputStream(FileOutputStream(ttTemp), IO_BUFFER_SIZE).use { ttOut ->
                    BufferedOutputStream(FileOutputStream(strTemp), IO_BUFFER_SIZE).use { strOut ->
                        for ((idx, reading) in sortedReadings.withIndex()) {
                            if ((idx and 0xFFF) == 0 && (isCancelled() || Thread.currentThread().isInterrupted)) {
                                throw java.util.concurrent.CancellationException("Compilation cancelled")
                            }
                            val rBytes = reading.toByteArray(Charsets.UTF_8)
                            val rOffset = stringPoolOffset
                            strOut.write(rBytes)
                            stringPoolOffset += rBytes.size

                            val tokens = readingMap[reading]?.sortedBy { it.cost } ?: emptyList()
                            val tokenCount = minOf(tokens.size, 65535)
                            val tokenStartIdx = totalTokens

                            putIntLE(recBuf, 0, rOffset)
                            putShortLE(recBuf, 4, rBytes.size)
                            putShortLE(recBuf, 6, tokenCount)
                            putIntLE(recBuf, 8, tokenStartIdx)
                            rtOut.write(recBuf, 0, 12)

                            for (i in 0 until tokenCount) {
                                val t = tokens[i]
                                val sBytes = t.surface.toByteArray(Charsets.UTF_8)
                                val sOffset = stringPoolOffset
                                strOut.write(sBytes)
                                stringPoolOffset += sBytes.size

                                putIntLE(recBuf, 0, sOffset)
                                putShortLE(recBuf, 4, sBytes.size)
                                putShortLE(recBuf, 6, t.cost)
                                putShortLE(recBuf, 8, t.lid)
                                putShortLE(recBuf, 10, t.rid)
                                ttOut.write(recBuf, 0, 12)
                                totalTokens++
                            }
                        }
                        strOut.flush()
                    }
                    ttOut.flush()
                }
                rtOut.flush()
            }

            assembleBinaryFile(
                tempFile = tempFile,
                targetFile = targetFile,
                rtTemp = rtTemp,
                ttTemp = ttTemp,
                strTemp = strTemp,
                numReadings = numReadings,
                totalTokens = totalTokens,
                stringPoolBytes = stringPoolOffset,
                isCancelled = isCancelled
            )

            Log.i(TAG, "Successfully compiled ${targetFile.name} (${targetFile.length() / 1024} KB)")
            Result.success(totalTokens)
        } catch (e: Exception) {
            Log.e(TAG, "Failed compiling ${targetFile.name}: ${e.message}", e)
            tempFile.delete()
            Result.failure(e)
        } finally {
            rtTemp.delete()
            ttTemp.delete()
            strTemp.delete()
        }
    }

    private fun assembleBinaryFile(
        tempFile: File,
        targetFile: File,
        rtTemp: File,
        ttTemp: File,
        strTemp: File,
        numReadings: Int,
        totalTokens: Int,
        stringPoolBytes: Int,
        isCancelled: () -> Boolean
    ) {
        if (isCancelled() || Thread.currentThread().isInterrupted) {
            throw java.util.concurrent.CancellationException("Compilation cancelled")
        }
        val headerSize = 64
        val readingTableOffset = headerSize
        val tokenTableOffset = readingTableOffset + numReadings * MmapDictionary.READING_ENTRY_SIZE
        val stringPoolOffset = tokenTableOffset + totalTokens * MmapDictionary.TOKEN_ENTRY_SIZE

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
        while (headerBytes.position() < headerSize) {
            headerBytes.put(0.toByte())
        }

        val copyBuf = ByteArray(IO_BUFFER_SIZE)
        BufferedOutputStream(FileOutputStream(tempFile), IO_BUFFER_SIZE).use { fos ->
            fos.write(headerBytes.array())
            copyFileToStream(rtTemp, fos, copyBuf)
            copyFileToStream(ttTemp, fos, copyBuf)
            copyFileToStream(strTemp, fos, copyBuf)
            fos.flush()
        }

        if (targetFile.exists()) {
            targetFile.delete()
        }
        if (!tempFile.renameTo(targetFile)) {
            tempFile.copyTo(targetFile, overwrite = true)
            tempFile.delete()
        }
    }

    private fun copyFileToStream(src: File, dst: OutputStream, buf: ByteArray) {
        if (!src.exists() || src.length() == 0L) return
        FileInputStream(src).use { fis ->
            while (true) {
                val n = fis.read(buf)
                if (n <= 0) break
                dst.write(buf, 0, n)
            }
        }
    }

    private fun putIntLE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        buf[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        buf[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    private fun putShortLE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun compareUtf8Strings(a: String, b: String): Int {
        var i = 0
        var j = 0
        val lenA = a.length
        val lenB = b.length
        while (i < lenA && j < lenB) {
            val cpA = Character.codePointAt(a, i)
            val cpB = Character.codePointAt(b, j)
            if (cpA != cpB) return cpA - cpB
            i += Character.charCount(cpA)
            j += Character.charCount(cpB)
        }
        return (lenA - i) - (lenB - j)
    }
}

