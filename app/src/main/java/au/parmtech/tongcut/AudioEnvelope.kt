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
}
