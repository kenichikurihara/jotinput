package com.example.myasr.shared.domain.dmn

import com.example.myasr.shared.domain.Judgement

/**
 * OMG DMN (Decision Model and Notation) Hit Policies.
 *
 * - [UNIQUE]: At most one rule may match; throws if multiple rules match.
 * - [FIRST]: Rules are evaluated top-to-bottom; the first matching rule wins.
 * - [PRIORITY]: Multiple rules may match; the matching rule with the highest [DecisionRule.priority] wins.
 * - [ANY]: Multiple rules may match only if they all produce equal outputs.
 * - [COLLECT]: All matching rules are collected in definition order.
 * - [RULE_ORDER]: All matching rules are returned in definition order.
 */
enum class HitPolicy(val code: String) {
    UNIQUE("U"),
    FIRST("F"),
    PRIORITY("P"),
    ANY("A"),
    COLLECT("C"),
    RULE_ORDER("R")
}

/**
 * Single rule row inside a DMN [DecisionTable].
 */
data class DecisionRule<in I, out O>(
    val id: String,
    val description: String,
    val priority: Int = 0,
    val condition: (I) -> Boolean,
    val output: (I) -> O
)

/**
 * Auditable evaluation trace returned by [DecisionTable.evaluateWithTrace].
 */
data class DecisionTrace<out O>(
    val decisionName: String,
    val hitPolicy: HitPolicy,
    val matchedRuleIds: List<String>,
    val selectedRuleId: String?,
    val selectedRuleDescription: String?,
    val usedDefaultFallback: Boolean,
    val result: O
)

/**
 * Aggregated trace for a Decision Requirements Graph (DRG) evaluation across multiple tables.
 */
data class DrgEvaluation<out O>(
    val result: O,
    val traces: List<DecisionTrace<*>>
)

/**
 * Pure-domain DMN Decision Table that implements DDD [Judgement].
 *
 * Bridges Strategic/Tactical DDD ([Judgement]) with declarative, auditable DMN Decision Tables.
 */
class DecisionTable<in I, out O>(
    val name: String,
    val hitPolicy: HitPolicy,
    val rules: List<DecisionRule<I, O>>,
    private val defaultRule: DecisionRule<I, O>? = null
) : Judgement<I, O> {

    init {
        require(name.isNotBlank()) { "DecisionTable name must not be blank" }
        val ids = rules.map { it.id }
        require(ids.size == ids.toSet().size) {
            "DecisionTable '$name' contains duplicate rule IDs: $ids"
        }
    }

    override fun judge(input: I): O = evaluate(input)

    fun evaluate(input: I): O = evaluateWithTrace(input).result

    fun evaluateWithTrace(input: I): DecisionTrace<O> {
        val matched = rules.filter { it.condition(input) }

        if (matched.isEmpty()) {
            val fallback = defaultRule
                ?: throw IllegalStateException(
                    "DecisionTable '$name' (${hitPolicy.code}) had no matching rule and no default fallback"
                )
            return DecisionTrace(
                decisionName = name,
                hitPolicy = hitPolicy,
                matchedRuleIds = emptyList(),
                selectedRuleId = fallback.id,
                selectedRuleDescription = fallback.description,
                usedDefaultFallback = true,
                result = fallback.output(input)
            )
        }

        val selectedRule: DecisionRule<I, O> = when (hitPolicy) {
            HitPolicy.UNIQUE -> {
                if (matched.size > 1) {
                    throw IllegalStateException(
                        "DecisionTable '$name' (UNIQUE) matched multiple rules: ${matched.map { it.id }}"
                    )
                }
                matched.first()
            }
            HitPolicy.FIRST -> matched.first()
            HitPolicy.PRIORITY -> matched.maxBy { it.priority }
            HitPolicy.ANY -> {
                val firstOut = matched.first().output(input)
                val allEqual = matched.drop(1).all { it.output(input) == firstOut }
                if (!allEqual) {
                    throw IllegalStateException(
                        "DecisionTable '$name' (ANY) matched rules with conflicting outputs: ${matched.map { it.id }}"
                    )
                }
                matched.first()
            }
            HitPolicy.COLLECT, HitPolicy.RULE_ORDER -> matched.first()
        }

        return DecisionTrace(
            decisionName = name,
            hitPolicy = hitPolicy,
            matchedRuleIds = matched.map { it.id },
            selectedRuleId = selectedRule.id,
            selectedRuleDescription = selectedRule.description,
            usedDefaultFallback = false,
            result = selectedRule.output(input)
        )
    }

    /**
     * Evaluates all matching rules in rule order (primarily for [HitPolicy.COLLECT] / [HitPolicy.RULE_ORDER]).
     */
    fun evaluateAll(input: I): List<O> {
        val matched = rules.filter { it.condition(input) }
        if (matched.isEmpty() && defaultRule != null) {
            return listOf(defaultRule.output(input))
        }
        return matched.map { it.output(input) }
    }

    /**
     * Renders a compact Markdown-like summary of the decision table structure for diagnostics.
     */
    fun describeTable(): String = buildString {
        appendLine("DecisionTable[$name] (HitPolicy=${hitPolicy.name}/${hitPolicy.code})")
        rules.forEachIndexed { idx, rule ->
            appendLine("  ${idx + 1}. [${rule.id}] (p=${rule.priority}) ${rule.description}")
        }
        defaultRule?.let {
            appendLine("  *. [${it.id}] (default) ${it.description}")
        }
    }.trimEnd()
}

/**
 * FEEL-inspired predicate helpers for building readable DMN rule conditions.
 */
object DmnFeel {
    fun <T> any(): (T) -> Boolean = { true }

    fun <T> eq(expected: T): (T) -> Boolean = { it == expected }

    fun <T> neq(unexpected: T): (T) -> Boolean = { it != unexpected }

    fun <T> oneOf(vararg values: T): (T) -> Boolean {
        val set = values.toSet()
        return { it in set }
    }

    fun <T : Comparable<T>> inRange(range: ClosedRange<T>): (T) -> Boolean = { it in range }

    fun <T : Comparable<T>> lt(threshold: T): (T) -> Boolean = { it < threshold }

    fun <T : Comparable<T>> lte(threshold: T): (T) -> Boolean = { it <= threshold }

    fun <T : Comparable<T>> gt(threshold: T): (T) -> Boolean = { it > threshold }

    fun <T : Comparable<T>> gte(threshold: T): (T) -> Boolean = { it >= threshold }
}

@DslMarker
annotation class DmnDslMarker

@DmnDslMarker
class DecisionRuleBuilder<I, O>(
    private val id: String,
    private val description: String,
    private val priority: Int = 0
) {
    private var conditionFn: ((I) -> Boolean)? = null
    private var outputFn: ((I) -> O)? = null

    fun `when`(predicate: (I) -> Boolean) {
        conditionFn = predicate
    }

    fun whenAll(vararg predicates: (I) -> Boolean) {
        conditionFn = { input -> predicates.all { it(input) } }
    }

    fun whenAny(vararg predicates: (I) -> Boolean) {
        conditionFn = { input -> predicates.any { it(input) } }
    }

    fun then(output: (I) -> O) {
        outputFn = output
    }

    fun thenValue(value: O) {
        outputFn = { value }
    }

    fun build(): DecisionRule<I, O> {
        val cond = conditionFn ?: { true }
        val out = outputFn ?: throw IllegalStateException("Rule '$id' must define a 'then' output")
        return DecisionRule(
            id = id,
            description = description,
            priority = priority,
            condition = cond,
            output = out
        )
    }
}

@DmnDslMarker
class DecisionTableBuilder<I, O>(
    private val name: String,
    private val hitPolicy: HitPolicy
) {
    private val rules = mutableListOf<DecisionRule<I, O>>()
    private var defaultRule: DecisionRule<I, O>? = null

    fun rule(
        id: String,
        description: String,
        priority: Int = 0,
        block: DecisionRuleBuilder<I, O>.() -> Unit
    ) {
        val builder = DecisionRuleBuilder<I, O>(id, description, priority)
        builder.block()
        rules.add(builder.build())
    }

    fun default(
        id: String = "DEFAULT",
        description: String = "Default fallback rule",
        output: (I) -> O
    ) {
        defaultRule = DecisionRule(
            id = id,
            description = description,
            priority = Int.MIN_VALUE,
            condition = { true },
            output = output
        )
    }

    fun defaultValue(
        id: String = "DEFAULT",
        description: String = "Default fallback rule",
        value: O
    ) {
        default(id, description) { value }
    }

    fun build(): DecisionTable<I, O> = DecisionTable(
        name = name,
        hitPolicy = hitPolicy,
        rules = rules.toList(),
        defaultRule = defaultRule
    )
}

/**
 * Entry point DSL for defining a DMN [DecisionTable].
 */
fun <I, O> decisionTable(
    name: String,
    hitPolicy: HitPolicy = HitPolicy.FIRST,
    block: DecisionTableBuilder<I, O>.() -> Unit
): DecisionTable<I, O> {
    val builder = DecisionTableBuilder<I, O>(name, hitPolicy)
    builder.block()
    return builder.build()
}

/**
 * Composes two DMN [DecisionTable]s in a Decision Requirements Graph (DRG) pipeline,
 * preserving full evaluation traces across both nodes.
 */
fun <I, M, O> DecisionTable<I, M>.composeWith(
    next: DecisionTable<M, O>
): Judgement<I, DrgEvaluation<O>> = Judgement { input ->
    val firstTrace = this.evaluateWithTrace(input)
    val secondTrace = next.evaluateWithTrace(firstTrace.result)
    DrgEvaluation(
        result = secondTrace.result,
        traces = listOf(firstTrace, secondTrace)
    )
}
