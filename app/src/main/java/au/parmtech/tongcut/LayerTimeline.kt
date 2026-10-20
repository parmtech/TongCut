package au.parmtech.tongcut

import java.util.UUID
import java.math.BigInteger
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Non-destructive secondary track graph. The legacy main video sequence remains in
 * EditorDraft.clips until the shared preview/export compositor is implemented.
 * Clock units are microseconds. Models do not depend on Android framework classes.
 */
enum class LayerKind { VIDEO, AUDIO, IMAGE, TEXT, STICKER }

data class TimelineRate(val numerator: Int = 30, val denominator: Int = 1) {
    init {
        require(numerator in 1..240_000 && denominator in 1..1001)
        require(numerator.toDouble() / denominator in 1.0..240.0)
    }

    private fun bi(value: Long) = BigInteger.valueOf(value)

    // BigInteger.longValueExact is only exposed at Android API 31+, but TongCut supports 29.
    // Nonnegative frame counts fit in Long precisely if their bit length is <= 63.
    private fun BigInteger.checkedLong(): Long {
        require(signum() >= 0 && bitLength() <= 63) { "Frame timestamp exceeds supported range" }
        return toLong()
    }

    /** Floor of exact time-to-frame conversion, including 30000/1001 FPS. */
    fun frameAt(us: Long): Long {
        require(us >= 0)
        val numeratorUs = bi(us).multiply(bi(numerator.toLong()))
        val denominatorUs = bi(1_000_000L).multiply(bi(denominator.toLong()))
        return numeratorUs.divide(denominatorUs).checkedLong()
    }

    /** Earliest integer microsecond belonging to the requested frame. */
    fun atFrame(frame: Long): Long {
        require(frame >= 0)
        val numeratorUs = bi(frame)
            .multiply(bi(denominator.toLong()))
            .multiply(bi(1_000_000L))
        val rateN = bi(numerator.toLong())
        return numeratorUs.add(rateN.subtract(BigInteger.ONE)).divide(rateN).checkedLong()
    }

    fun nearestFrameUs(us: Long): Long {
        require(us >= 0)
        val lower = frameAt(us)
        val before = atFrame(lower)
        val after = atFrame(Math.addExact(lower, 1))
        return if (abs(us - before) <= abs(after - us)) before else after
    }
}

/** An independent ordered lane; locking prevents accidental edits. */
data class TimelineTrack(
    val id: String,
    val name: String,
    val kind: LayerKind,
    val order: Int,
    val locked: Boolean = false,
    val hidden: Boolean = false,
    val muted: Boolean = false,
    val gain: Float = 1f,
    /** When enabled, clips on this AUDIO lane trigger ducking on other audio lanes. */
    val duckingSource: Boolean = false,
    /** Target gain for other lanes while this dialogue/priority track is active. */
    val duckingGain: Float = 0.25f,
    /** Optional three-band PCM equalizer for preview and rendered audio. */
    val equalizer: AudioEqualizer = AudioEqualizer(),
    /** Stereo balance: -1 favors left, +1 favors right; mono sources remain unchanged. */
    val stereoBalance: Float = 0f,
    /** Average a stereo source to mono; other source channel layouts are preserved. */
    val monoFoldDown: Boolean = false,
    /** Apply deterministic 80 Hz high-pass rumble/DC cleanup to this audio lane. */
    val rumbleReduction: Boolean = false
) {
    init {
        require(id.isNotBlank() && id.length <= 100)
        require(name.isNotBlank() && name.length <= 120)
        require(order >= 0)
        require(gain.isFinite() && gain in 0f..1f) { "Audio gain must be 0–100%" }
        require(duckingGain.isFinite() && duckingGain in 0.05f..1f) {
            "Dialogue duck level must be 5–100%"
        }
        require(stereoBalance.isFinite() && stereoBalance in -1f..1f) {
            "Stereo balance must be -100–100%"
        }
        require(!duckingSource || kind == LayerKind.AUDIO) {
            "Only audio tracks can be dialogue ducking sources"
        }
        require(!rumbleReduction || kind == LayerKind.AUDIO) {
            "Rumble reduction applies only to audio tracks"
        }
    }
}

/**
 * Clip is anchored to project time; its source window is retained independently.
 * TEXT has literal text. VIDEO/AUDIO/IMAGE/STICKER have media URI.
 * sourceDurationUs may be omitted until media metadata is resolved.
 */
data class TimelineLayer(
    val id: String,
    val trackId: String,
    val kind: LayerKind,
    val startUs: Long,
    val durationUs: Long,
    val sourceUri: String? = null,
    val sourceInUs: Long = 0,
    val sourceDurationUs: Long? = null,
    val text: String? = null,
    val motion: List<MotionKeyframe> = emptyList(),
    val playbackSpeed: Float = 1f,
    val sourceSpanUs: Long? = null,
    val speedRamp: List<SpeedKeyframe> = emptyList(),
    val audioEnvelope: List<AudioGainKeyframe> = emptyList(),
    val photoEffects: PhotoEffects = PhotoEffects(),
    val fade: LayerFade = LayerFade(),
    // An explicit PiP screen slot is required for a freeze still to cover its source video.
    val visualSlot: Int? = null,
    val captionStyle: CaptionStyle = CaptionStyle(),
    /** Whether AUDIO time stretching keeps the source pitch instead of raising/lowering it. */
    val preservePitch: Boolean = true
) {
    init {
        require(id.isNotBlank() && id.length <= 100)
        require(trackId.isNotBlank())
        require(startUs >= 0)
        require(durationUs >= 1_000L)
        require(startUs <= Long.MAX_VALUE - durationUs)
        require(sourceInUs >= 0)
        require(visualSlot == null ||
            (kind in setOf(LayerKind.VIDEO,LayerKind.IMAGE) &&
                visualSlot in 0 until VisualOverlaySlots.MAX_SLOTS)) {
            "Invalid video/photo screen slot"
        }
        MotionCurve.validate(motion, durationUs)
        if (kind == LayerKind.AUDIO) require(fade == LayerFade()) {
            "Audio fading requires a separate sound envelope"
        } else fade.validate(durationUs)
        if (kind == LayerKind.AUDIO) AudioEnvelope.validate(durationUs, audioEnvelope)
        else require(audioEnvelope.isEmpty()) { "Audio envelope applies only to audio clips" }
        if (kind != LayerKind.IMAGE && kind != LayerKind.VIDEO) {
            require(photoEffects == PhotoEffects()) {
                "Visual masks and chroma apply only to video/photo overlays"
            }
        }
        require(playbackSpeed.isFinite() && playbackSpeed in 0.25f..4f)
        require(preservePitch || kind == LayerKind.AUDIO) {
            "Pitch policy applies only to audio clips"
        }
        // Both VIDEO and AUDIO may consume source at a non-unit speed.
        // Other layers are static and must never carry a source-time curve.
        if (kind != LayerKind.VIDEO && kind != LayerKind.AUDIO) {
            require(playbackSpeed == 1f && sourceSpanUs == null && speedRamp.isEmpty())
        }
        if (kind == LayerKind.AUDIO && sourceSpanUs != null && speedRamp.isEmpty()) {
            require(sourceSpanUs > 0L && sourceSpanUs % 1_000L == 0L) {
                "Pinned audio source span must align to complete milliseconds"
            }
            val nominal = (durationUs.toDouble() * playbackSpeed)
            require(kotlin.math.abs(sourceSpanUs.toDouble() - nominal) <= 1_000.0) {
                "Pinned audio source length must match its playback speed"
            }
        }
        if (speedRamp.isNotEmpty()) {
            require(kind == LayerKind.VIDEO || kind == LayerKind.AUDIO)
            val span = requireNotNull(sourceSpanUs) {
                "Speed-ramped media needs a fixed source span"
            }
            SpeedRamp.validate(span, speedRamp)
            val expected = SpeedRamp.durationUs(span, speedRamp)
            // Independently quantized halves of a split may differ by one
            // millisecond. Preserve the original project clock exactly.
            require((durationUs - expected) in -1_000L..1_000L) {
                "Ramped output duration disagrees with its source speed curve"
            }
        }

        if (kind == LayerKind.TEXT) {
            require(!text.isNullOrBlank() && text.length <= 10_000)
            require(sourceUri == null)
            require(sourceInUs == 0L && sourceDurationUs == null)
        } else {
            require(!sourceUri.isNullOrBlank())
            require(text == null && captionStyle == CaptionStyle())
            if (kind == LayerKind.VIDEO || kind == LayerKind.AUDIO) {
                val consumedUs = consumedSourceUs
                require(sourceInUs <= Long.MAX_VALUE - consumedUs)
                require(sourceDurationUs == null || sourceInUs + consumedUs <= sourceDurationUs)
            }
        }
    }

    val endUs: Long get() = startUs + durationUs

    /** Exact source span for a ramp, or inferred constant-speed legacy duration. */
    val consumedSourceUs: Long get() =
        sourceSpanUs ?: (durationUs.toDouble() * playbackSpeed).toLong()

    /** Relative source position, not project position. */
    fun sourceAt(positionUs: Long): Long {
        // Compare BEFORE subtracting: a stale negative seek on a layer near
        // Long.MAX_VALUE can wrap positive and incorrectly choose the final
        // source frame instead of its first frame.
        val output = if (positionUs <= startUs) 0L
            else (positionUs - startUs).coerceAtMost(durationUs)
        if (sourceSpanUs != null && output >= durationUs) {
            return sourceInUs + sourceSpanUs
        }
        return sourceInUs + if (speedRamp.isEmpty()) {
            (output.toDouble() * playbackSpeed).toLong()
        } else SpeedRamp.sourceAtOutput(output, requireNotNull(sourceSpanUs), speedRamp)
    }
}

/**
 * Immutable overlay track model. The initial composition exporter does NOT render it;
 * gate export when layers are present until preview/export compositing is implemented.
 */
data class LayerTimeline(
    val rate: TimelineRate = TimelineRate(),
    val tracks: List<TimelineTrack> = emptyList(),
    val layers: List<TimelineLayer> = emptyList()
) {
    init {
        require(tracks.size <= 64 && layers.size <= 5_000)
        require(tracks.map { it.id }.toSet().size == tracks.size)
        require(tracks.map { it.order }.toSet().size == tracks.size)
        require(layers.map { it.id }.toSet().size == layers.size)
        val byId = tracks.associateBy { it.id }
        layers.forEach { layer ->
            val lane = byId[layer.trackId] ?: throw IllegalArgumentException("Unknown layer track")
            require(lane.kind == layer.kind) { "Clip kind does not match its lane" }
        }
        // We permit overlapping items on different tracks. Reject an overlap within
        // one track until the transition model explicitly handles it.
        for (track in tracks) {
            val items = layers.filter { it.trackId == track.id }.sortedBy { it.startUs }
            for (i in 1 until items.size) {
                require(items[i - 1].endUs <= items[i].startUs) { "Overlapping clips on a lane" }
            }
        }
    }

    val endUs: Long get() = layers.maxOfOrNull { it.endUs } ?: 0L
    val hasUnrenderedLayers: Boolean get() = layers.isNotEmpty()

    fun addTrack(kind: LayerKind, name: String, id: String = UUID.randomUUID().toString()): LayerTimeline =
        copy(tracks = tracks + TimelineTrack(id, name, kind, (tracks.maxOfOrNull { it.order } ?: -1) + 1))

    fun addLayer(layer: TimelineLayer): LayerTimeline {
        val track = tracks.firstOrNull { it.id == layer.trackId }
            ?: throw IllegalArgumentException("Missing track")
        require(!track.locked) { "Track locked" }
        return copy(layers = layers + layer)
    }

    fun removeLayer(id: String): LayerTimeline {
        val item = layers.firstOrNull { it.id == id } ?: return this
        require(tracks.first { it.id == item.trackId }.locked.not()) { "Track locked" }
        return copy(layers = layers.filterNot { it.id == id })
    }

    fun moveLayer(id: String, trackId: String, newStartUs: Long): LayerTimeline {
        val layer = layers.firstOrNull { it.id == id } ?: throw IllegalArgumentException("Unknown clip")
        val fromTrack = tracks.first { it.id == layer.trackId }
        val dest = tracks.firstOrNull { it.id == trackId }
            ?: throw IllegalArgumentException("Unknown target track")
        require(!fromTrack.locked && !dest.locked) { "Track locked" }
        require(dest.kind == layer.kind) { "Cannot move clip to incompatible track" }
        return copy(layers = layers.map {
            if (it.id == id) layer.copy(trackId = trackId, startUs = newStartUs) else it
        })
    }

    fun splitLayer(id: String, atUs: Long, rightId: String = UUID.randomUUID().toString()): LayerTimeline {
        val layer = layers.firstOrNull { it.id == id } ?: throw IllegalArgumentException("Missing clip")
        require(layer.kind != LayerKind.STICKER) { "Sticker renderer not available" }
        val track = tracks.first { it.id == layer.trackId }
        require(!track.locked)
        require(atUs - layer.startUs >= 1_000 && layer.endUs - atUs >= 1_000) {
            "Split too close to clip edge"
        }
        val requestedLeft = atUs - layer.startUs
        val (leftEnvelope, rightEnvelope) = if (layer.kind == LayerKind.AUDIO) {
            AudioEnvelope.split(layer.durationUs, layer.audioEnvelope, requestedLeft)
        } else emptyList<AudioGainKeyframe>() to emptyList()
        if (layer.kind in setOf(LayerKind.VIDEO, LayerKind.AUDIO) &&
            layer.speedRamp.isNotEmpty()) {
            val fullSpan = requireNotNull(layer.sourceSpanUs)
            val splitSource = (
                SpeedRamp.sourceAtOutput(requestedLeft, fullSpan, layer.speedRamp) / 1_000L
            ).coerceIn(1L, fullSpan / 1_000L - 1L) * 1_000L
            val (leftRamp, rightRamp) = SpeedRamp.splitAtSource(
                fullSpan, layer.speedRamp, splitSource
            )
            val leftDuration = SpeedRamp.durationUs(splitSource, leftRamp)
            val rightSpan = fullSpan - splitSource
            // Round only the left boundary and give the rest to the right.
            // Rounding each half separately can create or remove 1ms at a cut.
            val rightDuration = layer.durationUs - leftDuration
            require(rightDuration >= 1_000L) { "Split too close to ramp edge" }
            val (leftMotion, rightMotion) =
                MotionCurve.split(layer.motion, leftDuration, layer.durationUs)
            val (leftFade, rightFade) = if (layer.kind == LayerKind.AUDIO) {
                LayerFade() to LayerFade()
            } else layer.fade.split(leftDuration, layer.durationUs)
            val left = layer.copy(
                durationUs = leftDuration, sourceSpanUs = splitSource,
                speedRamp = leftRamp, motion = leftMotion, fade = leftFade,
                audioEnvelope = leftEnvelope
            )
            val right = layer.copy(
                id = rightId, startUs = layer.startUs + leftDuration,
                durationUs = rightDuration, sourceInUs = layer.sourceInUs + splitSource,
                sourceSpanUs = rightSpan, speedRamp = rightRamp, motion = rightMotion,
                fade = rightFade, audioEnvelope = rightEnvelope
            )
            return copy(layers = layers.filterNot { it.id == id } + left + right)
        }
        val leftDuration = requestedLeft
        val (leftMotion, rightMotion) =
            MotionCurve.split(layer.motion, leftDuration, layer.durationUs)
        val (leftFade, rightFade) = if (layer.kind == LayerKind.AUDIO) {
            LayerFade() to LayerFade()
        } else layer.fade.split(leftDuration, layer.durationUs)
        // A constant-speed audio clip may pin an exact millisecond input span
        // while its output is rounded. Splits must divide that span, not
        // duplicate it into both child layers.
        val audioPinned = layer.kind == LayerKind.AUDIO && layer.sourceSpanUs != null
        val consumedLeftUs = if (audioPinned) {
            ((leftDuration.toDouble() * layer.playbackSpeed / 1_000.0)
                .roundToLong() * 1_000L)
        } else 0L
        if (audioPinned) require(
            consumedLeftUs >= 1_000L &&
                consumedLeftUs <= requireNotNull(layer.sourceSpanUs) - 1_000L
        ) { "Audio split must keep at least 1ms of source on each side" }
        val left = layer.copy(
            durationUs = leftDuration,
            sourceSpanUs = if (audioPinned) consumedLeftUs else layer.sourceSpanUs,
            motion = leftMotion, fade = leftFade, audioEnvelope = leftEnvelope
        )
        val right = layer.copy(
            id = rightId,
            startUs = atUs,
            durationUs = layer.durationUs - leftDuration,
            sourceInUs = when (layer.kind) {
                LayerKind.VIDEO -> layer.sourceInUs +
                    (leftDuration.toDouble() * layer.playbackSpeed).toLong()
                LayerKind.AUDIO -> layer.sourceInUs +
                    (if (audioPinned) consumedLeftUs else leftDuration)
                else -> layer.sourceInUs // IMAGE/TEXT reuse the same static source.
            },
            sourceSpanUs = if (audioPinned)
                requireNotNull(layer.sourceSpanUs) - consumedLeftUs
            else layer.sourceSpanUs,
            motion = rightMotion, fade = rightFade, audioEnvelope = rightEnvelope
        )
        return copy(layers = layers.filterNot { it.id == id } + left + right)
    }

    fun resizeLayer(id: String, newDurationUs: Long): LayerTimeline {
        val layer = layers.firstOrNull { it.id == id } ?: throw IllegalArgumentException("Missing clip")
        require(!tracks.first { it.id == layer.trackId }.locked)
        return copy(layers = layers.map {
            if (it.id == id) {
                require(newDurationUs >= 1_000L)
                require(it.speedRamp.isEmpty()) {
                    "Trim this ramped clip using the source speed editor first"
                }
                val trimmedMotion = it.motion.filter { key -> key.timeUs <= newDurationUs }
                val trimmedEnvelope = it.audioEnvelope.filter { key -> key.timeUs <= newDurationUs }
                val local = it.fade.resetToClip()
                val fadeIn = minOf(local.inUs, newDurationUs)
                val fadeOut = minOf(local.outUs, newDurationUs - fadeIn)
                it.copy(
                    durationUs = newDurationUs,
                    sourceSpanUs = if (it.kind == LayerKind.AUDIO &&
                        it.sourceSpanUs != null) {
                        (newDurationUs.toDouble() * it.playbackSpeed / 1_000.0)
                            .roundToLong() * 1_000L
                    } else it.sourceSpanUs,
                    motion = trimmedMotion, audioEnvelope = trimmedEnvelope,
                    fade = LayerFade(fadeIn,fadeOut)
                )
            } else it
        })
    }

    fun upsertMotion(id: String, frame: MotionKeyframe): LayerTimeline {
        val layer = layers.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Missing layer")
        require(!tracks.first { it.id == layer.trackId }.locked) { "Track locked" }
        require(layer.kind != LayerKind.AUDIO) { "Audio gain is edited separately" }
        val motion = MotionCurve.upsert(layer.motion, frame, layer.durationUs)
        return copy(layers = layers.map { if (it.id == id) it.copy(motion = motion) else it })
    }

    fun setTrackLocked(id: String, locked: Boolean): LayerTimeline =
        copy(tracks = tracks.map { if (it.id == id) it.copy(locked = locked) else it })

    fun activeAt(us: Long): List<TimelineLayer> {
        if (us < 0) return emptyList()
        val tracksById = tracks.associateBy { it.id }
        return layers.filter {
            val lane = tracksById[it.trackId] ?: return@filter false
            !lane.hidden && it.startUs <= us && us < it.endUs
        }.sortedWith(compareBy<TimelineLayer> { tracksById[it.trackId]!!.order }.thenBy { it.startUs })
    }
}
