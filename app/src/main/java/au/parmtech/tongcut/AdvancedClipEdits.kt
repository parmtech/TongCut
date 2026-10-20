package au.parmtech.tongcut

import java.util.UUID

/**
 * P3 non-destructive clipboard/time operations for independent layered clips.
 *
 * All operations return a NEW EditorDraft: a single user gesture adds one undoable
 * history snapshot. The primary video sequence is never silently shifted.
 * Project timestamps and media source timestamps are separate clocks.
 */
object AdvancedClipEdits {
    private fun projectEndUs(draft: EditorDraft): Long =
        Math.multiplyExact(SequentialTimeline(draft.clips).totalMs, 1_000L)

    private fun editable(draft: EditorDraft, id: String): TimelineLayer {
        val layer = draft.layers.layers.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Layer no longer exists")
        require(draft.layers.tracks.first { it.id == layer.trackId }.locked.not()) {
            "Unlock the track before editing its clips"
        }
        require(layer.kind != LayerKind.STICKER) {
            "Sticker rendering is not ready for advanced edits"
        }
        return layer
    }

    /** 
     * Clone the entire non-destructive layer state at the selected project time:
     * media URI/source in/trim, speed keys, motion keys, photo mask and fade.
     *
     * If the source lane is occupied at the destination, use a new lane
     * with matching mute/visibility/gain; never alter another clip or
     * silently change how its audio sounds.
     */
    fun duplicateAt(
        draft: EditorDraft, layerId: String, destinationMs: Long
    ): EditorDraft {
        val original = editable(draft, layerId)
        require(destinationMs >= 0L) { "Choose a timeline position at or after zero" }
        val start = Math.multiplyExact(destinationMs, 1_000L)
        val total = projectEndUs(draft)
        require(original.durationUs <= total && start <= total - original.durationUs) {
            "Duplicated clip does not fit inside the main video"
        }
        val lane = draft.layers.tracks.first { it.id == original.trackId }
        val sameLaneFree = draft.layers.layers.none { item ->
            item.trackId == lane.id &&
                item.startUs < start + original.durationUs &&
                start < item.endUs
        }
        val nextTrack = if (sameLaneFree) null else {
            lane.copy(
                id = UUID.randomUUID().toString(),
                name = (lane.name + " copy").take(120),
                order = (draft.layers.tracks.maxOfOrNull { it.order } ?: -1) + 1,
                locked = false
            )
        }
        val duplicate = original.copy(
            id = UUID.randomUUID().toString(),
            trackId = nextTrack?.id ?: lane.id,
            startUs = start
        )
        val graph = draft.layers.copy(
            tracks = if (nextTrack == null) draft.layers.tracks else
                draft.layers.tracks + nextTrack,
            layers = draft.layers.layers + duplicate
        )
        if (original.kind in setOf(LayerKind.VIDEO, LayerKind.IMAGE)) {
            val visualTracks = graph.tracks.count {
                it.kind in setOf(LayerKind.VIDEO, LayerKind.IMAGE) &&
                    graph.layers.any { layer -> layer.trackId == it.id }
            }
            require(visualTracks <= VisualMixPlan.MAX_VISUAL_LANES) {
                "Only three picture-in-picture visual lanes are currently supported"
            }
            // Validates selected lanes, project bounds and exportable scheduling.
            VisualMixPlan.from(graph, total)
        }
        if (original.kind == LayerKind.AUDIO) {
            AudioMixPlan.from(graph, total)
        }
        return draft.copy(layers = graph)
    }

    /**
     * Slip an audio/video source beneath an unmoved timeline window.
     * Positive delta shows later source media. Ramped VIDEO clips preserve their
     * entire source-relative speed curve and the same output duration.
     *
     * Requires source duration metadata: without a known upper bound, source
     * sliding could silently request frames past EOF during export.
     */
    fun slipSourceBy(
        draft: EditorDraft, layerId: String, deltaMs: Long
    ): EditorDraft {
        val original = editable(draft, layerId)
        require(original.kind == LayerKind.VIDEO || original.kind == LayerKind.AUDIO) {
            "Slip is available only for video and audio sources"
        }
        val totalSource = requireNotNull(original.sourceDurationUs) {
            "Source duration unknown; relink this file before slipping"
        }
        val deltaUs = Math.multiplyExact(deltaMs, 1_000L)
        val newStart = Math.addExact(original.sourceInUs, deltaUs)
        val consumed = original.consumedSourceUs
        require(newStart >= 0L && consumed <= totalSource &&
            newStart <= totalSource - consumed) {
            "Slip would read beyond the available source footage"
        }
        val changed = original.copy(sourceInUs = newStart)
        return draft.copy(layers = draft.layers.copy(
            layers = draft.layers.layers.map { if (it.id == layerId) changed else it }
        ))
    }

    /**
     * Ripple an independent lane at the selected clip: move that clip and
     * every later clip ON THAT LANE by the same requested timeline offset.
     * Source trim, speed, effects and other tracks are unchanged.
     *
     * It never changes the length of the base project and rejects collisions,
     * including attempting to shift beyond the base video or before zero.
     */
    fun rippleLaneBy(
        draft: EditorDraft, layerId: String, deltaMs: Long
    ): EditorDraft {
        val selected = editable(draft, layerId)
        val delta = Math.multiplyExact(deltaMs, 1_000L)
        if (delta == 0L) return draft
        val end = projectEndUs(draft)
        val shifted = draft.layers.layers.map { item ->
            if (item.trackId != selected.trackId || item.startUs < selected.startUs) item
            else {
                val newStart = Math.addExact(item.startUs, delta)
                require(newStart >= 0 && item.durationUs <= end &&
                    newStart <= end - item.durationUs) {
                    "Ripple would move a clip outside the main video"
                }
                item.copy(startUs = newStart)
            }
        }
        return draft.copy(layers = draft.layers.copy(layers = shifted))
    }

    /**
     * Move an explicit group of layers together across tracks without
     * resampling their source footage. The timeline's transient group
     * selection commits the shared displacement as one undoable edit.
     */
    fun moveGroupBy(
        draft: EditorDraft, layerIds: Set<String>, deltaMs: Long
    ): EditorDraft = moveGroupByUs(
        draft, layerIds, Math.multiplyExact(deltaMs, 1_000L)
    )

    /** Frame-snapped counterpart used by direct timeline drag gestures. */
    fun moveGroupByFrames(
        draft: EditorDraft, layerIds: Set<String>, requestedDeltaUs: Long
    ): EditorDraft {
        val deltaUs = if (requestedDeltaUs < 0L) {
            -draft.layers.rate.nearestFrameUs(Math.negateExact(requestedDeltaUs))
        } else draft.layers.rate.nearestFrameUs(requestedDeltaUs)
        return moveGroupByUs(draft, layerIds, deltaUs)
    }

    private fun moveGroupByUs(
        draft: EditorDraft, layerIds: Set<String>, delta: Long
    ): EditorDraft {
        require(layerIds.isNotEmpty()) { "Select at least one clip" }
        require(layerIds.size == draft.layers.layers.count { it.id in layerIds }) {
            "The selected group contains a missing layer"
        }
        layerIds.forEach { editable(draft, it) }
        if (delta == 0L) return draft
        val end = projectEndUs(draft)
        val shifted = draft.layers.layers.map { item ->
            if (item.id !in layerIds) item
            else {
                val newStart = Math.addExact(item.startUs, delta)
                require(newStart >= 0L && item.durationUs <= end &&
                    newStart <= end - item.durationUs) {
                    "Group move exceeds the video timeline"
                }
                item.copy(startUs = newStart)
            }
        }
        return draft.copy(layers = draft.layers.copy(layers = shifted))
    }
}
