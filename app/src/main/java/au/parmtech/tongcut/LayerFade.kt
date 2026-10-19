package au.parmtech.tongcut

/**
 * Shared visual opacity envelope. phaseOffsetUs/phaseDurationUs allow splitting a
 * clip without resetting the in-progress fade at the new edit boundary.
 */
data class LayerFade(
    val inUs: Long = 0L,
    val outUs: Long = 0L,
    val phaseOffsetUs: Long = 0L,
    val phaseDurationUs: Long? = null
) {
    init {
        require(inUs >= 0L && outUs >= 0L && phaseOffsetUs >= 0L)
        require(phaseDurationUs == null || phaseDurationUs >= 1_000L)
    }

    fun validate(durationUs: Long) {
        require(durationUs >= 1_000L)
        val total = phaseDurationUs ?: durationUs
        require(phaseOffsetUs <= total && durationUs <= total - phaseOffsetUs)
        require(inUs <= total && outUs <= total - inUs) {
            "Fade lengths overlap inside the original layer envelope"
        }
    }

    /** Shared alpha for Android preview and Media3 export. */
    fun alphaAt(positionUs: Long, durationUs: Long): Float {
        validate(durationUs)
        val total = phaseDurationUs ?: durationUs
        val at = phaseOffsetUs + positionUs.coerceIn(0L, durationUs)
        val enter = if (inUs == 0L) 1f else
            (at.toDouble() / inUs).toFloat().coerceIn(0f,1f)
        val exit = if (outUs == 0L) 1f else
            ((total - at).toDouble() / outUs).toFloat().coerceIn(0f,1f)
        return minOf(enter,exit)
    }

    /** Keep phase of the original curve when cutting a visual clip in two. */
    fun split(leftDurationUs: Long, originalDurationUs: Long): Pair<LayerFade,LayerFade> {
        validate(originalDurationUs)
        require(leftDurationUs in 1 until originalDurationUs)
        val total = phaseDurationUs ?: originalDurationUs
        val left = copy(phaseDurationUs = total)
        val right = copy(
            phaseOffsetUs = phaseOffsetUs + leftDurationUs,
            phaseDurationUs = total
        )
        left.validate(leftDurationUs)
        right.validate(originalDurationUs-leftDurationUs)
        return left to right
    }

    /** Intentionally start fresh fade handles relative to the selected subclip. */
    fun resetToClip() = LayerFade(inUs,outUs)
}
