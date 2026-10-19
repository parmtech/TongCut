package au.parmtech.tongcut

import android.content.Context
import android.graphics.Color
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** GPU alpha key of actual VIDEO frames composited over blue background. */
@RunWith(AndroidJUnit4::class)
class VideoMatteExportDeviceTest {
    @Test fun greenVideoBackgroundBecomesTransparentAndRedObjectSurvives() {
        val app=ApplicationProvider.getApplicationContext<Context>()
        fun fixture(name: String): Uri {
            val f=File(app.cacheDir, "video-key-$name")
            InstrumentationRegistry.getInstrumentation().context.assets.open(name).use {
                input -> f.outputStream().use { input.copyTo(it) }
            }
            return Uri.fromFile(f)
        }
        val blue=fixture("main_blue.mp4")
        val greenWithRed=fixture("overlay_chroma.mp4")
        val input=LayerTimeline(
            tracks=listOf(TimelineTrack("pip","Video matte",LayerKind.VIDEO,0)),
            layers=listOf(TimelineLayer(
                "key","pip",LayerKind.VIDEO,500_000L,1_500_000L,
                sourceUri=greenWithRed.toString(),
                sourceDurationUs=3_000_000L,
                photoEffects=PhotoEffects(chroma=PhotoChroma(
                    keyArgb=0xFF00FF00.toInt(),tolerance=0.16f,feather=0.12f
                ))
            ))
        )
        val done=CountDownLatch(1)
        var saved:Uri?=null
        var err:String?=null
        Handler(Looper.getMainLooper()).post {
            VideoExporter(app).start(
                clips=listOf(Clip(blue,"Blue",3_000L)),
                options=ExportOptions(shortSidePixels=720,mute=true),
                overlayLayers=input,
                onProgress={},
                onSuccess={saved=it;done.countDown()},
                onError={err=it;done.countDown()}
            )
        }
        assertTrue("GPU video matte export timeout",done.await(240,TimeUnit.SECONDS))
        assertNull("GPU video matte export failed: $err",err)
        val uri=saved ?: throw AssertionError("Missing video matte MP4")
        try{
            MediaMetadataRetriever().use { ret ->
                ret.setDataSource(app,uri)
                val frame=ret.getFrameAtTime(1_000_000L,
                    MediaMetadataRetriever.OPTION_CLOSEST)
                    ?: throw AssertionError("No keyed video frame")
                try{
                    // Right-bottom PiP: uniform keyed green SHOULD now reveal blue
                    // at corner; the central drawbox red survives compositing.
                    val corner=frame.getPixel(
                        (frame.width*0.65f).toInt(),
                        (frame.height*0.65f).toInt()
                    )
                    val center=frame.getPixel(
                        (frame.width*0.79f).toInt(),
                        (frame.height*0.76f).toInt()
                    )
                    assertTrue("Expected transparent green revealing blue: $corner",
                        Color.blue(corner)>Color.green(corner)+60)
                    assertTrue("Red object was removed with green: $center",
                        Color.red(center)>Color.green(center)+60)
                }finally{frame.recycle()}
            }
        }finally{app.contentResolver.delete(uri,null,null)}
    }
}
