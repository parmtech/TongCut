package au.parmtech.tongcut

import android.content.Context
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
import kotlin.math.abs

/**
 * One compressed test-tone track at 2x speed: actual MP4 has the short
 * audible segment, then silence, while 3s primary video duration remains.
 */
@RunWith(AndroidJUnit4::class)
class AudioSpeedExportDeviceTest {
    @Test fun spedUpAudioFinishesEarlyButSilentTailPreservesMainLength() {
        val app=ApplicationProvider.getApplicationContext<Context>()
        fun file(name: String): Uri {
            val output=File(app.cacheDir,"speed-audio-$name")
            InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { stream ->
                output.outputStream().use { stream.copyTo(it) }
            }
            return Uri.fromFile(output)
        }
        val blue=file("main_blue.mp4")
        val tone=file("music_440.m4a")
        val original=LayerTimeline(
            tracks=listOf(TimelineTrack("a","Audio speed",LayerKind.AUDIO,0)),
            layers=listOf(TimelineLayer(
                "tone","a",LayerKind.AUDIO,0L,3_000_000L,
                sourceUri=tone.toString(), sourceDurationUs=3_000_000L
            ))
        )
        val project=EditorDraft(clips=listOf(
            DraftClip(blue.toString(),"Base",durationMs=3_000L)
        ),layers=original)
        val advanced=AudioSpeedEdits.setConstant(project,"tone",2f)
        assertEquals(1_500_000L,advanced.layers.layers.single().durationUs)
        assertEquals(3_000_000L,advanced.layers.layers.single().consumedSourceUs)
        val done=CountDownLatch(1)
        var success:Uri?=null
        var error:String?=null
        Handler(Looper.getMainLooper()).post {
            VideoExporter(app).start(
                clips=listOf(Clip(blue,"Blue base",3_000L)),
                options=ExportOptions(shortSidePixels=720,mute=true),
                overlayLayers=advanced.layers,
                onProgress={},
                onSuccess={success=it;done.countDown()},
                onError={error=it;done.countDown()}
            )
        }
        assertTrue("Speed audio render timeout",done.await(240L,TimeUnit.SECONDS))
        assertNull("Speed audio render error: $error",error)
        val output=success ?: throw AssertionError("No MP4")
        try {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(app,output)
                assertEquals("yes",retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))
                val duration=retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION
                )?.toLongOrNull() ?: 0L
                assertTrue("Speed audio changed video duration: $duration",
                    duration in 2_800L..3_250L)
            }
            val (audible,silent)=measureEnvelope(app,output)
            assertTrue("Sped-up tone was muted: $audible",audible>0.003)
            assertTrue("Audio unexpectedly leaked after 1.5s: $silent vs $audible",
                silent<0.0025 && silent<audible/3)
        }finally{
            app.contentResolver.delete(output,null,null)
        }
    }

    private fun measureEnvelope(context: Context, uri: Uri): Pair<Double,Double> {
        val extractor=MediaExtractor()
        var decoder:MediaCodec?=null
        try{
            extractor.setDataSource(context,uri,null)
            val idx=(0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/")==true
            } ?: throw AssertionError("MP4 missing audio stream")
            extractor.selectTrack(idx)
            val format=extractor.getTrackFormat(idx)
            val codec=MediaCodec.createDecoderByType(
                requireNotNull(format.getString(MediaFormat.KEY_MIME))
            )
            decoder=codec
            codec.configure(format,null,null,0)
            codec.start()
            var rate=format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels=format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var isFloat=false
            var inputEnd=false
            var outputEnd=false
            var signal=0.0
            var signalN=0
            var tail=0.0
            var tailN=0
            var loops=0
            val info=MediaCodec.BufferInfo()
            while(!outputEnd && loops++<2500) {
                if(!inputEnd) {
                    val input=codec.dequeueInputBuffer(10_000)
                    if(input>=0) {
                        val buffer=codec.getInputBuffer(input)
                            ?: throw AssertionError("Decoder input buffer absent")
                        buffer.clear()
                        val length=extractor.readSampleData(buffer,0)
                        if(length<0){
                            codec.queueInputBuffer(input,0,0,0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnd=true
                        }else{
                            codec.queueInputBuffer(input,0,length,
                                extractor.sampleTime.coerceAtLeast(0L),0)
                            extractor.advance()
                        }
                    }
                }
                val next=codec.dequeueOutputBuffer(info,10_000)
                if(next==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){
                    val converted=codec.outputFormat
                    rate=converted.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels=converted.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    isFloat=converted.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        converted.getInteger(MediaFormat.KEY_PCM_ENCODING)==
                            AudioFormat.ENCODING_PCM_FLOAT
                }else if(next>=0){
                    val samples=codec.getOutputBuffer(next)
                        ?: throw AssertionError("Missing audio output bytes")
                    val data=samples.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                    data.position(info.offset)
                    data.limit(info.offset+info.size)
                    val sampleBytes=if(isFloat)4 else 2
                    val frameBytes=sampleBytes*channels.coerceAtLeast(1)
                    var frameNumber=0
                    while(data.remaining()>=frameBytes) {
                        val valAt=if(isFloat)data.float else data.short/32768f
                        data.position(data.position()+frameBytes-sampleBytes)
                        val time=info.presentationTimeUs+frameNumber*1_000_000L/
                            rate.coerceAtLeast(1)
                        if(time in 250_000L..1_250_000L){
                            signal+=abs(valAt.toDouble());signalN++
                        }else if(time in 2_000_000L..2_800_000L) {
                            tail+=abs(valAt.toDouble());tailN++
                        }
                        frameNumber++
                    }
                    outputEnd=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0
                    codec.releaseOutputBuffer(next,false)
                }
            }
            assertTrue("Not enough first-tone samples",signalN>4_000)
            assertTrue("Not enough silent-tail samples",tailN>4_000)
            return signal/signalN to tail/tailN
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            extractor.release()
        }
    }
}
