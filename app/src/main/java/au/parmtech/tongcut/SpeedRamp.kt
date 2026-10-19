package au.parmtech.tongcut

import kotlin.math.roundToLong

/** Piecewise-constant speed changes keyed to the TRIMMED SOURCE clock, not output time. */
data class SpeedKeyframe(val sourceOffsetUs: Long, val speed: Float) {
    init {
        require(sourceOffsetUs >= 0 && sourceOffsetUs % 1_000L == 0L)
        require(speed.isFinite() && speed in 0.25f..4f)
    }
}

/**
 * Shared source/project clock mapping for keyframed speed ramps.
 * Media3 SpeedProvider uses source timestamps, matching the keyframe clock.
 * All authoring boundaries are millisecond-aligned, as required by MediaItem clipping.
 */
object SpeedRamp {
    const val MAX_POINTS = 64

    fun validate(sourceSpanUs: Long, points: List<SpeedKeyframe>) {
        require(sourceSpanUs >= 1_000L && sourceSpanUs % 1_000L == 0L)
        require(points.isNotEmpty() && points.size <= MAX_POINTS)
        require(points[0].sourceOffsetUs == 0L) { "Speed curve must start at source zero" }
        require(points.last().sourceOffsetUs < sourceSpanUs)
        require(points.zipWithNext().all { (left, right) ->
            left.sourceOffsetUs < right.sourceOffsetUs
        }) { "Speed keyframes must be strictly increasing" }
    }

    /** Timeline duration quantised to the nearest millisecond for stable Media3 sequences. */
    fun durationUs(sourceSpanUs: Long, points: List<SpeedKeyframe>): Long {
        validate(sourceSpanUs, points)
        var outputUs = 0.0
        points.forEachIndexed { index, key ->
            val next = points.getOrNull(index + 1)?.sourceOffsetUs ?: sourceSpanUs
            outputUs += (next - key.sourceOffsetUs).toDouble() / key.speed.toDouble()
        }
        require(outputUs.isFinite() && outputUs / 1_000.0 <= Long.MAX_VALUE / 1_000L)
        return (outputUs / 1_000.0).roundToLong().coerceAtLeast(1L) * 1_000L
    }

    /** Maps trimmed media SOURCE position -> output presentation time. */
    fun outputAtSource(sourceOffsetUs: Long, sourceSpanUs: Long, points: List<SpeedKeyframe>): Long {
        validate(sourceSpanUs, points)
        val at = sourceOffsetUs.coerceIn(0, sourceSpanUs)
        var output = 0.0
        for (index in points.indices) {
            val key = points[index]
            if (at <= key.sourceOffsetUs) break
            val right = points.getOrNull(index + 1)?.sourceOffsetUs ?: sourceSpanUs
            val delta = minOf(at, right) - key.sourceOffsetUs
            if (delta > 0) output += delta.toDouble() / key.speed.toDouble()
            if (at < right) break
        }
        return output.roundToLong().coerceIn(0L, durationUs(sourceSpanUs, points))
    }

    /** Maps output position -> trimmed SOURCE position, for video preview and splitting. */
    fun sourceAtOutput(outputUs: Long, sourceSpanUs: Long, points: List<SpeedKeyframe>): Long {
        validate(sourceSpanUs, points)
        val at = outputUs.coerceIn(0L, durationUs(sourceSpanUs, points)).toDouble()
        var remaining = at
        for (index in points.indices) {
            val key = points[index]
            val right = points.getOrNull(index + 1)?.sourceOffsetUs ?: sourceSpanUs
            val sourceLength = right - key.sourceOffsetUs
            val segmentOutput = sourceLength.toDouble() / key.speed.toDouble()
            if (remaining < segmentOutput) {
                return (key.sourceOffsetUs.toDouble() + remaining * key.speed.toDouble())
                    .roundToLong().coerceIn(key.sourceOffsetUs, right)
            }
            remaining -= segmentOutput
        }
        return sourceSpanUs
    }

    fun speedAt(sourceOffsetUs: Long, points: List<SpeedKeyframe>): Float {
        require(points.isNotEmpty())
        return points.lastOrNull { it.sourceOffsetUs <= sourceOffsetUs }?.speed ?: points.first().speed
    }

    /** Next change, expressed relative to the clipped media's SOURCE start. */
    fun nextChangeAfter(sourceOffsetUs: Long, points: List<SpeedKeyframe>): Long? =
        points.firstOrNull { it.sourceOffsetUs > sourceOffsetUs }?.sourceOffsetUs

    fun upsert(
        sourceSpanUs: Long,
        points: List<SpeedKeyframe>,
        newKey: SpeedKeyframe
    ): List<SpeedKeyframe> {
        validate(sourceSpanUs, points)
        require(newKey.sourceOffsetUs < sourceSpanUs)
        val result = (points.filterNot { it.sourceOffsetUs == newKey.sourceOffsetUs } + newKey)
            .sortedBy { it.sourceOffsetUs }
        validate(sourceSpanUs, result)
        return result
    }

    /** Split at SOURCE offset, preserving instantaneous speed on the right edge. */
    fun splitAtSource(
        sourceSpanUs: Long, points: List<SpeedKeyframe>, splitSourceUs: Long
    ): Pair<List<SpeedKeyframe>, List<SpeedKeyframe>> {
        validate(sourceSpanUs, points)
        require(splitSourceUs in 1 until sourceSpanUs && splitSourceUs % 1_000L == 0L)
        val left = points.filter { it.sourceOffsetUs < splitSourceUs }
        val right = listOf(SpeedKeyframe(0L, speedAt(splitSourceUs, points))) +
            points.filter { it.sourceOffsetUs > splitSourceUs }.map { key ->
                key.copy(sourceOffsetUs = key.sourceOffsetUs - splitSourceUs)
            }
        validate(splitSourceUs, left)
        validate(sourceSpanUs - splitSourceUs, right)
        return left to right
    }
}
