package au.parmtech.tongcut

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VariableSpeedIntegrationTest {
    private fun footage(): EditorDraft = EditorDraft(clips=listOf(
        DraftClip("content://main","Main",durationMs=12_000)
    ))

    @Test fun changingRampKeepsSameSourceRangeAndRemapsPreviewTime() {
        val source = LayerEdits.addVisual(
            footage(), 0, LayerKind.VIDEO, "content://overlay", 8_000
        )
        val id = source.layers.layers.single().id
        val accelerated = LayerEdits.setSpeedKeyAt(source,id,2_000,2f)
        val halfThenFast = LayerEdits.setSpeedKeyAt(accelerated,id,0,0.5f)
        val clip = halfThenFast.layers.layers.single()
        assertEquals(7_000_000L,clip.durationUs)
        assertEquals(8_000_000L,clip.sourceSpanUs)
        assertEquals(2,clip.speedRamp.size)
        assertEquals(2_000_000L,clip.sourceAt(4_000_000L))
        assertEquals(4_000_000L,clip.sourceAt(5_000_000L))
        assertEquals(8_000_000L,clip.sourceAt(7_000_000L))
        assertEquals(8_000_000L,source.layers.layers.single().durationUs)
        assertEquals(1f, source.layers.layers.single().playbackSpeed)
    }

    @Test fun rampSerializationIsBackwardCompatible() {
        val source = LayerEdits.addVisual(footage(),0,LayerKind.VIDEO,"content://v",8_000)
        val id=source.layers.layers.single().id
        val ramp=LayerEdits.setSpeedKeyAt(source,id,4_000,2f)
        val roundtrip=LayerTimelineCodec.decode(LayerTimelineCodec.encode(ramp.layers))
        assertEquals(ramp.layers,roundtrip)
        val old=LayerTimelineCodec.encode(source.layers)
        old.put("schema",3)
        old.getJSONArray("layers").getJSONObject(0).remove("speedRamp")
        old.getJSONArray("layers").getJSONObject(0).remove("sourceSpanUs")
        val migrated=LayerTimelineCodec.decode(old).layers.single()
        assertNull(migrated.sourceSpanUs)
        assertTrue(migrated.speedRamp.isEmpty())
        assertEquals(1f,migrated.playbackSpeed)
    }

    @Test fun rampSplitPreservesSourcesAndSpeedAtBoundary() {
        val input=LayerEdits.addVisual(footage(),0,LayerKind.VIDEO,"content://v",8_000)
        val first=LayerEdits.setSpeedKeyAt(input,input.layers.layers.single().id,2_000,2f)
        val ramp=LayerEdits.setSpeedKeyAt(first,first.layers.layers.single().id,0,0.5f)
        val id=ramp.layers.layers.single().id
        val graph=ramp.layers.splitLayer(id,5_000_000L,"right")
        val left=graph.layers.first { it.id==id }
        val right=graph.layers.first { it.id=="right" }
        assertEquals(5_000_000L,left.durationUs)
        assertEquals(2_000_000L,right.durationUs)
        assertEquals(4_000_000L,left.sourceSpanUs)
        assertEquals(4_000_000L,right.sourceSpanUs)
        assertEquals(4_000_000L,right.sourceInUs)
        assertEquals(5_000_000L,right.startUs)
        assertEquals(2f,right.speedRamp.single().speed)
        assertEquals(8_000_000L,right.sourceAt(right.endUs))
    }

    @Test fun resettingToUniformSpeedPreservesRawSourceSpanAndSupportsUndo() {
        val start=LayerEdits.addVisual(footage(),0,LayerKind.VIDEO,"content://v",8_000)
        val changed=LayerEdits.setSpeedKeyAt(start,start.layers.layers.single().id,2_000,2f)
        val reset=LayerEdits.setVisualSpeed(changed,changed.layers.layers.single().id,1f)
        assertTrue(reset.layers.layers.single().speedRamp.isEmpty())
        assertEquals(8_000_000L,reset.layers.layers.single().durationUs)
        val history=EditHistory(start)
        history.apply(changed)
        history.apply(reset)
        assertEquals(changed,history.undo())
        assertEquals(start,history.undo())
        assertEquals(changed,history.redo())
        assertEquals(reset,history.redo())
    }

    @Test(expected=IllegalArgumentException::class)
    fun speedRampedClipCannotSilentlyExtendPastMainVideo() {
        val d=EditorDraft(clips=listOf(
            DraftClip("content://main","Main",durationMs=5_000)
        ))
        val visual=LayerEdits.addVisual(d,0,LayerKind.VIDEO,"content://overlay",5_000)
        LayerEdits.setSpeedKeyAt(visual, visual.layers.layers.single().id,0,0.25f)
    }

    @Test(expected=IllegalArgumentException::class)
    fun sourceBoundViolationCannotBeSerializedIntoRampedClip() {
        val track=TimelineTrack("video","Overlay",LayerKind.VIDEO,0)
        LayerTimeline(tracks=listOf(track),layers=listOf(
            TimelineLayer(id="bad",trackId="video",kind=LayerKind.VIDEO,
                startUs=0,durationUs=4_000_000,sourceUri="content://v",
                sourceDurationUs=2_000_000,sourceSpanUs=4_000_000,
                speedRamp=listOf(SpeedKeyframe(0,1f)))
        ))
    }
}
