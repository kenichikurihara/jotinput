package com.example.myasr.context.provisioning.infrastructure

import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/**
 * Pure-Kotlin BZip2 decompression [InputStream] supporting single and concatenated streams,
 * multiple blocks, all block sizes (`BZh1`–`BZh9`), RLE2 decoding, optional block randomization,
 * and strict per-block / combined MSB-first CRC-32 verification.
 *
 * Replaces `org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream`
 * with zero external dependencies.
 */
class BZip2InputStream(
    private val input: InputStream,
    private val decompressConcatenated: Boolean = false
) : InputStream() {

    companion object {
        private const val IO_BUF_SIZE = 8192
        private const val BLOCK_MAGIC = 0x314159265359L
        private const val EOS_MAGIC = 0x177245385090L
        private const val MAX_ALPHA_SIZE = 258
        private const val MAX_CODE_LEN = 23
        private const val GROUP_SIZE = 50
        private const val MAX_GROUPS = 6

        private val CRC32_TABLE = IntArray(256) { i ->
            var c = i shl 24
            for (j in 0 until 8) {
                c = if ((c and Int.MIN_VALUE) != 0) {
                    (c shl 1) xor 0x04C11DB7
                } else {
                    c shl 1
                }
            }
            c
        }

        private val RNUMS = intArrayOf(
            619, 720, 127, 481, 931, 816, 813, 233, 566, 247, 985, 724, 205, 454, 863, 491,
            741, 242, 949, 214, 733, 859, 335, 708, 621, 574, 73, 654, 730, 472, 419, 436,
            278, 496, 867, 210, 399, 680, 480, 51, 871, 497, 381, 495, 498, 437, 797, 160,
            182, 603, 85, 429, 539, 512, 965, 488, 315, 10, 439, 52, 768, 940, 224, 449,
            798, 864, 474, 632, 745, 295, 415, 701, 106, 201, 677, 239, 434, 306, 108, 175,
            500, 58, 345, 278, 212, 864, 872, 117, 874, 202, 536, 386, 837, 677, 222, 261,
            501, 28, 64, 174, 46, 562, 32, 262, 754, 54, 691, 363, 863, 183, 673, 199,
            218, 799, 396, 733, 534, 286, 537, 523, 217, 12, 442, 692, 17, 134, 800, 183,
            880, 968, 749, 611, 268, 685, 526, 484, 437, 736, 384, 911, 131, 586, 372, 709,
            666, 220, 331, 54, 64, 652, 706, 870, 64, 296, 676, 734, 236, 954, 146, 589,
            741, 831, 433, 937, 447, 481, 281, 48, 843, 727, 909, 431, 739, 892, 63, 810,
            854, 176, 114, 393, 135, 152, 712, 331, 564, 964, 463, 842, 595, 118, 165, 4,
            409, 871, 823, 121, 505, 333, 208, 283, 162, 234, 917, 211, 805, 739, 413, 207,
            60, 425, 23, 670, 93, 14, 159, 629, 449, 321, 907, 603, 841, 705, 183, 520,
            107, 388, 700, 545, 814, 906, 761, 100, 497, 655, 728, 712, 497, 545, 840, 283,
            188, 86, 255, 603, 701, 489, 99, 494, 380, 49, 453, 738, 223, 474, 911, 532,
            786, 240, 795, 391, 538, 415, 766, 794, 83, 530, 305, 467, 735, 419, 174, 47,
            410, 548, 61, 453, 715, 8, 571, 14, 692, 220, 41, 329, 712, 491, 506, 133,
            926, 39, 252, 507, 198, 95, 850, 320, 142, 611, 391, 293, 534, 788, 494, 386,
            738, 89, 268, 828, 734, 242, 27, 955, 687, 775, 653, 816, 185, 564, 651, 491,
            766, 170, 407, 557, 83, 497, 623, 523, 915, 421, 903, 593, 297, 592, 67, 639,
            386, 209, 269, 462, 82, 405, 917, 566, 559, 608, 375, 930, 855, 227, 561, 915,
            8, 326, 563, 742, 710, 208, 465, 411, 631, 134, 667, 482, 470, 217, 719, 212,
            881, 427, 372, 667, 895, 229, 178, 914, 132, 743, 703, 66, 298, 381, 412, 204,
            410, 810, 909, 805, 513, 311, 611, 171, 148, 497, 911, 384, 544, 611, 721, 740,
            410, 456, 611, 171, 148, 497, 611, 709, 114, 171, 148, 497, 611, 275, 513, 171,
            148, 497, 611, 384, 544, 611, 721, 740, 410, 456, 611, 171, 148, 497, 611, 709,
            114, 171, 148, 497, 611, 275, 513, 171, 148, 497, 611, 384, 544, 611, 721, 740,
            410, 456, 611, 171, 148, 497, 611, 709, 114, 171, 148, 497, 611, 275, 513, 171,
            148, 497, 611, 384, 544, 611, 721, 740, 410, 456, 611, 171, 148, 497, 611, 709,
            114, 171, 148, 497, 611, 275, 513, 171, 148, 497, 611, 384, 544, 611, 721, 740,
            410, 456, 611, 171, 148, 497, 611, 709, 114, 171, 148, 497, 611, 275, 513, 171
        )
    }

    // Buffered raw input state
    private val ioBuf = ByteArray(IO_BUF_SIZE)
    private var ioPos = 0
    private var ioLen = 0
    private var bitBuff = 0L
    private var bitLive = 0

    // Stream & block state
    private var closed = false
    private var eof = false
    private var blockSize100k = 0
    private var maxBlockSize = 0
    private var computedCombinedCrc = 0

    // Reusable per-block decompression buffers
    private var ll8 = ByteArray(0)
    private var tt = IntArray(0)
    private val seqToUnseq = ByteArray(256)
    private val unzftab = IntArray(256)
    private val cftab = IntArray(257)
    private val mtfYy = ByteArray(256)
    private val groupMinLens = IntArray(MAX_GROUPS)
    private val groupMaxLens = IntArray(MAX_GROUPS)
    private val groupLimits = Array(MAX_GROUPS) { IntArray(MAX_CODE_LEN) }
    private val groupBases = Array(MAX_GROUPS) { IntArray(MAX_CODE_LEN) }
    private val groupPerms = Array(MAX_GROUPS) { IntArray(MAX_ALPHA_SIZE) }
    private val codeLengths = Array(MAX_GROUPS) { IntArray(MAX_ALPHA_SIZE) }

    // Current block output state (inverse BWT + randomization + RLE2)
    private var blockActive = false
    private var storedBlockCrc = 0
    private var computedBlockCrc = -1
    private var blockRandomised = false
    private var blockBytesRemaining = 0
    private var tPos = 0
    private var rNToGo = 0
    private var rTPos = 0
    private var statePrevChar = -1
    private var stateRunLen = 0
    private var pendingRepeatCount = 0

    init {
        initStream(isFirstStream = true)
    }

    override fun read(): Int {
        if (closed) throw IOException("Stream closed")
        val b = nextDecodedByte()
        return if (b < 0) -1 else (b and 0xFF)
    }

    override fun read(dest: ByteArray, off: Int, len: Int): Int {
        if (closed) throw IOException("Stream closed")
        if (off < 0 || len < 0 || off + len > dest.size) {
            throw IndexOutOfBoundsException("off=$off, len=$len, size=${dest.size}")
        }
        if (len == 0) return 0
        if (eof) return -1

        var total = 0
        while (total < len) {
            val b = nextDecodedByte()
            if (b < 0) break
            dest[off + total] = b.toByte()
            total++
        }
        return if (total == 0) -1 else total
    }

    override fun close() {
        if (!closed) {
            closed = true
            input.close()
        }
    }

    private fun readRawByte(): Int {
        if (ioPos >= ioLen) {
            ioPos = 0
            ioLen = input.read(ioBuf)
            if (ioLen <= 0) {
                ioLen = 0
                return -1
            }
        }
        return ioBuf[ioPos++].toInt() and 0xFF
    }

    private fun readBit(): Int {
        if (bitLive == 0) {
            val b = readRawByte()
            if (b < 0) throw EOFException("Unexpected end of BZip2 stream")
            bitBuff = b.toLong()
            bitLive = 8
        }
        bitLive--
        val bit = ((bitBuff ushr bitLive) and 1L).toInt()
        bitBuff = bitBuff and ((1L shl bitLive) - 1L)
        return bit
    }

    private fun readBits(n: Int): Int {
        while (bitLive < n) {
            val b = readRawByte()
            if (b < 0) throw EOFException("Unexpected end of BZip2 stream")
            bitBuff = (bitBuff shl 8) or b.toLong()
            bitLive += 8
        }
        val shift = bitLive - n
        val result = ((bitBuff ushr shift) and ((1L shl n) - 1L)).toInt()
        bitLive = shift
        bitBuff = bitBuff and ((1L shl shift) - 1L)
        return result
    }

    private fun readMagic48(): Long {
        val hi = readBits(24).toLong() and 0xFFFFFFL
        val lo = readBits(24).toLong() and 0xFFFFFFL
        return (hi shl 24) or lo
    }

    private fun initStream(isFirstStream: Boolean): Boolean {
        bitBuff = 0L
        bitLive = 0
        val b0 = readRawByte()
        if (b0 < 0) {
            if (isFirstStream) throw IOException("Empty BZip2 stream")
            eof = true
            return false
        }
        val b1 = readRawByte()
        val b2 = readRawByte()
        val b3 = readRawByte()
        if (b0 != 'B'.code || b1 != 'Z'.code || b2 != 'h'.code) {
            throw IOException("Invalid BZip2 header magic")
        }
        if (b3 < '1'.code || b3 > '9'.code) {
            throw IOException("Invalid BZip2 block size char: $b3")
        }
        val newBlockSize100k = b3 - '0'.code
        if (newBlockSize100k != blockSize100k) {
            blockSize100k = newBlockSize100k
            maxBlockSize = blockSize100k * 100_000
            ll8 = ByteArray(maxBlockSize)
            tt = IntArray(maxBlockSize)
        }
        computedCombinedCrc = 0
        return setupNextBlock()
    }

    private fun setupNextBlock(): Boolean {
        while (true) {
            if (eof) return false
            val magic = readMagic48()
            if (magic == EOS_MAGIC) {
                val storedCombinedCrc = readBits(32)
                if (storedCombinedCrc != computedCombinedCrc) {
                    throw IOException(
                        "BZip2 combined CRC mismatch: expected=0x${Integer.toHexString(storedCombinedCrc)}, " +
                            "actual=0x${Integer.toHexString(computedCombinedCrc)}"
                    )
                }
                blockActive = false
                if (decompressConcatenated && initStream(isFirstStream = false)) {
                    return true
                }
                eof = true
                return false
            }
            if (magic != BLOCK_MAGIC) {
                throw IOException("Invalid BZip2 block header magic: 0x${java.lang.Long.toHexString(magic)}")
            }
            decodeBlock()
            if (blockBytesRemaining > 0) {
                return true
            }
            // Empty block (0 bytes): verify CRC and proceed to next block
            finishBlock()
        }
    }

    private fun decodeBlock() {
        storedBlockCrc = readBits(32)
        blockRandomised = readBits(1) == 1
        val origPtr = readBits(24)

        // Read inUse symbol bitmap (16 x 16)
        val inUse16 = readBits(16)
        var nInUse = 0
        for (i in 0 until 16) {
            if ((inUse16 and (1 shl (15 - i))) != 0) {
                val inUseRow = readBits(16)
                val base = i shl 4
                for (j in 0 until 16) {
                    if ((inUseRow and (1 shl (15 - j))) != 0) {
                        seqToUnseq[nInUse++] = (base + j).toByte()
                    }
                }
            }
        }
        if (nInUse <= 0) {
            throw IOException("BZip2 block has no symbols in use")
        }
        val alphaSize = nInUse + 2
        val endOfBlock = nInUse + 1

        // Read selector list
        val nGroups = readBits(3)
        if (nGroups < 2 || nGroups > MAX_GROUPS) {
            throw IOException("Invalid BZip2 Huffman group count: $nGroups")
        }
        val nSelectors = readBits(15)
        if (nSelectors <= 0) {
            throw IOException("Invalid BZip2 selector count: $nSelectors")
        }

        val selectorMtf = ByteArray(nGroups) { it.toByte() }
        val selectors = ByteArray(nSelectors)
        for (i in 0 until nSelectors) {
            var j = 0
            while (readBit() == 1) {
                j++
                if (j >= nGroups) {
                    throw IOException("BZip2 selector MTF index out of bounds")
                }
            }
            val tmp = selectorMtf[j]
            for (k in j downTo 1) {
                selectorMtf[k] = selectorMtf[k - 1]
            }
            selectorMtf[0] = tmp
            selectors[i] = tmp
        }

        // Read Huffman coding tables and build canonical decode tables
        for (t in 0 until nGroups) {
            var currLen = readBits(5)
            val lens = codeLengths[t]
            for (i in 0 until alphaSize) {
                while (true) {
                    if (currLen !in 1..20) {
                        throw IOException("Invalid BZip2 Huffman code length: $currLen")
                    }
                    if (readBit() == 0) break
                    if (readBit() == 0) currLen++ else currLen--
                }
                lens[i] = currLen
            }
            createDecodeTables(t, alphaSize)
        }

        // Decode MTF symbols
        unzftab.fill(0)
        for (i in 0 until nInUse) {
            mtfYy[i] = i.toByte()
        }

        var groupNo = 0
        var groupPos = 0
        var currentGroup = selectors[0].toInt() and 0xFF

        fun nextMtfSymbol(): Int {
            if (groupPos == GROUP_SIZE) {
                groupPos = 0
                groupNo++
                if (groupNo >= nSelectors) {
                    throw IOException("BZip2 selector index exceeded ($groupNo >= $nSelectors)")
                }
                currentGroup = selectors[groupNo].toInt() and 0xFF
            }
            groupPos++

            var zn = groupMinLens[currentGroup]
            val maxLen = groupMaxLens[currentGroup]
            val limit = groupLimits[currentGroup]
            var zvec = readBits(zn)
            while (zvec > limit[zn]) {
                zn++
                if (zn > maxLen) {
                    throw IOException("Corrupt BZip2 Huffman bitstream")
                }
                zvec = (zvec shl 1) or readBit()
            }
            return groupPerms[currentGroup][zvec - groupBases[currentGroup][zn]]
        }

        var last = -1
        var nextSym = nextMtfSymbol()
        while (nextSym != endOfBlock) {
            if (nextSym == 0 || nextSym == 1) {
                var run = 0
                var power = 1
                while (nextSym == 0 || nextSym == 1) {
                    if (power > maxBlockSize || run > maxBlockSize) {
                        throw IOException("BZip2 RLE1 run overflow")
                    }
                    run += (nextSym + 1) * power
                    power = power shl 1
                    nextSym = nextMtfSymbol()
                }
                if (last + run >= maxBlockSize) {
                    throw IOException("BZip2 block overflow")
                }
                val ch = seqToUnseq[mtfYy[0].toInt() and 0xFF]
                unzftab[ch.toInt() and 0xFF] += run
                while (run-- > 0) {
                    ll8[++last] = ch
                }
            } else {
                val mtfIdx = nextSym - 1
                if (mtfIdx >= nInUse) {
                    throw IOException("BZip2 MTF index out of bounds: $mtfIdx")
                }
                val tmp = mtfYy[mtfIdx]
                System.arraycopy(mtfYy, 0, mtfYy, 1, mtfIdx)
                mtfYy[0] = tmp
                val ch = seqToUnseq[tmp.toInt() and 0xFF]
                unzftab[ch.toInt() and 0xFF]++
                if (last + 1 >= maxBlockSize) {
                    throw IOException("BZip2 block overflow")
                }
                ll8[++last] = ch
                nextSym = nextMtfSymbol()
            }
        }

        if (origPtr < 0 || origPtr > last) {
            throw IOException("BZip2 origPtr out of bounds: origPtr=$origPtr, last=$last")
        }

        // Build inverse BWT transformation table
        cftab[0] = 0
        for (i in 1..256) {
            cftab[i] = cftab[i - 1] + unzftab[i - 1]
        }
        for (i in 0..last) {
            val ch = ll8[i].toInt() and 0xFF
            tt[cftab[ch]++] = i
        }

        blockActive = true
        computedBlockCrc = -1
        blockBytesRemaining = last + 1
        tPos = tt[origPtr]
        rNToGo = 0
        rTPos = 0
        statePrevChar = -1
        stateRunLen = 0
        pendingRepeatCount = 0
    }

    private fun createDecodeTables(group: Int, alphaSize: Int) {
        val lens = codeLengths[group]
        val limit = groupLimits[group]
        val base = groupBases[group]
        val perm = groupPerms[group]

        var minLen = MAX_CODE_LEN
        var maxLen = 0
        for (i in 0 until alphaSize) {
            val l = lens[i]
            if (l < minLen) minLen = l
            if (l > maxLen) maxLen = l
        }
        groupMinLens[group] = minLen
        groupMaxLens[group] = maxLen

        var pp = 0
        for (len in minLen..maxLen) {
            for (sym in 0 until alphaSize) {
                if (lens[sym] == len) {
                    perm[pp++] = sym
                }
            }
        }

        base.fill(0)
        limit.fill(0)
        for (i in 0 until alphaSize) {
            base[lens[i] + 1]++
        }
        for (i in 1 until MAX_CODE_LEN) {
            base[i] += base[i - 1]
        }

        var vec = 0
        for (i in minLen..maxLen) {
            vec += base[i + 1] - base[i]
            limit[i] = vec - 1
            vec = vec shl 1
        }
        for (i in (minLen + 1)..maxLen) {
            base[i] = ((limit[i - 1] + 1) shl 1) - base[i]
        }
    }

    private fun nextBwtByte(): Int {
        var b = ll8[tPos].toInt() and 0xFF
        tPos = tt[tPos]
        blockBytesRemaining--
        if (blockRandomised) {
            if (rNToGo == 0) {
                rNToGo = RNUMS[rTPos]
                if (++rTPos == 512) rTPos = 0
            }
            rNToGo--
            if (rNToGo == 1) {
                b = b xor 1
            }
        }
        return b
    }

    private fun updateBlockCrc(b: Int) {
        computedBlockCrc = (computedBlockCrc shl 8) xor CRC32_TABLE[((computedBlockCrc ushr 24) xor b) and 0xFF]
    }

    private fun finishBlock() {
        val finalCrc = computedBlockCrc.inv()
        if (finalCrc != storedBlockCrc) {
            throw IOException(
                "BZip2 block CRC mismatch: expected=0x${Integer.toHexString(storedBlockCrc)}, " +
                    "actual=0x${Integer.toHexString(finalCrc)}"
            )
        }
        computedCombinedCrc = ((computedCombinedCrc shl 1) or (computedCombinedCrc ushr 31)) xor finalCrc
        blockActive = false
    }

    private fun nextDecodedByte(): Int {
        while (true) {
            if (eof) return -1

            if (pendingRepeatCount > 0) {
                pendingRepeatCount--
                val out = statePrevChar
                if (pendingRepeatCount == 0) {
                    statePrevChar = -1
                    stateRunLen = 0
                }
                updateBlockCrc(out)
                return out
            }

            if (blockActive && blockBytesRemaining > 0) {
                if (stateRunLen == 4) {
                    val extraCount = nextBwtByte()
                    if (extraCount == 0) {
                        statePrevChar = -1
                        stateRunLen = 0
                        continue
                    }
                    val out = statePrevChar
                    if (extraCount == 1) {
                        statePrevChar = -1
                        stateRunLen = 0
                    } else {
                        pendingRepeatCount = extraCount - 1
                    }
                    updateBlockCrc(out)
                    return out
                }

                val b = nextBwtByte()
                if (b == statePrevChar) {
                    stateRunLen++
                } else {
                    statePrevChar = b
                    stateRunLen = 1
                }
                updateBlockCrc(b)
                return b
            }

            if (blockActive) {
                finishBlock()
            }
            if (!setupNextBlock()) {
                return -1
            }
        }
    }
}
