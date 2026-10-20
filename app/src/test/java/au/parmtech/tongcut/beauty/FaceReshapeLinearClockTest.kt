package au.parmtech.tongcut.beauty

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FaceReshapeLinearClockTest {
    @Test fun mapsTrimmedClipAndRejectsOutsideIntervals() {
        val clock = FaceReshapeLinearClock(
            sourceInUs = 2_000_000L, sourceOutUsExclusive = 3_000_000L
        )
        assertEquals(2_000_000L, clock.at(0L))
        assertEquals(2_250_000L, clock.at(250_000L))
        assertEquals(2_999_999L, clock.at(999_999L))
        assertNull(clock.at(-1L))
        assertNull(clock.at(1_000_000L))
    }

    @Test fun speedAndPresentationOriginPreserveSourceTiming() {
        val clock = FaceReshapeLinearClock(
            sourceInUs = 100_000L, sourceOutUsExclusive = 1_100_000L,
            speed = 2.0, presentationOriginUs = 50_000L
        )
        assertNull(clock.at(49_999L))
        assertEquals(100_000L, clock.at(50_000L))
        assertEquals(600_000L, clock.at(300_000L))
        assertNull(clock.at(550_000L))
    }

    @Test fun reversesFromLastValidSourceMicrosecond() {
        val clock = FaceReshapeLinearClock(
            sourceInUs = 1_000L, sourceOutUsExclusive = 11_000L,
            speed = 1.0, reverse = true
        )
        assertEquals(10_999L, clock.at(0L))
        assertEquals(10_000L, clock.at(999L))
        assertEquals(1_000L, clock.at(9_999L))
        assertNull(clock.at(10_000L))
    }

    @Test fun slowMotionMapsToSmallerSourceAdvance() {
        val clock = FaceReshapeLinearClock(
            sourceInUs = 0L, sourceOutUsExclusive = 100_000L, speed = 0.25
        )
        assertEquals(25_000L, clock.at(100_000L))
        assertEquals(99_999L, clock.at(399_999L))
        assertNull(clock.at(400_000L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyTrimRangeIsNotAccepted() {
        FaceReshapeLinearClock(1_000L, 1_000L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonFiniteSpeedIsNotAccepted() {
        FaceReshapeLinearClock(0L, 5_000L, speed = Double.NaN)
    }
}
