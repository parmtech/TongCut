package au.parmtech.tongcut

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.Closeable

/**
 * Bounded, read-only consumer for a fully materialized reverse segment. The segment lease keeps
 * every frame pinned against cache eviction for the lifetime of this reader. Returned bitmaps
 * are caller-owned and should be recycled when no longer displayed or encoded.
 */
class ReverseFramePlayback private constructor(
    private val segment: CachedReverseVideoSegment,
    private val lease: ReverseFrameCache.SegmentLease,
    private val maxDecodedPixels: Long
) : Closeable {
    private var closed = false
    private val files = lease.frameFiles

    init {
        require(files.size == segment.frameCount)
    }

    /** Returns the frame visible at segment output time, or null at/after the end. */
    @Synchronized
    fun frameIndexAtOutputTimeUs(outputTimeUs: Long): Int? {
        check(!closed) { "Reverse frame reader is closed" }
        return segment.frameIndexAtOutputTimeUs(outputTimeUs)
    }

    /** Decodes the frame visible at segment output time, or null at/after the end. */
    @Synchronized
    fun decodeFrameAt(outputTimeUs: Long): Bitmap? {
        check(!closed) { "Reverse frame reader is closed" }
        val frameIndex = segment.frameIndexAtOutputTimeUs(outputTimeUs) ?: return null
        val file = files[frameIndex]
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) {
            "Cached reverse frame is not a decodable image"
        }
        var sampleSize = 1
        while (decodedPixels(bounds.outWidth, bounds.outHeight, sampleSize) > maxDecodedPixels) {
            require(sampleSize <= (1 shl 29)) { "Reverse frame cannot fit decode pixel budget" }
            sampleSize *= 2
        }
        val bitmap = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sampleSize }
        ) ?: throw IllegalStateException("Cached reverse frame could not be decoded")
        if (bitmap.width.toLong() * bitmap.height.toLong() > maxDecodedPixels) {
            bitmap.recycle()
            throw IllegalStateException("Decoded reverse frame exceeded pixel budget")
        }
        return bitmap
    }

    /** File URI for a frame while this reader's segment lease remains open. */
    @Synchronized
    internal fun frameUriAt(index: Int): android.net.Uri {
        check(!closed) { "Reverse frame reader is closed" }
        require(index in files.indices)
        return android.net.Uri.fromFile(files[index])
    }

    @Synchronized
    override fun close() {
        if (!closed) {
            closed = true
            lease.close()
        }
    }

    private fun decodedPixels(width: Int, height: Int, sampleSize: Int): Long =
        (width / sampleSize).coerceAtLeast(1).toLong() *
            (height / sampleSize).coerceAtLeast(1).toLong()

    companion object {
        const val DEFAULT_MAX_DECODED_PIXELS = 2_073_600L // 1920 × 1080

        /** Returns null for incomplete/evicted segments; it never pins a partial segment. */
        fun open(
            segment: CachedReverseVideoSegment,
            cache: ReverseFrameCache,
            maxDecodedPixels: Long = DEFAULT_MAX_DECODED_PIXELS
        ): ReverseFramePlayback? {
            require(maxDecodedPixels > 0L)
            val lease = segment.openFrames(cache) ?: return null
            return ReverseFramePlayback(segment, lease, maxDecodedPixels)
        }
    }
}
