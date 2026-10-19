package au.parmtech.tongcut

/** Audio peak scan windows distributed across the entire *trimmed* source. */
data class WaveformScanWindow(val startUs: Long, val endUs: Long) {
    init { require(startUs >= 0L && endUs > startUs) }
    val durationUs: Long get() = endUs - startUs
}

/**
 * Decode every frame for <=90s clips. Longer media gets 80 equally distributed
 * short windows (<=750ms each, <=60s total decoder work) instead of showing
 * waveform only at the start. This is explicitly a *sampled estimate*, not
 * exact peak coverage for long recordings.
 */
object WaveformScanPlan {
    const val TOTAL_FULL_SCAN_US = 90_000_000L
    const val MAX_WINDOW_US = 750_000L
    const val SAMPLED_WINDOWS = 80

    fun windows(startUs: Long, durationUs: Long): List<WaveformScanWindow> {
        require(startUs >= 0L && durationUs >= 1_000L &&
            startUs <= Long.MAX_VALUE - durationUs)
        val end = startUs + durationUs
        if (durationUs <= TOTAL_FULL_SCAN_US) {
            return listOf(WaveformScanWindow(startUs, end))
        }
        return (0 until SAMPLED_WINDOWS).map { index ->
            val low = startUs + (durationUs.toDouble() * index / SAMPLED_WINDOWS).toLong()
            val high = if (index == SAMPLED_WINDOWS - 1) end else {
                startUs + (durationUs.toDouble() * (index + 1) / SAMPLED_WINDOWS).toLong()
            }
            val sampleUs = minOf(MAX_WINDOW_US, high - low)
            val left = low + (high - low - sampleUs) / 2L
            WaveformScanWindow(left, left + sampleUs)
        }
    }
}
