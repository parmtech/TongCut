package au.parmtech.tongcut

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Exact-PTS cached frame reader for one selected primary clip; open/decode from an IO worker. */
class ReversePreviewSession private constructor(
    private val readers: List<ReverseFramePlayback>,
    private val segmentStartsUs: LongArray,
    private val segmentDurationsUs: LongArray
) : Closeable {
    private var closed = false
    private var lastSegment = -1
    private var lastFrame = -1

    /** Returns null when the requested local clip time is outside its trimmed interval. */
    @Synchronized
    fun decodeFrameAt(outputTimeUs: Long): Bitmap? {
        check(!closed) { "Reverse preview is closed" }
        if (outputTimeUs < 0L) return null
        val segmentIndex = segmentStartsUs.indices.lastOrNull {
            segmentStartsUs[it] <= outputTimeUs
        } ?: return null
        val localUs = outputTimeUs - segmentStartsUs[segmentIndex]
        if (localUs >= segmentDurationsUs[segmentIndex]) return null
        if (segmentIndex == lastSegment &&
            readers[segmentIndex].frameIndexAtOutputTimeUs(localUs) == lastFrame) return null
        val bitmap = readers[segmentIndex].decodeFrameAt(localUs)
        if (bitmap != null) {
            lastSegment = segmentIndex
            lastFrame = readers[segmentIndex].frameIndexAtOutputTimeUs(localUs) ?: -1
        }
        return bitmap
    }

    @Synchronized
    fun invalidateLastFrame() {
        check(!closed) { "Reverse preview is closed" }
        lastSegment = -1
        lastFrame = -1
    }

    @Synchronized
    override fun close() {
        if (!closed) {
            closed = true
            readers.forEach { runCatching { it.close() } }
        }
    }

    companion object {
        /** Cancellable handoff: a session completed after cancellation is closed on the worker. */
        suspend fun openCancellable(context: Context, clip: DraftClip): ReversePreviewSession {
            val pending = AtomicReference<ReversePreviewSession?>(null)
            val session = suspendCancellableCoroutine<ReversePreviewSession> { continuation ->
                val cancelled = AtomicBoolean(false)
                val worker = Thread({
                    try {
                        val prepared = open(context, clip) {
                            cancelled.get() || Thread.currentThread().isInterrupted
                        }
                        pending.set(prepared)
                        if (cancelled.get() || !continuation.isActive) {
                            pending.getAndSet(null)?.close()
                        } else {
                            continuation.resume(prepared)
                        }
                    } catch (failure: Throwable) {
                        if (continuation.isActive) continuation.resumeWithException(failure)
                    }
                }, "TongCut-reverse-preview").apply { isDaemon = true }
                continuation.invokeOnCancellation {
                    cancelled.set(true)
                    worker.interrupt()
                    pending.getAndSet(null)?.close()
                }
                worker.start()
            }
            pending.compareAndSet(session, null)
            return session
        }

        /** Builds and pins bounded chunks; the caller must close the returned session. */
        fun open(
            context: Context,
            clip: DraftClip,
            isCancelled: () -> Boolean = { Thread.currentThread().isInterrupted }
        ): ReversePreviewSession {
            val source = Uri.parse(clip.sourceUri)
            val inUs = Math.multiplyExact(clip.inMs, 1_000L)
            val outUs = Math.multiplyExact(clip.outMs, 1_000L)
            val plan = ReversePtsIndexer.index(context.applicationContext, source, inUs, outUs, isCancelled)
                ?: throw IllegalArgumentException("No video frames were found in ${clip.label}.")
            val cache = ReverseFrameCache(File(context.applicationContext.cacheDir, "tongcut-reverse"))
            val segments = ArrayList<CachedReverseVideoSegment>()
            var from = 0
            while (from < plan.frameCount) {
                if (isCancelled()) throw java.util.concurrent.CancellationException(
                    "Reverse preview preparation cancelled"
                )
                var to = minOf(from + ReverseFrameCache.MAX_SEGMENT_LEASE_FRAMES, plan.frameCount)
                var chunk = plan.sliceOutputRange(from, to)
                while (chunk.durationUs > MAX_SEGMENT_DURATION_US && to > from + 1) {
                    to--
                    chunk = plan.sliceOutputRange(from, to)
                }
                require(chunk.durationUs <= MAX_SEGMENT_DURATION_US) {
                    "A reverse frame interval exceeds the preview segment limit"
                }
                segments += ExactReverseFrameDecoder.materialize(
                    context = context.applicationContext,
                    source = source,
                    inUs = chunk.sourceInUs,
                    outUs = chunk.sourceOutUs,
                    plan = chunk,
                    cache = cache,
                    isCancelled = isCancelled
                )
                from = to
            }

            val readers = ArrayList<ReverseFramePlayback>(segments.size)
            try {
                for (segment in segments) {
                    val reader = ReverseFramePlayback.open(segment, cache) ?:
                        throw IllegalStateException("Reverse preview cache is incomplete for ${clip.label}.")
                    readers += reader
                }
                val starts = LongArray(segments.size)
                val durations = LongArray(segments.size)
                var elapsedUs = 0L
                segments.forEachIndexed { index, segment ->
                    starts[index] = elapsedUs
                    durations[index] = segment.durationUs
                    elapsedUs = Math.addExact(elapsedUs, segment.durationUs)
                }
                require(elapsedUs == outUs - inUs) {
                    "Reverse preview chunks do not cover the selected trim"
                }
                return ReversePreviewSession(readers, starts, durations)
            } catch (failure: Throwable) {
                readers.forEach { runCatching { it.close() } }
                throw failure
            }
        }

        private const val MAX_SEGMENT_DURATION_US = 20_000_000L
    }
}
