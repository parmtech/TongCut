package au.parmtech.tongcut

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

/**
 * Small, on-device audio waveform. Decodes PCM from the actual selected audio source.
 * No network, no broad storage permission; duration and memory are bounded.
 */
internal object AudioWaveformDecoder {
    private const val BINS = 80
    // Long clips use evenly distributed source windows, not a truncated 90s beginning.
    private val cache = object : LruCache<String, List<Float>>(18) {}
    private fun key(uri: String, start: Long, length: Long) = "sample-v2:$uri:$start:$length"

    @Synchronized fun lookup(uri: String, start: Long, length: Long): List<Float>? =
        cache.get(key(uri, start, length))
    @Synchronized private fun remember(uri: String, start: Long, length: Long, result: List<Float>) {
        cache.put(key(uri, start, length), result)
    }

    fun extract(context: Context, layer: TimelineLayer): List<Float> {
        val uri = requireNotNull(layer.sourceUri)
        lookup(uri, layer.sourceInUs, layer.durationUs)?.let { return it }
        val startUs = layer.sourceInUs
        val windows = WaveformScanPlan.windows(startUs, layer.durationUs)
        val histogram = WaveformPeaks(BINS, startUs, layer.durationUs)
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, Uri.parse(uri), null)
            val track = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: return histogram.snapshot()
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)
                ?: return histogram.snapshot()
            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()
            val info = MediaCodec.BufferInfo()
            var pcmFloat = false

            windows.forEachIndexed { windowIndex, window ->
                // Seeking and flushing one decoder lets a 20-minute music track
                // show peaks from the ENTIRE duration while decoding <=60 seconds.
                // Window timings are absolute source times; bins cover whole clip.
                if (Thread.currentThread().isInterrupted) return@forEachIndexed
                if (windowIndex > 0) decoder.flush()
                extractor.seekTo(window.startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                var inputDone = false
                var outputDone = false
                var loops = 0
                val packetCap = if (windows.size == 1) 12_000 else 450
                while (!outputDone && loops++ < packetCap &&
                    !Thread.currentThread().isInterrupted
                ) {
                    if (!inputDone) {
                        val index = decoder.dequeueInputBuffer(5_000)
                        if (index >= 0) {
                            val input = decoder.getInputBuffer(index) ?: break
                            input.clear()
                            val count = extractor.readSampleData(input, 0)
                            val timestamp = extractor.sampleTime
                            if (count < 0 || timestamp >= window.endUs) {
                                decoder.queueInputBuffer(
                                    index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(index, 0, count, max(0L, timestamp), 0)
                                extractor.advance()
                            }
                        }
                    }
                    val out = decoder.dequeueOutputBuffer(info, 5_000)
                    when {
                        out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val outputFormat = decoder.outputFormat
                            pcmFloat = outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                                outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) ==
                                    android.media.AudioFormat.ENCODING_PCM_FLOAT
                        }
                        out >= 0 -> {
                            val buffer = decoder.getOutputBuffer(out)
                            if (buffer != null && info.size > 0 &&
                                info.presentationTimeUs >= window.startUs &&
                                info.presentationTimeUs < window.endUs
                            ) {
                                val sample = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                                sample.position(info.offset)
                                sample.limit(info.offset + info.size)
                                var peak = 0f
                                if (pcmFloat) {
                                    while (sample.remaining() >= 4) {
                                        val amplitude = sample.float
                                        if (amplitude.isFinite()) peak =
                                            max(peak, abs(amplitude))
                                    }
                                } else {
                                    while (sample.remaining() >= 2) {
                                        peak = max(peak, abs(sample.short.toFloat() / 32768f))
                                    }
                                }
                                histogram.addAt(info.presentationTimeUs, peak)
                            }
                            outputDone = info.flags and
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            decoder.releaseOutputBuffer(out, false)
                        }
                    }
                }
            }
            val done = histogram.snapshot()
            // Interrupted scans should not permanently cache a partial waveform.
            if (!Thread.currentThread().isInterrupted) {
                remember(uri, startUs, layer.durationUs, done)
            }
            return done
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

}

@Composable
internal fun AudioWaveform(
    layer: TimelineLayer,
    modifier: Modifier = Modifier,
    tint: Color = Color(0xFF85DFB9)
) {
    val context = LocalContext.current
    var bins by remember(layer.sourceUri,layer.sourceInUs,layer.durationUs) {
        mutableStateOf(AudioWaveformDecoder.lookup(
            layer.sourceUri.orEmpty(), layer.sourceInUs, layer.durationUs
        ) ?: emptyList())
    }
    LaunchedEffect(layer.sourceUri,layer.sourceInUs,layer.durationUs) {
        bins = withContext(Dispatchers.IO) {
            runCatching { AudioWaveformDecoder.extract(context, layer) }.getOrDefault(emptyList())
        }
    }
    Canvas(modifier) {
        if (bins.isNotEmpty()) {
            val step = size.width / bins.size
            bins.forEachIndexed { i, amplitude ->
                val height = size.height * (0.08f + amplitude * 0.88f)
                drawLine(
                    color = tint,
                    start = Offset((i + 0.5f) * step, (size.height - height) / 2f),
                    end = Offset((i + 0.5f) * step, (size.height + height) / 2f),
                    strokeWidth = (step * 0.62f).coerceIn(1f, 4f)
                )
            }
        }
    }
}
