package com.example.myasr.shared.domain.dmn

import com.example.myasr.shared.domain.Judgement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DmnEngineTest {

    @Test
    fun `UNIQUE hit policy succeeds on single match and throws on overlap`() {
        val table = decisionTable<Int, String>(
            name = "UniqueTierTable",
            hitPolicy = HitPolicy.UNIQUE
        ) {
            rule("R1", "Negative") {
                `when`(DmnFeel.lt(0))
                thenValue("NEGATIVE")
            }
            rule("R2", "Zero") {
                `when`(DmnFeel.eq(0))
                thenValue("ZERO")
            }
            rule("R3", "Positive") {
                `when`(DmnFeel.gt(0))
                thenValue("POSITIVE")
            }
        }

        val judgement: Judgement<Int, String> = table
        assertEquals("NEGATIVE", judgement.judge(-5))
        assertEquals("ZERO", table.evaluate(0))
        assertEquals("POSITIVE", table.evaluate(42))

        val overlapping = decisionTable<Int, String>(
            name = "OverlappingUnique",
            hitPolicy = HitPolicy.UNIQUE
        ) {
            rule("R1", "GTE 0") {
                `when`(DmnFeel.gte(0))
                thenValue("A")
            }
            rule("R2", "LTE 10") {
                `when`(DmnFeel.lte(10))
                thenValue("B")
            }
        }

        var failed = false
        try {
            overlapping.evaluate(5)
        } catch (e: IllegalStateException) {
            failed = true
            assertTrue(e.message!!.contains("UNIQUE"))
        }
        assertTrue(failed)
    }

    @Test
    fun `FIRST hit policy selects first matching rule and falls back to default`() {
        val table = decisionTable<Int, String>(
            name = "FirstMatchTable",
            hitPolicy = HitPolicy.FIRST
        ) {
            rule("R1", "Large") {
                `when`(DmnFeel.gte(100))
                thenValue("LARGE")
            }
            rule("R2", "Medium") {
                `when`(DmnFeel.gte(10))
                thenValue("MEDIUM")
            }
            defaultValue("DEF", "Small", "SMALL")
        }

        val traceLarge = table.evaluateWithTrace(150)
        assertEquals("LARGE", traceLarge.result)
        assertEquals("R1", traceLarge.selectedRuleId)
        assertEquals(listOf("R1", "R2"), traceLarge.matchedRuleIds)
        assertFalse(traceLarge.usedDefaultFallback)

        val traceDefault = table.evaluateWithTrace(3)
        assertEquals("SMALL", traceDefault.result)
        assertEquals("DEF", traceDefault.selectedRuleId)
        assertTrue(traceDefault.matchedRuleIds.isEmpty())
        assertTrue(traceDefault.usedDefaultFallback)
    }

    @Test
    fun `PRIORITY hit policy selects matching rule with highest priority`() {
        val table = decisionTable<Int, String>(
            name = "PriorityTable",
            hitPolicy = HitPolicy.PRIORITY
        ) {
            rule("LOW", "Low priority", priority = 10) {
                `when`(DmnFeel.inRange(1..100))
                thenValue("LOW_OUT")
            }
            rule("HIGH", "High priority", priority = 90) {
                `when`(DmnFeel.inRange(50..100))
                thenValue("HIGH_OUT")
            }
            rule("MID", "Medium priority", priority = 50) {
                `when`(DmnFeel.oneOf(60, 70))
                thenValue("MID_OUT")
            }
        }

        val trace = table.evaluateWithTrace(60)
        assertEquals("HIGH_OUT", trace.result)
        assertEquals("HIGH", trace.selectedRuleId)
        assertEquals(listOf("LOW", "HIGH", "MID"), trace.matchedRuleIds)
    }

    @Test
    fun `ANY hit policy allows multiple matching rules with identical output and rejects conflicts`() {
        val consistent = decisionTable<Int, String>(
            name = "AnyConsistent",
            hitPolicy = HitPolicy.ANY
        ) {
            rule("R1", "Even") {
                `when` { it % 2 == 0 }
                thenValue("ALLOW")
            }
            rule("R2", "Positive") {
                `when`(DmnFeel.gt(0))
                thenValue("ALLOW")
            }
        }
        assertEquals("ALLOW", consistent.evaluate(4))

        val conflicting = decisionTable<Int, String>(
            name = "AnyConflicting",
            hitPolicy = HitPolicy.ANY
        ) {
            rule("R1", "Even") {
                `when` { it % 2 == 0 }
                thenValue("EVEN")
            }
            rule("R2", "Positive") {
                `when`(DmnFeel.gt(0))
                thenValue("POSITIVE")
            }
        }
        var failed = false
        try {
            conflicting.evaluate(4)
        } catch (e: IllegalStateException) {
            failed = true
            assertTrue(e.message!!.contains("ANY"))
        }
        assertTrue(failed)
    }

    @Test
    fun `COLLECT hit policy gathers all matching outputs in rule order`() {
        val table = decisionTable<Int, String>(
            name = "CollectTags",
            hitPolicy = HitPolicy.COLLECT
        ) {
            rule("R1", "Positive") {
                `when`(DmnFeel.gt(0))
                thenValue("positive")
            }
            rule("R2", "Even") {
                `when` { it % 2 == 0 }
                thenValue("even")
            }
            rule("R3", "Small") {
                `when`(DmnFeel.lt(10))
                thenValue("single_digit")
            }
        }

        assertEquals(listOf("positive", "even", "single_digit"), table.evaluateAll(4))
        assertEquals(listOf("positive"), table.evaluateAll(15))
    }

    @Test
    fun `composeWith links two DecisionTables into an auditable DRG pipeline`() {
        val stage1 = decisionTable<Int, String>(name = "ClassifyScore", hitPolicy = HitPolicy.FIRST) {
            rule("S1", "High") {
                `when`(DmnFeel.gte(80))
                thenValue("HIGH")
            }
            defaultValue("S_DEF", "Low", "LOW")
        }
        val stage2 = decisionTable<String, Boolean>(name = "ApproveReward", hitPolicy = HitPolicy.UNIQUE) {
            rule("A1", "Approve HIGH") {
                `when`(DmnFeel.eq("HIGH"))
                thenValue(true)
            }
            rule("A2", "Reject LOW") {
                `when`(DmnFeel.neq("HIGH"))
                thenValue(false)
            }
        }

        val drg = stage1.composeWith(stage2)
        val eval = drg.judge(85)
        assertTrue(eval.result)
        assertEquals(2, eval.traces.size)
        assertEquals("ClassifyScore", eval.traces[0].decisionName)
        assertEquals("S1", eval.traces[0].selectedRuleId)
        assertEquals("ApproveReward", eval.traces[1].decisionName)
        assertEquals("A1", eval.traces[1].selectedRuleId)

        assertTrue(stage1.describeTable().contains("DecisionTable[ClassifyScore]"))
    }
}
