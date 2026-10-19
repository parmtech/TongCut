package au.parmtech.tongcut

import java.util.UUID
import java.math.BigInteger
import kotlin.math.abs

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
    val gain: Float = 1f
) {
    init {
        require(id.isNotBlank() && id.length <= 100)
        require(name.isNotBlank() && name.length <= 120)
        require(order >= 0)
        require(gain.isFinite() && gain in 0f..1f) { "Audio gain must be 0–100%" }
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
    val playbackSpeed: Float = 1f
) {
    init {
        require(id.isNotBlank() && id.length <= 100)
        require(trackId.isNotBlank())
        require(startUs >= 0)
        require(durationUs >= 1_000L)
        require(startUs <= Long.MAX_VALUE - durationUs)
        require(sourceInUs >= 0)
        MotionCurve.validate(motion, durationUs)
        require(playbackSpeed.isFinite() && playbackSpeed in 0.25f..4f)
        if (kind != LayerKind.VIDEO) require(playbackSpeed == 1f)

        if (kind == LayerKind.TEXT) {
            require(!text.isNullOrBlank() && text.length <= 10_000)
            require(sourceUri == null)
            require(sourceInUs == 0L && sourceDurationUs == null)
        } else {
            require(!sourceUri.isNullOrBlank())
            require(text == null)
            if (kind == LayerKind.VIDEO || kind == LayerKind.AUDIO) {
                val consumedUs = if (kind == LayerKind.VIDEO) {
                    (durationUs.toDouble() * playbackSpeed).toLong()
                } else durationUs
                require(sourceInUs <= Long.MAX_VALUE - consumedUs)
                require(sourceDurationUs == null || sourceInUs + consumedUs <= sourceDurationUs)
            }
        }
    }

    val endUs: Long get() = startUs + durationUs

    /** Relative source position, not project position. */
    fun sourceAt(positionUs: Long): Long =
        sourceInUs + (
            ((positionUs - startUs).coerceIn(0L, durationUs).toDouble() *
                playbackSpeed).toLong()
        )
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
        require(layer.kind in listOf(LayerKind.VIDEO, LayerKind.AUDIO)) { "Only timed media can be split" }
        val track = tracks.first { it.id == layer.trackId }
        require(!track.locked)
        require(atUs - layer.startUs >= 1_000 && layer.endUs - atUs >= 1_000) {
            "Split too close to clip edge"
        }
        val leftDuration = atUs - layer.startUs
        val (leftMotion, rightMotion) =
            MotionCurve.split(layer.motion, leftDuration, layer.durationUs)
        val left = layer.copy(durationUs = leftDuration, motion = leftMotion)
        val right = layer.copy(
            id = rightId,
            startUs = atUs,
            durationUs = layer.durationUs - leftDuration,
            sourceInUs = layer.sourceInUs +
                (leftDuration.toDouble() * layer.playbackSpeed).toLong(),
            motion = rightMotion
        )
        return copy(layers = layers.filterNot { it.id == id } + left + right)
    }

    fun resizeLayer(id: String, newDurationUs: Long): LayerTimeline {
        val layer = layers.firstOrNull { it.id == id } ?: throw IllegalArgumentException("Missing clip")
        require(!tracks.first { it.id == layer.trackId }.locked)
        return copy(layers = layers.map {
            if (it.id == id) {
                require(newDurationUs >= 1_000L)
                val trimmedMotion = it.motion.filter { key -> key.timeUs <= newDurationUs }
                it.copy(durationUs = newDurationUs, motion = trimmedMotion)
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
