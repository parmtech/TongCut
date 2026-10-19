package au.parmtech.tongcut

import kotlin.math.max

/** Framework-free, bounded peak aggregator used for decoded PCM waveform previews. */
class WaveformPeaks(
    private val bins: Int,
    private val startUs: Long,
    private val durationUs: Long
) {
    init {
        require(bins in 4..1024)
        require(startUs >= 0 && durationUs > 0 && startUs <= Long.MAX_VALUE - durationUs)
    }
    private val peaks = FloatArray(bins)

    /**
     * Supply a magnitude for an audio frame at its absolute presentation time.
     * Out-of-window samples do not affect the resulting waveform.
     */
    fun addAt(timeUs: Long, amplitude: Float) {
        if (timeUs < startUs || timeUs >= startUs + durationUs) return
        require(amplitude.isFinite() && amplitude >= 0f)
        val position = timeUs - startUs
        val index = ((position.toDouble() / durationUs.toDouble()) * bins)
            .toInt().coerceIn(0, bins - 1)
        peaks[index] = max(peaks[index], amplitude.coerceAtMost(1f))
    }

    /** Compressed waveform; each bin is normalized to [0,1]. */
    fun snapshot(): List<Float> = peaks.toList()
}
