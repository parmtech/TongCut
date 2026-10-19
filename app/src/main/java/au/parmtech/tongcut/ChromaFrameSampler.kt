package au.parmtech.tongcut

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * P3 user-selected video chroma key. Sampling is from the ORIGINAL SOURCE frame
 * (before the key removes it), not from a composited blue/transparent preview.
 *
 * A bounded MediaMetadataRetriever decode is used only when the chooser opens.
 * Never decode frames on the playhead's frequent UI polling loop.
 */
internal object ChromaFrameSampler {
    const val MAX_EDGE_PX = 512

    fun frameAt(context: Context, uri: String, sourceTimeUs: Long): Bitmap {
        require(uri.isNotBlank() && sourceTimeUs >= 0L)
        return MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(context, Uri.parse(uri))
            val width = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
            )?.toIntOrNull() ?: throw IllegalArgumentException("Video width unavailable")
            val height = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
            )?.toIntOrNull() ?: throw IllegalArgumentException("Video height unavailable")
            require(width > 0 && height > 0) { "Invalid source video frame dimensions" }
            val divisor = max(width, height).toDouble() / MAX_EDGE_PX
            val targetW = (width / divisor.coerceAtLeast(1.0))
                .roundToInt().coerceIn(1, MAX_EDGE_PX)
            val targetH = (height / divisor.coerceAtLeast(1.0))
                .roundToInt().coerceIn(1, MAX_EDGE_PX)
            // At the very end of a trimmed clip, sourceAt(end) may equal
            // the media's EOF (which does not contain a decodable frame).
            // Clamp to a frame inside the file while keeping the picker frozen.
            val durationMs = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull()
            val safeUs = ChromaSampleMath.safeSeekUs(sourceTimeUs, durationMs)
            retriever.getScaledFrameAtTime(
                safeUs,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                targetW, targetH
            ) ?: throw IllegalArgumentException("Unable to decode source colour frame")
        }
    }
}

/** Coords and colour conversions are pure Kotlin; test without Android codecs. */
internal object ChromaSampleMath {
    /**
     * Timestamp of the nearest safe millisecond inside a finite media file.
     * Metadata may be absent or invalid; do not fabricate duration in that case.
     */
    /**
     * Convert an editor playhead from project ms to source µs without allowing
     * corrupt/restored state to overflow Math.multiplyExact in Compose.
     * The timeline is non-negative; a negative stale seek safely starts at zero.
     */
    fun safeProjectTimeUs(playheadMs: Long): Long = when {
        playheadMs <= 0L -> 0L
        playheadMs > Long.MAX_VALUE / 1_000L -> Long.MAX_VALUE
        else -> playheadMs * 1_000L
    }

    fun safeSeekUs(requestedUs: Long, durationMs: Long?): Long {
        require(requestedUs >= 0L)
        if (durationMs == null || durationMs <= 0L) return requestedUs
        // Duration is untrusted media metadata. Clamp before converting ms
        // to µs; a malformed container must not crash the colour picker.
        val lastMs = durationMs - 1L
        val endUs = if (lastMs > Long.MAX_VALUE / 1_000L) {
            Long.MAX_VALUE
        } else lastMs * 1_000L
        return requestedUs.coerceAtMost(endUs)
    }

    fun pixelAt(
        tapX: Float, tapY: Float,
        viewWidth: Int, viewHeight: Int,
        bitmapWidth: Int, bitmapHeight: Int
    ): Pair<Int, Int> {
        require(viewWidth > 0 && viewHeight > 0 &&
            bitmapWidth > 0 && bitmapHeight > 0)
        require(tapX.isFinite() && tapY.isFinite())
        val x = ((tapX.coerceIn(0f, viewWidth.toFloat()) / viewWidth) *
            bitmapWidth).toInt().coerceIn(0, bitmapWidth - 1)
        val y = ((tapY.coerceIn(0f, viewHeight.toFloat()) / viewHeight) *
            bitmapHeight).toInt().coerceIn(0, bitmapHeight - 1)
        return x to y
    }

    fun opaqueArgb(argb: Int): Int = (argb and 0x00FF_FFFF) or 0xFF00_0000.toInt()
}
