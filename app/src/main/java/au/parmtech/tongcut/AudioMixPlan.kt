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
        override val durationUs: Long,
        val playbackSpeed: Float = 1f,
        val sourceSpanUs: Long? = null,
        val speedRamp: List<SpeedKeyframe> = emptyList(),
        val envelope: List<AudioGainKeyframe> = emptyList(),
        val preservePitch: Boolean = true
    ) : AudioPart {
        init {
            require(uri.isNotBlank())
            require(sourceInUs >= 0 && durationUs >= 1_000)
            require(playbackSpeed.isFinite() && playbackSpeed in 0.25f..4f)
            if (speedRamp.isNotEmpty()) {
                val span = requireNotNull(sourceSpanUs)
                SpeedRamp.validate(span,speedRamp)
                require(kotlin.math.abs(SpeedRamp.durationUs(span,speedRamp)-durationUs)
                    <= 1_000L)
            }
            AudioEnvelope.validate(durationUs, envelope)
        }

        val consumedSourceUs: Long get() = sourceSpanUs ?:
            (durationUs.toDouble() * playbackSpeed).toLong()
    }
}

data class AudioLanePlan(
    val trackId: String,
    val parts: List<AudioPart>,
    val gain: Float = 1f,
    val equalizer: AudioEqualizer = AudioEqualizer(),
    val stereoBalance: Float = 0f
) {
    init {
        require(gain.isFinite() && gain in 0f..1f)
        require(stereoBalance.isFinite() && stereoBalance in -1f..1f)
    }
    val totalUs: Long get() = parts.sumOf { it.durationUs }
}

object AudioMixPlan {
    /** Do not spawn too many decoders at once. Users can still keep additional muted tracks. */
    const val MAX_MIXED_LANES = 8

    fun from(timeline: LayerTimeline, videoDurationUs: Long): List<AudioLanePlan> {
        require(videoDurationUs > 0) { "Video duration is required" }
        require(timeline.layers.none { it.kind == LayerKind.STICKER }) {
            "Sticker compositions require a dedicated renderer"
        }
        val activeTracks = timeline.tracks
            .filter { it.kind == LayerKind.AUDIO && !it.muted }
            .sortedBy { it.order }
            .filter { track -> timeline.layers.any { it.trackId == track.id } }
        val duckingWindows = AudioDucking.sourceWindows(timeline)
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
                    durationUs = layer.durationUs,
                    playbackSpeed = layer.playbackSpeed,
                    sourceSpanUs = layer.sourceSpanUs,
                    speedRamp = layer.speedRamp,
                    envelope = if (track.duckingSource) layer.audioEnvelope
                        else AudioDucking.envelopeFor(layer, duckingWindows),
                    preservePitch = layer.preservePitch
                )
                cursor = layer.endUs
            }
            if (cursor < videoDurationUs) parts += AudioPart.Gap(videoDurationUs - cursor)
            AudioLanePlan(track.id, parts, track.gain, track.equalizer,
                track.stereoBalance).also { plan ->
                check(plan.totalUs == videoDurationUs) { "Audio layer drift in schedule" }
            }
        }
    }
}
