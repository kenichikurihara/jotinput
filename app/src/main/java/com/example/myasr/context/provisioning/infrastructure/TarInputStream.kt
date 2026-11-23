package com.example.myasr.context.provisioning.infrastructure

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets

/**
 * Lightweight tar entry metadata returned by [TarInputStream.nextEntry].
 */
data class TarEntry(
    val name: String,
    val size: Long,
    val isDirectory: Boolean
)

/**
 * Minimal streaming POSIX/ustar/GNU/PAX tar reader [InputStream] with zero external dependencies.
 * Replaces `org.apache.commons.compress.archivers.tar.TarArchiveInputStream`.
 */
class TarInputStream(private val input: InputStream) : InputStream() {

    companion object {
        private const val RECORD_SIZE = 512
        private const val TYPE_FILE = '0'.code
        private const val TYPE_ALT_FILE = 0
        private const val TYPE_DIR = '5'.code
        private const val TYPE_GNU_LONGNAME = 'L'.code
        private const val TYPE_PAX_HEADER = 'x'.code
        private const val TYPE_PAX_GLOBAL_HEADER = 'g'.code
    }

    private val headerBuf = ByteArray(RECORD_SIZE)
    private val skipBuf = ByteArray(4096)
    private var entryRemaining = 0L
    private var entryPadding = 0L
    private var eof = false
    private var closed = false

    fun nextEntry(): TarEntry? {
        if (closed) throw IOException("Stream closed")
        if (eof) return null

        // Drain any unread entry payload plus 512-byte block alignment padding
        val toSkip = entryRemaining + entryPadding
        if (toSkip > 0L) {
            skipFully(toSkip)
        }
        entryRemaining = 0L
        entryPadding = 0L

        var pendingLongName: String? = null
        var pendingPaxPath: String? = null
        var pendingPaxSize: Long? = null

        while (true) {
            if (!readHeaderRecord(headerBuf)) {
                eof = true
                return null
            }
            if (isAllZero(headerBuf)) {
                // Consume second EOF zero block if present
                if (readHeaderRecord(headerBuf) && !isAllZero(headerBuf)) {
                    throw IOException("Corrupt tar archive: unexpected data after zero block")
                }
                eof = true
                return null
            }

            val rawSize = parseSize(headerBuf)
            val typeFlag = headerBuf[156].toInt() and 0xFF
            val padding = paddingForSize(rawSize)

            when (typeFlag) {
                TYPE_GNU_LONGNAME -> {
                    val bytes = readExactBytes(rawSize)
                    skipFully(padding)
                    var end = bytes.size
                    while (end > 0 && bytes[end - 1] == 0.toByte()) end--
                    pendingLongName = String(bytes, 0, end, StandardCharsets.UTF_8)
                    continue
                }
                TYPE_PAX_HEADER -> {
                    val bytes = readExactBytes(rawSize)
                    skipFully(padding)
                    val pax = parsePaxHeaders(bytes)
                    pax["path"]?.let { pendingPaxPath = it }
                    pax["size"]?.toLongOrNull()?.let { pendingPaxSize = it }
                    continue
                }
                TYPE_PAX_GLOBAL_HEADER -> {
                    skipFully(rawSize + padding)
                    continue
                }
            }

            val size = pendingPaxSize ?: rawSize
            var name = pendingPaxPath ?: pendingLongName ?: run {
                val baseName = readCString(headerBuf, 0, 100)
                val magic = String(headerBuf, 257, 5, StandardCharsets.US_ASCII)
                val prefix = if (magic == "ustar") readCString(headerBuf, 345, 155) else ""
                if (prefix.isNotEmpty()) "$prefix/$baseName" else baseName
            }

            val isDirectory = typeFlag == TYPE_DIR || name.endsWith("/")
            val effectivePadding = paddingForSize(size)
            if (isDirectory || (typeFlag != TYPE_FILE && typeFlag != TYPE_ALT_FILE)) {
                if (size > 0L) {
                    skipFully(size + effectivePadding)
                }
                entryRemaining = 0L
                entryPadding = 0L
            } else {
                entryRemaining = size
                entryPadding = effectivePadding
            }

            return TarEntry(name = name, size = size, isDirectory = isDirectory)
        }
    }

    override fun read(): Int {
        if (closed) throw IOException("Stream closed")
        if (entryRemaining <= 0L) return -1
        val b = input.read()
        if (b < 0) throw EOFException("Unexpected EOF inside tar entry")
        entryRemaining--
        return b
    }

    override fun read(dest: ByteArray, off: Int, len: Int): Int {
        if (closed) throw IOException("Stream closed")
        if (off < 0 || len < 0 || off + len > dest.size) {
            throw IndexOutOfBoundsException("off=$off, len=$len, size=${dest.size}")
        }
        if (len == 0) return 0
        if (entryRemaining <= 0L) return -1

        val toRead = minOf(len.toLong(), entryRemaining).toInt()
        val n = input.read(dest, off, toRead)
        if (n < 0) throw EOFException("Unexpected EOF inside tar entry")
        entryRemaining -= n.toLong()
        return n
    }

    override fun close() {
        if (!closed) {
            closed = true
            input.close()
        }
    }

    private fun readHeaderRecord(buf: ByteArray): Boolean {
        var offset = 0
        while (offset < RECORD_SIZE) {
            val n = input.read(buf, offset, RECORD_SIZE - offset)
            if (n < 0) {
                if (offset == 0) return false
                throw EOFException("Truncated tar header record ($offset/$RECORD_SIZE bytes)")
            }
            offset += n
        }
        return true
    }

    private fun readExactBytes(size: Long): ByteArray {
        if (size < 0L || size > 16L * 1024L * 1024L) {
            throw IOException("Unsupported tar metadata entry size: $size")
        }
        val out = ByteArrayOutputStream(size.toInt())
        var remaining = size
        while (remaining > 0L) {
            val chunk = minOf(remaining, skipBuf.size.toLong()).toInt()
            val n = input.read(skipBuf, 0, chunk)
            if (n < 0) throw EOFException("Unexpected EOF reading tar metadata")
            out.write(skipBuf, 0, n)
            remaining -= n.toLong()
        }
        return out.toByteArray()
    }

    private fun skipFully(bytes: Long) {
        var remaining = bytes
        while (remaining > 0L) {
            val chunk = minOf(remaining, skipBuf.size.toLong()).toInt()
            val n = input.read(skipBuf, 0, chunk)
            if (n < 0) throw EOFException("Unexpected EOF skipping tar padding/entry")
            remaining -= n.toLong()
        }
    }

    private fun isAllZero(buf: ByteArray): Boolean {
        for (b in buf) {
            if (b != 0.toByte()) return false
        }
        return true
    }

    private fun paddingForSize(size: Long): Long {
        if (size <= 0L) return 0L
        val rem = size % RECORD_SIZE
        return if (rem == 0L) 0L else (RECORD_SIZE - rem)
    }

    private fun readCString(buf: ByteArray, offset: Int, maxLen: Int): String {
        var len = 0
        while (len < maxLen && buf[offset + len] != 0.toByte()) {
            len++
        }
        return String(buf, offset, len, StandardCharsets.UTF_8)
    }

    private fun parseSize(buf: ByteArray): Long {
        val first = buf[124].toInt() and 0xFF
        // GNU binary base-256 encoding when MSB is set
        if ((first and 0x80) != 0) {
            var acc = (first and 0x7F).toLong()
            for (i in 125 until 136) {
                acc = (acc shl 8) or (buf[i].toInt() and 0xFF).toLong()
            }
            return acc
        }
        val octalStr = readCString(buf, 124, 12).trim()
        if (octalStr.isEmpty()) return 0L
        return octalStr.toLongOrNull(8)
            ?: throw IOException("Invalid tar entry size octal string: '$octalStr'")
    }

    private fun parsePaxHeaders(bytes: ByteArray): Map<String, String> {
        val result = HashMap<String, String>()
        var pos = 0
        while (pos < bytes.size) {
            val spaceIdx = findByte(bytes, pos, ' '.code.toByte())
            if (spaceIdx <= pos) break
            val lenStr = String(bytes, pos, spaceIdx - pos, StandardCharsets.US_ASCII)
            val recLen = lenStr.toIntOrNull() ?: break
            if (recLen <= 0 || pos + recLen > bytes.size) break

            val recordEnd = pos + recLen
            val contentStart = spaceIdx + 1
            // Strip trailing '\n'
            val contentEnd = if (recordEnd > contentStart && bytes[recordEnd - 1] == '\n'.code.toByte()) {
                recordEnd - 1
            } else {
                recordEnd
            }
            val eqIdx = findByte(bytes, contentStart, '='.code.toByte(), contentEnd)
            if (eqIdx > contentStart) {
                val key = String(bytes, contentStart, eqIdx - contentStart, StandardCharsets.UTF_8)
                val value = String(bytes, eqIdx + 1, contentEnd - (eqIdx + 1), StandardCharsets.UTF_8)
                result[key] = value
            }
            pos = recordEnd
        }
        return result
    }

    private fun findByte(
        bytes: ByteArray,
        start: Int,
        target: Byte,
        end: Int = bytes.size
    ): Int {
        for (i in start until end) {
            if (bytes[i] == target) return i
        }
        return -1
    }
}
