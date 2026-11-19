package com.example.myasr

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards against native SIGABRT regressions in llama.cpp integration (Finding 42):
 * `llama_memory_seq_cp(mem, src, dst, p0, p1)` asserts `is_full` inside llama.cpp
 * and aborts the process if called on a subrange (p0 != -1 || p1 != -1).
 */
class LlmRerankerNativeSafetyTest {

    private fun resolveProjectRoot(): File {
        var dir = File(System.getProperty("user.dir") ?: ".")
        if (!File(dir, "app/src/main/cpp").exists() && File(dir, "src/main/cpp").exists()) {
            dir = dir.parentFile ?: dir
        }
        return dir
    }

    @Test
    fun `llama_memory_seq_cp must always use full sequence range (-1, -1)`() {
        val root = resolveProjectRoot()
        val cppDir = File(root, "app/src/main/cpp")
        assertTrue("Expected C++ directory at ${cppDir.absolutePath}", cppDir.isDirectory)

        val seqCpPattern = Regex("""llama_memory_seq_cp\s*\(([^)]+)\)""")
        val cppFiles = cppDir.walkTopDown().filter { it.isFile && (it.extension == "cc" || it.extension == "cpp" || it.extension == "h") }

        for (file in cppFiles) {
            val content = file.readText()
            seqCpPattern.findAll(content).forEach { match ->
                val args = match.groupValues[1].split(",").map { it.trim() }
                assertTrue(
                    "${file.name}: Expected 5 arguments to llama_memory_seq_cp, got ${args.size}: ${match.value}",
                    args.size == 5
                )
                assertTrue(
                    "${file.name}: llama_memory_seq_cp must pass (-1, -1) for p0, p1 to avoid GGML_ASSERT(is_full) SIGABRT (Finding 42), found: ${match.value}",
                    args[3] == "-1" && args[4] == "-1"
                )
            }
        }
    }
}
