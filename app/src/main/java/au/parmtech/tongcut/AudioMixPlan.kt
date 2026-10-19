package au.parmtech.tongcut

/**
 * Pure (framework-free) audio composition schedule. Each independent audio lane
 * becomes a Media3 audio-only sequence of silence gaps and clipped source media.
 * All timestamps are integer microseconds to preserve placement; Media3 MediaItem
 * clipping currently has millisecond precision, so export is not sample accurate.
 */
sealed interface AudioPart {
    val durationUs: Long
    data class Gap(override val durationUs: Long) : AudioPart {
        init { require(durationUs > 0) }
    }
    data class Source(
        val layerId: String,
        val uri: String,
        val sourceInUs: Long,
        override val durationUs: Long
    ) : AudioPart {
        init {
            require(uri.isNotBlank())
            require(sourceInUs >= 0 && durationUs >= 1_000)
        }
    }
}

data class AudioLanePlan(
    val trackId: String,
    val parts: List<AudioPart>
) {
    val totalUs: Long get() = parts.sumOf { it.durationUs }
}

object AudioMixPlan {
    /** Do not spawn too many decoders at once. Users can still keep additional muted tracks. */
    const val MAX_MIXED_LANES = 8

    fun from(timeline: LayerTimeline, videoDurationUs: Long): List<AudioLanePlan> {
        require(videoDurationUs > 0) { "Video duration is required" }
        require(timeline.layers.none {
            it.kind !in setOf(LayerKind.TEXT, LayerKind.AUDIO)
        }) { "Video/image/sticker overlays require the visual compositor" }
        val activeTracks = timeline.tracks
            .filter { it.kind == LayerKind.AUDIO && !it.muted }
            .sortedBy { it.order }
            .filter { track -> timeline.layers.any { it.trackId == track.id } }
        require(activeTracks.size <= MAX_MIXED_LANES) {
            "More than $MAX_MIXED_LANES simultaneous audio tracks are not supported"
        }

        return activeTracks.map { track ->
            val layers = timeline.layers.filter { it.trackId == track.id }
                .sortedBy { it.startUs }
            val parts = ArrayList<AudioPart>(layers.size * 2 + 1)
            var cursor = 0L
            for (layer in layers) {
                require(layer.kind == LayerKind.AUDIO)
                require(layer.startUs >= cursor) { "Overlapping clips on audio lane" }
                require(layer.endUs <= videoDurationUs) {
                    "Audio layer extends past video duration"
                }
                if (layer.startUs > cursor) parts += AudioPart.Gap(layer.startUs - cursor)
                parts += AudioPart.Source(
                    layerId = layer.id,
                    uri = requireNotNull(layer.sourceUri),
                    sourceInUs = layer.sourceInUs,
                    durationUs = layer.durationUs
                )
                cursor = layer.endUs
            }
            if (cursor < videoDurationUs) parts += AudioPart.Gap(videoDurationUs - cursor)
            AudioLanePlan(track.id, parts).also { plan ->
                check(plan.totalUs == videoDurationUs) { "Audio layer drift in schedule" }
            }
        }
    }
}
