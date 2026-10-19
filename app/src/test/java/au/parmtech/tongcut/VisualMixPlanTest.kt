package au.parmtech.tongcut

import org.junit.Assert.*
import org.junit.Test

class VisualMixPlanTest {
    private val video = TimelineTrack("v2","Overlay",LayerKind.VIDEO,1)
    private val image = TimelineTrack("i1","Photos",LayerKind.IMAGE,2)
    private val overlay = TimelineLayer("v","v2",LayerKind.VIDEO,2_000_000,4_000_000,
        sourceUri="content://video")
    private val photo = TimelineLayer("i","i1",LayerKind.IMAGE,1_000_000,3_000_000,
        sourceUri="content://photo")
    @Test fun schedulesVisualClipsAndSilenceGaps() {
        val graph=LayerTimeline(tracks=listOf(video,image),layers=listOf(overlay,photo))
        val plans=VisualMixPlan.from(graph,10_000_000)
        assertEquals(2,plans.size)
        assertEquals(2_000_000L,(plans[0].items.first() as VisualPart.Gap).durationUs)
        assertEquals(4_000_000L,(plans[0].items[1] as VisualPart.Clip).durationUs)
        assertEquals(LayerKind.IMAGE,(plans[1].items.last() as VisualPart.Clip).kind)
    }
    @Test fun hiddenPhotoDoesNotExport() {
        val graph=LayerTimeline(tracks=listOf(video,image.copy(hidden=true)),
            layers=listOf(overlay,photo))
        assertEquals(1,VisualMixPlan.from(graph,10_000_000).size)
    }
    @Test(expected=IllegalArgumentException::class)
    fun rejectsVisualLayerBeyondVideoEnd() {
        val late=overlay.copy(startUs=8_000_000)
        VisualMixPlan.from(LayerTimeline(tracks=listOf(video),layers=listOf(late)),10_000_000)
    }

    @Test fun speedFlowsToOverlayClipPlan() {
        val graph = LayerTimeline(tracks=listOf(video),layers=listOf(
            overlay.copy(durationUs=2_000_000,playbackSpeed=2f)
        ))
        val part = VisualMixPlan.from(graph,10_000_000)
            .single().items.filterIsInstance<VisualPart.Clip>().single()
        assertEquals(2f,part.playbackSpeed)
        assertEquals(2_000_000L,part.durationUs)
    }

}
