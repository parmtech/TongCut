package au.parmtech.tongcut

import org.junit.Assert.*
import org.junit.Test

class VideoCrossfadeEditsTest {
    private fun base() = EditorDraft(clips = listOf(
        DraftClip("content://main", "Main", durationMs = 10_000L)
    ))

    private fun adjacent(): EditorDraft {
        val a = LayerEdits.addVisual(
            base(), 1_000L, LayerKind.VIDEO, "content://red", 3_000L
        )
        return LayerEdits.addVisual(
            a, 4_000L, LayerKind.VIDEO, "content://green", 3_000L
        )
    }

    @Test fun adjacentSameLaneBecomesRealTwoLaneCrossfadeAndUndoWorks() {
        val before = adjacent()
        val from = before.layers.layers.single { it.sourceUri == "content://red" }
        val to = before.layers.layers.single { it.sourceUri == "content://green" }
        assertEquals(from.trackId, to.trackId)
        val changed = VideoCrossfadeEdits.crossfadeWithNext(before, from.id, 500L)
        val a = changed.layers.layers.first { it.id == from.id }
        val b = changed.layers.layers.first { it.id == to.id }
        assertNotEquals(a.trackId, b.trackId)
        assertEquals(3_500_000L, b.startUs)
        assertEquals(4_000_000L, a.endUs)
        assertEquals(500_000L, a.fade.outUs)
        assertEquals(LayerFade(), b.fade)
        assertEquals(VisualOverlaySlots.slotFor(changed.layers, a),
            VisualOverlaySlots.slotFor(changed.layers, b))
        assertEquals(2, changed.layers.activeAt(3_750_000L)
            .count { it.kind == LayerKind.VIDEO })
        assertEquals(before.clips, changed.clips)
        val history = EditHistory(before)
        history.apply(changed)
        assertEquals(before, history.undo())
        assertEquals(changed, history.redo())
    }

    @Test fun fadedFrontAndSolidBackMaintainUnitTotalAlphaInOverlap() {
        val before = adjacent()
        val cut = before.layers.layers.first().id
        val next = VideoCrossfadeEdits.crossfadeWithNext(before, cut, 1_000L)
        val byOrder = next.layers.tracks.associateBy { it.id }
        val videos = next.layers.layers.sortedBy { byOrder.getValue(it.trackId).order }
        val front = videos.first()
        val back = videos.last()
        val at = 3_500_000L
        val frontAlpha = front.fade.alphaAt(at - front.startUs, front.durationUs)
        val backAlpha = back.fade.alphaAt(at - back.startUs, back.durationUs)
        assertEquals(0.5f, frontAlpha, 0.001f)
        assertEquals(1f, backAlpha, 0.001f)
        assertEquals(1f, frontAlpha + (1f - frontAlpha) * backAlpha, 0.001f)
    }

    @Test fun existingDistinctFrontToBackTracksUseIncomingFadeInstead() {
        val timeline = LayerTimeline(
            tracks = listOf(
                TimelineTrack("incoming", "Incoming", LayerKind.VIDEO, 0),
                TimelineTrack("outgoing", "Outgoing", LayerKind.VIDEO, 1)
            ),
            layers = listOf(
                TimelineLayer("a", "outgoing", LayerKind.VIDEO, 1_000_000L,
                    2_000_000L, sourceUri = "content://red", sourceDurationUs = 4_000_000L),
                TimelineLayer("b", "incoming", LayerKind.VIDEO, 3_000_000L,
                    2_000_000L, sourceUri = "content://green", sourceDurationUs = 4_000_000L)
            )
        )
        val project = base().copy(layers = timeline)
        val changed = VideoCrossfadeEdits.crossfade(project, "a", "b", 500L)
        val a = changed.layers.layers.first { it.id == "a" }
        val b = changed.layers.layers.first { it.id == "b" }
        assertEquals(LayerFade(), a.fade)
        assertEquals(500_000L, b.fade.inUs)
        assertEquals(2_500_000L, b.startUs)
        assertEquals(2, changed.layers.tracks.size)
        assertEquals(1f, b.fade.alphaAt(500_000L, b.durationUs), 0f)
    }

    @Test fun previewDrawsMedia3RearFirstAndPostProcessPhotosLast() {
        val graph = LayerTimeline(
            tracks = listOf(
                TimelineTrack("front", "Front", LayerKind.VIDEO, 0),
                TimelineTrack("rear", "Rear", LayerKind.VIDEO, 1),
                TimelineTrack("photo", "Photo", LayerKind.IMAGE, 2)
            ),
            layers = listOf(
                TimelineLayer("a", "front", LayerKind.VIDEO, 0, 1_000_000L,
                    sourceUri = "content://red"),
                TimelineLayer("b", "rear", LayerKind.VIDEO, 0, 1_000_000L,
                    sourceUri = "content://green"),
                TimelineLayer("p", "photo", LayerKind.IMAGE, 0, 1_000_000L,
                    sourceUri = "content://photo")
            )
        )
        assertEquals(listOf("b", "a", "p"),
            VisualLayerOrder.forPreview(graph, 500_000L).map { it.id })
    }

    @Test fun rejectsMissingCutLockAndUnsupportedExistingFades() {
        val before = adjacent()
        val from = before.layers.layers.first()
        assertThrows(IllegalArgumentException::class.java) {
            VideoCrossfadeEdits.crossfade(before, from.id, from.id, 500L)
        }
        val locked = LayerEdits.setTrackLocked(before, from.trackId, true)
        assertThrows(IllegalArgumentException::class.java) {
            VideoCrossfadeEdits.crossfadeWithNext(locked, from.id, 500L)
        }
        val faded = LayerEdits.setLayerFade(before, from.id, LayerFade(inUs=200_000L))
        assertThrows(IllegalArgumentException::class.java) {
            VideoCrossfadeEdits.crossfadeWithNext(faded, from.id, 500L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            VideoCrossfadeEdits.crossfadeWithNext(before, from.id, 3_000L)
        }
    }

    @Test fun rejectsThirdVisualLaneWhenAutoAllocating() {
        var project = adjacent()
        project = LayerEdits.addVisual(
            project, 1_000L, LayerKind.IMAGE, "content://photo", 2_000L
        )
        project = LayerEdits.addVisual(
            project, 1_000L, LayerKind.VIDEO, "content://third", 2_000L
        )
        val from = project.layers.layers.first { it.sourceUri == "content://red" }
        assertThrows(IllegalArgumentException::class.java) {
            VideoCrossfadeEdits.crossfade(project, from.id,
                project.layers.layers.first { it.sourceUri=="content://green" }.id, 500L)
        }
    }
}
