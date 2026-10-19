package au.parmtech.tongcut

import org.junit.Assert.*
import org.junit.Test

class AdvancedClipEditsTest {
    private fun base() = EditorDraft(clips = listOf(
        DraftClip("content://source/main", "Main video", durationMs = 12_000L)
    ))
    private fun video(start: Long = 0L) = LayerEdits.addVisual(
        base(), start, LayerKind.VIDEO, "content://source/overlay", 5_000L
    )

    @Test fun duplicateKeepsAllAuthoringMetadataAndHistory() {
        val raw = LayerEdits.addVisual(
            base(), 0L, LayerKind.IMAGE, "content://source/photo", 2_000L
        )
        val id = raw.layers.layers.single().id
        val modified = LayerEdits.setLayerFade(
            LayerEdits.setPhotoEffects(
                LayerEdits.setMotionAt(raw, id, 1_000L) {
                    it.copy(x = 0.25f, scale = 1.5f, opacity = 0.7f)
                },
                id, PhotoEffects(mask = PhotoMask.CIRCLE)
            ),
            id, LayerFade(inUs = 100_000L, outUs = 100_000L)
        )
        val original = modified.layers.layers.single()
        val duplicated = AdvancedClipEdits.duplicateAt(modified, id, 4_000L)
        val copy = duplicated.layers.layers.single { it.id != id }
        assertNotEquals(id, copy.id)
        assertEquals(4_000_000L, copy.startUs)
        assertEquals(original.sourceUri, copy.sourceUri)
        assertEquals(original.motion, copy.motion)
        assertEquals(original.photoEffects, copy.photoEffects)
        assertEquals(original.fade, copy.fade)
        assertEquals(original.durationUs, copy.durationUs)
        assertEquals(original.trackId, copy.trackId)
        assertEquals(modified.layers.layers.size, 1)
        val history = EditHistory(modified)
        history.apply(duplicated)
        assertEquals(modified, history.undo())
        assertEquals(duplicated, history.redo())
    }

    @Test fun overlapDuplicatesIntoNewTrackButKeepsVolume() {
        val original = LayerEdits.addAudio(base(), 0L, "content://source/song", 4_000L)
        val lane = original.layers.tracks.single()
        val gain = LayerEdits.setAudioGain(original, lane.id, 0.35f)
        val duplicated = AdvancedClipEdits.duplicateAt(
            gain, gain.layers.layers.single().id, 1_000L
        )
        assertEquals(2, duplicated.layers.layers.size)
        assertEquals(2, duplicated.layers.tracks.size)
        val copyLane = duplicated.layers.tracks.first { it.id != lane.id }
        assertEquals(0.35f, copyLane.gain, 0f)
        assertEquals(LayerKind.AUDIO, copyLane.kind)
        assertTrue(duplicated.canExportWithLegacyRenderer)
    }

    @Test fun duplicatingSpeedRampedVideoKeepsSpeedSourceAndKeys() {
        val raw = video()
        val id = raw.layers.layers.single().id
        val ramped = LayerEdits.applySpeedPreset(raw, id, SpeedRhythmPreset.RUSH)
        val clone = AdvancedClipEdits.duplicateAt(ramped, id, 6_000L)
        val first = ramped.layers.layers.single()
        val second = clone.layers.layers.single { it.id != id }
        assertEquals(first.speedRamp, second.speedRamp)
        assertEquals(first.sourceSpanUs, second.sourceSpanUs)
        assertEquals(first.playbackSpeed, second.playbackSpeed)
        assertEquals(first.durationUs, second.durationUs)
        assertEquals(first.sourceInUs, second.sourceInUs)
    }

    @Test fun cannotDuplicateVisualByExhaustingSlots() {
        var draft = video(0L)
        draft = LayerEdits.addVisual(
            draft, 0L, LayerKind.IMAGE, "content://source/image", 3_000L
        )
        draft = LayerEdits.addVisual(
            draft, 0L, LayerKind.VIDEO, "content://source/second", 3_000L
        )
        val first = draft.layers.layers.first()
        assertThrows(IllegalArgumentException::class.java) {
            AdvancedClipEdits.duplicateAt(draft, first.id, 1_000L)
        }
    }

    @Test fun slippingVideoKeepsDurationAndRampButChangesSourceWindow() {
        val source = video()
        val id = source.layers.layers.single().id
        val shortened = LayerEdits.trimLayer(source, id, 2_000_000L)
        val ramped = LayerEdits.applySpeedPreset(
            shortened, id, SpeedRhythmPreset.PULSE
        )
        val moved = AdvancedClipEdits.slipSourceBy(ramped, id, 500L)
        assertEquals(500_000L, moved.layers.layers.single().sourceInUs)
        assertEquals(ramped.layers.layers.single().startUs, moved.layers.layers.single().startUs)
        assertEquals(ramped.layers.layers.single().durationUs, moved.layers.layers.single().durationUs)
        assertEquals(ramped.layers.layers.single().speedRamp, moved.layers.layers.single().speedRamp)
        val reversed = AdvancedClipEdits.slipSourceBy(moved, id, -500L)
        assertEquals(ramped, reversed)
    }

    @Test fun slippingAudioEditsSourceAndRejectsOverrun() {
        val draft = LayerEdits.addAudio(base(), 2_000L, "content://source/music", 4_000L)
        val item = draft.layers.layers.single()
        assertThrows(IllegalArgumentException::class.java) {
            AdvancedClipEdits.slipSourceBy(draft, item.id, 250L)
        }
        val short = LayerEdits.trimLayer(draft, item.id, 2_000_000L)
        val moved = AdvancedClipEdits.slipSourceBy(short, item.id, 1_000L)
        assertEquals(1_000_000L, moved.layers.layers.single().sourceInUs)
        assertEquals(2_000_000L, moved.layers.layers.single().durationUs)
    }

    @Test fun rippleMovesLaterSameLaneWithoutChangingOtherLaneOrMediaSource() {
        var draft = LayerEdits.addText(base(), 1_000L, "First")
        draft = LayerEdits.addText(draft, 5_000L, "Second")
        draft = LayerEdits.addAudio(draft, 2_000L, "content://sound", 2_000L)
        val first = draft.layers.layers.first { it.text == "First" }
        val next = draft.layers.layers.first { it.text == "Second" }
        assertEquals(first.trackId, next.trackId)
        val shifted = AdvancedClipEdits.rippleLaneBy(draft, first.id, 500L)
        assertEquals(1_500_000L, shifted.layers.layers.first { it.id == first.id }.startUs)
        assertEquals(5_500_000L, shifted.layers.layers.first { it.id == next.id }.startUs)
        assertEquals(2_000_000L, shifted.layers.layers.first {
            it.kind == LayerKind.AUDIO
        }.startUs)
        assertEquals(draft.layers.layers.first { it.id == first.id }.sourceUri,
            shifted.layers.layers.first { it.id == first.id }.sourceUri)
        val history = EditHistory(draft)
        history.apply(shifted)
        assertEquals(draft, history.undo())
    }

    @Test fun rippleRejectsCollisionsAndMainClockOverrun() {
        var draft = LayerEdits.addText(base(), 0L, "First")
        draft = LayerEdits.addText(draft, 4_000L, "Second")
        val next = draft.layers.layers.first { it.text == "Second" }
        assertThrows(IllegalArgumentException::class.java) {
            AdvancedClipEdits.rippleLaneBy(draft, next.id, -1_500L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AdvancedClipEdits.rippleLaneBy(draft, next.id, 6_000L)
        }
    }

    @Test fun groupShiftHonoursLocksAndCollisionRules() {
        var draft = LayerEdits.addText(base(), 1_000L, "One")
        draft = LayerEdits.addAudio(draft, 1_000L, "content://song", 3_000L)
        val ids = draft.layers.layers.map { it.id }.toSet()
        val moved = AdvancedClipEdits.moveGroupBy(draft, ids, 1_000L)
        assertEquals(listOf(2_000_000L, 2_000_000L),
            moved.layers.layers.map { it.startUs })
        assertEquals(draft.layers.tracks, moved.layers.tracks)
        val locked = LayerEdits.setTrackLocked(draft,
            draft.layers.tracks.first().id, true)
        assertThrows(IllegalArgumentException::class.java) {
            AdvancedClipEdits.moveGroupBy(locked, ids, 500L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AdvancedClipEdits.moveGroupBy(draft, setOf("missing"), 500L)
        }
    }

    @Test fun duplicateOnLockedTrackAndBeyondProjectFailsWithoutMutation() {
        val draft = video()
        val layer = draft.layers.layers.single()
        assertThrows(IllegalArgumentException::class.java) {
            AdvancedClipEdits.duplicateAt(draft, layer.id, 10_000L)
        }
        val locked = LayerEdits.setTrackLocked(draft, layer.trackId, true)
        assertThrows(IllegalArgumentException::class.java) {
            AdvancedClipEdits.duplicateAt(locked, layer.id, 5_000L)
        }
        assertEquals(1, draft.layers.layers.size)
    }
}
