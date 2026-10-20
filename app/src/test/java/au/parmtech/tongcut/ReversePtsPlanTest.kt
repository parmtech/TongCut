package au.parmtech.tongcut

import org.junit.Assert.*
import org.junit.Test

class ReversePtsPlanTest {
    @Test fun reversesObservedVfrTimestampsInsideHalfOpenTrim() {
        val plan = ReversePtsPlan.fromPresentationTimes(
            longArrayOf(0L, 40_000L, 70_000L, 120_000L), 35_000L, 100_000L
        )!!
        assertEquals(2, plan.frameCount)
        assertEquals(70_000L, plan.sourceTimeUs(0))
        assertEquals(40_000L, plan.sourceTimeUs(1))
    }

    @Test fun excludesExactExclusiveOutTimestampAndEmptyRanges() {
        assertNull(ReversePtsPlan.fromPresentationTimes(longArrayOf(0L, 40_000L), 40_001L, 50_000L))
        assertNull(ReversePtsPlan.fromPresentationTimes(longArrayOf(0L, 40_000L), 40_000L, 40_000L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDuplicatePtsInsteadOfProducingDuplicateFrames() {
        ReversePtsPlan.fromPresentationTimes(longArrayOf(0L, 40_000L, 40_000L), 0L, 50_000L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonMonotonicPts() {
        ReversePtsPlan.fromPresentationTimes(longArrayOf(0L, 40_000L, 30_000L), 0L, 50_000L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidTrim() {
        ReversePtsPlan.fromPresentationTimes(longArrayOf(0L), 50L, 40L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOutOfRangeOutputOffset() {
        ReversePtsPlan.fromPresentationTimes(longArrayOf(0L), 0L, 1L)!!.sourceTimeUs(1)
    }
}

