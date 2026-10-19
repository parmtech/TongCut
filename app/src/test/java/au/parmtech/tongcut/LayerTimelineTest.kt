package au.parmtech.tongcut

import org.junit.Assert.*
import org.junit.Test

class LayerTimelineTest {
    private val rate = TimelineRate(30_000, 1001)
    private val video = TimelineTrack("v2", "Video overlay", LayerKind.VIDEO, 1)
    private val video2 = TimelineTrack("v3", "Second overlay", LayerKind.VIDEO, 2)
    private val audio = TimelineTrack("a1", "Voiceover", LayerKind.AUDIO, 3)
    private val title = TimelineTrack("t1", "Title", LayerKind.TEXT, 4)

    private fun videoClip(id: String = "v-clip", start: Long = 0, duration: Long = 3_000_000) =
        TimelineLayer(
            id = id, trackId = "v2", kind = LayerKind.VIDEO,
            startUs = start, durationUs = duration, sourceUri = "content://video",
            sourceInUs = 1_000_000, sourceDurationUs = 8_000_000
        )

    @Test fun timebaseSupports2997AndExactRoundTrip() {
        assertEquals(33_367, rate.atFrame(1))
        assertEquals(1L, rate.frameAt(rate.atFrame(1)))
        assertEquals(1_001_000L, rate.atFrame(30))
        assertEquals(30L, rate.frameAt(1_001_000))
        assertEquals(33_367L, rate.nearestFrameUs(33_000))
        for (frame in 0L..1000L) assertEquals(frame, rate.frameAt(rate.atFrame(frame)))
    }

    @Test fun clipsCanOverlapOnDifferentLayersAndHaveZOrder() {
        val item = videoClip()
        val second = item.copy(id = "v-second", trackId = "v3")
        val timeline = LayerTimeline(rate, listOf(video2, video), listOf(item, second))
        assertEquals(listOf("v-clip", "v-second"), timeline.activeAt(1_000_000).map { it.id })
        assertEquals(3_000_000, timeline.endUs)
        assertTrue(timeline.hasUnrenderedLayers)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOverlapOnSameLayer() {
        LayerTimeline(tracks = listOf(video), layers = listOf(
            videoClip(), videoClip("second", 2_000_000, 2_000_000)
        ))
    }

    @Test fun moveSplitResizeAndLockAreNonDestructive() {
        val first = videoClip()
        val timeline = LayerTimeline(tracks = listOf(video,video2),layers=listOf(first))
        val moved = timeline.moveLayer(first.id, "v3", 2_000_000)
        assertEquals("v2", timeline.layers.single().trackId)
        assertEquals(2_000_000, moved.layers.single().startUs)
        val split = moved.splitLayer(first.id, 3_000_000, "right")
        assertEquals(2, split.layers.size)
        val right = split.layers.single { it.id == "right" }
        assertEquals(2_000_000, right.sourceInUs)
        assertEquals(3_000_000, right.startUs)
        assertEquals(2_000_000, right.durationUs)
        val resized = split.resizeLayer("right", 1_000_000)
        assertEquals(1_000_000, resized.layers.single { it.id == "right" }.durationUs)
        val locked = resized.setTrackLocked("v3", true)
        assertThrows(IllegalArgumentException::class.java) { locked.removeLayer("right") }
    }

    @Test fun textAndAudioAreIndependentLayers() {
        val timeline = LayerTimeline(tracks = listOf(audio,title))
            .addLayer(TimelineLayer(
                "text-1", "t1", LayerKind.TEXT, 1_000_000, 2_000_000, text = "Hello"
            ))
            .addLayer(TimelineLayer(
                "sound-1", "a1", LayerKind.AUDIO, 0, 5_000_000,
                sourceUri = "content://audio", sourceDurationUs = 5_000_000
            ))
        assertEquals(listOf("sound-1"),timeline.activeAt(0).map { it.id })
        assertEquals(listOf("sound-1","text-1"),timeline.activeAt(2_000_000).map { it.id })
        assertEquals(emptyList<String>(),timeline.activeAt(5_000_000).map { it.id })
        assertEquals(5_000_000,timeline.endUs)
    }

    @Test(expected = IllegalArgumentException::class)
    fun preventsAudioClipOnTextTrack() {
        LayerTimeline(tracks=listOf(title)).addLayer(TimelineLayer(
            "wrong", "t1", LayerKind.AUDIO, 0, 1_000_000, sourceUri="content://audio"
        ))
    }

    @Test(expected = IllegalArgumentException::class)
    fun preventsSourceOverrun() {
        TimelineLayer("v", "v2", LayerKind.VIDEO, 0, 5_000_000,
            sourceUri="content://video",sourceInUs=2_000_000,sourceDurationUs=6_000_000)
    }

    @Test fun emptyTimelineNeedsNoRenderer() {
        val timeline = LayerTimeline()
        assertFalse(timeline.hasUnrenderedLayers)
        assertEquals(0L, timeline.endUs)
        assertEquals(emptyList<TimelineLayer>(), timeline.activeAt(0))
    }
}
