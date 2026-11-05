package com.example.myasr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.StandardCharsets

class ConnectionMatrixTest {

    @Test
    fun testInMemoryConnectionMatrix() {
        val size = 4
        // Matrix size 4x4 = 16 transitions
        val data = ShortArray(size * size)
        // Transition from 1 to 2 has cost 500
        data[1 * size + 2] = 500.toShort()
        // Transition from 0 (BOS) to 1 has cost 120
        data[0 * size + 1] = 120.toShort()
        // Transition from 2 to 0 (EOS) has cost 300
        data[2 * size + 0] = 300.toShort()

        val matrix = ConnectionMatrix.createInMemory(size, data)
        assertEquals(4, matrix.matrixSize)
        assertEquals(500, matrix.getTransitionCost(1, 2))
        assertEquals(120, matrix.getTransitionCost(0, 1))
        assertEquals(300, matrix.getTransitionCost(2, 0))
        assertEquals(0, matrix.getTransitionCost(0, 0))

        // Out of bounds
        assertEquals(ConnectionMatrix.INVALID_COST, matrix.getTransitionCost(-1, 0))
        assertEquals(ConnectionMatrix.INVALID_COST, matrix.getTransitionCost(0, 4))
        assertEquals(ConnectionMatrix.INVALID_COST, matrix.getTransitionCost(4, 0))
    }

    @Test
    fun testCompileStreamToBinaryAndLoadFile() {
        val size = 3
        // Line 1: dimension
        // Lines 2..10: costs for 9 cells (0..8)
        val tsvContent = buildString {
            appendLine(size.toString())
            for (i in 0 until (size * size)) {
                appendLine((i * 100).toString())
            }
        }

        val tempBin = File.createTempFile("test_conn", ".bin")
        tempBin.deleteOnExit()

        val inputStream = ByteArrayInputStream(tsvContent.toByteArray(StandardCharsets.UTF_8))
        ConnectionDataManager.compileStreamToBinary(inputStream, tempBin)

        assertTrue(tempBin.exists())
        assertEquals(6L + (9 * 2), tempBin.length()) // 6 header bytes + 18 cost bytes

        val matrix = ConnectionMatrix.loadFromBinaryFile(tempBin)
        assertNotNull(matrix)
        matrix!!.use { mat ->
            assertEquals(3, mat.matrixSize)
            // Cell (0, 0) = index 0 -> 0
            assertEquals(0, mat.getTransitionCost(0, 0))
            // Cell (0, 1) = index 1 -> 100
            assertEquals(100, mat.getTransitionCost(0, 1))
            // Cell (1, 2) = index 1*3 + 2 = 5 -> 500
            assertEquals(500, mat.getTransitionCost(1, 2))
            // Cell (2, 2) = index 2*3 + 2 = 8 -> 800
            assertEquals(800, mat.getTransitionCost(2, 2))
        }
    }

    @Test
    fun testKanaKanjiEngineWithConnectionMatrix() {
        val engine = KanaKanjiEngine(null)
        val tsvFile = listOf(
            File("src/test/resources/mozc/base_dictionary.tsv"),
            File("app/src/test/resources/mozc/base_dictionary.tsv"),
            File("tools/mozc_dict/base_dictionary.tsv"),
            File("../tools/mozc_dict/base_dictionary.tsv"),
            File("src/main/assets/mozc/base_dictionary.tsv"),
            File("app/src/main/assets/mozc/base_dictionary.tsv")
        ).firstOrNull { it.exists() }
        requireNotNull(tsvFile) { "base_dictionary.tsv not found for unit test" }
        tsvFile.inputStream().use { stream ->
            engine.initializeWithStream(stream)
        }

        // Setup connection matrix transitions:
        // BOS(0) -> 海(1851): 1066
        // BOS(0) -> 生み(2123): 6907 (penalty)
        // BOS(0) -> 産み(842): 2451
        // 海(1851) -> の(433): 1033
        // 生み(2123) -> の(433): 26
        // 産み(842) -> の(433): 1801
        // の(433) -> 周り(1851): 409
        // 周り(1851) -> に(372): 1290
        val size = 2672
        // Default unset cells to high transition cost (10000) so unspecified transitions are not accidentally free (0)
        val data = ShortArray(size * size) { 10000.toShort() }
        data[0 * size + 1851] = 1066.toShort()
        data[0 * size + 2123] = 6907.toShort()
        data[0 * size + 842] = 2451.toShort()
        data[1851 * size + 433] = 1033.toShort()
        data[2123 * size + 433] = 26.toShort()
        data[842 * size + 433] = 1801.toShort()
        data[433 * size + 1851] = 409.toShort()
        data[1851 * size + 372] = 1290.toShort()
        data[372 * size + 0] = 0.toShort() // EOS

        val matrix = ConnectionMatrix.createInMemory(size, data)
        engine.setConnectionMatrix(matrix)

        // Warm up JIT
        engine.getBaseCandidates("うみ")

        // Verify conversion with connection matrix loaded runs stably and fast (< 50ms on cold JVM)
        val start = System.nanoTime()
        val cands = engine.getBaseCandidates("うみのまわりに")
        val elapsedMs = (System.nanoTime() - start) / 1_000_000.0

        assertTrue("Viterbi latency with ConnectionMatrix should be < 50ms, was: $elapsedMs ms", elapsedMs < 50.0)
        assertTrue("Should return conversion candidates", cands.isNotEmpty())
        val topSurface = cands.first().surface
        assertTrue("With connection matrix, top surface should be '海の周りに', was: $topSurface",
            topSurface == "海の周りに" || topSurface == "海のまわりに")
    }
}
