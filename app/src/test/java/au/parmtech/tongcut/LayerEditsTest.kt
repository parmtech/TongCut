package au.parmtech.tongcut

import org.junit.Assert.*
import org.junit.Test

class LayerEditsTest {
    private fun base() = EditorDraft(clips = listOf(
        DraftClip("content://footage", "Clip", durationMs = 10_000)
    ))

    @Test fun textStartsAtPlayheadAndKeepsProjectImmutable() {
        val draft = base()
        val after = LayerEdits.addText(draft, 2_000, "  Hello Manbir!  ")
        assertEquals(0, draft.layers.layers.size)
        val layer = after.layers.layers.single()
        assertEquals(LayerKind.TEXT, layer.kind)
        assertEquals(2_000_000L, layer.startUs)
        assertEquals(3_000_000L, layer.durationUs)
        assertEquals("Hello Manbir!", layer.text)
        assertEquals(emptyList<TimelineLayer>(), after.layers.activeAt(1_999_999L))
        assertEquals(listOf(layer), after.layers.activeAt(2_000_000L))
    }

    @Test fun overlapsCreateDifferentTextLanesAndCanBeRemoved() {
        val first = LayerEdits.addText(base(), 0, "First")
        val second = LayerEdits.addText(first, 1_000, "Second")
        assertEquals(2, second.layers.tracks.size)
        assertEquals(2, second.layers.activeAt(1_500_000).size)
        val removed = LayerEdits.remove(second, second.layers.layers.first().id)
        assertEquals(1, removed.layers.layers.size)
        assertEquals(2, second.layers.layers.size)
    }

    @Test fun editsPreserveSourceAndUndoCanRestore() {
        val initial = base()
        val changed = LayerEdits.addText(initial, 300, "Original")
        val id = changed.layers.layers.single().id
        val renamed = LayerEdits.changeText(changed, id, "Updated")
        assertEquals("Updated", renamed.layers.layers.single().text)
        assertEquals("Original", changed.layers.layers.single().text)
        val history = EditHistory(initial)
        history.apply(changed)
        history.apply(renamed)
        assertEquals(changed, history.undo())
        assertEquals(initial, history.undo())
        assertEquals(changed, history.redo())
    }

    @Test fun newAudioLayerIsAnchoredAndBoundedByVideo() {
        val next = LayerEdits.addAudio(base(), 7_000, "content://music", 9_000)
        val item = next.layers.layers.single()
        assertEquals(LayerKind.AUDIO, item.kind)
        assertEquals(7_000_000L, item.startUs)
        assertEquals(3_000_000L, item.durationUs)
        assertEquals(9_000_000L, item.sourceDurationUs)
    }

    @Test(expected = IllegalArgumentException::class)
    fun cannotAddBlankText() {
        LayerEdits.addText(base(), 0, "   ")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectAudioWithoutVideo() {
        LayerEdits.addAudio(EditorDraft(), 0, "content://music", 3_000)
    }

    @Test fun trackVisibilityDoesNotDestroyLayers() {
        val draft = LayerEdits.addText(base(), 0, "Hello")
        val id = draft.layers.tracks.single().id
        val hidden = LayerEdits.setTrackVisibility(draft, id, true)
        assertTrue(hidden.layers.activeAt(1_000_000L).isEmpty())
        assertEquals(1, hidden.layers.layers.size)
        assertEquals(1, draft.layers.activeAt(1_000_000L).size)
    }

    @Test fun timelineMovesAndTrimsAffectVisibleRange() {
        val base = LayerEdits.addText(base(), 0, "Move me")
        val id = base.layers.layers.single().id
        val moved = LayerEdits.placeLayer(base, id, 6_000)
        val trimmed = LayerEdits.trimLayer(moved, id, 1_000_000L)
        assertEquals(6_000_000L, trimmed.layers.layers.single().startUs)
        assertEquals(1_000_000L, trimmed.layers.layers.single().durationUs)
    }

    @Test fun audioGainEditsAreReversible() {
        val original = LayerEdits.addAudio(base(),0,"content://audio",2_000)
        val track = original.layers.tracks.single()
        val adjusted = LayerEdits.setAudioGain(original,track.id,0.4f)
        assertEquals(1f, original.layers.tracks.single().gain)
        assertEquals(0.4f, adjusted.layers.tracks.single().gain)
        val history = EditHistory(original)
        history.apply(adjusted)
        assertEquals(original,history.undo())
    }

    @Test(expected=IllegalArgumentException::class)
    fun invalidAudioGainRejected() {
        val original = LayerEdits.addAudio(base(),0,"content://audio",2_000)
        LayerEdits.setAudioGain(original,original.layers.tracks.single().id,1.5f)
    }
}
