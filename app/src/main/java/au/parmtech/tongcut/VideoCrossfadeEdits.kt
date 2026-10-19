package au.parmtech.tongcut

import java.util.UUID

/**
 * A true source-over VIDEO PiP dissolve between two adjacent overlay clips.
 *
 * Media3 composites its VIDEO input lanes back-to-front (lower input index is
 * in front). Therefore only the *front* clip's alpha fades. The background clip
 * stays opaque through the overlap: (1-t)*outgoing + t*incoming, without the
 * dark dip produced by fading both streams independently.
 *
 * This intentionally does NOT modify the primary video sequence or claim
 * general track-to-track audio transitions.
 */
object VideoCrossfadeEdits {
    private fun projectEndUs(draft: EditorDraft): Long =
        Math.multiplyExact(SequentialTimeline(draft.clips).totalMs, 1_000L)

    fun crossfadeWithNext(
        draft: EditorDraft,
        outgoingId: String,
        durationMs: Long
    ): EditorDraft {
        val from = draft.layers.layers.firstOrNull {
            it.id == outgoingId && it.kind == LayerKind.VIDEO
        } ?: throw IllegalArgumentException("Select an outgoing VIDEO overlay")
        val next = draft.layers.layers.filter {
            it.kind == LayerKind.VIDEO && it.id != from.id &&
                it.startUs == from.endUs
        }
        require(next.size == 1) {
            "A crossfade needs exactly one VIDEO overlay starting at this clip's cut"
        }
        return crossfade(draft, from.id, next.single().id, durationMs)
    }

    fun crossfade(
        draft: EditorDraft,
        outgoingId: String,
        incomingId: String,
        durationMs: Long
    ): EditorDraft {
        require(outgoingId != incomingId)
        require(durationMs in 100L..3_000L) {
            "Crossfade duration must be 0.1–3 seconds"
        }
        val overlapUs = Math.multiplyExact(durationMs, 1_000L)
        val from = draft.layers.layers.firstOrNull {
            it.id == outgoingId && it.kind == LayerKind.VIDEO
        } ?: throw IllegalArgumentException("Outgoing video layer not found")
        val to = draft.layers.layers.firstOrNull {
            it.id == incomingId && it.kind == LayerKind.VIDEO
        } ?: throw IllegalArgumentException("Incoming video layer not found")
        val fromTrack = draft.layers.tracks.first { it.id == from.trackId }
        val toTrack = draft.layers.tracks.first { it.id == to.trackId }
        require(!fromTrack.locked && !toTrack.locked &&
            !fromTrack.hidden && !toTrack.hidden) {
            "Both videos must be visible and unlocked"
        }
        require(from.endUs == to.startUs) {
            "Crossfade clips must touch at a cut with no timeline gap"
        }
        require(overlapUs <= from.durationUs - 100_000L &&
            overlapUs <= to.durationUs - 100_000L) {
            "Both clips need at least 0.1 second outside the crossfade"
        }
        require(from.fade == LayerFade() && to.fade == LayerFade()) {
            "Reset existing clip fades before creating a crossfade"
        }
        val startTo = Math.subtractExact(to.startUs, overlapUs)
        require(startTo >= from.startUs + 100_000L) {
            "Overlap would start before the outgoing clip's safe boundary"
        }
        val total = projectEndUs(draft)
        require(to.endUs <= total) { "Incoming video exceeds the project duration" }

        // A single lane cannot contain overlapped media. Move the incoming
        // clip onto a new VIDEO lane and reuse the outgoing PiP screen slot.
        val createTrack = fromTrack.id == toTrack.id
        val destinationTrack = if (createTrack) {
            toTrack.copy(
                id = UUID.randomUUID().toString(),
                name = (toTrack.name + " dissolve").take(120),
                order = (draft.layers.tracks.maxOfOrNull { it.order } ?: -1) + 1,
                locked = false
            )
        } else toTrack
        val slot = VisualOverlaySlots.slotFor(draft.layers, from)
        // VideoLanePlan reverses ascending track order before Media3's
        // reverse-index draw. The highest track order is front-most.
        val outgoingInFront = fromTrack.order > destinationTrack.order
        val outgoing = from.copy(
            visualSlot = slot,
            fade = if (outgoingInFront) LayerFade(outUs = overlapUs) else from.fade
        )
        val incoming = to.copy(
            trackId = destinationTrack.id,
            startUs = startTo,
            visualSlot = slot,
            fade = if (!outgoingInFront) LayerFade(inUs = overlapUs) else to.fade
        )
        val graph = draft.layers.copy(
            tracks = if (createTrack)
                draft.layers.tracks + destinationTrack else draft.layers.tracks,
            layers = draft.layers.layers.map {
                when (it.id) {
                    outgoing.id -> outgoing
                    incoming.id -> incoming
                    else -> it
                }
            }
        )
        // No hidden/destructive fallback if the compositor needs >3 lanes.
        val visualCount = graph.tracks.count {
            it.kind in setOf(LayerKind.VIDEO, LayerKind.IMAGE) &&
                graph.layers.any { layer -> layer.trackId == it.id }
        }
        require(visualCount <= VisualMixPlan.MAX_VISUAL_LANES) {
            "Crossfade needs one available PiP decoder/layer"
        }
        VisualMixPlan.from(graph, total)
        return draft.copy(layers = graph)
    }
}
