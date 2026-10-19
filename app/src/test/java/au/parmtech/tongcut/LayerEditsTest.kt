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
    @Test fun frameSnappedDragIsUndoable() {
        val before = LayerEdits.addText(base(),0,"Drag")
        val id = before.layers.layers.single().id
        val updated = LayerEdits.placeLayerAtFrame(before,id,1_025_000L)
        val start = updated.layers.layers.single().startUs
        assertEquals(updated.layers.rate.nearestFrameUs(1_025_000L), start)
        val history = EditHistory(before)
        history.apply(updated)
        assertEquals(before,history.undo())
    }

    @Test(expected=IllegalArgumentException::class)
    fun frameDragPreventsOverlapsOnSameLane() {
        val first = LayerEdits.addText(base(),0,"One")
        val second = LayerEdits.addText(first,5_000,"Two")
        val a = first.layers.layers.single()
        val b = second.layers.layers.last()
        // They share a free lane, so moving second into first must be rejected.
        LayerEdits.placeLayerAtFrame(second,b.id,a.startUs)
    }

    @Test fun lockBlocksClipMoveButCanBeUndone() {
        val initial = LayerEdits.addText(base(), 0, "Lock me")
        val clip = initial.layers.layers.single()
        val locked = LayerEdits.setTrackLocked(initial, clip.trackId, true)
        assertThrows(IllegalArgumentException::class.java) {
            LayerEdits.placeLayerAtFrame(locked,clip.id, 2_000_000L)
        }
        val restored = LayerEdits.setTrackLocked(locked,clip.trackId, false)
        assertEquals(initial, restored)
    }

    @Test fun visualLayerImportsDoNotAlterMainFootage() {
        val original = base()
        val overlay = LayerEdits.addVisual(original,2_000,LayerKind.VIDEO,"content://overlay",4_000)
        assertEquals(original.clips, overlay.clips)
        assertEquals(2_000_000L,overlay.layers.layers.single().startUs)
        assertEquals(4_000_000L,overlay.layers.layers.single().durationUs)
        assertEquals(LayerKind.VIDEO,overlay.layers.layers.single().kind)
        val image = LayerEdits.addVisual(overlay, 7_000,LayerKind.IMAGE,"content://image",5_000)
        assertEquals(3_000_000L,image.layers.layers.last().durationUs)
    }

    @Test(expected=IllegalArgumentException::class)
    fun unsupportedLayerKindCannotEnterVisualImporter() {
        LayerEdits.addVisual(base(),0,LayerKind.AUDIO,"content://x",1_000)
    }

    @Test fun photoAndVideoOverlayModelsAreExportCandidates() {
        val original = base()
        val photo = LayerEdits.addVisual(
            original, 0, LayerKind.IMAGE, "content://gallery/picture", 3_000
        )
        assertTrue(photo.canExportWithLegacyRenderer)
        val movie = LayerEdits.addVisual(
            photo, 1_000, LayerKind.VIDEO, "content://gallery/clip", 4_000
        )
        // Native Media3 compositor draws video lane index 0 last (on top) after reordering.
        // Pixel-perfect output still needs Android instrumentation/device testing.
        assertTrue(movie.canExportWithLegacyRenderer)
        val videoTrack = movie.layers.tracks.first { it.kind == LayerKind.VIDEO }
        val hidden = LayerEdits.setTrackVisibility(movie, videoTrack.id, true)
        assertTrue(hidden.canExportWithLegacyRenderer)
    }

    @Test fun motionKeysAreFrameSnappedAndUndoable() {
        val start = LayerEdits.addText(base(), 1_000L, "Title")
        val id = start.layers.layers.single().id
        val animated = LayerEdits.setMotionAt(start, id, 1_730L) {
            it.copy(x = 0.3f, opacity = 0.6f)
        }
        val relative = animated.layers.layers.single().motion.single().timeUs
        assertEquals(animated.layers.rate.nearestFrameUs(730_000L), relative)
        val history = EditHistory(start)
        history.apply(animated)
        assertEquals(start, history.undo())
        assertEquals(animated, history.redo())
    }

    @Test(expected = IllegalArgumentException::class)
    fun motionOnLockedTrackIsRejected() {
        val draft = LayerEdits.addText(base(), 0, "Locked")
        val item = draft.layers.layers.single()
        val locked = LayerEdits.setTrackLocked(draft, item.trackId, true)
        LayerEdits.setMotionAt(locked, item.id, 100) { it.copy(x = 1f) }
    }


    @Test fun speedChangeUpdatesTimelineLengthAndCanUndo() {
        val base = LayerEdits.addVisual(
            base(),1_000,LayerKind.VIDEO,"content://short",4_000
        )
        val id = base.layers.layers.single().id
        val fast = LayerEdits.setVisualSpeed(base,id,2f)
        val layer = fast.layers.layers.single()
        assertEquals(2_000_000L,layer.durationUs)
        assertEquals(2f,layer.playbackSpeed)
        assertEquals(2_000_000L,layer.sourceAt(layer.startUs+1_000_000L))
        val history = EditHistory(base)
        history.apply(fast)
        assertEquals(base,history.undo())
    }

    @Test fun slowPlaybackDoesNotOverrunSourceMedia() {
        val initial = LayerEdits.addVisual(
            base(),1_000,LayerKind.VIDEO,"content://short",4_000
        )
        val item = initial.layers.layers.single()
        val slow = LayerEdits.setVisualSpeed(initial,item.id,0.5f)
        assertEquals(8_000_000L,slow.layers.layers.single().durationUs)
        assertEquals(0.5f,slow.layers.layers.single().playbackSpeed)
    }

    @Test(expected = IllegalArgumentException::class)
    fun speedChangeThatOutgrowsTimelineFails() {
        val initial = LayerEdits.addVisual(
            base(),7_000,LayerKind.VIDEO,"content://short",3_000
        )
        LayerEdits.setVisualSpeed(initial,initial.layers.layers.single().id,0.25f)
    }

    @Test fun photoMatteEditSupportsUndoAndLocks() {
        val base = LayerEdits.addVisual(base(), 0, LayerKind.IMAGE, "content://photo", 3_000)
        val photo = base.layers.layers.single()
        val changed = LayerEdits.setPhotoEffects(base,photo.id,PhotoEffects(
            PhotoMask.CIRCLE,0.1f,PhotoChroma()))
        assertEquals(PhotoEffects(),base.layers.layers.single().photoEffects)
        assertEquals(PhotoMask.CIRCLE,changed.layers.layers.single().photoEffects.mask)
        val history = EditHistory(base)
        history.apply(changed)
        assertEquals(base,history.undo())
        val locked = LayerEdits.setTrackLocked(changed,photo.trackId,true)
        assertThrows(IllegalArgumentException::class.java) {
            LayerEdits.setPhotoEffects(locked,photo.id,PhotoEffects(PhotoMask.LINEAR_WIPE))
        }
    }

    @Test(expected=IllegalArgumentException::class)
    fun videoCannotUsePhotoMatteOperation() {
        val draft = LayerEdits.addVisual(base(),0,LayerKind.VIDEO,"content://movie",2_000)
        LayerEdits.setPhotoEffects(draft,draft.layers.layers.single().id,
            PhotoEffects(PhotoMask.CIRCLE))
    }

    @Test fun visibleClipFadesAreReversible() {
        val initial=LayerEdits.addVisual(base(),0,LayerKind.IMAGE,"content://photo",4_000)
        val clip=initial.layers.layers.single()
        val faded=LayerEdits.setLayerFade(initial,clip.id,LayerFade(500_000,750_000))
        assertEquals(LayerFade(),initial.layers.layers.single().fade)
        assertEquals(0.5f,faded.layers.layers.single().fade.alphaAt(250_000,4_000_000),0.001f)
        val history=EditHistory(initial)
        history.apply(faded)
        assertEquals(initial,history.undo())
    }
    @Test(expected=IllegalArgumentException::class)
    fun audioLayerFadeIsNotAllowed() {
        val initial=LayerEdits.addAudio(base(),0,"content://voice",2_000)
        LayerEdits.setLayerFade(initial,initial.layers.layers.single().id,LayerFade(1_000,0))
    }

    @Test fun overlayRelinkKeepsTransformsFadesAndPhotoMatte() {
        val added=LayerEdits.addVisual(base(),0,LayerKind.IMAGE,
            "content://old-photo",4_000)
        val id=added.layers.layers.single().id
        val fx=PhotoEffects(mask=PhotoMask.CIRCLE,chroma=PhotoChroma())
        val enhanced=LayerEdits.setLayerFade(
            LayerEdits.setPhotoEffects(added,id,fx),id,
            LayerFade(500_000,500_000)
        )
        val reconnected=LayerEdits.relinkLayer(enhanced,id,"content://new-photo")
        assertEquals(enhanced.layers.layers.single().photoEffects,
            reconnected.layers.layers.single().photoEffects)
        assertEquals(enhanced.layers.layers.single().fade,
            reconnected.layers.layers.single().fade)
        assertEquals("content://old-photo",enhanced.layers.layers.single().sourceUri)
        assertEquals("content://new-photo",reconnected.layers.layers.single().sourceUri)
    }

    @Test fun videoRelinkRequiresFullSavedTrimAndSpeedSourceWindow() {
        val original=LayerEdits.addVisual(base(),0,LayerKind.VIDEO,
            "content://old-video",4_000)
        val changed=LayerEdits.setVisualSpeed(
            original,original.layers.layers.single().id,2f)
        val id=changed.layers.layers.single().id
        val good=LayerEdits.relinkLayer(changed,id,"content://new-video",4_500)
        assertEquals(2f,good.layers.layers.single().playbackSpeed)
        assertEquals(2_000_000L,good.layers.layers.single().durationUs)
        assertThrows(IllegalArgumentException::class.java) {
            LayerEdits.relinkLayer(changed,id,"content://too-short",1_500)
        }
    }

    @Test(expected=IllegalArgumentException::class)
    fun cannotRelinkLockedLayer() {
        val start=LayerEdits.addAudio(base(),0,"content://old-audio",2_000)
        val id=start.layers.layers.single().id
        val locked=LayerEdits.setTrackLocked(start,
            start.layers.layers.single().trackId,true)
        LayerEdits.relinkLayer(locked,id,"content://replacement",3_000)
    }


    @Test fun advancedKeyframeEditsAreUndoableAndPreserveOtherKeys() {
        var draft = LayerEdits.addText(base(), 0, "Animated")
        val id = draft.layers.layers.single().id
        draft = LayerEdits.setMotionAt(draft,id,0L) { it.copy(opacity=0f) }
        draft = LayerEdits.setMotionAt(draft,id,2_000L) { it.copy(opacity=1f) }
        val keys = draft.layers.layers.single().motion
        assertEquals(2,keys.size)
        val firstUs = keys.first().timeUs
        val edited = LayerEdits.setMotionKeyInterpolation(
            draft,id,firstUs,MotionInterpolation.SMOOTH
        )
        assertEquals(MotionInterpolation.SMOOTH,edited.layers.layers.single().motion.first().interpolation)
        val mid = MotionCurve.at(edited.layers.layers.single().motion,400_000L)
        assertTrue(mid.opacity < 0.2f)
        val moved = LayerEdits.moveMotionKey(edited,id,firstUs,200_000L)
        assertEquals(moved.layers.rate.nearestFrameUs(200_000L),
            moved.layers.layers.single().motion.first().timeUs)
        val next = LayerEdits.setMotionKeyPose(moved,id,
            moved.layers.layers.single().motion.first().timeUs, MotionPose(scale=1.5f))
        assertEquals(1.5f,next.layers.layers.single().motion.first().pose.scale,0.0001f)
        val history = EditHistory(draft)
        history.apply(edited)
        assertEquals(draft,history.undo())
        assertEquals(edited,history.redo())
        val removed = LayerEdits.removeMotionKey(edited,id,firstUs)
        assertEquals(1,removed.layers.layers.single().motion.size)
        assertEquals(2,edited.layers.layers.single().motion.size)
    }

    @Test fun lockedTrackCannotChangeKeyframes() {
        val start=LayerEdits.addText(base(),0,"Locked")
        val id=start.layers.layers.single().id
        val keyed=LayerEdits.setMotionAt(start,id,0L) { it.copy(x=0.5f) }
        val locked=LayerEdits.setTrackLocked(keyed,keyed.layers.layers.single().trackId,true)
        assertThrows(IllegalArgumentException::class.java) {
            LayerEdits.setMotionKeyInterpolation(locked,id,0L,MotionInterpolation.HOLD)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LayerEdits.removeMotionKey(locked,id,0L)
        }
    }

    @Test fun movingKeyframeOntoAnotherKeyIsRejected() {
        val first=LayerEdits.addText(base(),0,"Overlap")
        val id=first.layers.layers.single().id
        val a=LayerEdits.setMotionAt(first,id,0L) { it.copy(x=0f) }
        val b=LayerEdits.setMotionAt(a,id,1_000L) { it.copy(x=1f) }
        assertThrows(IllegalArgumentException::class.java) {
            LayerEdits.moveMotionKey(b,id,0L,1_000_000L)
        }
    }
}