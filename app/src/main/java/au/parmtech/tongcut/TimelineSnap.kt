package au.parmtech.tongcut

import kotlin.math.abs

/** Editor magnetic snapping, independent of Compose rendering and device pixels. */
object TimelineSnap {
    private const val DEFAULT_TOLERANCE_US = 120_000L

    /**
     * Snap the start OR end of a moving layer to a nearby clip boundary.
     * Frames remain the base timing unit and all results are clamped to the project duration.
     * Do not include the moved layer among targets.
     */
    fun move(
        draft: EditorDraft,
        id: String,
        requestedUs: Long,
        toleranceUs: Long = DEFAULT_TOLERANCE_US
    ): Long {
        require(toleranceUs >= 0)
        val layer = draft.layers.layers.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Unknown layer")
        val main = SequentialTimeline(draft.clips)
        require(main.totalMs <= Long.MAX_VALUE / 1_000L)
        val total = main.totalMs * 1_000L
        require(layer.durationUs <= total)
        val maxStart = total - layer.durationUs
        val requested = requestedUs.coerceIn(0, maxStart)
        val otherMarkers = draft.layers.layers.asSequence().filter { it.id != id }
            .flatMap { sequenceOf(it.startUs, it.endUs) }
        val mainMarkers = (draft.clips.indices).asSequence().map { main.startOf(it) * 1_000L }
        val markers = sequenceOf(0L, total) + mainMarkers + otherMarkers
        var snapped = requested
        var best = toleranceUs + 1L
        for (marker in markers) {
            // Align either the start or the end of the dragged layer.
            val candidates = longArrayOf(marker, marker - layer.durationUs)
            for (candidate in candidates) {
                if (candidate !in 0L..maxStart) continue
                val distance = abs(requested - candidate)
                if (distance < best) {
                    best = distance
                    snapped = candidate
                }
            }
        }
        val result = if (best <= toleranceUs) snapped else requested
        return draft.layers.rate.nearestFrameUs(result).coerceIn(0L, maxStart)
    }
}
