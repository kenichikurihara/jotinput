package com.example.myasr.formal

import edu.mit.csail.sdg.alloy4.A4Reporter
import edu.mit.csail.sdg.parser.CompUtil
import edu.mit.csail.sdg.translator.A4Options
import edu.mit.csail.sdg.translator.TranslateAlloyToKodkod
import kodkod.engine.satlab.SATFactory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Automated Alloy 6 Formal Verification suite (runs in CI via `./gradlew testDebugUnitTest`).
 *
 * Parses and model-checks all `.als` specifications using SAT4J:
 * - `check` assertions must yield ZERO counterexamples (`!solution.satisfiable()`).
 * - `run` predicates must yield at least one instance (`solution.satisfiable()`), proving non-vacuity.
 */
class AlloyVerificationTest {

    private fun verifyAlloyModel(resourcePath: String) {
        val stream = javaClass.classLoader?.getResourceAsStream(resourcePath)
            ?: throw IllegalStateException("Alloy specification resource not found: $resourcePath")

        val tempFile: File = Files.createTempFile("jotinput_alloy_", ".als").toFile()
        try {
            stream.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            val reporter = A4Reporter.NOP
            val world = CompUtil.parseEverything_fromFile(reporter, null, tempFile.absolutePath)
            val commands = world.allCommands
            assertTrue("Expected at least one Alloy command in $resourcePath", commands.isNotEmpty())

            val options = A4Options().apply {
                solver = SATFactory.get("sat4j")
            }

            for (cmd in commands) {
                val solution = TranslateAlloyToKodkod.execute_command(
                    reporter,
                    world.allReachableSigs,
                    cmd,
                    options
                )
                assertNotNull("Alloy solver returned null for command '${cmd.label}' in $resourcePath", solution)

                if (cmd.check) {
                    assertFalse(
                        "Alloy assertion '${cmd.label}' in $resourcePath failed with counterexample:\n$solution",
                        solution.satisfiable()
                    )
                } else {
                    assertTrue(
                        "Alloy reachability predicate '${cmd.label}' in $resourcePath is unsatisfiable (vacuous model)",
                        solution.satisfiable()
                    )
                }
            }
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `voice_touch_arbitration formal model satisfies all safety invariants and reachability checks`() {
        verifyAlloyModel("alloy/voice_touch_arbitration.als")
    }

    @Test
    fun `dmn_decision_safety formal model satisfies all decision table invariants and reachability checks`() {
        verifyAlloyModel("alloy/dmn_decision_safety.als")
    }

    @Test
    fun `custom_dictionary_ui formal model satisfies all undo, tombstone, and filter invariants`() {
        verifyAlloyModel("alloy/custom_dictionary_ui.als")
    }
}
