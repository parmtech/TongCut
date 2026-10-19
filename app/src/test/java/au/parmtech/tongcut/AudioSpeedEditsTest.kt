package au.parmtech.tongcut

import org.junit.Assert.*
import org.junit.Test

class AudioSpeedEditsTest {
    private fun draft() = LayerEdits.addAudio(
        EditorDraft(clips = listOf(
            DraftClip("content://main", "Main", durationMs=12_000L)
        )), 1_000L, "content://audio", 4_000L
    )

    @Test fun constantDoubleSpeedPreservesSourceWindowAndUndo() {
        val original=draft()
        val id=original.layers.layers.single().id
        val fast=AudioSpeedEdits.setConstant(original,id,2f)
        val clip=fast.layers.layers.single()
        assertEquals(2_000_000L,clip.durationUs)
        assertEquals(4_000_000L,clip.consumedSourceUs)
        assertEquals(3_000_000L,clip.endUs)
        val source=AudioMixPlan.from(fast.layers,12_000_000L).single()
            .parts.filterIsInstance<AudioPart.Source>().single()
        assertEquals(2_000_000L,source.durationUs)
        assertEquals(4_000_000L,source.consumedSourceUs)
        assertEquals(2f,source.playbackSpeed,0f)
        val history=EditHistory(original)
        history.apply(fast)
        assertEquals(original,history.undo())
        assertEquals(fast,history.redo())
    }

    @Test fun audioRampHasDistinctSourceClockAndSurvivesSerialization() {
        val original=draft()
        val id=original.layers.layers.single().id
        val keys=listOf(
            SpeedKeyframe(0L,0.5f),
            SpeedKeyframe(1_000_000L,2f)
        )
        val updated=AudioSpeedEdits.setCurve(original,id,keys)
        val clip=updated.layers.layers.single()
        assertEquals(3_500_000L,clip.durationUs)
        assertEquals(4_000_000L,clip.consumedSourceUs)
        assertEquals(1_000_000L,clip.sourceAt(clip.startUs+2_000_000L))
        assertEquals(keys,clip.speedRamp)
        assertEquals(updated.layers,
            LayerTimelineCodec.decode(LayerTimelineCodec.encode(updated.layers)))
        val fromPlan=AudioMixPlan.from(updated.layers,12_000_000L).single()
            .parts.filterIsInstance<AudioPart.Source>().single()
        assertEquals(keys,fromPlan.speedRamp)
    }

    @Test fun rampSplitsOnSourceClockWithoutChangingDurationOrSource() {
        val original=draft()
        val id=original.layers.layers.single().id
        val ramped=AudioSpeedEdits.setCurve(original,id,listOf(
            SpeedKeyframe(0L,0.5f),
            SpeedKeyframe(1_000_000L,2f)
        ))
        val layer=ramped.layers.layers.single()
        val divided=ramped.layers.splitLayer(id,layer.startUs+2_500_000L,"right")
        val left=divided.layers.single { it.id == id }
        val right=divided.layers.single { it.id == "right" }
        assertEquals(layer.durationUs,left.durationUs+right.durationUs)
        assertEquals(layer.consumedSourceUs,
            left.consumedSourceUs+right.consumedSourceUs)
        assertEquals(left.endUs,right.startUs)
        assertEquals(LayerFade(),left.fade)
        assertEquals(LayerFade(),right.fade)
    }

    @Test fun keyAtProjectTimeChangesUnderlyingSourceSpeed() {
        val source=draft()
        val id=source.layers.layers.single().id
        val ramped=AudioSpeedEdits.addKeyAt(source,id,3_000L,2f)
        assertEquals(listOf(0L,2_000_000L),
            ramped.layers.layers.single().speedRamp.map { it.sourceOffsetUs })
        assertEquals(3_000_000L,ramped.layers.layers.single().durationUs)
    }

    @Test fun slowerAudioRejectsTrackCollision() {
        var original=draft()
        original=LayerEdits.addAudio(original,6_000L,"content://later",2_000L)
        val id=original.layers.layers.first().id
        assertThrows(IllegalArgumentException::class.java) {
            AudioSpeedEdits.setConstant(original,id,0.5f)
        }
    }

    @Test fun audioClipSpeedCannotExceedSourceOrBaseVideo() {
        val original=draft()
        val id=original.layers.layers.single().id
        assertThrows(IllegalArgumentException::class.java) {
            AudioSpeedEdits.setConstant(original,id,0.25f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AudioSpeedEdits.setConstant(original,id,0f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AudioSpeedEdits.setCurve(original,id,listOf(
                SpeedKeyframe(10_000L,1f)
            ))
        }
    }

    @Test fun sourceSlipUsesConsumedTimeAfterAudioSpeed() {
        val input=draft()
        val id=input.layers.layers.single().id
        val sped=AudioSpeedEdits.setConstant(input,id,2f)
        assertThrows(IllegalArgumentException::class.java) {
            AdvancedClipEdits.slipSourceBy(sped,id,500L)
        }
    }

    @Test fun pinnedConstantAudioSourceRemainsExactWhenDurationRounds() {
        val original=draft()
        val id=original.layers.layers.single().id
        val modified=AudioSpeedEdits.setConstant(original,id,0.75f)
        val audio=modified.layers.layers.single()
        assertEquals(5_333_000L,audio.durationUs)
        assertEquals(4_000_000L,audio.consumedSourceUs)
        assertEquals(audio.sourceInUs+4_000_000L,
            audio.sourceAt(audio.endUs))
        val part=AudioMixPlan.from(modified.layers,12_000_000L).single()
            .parts.filterIsInstance<AudioPart.Source>().single()
        assertEquals(4_000_000L,part.consumedSourceUs)
    }

    @Test fun constantAudioSpeedSplitAndTrimKeepPerHalfInputWindows() {
        val original=draft()
        val id=original.layers.layers.single().id
        val fast=AudioSpeedEdits.setConstant(original,id,2f)
        val cut=fast.layers.splitLayer(id,fast.layers.layers.single().startUs+
            1_000_000L,"right")
        val left=cut.layers.single { it.id == id }
        val right=cut.layers.single { it.id == "right" }
        assertEquals(2_000_000L,left.sourceSpanUs)
        assertEquals(2_000_000L,right.sourceSpanUs)
        assertEquals(2_000_000L,right.sourceInUs)
        val rightShort=cut.resizeLayer("right",500_000L)
            .layers.single { it.id == "right" }
        assertEquals(1_000_000L,rightShort.consumedSourceUs)
        assertEquals(500_000L,rightShort.durationUs)
    }

    @Test fun audioCurveGraphEditsPersistAndUndoAsSingleSteps() {
        val original = draft()
        val layerId = original.layers.layers.single().id
        val preset = AudioSpeedEdits.applyPreset(original,layerId,SpeedRhythmPreset.PULSE)
        assertEquals(4,preset.layers.layers.single().speedRamp.size)
        assertEquals(4_000_000L,preset.layers.layers.single().consumedSourceUs)
        val first = preset.layers.layers.single().speedRamp[1]
        val changed = AudioSpeedEdits.changeKey(preset,layerId,first.sourceOffsetUs,0.75f)
        assertEquals(0.75f,changed.layers.layers.single().speedRamp[1].speed,0.0001f)
        assertEquals(first.speed,preset.layers.layers.single().speedRamp[1].speed,0.0001f)

        val moved = AudioSpeedEdits.moveKey(changed,layerId,first.sourceOffsetUs,1_250_000L)
        assertEquals(1_250_000L,moved.layers.layers.single().speedRamp[1].sourceOffsetUs)
        assertEquals(4_000_000L,moved.layers.layers.single().consumedSourceUs)
        val removed = AudioSpeedEdits.removeKey(moved,layerId,1_250_000L)
        assertEquals(3,removed.layers.layers.single().speedRamp.size)
        assertEquals(0L,removed.layers.layers.single().speedRamp.first().sourceOffsetUs)

        val history = EditHistory(original)
        history.apply(preset)
        history.apply(changed)
        assertEquals(preset,history.undo())
        assertEquals(changed,history.redo())
    }

    @Test fun audioCurveGraphRejectsDuplicateOrMissingKeysAndLockedTracks() {
        val input = draft()
        val id = input.layers.layers.single().id
        val preset = AudioSpeedEdits.applyPreset(input,id,SpeedRhythmPreset.PULSE)
        assertThrows(IllegalArgumentException::class.java) {
            AudioSpeedEdits.moveKey(preset,id,1_000_000L,2_000_000L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AudioSpeedEdits.moveKey(preset,id,0L,250_000L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AudioSpeedEdits.removeKey(preset,id,0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AudioSpeedEdits.changeKey(preset,id,1_250_000L,2f)
        }
        val locked = LayerEdits.setTrackLocked(preset,preset.layers.tracks.single().id,true)
        assertThrows(IllegalArgumentException::class.java) {
            AudioSpeedEdits.changeKey(locked,id,1_000_000L,0.5f)
        }
    }

    @Test fun audioSpeedPresetResetReturnsToOriginalSourceDuration() {
        val input = draft()
        val id = input.layers.layers.single().id
        val rush = AudioSpeedEdits.applyPreset(input,id,SpeedRhythmPreset.RUSH)
        assertTrue(rush.layers.layers.single().durationUs < 5_000_000L)
        val reset = AudioSpeedEdits.applyPreset(rush,id,SpeedRhythmPreset.RESET)
        val layer = reset.layers.layers.single()
        assertEquals(4_000_000L,layer.durationUs)
        assertEquals(4_000_000L,layer.consumedSourceUs)
        assertEquals(4_000_000L,layer.sourceAt(layer.endUs))
        assertEquals(input.layers.layers.single().startUs,layer.startUs)
    }

}
