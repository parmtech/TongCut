package au.parmtech.tongcut

import java.math.BigInteger

private fun BigInteger.toNonNegativeLongExact(): Long {
    require(signum() >= 0 && bitLength() <= 63) { "Reverse frame index exceeds supported range" }
    return toLong()
}

/**
 * Bounded, lazy source-frame order for a CFR reverse segment. Frame extraction,
 * cache ownership, VFR PTS indexing, and rendering remain the caller's job.
 * Source trims are half-open [inUs, outUs); frame timestamps are rational FPS.
 */
data class ReverseFramePlan(
    val firstFrameIndex: Long,
    val lastFrameIndex: Long,
    val rate: TimelineRate
) {
    init {
        require(firstFrameIndex >= lastFrameIndex && lastFrameIndex >= 0L)
        require(firstFrameIndex - lastFrameIndex < MAX_FRAMES) {
            "Reverse segment exceeds frame safety limit"
        }
    }

    val frameCount: Long get() = firstFrameIndex - lastFrameIndex + 1L

    /** Source presentation timestamp, in microseconds, for output frame offset. */
    fun sourceTimeUs(outputOffset: Long): Long {
        require(outputOffset in 0 until frameCount)
        val frame = firstFrameIndex - outputOffset
        val numerator = BigInteger.valueOf(frame)
            .multiply(BigInteger.valueOf(rate.denominator.toLong()))
            .multiply(MICROS_PER_SECOND)
        val divisor = BigInteger.valueOf(rate.numerator.toLong())
        return numerator.add(divisor).subtract(BigInteger.ONE)
            .divide(divisor).toNonNegativeLongExact()
    }

    companion object {
        const val MAX_FRAMES = 1_000_000L
        private val MICROS_PER_SECOND = BigInteger.valueOf(1_000_000L)

        /** Returns null when the half-open trim contains no CFR presentation timestamp. */
        fun fromTrim(inUs: Long, outUs: Long, rate: TimelineRate): ReverseFramePlan? {
            require(inUs >= 0L && outUs >= inUs) { "Invalid reverse trim" }
            if (inUs == outUs) return null
            val fpsNumerator = BigInteger.valueOf(rate.numerator.toLong())
            val fpsDenominator = MICROS_PER_SECOND
                .multiply(BigInteger.valueOf(rate.denominator.toLong()))
            val first = BigInteger.valueOf(outUs - 1L)
                .multiply(fpsNumerator).divide(fpsDenominator)
            val last = if (inUs == 0L) BigInteger.ZERO else
                BigInteger.valueOf(inUs - 1L).multiply(fpsNumerator)
                    .divide(fpsDenominator).add(BigInteger.ONE)
            if (first < last) return null
            val firstLong = first.toNonNegativeLongExact()
            val lastLong = last.toNonNegativeLongExact()
            require(firstLong - lastLong < MAX_FRAMES) {
                "Reverse segment exceeds frame safety limit"
            }
            return ReverseFramePlan(firstLong, lastLong, rate)
        }
    }
}
