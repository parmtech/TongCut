package au.parmtech.tongcut

import org.junit.Assert.*
import org.junit.Test

class ReverseFramePlanTest {
    @Test fun halfOpenTrimReversesOnlyIncludedFrameTimestamps() {
        val plan = ReverseFramePlan.fromTrim(100_000, 300_000, TimelineRate(10, 1))!!
        assertEquals(2L, plan.frameCount)
        assertEquals(200_000L, plan.sourceTimeUs(0))
        assertEquals(100_000L, plan.sourceTimeUs(1))
    }

    @Test fun rationalRateUsesExactFloorAndCeilingBoundaries() {
        val rate = TimelineRate(30_000, 1_001)
        val plan = ReverseFramePlan.fromTrim(33_367, 100_101, rate)!!
        assertEquals(3L, plan.frameCount)
        assertEquals(100_100L, plan.sourceTimeUs(0))
        assertEquals(66_734L, plan.sourceTimeUs(1))
        assertEquals(33_367L, plan.sourceTimeUs(2))
    }

    @Test fun fractionalFrameTimestampAtTrimEdgeIsIncludedConsistently() {
        val rate = TimelineRate(30_000, 1_001)
        val plan = ReverseFramePlan.fromTrim(33_367, 33_368, rate)!!
        assertEquals(1L, plan.frameCount)
        assertEquals(33_367L, plan.sourceTimeUs(0))
    }

    @Test fun emptyTrimOrTrimBetweenTimestampsHasNoFrames() {
        assertNull(ReverseFramePlan.fromTrim(100, 100, TimelineRate(30, 1)))
        assertNull(ReverseFramePlan.fromTrim(1, 2, TimelineRate(30, 1)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidTrim() {
        ReverseFramePlan.fromTrim(20, 10, TimelineRate())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOversizedFramePlan() {
        ReverseFramePlan.fromTrim(0, Long.MAX_VALUE, TimelineRate(240, 1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOutOfRangeOutputFrame() {
        ReverseFramePlan.fromTrim(0, 200_000, TimelineRate(10, 1))!!.sourceTimeUs(2)
    }
}
