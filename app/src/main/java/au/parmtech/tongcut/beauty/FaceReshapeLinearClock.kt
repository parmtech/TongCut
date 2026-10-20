package au.parmtech.tongcut.beauty

import kotlin.math.floor

/**
 * Deterministic presentation-time -> source-time mapping for a SINGLE video
 * clip with constant speed. A renderer may use this in
 * FaceReshapeEffectFactory.videoEffects(edit, clock::at).
 *
 * It deliberately does NOT implement nonlinear speed ramps, clip joins or
 * Media3's composition-time semantics. Those must use the authoritative
 * existing TongCut layer.sourceAt() clock via an explicit adapter.
 */
internal data class FaceReshapeLinearClock(
    val sourceInUs: Long,
    val sourceOutUsExclusive: Long,
    val speed: Double = 1.0,
    val reverse: Boolean = false,
    /** Frame PTS at which this clip begins in the EFFECT's own time domain. */
    val presentationOriginUs: Long = 0L
) {
    init {
        require(sourceInUs >= 0 && sourceOutUsExclusive > sourceInUs)
        require(speed.isFinite() && speed in 0.25..4.0)
        require(presentationOriginUs >= 0)
    }

    /**
     * Return null outside the exact trimmed clip interval. The source's upper
     * bound is exclusive and reverse starts at the LAST valid microsecond.
     * This is a timebase helper, not a change to the media decoder.
     */
    fun at(presentationTimeUs: Long): Long? {
        if (presentationTimeUs < presentationOriginUs) return null
        val elapsedUs = presentationTimeUs - presentationOriginUs
        val spanUs = sourceOutUsExclusive - sourceInUs
        val sourceOffset = elapsedUs.toDouble() * speed
        if (!sourceOffset.isFinite() || sourceOffset >= spanUs.toDouble()) {
            return null
        }
        val delta = floor(sourceOffset).toLong()
        return if (reverse) {
            sourceOutUsExclusive - 1L - delta
        } else {
            sourceInUs + delta
        }
    }
}
