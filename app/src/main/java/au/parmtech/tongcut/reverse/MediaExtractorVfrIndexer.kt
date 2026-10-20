package au.parmtech.tongcut.reverse

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.util.concurrent.CancellationException

/**
 * Reads actual video-track sample presentation times with MediaExtractor.
 *
 * Call on a worker thread. No video samples are decoded or copied; the indexer
 * retains bounded PTS/ordinal arrays only. Extractor cleanup runs for success,
 * source errors, sample-limit failures, and cooperative cancellation.
 */
object MediaExtractorVfrIndexer {
    fun index(
        context: Context,
        source: Uri,
        inUs: Long,
        outUs: Long,
        maxSamples: Int = VfrPresentationIndexBuilder.MAX_SAMPLES,
        isCancelled: () -> Boolean = { Thread.currentThread().isInterrupted }
    ): VfrPresentationIndex? {
        require(inUs >= 0L && outUs >= inUs) { "Invalid reverse trim" }
        if (inUs == outUs) return null

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, source, emptyMap())
            val videoTrack = (0 until extractor.trackCount).firstOrNull { track ->
                extractor.getTrackFormat(track).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("video/") == true
            } ?: throw IllegalArgumentException("Source has no video track")
            extractor.selectTrack(videoTrack)

            val index = VfrPresentationIndexBuilder(maxSamples)
            while (extractor.sampleTrackIndex >= 0) {
                if (isCancelled()) {
                    throw CancellationException("Reverse presentation-time indexing cancelled")
                }
                // sampleTime is the media sample's PTS in microseconds, not a
                // frame-rate-derived estimate. Preserve read ordinal in the builder.
                index.add(extractor.sampleTime)
                if (!extractor.advance()) break
            }
            if (isCancelled()) {
                throw CancellationException("Reverse presentation-time indexing cancelled")
            }
            return index.build(inUs, outUs)
        } finally {
            extractor.release()
        }
    }
}
