package au.parmtech.tongcut

/** Visual overlay track schedule for independent video/image layers. */
data class VisualLanePlan(
    val trackId: String,
    val items: List<VisualPart>
)

sealed interface VisualPart {
    val durationUs: Long
    data class Gap(override val durationUs: Long) : VisualPart {
        init { require(durationUs > 0) }
    }
    data class Clip(
        val layerId: String,
        val kind: LayerKind,
        val uri: String,
        val sourceInUs: Long,
        override val durationUs: Long,
        val playbackSpeed: Float = 1f,
        val sourceSpanUs: Long? = null,
        val speedRamp: List<SpeedKeyframe> = emptyList()
    ) : VisualPart {
        init {
            require(kind == LayerKind.VIDEO || kind == LayerKind.IMAGE)
            require(uri.isNotBlank() && durationUs >= 1_000)
            require(sourceInUs >= 0)
            require(playbackSpeed.isFinite() && playbackSpeed in 0.25f..4f)
            if (speedRamp.isNotEmpty()) {
                val span = requireNotNull(sourceSpanUs)
                SpeedRamp.validate(span, speedRamp)
                require(durationUs == SpeedRamp.durationUs(span, speedRamp))
            }

        }
    }
}

object VisualMixPlan {
    const val MAX_VISUAL_LANES = 3
    fun from(timeline: LayerTimeline, videoDurationUs: Long): List<VisualLanePlan> {
        require(videoDurationUs > 0)
        val kinds = setOf(LayerKind.VIDEO, LayerKind.IMAGE)
        val lanes = timeline.tracks.filter { it.kind in kinds && !it.hidden }
            .filter { t -> timeline.layers.any { it.trackId == t.id } }
            .sortedBy { it.order }
        require(lanes.size <= MAX_VISUAL_LANES) { "Too many visible video/image layers" }
        return lanes.map { track ->
            var cursor = 0L
            val parts = mutableListOf<VisualPart>()
            timeline.layers.filter { it.trackId == track.id }.sortedBy { it.startUs }
                .forEach { layer ->
                    require(layer.startUs >= cursor) { "Layer collision" }
                    require(layer.endUs <= videoDurationUs) { "Overlay exceeds video" }
                    if (layer.startUs > cursor) parts += VisualPart.Gap(layer.startUs - cursor)
                    parts += VisualPart.Clip(layer.id, layer.kind,
                        requireNotNull(layer.sourceUri), layer.sourceInUs,
                        layer.durationUs, layer.playbackSpeed,
                        layer.sourceSpanUs, layer.speedRamp)
                    cursor = layer.endUs
                }
            VisualLanePlan(track.id, parts)
        }
    }
}
