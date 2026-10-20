Warning: fs was declared with const; use let for reassignable variables.
Warning: s was declared with const; use let for reassignable variables.
package au.parmtech.tongcut

import org.junit.Assert.*
import org.junit.Test

class SequentialTimelineTest {
    private val first = DraftClip("content://media/first", "First", 15_000, 1_000, 8_000)
    private val second = DraftClip("content://media/second", "Second", 20_000, 5_000, 9_000)
    private val third = DraftClip("content://media/third", "Third", 12_000, 3_000, 12_000)

    @Test fun emptyTimelineHasNoSeekPoint() {
        val timeline = SequentialTimeline(emptyList())
        assertEquals(0L, timeline.totalMs)
        assertNull(timeline.pointAt(0))
    }

    @Test fun convertsTimelinePositionsAndSourceTrims() {
        val timeline = SequentialTimeline(listOf(first, second, third))
        assertEquals(20_000L, timeline.totalMs)
        assertEquals(0L, timeline.startOf(0))
        assertEquals(7_000L, timeline.startOf(1))
        assertEquals(11_000L, timeline.startOf(2))
        assertEquals(TimelinePosition(0, 0, 1_000, 0), timeline.pointAt(-200))
        assertEquals(TimelinePosition(0, 6_999, 7_999, 6_999), timeline.pointAt(6_999))
        assertEquals(TimelinePosition(1, 0, 5_000, 7_000), timeline.pointAt(7_000))
        assertEquals(TimelinePosition(1, 500, 5_500, 7_500), timeline.pointAt(7_500))
        assertEquals(TimelinePosition(2, 0, 3_000, 11_000), timeline.pointAt(11_000))
        assertEquals(TimelinePosition(2, 9_000, 12_000, 20_000), timeline.pointAt(Long.MAX_VALUE))
        assertEquals(9_000L, timeline.timeOf(1, 2_000))
        assertEquals(7_000L, timeline.sourceTimeOf(1, 1_000))
        assertEquals(11_000L, timeline.sourceTimeOf(1, 50_000))
    }

    @Test fun splitKeepsTimelineLengthAndBoundaryOwnership() {
        val before = EditorDraft(clips = listOf(first, second), selectedIndex = 0)
        val after = before.splitSelected(4_000)!!
        assertEquals(3, after.clips.size)
        assertEquals(SequentialTimeline(before.clips).totalMs, SequentialTimeline(after.clips).totalMs)
        val point = SequentialTimeline(after.clips).pointAt(3_000)!!
        assertEquals(1, point.clipIndex)
        assertEquals(4_000L, point.sourceMs)
    }

    @Test fun sourcePositionRoundTrip() {
        val timeline = SequentialTimeline(listOf(first, second, third))
        for (index in timeline.clips.indices) {
            val clip = timeline.clips[index]
            val local = clip.editedDurationMs / 2
            val point = timeline.pointAt(timeline.timeOf(index, local))!!
            assertEquals(index, point.clipIndex)
            assertEquals(clip.inMs + local, point.sourceMs)
        }
    }

    @Test fun reverseTargetFollowsPlayheadAcrossClipsAndUsesHalfOpenBoundaries() {
        val forward = first
        val reverse = second.copy(reverseVideo = true)
        val timeline = SequentialTimeline(listOf(forward, reverse, third))

        assertNull(timeline.reverseTargetAt(6_999))
        val atReverseStart = timeline.reverseTargetAt(7_000)!!
        assertEquals(1, atReverseStart.clipIndex)
        assertEquals(7_000L, atReverseStart.startMs)
        assertEquals(0L, atReverseStart.localMs)

        val insideReverse = timeline.reverseTargetAt(10_999)!!
        assertEquals(1, insideReverse.clipIndex)
        assertEquals(3_999L, insideReverse.localMs)
        assertNull(timeline.reverseTargetAt(11_000))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOverflow() {
        val huge = DraftClip("content://big", "Big", Long.MAX_VALUE - 99)
        SequentialTimeline(listOf(huge, huge))
    }
}
