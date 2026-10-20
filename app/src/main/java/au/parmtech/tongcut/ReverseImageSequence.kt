package au.parmtech.tongcut

import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.EditedMediaItem
import java.io.Closeable

/**
 * Media3 image inputs for one fully decoded reverse segment. Keep this handle open until the
 * consumer has finished reading the image URIs; closing releases the cache pins atomically.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class ReverseImageSequence private constructor(
    val items: List<EditedMediaItem>,
    private val frames: ReverseFramePlayback
) : Closeable {
    override fun close() = frames.close()

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
            require(frameRate in ExportFrameRate.choices)
            require(segment.durationUs > 0L && segment.durationUs % 1_000L == 0L) {
                "Reverse image sequences require millisecond-aligned trim duration"
            }
            val frames = ReverseFramePlayback.open(segment, cache) ?: return null
            try {
                var elapsedUs = 0L
                var previousBoundaryMs = 0L
                val items = ArrayList<EditedMediaItem>(segment.frameCount)
                for (index in 0 until segment.frameCount) {
                    elapsedUs = Math.addExact(elapsedUs, segment.frameDurationUs(index))
                    val boundaryMs = if (index == segment.frameCount - 1) {
                        segment.durationUs / 1_000L
                    } else {
                        Math.addExact(elapsedUs, 500L) / 1_000L
                    }
                    val durationMs = boundaryMs - previousBoundaryMs
                    require(durationMs > 0L) {
                        "Reverse frame duration is below Media3 image timing resolution"
                    }
                    val image = MediaItem.Builder()
                        .setUri(frames.frameUriAt(index))
                        .setImageDurationMs(durationMs)
                        .build()
                    items += EditedMediaItem.Builder(image)
                        .setFrameRate(frameRate)
                        .setRemoveAudio(true)
                        .build()
                    previousBoundaryMs = boundaryMs
                }
                require(elapsedUs == segment.durationUs) {
                    "Reverse frame durations do not cover the segment"
                }
                return ReverseImageSequence(items, frames)
            } catch (failure: Throwable) {
                frames.close()
                throw failure
            }
        }
    }
}
