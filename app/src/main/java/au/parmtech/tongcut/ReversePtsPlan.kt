package au.parmtech.tongcut

/** Exact reverse order over observed extractor presentation timestamps (including VFR). */
class ReversePtsPlan private constructor(private val descendingPtsUs: LongArray) {
    val frameCount: Int get() = descendingPtsUs.size

    fun sourceTimeUs(outputOffset: Int): Long {
        require(outputOffset in descendingPtsUs.indices)
        return descendingPtsUs[outputOffset]
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
            return ReversePtsPlan(selected.copyOf(selectedCount))
        }

        private const val MAX_INDEXED_FRAMES = 5_000_000
    }
}

