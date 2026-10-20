package au.parmtech.tongcut

import android.content.Context
import android.media.MediaExtractor
import android.net.Uri
import java.util.Arrays
import java.util.concurrent.CancellationException

/**
 * Reads a video's observed presentation timestamps without decoding frames.
 * Call from a worker thread; the extractor is always released, including on cancellation.
 */
object ReversePtsIndexer {
    fun index(
        context: Context,
        source: Uri,
        inUs: Long,
        outUs: Long,
        isCancelled: () -> Boolean = { Thread.currentThread().isInterrupted }
    ): ReversePtsPlan? {
        require(inUs >= 0L && outUs >= inUs) { "Invalid reverse trim" }
        if (inUs == outUs) return null
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, source, emptyMap())
            val track = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(android.media.MediaFormat.KEY_MIME)
                    ?.startsWith("video/") == true
            } ?: throw IllegalArgumentException("Source has no video track")
            extractor.selectTrack(track)

            val timestamps = PresentationTimestampCollector()
            while (extractor.sampleTrackIndex >= 0) {
                if (isCancelled()) throw CancellationException("Reverse timestamp indexing cancelled")
                timestamps.add(extractor.sampleTime)
                if (!extractor.advance()) break
            }
            if (isCancelled()) throw CancellationException("Reverse timestamp indexing cancelled")
            return ReversePtsPlan.fromPresentationTimes(timestamps.toSortedArray(), inUs, outUs)
        } finally {
            extractor.release()
        }
    }
}

/** Primitive bounded accumulator; sorting converts extractor decode order to presentation order. */
internal class PresentationTimestampCollector(
    private val maxSamples: Int = MAX_SAMPLES
) {
    private var values = LongArray(minOf(INITIAL_CAPACITY, maxSamples.coerceAtLeast(1)))
    var size: Int = 0
        private set

    init {
        require(maxSamples > 0)
    }

    fun add(timeUs: Long) {
        require(timeUs >= 0L) { "Video presentation timestamp must be non-negative" }
        require(size < maxSamples) { "Video frame index exceeds safety limit" }
        if (size == values.size) {
            val nextSize = minOf(maxSamples, maxOf(1, values.size * 2))
            values = values.copyOf(nextSize)
        }
        values[size++] = timeUs
    }

    fun toSortedArray(): LongArray {
        val result = values.copyOf(size)
        Arrays.sort(result)
        for (index in 1 until result.size) {
            require(result[index] > result[index - 1]) {
                "Video presentation timestamps must be unique"
            }
        }
        return result
    }

    companion object {
        // 1M samples bounds this indexer to roughly 8 MiB per retained array.
        const val MAX_SAMPLES = 1_000_000
        private const val INITIAL_CAPACITY = 1024
    }
}

