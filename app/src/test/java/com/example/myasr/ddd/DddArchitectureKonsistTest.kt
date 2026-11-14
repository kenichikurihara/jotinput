package com.example.myasr.ddd

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.ext.list.withNameEndingWith
import com.lemonappdev.konsist.api.ext.list.withPackage
import com.lemonappdev.konsist.api.verify.assertTrue
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Automated DDD Architecture Fitness Functions (enforced in CI via `./gradlew testDebugUnitTest`).
 *
 * Prevents architectural drift across Bounded Contexts, Shared Kernel, Domain layers,
 * Judgement/DMN models, Alloy specifications, and `docs/ddd/` documentation.
 */
class DddArchitectureKonsistTest {

    companion object {
        // Cache scopes once per test class to keep CI execution fast.
        private val productionScope by lazy { Konsist.scopeFromProduction() }
        private val testScope by lazy { Konsist.scopeFromTest() }

        private val documentedBoundedContexts = setOf(
            "kanakanji",
            "reranking",
            "speech",
            "english",
            "vocabulary",
            "provisioning",
            "ime",
            "clipboard"
        )

        private fun resolveRepoRoot(): File {
            var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
            while (dir != null) {
                if (File(dir, "docs/ddd").isDirectory) {
                    return dir
                }
                dir = dir.parentFile
            }
            throw IllegalStateException(
                "Cannot locate repository root containing docs/ddd from ${System.getProperty("user.dir")}"
            )
        }
    }

    @Test
    fun `Shared Kernel must not depend on any Bounded Context or Android UI`() {
        productionScope
            .files
            .withPackage("com.example.myasr.shared..")
            .assertTrue { file ->
                file.imports.none { imp ->
                    imp.name.startsWith("com.example.myasr.context.") ||
                        imp.name.startsWith("android.view.") ||
                        imp.name.startsWith("android.widget.") ||
                        imp.name.startsWith("android.app.")
                }
            }
    }

    @Test
    fun `all Judgement classes and objects must reside in domain packages`() {
        productionScope
            .classes()
            .withNameEndingWith("Judgement")
            .assertTrue { it.resideInPackage("..domain..") }

        productionScope
            .objects()
            .withNameEndingWith("Judgement")
            .assertTrue { it.resideInPackage("..domain..") }
    }

    @Test
    fun `all Judgement files must remain pure and never depend on infrastructure, presentation, or Android UI`() {
        productionScope
            .files
            .filter { it.name.endsWith("Judgement") }
            .assertTrue { file ->
                file.imports.none { imp ->
                    imp.name.contains(".infrastructure.") ||
                        imp.name.contains(".presentation.") ||
                        imp.name.startsWith("android.view.") ||
                        imp.name.startsWith("android.widget.") ||
                        imp.name.startsWith("android.app.")
                }
            }
    }

    @Test
    fun `domain packages must never depend on presentation packages or Android UI widgets`() {
        // KeyboardHeightManager is a legacy SharedPreferences settings helper currently in ime.presentation.
        val legacySettingsExceptions = setOf(
            "com.example.myasr.context.ime.presentation.KeyboardHeightManager"
        )
        productionScope
            .files
            .withPackage("com.example.myasr.context..domain..")
            .assertTrue { file ->
                file.imports.none { imp ->
                    (imp.name.contains(".presentation.") && imp.name !in legacySettingsExceptions) ||
                        imp.name.startsWith("android.widget.") ||
                        imp.name.startsWith("androidx.appcompat.") ||
                        imp.name.startsWith("com.google.android.material.")
                }
            }
    }

    @Test
    fun `pure bounded context domains (reranking, speech, english, vocabulary, clipboard) must not depend on any infrastructure`() {
        val pureDomainPackages = listOf(
            "com.example.myasr.context.reranking.domain..",
            "com.example.myasr.context.speech.domain..",
            "com.example.myasr.context.english.domain..",
            "com.example.myasr.context.vocabulary.domain..",
            "com.example.myasr.context.clipboard.domain.."
        )

        pureDomainPackages.forEach { pkg ->
            productionScope
                .files
                .withPackage(pkg)
                .assertTrue { file ->
                    file.imports.none { imp ->
                        imp.name.contains(".infrastructure.")
                    }
                }
        }
    }

    @Test
    fun `DMN DecisionTable and DecisionModel definitions must reside in domain packages and remain pure`() {
        productionScope
            .classes()
            .withNameEndingWith("DecisionTable")
            .assertTrue { it.resideInPackage("..domain..") }

        productionScope
            .classes()
            .withNameEndingWith("DecisionModel")
            .assertTrue { it.resideInPackage("..domain..") }

        productionScope
            .files
            .withPackage("com.example.myasr.shared.domain.dmn..")
            .assertTrue { file ->
                file.imports.none { imp ->
                    imp.name.contains(".infrastructure.") ||
                        imp.name.contains(".presentation.") ||
                        imp.name.startsWith("android.")
                }
            }
    }

    @Test
    fun `all Repository classes must reside in infrastructure packages`() {
        productionScope
            .classes()
            .withNameEndingWith("Repository")
            .assertTrue { it.resideInPackage("..infrastructure..") }
    }

    @Test
    fun `all Bounded Context packages must match documented contexts in context_map`() {
        val contextPrefix = "com.example.myasr.context."
        productionScope
            .files
            .withPackage("com.example.myasr.context..")
            .assertTrue { file ->
                val pkgName = file.packagee?.name ?: return@assertTrue false
                val sub = pkgName.removePrefix(contextPrefix).substringBefore('.')
                sub in documentedBoundedContexts
            }
    }

    @Test
    fun `all Judgement and DecisionModel definitions must be exercised by unit tests`() {
        val decisionHolders = (
            productionScope.classes().withNameEndingWith("Judgement").map { it.name } +
                productionScope.objects().withNameEndingWith("Judgement").map { it.name } +
                productionScope.classes().withNameEndingWith("DecisionModel").map { it.name } +
                productionScope.objects().withNameEndingWith("DecisionModel").map { it.name }
            ).distinct()

        assertTrue("Expected at least one Judgement or DecisionModel in production", decisionHolders.isNotEmpty())

        val allTestText = testScope.files.joinToString("\n") { it.text }
        val untested = decisionHolders.filterNot { holderName -> allTestText.contains(holderName) }
        assertTrue(
            "Undertested Judgement/DecisionModel definitions (add unit tests): $untested",
            untested.isEmpty()
        )
    }

    @Test
    fun `all DMN DecisionTable names must be documented in dmn_decision_models`() {
        val repoRoot = resolveRepoRoot()
        val dmnDoc = File(repoRoot, "docs/ddd/dmn_decision_models.md").readText()
        val tableNameRegex = Regex("""decisionTable\s*(?:<[^>]+>)?\s*\(\s*name\s*=\s*"([^"]+)"""")

        val tableNames = productionScope
            .files
            .flatMap { file -> tableNameRegex.findAll(file.text).map { it.groupValues[1] }.toList() }
            .distinct()

        assertTrue("Expected at least one DMN DecisionTable in production", tableNames.isNotEmpty())
        val undocumented = tableNames.filterNot { tableName -> dmnDoc.contains(tableName) }
        assertTrue(
            "DMN DecisionTables missing from docs/ddd/dmn_decision_models.md: $undocumented",
            undocumented.isEmpty()
        )
    }

    @Test
    fun `all Alloy specifications must be documented in alloy_formal_verification`() {
        val repoRoot = resolveRepoRoot()
        val alloyDoc = File(repoRoot, "docs/ddd/alloy_formal_verification.md").readText()
        val alloyDir = File(repoRoot, "app/src/test/resources/alloy")
        val alsFiles = alloyDir.listFiles { f -> f.isFile && f.extension == "als" }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()

        assertTrue("Expected at least 6 .als files in ${alloyDir.absolutePath}", alsFiles.size >= 6)
        val undocumented = alsFiles.filterNot { alsName -> alloyDoc.contains(alsName) }
        assertTrue(
            "Alloy specs missing from docs/ddd/alloy_formal_verification.md: $undocumented",
            undocumented.isEmpty()
        )
    }
}
