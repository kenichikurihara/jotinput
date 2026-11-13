package com.example.myasr.ddd

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.ext.list.withNameEndingWith
import com.lemonappdev.konsist.api.ext.list.withPackage
import com.lemonappdev.konsist.api.verify.assertTrue
import org.junit.Test

/**
 * Automated DDD Architecture Fitness Functions (enforced in CI via `./gradlew testDebugUnitTest`).
 *
 * Prevents architectural drift across Bounded Contexts, Shared Kernel, Domain layers, and Judgement classes.
 */
class DddArchitectureKonsistTest {

    @Test
    fun `Shared Kernel must not depend on any Bounded Context or Android UI`() {
        Konsist
            .scopeFromProduction()
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
        Konsist
            .scopeFromProduction()
            .classes()
            .withNameEndingWith("Judgement")
            .assertTrue { it.residesInPackage("..domain..") }

        Konsist
            .scopeFromProduction()
            .objects()
            .withNameEndingWith("Judgement")
            .assertTrue { it.residesInPackage("..domain..") }
    }

    @Test
    fun `all Judgement files must remain pure and never depend on infrastructure, presentation, or Android UI`() {
        Konsist
            .scopeFromProduction()
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
        Konsist
            .scopeFromProduction()
            .files
            .withPackage("com.example.myasr.context..domain..")
            .assertTrue { file ->
                file.imports.none { imp ->
                    imp.name.contains(".presentation.") ||
                        imp.name.startsWith("android.widget.") ||
                        imp.name.startsWith("androidx.appcompat.") ||
                        imp.name.startsWith("com.google.android.material.")
                }
            }
    }

    @Test
    fun `pure bounded context domains (reranking, speech, english, vocabulary) must not depend on any infrastructure`() {
        val pureDomainPackages = listOf(
            "com.example.myasr.context.reranking.domain..",
            "com.example.myasr.context.speech.domain..",
            "com.example.myasr.context.english.domain..",
            "com.example.myasr.context.vocabulary.domain.."
        )

        pureDomainPackages.forEach { pkg ->
            Konsist
                .scopeFromProduction()
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
    fun `all Repository classes must reside in infrastructure packages`() {
        Konsist
            .scopeFromProduction()
            .classes()
            .withNameEndingWith("Repository")
            .assertTrue { it.residesInPackage("..infrastructure..") }
    }
}
