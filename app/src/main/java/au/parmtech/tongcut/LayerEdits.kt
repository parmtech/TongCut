package au.parmtech.tongcut

import java.util.UUID

/**
 * User-facing operations over immutable LayerTimeline snapshots.
 * All editing positions are on the project timeline, never source-clip time.
 * These functions are deliberately free of Android APIs for deterministic JVM testing.
 */
object LayerEdits {
    private fun clock(draft: EditorDraft): Long =
        SequentialTimeline(draft.clips).totalMs * 1_000L

    private fun checkedStart(draft: EditorDraft, playheadMs: Long): Long {
        val end = clock(draft)
        require(end >= 1_000) { "Add video before editing overlays" }
        return (playheadMs.coerceAtLeast(0L) * 1_000L).coerceAtMost(end - 1_000L)
    }

    private fun laneFor(timeline: LayerTimeline, kind: LayerKind, startUs: Long, endUs: Long): Pair<LayerTimeline, String> {
        // Place on an existing unlocked lane without collision; otherwise create a new lane.
        val available = timeline.tracks.firstOrNull { lane ->
            lane.kind == kind && !lane.locked && !lane.hidden && timeline.layers.none {
                it.trackId == lane.id && it.startUs < endUs && startUs < it.endUs
            }
        }
        if (available != null) return timeline to available.id
        val id = UUID.randomUUID().toString()
        val name = when (kind) {
            LayerKind.TEXT -> "Text"
            LayerKind.AUDIO -> "Audio"
            LayerKind.IMAGE -> "Image"
            LayerKind.VIDEO -> "Overlay"
            LayerKind.STICKER -> "Sticker"
        }
        return timeline.addTrack(kind, "$name ${timeline.tracks.count { it.kind == kind } + 1}", id) to id
    }

    fun addText(draft: EditorDraft, playheadMs: Long, text: String): EditorDraft {
        val clean = text.trim().take(300)
        require(clean.isNotEmpty()) { "Enter caption text" }
        val start = checkedStart(draft, playheadMs)
        val duration = minOf(3_000_000L, clock(draft) - start)
        val (graph, trackId) = laneFor(draft.layers, LayerKind.TEXT, start, start + duration)
        val newLayer = TimelineLayer(
            id = UUID.randomUUID().toString(), trackId = trackId, kind = LayerKind.TEXT,
            startUs = start, durationUs = duration, text = clean
        )
        return draft.copy(layers = graph.addLayer(newLayer))
    }

    fun addAudio(draft: EditorDraft, playheadMs: Long, uri: String, durationMs: Long): EditorDraft {
        require(uri.isNotBlank() && durationMs >= 100) { "Choose a playable audio file" }
        val start = checkedStart(draft, playheadMs)
        val duration = minOf(durationMs * 1_000L, clock(draft) - start)
        require(duration >= 1_000) { "Audio clip too short" }
        val (graph, trackId) = laneFor(draft.layers, LayerKind.AUDIO, start, start + duration)
        val newLayer = TimelineLayer(
            id = UUID.randomUUID().toString(), trackId = trackId, kind = LayerKind.AUDIO,
            startUs = start, durationUs = duration, sourceUri = uri,
            sourceDurationUs = durationMs * 1_000L
        )
        val updated = draft.copy(layers = graph.addLayer(newLayer))
        AudioMixPlan.from(updated.layers, clock(updated)) // enforce mixer limits on edit
        return updated
    }

    /** Move one primary clip's embedded sound onto an editable audio lane. */
    fun detachSourceAudio(draft: EditorDraft, clipIndex: Int): EditorDraft {
        require(clipIndex in draft.clips.indices) { "Select a video clip first" }
        val clip = draft.clips[clipIndex]
        require(!clip.sourceAudioMuted) { "Audio is already detached from this clip" }
        val timeline = SequentialTimeline(draft.clips)
        val durationUs = Math.multiplyExact(clip.editedDurationMs, 1_000L)
        val startUs = Math.multiplyExact(timeline.startOf(clipIndex), 1_000L)
        val sourceInUs = Math.multiplyExact(clip.inMs, 1_000L)
        val sourceDurationUs = Math.multiplyExact(clip.durationMs, 1_000L)
        val (graph, trackId) = laneFor(draft.layers, LayerKind.AUDIO, startUs, startUs + durationUs)
        val nextGraph = graph.addLayer(TimelineLayer(
            id = UUID.randomUUID().toString(),
            trackId = trackId,
            kind = LayerKind.AUDIO,
            startUs = startUs,
            durationUs = durationUs,
            sourceUri = clip.sourceUri,
            sourceInUs = sourceInUs,
            sourceDurationUs = sourceDurationUs
        ))
        val next = draft.copy(
            clips = draft.clips.toMutableList().also { it[clipIndex] = clip.copy(sourceAudioMuted = true) },
            layers = nextGraph
        )
        AudioMixPlan.from(next.layers, Math.multiplyExact(timeline.totalMs, 1_000L))
        return next
    }

    /** Add an independent visible clip above the primary video, preserving source media. */
    fun addVisual(
        draft: EditorDraft,
        playheadMs: Long,
        kind: LayerKind,
        sourceUri: String,
        sourceDurationMs: Long
    ): EditorDraft {
        require(kind == LayerKind.VIDEO || kind == LayerKind.IMAGE) {
            "Visual importer supports only video and still photos"
        }
        require(sourceUri.isNotBlank() && sourceDurationMs >= 100L)
        val start = checkedStart(draft, playheadMs)
        val remaining = clock(draft) - start
        val requested = Math.multiplyExact(sourceDurationMs, 1_000L)
        val duration = minOf(remaining, requested)
        require(duration >= 1_000L)
        val (graph, laneId) = laneFor(draft.layers, kind, start, start + duration)
        require(graph.tracks.count { it.kind == LayerKind.VIDEO || it.kind == LayerKind.IMAGE } <= 3) {
            "Only three independent visual overlays supported in this preview"
        }
        return draft.copy(layers = graph.addLayer(
            TimelineLayer(
                id = UUID.randomUUID().toString(),
                trackId = laneId, kind = kind,
                startUs = start, durationUs = duration,
                sourceUri = sourceUri,
                sourceDurationUs = if (kind == LayerKind.VIDEO) requested else null
            )
        ))
    }

    /**
     * Inserts a genuine paused VIDEO PiP segment: the left source finishes at the
     * playhead, a captured still IMAGE occupies the requested freeze interval,
     * and the right source resumes at the original next source frame.
     *
     * The main video clock and other independent audio/photo tracks do NOT ripple;
     * subsequent clips on this VIDEO lane shift to avoid collisions. All changes
     * live in one immutable project snapshot for undo/redo.
     *
     * [frameUri] is a persistent app-owned snapshot created by FreezeFrameCapture.
     */
    fun freezeVisualAt(
        draft: EditorDraft,
        id: String,
        playheadMs: Long,
        freezeDurationMs: Long,
        frameUri: String
    ): EditorDraft {
        require(playheadMs >= 0L && playheadMs <= Long.MAX_VALUE / 1_000L)
        require(freezeDurationMs in 100L..5_000L)
        require(frameUri.isNotBlank())
        val source = draft.layers.layers.firstOrNull {
            it.id == id && it.kind == LayerKind.VIDEO
        } ?: throw IllegalArgumentException("Select a video overlay to freeze")
        val track = draft.layers.tracks.first { it.id == source.trackId }
        require(!track.locked) { "Unlock this video lane first" }
        val cut = playheadMs * 1_000L
        require(cut >= source.startUs + 100_000L &&
            cut <= source.endUs - 100_000L) {
            "Move playhead at least 0.1s inside the video overlay"
        }
        val freezeUs = freezeDurationMs * 1_000L
        val videoDurationUs = clock(draft)
        val following = draft.layers.layers.filter {
            it.trackId == source.trackId && it.id != id && it.startUs >= source.endUs
        }
        require(source.endUs <= videoDurationUs - freezeUs &&
            following.all { it.endUs <= videoDurationUs - freezeUs }) {
            "Freeze needs more space before the end of the primary video"
        }
        val graph = draft.layers.splitLayer(id,cut)
        val left = graph.layers.firstOrNull { it.id == id }
            ?: throw IllegalStateException("Freeze split lost the source")
        val right = graph.layers.first {
            it.trackId == source.trackId && it.id != id &&
                it.sourceInUs >= source.sourceInUs &&
                it.startUs >= cut && it.startUs < source.endUs
        }
        val actualCut = left.endUs
        // If a speed-ramp split snaps by 1ms, anchor the frozen image to the
        // actual rendered boundary rather than opening a pixel-sized gap.
        val followingIds = following.map { it.id }.toSet()
        val movedVideo = graph.copy(layers = graph.layers.map { layer ->
            when {
                layer.id == right.id -> layer.copy(startUs = actualCut + freezeUs)
                layer.id in followingIds ->
                    layer.copy(startUs = layer.startUs + freezeUs)
                else -> layer
            }
        })
        val snapshotPose = MotionCurve.at(
            source.motion, (actualCut - source.startUs).coerceIn(0L,source.durationUs)
        )
        val frozenAlpha = source.fade.alphaAt(
            (actualCut - source.startUs).coerceIn(0L,source.durationUs),
            source.durationUs
        )
        val (withTrack, photoLaneId) = laneFor(
            movedVideo, LayerKind.IMAGE, actualCut, actualCut + freezeUs
        )
        require(withTrack.tracks.count { track ->
            track.kind in setOf(LayerKind.VIDEO,LayerKind.IMAGE) &&
                withTrack.layers.any { it.trackId == track.id }
        } <= VisualMixPlan.MAX_VISUAL_LANES) {
            "Freezing would exceed the three visible-overlay lane limit"
        }
        val image = TimelineLayer(
            id = UUID.randomUUID().toString(),
            trackId = photoLaneId,
            visualSlot = VisualOverlaySlots.slotFor(draft.layers,source),
            kind = LayerKind.IMAGE,
            startUs = actualCut,
            durationUs = freezeUs,
            sourceUri = frameUri,
            motion = listOf(MotionKeyframe(
                0L,snapshotPose.copy(opacity = snapshotPose.opacity * frozenAlpha)
            ))
        )
        return draft.copy(layers = withTrack.addLayer(image))
    }

    /** Constant-speed part of P3 speed work; ramp curves need a separate source clock. */
    fun setVisualSpeed(draft: EditorDraft, id: String, speed: Float): EditorDraft {
        require(speed.isFinite() && speed in 0.25f..4f)
        val layer = draft.layers.layers.firstOrNull { it.id == id && it.kind == LayerKind.VIDEO }
            ?: throw IllegalArgumentException("Video overlay not found")
        require(!draft.layers.tracks.first { it.id == layer.trackId }.locked)
        val sourceSpan = layer.consumedSourceUs.toDouble()
        // Keep all clip edit boundaries at Media3's millisecond resolution.
        val newDurationUs = (
            kotlin.math.round(sourceSpan / speed / 1_000.0).toLong() * 1_000L
        ).coerceAtLeast(1_000L)
        require(layer.startUs <= clock(draft) - newDurationUs) {
            "Speed-adjusted video no longer fits the main footage"
        }
        val rebuilt = layer.copy(
            durationUs = newDurationUs,
            playbackSpeed = speed,
            sourceSpanUs = null,
            speedRamp = emptyList(),
            motion = layer.motion.map { key ->
                key.copy(timeUs = (
                    key.timeUs.toDouble() * newDurationUs / layer.durationUs
                ).toLong().coerceIn(0L,newDurationUs))
            }.distinctBy { it.timeUs }
        )
        return draft.copy(layers = draft.layers.copy(layers = draft.layers.layers.map {
            if (it.id == id) rebuilt else it
        }))
    }

    /**
     * Insert/modify the VIDEO PiP speed at the playhead.
     * Existing source frames are retained; only their presentation timing changes.
     */
    fun setSpeedKeyAt(draft: EditorDraft, id: String, playheadMs: Long, speed: Float): EditorDraft {
        require(speed.isFinite() && speed in 0.25f..4f)
        val layer = draft.layers.layers.firstOrNull { it.id == id && it.kind == LayerKind.VIDEO }
            ?: throw IllegalArgumentException("Missing video overlay")
        require(!draft.layers.tracks.first { it.id == layer.trackId }.locked)
        val span = ((layer.consumedSourceUs + 500L) / 1_000L) * 1_000L
        require(span >= 2_000L)
        val oldPoints = if (layer.speedRamp.isNotEmpty()) layer.speedRamp else
            listOf(SpeedKeyframe(0L, layer.playbackSpeed))
        val relativeUs = (
            (layer.sourceAt(playheadMs.coerceAtLeast(0L) * 1_000L) -
                layer.sourceInUs + 500L) / 1_000L
        ).coerceIn(0L, span / 1_000L - 1L) * 1_000L
        val points = SpeedRamp.upsert(span, oldPoints, SpeedKeyframe(relativeUs, speed))
        return applySpeedPoints(draft, layer, points)
    }

    /**
     * Mutate the true source-clock speed curve. Changing tempo moves every later
     * project timestamp. Re-map existing motion keys to their unchanged source
     * frames and keep fade handles proportionate to the new authored duration.
     */
    private fun applySpeedPoints(
        draft: EditorDraft, layer: TimelineLayer, points: List<SpeedKeyframe>
    ): EditorDraft {
        val sourceSpanUs = ((layer.consumedSourceUs + 500L) / 1_000L) * 1_000L
        SpeedRamp.validate(sourceSpanUs, points)
        require(layer.fade.phaseDurationUs == null) {
            "Speed editing on a previously split fade needs a reset fade first"
        }
        val newDuration = SpeedRamp.durationUs(sourceSpanUs, points)
        val videoEnd = clock(draft)
        require(layer.startUs <= videoEnd && newDuration <= videoEnd - layer.startUs) {
            "Changed speed extends past the project's video"
        }
        val remapped = layer.motion.map { key ->
            val sourceOffset = layer.sourceAt(layer.startUs + key.timeUs) - layer.sourceInUs
            key.copy(timeUs = SpeedRamp.outputAtSource(
                sourceOffset.coerceIn(0L, sourceSpanUs), sourceSpanUs, points
            ).coerceIn(0L, newDuration))
        }.distinctBy { it.timeUs }.sortedBy { it.timeUs }
        val oldFade = layer.fade.resetToClip()
        val newFadeIn = (oldFade.inUs.toDouble() * newDuration / layer.durationUs)
            .toLong().coerceIn(0L, newDuration)
        val newFadeOut = (oldFade.outUs.toDouble() * newDuration / layer.durationUs)
            .toLong().coerceIn(0L, newDuration - newFadeIn)
        val updated = layer.copy(
            durationUs = newDuration,
            sourceSpanUs = sourceSpanUs,
            speedRamp = points,
            motion = remapped,
            fade = LayerFade(newFadeIn, newFadeOut)
        )
        return draft.copy(layers = draft.layers.copy(layers = draft.layers.layers.map {
            if (it.id == layer.id) updated else it
        }))
    }

    /**
     * CapCut-style speed rhythm presets, represented as REAL source-clock speed keys.
     * Uses the same deterministic Media3 SpeedProvider path as manually edited ramps.
     * Every boundary is millisecond aligned; the whole change is one undoable edit.
     */
    fun applySpeedPreset(
        draft: EditorDraft, id: String, preset: SpeedRhythmPreset
    ): EditorDraft {
        val layer = draft.layers.layers.firstOrNull { it.id == id && it.kind == LayerKind.VIDEO }
            ?: throw IllegalArgumentException("Select a video overlay")
        require(!draft.layers.tracks.first { it.id == layer.trackId }.locked) {
            "Unlock the video track first"
        }
        val span = ((layer.consumedSourceUs + 500L) / 1_000L) * 1_000L
        val keys = SpeedRhythmPresets.build(span, preset)
        return applySpeedPoints(draft, layer, keys)
    }

    /** A direct speed-graph edit; source-relative offset must match an existing key. */
    fun changeSpeedKey(
        draft: EditorDraft, id: String, sourceOffsetUs: Long, speed: Float
    ): EditorDraft {
        require(speed.isFinite() && speed in 0.25f..4f)
        val layer = draft.layers.layers.firstOrNull { it.id == id && it.kind == LayerKind.VIDEO }
            ?: throw IllegalArgumentException("Video layer not found")
        require(!draft.layers.tracks.first { it.id == layer.trackId }.locked) {
            "Unlock the layer before changing speed"
        }
        require(layer.speedRamp.any { it.sourceOffsetUs == sourceOffsetUs }) {
            "Speed point no longer exists"
        }
        return applySpeedPoints(draft, layer, layer.speedRamp.map {
            if (it.sourceOffsetUs == sourceOffsetUs) it.copy(speed = speed) else it
        })
    }

    /** Move an existing speed control point on the trimmed SOURCE clock. */
    fun moveSpeedKey(
        draft: EditorDraft, id: String, sourceOffsetUs: Long, newSourceOffsetUs: Long
    ): EditorDraft {
        val layer = draft.layers.layers.firstOrNull { it.id == id && it.kind == LayerKind.VIDEO }
            ?: throw IllegalArgumentException("Video layer not found")
        require(!draft.layers.tracks.first { it.id == layer.trackId }.locked) {
            "Unlock the layer before changing speed"
        }
        require(sourceOffsetUs != 0L) { "The first speed key must start at source zero" }
        require(layer.speedRamp.any { it.sourceOffsetUs == sourceOffsetUs }) {
            "Selected speed point not found"
        }
        require(newSourceOffsetUs >= 1_000L && newSourceOffsetUs % 1_000L == 0L &&
            newSourceOffsetUs < layer.consumedSourceUs) { "Speed point out of source range" }
        require(layer.speedRamp.none {
            it.sourceOffsetUs == newSourceOffsetUs && it.sourceOffsetUs != sourceOffsetUs
        }) { "Another speed point already occupies that frame" }
        val points = layer.speedRamp.map {
            if (it.sourceOffsetUs == sourceOffsetUs) it.copy(sourceOffsetUs = newSourceOffsetUs) else it
        }.sortedBy { it.sourceOffsetUs }
        return applySpeedPoints(draft, layer, points)
    }

    fun removeSpeedKey(draft: EditorDraft, id: String, sourceOffsetUs: Long): EditorDraft {
        val layer = draft.layers.layers.firstOrNull { it.id == id && it.kind == LayerKind.VIDEO }
            ?: throw IllegalArgumentException("Video layer not found")
        require(!draft.layers.tracks.first { it.id == layer.trackId }.locked) {
            "Unlock the layer before changing speed"
        }
        require(sourceOffsetUs != 0L) { "The initial speed key cannot be removed" }
        require(layer.speedRamp.any { it.sourceOffsetUs == sourceOffsetUs }) {
            "Selected speed point no longer exists"
        }
        return applySpeedPoints(draft, layer,
            layer.speedRamp.filterNot { it.sourceOffsetUs == sourceOffsetUs })
    }

    /** Change the persisted photograph alpha matte; both preview and export share the renderer. */
    /** Visual fades are independent from pose/opacity keyframes. */
    fun setLayerFade(draft: EditorDraft, id: String, fade: LayerFade): EditorDraft {
        val layer = draft.layers.layers.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Missing visual layer")
        require(layer.kind == LayerKind.VIDEO || layer.kind == LayerKind.IMAGE ||
            layer.kind == LayerKind.TEXT) { "Only visible layers support fades" }
        require(!draft.layers.tracks.first { it.id == layer.trackId }.locked) {
            "Unlock the track before changing fades"
        }
        fade.validate(layer.durationUs)
        return draft.copy(layers = draft.layers.copy(layers = draft.layers.layers.map {
            if (it.id == id) it.copy(fade = fade) else it
        }))
    }

    fun setPhotoEffects(draft: EditorDraft, id: String, effects: PhotoEffects): EditorDraft {
        val photo = draft.layers.layers.firstOrNull { it.id == id && it.kind == LayerKind.IMAGE }
            ?: throw IllegalArgumentException("Photo layer not found")
        require(!draft.layers.tracks.first { it.id == photo.trackId }.locked) {
            "Unlock the photo track first"
        }
        return draft.copy(layers = draft.layers.copy(layers = draft.layers.layers.map {
            if (it.id == id) it.copy(photoEffects = effects) else it
        }))
    }

    /**
     * P3 video-only GPU matte, sharing the validated PhotoEffects model and
     * serialized "photoFx" key for backward-compatible project migration.
     * Preview warns until the real-time video shader is acceptance-tested.
     */
    fun setVideoEffects(draft: EditorDraft, id: String, effects: PhotoEffects): EditorDraft {
        val video = draft.layers.layers.firstOrNull {
            it.id == id && it.kind == LayerKind.VIDEO
        } ?: throw IllegalArgumentException("Video overlay not found")
        require(!draft.layers.tracks.first { it.id == video.trackId }.locked) {
            "Unlock the video track before changing its matte"
        }
        return draft.copy(layers = draft.layers.copy(layers = draft.layers.layers.map {
            if (it.id == id) it.copy(photoEffects = effects) else it
        }))
    }

    /**
     * Relink an overlay to replacement media WITHOUT resetting trims, speed
     * ramps, motion, fade or photomatte. Reject shorter sources rather than
     * silently shortening a clip and losing authored edits.
     */
    fun relinkLayer(
        draft: EditorDraft, id: String, newUri: String, newDurationMs: Long? = null
    ): EditorDraft {
        require(newUri.isNotBlank())
        val layer = draft.layers.layers.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Layer no longer exists")
        require(layer.kind in setOf(LayerKind.VIDEO,LayerKind.AUDIO,LayerKind.IMAGE)) {
            "This layer cannot be relinked"
        }
        require(!draft.layers.tracks.first { it.id == layer.trackId }.locked) {
            "Unlock this layer before reconnecting media"
        }
        val replacementDurationUs = if (layer.kind == LayerKind.IMAGE) null else {
            val ms = requireNotNull(newDurationMs) { "Replacement duration required" }
            require(ms >= 100L && ms <= Long.MAX_VALUE / 1_000L)
            Math.multiplyExact(ms,1_000L)
        }
        if (replacementDurationUs != null) {
            val required = Math.addExact(
                layer.sourceInUs,
                if (layer.kind == LayerKind.VIDEO || layer.kind == LayerKind.AUDIO)
                    layer.consumedSourceUs else layer.durationUs
            )
            require(replacementDurationUs >= required) {
                "Replacement source is too short for this layer's saved trim"
            }
        }
        return draft.copy(layers = draft.layers.copy(layers = draft.layers.layers.map {
            if (it.id == id) it.copy(
                sourceUri=newUri,sourceDurationUs=replacementDurationUs
            ) else it
        }))
    }

    fun changeText(draft: EditorDraft, id: String, text: String): EditorDraft {
        val clean = text.trim().take(300)
        require(clean.isNotEmpty()) { "Caption cannot be blank" }
        val match = draft.layers.layers.firstOrNull { it.id == id && it.kind == LayerKind.TEXT }
            ?: throw IllegalArgumentException("Text layer not found")
        require(!draft.layers.tracks.first { it.id == match.trackId }.locked) { "Track is locked" }
        return draft.copy(layers = draft.layers.copy(layers = draft.layers.layers.map {
            if (it.id == id) it.copy(text = clean) else it
        }))
    }

    /** One undoable style edit; no changes to caption timing or other clips. */
    fun changeCaptionStyle(
        draft: EditorDraft, id: String, style: CaptionStyle
    ): EditorDraft {
        val layer = draft.layers.layers.firstOrNull {
            it.id == id && it.kind == LayerKind.TEXT
        } ?: throw IllegalArgumentException("Text layer not found")
        val track = draft.layers.tracks.first { it.id == layer.trackId }
        require(!track.locked) { "Unlock this caption track before styling" }
        return draft.copy(layers = draft.layers.copy(layers = draft.layers.layers.map {
            if (it.id == id) it.copy(captionStyle = style) else it
        }))
    }

    fun remove(draft: EditorDraft, id: String): EditorDraft =
        draft.copy(layers = draft.layers.removeLayer(id))

    fun setTrackVisibility(draft: EditorDraft, trackId: String, hidden: Boolean): EditorDraft =
        draft.copy(layers = draft.layers.copy(tracks = draft.layers.tracks.map {
            if (it.id == trackId) it.copy(hidden = hidden) else it
        }))

    /** Lock a lane while preserving its current edit state. Unlock remains available. */
    fun setTrackLocked(draft: EditorDraft, trackId: String, locked: Boolean): EditorDraft {
        require(draft.layers.tracks.any { it.id == trackId }) { "Missing track" }
        return draft.copy(layers = draft.layers.setTrackLocked(trackId, locked))
    }

    fun setTrackMuted(draft: EditorDraft, trackId: String, muted: Boolean): EditorDraft =
        draft.copy(layers = draft.layers.copy(tracks = draft.layers.tracks.map {
            if (it.id == trackId) it.copy(muted = muted) else it
        }))

    fun setAudioGain(draft: EditorDraft, trackId: String, gain: Float): EditorDraft {
        require(gain.isFinite() && gain in 0f..1f)
        val lane = draft.layers.tracks.firstOrNull { it.id == trackId }
            ?: throw IllegalArgumentException("Audio track not found")
        require(lane.kind == LayerKind.AUDIO && !lane.locked) { "Not an editable audio track" }
        return draft.copy(layers = draft.layers.copy(tracks = draft.layers.tracks.map {
            if (it.id == trackId) it.copy(gain = gain) else it
        }))
    }

    fun setAudioEqualizer(
        draft: EditorDraft, trackId: String, equalizer: AudioEqualizer
    ): EditorDraft {
        val lane = draft.layers.tracks.firstOrNull { it.id == trackId }
            ?: throw IllegalArgumentException("Audio track not found")
        require(lane.kind == LayerKind.AUDIO && !lane.locked) {
            "Unlock the audio track first"
        }
        return draft.copy(layers = draft.layers.copy(tracks = draft.layers.tracks.map {
            if (it.id == trackId) it.copy(equalizer = equalizer) else it
        }))
    }

    /** Mark a dialogue/voiceover lane as the source that ducks other audio. */
    fun setAudioDuckingSource(
        draft: EditorDraft, trackId: String, enabled: Boolean
    ): EditorDraft {
        val lane = draft.layers.tracks.firstOrNull { it.id == trackId }
            ?: throw IllegalArgumentException("Audio track not found")
        require(lane.kind == LayerKind.AUDIO && !lane.locked) {
            "Unlock the audio track first"
        }
        return draft.copy(layers = draft.layers.copy(tracks = draft.layers.tracks.map {
            if (it.id == trackId) it.copy(duckingSource = enabled) else it
        }))
    }

    fun setAudioDuckingGain(draft: EditorDraft, trackId: String, gain: Float): EditorDraft {
        require(gain.isFinite() && gain in 0.05f..1f) {
            "Dialogue duck level must be 5–100%"
        }
        val lane = draft.layers.tracks.firstOrNull { it.id == trackId }
            ?: throw IllegalArgumentException("Audio track not found")
        require(lane.kind == LayerKind.AUDIO && !lane.locked) {
            "Unlock the audio track first"
        }
        return draft.copy(layers = draft.layers.copy(tracks = draft.layers.tracks.map {
            if (it.id == trackId) it.copy(duckingGain = gain) else it
        }))
    }

    /** Adds or replaces one clip-local envelope point at the current project playhead. */
    fun setAudioEnvelopeAt(
        draft: EditorDraft, id: String, playheadMs: Long, gain: Float
    ): EditorDraft {
        require(gain.isFinite() && gain in 0f..1f)
        val layer = draft.layers.layers.firstOrNull { it.id == id && it.kind == LayerKind.AUDIO }
            ?: throw IllegalArgumentException("Audio clip not found")
        require(!draft.layers.tracks.first { it.id == layer.trackId }.locked) {
            "Unlock the audio track first"
        }
        val relativeUs = (playheadMs.coerceAtLeast(0L) * 1_000L - layer.startUs)
            .coerceIn(0L, layer.durationUs)
        val updated = AudioEnvelope.upsert(layer.durationUs, layer.audioEnvelope,
            AudioGainKeyframe(relativeUs, gain))
        return draft.copy(layers = draft.layers.copy(layers = draft.layers.layers.map {
            if (it.id == id) it.copy(audioEnvelope = updated) else it
        }))
    }

    fun clearAudioEnvelope(draft: EditorDraft, id: String): EditorDraft {
        val layer = draft.layers.layers.firstOrNull { it.id == id && it.kind == LayerKind.AUDIO }
            ?: throw IllegalArgumentException("Audio clip not found")
        require(!draft.layers.tracks.first { it.id == layer.trackId }.locked) {
            "Unlock the audio track first"
        }
        return draft.copy(layers = draft.layers.copy(layers = draft.layers.layers.map {
            if (it.id == id) it.copy(audioEnvelope = emptyList()) else it
        }))
    }

    /** Replace a clip envelope with project-time fade handles as one undoable edit. */
    fun setAudioFades(
        draft: EditorDraft, id: String, fadeInMs: Long, fadeOutMs: Long
    ): EditorDraft {
        require(fadeInMs >= 0L && fadeOutMs >= 0L)
        val layer = draft.layers.layers.firstOrNull { it.id == id && it.kind == LayerKind.AUDIO }
            ?: throw IllegalArgumentException("Audio clip not found")
        require(!draft.layers.tracks.first { it.id == layer.trackId }.locked) {
            "Unlock the audio track first"
        }
        val envelope = AudioEnvelope.fades(
            layer.durationUs, Math.multiplyExact(fadeInMs, 1_000L),
            Math.multiplyExact(fadeOutMs, 1_000L)
        )
        return draft.copy(layers = draft.layers.copy(layers = draft.layers.layers.map {
            if (it.id == id) it.copy(audioEnvelope = envelope) else it
        }))
    }

    /** Set or replace a motion keyframe at the closest rational project frame. */
    fun setMotionAt(
        draft: EditorDraft,
        id: String,
        playheadMs: Long,
        interpolation: MotionInterpolation = MotionInterpolation.LINEAR,
        mutate: (MotionPose) -> MotionPose
    ): EditorDraft {
        val layer = draft.layers.layers.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Missing visual layer")
        require(layer.kind != LayerKind.AUDIO) { "Audio motion is unsupported" }
        val requested = Math.multiplyExact(playheadMs, 1_000L) - layer.startUs
        val relative = draft.layers.rate.nearestFrameUs(
            requested.coerceIn(0L, layer.durationUs)
        ).coerceIn(0L, layer.durationUs)
        val pose = MotionCurve.at(layer.motion, relative)
        return draft.copy(layers = draft.layers.upsertMotion(
            id, MotionKeyframe(relative, mutate(pose), interpolation)
        ))
    }

    /** Edits one existing key, preserving clip duration and other animation tracks. */
    private fun changeMotionKey(
        draft: EditorDraft, layerId: String, keyTimeUs: Long,
        change: (MotionKeyframe) -> MotionKeyframe
    ): EditorDraft {
        val layer = draft.layers.layers.firstOrNull { it.id == layerId }
            ?: throw IllegalArgumentException("Motion layer missing")
        require(layer.kind in setOf(LayerKind.VIDEO, LayerKind.IMAGE, LayerKind.TEXT))
        require(!draft.layers.tracks.first { it.id == layer.trackId }.locked) {
            "Unlock the track first"
        }
        require(layer.motion.any { it.timeUs == keyTimeUs }) { "Selected keyframe no longer exists" }
        val keys = layer.motion.map { if (it.timeUs == keyTimeUs) change(it) else it }
            .sortedBy { it.timeUs }
        MotionCurve.validate(keys, layer.durationUs)
        return draft.copy(layers = draft.layers.copy(layers = draft.layers.layers.map {
            if (it.id == layerId) it.copy(motion = keys) else it
        }))
    }

    fun setMotionKeyPose(
        draft: EditorDraft, layerId: String, keyTimeUs: Long, pose: MotionPose
    ): EditorDraft = changeMotionKey(draft, layerId, keyTimeUs) { it.copy(pose = pose) }

    fun setMotionKeyInterpolation(
        draft: EditorDraft, layerId: String, keyTimeUs: Long,
        interpolation: MotionInterpolation
    ): EditorDraft = changeMotionKey(draft, layerId, keyTimeUs) {
        it.copy(interpolation = interpolation)
    }

    fun moveMotionKey(
        draft: EditorDraft, layerId: String, keyTimeUs: Long, destinationUs: Long
    ): EditorDraft {
        val layer = draft.layers.layers.firstOrNull { it.id == layerId }
            ?: throw IllegalArgumentException("Motion layer missing")
        require(destinationUs >= 0L) { "Keyframe cannot move before the clip" }
        val snapped = draft.layers.rate.nearestFrameUs(
            destinationUs.coerceAtMost(layer.durationUs)
        ).coerceAtMost(layer.durationUs)
        require(layer.motion.none { it.timeUs == snapped && it.timeUs != keyTimeUs }) {
            "A keyframe already exists on this frame"
        }
        return changeMotionKey(draft, layerId, keyTimeUs) { it.copy(timeUs = snapped) }
    }

    fun removeMotionKey(draft: EditorDraft, layerId: String, keyTimeUs: Long): EditorDraft {
        val layer = draft.layers.layers.firstOrNull { it.id == layerId }
            ?: throw IllegalArgumentException("Motion layer missing")
        require(!draft.layers.tracks.first { it.id == layer.trackId }.locked) {
            "Unlock the track first"
        }
        require(layer.motion.any { it.timeUs == keyTimeUs }) { "Selected keyframe missing" }
        return draft.copy(layers = draft.layers.copy(layers = draft.layers.layers.map {
            if (it.id == layerId) it.copy(
                motion = it.motion.filterNot { key -> key.timeUs == keyTimeUs }
            ) else it
        }))
    }

    /**
     * Split any renderable independent layer at the current project playhead.
     * Text/photo copies remain static media, while video/audio are source-trimmed.
     * Reject cuts closer than 100ms to an edge, preserving export-safe intervals.
     */
    fun splitLayerAtFrame(draft: EditorDraft, id: String, requestedUs: Long): EditorDraft {
        val layer = draft.layers.layers.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Timeline layer not found")
        val cut = snappedEditUs(draft, requestedUs)
        require(cut >= layer.startUs + 100_000L && cut <= layer.endUs - 100_000L) {
            "Move playhead at least 0.1 seconds inside the layer"
        }
        return draft.copy(layers = draft.layers.splitLayer(id, cut))
    }

    /**
     * Trim the source/start of an independent layer by its left handle.
     * The remaining right-side layer keeps the original ID, all visual effects,
     * timeline track and stable PiP slot. Removing only the discarded left half
     * preserves ramp motion/fade phase and the exact trimmed media source.
     */
    fun trimLayerStartAtFrame(draft: EditorDraft, id: String, requestedUs: Long): EditorDraft {
        val layer = draft.layers.layers.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Timeline layer not found")
        val cut = snappedEditUs(draft, requestedUs)
        require(cut <= layer.endUs - 100_000L) {
            "At least 0.1 seconds of this layer must remain"
        }
        if (cut == layer.startUs) return draft
        if (cut < layer.startUs) {
            require(layer.speedRamp.isEmpty() && layer.fade.phaseDurationUs == null) {
                "Use Undo before extending a split speed-ramped or faded clip"
            }
            val extension = layer.startUs - cut
            val consumed = when (layer.kind) {
                LayerKind.VIDEO -> (extension.toDouble() * layer.playbackSpeed).toLong()
                LayerKind.AUDIO ->
                    (extension.toDouble() * layer.playbackSpeed).toLong()
                else -> 0L
            }
            require(consumed <= layer.sourceInUs) {
                "The source has no earlier footage to extend into"
            }
            // Media3 source clipping is precise only to 1ms.
            require(consumed % 1_000L == 0L) {
                "Adjust trim to a full source millisecond for this playback speed"
            }
            val prepend = if (layer.motion.isEmpty()) emptyList() else listOf(
                MotionKeyframe(0L, MotionCurve.at(layer.motion, 0),
                    MotionInterpolation.HOLD)
            )
            val shifted = prepend + layer.motion.map {
                it.copy(timeUs = it.timeUs + extension)
            }
            val extendedAudioEnvelope = if (layer.kind == LayerKind.AUDIO) {
                if (layer.audioEnvelope.isEmpty() || layer.audioEnvelope.all { it.gain == 1f }) {
                    emptyList()
                } else {
                    val startingGain = AudioEnvelope.gainAt(
                        0L, layer.durationUs, layer.audioEnvelope
                    )
                    listOf(AudioGainKeyframe(0L, startingGain)) +
                        layer.audioEnvelope.map { point ->
                            point.copy(timeUs = Math.addExact(point.timeUs, extension))
                        }
                }
            } else layer.audioEnvelope
            val newLayer = layer.copy(
                startUs = cut,
                durationUs = layer.durationUs + extension,
                sourceInUs = layer.sourceInUs - consumed,
                sourceSpanUs = if (layer.kind == LayerKind.AUDIO &&
                    layer.sourceSpanUs != null)
                    requireNotNull(layer.sourceSpanUs) + consumed
                    else layer.sourceSpanUs,
                motion = shifted,
                audioEnvelope = extendedAudioEnvelope,
                fade = layer.fade.resetToClip()
            )
            return draft.copy(layers = draft.layers.copy(layers = draft.layers.layers.map {
                if (it.id == id) newLayer else it
            }))
        }
        val split = draft.layers.splitLayer(id, cut)
        val tail = split.layers.first { it.trackId == layer.trackId &&
            it.id != id && it.startUs >= cut && it.startUs < layer.endUs }
        val updated = split.copy(layers = split.layers.filterNot {
            it.id == id || it.id == tail.id
        } + tail.copy(id = id))
        return draft.copy(layers = updated)
    }

    /**
     * Right handle: splitting discards the source tail while preserving all
     * interpolated effects. Extension is allowed for static media or when the
     * full source-duration validation still passes.
     */
    fun trimLayerEndAtFrame(draft: EditorDraft, id: String, requestedUs: Long): EditorDraft {
        val layer = draft.layers.layers.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Timeline layer not found")
        val cut = snappedEditUs(draft, requestedUs)
        val total = clock(draft)
        require(cut <= total) { "Layer cannot exceed the main video" }
        require(cut >= layer.startUs + 100_000L) {
            "At least 0.1 seconds of this layer must remain"
        }
        if (cut == layer.endUs) return draft
        val next = if (cut < layer.endUs) {
            val split = draft.layers.splitLayer(id, cut)
            split.copy(layers = split.layers.filterNot { it.id != id &&
                it.trackId == layer.trackId && it.startUs >= cut &&
                it.endUs <= layer.endUs })
        } else draft.layers.resizeLayer(id, cut - layer.startUs)
        return draft.copy(layers = next)
    }

    /** Frame snap followed by export-compatible millisecond source boundaries. */
    private fun snappedEditUs(draft: EditorDraft, requestedUs: Long): Long {
        require(requestedUs >= 0L)
        val total = clock(draft)
        val aligned = draft.layers.rate.nearestFrameUs(requestedUs.coerceAtMost(total))
        return ((aligned / 1_000L) + if (aligned % 1_000L >= 500L) 1 else 0) *
            1_000L
    }

    fun trimLayer(draft: EditorDraft, id: String, durationUs: Long): EditorDraft =
        draft.copy(layers = draft.layers.resizeLayer(id, durationUs.coerceAtLeast(1_000L)))

    fun placeLayer(draft: EditorDraft, id: String, positionMs: Long): EditorDraft {
        require(positionMs >= 0)
        val duration = draft.layers.layers.firstOrNull { it.id == id }?.durationUs
            ?: throw IllegalArgumentException("Missing layer")
        val end = clock(draft)
        require(duration <= end)
        val pos = (positionMs * 1_000L).coerceAtMost(end - duration)
        val track = draft.layers.layers.first { it.id == id }.trackId
        return draft.copy(layers = draft.layers.moveLayer(id, track, pos))
    }
    /** Single gesture commit, snapped to the project's rational-frame timebase. */
    fun placeLayerAtFrame(draft: EditorDraft, id: String, requestedStartUs: Long): EditorDraft {
        val layer = draft.layers.layers.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Missing layer")
        val total = clock(draft)
        require(layer.durationUs <= total) { "Layer does not fit the video" }
        val maximum = total - layer.durationUs
        val bounded = requestedStartUs.coerceIn(0L, maximum)
        val frameOnly = draft.layers.rate.nearestFrameUs(bounded).coerceIn(0L, maximum)
        val magnetic = TimelineSnap.move(draft, id, requestedStartUs)
        // Reject a collision instead of allowing two clips on a lane to overlap.
        // If the magnetic candidate collides, try the independently snapped position.
        val moved = runCatching { draft.layers.moveLayer(id, layer.trackId, magnetic) }
            .getOrElse { draft.layers.moveLayer(id, layer.trackId, frameOnly) }
        return draft.copy(layers = moved)
    }

}
