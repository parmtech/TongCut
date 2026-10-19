package au.parmtech.tongcut

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
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

/**
 * P2 acceptance uses synthetic, locally generated media only. A successful test
 * proves the actual encoded output contains PiP/video/photo/text and *two* audible
 * imported frequencies; no user's media is uploaded to CI.
 */
@RunWith(AndroidJUnit4::class)
class MixedMediaExportDeviceTest {
    private fun fixture(context: Context, name: String): File {
        val file = File(context.cacheDir, "combined-$name")
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        // Tiny uncompressed-color PNG fixtures can be below 500 bytes.
        val minimum = if (name.endsWith(".png")) 64L else 500L
        assertTrue("Test fixture empty: $name", file.length() > minimum)
        return file
    }

    @Test fun videoPhotoCaptionAndTwoAudioFrequenciesSurviveSingleExport() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val base = fixture(app, "main_blue.mp4")
        val pip = fixture(app, "overlay_red.mp4")
        val photo = fixture(app, "matte_green_red.png")
        val audio440 = fixture(app, "music_440.m4a")
        val audio880 = fixture(app, "music_880.m4a")
        val tracks = listOf(
            TimelineTrack("v","Video overlay",LayerKind.VIDEO,0),
            TimelineTrack("p","Keyed image",LayerKind.IMAGE,1),
            TimelineTrack("a1","Music",LayerKind.AUDIO,2,gain=1f),
            TimelineTrack("a2","Voice",LayerKind.AUDIO,3,gain=0.5f),
            TimelineTrack("t","Caption",LayerKind.TEXT,4)
        )
        val graph = LayerTimeline(tracks=tracks,layers=listOf(
            TimelineLayer(
                "pip","v",LayerKind.VIDEO,500_000L,1_500_000L,
                sourceUri=Uri.fromFile(pip).toString(),
                sourceDurationUs=3_000_000L
            ),
            TimelineLayer(
                "key","p",LayerKind.IMAGE,1_200_000L,1_000_000L,
                sourceUri=Uri.fromFile(photo).toString(),
                photoEffects=PhotoEffects(
                    mask=PhotoMask.CIRCLE,feather=0.03f,
                    chroma=PhotoChroma(tolerance=0.18f,feather=0.12f)
                )
            ),
            TimelineLayer(
                "tone440","a1",LayerKind.AUDIO,500_000L,2_000_000L,
                sourceUri=Uri.fromFile(audio440).toString(),
                sourceDurationUs=3_000_000L
            ),
            TimelineLayer(
                "tone880","a2",LayerKind.AUDIO,500_000L,2_000_000L,
                sourceUri=Uri.fromFile(audio880).toString(),
                sourceDurationUs=3_000_000L
            ),
            TimelineLayer("caption","t",LayerKind.TEXT,800_000L,1_800_000L,
                text="TONGCUT TEST")
        ))
        val done=CountDownLatch(1)
        var exported: Uri? = null
        var failure: String? = null
        Handler(Looper.getMainLooper()).post {
            VideoExporter(app).start(
                clips=listOf(Clip(Uri.fromFile(base),"Blue base",3_000L)),
                options=ExportOptions(shortSidePixels=720,mute=true),
                overlayLayers=graph,
                onProgress={},
                onSuccess={exported=it;done.countDown()},
                onError={failure=it;done.countDown()}
            )
        }
        assertTrue("Combined export did not finish",done.await(240,TimeUnit.SECONDS))
        assertNull("Combined export failed: $failure",failure)
        val uri=exported ?: throw AssertionError("Combined MP4 missing")
        try {
            MediaMetadataRetriever().use { media ->
                media.setDataSource(app,uri)
                assertEquals("yes",media.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))
                val duration=media.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION
                )?.toLongOrNull() ?: 0L
                assertTrue("Mixed video changed primary duration: $duration",
                    duration in 2_800L..3_250L)
                val before=countRed(media,250_000L)
                val during=countRed(media,1_500_000L)
                val after=countRed(media,2_750_000L)
                assertTrue("Overlays missing from mixed export: $before $during",
                    during>before+150)
                assertTrue("Overlay persists after its end: $after $before",
                    after<=before+80)
            }
            val (rate,pcm)=readFirstChannel(app,uri)
            assertTrue("No decoded audio in combined MP4",pcm.size>8_000)
            val p440=toneAmplitude(pcm,rate,440.0)
            val p880=toneAmplitude(pcm,rate,880.0)
            assertTrue("440Hz track missing: amplitude=$p440",p440>0.004)
            assertTrue("880Hz mixed track missing: amplitude=$p880",p880>0.003)
        } finally {
            app.contentResolver.delete(uri,null,null)
        }
    }

    private fun countRed(media: MediaMetadataRetriever, atUs: Long): Int {
        val bmp=media.getFrameAtTime(
            atUs,MediaMetadataRetriever.OPTION_CLOSEST
        ) ?: throw AssertionError("Missing decoded video at $atUs")
        try {
            var red=0
            for(y in 0 until bmp.height step 6) {
                for(x in 0 until bmp.width step 6) {
                    val pixel=bmp.getPixel(x,y)
                    if(Color.red(pixel)>125 &&
                        Color.red(pixel)>Color.green(pixel)*1.5 &&
                        Color.red(pixel)>Color.blue(pixel)*1.5) red++
                }
            }
            return red
        } finally { bmp.recycle() }
    }

    private fun readFirstChannel(context: Context, uri: Uri): Pair<Int,FloatArray> {
        val extractor=MediaExtractor()
        var decoder: MediaCodec?=null
        try {
            extractor.setDataSource(context,uri,null)
            val idx=(0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/")==true
            } ?: throw AssertionError("No audio stream in exported MP4")
            extractor.selectTrack(idx)
            val format=extractor.getTrackFormat(idx)
            val mime=requireNotNull(format.getString(MediaFormat.KEY_MIME))
            val player=MediaCodec.createDecoderByType(mime)
            decoder=player
            player.configure(format,null,null,0)
            player.start()
            var sampleRate=format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels=format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var floats=false
            var inputEnd=false
            var outputEnd=false
            val bufferInfo=MediaCodec.BufferInfo()
            val samples=ArrayList<Float>(48_000)
            var loops=0
            while (!outputEnd && samples.size<48_000 && loops++<2000) {
                if (!inputEnd) {
                    val index=player.dequeueInputBuffer(10_000)
                    if(index>=0) {
                        val data=player.getInputBuffer(index)
                            ?: throw AssertionError("Audio decoder input buffer missing")
                        data.clear()
                        val count=extractor.readSampleData(data,0)
                        if(count<0) {
                            player.queueInputBuffer(index,0,0,0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnd=true
                        } else {
                            player.queueInputBuffer(index,0,count,
                                extractor.sampleTime.coerceAtLeast(0L),0)
                            extractor.advance()
                        }
                    }
                }
                val out=player.dequeueOutputBuffer(bufferInfo,10_000)
                when {
                    out==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFormat=player.outputFormat
                        sampleRate=outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels=outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        floats=outFormat.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) ==
                                AudioFormat.ENCODING_PCM_FLOAT
                    }
                    out>=0 -> {
                        if (bufferInfo.size>0 &&
                            bufferInfo.presentationTimeUs in 700_000L..1_800_000L) {
                            val data=player.getOutputBuffer(out)
                                ?: throw AssertionError("Audio decoder output missing")
                            val view=data.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                            view.position(bufferInfo.offset)
                            view.limit(bufferInfo.offset+bufferInfo.size)
                            val bytesPerSample=if(floats)4 else 2
                            val frameSize=bytesPerSample*channels.coerceAtLeast(1)
                            while(view.remaining()>=frameSize && samples.size<48_000) {
                                val value=if(floats)view.float else view.short/32768f
                                samples.add(value)
                                view.position(view.position()+frameSize-bytesPerSample)
                            }
                        }
                        outputEnd=bufferInfo.flags and
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0
                        player.releaseOutputBuffer(out,false)
                    }
                }
            }
            return sampleRate to samples.toFloatArray()
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            extractor.release()
        }
    }

    private fun toneAmplitude(samples: FloatArray, sampleRate: Int, frequency: Double): Double {
        require(sampleRate>0)
        val count=minOf(samples.size,20_000)
        if (count<1) return 0.0
        var cosine=0.0
        var sine=0.0
        for (i in 0 until count) {
            val phase=2.0*Math.PI*frequency*i/sampleRate
            cosine+=samples[i]*cos(phase)
            sine+=samples[i]*sin(phase)
        }
        return 2.0*sqrt(cosine*cosine+sine*sine)/count
    }
}
