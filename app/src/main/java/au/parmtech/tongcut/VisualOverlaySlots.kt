package au.parmtech.tongcut

/**
 * Single track-order / stable-location policy shared by Compose preview,
 * Media3 VIDEO compositor and post-composition PHOTO effects.
 *
 * The slot is independent of which clips happen to be playing at a moment:
 * ending another overlay must not cause this clip to jump across the canvas.
 * A captured freeze frame saves its parent video's slot as an override so
 * the still image remains exactly in the video PiP position.
 */
object VisualOverlaySlots {
    const val MAX_SLOTS = 3
    val anchors: List<Pair<Float, Float>> = listOf(
        0.55f to -0.55f,
        -0.55f to -0.55f,
        0.55f to 0.55f
    )

    fun slotFor(timeline: LayerTimeline, layer: TimelineLayer): Int {
        require(layer.kind == LayerKind.VIDEO || layer.kind == LayerKind.IMAGE)
        layer.visualSlot?.let { return it }
        val visualTracks = timeline.tracks
            .filter { track ->
                track.kind == LayerKind.VIDEO || track.kind == LayerKind.IMAGE
            }
            .filter { track -> timeline.layers.any { it.trackId == track.id } }
            .sortedBy { it.order }
        val index = visualTracks.indexOfFirst { it.id == layer.trackId }
        require(index >= 0 && index < MAX_SLOTS) {
            "Visual track does not have a supported screen slot"
        }
        return index
    }

    fun position(timeline: LayerTimeline, layer: TimelineLayer): Pair<Float,Float> =
        anchors[slotFor(timeline, layer)]

    /**
     * Compose anchors a 42%-wide, 16:9 FIT content box at the canvas corner.
     * Translate those actual box centres to Media3 NDC, including PORTRAIT
     * and SQUARE canvases (fixed ±0.55 moves photos vertically on portrait).
     */
    fun canvasAnchor(slot: Int, canvasAspect: Float): Pair<Float, Float> {
        require(slot in 0 until MAX_SLOTS)
        require(canvasAspect.isFinite() && canvasAspect in 0.1f..10f)
        val halfWidth = 1f - PhotoOverlaySizing.PIP_WIDTH_FRACTION
        val boxHeightFraction = (
            PhotoOverlaySizing.PIP_WIDTH_FRACTION * canvasAspect /
                PhotoOverlaySizing.PREVIEW_BOX_ASPECT
        ).coerceIn(0.001f, 1f)
        val halfHeight = 1f - boxHeightFraction
        return when (slot) {
            0 -> halfWidth to -halfHeight
            1 -> -halfWidth to -halfHeight
            else -> halfWidth to halfHeight
        }
    }

    fun canvasPosition(
        timeline: LayerTimeline, layer: TimelineLayer, canvasAspect: Float
    ): Pair<Float, Float> = canvasAnchor(slotFor(timeline, layer), canvasAspect)

}
