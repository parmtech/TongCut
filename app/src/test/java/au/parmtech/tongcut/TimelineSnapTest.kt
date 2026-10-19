package au.parmtech.tongcut

import org.junit.Assert.*
import org.junit.Test

class TimelineSnapTest {
    private fun base(): EditorDraft = EditorDraft(clips = listOf(
        DraftClip("content://a","A",5_000),
        DraftClip("content://b","B",5_000)
    ))
    @Test fun dragsSnapToBaseCuts() {
        val draft = LayerEdits.addText(base(), 0, "Text")
        val id = draft.layers.layers.single().id
        val result = TimelineSnap.move(draft,id,5_065_000)
        assertEquals(5_000_000L,result)
    }
    @Test fun alignsClipEndToAnotherLayerStart() {
        val a = LayerEdits.addText(base(),0,"A")
        val b = LayerEdits.addText(a,7_000,"B") // fixed alignment anchor at 7s
        val c = LayerEdits.addText(b,5_000,"Moving") // independent overlapping lane
        val moved = c.layers.layers.last()
        val start = TimelineSnap.move(c,moved.id,3_935_000)
        assertEquals(4_000_000L,start)
    }
    @Test fun unsnappedMovesStillSnapToRationalFrame() {
        val a = LayerEdits.addText(base(),0,"Test")
        val id = a.layers.layers.single().id
        val moved = TimelineSnap.move(a,id,1_025_000,80_000)
        assertEquals(a.layers.rate.nearestFrameUs(1_025_000), moved)
    }
    @Test fun clampsToProjectBounds() {
        val a = LayerEdits.addText(base(),0,"Test")
        val id = a.layers.layers.single().id
        assertEquals(7_000_000L,TimelineSnap.move(a,id,Long.MAX_VALUE))
        assertEquals(0L,TimelineSnap.move(a,id,-30_000L))
    }
}
