package au.parmtech.tongcut

/**
 * Pure front-to-back mapping for Media3's reverse-order compositor.
 *
 * Media3 composites streams from the highest registered input down to index 0.
 * Therefore the opaque base footage must be registered AFTER all translucent
 * overlay video lanes. This plan is shared by tests and native export setup.
 */
data class CompositorVideoPlan(
    val videoDurationUs: Long,
    val overlayLanes: List<VisualLanePlan>
) {
    init {
        require(videoDurationUs > 0)
        require(overlayLanes.size <= VisualMixPlan.MAX_VISUAL_LANES)
        require(overlayLanes.map { it.trackId }.toSet().size == overlayLanes.size)
        overlayLanes.forEach { lane ->
            val total = lane.items.sumOf { it.durationUs }
            require(total <= videoDurationUs)
            require(lane.items.any { it is VisualPart.Clip })
            require(lane.items.all { it is VisualPart.Gap ||
                (it is VisualPart.Clip && it.kind == LayerKind.VIDEO)
            })
        }
    }

    val backgroundVideoInputIndex: Int get() = overlayLanes.size

    fun layerAt(inputId: Int, presentationUs: Long): String? {
        if (inputId !in overlayLanes.indices ||
            presentationUs !in 0 until videoDurationUs) return null
        var startUs = 0L
        overlayLanes[inputId].items.forEach { part ->
            val endUs = startUs + part.durationUs
            if (presentationUs >= startUs && presentationUs < endUs) {
                return if (part is VisualPart.Clip) part.layerId else null
            }
            startUs = endUs
        }
        return null
    }

    fun alphaFor(inputId: Int, presentationUs: Long): Float =
        when {
            inputId == backgroundVideoInputIndex -> 1f
            inputId !in overlayLanes.indices -> 0f
            layerAt(inputId, presentationUs) == null -> 0f
            else -> 1f
        }

    companion object {
        fun from(timeline: LayerTimeline, videoDurationUs: Long): CompositorVideoPlan {
            val visual = VisualMixPlan.from(timeline, videoDurationUs)
            // Highest track.order must be registered first because Media3
            // draws input 0 last (on top). Preview uses the same order.
            val overlays = visual.filter { lane ->
                lane.items.any { part ->
                    part is VisualPart.Clip && part.kind == LayerKind.VIDEO
                }
            }.reversed()
            overlays.forEach { lane ->
                lane.items.filterIsInstance<VisualPart.Clip>().forEach { clip ->
                    require(clip.kind == LayerKind.VIDEO)
                    require(clip.sourceInUs % 1_000L == 0L &&
                        clip.durationUs % 1_000L == 0L) {
                        "Video compositor needs millisecond source boundaries"
                    }
                }
            }
            return CompositorVideoPlan(videoDurationUs, overlays)
        }
    }
}
