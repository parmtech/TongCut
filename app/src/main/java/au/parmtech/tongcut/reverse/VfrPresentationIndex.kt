package au.parmtech.tongcut.reverse

/**
 * One encoded video sample's exact source presentation time and its zero-based
 * ordinal in the selected MediaExtractor track. The ordinal preserves a route
 * back to the same source sample; PTS remains the canonical timeline mapping.
 */
data class VfrPresentationSample(
    val extractorSampleOrdinal: Int,
    val presentationTimeUs: Long
)

/**
 * Immutable view of observed VFR samples in a half-open source trim [inUs, outUs).
 * Samples are exposed in presentation order and can be iterated in reverse without
 * synthesizing CFR timestamps or losing their source PTS/sample-ordinal mapping.
 */
class VfrPresentationIndex private constructor(
    private val presentationTimesUs: LongArray,
    private val extractorOrdinals: IntArray,
    private val rangeStart: Int,
    private val rangeEndExclusive: Int,
    val extractorOrderWasStrictlyIncreasing: Boolean
) {
    val frameCount: Int get() = rangeEndExclusive - rangeStart

    fun chronologicalAt(index: Int): VfrPresentationSample {
        require(index in 0 until frameCount)
        val sourceIndex = rangeStart + index
        return VfrPresentationSample(
            extractorSampleOrdinal = extractorOrdinals[sourceIndex],
            presentationTimeUs = presentationTimesUs[sourceIndex]
        )
    }

    fun reverseAt(outputFrameOrdinal: Int): VfrPresentationSample {
        require(outputFrameOrdinal in 0 until frameCount)
        return chronologicalAt(frameCount - outputFrameOrdinal - 1)
    }

    /** Original source PTS for an output frame ordinal; no CFR resampling is implied. */
    fun sourceTimeUs(outputFrameOrdinal: Int): Long =
        reverseAt(outputFrameOrdinal).presentationTimeUs

    fun reverseOrder(): Sequence<VfrPresentationSample> = sequence {
        for (outputIndex in 0 until frameCount) yield(reverseAt(outputIndex))
    }

    internal companion object {
        fun fromSortedStorage(
            presentationTimesUs: LongArray,
            extractorOrdinals: IntArray,
            sampleCount: Int,
            rangeStart: Int,
            rangeEndExclusive: Int,
            extractorOrderWasStrictlyIncreasing: Boolean
        ): VfrPresentationIndex {
            require(sampleCount in 1..presentationTimesUs.size)
            require(sampleCount == extractorOrdinals.size)
            require(rangeStart in 0..sampleCount)
            require(rangeEndExclusive in rangeStart..sampleCount)
            return VfrPresentationIndex(
                presentationTimesUs, extractorOrdinals,
                rangeStart, rangeEndExclusive, extractorOrderWasStrictlyIncreasing
            )
        }
    }
}

/**
 * Bounded primitive-array collector for MediaExtractor sample PTS values.
 *
 * Non-monotonic extractor order is permitted and sorted by PTS at build time;
 * duplicate PTS values are rejected because reverse source-time mapping would be
 * ambiguous. The paired arrays retain each sample's original extractor ordinal.
 */
class VfrPresentationIndexBuilder(
    private val maxSamples: Int = MAX_SAMPLES
) {
    private var times = LongArray(minOf(INITIAL_CAPACITY, maxSamples.coerceAtLeast(1)))
    private var ordinals = IntArray(times.size)
    private var lastObservedTimeUs = -1L
    private var orderStrictlyIncreasing = true
    private var built = false
    var size: Int = 0
        private set

    init {
        require(maxSamples in 1..MAX_SAMPLES)
    }

    fun add(presentationTimeUs: Long, extractorSampleOrdinal: Int = size) {
        check(!built) { "Timestamp index has already been built" }
        require(presentationTimeUs >= 0L) { "Video presentation timestamp must be non-negative" }
        require(extractorSampleOrdinal == size) {
            "Extractor sample ordinals must match selected-track read order"
        }
        require(size < maxSamples) { "Video frame index exceeds sample safety limit" }
        if (size > 0 && presentationTimeUs <= lastObservedTimeUs) {
            orderStrictlyIncreasing = false
        }
        if (size == times.size) {
            val nextSize = minOf(maxSamples, times.size * 2)
            times = times.copyOf(nextSize)
            ordinals = ordinals.copyOf(nextSize)
        }
        times[size] = presentationTimeUs
        ordinals[size] = extractorSampleOrdinal
        size++
        lastObservedTimeUs = presentationTimeUs
    }

    /** Returns null for an empty range or when no actual frame PTS falls in the trim. */
    fun build(inUs: Long, outUs: Long): VfrPresentationIndex? {
        check(!built) { "Timestamp index has already been built" }
        require(inUs >= 0L && outUs >= inUs) { "Invalid reverse trim" }
        built = true
        if (size == 0 || inUs == outUs) return null

        heapSortPairsByTime()
        for (index in 1 until size) {
            require(times[index] > times[index - 1]) {
                "Video presentation timestamps must be unique"
            }
        }

        val start = lowerBound(inUs)
        val end = lowerBound(outUs)
        if (start == end) return null
        return VfrPresentationIndex.fromSortedStorage(
            times, ordinals, size, start, end, orderStrictlyIncreasing
        )
    }

    private fun lowerBound(value: Long): Int {
        var low = 0
        var high = size
        while (low < high) {
            val middle = low + (high - low) / 2
            if (times[middle] < value) low = middle + 1 else high = middle
        }
        return low
    }

    /** In-place max-heapsort keeps the bounded timestamp/ordinal arrays paired. */
    private fun heapSortPairsByTime() {
        if (size < 2) return
        for (root in (size / 2 - 1) downTo 0) siftDown(root, size)
        for (endExclusive in (size - 1) downTo 1) {
            swap(0, endExclusive)
            siftDown(0, endExclusive)
        }
    }

    private fun siftDown(start: Int, endExclusive: Int) {
        var root = start
        while (true) {
            val left = root * 2 + 1
            if (left >= endExclusive) return
            var greater = left
            val right = left + 1
            if (right < endExclusive && times[right] > times[left]) greater = right
            if (times[root] >= times[greater]) return
            swap(root, greater)
            root = greater
        }
    }

    private fun swap(first: Int, second: Int) {
        val time = times[first]
        times[first] = times[second]
        times[second] = time
        val ordinal = ordinals[first]
        ordinals[first] = ordinals[second]
        ordinals[second] = ordinal
    }

    companion object {
        /** At most two 1M primitive arrays (about 12 MiB total) are retained. */
        const val MAX_SAMPLES = 1_000_000
        private const val INITIAL_CAPACITY = 1024
    }
}
