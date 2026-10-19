package au.parmtech.tongcut

import org.junit.Assert.*
import org.junit.Test

class CompositorVideoPlanTest {
    private val bottom = TimelineTrack("bottom", "Bottom PiP", LayerKind.VIDEO, 0)
    private val top = TimelineTrack("top", "Top PiP", LayerKind.VIDEO, 2)

    @Test fun topmostIsInputZeroAndOpaqueBaseIsLast() {
        val graph = LayerTimeline(tracks=listOf(bottom,top), layers=listOf(
            TimelineLayer("b", "bottom", LayerKind.VIDEO, 0, 2_000_000,
                sourceUri="content://bottom"),
            TimelineLayer("t", "top", LayerKind.VIDEO, 1_000_000, 3_000_000,
                sourceUri="content://top")
        ))
        val plan = CompositorVideoPlan.from(graph, 5_000_000)
        assertEquals(listOf("top","bottom"),plan.overlayLanes.map { it.trackId })
        assertEquals(2,plan.backgroundVideoInputIndex)
        assertEquals("t",plan.layerAt(0,2_000_000))
        assertEquals("b",plan.layerAt(1,500_000))
        assertEquals(1f,plan.alphaFor(2,4_999_999),0.0001f)
    }

    @Test fun previewVideoDrawOrderMatchesMedia3CompositorInputOrder() {
        val graph = LayerTimeline(tracks=listOf(bottom,top), layers=listOf(
            TimelineLayer("b", "bottom", LayerKind.VIDEO, 0L, 3_000_000L,
                sourceUri="content://red"),
            TimelineLayer("t", "top", LayerKind.VIDEO, 0L, 3_000_000L,
                sourceUri="content://green")
        ))
        val compositor = CompositorVideoPlan.from(graph, 3_000_000L)
        val paintedTopFirst = compositor.overlayLanes.map { it.trackId }
        val previewBackToFront = VisualLayerOrder.forPreview(graph, 1_000_000L)
            .map { it.trackId }
        assertEquals(paintedTopFirst, previewBackToFront.reversed())
        assertEquals("top", previewBackToFront.last())
    }

    @Test fun gapTransparencyPreventsBlackFrameObscuringBase() {
        val graph = LayerTimeline(tracks=listOf(bottom),layers=listOf(
            TimelineLayer("clip","bottom",LayerKind.VIDEO,2_000_000,1_000_000,
                sourceUri="content://clip")
        ))
        val plan = CompositorVideoPlan.from(graph,5_000_000)
        assertNull(plan.layerAt(0,0))
        assertNull(plan.layerAt(0,1_999_999))
        assertEquals("clip",plan.layerAt(0,2_000_000))
        assertNull(plan.layerAt(0,3_000_000))
        assertEquals(0f,plan.alphaFor(0,4_000_000),0f)
        assertEquals(1f,plan.alphaFor(1,4_000_000),0f)
    }

    @Test fun photoLayersDoNotBecomeVideoCompositorInputs() {
        val picture = TimelineTrack("photo","Photo",LayerKind.IMAGE,1)
        val graph=LayerTimeline(tracks=listOf(bottom,picture),layers=listOf(
            TimelineLayer("b","bottom",LayerKind.VIDEO,0,1_000_000,sourceUri="content://b"),
            TimelineLayer("p","photo",LayerKind.IMAGE,0,1_000_000,sourceUri="content://p")
        ))
        val plan=CompositorVideoPlan.from(graph,3_000_000)
        assertEquals(1,plan.overlayLanes.size)
        assertEquals("bottom",plan.overlayLanes.single().trackId)
    }

    @Test(expected=IllegalArgumentException::class)
    fun videoBeyondProjectRefused() {
        val graph=LayerTimeline(tracks=listOf(bottom),layers=listOf(
            TimelineLayer("b","bottom",LayerKind.VIDEO,4_000_000,3_000_000,
                sourceUri="content://b")
        ))
        CompositorVideoPlan.from(graph,5_000_000)
    }

    @Test(expected=IllegalArgumentException::class)
    fun fractionalMillisecondMediaClipRefused() {
        val graph=LayerTimeline(tracks=listOf(bottom),layers=listOf(
            TimelineLayer("b","bottom",LayerKind.VIDEO,0,1_001_001,
                sourceUri="content://b")
        ))
        CompositorVideoPlan.from(graph,5_000_000)
    }
}
