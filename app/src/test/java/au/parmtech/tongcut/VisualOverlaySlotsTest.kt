package au.parmtech.tongcut

import org.junit.Assert.*
import org.junit.Test

class VisualOverlaySlotsTest {
    private fun graph(): LayerTimeline {
        val video = TimelineTrack("vid", "Video", LayerKind.VIDEO, 0)
        val photo = TimelineTrack("photo", "Photo", LayerKind.IMAGE, 1)
        return LayerTimeline(tracks=listOf(video,photo),layers=listOf(
            TimelineLayer("v","vid",LayerKind.VIDEO,0,1_000_000,sourceUri="content://v"),
            TimelineLayer("p","photo",LayerKind.IMAGE,2_000_000,1_000_000,
                sourceUri="content://p")
        ))
    }

    @Test fun videoAndPhotoHaveStableSlotsAcrossDifferentActiveTimes() {
        val project=graph()
        val video=project.layers[0]
        val photo=project.layers[1]
        assertEquals(0,VisualOverlaySlots.slotFor(project,video))
        assertEquals(1,VisualOverlaySlots.slotFor(project,photo))
        assertEquals(0,VisualOverlaySlots.slotFor(project,video))
        assertEquals(-0.55f,VisualOverlaySlots.position(project,photo).first,0.0001f)
    }

    @Test fun freezePhotoCanOccupyItsOriginalVideoSlot() {
        val project=graph()
        val frozen=project.layers[1].copy(visualSlot=0)
        assertEquals(0,VisualOverlaySlots.slotFor(project,frozen))
        assertEquals(VisualOverlaySlots.position(project,project.layers[0]),
            VisualOverlaySlots.position(project,frozen))
    }

    @Test fun slotStoredInProjectAndRestoredFromSchemaSeven() {
        val project=graph()
        val frozen=project.copy(layers=project.layers.map { if(it.kind==LayerKind.IMAGE)
            it.copy(visualSlot=0) else it })
        val encoded=LayerTimelineCodec.encode(frozen)
        assertEquals(7,encoded.getInt("schema"))
        assertEquals(frozen,LayerTimelineCodec.decode(encoded))
        val legacy=LayerTimelineCodec.encode(project)
        legacy.put("schema",6)
        legacy.getJSONArray("layers").getJSONObject(1).remove("visualSlot")
        assertNull(LayerTimelineCodec.decode(legacy).layers[1].visualSlot)
    }

    @Test(expected=IllegalArgumentException::class)
    fun negativeSlotIsInvalid() {
        graph().layers[1].copy(visualSlot=-1)
    }

    @Test(expected=IllegalArgumentException::class)
    fun tooManyIndependentVisualTracksAreRejected() {
        val project=graph()
        val third=TimelineTrack("third","Third",LayerKind.VIDEO,2)
        val fourth=TimelineTrack("fourth","Fourth",LayerKind.IMAGE,3)
        val tooMany=project.copy(
            tracks=project.tracks+third+fourth,
            layers=project.layers+listOf(
                TimelineLayer("t","third",LayerKind.VIDEO,0,1_000_000,
                    sourceUri="content://t"),
                TimelineLayer("f","fourth",LayerKind.IMAGE,0,1_000_000,
                    sourceUri="content://f")
            )
        )
        VisualOverlaySlots.slotFor(tooMany,tooMany.layers.last())
    }
    @Test fun hidingOtherVisualTrackDoesNotMoveExistingImage() {
        val original=graph()
        val still=original.layers.last()
        val hiddenVideo=original.copy(tracks=original.tracks.map {
            if (it.kind == LayerKind.VIDEO) it.copy(hidden=true) else it
        })
        assertEquals(1,VisualOverlaySlots.slotFor(original,still))
        assertEquals(1,VisualOverlaySlots.slotFor(hiddenVideo,still))
    }

}
