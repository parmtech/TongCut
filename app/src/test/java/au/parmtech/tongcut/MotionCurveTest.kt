package au.parmtech.tongcut

import org.junit.Assert.*
import org.junit.Test

class MotionCurveTest {
    private val frames = listOf(
        MotionKeyframe(0, MotionPose(x = 0f, opacity = 0f)),
        MotionKeyframe(1_000_000, MotionPose(x = 1f, opacity = 1f))
    )

    @Test fun interpolationHandlesEndpointsAndHalfway() {
        assertEquals(0f, MotionCurve.at(frames, -1).x, 0.0001f)
        assertEquals(0.5f, MotionCurve.at(frames, 500_000).x, 0.0001f)
        assertEquals(1f, MotionCurve.at(frames, 1_000_000).x, 0.0001f)
        assertEquals(1f, MotionCurve.at(frames, 2_000_000).x, 0.0001f)
        assertEquals(0.5f, MotionCurve.at(frames, 500_000).opacity, 0.0001f)
    }

    @Test fun smoothAndHoldBehavePredictably() {
        val smooth = frames.mapIndexed { i,k ->
            if (i == 0) k.copy(interpolation = MotionInterpolation.SMOOTH) else k
        }
        assertTrue(MotionCurve.at(smooth, 200_000).x < 0.2f)
        assertTrue(MotionCurve.at(smooth, 800_000).x > 0.8f)
        val hold = frames.toMutableList().apply {
            this[0] = this[0].copy(interpolation = MotionInterpolation.HOLD)
        }
        assertEquals(0f, MotionCurve.at(hold, 900_000).x, 0.0001f)
        assertEquals(1f, MotionCurve.at(hold, 1_000_000).x, 0.0001f)
    }

    @Test fun splitMaintainsPoseAtBoundaryAndSubsequentFrames() {
        val (left,right) = MotionCurve.split(frames, 400_000, 1_000_000)
        assertEquals(0.4f, MotionCurve.at(left, 400_000).x, 0.0001f)
        assertEquals(0.4f, MotionCurve.at(right, 0).x, 0.0001f)
        assertEquals(0.7f, MotionCurve.at(right, 300_000).x, 0.0001f)
        assertEquals(600_000L, right.last().timeUs)
    }

    @Test fun upsertOverwritesSameTimeAndPreservesOrder() {
        val changed = MotionCurve.upsert(frames,
            MotionKeyframe(0, MotionPose(x = -1f)), 1_000_000)
        assertEquals(2, changed.size)
        assertEquals(-1f, changed[0].pose.x, 0.0001f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnorderedKeyframes() {
        MotionCurve.validate(frames.reversed(), 1_000_000)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOversizedMotion() {
        MotionPose(scale = 10f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNaNOpacity() {
        MotionPose(opacity = Float.NaN)
    }
}
