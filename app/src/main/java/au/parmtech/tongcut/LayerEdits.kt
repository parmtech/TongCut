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
            lane.kind == kind && !lane.locked && timeline.layers.none {
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
