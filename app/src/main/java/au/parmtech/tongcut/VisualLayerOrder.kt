package au.parmtech.tongcut

/**
 * Media3 video compositor paints input index 0 LAST (the front-most input).
 * Media3 Bitmap/Photo post-composition effects then paint ABOVE all video inputs
 * in the given list order (later photos in front). Compose children paint later
 * children in front. Use this policy for the preview to match both stages.
 */
internal object VisualLayerOrder {
    fun forPreview(timeline: LayerTimeline, atUs: Long): List<TimelineLayer> {
        val byId = timeline.tracks.associateBy { it.id }
        return timeline.activeAt(atUs)
            .filter { it.kind == LayerKind.VIDEO || it.kind == LayerKind.IMAGE }
            .sortedWith(
                compareBy<TimelineLayer> {
                    if (it.kind == LayerKind.IMAGE) 1 else 0
                }.thenBy {
                    val order = byId.getValue(it.trackId).order
                    if (it.kind == LayerKind.VIDEO) -order else order
                }.thenBy { it.id }
            )
    }
}
