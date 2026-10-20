package au.parmtech.tongcut

import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import java.io.Closeable

/**
 * Media3 image inputs for one fully decoded reverse segment. Keep this handle open until the
 * consumer has finished reading the image URIs; closing releases the cache pins atomically.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class ReverseImageSequence private constructor(
    val items: List<EditedMediaItem>,
    private val frames: List<ReverseFramePlayback>
) : Closeable {
    /** Ready-to-compose video-only sequence; this object must outlive Transformer consumption. */
    fun asVideoSequence(): EditedMediaItemSequence =
        EditedMediaItemSequence.withVideoFrom(items)

    override fun close() = frames.forEach { runCatching { it.close() } }

    companion object {
        /**
         * Builds a video-only sequence. Millisecond image durations use cumulative rounding so
         * quantization does not accumulate per frame; trims from DraftClip are millisecond based.
         * Returns null when the cache no longer contains a complete segment.
         */
        fun open(
            segment: CachedReverseVideoSegment,
            cache: ReverseFrameCache,
            frameRate: Int = ExportFrameRate.DEFAULT
        ): ReverseImageSequence? {
            return openSegments(listOf(segment), cache, frameRate)
        }

        /** Combines consecutive reverse chunks while rounding frame boundaries only once. */
        fun openSegments(
            segments: List<CachedReverseVideoSegment>,
            cache: ReverseFrameCache,
            frameRate: Int = ExportFrameRate.DEFAULT
        ): ReverseImageSequence? {
            require(frameRate in ExportFrameRate.choices)
            require(segments.isNotEmpty()) { "Reverse image sequence needs at least one segment" }
            val totalDurationUs = segments.fold(0L) { total, segment ->
                Math.addExact(total, segment.durationUs)
            }
            require(totalDurationUs > 0L && totalDurationUs % 1_000L == 0L) {
                "Reverse image sequence requires a millisecond-aligned total duration"
            }
            val readers = ArrayList<ReverseFramePlayback>(segments.size)
            try {
                for (segment in segments) {
                    val reader = ReverseFramePlayback.open(segment, cache) ?: run {
                        readers.forEach { it.close() }
                        return null
                    }
                    readers += reader
                }
                var elapsedUs = 0L
                var previousBoundaryMs = 0L
                val itemCount = segments.fold(0) { total, segment ->
                    Math.addExact(total, segment.frameCount)
                }
                val items = ArrayList<EditedMediaItem>(itemCount)
                segments.forEachIndexed { segmentIndex, segment ->
                    val reader = readers[segmentIndex]
                    for (index in 0 until segment.frameCount) {
                        elapsedUs = Math.addExact(elapsedUs, segment.frameDurationUs(index))
                        val boundaryMs = if (items.size == itemCount - 1) {
                            totalDurationUs / 1_000L
                        } else {
                            Math.addExact(elapsedUs, 500L) / 1_000L
                        }
                        val durationMs = boundaryMs - previousBoundaryMs
                        require(durationMs > 0L) {
                            "Reverse frame duration is below Media3 image timing resolution"
                        }
                        val image = MediaItem.Builder()
                            .setUri(reader.frameUriAt(index))
                            .setImageDurationMs(durationMs)
                            .build()
                        items += EditedMediaItem.Builder(image)
                            .setFrameRate(frameRate)
                            .setRemoveAudio(true)
                            .build()
                        previousBoundaryMs = boundaryMs
                    }
                }
                require(elapsedUs == totalDurationUs) {
                    "Reverse frame durations do not cover the sequence"
                }
                return ReverseImageSequence(items, readers)
            } catch (failure: Throwable) {
                readers.forEach { runCatching { it.close() } }
                throw failure
            }
        }
    }
}
