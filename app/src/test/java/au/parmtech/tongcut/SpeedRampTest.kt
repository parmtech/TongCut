package au.parmtech.tongcut

import org.junit.Assert.*
import org.junit.Test

class SpeedRampTest {
    private val ramp = listOf(
        SpeedKeyframe(0L, 0.5f),
        SpeedKeyframe(2_000_000L, 2f),
        SpeedKeyframe(6_000_000L, 1f)
    )
    private val span = 8_000_000L

    @Test fun mapsSourceToOutputAndBack() {
        assertEquals(8_000_000L, SpeedRamp.durationUs(span, ramp))
        assertEquals(4_000_000L, SpeedRamp.outputAtSource(2_000_000, span, ramp))
        assertEquals(6_000_000L, SpeedRamp.outputAtSource(6_000_000, span, ramp))
        assertEquals(7_000_000L, SpeedRamp.outputAtSource(7_000_000, span, ramp))
        assertEquals(2_000_000L, SpeedRamp.sourceAtOutput(4_000_000, span, ramp))
        assertEquals(4_000_000L, SpeedRamp.sourceAtOutput(5_000_000, span, ramp))
        assertEquals(8_000_000L, SpeedRamp.sourceAtOutput(8_000_000, span, ramp))
    }

    @Test fun speedProviderBoundariesAreSourceRelative() {
        assertEquals(0.5f, SpeedRamp.speedAt(1_999_999L, ramp))
        assertEquals(2f, SpeedRamp.speedAt(2_000_000L, ramp))
        assertEquals(2_000_000L, SpeedRamp.nextChangeAfter(0L, ramp))
        assertEquals(6_000_000L, SpeedRamp.nextChangeAfter(2_000_000L, ramp))
        assertNull(SpeedRamp.nextChangeAfter(6_000_000L, ramp))
    }

    @Test fun upsertSplittingAndRecombiningKeepsDuration() {
        val (left, right) = SpeedRamp.splitAtSource(span, ramp, 4_000_000L)
        assertEquals(5_000_000L, SpeedRamp.durationUs(4_000_000L, left))
        assertEquals(3_000_000L, SpeedRamp.durationUs(4_000_000L, right))
        assertEquals(2f, right.first().speed)
        val inserted = SpeedRamp.upsert(span, ramp, SpeedKeyframe(3_000_000L, 1f))
        assertEquals(4, inserted.size)
        assertEquals(1f, SpeedRamp.speedAt(3_000_000L, inserted))
        assertEquals(3, ramp.size)
    }

    @Test fun outputTimeMappingMonotonicThroughAllSegments() {
        var previous = -1L
        for (tick in 0L..8000L) {
            val source = SpeedRamp.sourceAtOutput(tick * 1000L, span, ramp)
            assertTrue(source >= previous)
            previous = source
            val mapped = SpeedRamp.outputAtSource(source, span, ramp)
            assertTrue(kotlin.math.abs(mapped - tick * 1000L) <= 3L)
        }
    }

    @Test(expected=IllegalArgumentException::class)
    fun rejectsCurveWithoutZero() {
        SpeedRamp.durationUs(3_000_000L, listOf(SpeedKeyframe(1_000L,1f)))
    }

    @Test(expected=IllegalArgumentException::class)
    fun rejectsDuplicateKeys() {
        SpeedRamp.durationUs(3_000_000L, listOf(
            SpeedKeyframe(0L,1f), SpeedKeyframe(0L,2f)
        ))
    }

    @Test(expected=IllegalArgumentException::class)
    fun rejectsOutOfBoundsKeyframe() {
        SpeedRamp.durationUs(1_000_000L, listOf(
            SpeedKeyframe(0L,1f), SpeedKeyframe(1_000_000L,2f)
        ))
    }

    @Test(expected=IllegalArgumentException::class)
    fun rejectsUnalignedSourceTime() {
        SpeedKeyframe(1_001L,1f)
    }
}
