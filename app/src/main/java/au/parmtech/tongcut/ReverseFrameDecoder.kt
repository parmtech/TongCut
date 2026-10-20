package au.parmtech.tongcut

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt

/** Bounds for a single materialized reverse segment; one decoded bitmap is held at a time. */
data class ReverseDecodeLimits(
    val maxFrameCount: Int = 600,
    val maxDurationUs: Long = 20_000_000L,
    val maxDimension: Int = 1920,
    val maxPixels: Long = 2_073_600L,
    val maxSegmentBytes: Long = 128L * 1024L * 1024L,
    val maxFrameBytes: Int = 16 * 1024 * 1024,
    val jpegQuality: Int = 90
) {
    init {
        require(maxFrameCount in 1..ReverseFrameCache.MAX_SEGMENT_LEASE_FRAMES)
        require(maxDurationUs > 0L)
        require(maxDimension > 0 && maxPixels > 0L)
        require(maxSegmentBytes > 0L && maxFrameBytes > 0)
        require(jpegQuality in 50..100)
    }
}

/** A complete best-effort preview cache. Requested PTSs are not verified decoded-frame PTSs. */
class CachedReversePreviewSegment internal constructor(
    val cacheKey: String,
    val frameCount: Int,
    val width: Int,
    val height: Int,
    val durationUs: Long,
    private val ptsPlan: ReversePtsPlan
) {
    fun requestedSourceTimeUs(outputOffset: Int): Long {
        require(outputOffset in 0 until frameCount)
        return ptsPlan.sourceTimeUs(outputOffset)
    }

    /** A caller must close each lease after decoding/using the encoded frame. */
    fun openFrame(cache: ReverseFrameCache, outputOffset: Int): ReverseFrameCache.FrameLease? {
        require(outputOffset in 0 until frameCount)
        return cache.openFrame(cacheKey, outputOffset.toLong())
    }

    /** Pins all currently cached frames together; close promptly after the preview/export read. */
    fun openFrames(cache: ReverseFrameCache): ReverseFrameCache.SegmentLease? =
        cache.openSegment(cacheKey, frameCount)
}

/**
 * Best-effort preview materialization using MediaMetadataRetriever's nearest-frame API.
 * The API does not expose the decoded frame's actual PTS, so this must not be used for
 * exact VFR reverse playback/export. Call on a worker thread; editor/export wiring is separate.
 */
object ApproximateReverseFrameMaterializer {
    private const val DECODER_VERSION = "retriever-closest-scaled-jpeg-v1"

    fun materialize(
        context: Context,
        source: Uri,
        sourceRevision: String = "",
        inUs: Long,
        outUs: Long,
        plan: ReversePtsPlan,
        cache: ReverseFrameCache,
        limits: ReverseDecodeLimits = ReverseDecodeLimits(),
        isCancelled: () -> Boolean = { Thread.currentThread().isInterrupted }
    ): CachedReversePreviewSegment {
        require(inUs >= 0L && outUs > inUs) { "Invalid reverse trim" }
        val durationUs = outUs - inUs
        require(durationUs <= limits.maxDurationUs) { "Reverse segment exceeds duration limit" }
        require(plan.frameCount in 1..limits.maxFrameCount) {
            "Reverse segment exceeds frame limit"
        }

        val retriever = MediaMetadataRetriever()
        var cacheKey: String? = null
        try {
            checkCancelled(isCancelled)
            retriever.setDataSource(context.applicationContext, source)
            val sourceWidth = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
            )?.toIntOrNull()?.takeIf { it > 0 }
                ?: throw IllegalArgumentException("Video dimensions are unavailable")
            val sourceHeight = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
            )?.toIntOrNull()?.takeIf { it > 0 }
                ?: throw IllegalArgumentException("Video dimensions are unavailable")
            val rotation = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
            )?.toIntOrNull() ?: 0
            val (orientedWidth, orientedHeight) = if (rotation == 90 || rotation == 270) {
                sourceHeight to sourceWidth
            } else sourceWidth to sourceHeight
            val (targetWidth, targetHeight) = boundedSize(
                orientedWidth, orientedHeight, limits.maxDimension, limits.maxPixels
            )
            val key = segmentKey(
                source.toString(), sourceRevision, inUs, outUs, plan,
                targetWidth, targetHeight, limits.jpegQuality
            )
            cacheKey = key

            var totalBytes = 0L
            for (outputIndex in 0 until plan.frameCount) {
                checkCancelled(isCancelled)
                val sourceTimeUs = plan.sourceTimeUs(outputIndex)
                require(sourceTimeUs >= inUs && sourceTimeUs < outUs) {
                    "Reverse PTS plan contains a timestamp outside its trim"
                }
                val bitmap = retriever.getScaledFrameAtTime(
                    sourceTimeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST,
                    targetWidth,
                    targetHeight
                ) ?: throw IllegalArgumentException(
                    "Decoder returned no frame at source PTS $sourceTimeUs"
                )
                try {
                    checkCancelled(isCancelled)
                    require(bitmap.width in 1..limits.maxDimension &&
                        bitmap.height in 1..limits.maxDimension &&
                        bitmap.width.toLong() * bitmap.height.toLong() <= limits.maxPixels) {
                        "Decoded frame exceeds size limits"
                    }
                    val encoded = LimitedFrameOutput(limits.maxFrameBytes).use { output ->
                        check(bitmap.compress(Bitmap.CompressFormat.JPEG, limits.jpegQuality, output)) {
                            "JPEG encoder rejected a reverse frame"
                        }
                        output.toByteArray()
                    }
                    checkCancelled(isCancelled)
                    totalBytes = Math.addExact(totalBytes, encoded.size.toLong())
                    require(totalBytes <= limits.maxSegmentBytes) {
                        "Reverse segment exceeds encoded-byte limit"
                    }
                    cache.put(key, outputIndex.toLong(), encoded, isCancelled)
                } finally {
                    bitmap.recycle()
                }
            }
            // A smaller caller-configured cache may evict frames written earlier in this
            // generation. Atomically validate point-in-time completeness before publishing;
            // callers must still handle a later miss after this temporary lease is closed.
            val complete = cache.openSegment(key, plan.frameCount)
                ?: throw IllegalStateException(
                    "Reverse preview cache could not retain a complete segment"
                )
            complete.close()
            checkCancelled(isCancelled)
            return CachedReversePreviewSegment(
                cacheKey = key,
                frameCount = plan.frameCount,
                width = targetWidth,
                height = targetHeight,
                durationUs = durationUs,
                ptsPlan = plan
            )
        } catch (failure: Throwable) {
            cacheKey?.let(cache::removeSegment)
            throw failure
        } finally {
            retriever.release()
        }
    }

    private fun boundedSize(width: Int, height: Int, maxDimension: Int, maxPixels: Long): Pair<Int, Int> {
        require(width > 0 && height > 0)
        val pixels = width.toLong() * height.toLong()
        val scale = min(
            1.0,
            min(
                maxDimension.toDouble() / maxOf(width, height),
                sqrt(maxPixels.toDouble() / pixels.toDouble())
            )
        )
        val resultWidth = maxOf(1, floor(width * scale).toInt())
        val resultHeight = maxOf(1, floor(height * scale).toInt())
        require(resultWidth.toLong() * resultHeight <= maxPixels)
        return resultWidth to resultHeight
    }

    /** Unique per attempt to make cancellation cleanup incapable of deleting an older segment. */
    private fun segmentKey(
        sourceIdentity: String,
        sourceRevision: String,
        inUs: Long,
        outUs: Long,
        plan: ReversePtsPlan,
        width: Int,
        height: Int,
        jpegQuality: Int
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(
            ("$DECODER_VERSION|$sourceIdentity|$sourceRevision|$inUs|$outUs|" +
                "${plan.frameCount}|$width|$height|$jpegQuality|").toByteArray(Charsets.UTF_8)
        )
        val stamp = ByteArray(Long.SIZE_BYTES)
        for (index in 0 until plan.frameCount) {
            val time = plan.sourceTimeUs(index)
            for (byteIndex in 0 until Long.SIZE_BYTES) {
                stamp[byteIndex] = (time ushr (56 - byteIndex * 8)).toByte()
            }
            digest.update(stamp)
        }
        val fingerprint = digest.digest().joinToString("") { "%02x".format(it) }
        return "reverse-$fingerprint-${UUID.randomUUID()}"
    }

    private fun checkCancelled(isCancelled: () -> Boolean) {
        if (isCancelled()) throw CancellationException("Reverse frame decoding cancelled")
    }

    private class LimitedFrameOutput(private val limitBytes: Int) : ByteArrayOutputStream() {
        override fun write(value: Int) {
            check(count < limitBytes) { "Encoded reverse frame exceeds size limit" }
            super.write(value)
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            check(offset >= 0 && length >= 0 && offset <= bytes.size - length)
            check(length <= limitBytes - count) { "Encoded reverse frame exceeds size limit" }
            super.write(bytes, offset, length)
        }
    }
}
