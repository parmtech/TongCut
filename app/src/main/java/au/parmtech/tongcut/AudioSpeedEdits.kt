package au.parmtech.tongcut

import kotlin.math.roundToLong

/**
 * P3 audio source-speed editor. Media3 handles the actual source-clock
 * time stretching; original PCM remains untouched and undo restores edits.
 * Gain stays per-track. All source windows remain export-millisecond aligned.
 */
object AudioSpeedEdits {
    private fun audio(draft: EditorDraft, id: String): TimelineLayer {
        val clip = draft.layers.layers.firstOrNull {
            it.id == id && it.kind == LayerKind.AUDIO
        } ?: throw IllegalArgumentException("Select an audio clip")
        val track = draft.layers.tracks.first { it.id == clip.trackId }
        require(!track.locked) { "Unlock this audio track before speed editing" }
        return clip
    }

    private fun projectEnd(draft: EditorDraft): Long =
        Math.multiplyExact(SequentialTimeline(draft.clips).totalMs, 1_000L)

    /** Select whether a clip's speed changes preserve or intentionally shift pitch. */
    fun setPreservePitch(draft: EditorDraft, id: String, preserve: Boolean): EditorDraft {
        audio(draft, id)
        return draft.copy(layers = draft.layers.copy(layers = draft.layers.layers.map {
            if (it.id == id) it.copy(preservePitch = preserve) else it
        }))
    }

    /** A constant clip-local speed, 0.25x–4x, preserving the consumed source. */
    fun setConstant(draft: EditorDraft, id: String, speed: Float): EditorDraft {
        require(speed.isFinite() && speed in 0.25f..4f)
        val original = audio(draft, id)
        val consumed = original.consumedSourceUs
        require(consumed >= 1_000L && consumed % 1_000L == 0L) {
            "Audio source must align to full milliseconds"
        }
        val nextDuration = (consumed.toDouble() / speed / 1_000.0)
            .roundToLong().coerceAtLeast(1L) * 1_000L
        require(nextDuration <= projectEnd(draft) - original.startUs) {
            "Slowed audio would extend past the main footage"
        }
        val changed = original.copy(
            durationUs = nextDuration,
            playbackSpeed = speed,
            // Preserve the exact input source length when the output duration is
            // rounded to milliseconds (e.g. 4s / 0.75x = 5.333s).
            sourceSpanUs = consumed,
            speedRamp = emptyList()
        )
        return draft.copy(layers = draft.layers.copy(layers = draft.layers.layers.map {
            if (it.id == id) changed else it
        }))
    }

    /** Speed key lives on trimmed SOURCE clock, not project time. */
    fun addKeyAt(
        draft: EditorDraft, id: String, atProjectMs: Long, speed: Float
    ): EditorDraft {
        require(atProjectMs >= 0L)
        require(speed.isFinite() && speed in 0.25f..4f)
        val clip = audio(draft,id)
        val span = ((clip.consumedSourceUs + 500L) / 1_000L) * 1_000L
        require(span >= 2_000L)
        val sourceUs = clip.sourceAt(Math.multiplyExact(atProjectMs, 1_000L)) -
            clip.sourceInUs
        val offset = ((sourceUs + 500L) / 1_000L)
            .coerceIn(0L, span / 1_000L - 1L) * 1_000L
        val existing = clip.speedRamp.ifEmpty {
            listOf(SpeedKeyframe(0L,clip.playbackSpeed))
        }
        return setCurve(draft,id,SpeedRamp.upsert(span,existing,SpeedKeyframe(offset,speed)))
    }

    fun applyPreset(
        draft: EditorDraft, id: String, preset: SpeedRhythmPreset
    ): EditorDraft {
        val clip = audio(draft,id)
        val span = ((clip.consumedSourceUs + 500L) / 1_000L) * 1_000L
        return setCurve(draft,id,SpeedRhythmPresets.build(span,preset))
    }

    /** Change one source-clock audio speed key while preserving all other keys. */
    fun changeKey(
        draft: EditorDraft, id: String, sourceOffsetUs: Long, speed: Float
    ): EditorDraft {
        require(speed.isFinite() && speed in 0.25f..4f)
        val clip = audio(draft, id)
        require(clip.speedRamp.any { it.sourceOffsetUs == sourceOffsetUs }) {
            "Audio speed key no longer exists"
        }
        return setCurve(draft, id, clip.speedRamp.map {
            if (it.sourceOffsetUs == sourceOffsetUs) it.copy(speed = speed) else it
        })
    }

    /** Reposition a nonzero AUDIO key without ever moving its source-zero anchor. */
    fun moveKey(
        draft: EditorDraft, id: String, sourceOffsetUs: Long, newSourceOffsetUs: Long
    ): EditorDraft {
        val clip = audio(draft, id)
        require(sourceOffsetUs != 0L) { "The initial audio speed key cannot move" }
        require(newSourceOffsetUs > 0L &&
            newSourceOffsetUs < clip.consumedSourceUs &&
            newSourceOffsetUs % 1_000L == 0L) {
            "Audio speed key must stay at a valid source millisecond"
        }
        require(clip.speedRamp.any { it.sourceOffsetUs == sourceOffsetUs }) {
            "Audio speed key no longer exists"
        }
        require(clip.speedRamp.none {
            it.sourceOffsetUs == newSourceOffsetUs && it.sourceOffsetUs != sourceOffsetUs
        }) { "Another speed key exists at that source frame" }
        return setCurve(draft, id, clip.speedRamp.map {
            if (it.sourceOffsetUs == sourceOffsetUs)
                it.copy(sourceOffsetUs = newSourceOffsetUs) else it
        }.sortedBy { it.sourceOffsetUs })
    }

    /** Delete a nonzero AUDIO key while retaining the initial speed. */
    fun removeKey(
        draft: EditorDraft, id: String, sourceOffsetUs: Long
    ): EditorDraft {
        val clip = audio(draft, id)
        require(sourceOffsetUs != 0L) { "Cannot delete initial audio speed key" }
        require(clip.speedRamp.any { it.sourceOffsetUs == sourceOffsetUs }) {
            "Audio speed key no longer exists"
        }
        return setCurve(draft, id, clip.speedRamp.filterNot {
            it.sourceOffsetUs == sourceOffsetUs
        })
    }

    fun setCurve(
        draft: EditorDraft, id: String, points: List<SpeedKeyframe>
    ): EditorDraft {
        val clip = audio(draft,id)
        val source = ((clip.consumedSourceUs + 500L) / 1_000L) * 1_000L
        SpeedRamp.validate(source,points)
        require(clip.sourceInUs + source <= (clip.sourceDurationUs ?: Long.MAX_VALUE)) {
            "Speed curve would exceed the audio source"
        }
        val duration = SpeedRamp.durationUs(source,points)
        require(duration <= projectEnd(draft) - clip.startUs) {
            "Audio speed curve exceeds the primary video duration"
        }
        val changed = clip.copy(
            durationUs = duration,
            sourceSpanUs = source,
            speedRamp = points,
            playbackSpeed = points.first().speed
        )
        return draft.copy(layers = draft.layers.copy(layers = draft.layers.layers.map {
            if (it.id == id) changed else it
        }))
    }
}
