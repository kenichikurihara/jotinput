package com.example.myasr

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression test preventing UninitializedPropertyAccessException on Activity launch
 * caused by calling UI setup/update helpers inside initViews() before all findViewById()
 * assignments have completed.
 */
class ActivityInitOrderTest {

    private fun resolveProjectRoot(): File {
        var dir = File(System.getProperty("user.dir") ?: ".")
        if (!File(dir, "app/src/main/java").exists() && File(dir, "src/main/java").exists()) {
            dir = dir.parentFile ?: dir
        }
        return dir
    }

    @Test
    fun `initViews completes all findViewById assignments before calling UI helpers`() {
        val root = resolveProjectRoot()
        val activities = listOf(
            "app/src/main/java/com/example/myasr/MainActivity.kt",
            "app/src/main/java/com/example/myasr/OnboardingActivity.kt"
        )

        for (relPath in activities) {
            val file = File(root, relPath)
            assertTrue("Expected file to exist: ${file.absolutePath}", file.exists())
            val lines = file.readLines()

            val initStart = lines.indexOfFirst { it.contains("private fun initViews()") }
            assertTrue("initViews() not found in $relPath", initStart >= 0)

            var braceDepth = 0
            var initEnd = -1
            for (i in initStart until lines.size) {
                val line = lines[i]
                braceDepth += line.count { it == '{' }
                braceDepth -= line.count { it == '}' }
                if (i > initStart && braceDepth == 0) {
                    initEnd = i
                    break
                }
            }
            assertTrue("Could not find end of initViews() in $relPath", initEnd > initStart)

            val initBody = lines.subList(initStart + 1, initEnd)
            val lastFindViewLine = initBody.indexOfLast { it.contains("findViewById(") }
            assertTrue("No findViewById() calls found in initViews() of $relPath", lastFindViewLine >= 0)

            val helperCallPattern = Regex("""^\s*(setup|update|refresh|apply|render)[A-Z]\w*\(""")
            for (idx in 0 until lastFindViewLine) {
                val line = initBody[idx]
                assertTrue(
                    "$relPath: UI helper called at initViews() offset $idx before last findViewById() at offset $lastFindViewLine: ${line.trim()}",
                    !helperCallPattern.containsMatchIn(line)
                )
            }
        }
    }

    @Test
    fun `all findViewById references in MainActivity and OnboardingActivity exist in layout XMLs`() {
        val root = resolveProjectRoot()
        val layoutDir = File(root, "app/src/main/res/layout")
        assertTrue("Layout directory missing: ${layoutDir.absolutePath}", layoutDir.isDirectory)

        val declaredIds = mutableSetOf<String>()
        val idDeclPattern = Regex("""android:id="@\+id/(\w+)"""")
        layoutDir.listFiles { f -> f.extension == "xml" }?.forEach { xmlFile ->
            val content = xmlFile.readText()
            idDeclPattern.findAll(content).forEach { match ->
                declaredIds.add(match.groupValues[1])
            }
        }

        val findPattern = Regex("""findViewById(?:<[^>]+>)?\(R\.id\.(\w+)\)""")
        val activities = listOf(
            "app/src/main/java/com/example/myasr/MainActivity.kt",
            "app/src/main/java/com/example/myasr/OnboardingActivity.kt"
        )
        for (relPath in activities) {
            val content = File(root, relPath).readText()
            findPattern.findAll(content).forEach { match ->
                val idName = match.groupValues[1]
                assertTrue(
                    "$relPath references R.id.$idName which was not found in res/layout/*.xml",
                    idName in declaredIds
                )
            }
        }
    }
}
