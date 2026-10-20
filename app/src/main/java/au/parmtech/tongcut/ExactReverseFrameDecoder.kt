package au.parmtech.tongcut

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.media.Image
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt

/** Exact PTS-matched reverse frame cache descriptor. Frame zero is the last PTS in the trim. */
class CachedReverseVideoSegment internal constructor(
    val cacheKey: String,
    val frameCount: Int,
    val width: Int,
    val height: Int,
    val durationUs: Long,
    private val ptsPlan: ReversePtsPlan
) {
    fun sourceTimeUs(outputOffset: Int): Long {
        require(outputOffset in 0 until frameCount)
        return ptsPlan.sourceTimeUs(outputOffset)
    }

    fun openFrame(cache: ReverseFrameCache, outputOffset: Int): ReverseFrameCache.FrameLease? {
        require(outputOffset in 0 until frameCount)
        return cache.openFrame(cacheKey, outputOffset.toLong())
    }

    fun openFrames(cache: ReverseFrameCache): ReverseFrameCache.SegmentLease? =
        cache.openSegment(cacheKey, frameCount)
}

/**
 * Decodes a trimmed video into bounded private JPEG frames and maps every frame by the exact
 * MediaCodec output PTS. Call only on a worker thread. Unsupported formats or incomplete PTS
 * mappings fail closed and remove this attempt's unique cache generation.
 */
object ExactReverseFrameDecoder {
    private const val MAX_INPUT_SAMPLES = 12_000
    private const val MAX_SOURCE_DIMENSION = 4_096
    private const val MAX_SOURCE_PIXELS = 8_847_360L // 4096 × 2160
    private const val POLL_TIMEOUT_US = 10_000L
    private const val MAX_IDLE_POLLS = 6_000
    private const val DECODER_VERSION = "mediacodec-pts-yuv420-jpeg-v1"

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
    ): CachedReverseVideoSegment {
        require(inUs >= 0L && outUs > inUs) { "Invalid reverse trim" }
        val durationUs = outUs - inUs
        require(durationUs <= limits.maxDurationUs) { "Reverse segment exceeds duration limit" }
        require(plan.frameCount in 1..limits.maxFrameCount) {
            "Reverse segment exceeds frame limit"
        }

        val extractor = MediaExtractor()
        var activeDecoder: MediaCodec? = null
        var cacheKey: String? = null
        try {
            checkCancelled(isCancelled)
            extractor.setDataSource(context.applicationContext, source, emptyMap())
            val track = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("video/") == true
            } ?: throw IllegalArgumentException("Source has no video track")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = requireNotNull(format.getString(MediaFormat.KEY_MIME))
            val sourceWidth = format.getInteger(MediaFormat.KEY_WIDTH)
            val sourceHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
            require(sourceWidth in 1..MAX_SOURCE_DIMENSION &&
                sourceHeight in 1..MAX_SOURCE_DIMENSION &&
                sourceWidth.toLong() * sourceHeight <= MAX_SOURCE_PIXELS) {
                "Source dimensions exceed reverse decoder limits"
            }
            val cropLeft = format.getIntegerOrNull(MediaFormat.KEY_CROP_LEFT) ?: 0
            val cropTop = format.getIntegerOrNull(MediaFormat.KEY_CROP_TOP) ?: 0
            val cropRight = format.getIntegerOrNull(MediaFormat.KEY_CROP_RIGHT) ?: sourceWidth - 1
            val cropBottom = format.getIntegerOrNull(MediaFormat.KEY_CROP_BOTTOM) ?: sourceHeight - 1
            val visibleWidth = cropRight - cropLeft + 1
            val visibleHeight = cropBottom - cropTop + 1
            require(cropLeft >= 0 && cropTop >= 0 && cropRight < sourceWidth &&
                cropBottom < sourceHeight && visibleWidth in 1..sourceWidth &&
                visibleHeight in 1..sourceHeight) {
                "Source crop metadata is invalid"
            }
            val rotation = readRotation(context, source)
            require(rotation == 0 || rotation == 90 || rotation == 180 || rotation == 270) {
                "Unsupported source rotation"
            }
            val orientedWidth = if (rotation == 90 || rotation == 270) visibleHeight else visibleWidth
            val orientedHeight = if (rotation == 90 || rotation == 270) visibleWidth else visibleHeight
            val (targetWidth, targetHeight) = boundedSize(
                orientedWidth, orientedHeight, limits.maxDimension, limits.maxPixels
            )
            val key = segmentKey(
                source.toString(), sourceRevision, inUs, outUs, plan,
                targetWidth, targetHeight, limits.jpegQuality
            )
            cacheKey = key

            val outputIndexByPts = HashMap<Long, Int>(plan.frameCount)
            for (outputIndex in 0 until plan.frameCount) {
                val pts = plan.sourceTimeUs(outputIndex)
                require(pts >= inUs && pts < outUs) {
                    "Reverse PTS plan contains a timestamp outside its trim"
                }
                check(outputIndexByPts.put(pts, outputIndex) == null) {
                    "Reverse PTS plan contains duplicate timestamps"
                }
            }
            val seen = BooleanArray(plan.frameCount)

            val codec = MediaCodec.createDecoderByType(mime)
            activeDecoder = codec
            codec.configure(format, null, null, 0)
            codec.start()
            extractor.seekTo(inUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var inputSamples = 0
            var idlePolls = 0
            var totalBytes = 0L
            var passedOutTrim = false
            var syncSeenAfterTrim = false
            var decodedFormat = format

            while (!outputDone) {
                checkCancelled(isCancelled)
                var madeProgress = false
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(POLL_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val input = requireNotNull(codec.getInputBuffer(inputIndex))
                        input.clear()
                        val size = extractor.readSampleData(input, 0)
                        val sampleTimeUs = extractor.sampleTime
                        val sampleFlags = extractor.sampleFlags
                        val nextSyncAfterTrim = passedOutTrim && syncSeenAfterTrim &&
                            (sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                        if (size < 0 || sampleTimeUs < 0L || nextSyncAfterTrim) {
                            codec.queueInputBuffer(
                                inputIndex, 0, 0, maxOf(0L, sampleTimeUs),
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            require((sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED) == 0) {
                                "Encrypted source samples are not supported for reverse decoding"
                            }
                            require((sampleFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) == 0) {
                                "Fragmented video samples are not supported for reverse decoding"
                            }
                            inputSamples++
                            require(inputSamples <= MAX_INPUT_SAMPLES) {
                                "Reverse decode exceeded input sample limit"
                            }
                            if (sampleTimeUs >= outUs) {
                                passedOutTrim = true
                                if ((sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                                    syncSeenAfterTrim = true
                                }
                            }
                            codec.queueInputBuffer(inputIndex, 0, size, sampleTimeUs, 0)
                            extractor.advance()
                        }
                        madeProgress = true
                    }
                }

                val outputIndex = codec.dequeueOutputBuffer(info, POLL_TIMEOUT_US)
                when {
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        decodedFormat = codec.outputFormat
                        madeProgress = true
                    }
                    outputIndex >= 0 -> {
                        madeProgress = true
                        try {
                            if (info.size > 0) {
                                val outputPtsUs = info.presentationTimeUs
                                if (outputPtsUs >= inUs && outputPtsUs < outUs) {
                                    val mappedIndex = outputIndexByPts[outputPtsUs]
                                        ?: throw IllegalArgumentException(
                                            "Decoder returned an unindexed in-trim PTS $outputPtsUs"
                                        )
                                    require(!seen[mappedIndex]) {
                                        "Decoder returned duplicate PTS $outputPtsUs"
                                    }
                                    val image = codec.getOutputImage(outputIndex)
                                        ?: throw IllegalArgumentException(
                                            "Decoder does not expose YUV output images"
                                        )
                                    val bitmap = try {
                                        image.toBoundedBitmap(
                                            targetWidth, targetHeight, rotation,
                                            limits.maxPixels, decodedFormat, format, isCancelled
                                        )
                                    } finally {
                                        image.close()
                                    }
                                    try {
                                        checkCancelled(isCancelled)
                                        val encoded = BoundedJpegOutput(limits.maxFrameBytes).use {
                                            output ->
                                            check(bitmap.compress(
                                                Bitmap.CompressFormat.JPEG,
                                                limits.jpegQuality,
                                                output
                                            )) { "JPEG encoder rejected a reverse frame" }
                                            output.toByteArray()
                                        }
                                        totalBytes = Math.addExact(totalBytes, encoded.size.toLong())
                                        require(totalBytes <= limits.maxSegmentBytes) {
                                            "Reverse segment exceeds encoded-byte limit"
                                        }
                                        cache.put(key, mappedIndex.toLong(), encoded, isCancelled)
                                        seen[mappedIndex] = true
                                    } finally {
                                        bitmap.recycle()
                                    }
                                }
                            }
                            outputDone = info.flags and
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        } finally {
                            codec.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                }
                if (madeProgress) idlePolls = 0 else {
                    idlePolls++
                    if (idlePolls > MAX_IDLE_POLLS) {
                        throw IllegalStateException("Reverse decoder stalled before segment end")
                    }
                }
            }

            checkCancelled(isCancelled)
            require(seen.all { it }) { "Decoder did not produce every indexed reverse frame" }
            val complete = cache.openSegment(key, plan.frameCount)
                ?: throw IllegalStateException("Reverse cache could not retain a complete segment")
            complete.close()
            checkCancelled(isCancelled)
            return CachedReverseVideoSegment(
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
            runCatching { activeDecoder?.stop() }
            runCatching { activeDecoder?.release() }
            extractor.release()
        }
    }

    private fun Image.toBoundedBitmap(
        targetWidth: Int,
        targetHeight: Int,
        rotation: Int,
        maxPixels: Long,
        inputFormat: MediaFormat,
        sourceFormat: MediaFormat,
        isCancelled: () -> Boolean
    ): Bitmap {
        require(width in 1..MAX_SOURCE_DIMENSION && height in 1..MAX_SOURCE_DIMENSION &&
            width.toLong() * height <= MAX_SOURCE_PIXELS) {
            "Decoded image dimensions exceed reverse decoder limits"
        }
        require(format == ImageFormat.YUV_420_888 && planes.size == 3) {
            "Decoder returned unsupported image format"
        }
        val crop = cropRect ?: Rect(0, 0, width, height)
        require(crop.width() > 0 && crop.height() > 0)
        val displayWidth = if (rotation == 90 || rotation == 270) crop.height() else crop.width()
        val displayHeight = if (rotation == 90 || rotation == 270) crop.width() else crop.height()
        require(targetWidth.toLong() * targetHeight <= maxPixels)
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]
        val yBuffer = yPlane.buffer.duplicate()
        val uBuffer = uPlane.buffer.duplicate()
        val vBuffer = vPlane.buffer.duplicate()
        val output = IntArray(Math.multiplyExact(targetWidth, targetHeight))
        val fullRange = (inputFormat.getIntegerOrNull(MediaFormat.KEY_COLOR_RANGE)
            ?: sourceFormat.getIntegerOrNull(MediaFormat.KEY_COLOR_RANGE)) ==
            MediaFormat.COLOR_RANGE_FULL
        val colorStandard = inputFormat.getIntegerOrNull(MediaFormat.KEY_COLOR_STANDARD)
            ?: sourceFormat.getIntegerOrNull(MediaFormat.KEY_COLOR_STANDARD)
        val colorTransfer = inputFormat.getIntegerOrNull(MediaFormat.KEY_COLOR_TRANSFER)
            ?: sourceFormat.getIntegerOrNull(MediaFormat.KEY_COLOR_TRANSFER)
        require(colorTransfer == MediaFormat.COLOR_TRANSFER_SDR_VIDEO) {
            "Reverse preview requires an explicitly identified SDR video transfer"
        }
        val (chromaRed, chromaGreenU, chromaGreenV, chromaBlue) = when (colorStandard) {
            MediaFormat.COLOR_STANDARD_BT709 ->
                floatArrayOf(1.5748f, 0.1873f, 0.4681f, 1.8556f)
            MediaFormat.COLOR_STANDARD_BT2020 ->
                floatArrayOf(1.4746f, 0.16455f, 0.57135f, 1.8814f)
            else -> floatArrayOf(1.402f, 0.344136f, 0.714136f, 1.772f)
        }
        val chromaScale = if (fullRange) 1f else 255f / 224f

        for (destY in 0 until targetHeight) {
            checkCancelled(isCancelled)
            val displayY = (destY.toLong() * displayHeight / targetHeight).toInt()
            for (destX in 0 until targetWidth) {
                val displayX = (destX.toLong() * displayWidth / targetWidth).toInt()
                val (rawX, rawY) = when (rotation) {
                    90 -> displayY to (crop.height() - 1 - displayX)
                    180 -> (crop.width() - 1 - displayX) to (crop.height() - 1 - displayY)
                    270 -> (crop.width() - 1 - displayY) to displayX
                    else -> displayX to displayY
                }
                val x = crop.left + rawX
                val y = crop.top + rawY
                val yValue = samplePlane(yBuffer, yPlane.rowStride, yPlane.pixelStride, x, y)
                val chromaX = x / 2
                val chromaY = y / 2
                val uValue = (samplePlane(
                    uBuffer, uPlane.rowStride, uPlane.pixelStride, chromaX, chromaY
                ) - 128) * chromaScale
                val vValue = (samplePlane(
                    vBuffer, vPlane.rowStride, vPlane.pixelStride, chromaX, chromaY
                ) - 128) * chromaScale
                val luma = if (fullRange) yValue.toFloat() else (yValue - 16).coerceAtLeast(0) *
                    (255f / 219f)
                val red = (luma + chromaRed * vValue).toInt().coerceIn(0, 255)
                val green = (luma - chromaGreenU * uValue - chromaGreenV * vValue)
                    .toInt().coerceIn(0, 255)
                val blue = (luma + chromaBlue * uValue).toInt().coerceIn(0, 255)
                output[destY * targetWidth + destX] =
                    (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
            }
        }
        return Bitmap.createBitmap(output, targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
    }

    private fun samplePlane(
        buffer: java.nio.ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        x: Int,
        y: Int
    ): Int {
        val offset = buffer.position() + y * rowStride + x * pixelStride
        require(offset in buffer.position until buffer.limit()) {
            "Decoder returned truncated YUV plane data"
        }
        return buffer.get(offset).toInt() and 255
    }

    private fun MediaFormat.getIntegerOrNull(key: String): Int? =
        if (containsKey(key)) getInteger(key) else null

    private fun readRotation(context: Context, source: Uri): Int =
        MediaMetadataRetriever().use { metadata ->
            metadata.setDataSource(context.applicationContext, source)
            metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
        }

    private fun boundedSize(
        width: Int,
        height: Int,
        maxDimension: Int,
        maxPixels: Long
    ): Pair<Int, Int> {
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

    private fun segmentKey(
        source: String,
        revision: String,
        inUs: Long,
        outUs: Long,
        plan: ReversePtsPlan,
        width: Int,
        height: Int,
        quality: Int
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(
            ("$DECODER_VERSION|$source|$revision|$inUs|$outUs|${plan.frameCount}|" +
                "$width|$height|$quality|").toByteArray(Charsets.UTF_8)
        )
        val stamp = ByteArray(Long.SIZE_BYTES)
        for (index in 0 until plan.frameCount) {
            val time = plan.sourceTimeUs(index)
            for (byteIndex in stamp.indices) {
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

    private class BoundedJpegOutput(private val maxBytes: Int) : ByteArrayOutputStream() {
        override fun write(value: Int) {
            check(count < maxBytes) { "Encoded reverse frame exceeds size limit" }
            super.write(value)
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            check(offset >= 0 && length >= 0 && offset <= bytes.size - length)
            check(length <= maxBytes - count) { "Encoded reverse frame exceeds size limit" }
            super.write(bytes, offset, length)
        }
    }
}
