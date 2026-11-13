package com.example.myasr.shared.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JudgementTest {

    @Test
    fun `Judgement functional interface evaluates input to structured result`() {
        val thresholdJudgement = Judgement<Int, JudgementResult<Boolean>> { value ->
            if (value >= 10) {
                JudgementResult(true, "meets_threshold")
            } else {
                JudgementResult(false, "below_threshold")
            }
        }

        val pass = thresholdJudgement.judge(12)
        assertTrue(pass.decision)
        assertEquals("meets_threshold", pass.reason)

        val fail = thresholdJudgement.judge(5)
        assertFalse(fail.decision)
        assertEquals("below_threshold", fail.reason)
    }

    @Test
    fun `Specification supports and, or, and not combinators`() {
        val isPositive = Specification<Int> { it > 0 }
        val isEven = Specification<Int> { it % 2 == 0 }

        val positiveAndEven = isPositive and isEven
        val positiveOrEven = isPositive or isEven
        val nonPositive = !isPositive

        assertTrue(positiveAndEven.isSatisfiedBy(4))
        assertFalse(positiveAndEven.isSatisfiedBy(3))
        assertFalse(positiveAndEven.isSatisfiedBy(-2))

        assertTrue(positiveOrEven.isSatisfiedBy(3))
        assertTrue(positiveOrEven.isSatisfiedBy(-2))
        assertFalse(positiveOrEven.isSatisfiedBy(-3))

        assertTrue(nonPositive.isSatisfiedBy(0))
        assertTrue(nonPositive.isSatisfiedBy(-1))
        assertFalse(nonPositive.isSatisfiedBy(1))
    }
}
