package au.parmtech.tongcut

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Verifies ducking in decoded AAC from a real Media3 MP4 export. */
@RunWith(AndroidJUnit4::class)
class AudioDuckingExportDeviceTest {
    @Test fun dialogueDucksMusicInEncodedMixAndVoiceRemainsAudible() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        fun fixture(name: String): Uri {
            val file = File(app.cacheDir, "ducking-$name")
            InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
            assertTrue("Missing test fixture: $name", file.length() > 500L)
            return Uri.fromFile(file)
        }
        val video = fixture("main_blue.mp4")
        val music = fixture("music_440.m4a")
        val voice = fixture("music_880.m4a")
        val timeline = LayerTimeline(
            tracks = listOf(
                TimelineTrack("music", "Music", LayerKind.AUDIO, 0),
                TimelineTrack("voice", "Dialogue", LayerKind.AUDIO, 1, duckingSource = true)
            ),
            layers = listOf(
                TimelineLayer("music-clip", "music", LayerKind.AUDIO, 0L, 3_000_000L,
                    sourceUri = music.toString(), sourceDurationUs = 3_000_000L),
                TimelineLayer("voice-clip", "voice", LayerKind.AUDIO, 1_300_000L, 400_000L,
                    sourceUri = voice.toString(), sourceDurationUs = 3_000_000L)
            )
        )
        val done = CountDownLatch(1)
        var output: Uri? = null
        var error: String? = null
        Handler(Looper.getMainLooper()).post {
            VideoExporter(app).start(
                clips = listOf(Clip(video, "Synthetic base", 3_000L)),
                options = ExportOptions(shortSidePixels = 720, mute = true),
                overlayLayers = timeline,
                onProgress = {},
                onSuccess = { output = it; done.countDown() },
                onError = { error = it; done.countDown() }
            )
        }
        assertTrue("Ducking export timed out", done.await(240L, TimeUnit.SECONDS))
        assertNull("Ducking export failed: $error", error)
        val exported = output ?: throw AssertionError("Ducking MP4 missing")
        try {
            val (rate, pcm) = decodeMono(app, exported)
            val baseline = toneAmplitude(pcm, rate, 440.0, 500_000L, 900_000L)
            val ducked = toneAmplitude(pcm, rate, 440.0, 1_400_000L, 1_700_000L)
            val voiceLevel = toneAmplitude(pcm, rate, 880.0, 1_400_000L, 1_700_000L)
            val recovery = toneAmplitude(pcm, rate, 440.0, 2_300_000L, 2_700_000L)
            assertTrue("440Hz music absent before dialogue: $baseline", baseline > 0.01)
            assertTrue("Dialogue did not duck encoded music: $ducked vs $baseline",
                ducked < baseline * 0.55)
            assertTrue("Dialogue was lost in encoded mix: $voiceLevel", voiceLevel > 0.006)
            assertTrue("Music did not recover after dialogue: $recovery vs $baseline",
                recovery > baseline * 0.65)
        } finally {
            app.contentResolver.delete(exported, null, null)
        }
    }

    private fun decodeMono(context: Context, uri: Uri): Pair<Int, FloatArray> {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw AssertionError("Export has no audio track")
            extractor.selectTrack(track)
            val inputFormat = extractor.getTrackFormat(track)
            val codec = MediaCodec.createDecoderByType(requireNotNull(
                inputFormat.getString(MediaFormat.KEY_MIME)))
            decoder = codec
            codec.configure(inputFormat, null, null, 0)
            codec.start()
            var rate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var floatPcm = false
            val pcm = FloatArray(rate * 3 + rate / 2)
            var inputDone = false
            var outputDone = false
            var loops = 0
            val info = MediaCodec.BufferInfo()
            while (!outputDone && loops++ < 2500) {
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index) ?: error("Missing decoder input")
                        buffer.clear()
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(index, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(index, 0, size,
                                extractor.sampleTime.coerceAtLeast(0L), 0)
                            extractor.advance()
                        }
                    }
                }
                val index = codec.dequeueOutputBuffer(info, 10_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val format = codec.outputFormat
                    rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    floatPcm = format.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        format.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                } else if (index >= 0) {
                    if (info.size > 0) {
                        val bytes = codec.getOutputBuffer(index) ?: error("Missing decoded samples")
                        val view = bytes.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                        view.position(info.offset)
                        view.limit(info.offset + info.size)
                        val sampleBytes = if (floatPcm) 4 else 2
                        val frameBytes = sampleBytes * channels.coerceAtLeast(1)
                        var frame = 0
                        while (view.remaining() >= frameBytes) {
                            val sample = if (floatPcm) view.float else view.short / 32768f
                            val timeUs = info.presentationTimeUs + frame * 1_000_000L / rate
                            val destination = (timeUs * rate / 1_000_000L).toInt()
                            if (destination in pcm.indices) pcm[destination] = sample
                            view.position(view.position() + frameBytes - sampleBytes)
                            frame++
                        }
                    }
                    outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(index, false)
                }
            }
            assertTrue("Audio decoder did not reach end of stream", outputDone)
            return rate to pcm
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            extractor.release()
        }
    }

    private fun toneAmplitude(
        pcm: FloatArray, rate: Int, frequency: Double, startUs: Long, endUs: Long
    ): Double {
        val start = (startUs * rate / 1_000_000L).toInt().coerceIn(0, pcm.size)
        val end = (endUs * rate / 1_000_000L).toInt().coerceIn(start, pcm.size)
        val count = end - start
        if (count < 100) return 0.0
        var cosine = 0.0
        var sine = 0.0
        for (i in 0 until count) {
            val phase = 2.0 * Math.PI * frequency * i / rate
            cosine += pcm[start + i] * cos(phase)
            sine += pcm[start + i] * sin(phase)
        }
        return 2.0 * sqrt(cosine * cosine + sine * sine) / count
    }
}
