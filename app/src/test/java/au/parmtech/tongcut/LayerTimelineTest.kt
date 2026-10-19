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
    @Test fun videoSplitRetainsInterpolatedMotion() {
        val clip = videoClip().copy(motion = listOf(
            MotionKeyframe(0, MotionPose(x = 0f)),
            MotionKeyframe(3_000_000, MotionPose(x = 1f))
        ))
        val graph = LayerTimeline(tracks = listOf(video), layers = listOf(clip))
        val pieces = graph.splitLayer(clip.id, 1_500_000L, "right")
        val left = pieces.layers.first { it.id == clip.id }
        val right = pieces.layers.first { it.id == "right" }
        assertEquals(0.5f, MotionCurve.at(left.motion, 1_500_000L).x, 0.0001f)
        assertEquals(0.5f, MotionCurve.at(right.motion, 0L).x, 0.0001f)
        assertEquals(1f, MotionCurve.at(right.motion, 1_500_000L).x, 0.0001f)
        val trimmed = pieces.resizeLayer("right", 800_000L)
        assertTrue(trimmed.layers.single { it.id == "right" }.motion
            .all { it.timeUs <= 800_000L })
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsKeyframesAfterLayerEnd() {
        videoClip().copy(motion = listOf(MotionKeyframe(
            4_000_000L, MotionPose()
        )))
    }


    @Test fun splittingFastVideoUsesSourceRate() {
        val fast = videoClip().copy(
            durationUs = 2_000_000,
            playbackSpeed = 2f,
            sourceDurationUs = 8_000_000
        )
        val graph = LayerTimeline(tracks=listOf(video),layers=listOf(fast))
        val split = graph.splitLayer(fast.id,1_000_000L,"right-fast")
        val right = split.layers.first { it.id == "right-fast" }
        assertEquals(3_000_000L,right.sourceInUs)
        assertEquals(1_000_000L,right.durationUs)
        assertEquals(2f,right.playbackSpeed)
    }

    @Test(expected = IllegalArgumentException::class)
    fun videoCannotExceedSourceWithSpeedApplied() {
        videoClip().copy(
            durationUs = 2_000_000,
            playbackSpeed = 4f,
            sourceDurationUs = 8_000_000
        )
    }

    @Test fun videoMatteIsValidButNonVisualMediaStillRejectsMatte() {
        val settings = PhotoEffects(PhotoMask.CIRCLE)
        assertEquals(settings, videoClip().copy(photoEffects = settings).photoEffects)
        assertThrows(IllegalArgumentException::class.java) {
            TimelineLayer(
                "audio", "a1", LayerKind.AUDIO, 0L, 1_000_000L,
                sourceUri = "content://audio", photoEffects = settings
            )
        }
    }

    @Test fun rampCutNeverChangesProjectLengthDueToMillisecondRounding() {
        val ramp = listOf(SpeedKeyframe(0L, 0.7f))
        val duration = SpeedRamp.durationUs(2_000_000L,ramp)
        assertEquals(2_857_000L,duration)
        val first = TimelineLayer(
            "original","v2",LayerKind.VIDEO,0,duration,
            sourceUri="content://video", sourceInUs=0,
            sourceDurationUs=4_000_000,
            sourceSpanUs=2_000_000, speedRamp=ramp
        )
        val next = TimelineLayer(
            "next","v2",LayerKind.VIDEO,duration,1_000_000,
            sourceUri="content://next",sourceDurationUs=1_000_000
        )
        val graph = LayerTimeline(tracks=listOf(video),layers=listOf(first,next))
        val split = graph.splitLayer(first.id,1_429_000L,"right")
        val left = split.layers.first { it.id == "original" }
        val right = split.layers.first { it.id == "right" }
        val neighbour = split.layers.first { it.id == "next" }
        assertEquals(duration,left.durationUs+right.durationUs)
        assertEquals(left.endUs,right.startUs)
        assertEquals(right.endUs,neighbour.startUs)
        assertEquals(first.sourceInUs+2_000_000L,right.sourceAt(right.endUs))
        assertEquals(1_000_000L,right.sourceInUs)
    }

    @Test fun splittingFadedVideoKeepsOpacityContinuous() {
        val faded = TimelineLayer("faded",video.id,LayerKind.VIDEO,0L,5_000_000L,
            sourceUri="content://video",sourceDurationUs=5_000_000L,
            fade=LayerFade(1_000_000,2_000_000))
        val graph = LayerTimeline(tracks=listOf(video),layers=listOf(faded))
        val result = graph.splitLayer(faded.id,4_000_000L,"second")
        val left = result.layers.single { it.id == "faded" }
        val right = result.layers.single { it.id == "second" }
        val before = left.fade.alphaAt(left.durationUs,left.durationUs)
        val after = right.fade.alphaAt(0L,right.durationUs)
        assertEquals(before,after,0.0001f)
        assertEquals(0.5f,before,0.0001f)
    }

}