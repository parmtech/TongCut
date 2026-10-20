package au.parmtech.tongcut

/**
 * Clip-local volume automation. Times are on the edited project clock rather
 * than the source clock, so an envelope stays where the user placed it after
 * audio speed changes. Gain is linear because Media3's GainProcessor expects
 * a linear multiplier.
 */
data class AudioGainKeyframe(val timeUs: Long, val gain: Float) {
    init {
        require(timeUs >= 0L && timeUs % 1_000L == 0L)
        require(gain.isFinite() && gain in 0f..1f)
    }
}

object AudioEnvelope {
    const val MAX_POINTS = 64
    const val UNITY_TO_END_OF_STREAM = Long.MAX_VALUE

    fun validate(durationUs: Long, points: List<AudioGainKeyframe>) {
        require(points.size <= MAX_POINTS)
        require(points.zipWithNext().all { (left, right) -> left.timeUs < right.timeUs }) {
            "Audio envelope points must be in time order"
        }
        require(points.all { it.timeUs <= durationUs }) { "Audio envelope point is outside clip" }
    }

    fun gainAt(positionUs: Long, durationUs: Long, points: List<AudioGainKeyframe>): Float {
        validate(durationUs, points)
        if (points.isEmpty()) return 1f
        val position = positionUs.coerceIn(0L, durationUs)
        val rightIndex = points.indexOfFirst { it.timeUs >= position }
        if (rightIndex < 0) return points.last().gain
        val right = points[rightIndex]
        if (right.timeUs == position || rightIndex == 0) return right.gain
        val left = points[rightIndex - 1]
        val fraction = (position - left.timeUs).toFloat() / (right.timeUs - left.timeUs)
        return left.gain + (right.gain - left.gain) * fraction
    }

    /**
     * Returns the exclusive sample boundary of a unity run, null when the
     * current sample is not unity. A one-sample boundary is conservative at a
     * keyframe where a linear ramp begins; it avoids claiming a flat region
     * across the ramp. Long.MAX_VALUE denotes unity through stream end.
     */
    fun unityUntilSamplePosition(
        samplePosition: Long,
        sampleRate: Int,
        durationUs: Long,
        points: List<AudioGainKeyframe>,
        globalGain: Float = 1f
    ): Long? {
        require(samplePosition >= 0L && sampleRate > 0)
        require(globalGain.isFinite() && globalGain in 0f..1f)
        if (globalGain != 1f) return null
        val seconds = samplePosition / sampleRate
        val remainder = samplePosition % sampleRate
        val positionUs = seconds * 1_000_000L + remainder * 1_000_000L / sampleRate
        if (gainAt(positionUs, durationUs, points) != 1f) return null
        if (positionUs >= durationUs) return UNITY_TO_END_OF_STREAM
        return if (samplePosition == Long.MAX_VALUE) UNITY_TO_END_OF_STREAM
            else samplePosition + 1L
    }

    fun upsert(durationUs: Long, points: List<AudioGainKeyframe>, point: AudioGainKeyframe): List<AudioGainKeyframe> {
        require(point.timeUs <= durationUs)
        return (points.filterNot { it.timeUs == point.timeUs } + point).sortedBy { it.timeUs }.also {
            validate(durationUs, it)
        }
    }

    /** Preserve the audible curve on both sides of an edit split. */
    fun split(
        durationUs: Long, points: List<AudioGainKeyframe>, atUs: Long
    ): Pair<List<AudioGainKeyframe>, List<AudioGainKeyframe>> {
        require(atUs in 1_000L until durationUs)
        val boundary = gainAt(atUs, durationUs, points)
        val left = upsert(atUs, points.filter { it.timeUs < atUs }, AudioGainKeyframe(atUs, boundary))
        val rightDuration = durationUs - atUs
        val right = upsert(rightDuration, points.filter { it.timeUs > atUs }.map {
            it.copy(timeUs = it.timeUs - atUs)
        }, AudioGainKeyframe(0L, boundary))
        return left to right
    }

    /** Build a simple clip fade pair, shortening both fades evenly on short clips. */
    fun fades(durationUs: Long, fadeInUs: Long, fadeOutUs: Long): List<AudioGainKeyframe> {
        require(durationUs >= 1_000L)
        require(fadeInUs >= 0L && fadeOutUs >= 0L &&
            fadeInUs % 1_000L == 0L && fadeOutUs % 1_000L == 0L)
        var fadeIn = fadeInUs.coerceAtMost(durationUs)
        var fadeOut = fadeOutUs.coerceAtMost(durationUs)
        if (fadeIn > durationUs - fadeOut) {
            val total = fadeIn.toDouble() + fadeOut.toDouble()
            fadeIn = (fadeIn.toDouble() * durationUs / total / 1_000.0).toLong() * 1_000L
            fadeOut = ((fadeOut.toDouble() * durationUs / total / 1_000.0).toLong() * 1_000L)
                .coerceAtMost(durationUs - fadeIn)
        }
        val points = sortedMapOf<Long, Float>()
        points[0L] = if (fadeIn > 0L) 0f else 1f
        if (fadeIn > 0L) points[fadeIn] = 1f
        if (fadeOut > 0L) points[durationUs - fadeOut] = 1f
        points[durationUs] = if (fadeOut > 0L) 0f else 1f
        return points.map { (time, gain) -> AudioGainKeyframe(time, gain) }
    }
}
