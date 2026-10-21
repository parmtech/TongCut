package au.parmtech.tongcut

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression tests for cross-file contracts that previously caused 100+
 * cascading unresolved-reference errors in the current development branch.
 *
 * The tests also catch a missing implementation that a plain compile would
 * not detect: VFR reverse output boundaries must agree with the PTS plan.
 */
class DevelopmentBuildContractsTest {
    @Test
    fun polygonMaskLimitMatchesTheEightVertexGpuShader() {
        assertEquals(8, PhotoMatte.MAX_POLYGON_POINTS)
        val default = PhotoEffects(mask = PhotoMask.POLYGON)
        assertEquals(4, default.maskPoints.size)
    }

    @Test
    fun videoReverseDescriptorDelegatesPtsAndFrameDurations() {
        val pts = ReversePtsPlan.fromPresentationTimes(
            longArrayOf(0L, 40_000L, 70_000L),
            0L, 100_000L
        )!!
        val segment = CachedReverseVideoSegment(
            cacheKey = "synthetic-pts-only",
            frameCount = pts.frameCount,
            width = 64,
            height = 64,
            durationUs = pts.durationUs,
            ptsPlan = pts
        )
        assertEquals(70_000L, segment.sourceTimeUs(0))
        assertEquals(40_000L, segment.sourceTimeUs(1))
        assertEquals(0L, segment.sourceTimeUs(2))
        assertEquals(30_000L, segment.frameDurationUs(0))
        assertEquals(30_000L, segment.frameDurationUs(1))
        assertEquals(40_000L, segment.frameDurationUs(2))
        assertEquals(0, segment.frameIndexAtOutputTimeUs(0L))
        assertEquals(0, segment.frameIndexAtOutputTimeUs(29_999L))
        assertEquals(1, segment.frameIndexAtOutputTimeUs(30_000L))
        assertEquals(2, segment.frameIndexAtOutputTimeUs(60_000L))
        assertNull(segment.frameIndexAtOutputTimeUs(100_000L))
    }

    @Test
    fun consecutiveClipBoundariesAssignNextClipWithoutGaps() {
        val clips = listOf(
            DraftClip("content://qa/a", "A", 10_000L, 1_000L, 3_000L),
            DraftClip("content://qa/b", "B", 10_000L, 4_000L, 6_000L)
        )
        val timeline = SequentialTimeline(clips)
        assertEquals(4_000L, timeline.totalMs)
        assertEquals(TimelinePosition(0, 1_999L, 2_999L, 1_999L),
            timeline.pointAt(1_999L))
        assertEquals(TimelinePosition(1, 0L, 4_000L, 2_000L),
            timeline.pointAt(2_000L))
        assertEquals(2_000L, timeline.startOf(1))
    }
}
