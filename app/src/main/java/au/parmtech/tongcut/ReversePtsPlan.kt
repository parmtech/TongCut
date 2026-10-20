package au.parmtech.tongcut

/** Exact reverse order over observed extractor presentation timestamps (including VFR). */
class ReversePtsPlan private constructor(
    private val descendingPtsUs: LongArray,
    private val inUs: Long,
    private val outUs: Long
) {
    val frameCount: Int get() = descendingPtsUs.size
    val durationUs: Long get() = outUs - inUs
    val sourceInUs: Long get() = inUs
    val sourceOutUs: Long get() = outUs

    fun sourceTimeUs(outputOffset: Int): Long {
        require(outputOffset in descendingPtsUs.indices)
        return descendingPtsUs[outputOffset]
    }

    /**
     * Output duration for one reversed frame. The source-last frame keeps its trailing trim
     * duration; the source-first frame keeps the leading trim duration. Interior durations are
     * the exact adjacent observed-PTS deltas.
     */
    fun frameDurationUs(outputOffset: Int): Long {
        require(outputOffset in descendingPtsUs.indices)
        val lowerBoundary = if (frameCount == 1 || outputOffset == frameCount - 1) {
            inUs
        } else {
            descendingPtsUs[outputOffset]
        }
        val upperBoundary = if (outputOffset == 0) outUs else descendingPtsUs[outputOffset - 1]
        return upperBoundary - lowerBoundary
    }

    /** Returns the reversed frame visible at this zero-based segment output time. */
    fun frameIndexAtOutputTimeUs(outputTimeUs: Long): Int? {
        require(outputTimeUs >= 0L)
        if (outputTimeUs >= durationUs) return null
        var low = 0
        var high = frameCount - 1
        while (low < high) {
            val middle = low + (high - low) / 2
            val frameEndUs = outUs - descendingPtsUs[middle]
            if (outputTimeUs < frameEndUs) high = middle else low = middle + 1
        }
        return low
    }

    /** A consecutive output-order chunk with exact source-time boundaries. */
    fun sliceOutputRange(fromIndex: Int, toIndexExclusive: Int): ReversePtsPlan {
        require(fromIndex in 0 until frameCount)
        require(toIndexExclusive in (fromIndex + 1)..frameCount)
        val chunkInUs = if (toIndexExclusive == frameCount) inUs
            else descendingPtsUs[toIndexExclusive - 1]
        val chunkOutUs = if (fromIndex == 0) outUs else descendingPtsUs[fromIndex - 1]
        require(chunkOutUs > chunkInUs)
        return ReversePtsPlan(
            descendingPtsUs.copyOfRange(fromIndex, toIndexExclusive),
            chunkInUs,
            chunkOutUs
        )
    }

    companion object {
        const val MAX_REVERSED_FRAMES = 1_000_000

        /**
         * Builds a bounded order from strictly increasing extractor PTS values.
         * The trim is half-open [inUs, outUs). Non-monotonic/duplicate source
         * timestamps are rejected rather than silently reordered or duplicated.
         */
        fun fromPresentationTimes(
            presentationTimesUs: LongArray,
            inUs: Long,
            outUs: Long
        ): ReversePtsPlan? {
            require(inUs >= 0L && outUs >= inUs) { "Invalid reverse trim" }
            if (inUs == outUs) return null
            require(presentationTimesUs.size <= MAX_INDEXED_FRAMES) {
                "Video frame index exceeds safety limit"
            }
            val selected = LongArray(minOf(presentationTimesUs.size, MAX_REVERSED_FRAMES))
            var selectedCount = 0
            var previous = -1L
            for (timeUs in presentationTimesUs) {
                require(timeUs >= 0L && timeUs > previous) {
                    "Video presentation timestamps must be strictly increasing"
                }
                previous = timeUs
                if (timeUs >= inUs && timeUs < outUs) {
                    require(selectedCount < MAX_REVERSED_FRAMES) {
                        "Reverse segment exceeds frame safety limit"
                    }
                    selected[selectedCount++] = timeUs
                }
            }
            if (selectedCount == 0) return null
            selected.reverse(0, selectedCount)
            return ReversePtsPlan(selected.copyOf(selectedCount), inUs, outUs)
        }

        private const val MAX_INDEXED_FRAMES = 5_000_000
    }
}
