package au.parmtech.tongcut

import org.junit.Assert.*
import org.junit.Test

class AudioMixPlanTest {
    private val videoUs = 10_000_000L
    private val lane = TimelineTrack("a", "Music", LayerKind.AUDIO, 0)
    private fun clip(id: String, at: Long, duration: Long, inUs: Long = 0) =
        TimelineLayer(
            id = id, trackId = "a", kind = LayerKind.AUDIO,
            startUs = at, durationUs = duration,
            sourceUri = "content://audio/$id", sourceInUs = inUs,
            sourceDurationUs = 20_000_000L
        )

    @Test fun gapsAndClipsCoverEntireVideo() {
        val data = LayerTimeline(tracks = listOf(lane),
            layers = listOf(clip("one", 2_000_000, 3_000_000, 500_000),
                clip("two", 7_000_000, 1_000_000, 2_000_000)))
        val plan = AudioMixPlan.from(data, videoUs).single()
        assertEquals(videoUs, plan.totalUs)
        assertEquals(listOf(2_000_000L,3_000_000L,2_000_000L,1_000_000L,2_000_000L),
            plan.parts.map { it.durationUs })
        assertEquals(500_000L, (plan.parts[1] as AudioPart.Source).sourceInUs)
        assertEquals("two", (plan.parts[3] as AudioPart.Source).layerId)
    }

    @Test fun mutedTrackDoesNotRenderAndHiddenStillMixes() {
        val base = LayerTimeline(tracks=listOf(lane), layers=listOf(clip("a",0,videoUs)))
        assertTrue(AudioMixPlan.from(base.copy(tracks=listOf(lane.copy(muted=true))),videoUs).isEmpty())
        assertEquals(1,AudioMixPlan.from(base.copy(tracks=listOf(lane.copy(hidden=true))),videoUs).size)
    }

    @Test fun multipleTracksMixWithoutOverlappingInsideLane() {
        val second = TimelineTrack("b", "Voiceover", LayerKind.AUDIO, 1)
        val firstClip = clip("music",0,videoUs)
        val secondClip = clip("voice",1_000_000,3_000_000).copy(trackId="b")
        val graph = LayerTimeline(tracks=listOf(lane,second), layers=listOf(firstClip,secondClip))
        val plan = AudioMixPlan.from(graph,videoUs)
        assertEquals(2,plan.size)
        assertEquals(videoUs,plan[0].totalUs)
        assertEquals(videoUs,plan[1].totalUs)
    }

    @Test fun audioEqualizerFlowsIntoTheLaneRenderPlan() {
        val eq = AudioEqualizer(bassDb = 4f, midDb = -2f, trebleDb = 6f)
        val audioTrack = lane.copy(equalizer = eq, stereoBalance = 0.35f)
        val graph = LayerTimeline(tracks = listOf(audioTrack),
            layers = listOf(clip("eq", 0L, videoUs)))
        val plan = AudioMixPlan.from(graph, videoUs).single()
        assertEquals(eq, plan.equalizer)
        assertEquals(0.35f, plan.stereoBalance)
    }

    @Test(expected=IllegalArgumentException::class)
    fun overshootingLayersAreRejected() {
        AudioMixPlan.from(
            LayerTimeline(tracks=listOf(lane),layers=listOf(clip("overshoot",9_000_000,2_000_000))),
            videoUs
        )
    }

    @Test
    fun visualOverlayDoesNotAddAudioLane() {
        val overlayTrack = TimelineTrack("video", "Overlay", LayerKind.VIDEO, 1)
        val extra = TimelineLayer("v", "video", LayerKind.VIDEO, 0, 3_000_000, sourceUri="content://v")
        AudioMixPlan.from(LayerTimeline(tracks=listOf(lane, overlayTrack), layers=listOf(extra)),videoUs)
    }

    @Test fun noAudioKeepsVideoUntouched() {
        assertEquals(emptyList<AudioLanePlan>(), AudioMixPlan.from(LayerTimeline(),videoUs))
    }

    @Test fun gainTransfersToSequencePlan() {
        val data = LayerTimeline(
            tracks=listOf(lane.copy(gain = 0.5f)),
            layers=listOf(clip("voice",0,1_000_000))
        )
        assertEquals(0.5f, AudioMixPlan.from(data, videoUs).single().gain)
    }

    @Test(expected=IllegalArgumentException::class)
    fun invalidGainRejected() {
        lane.copy(gain = Float.NaN)
    }
}
