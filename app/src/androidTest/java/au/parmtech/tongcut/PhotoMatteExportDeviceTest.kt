package au.parmtech.tongcut

import android.content.Context
import android.graphics.Bitmap
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

/** Android/GL pixel acceptance: photo alpha matte must survive actual H264 encoding. */
@RunWith(AndroidJUnit4::class)
class PhotoMatteExportDeviceTest {
    @Test fun keyedRedPhotoAppearsWithoutOpaqueGreenRectangle() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        fun fixture(name: String): File {
            val destination = File(app.cacheDir, "matte-$name")
            InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { input ->
                destination.outputStream().use { input.copyTo(it) }
            }
            return destination
        }

        val base = fixture("main_blue.mp4")
        val photo = fixture("matte_green_red.png")
        val graph = LayerTimeline(
            tracks=listOf(TimelineTrack("photo", "Keyed photo", LayerKind.IMAGE, 0)),
            layers=listOf(TimelineLayer(
                id="red-square", trackId="photo", kind=LayerKind.IMAGE,
                startUs=500_000L, durationUs=1_500_000L,
                sourceUri=Uri.fromFile(photo).toString(),
                photoEffects=PhotoEffects(
                    mask=PhotoMask.CIRCLE,
                    feather=0.035f,
                    chroma=PhotoChroma(tolerance=0.18f,feather=0.12f)
                )
            ))
        )
        val done = CountDownLatch(1)
        var output: Uri? = null
        var error: String? = null
        Handler(Looper.getMainLooper()).post {
            VideoExporter(app).start(
                clips=listOf(Clip(Uri.fromFile(base),"Synthetic blue",3_000L)),
                options=ExportOptions(shortSidePixels=720,mute=true),
                overlayLayers=graph,
                onProgress={},
                onSuccess={output=it;done.countDown()},
                onError={error=it;done.countDown()}
            )
        }
        assertTrue("Photo export timeout",done.await(180,TimeUnit.SECONDS))
        assertNull("Photo compositor error: $error",error)
        val uri = output ?: throw AssertionError("Missing photo MP4")
        try {
            MediaMetadataRetriever().use { media ->
                media.setDataSource(app,uri)
                fun countColour(timeUs: Long): Pair<Int,Int> {
                    val frame = media.getFrameAtTime(
                        timeUs,MediaMetadataRetriever.OPTION_CLOSEST
                    ) ?: throw AssertionError("Missing MP4 frame at $timeUs")
                    var red = 0
                    var green = 0
                    for (y in 0 until frame.height step 4) {
                        for (x in 0 until frame.width step 4) {
                            val pixel = frame.getPixel(x,y)
                            if (Color.red(pixel) > 140 &&
                                Color.red(pixel) > Color.green(pixel) * 1.5 &&
                                Color.red(pixel) > Color.blue(pixel) * 1.5) red++
                            if (Color.green(pixel) > 140 &&
                                Color.green(pixel) > Color.red(pixel) * 1.5 &&
                                Color.green(pixel) > Color.blue(pixel) * 1.5) green++
                        }
                    }
                    frame.recycle()
                    return red to green
                }
                val before = countColour(250_000L)
                val during = countColour(1_000_000L)
                val after = countColour(2_500_000L)
                assertTrue("Masked red photo not visible: $before $during",
                    during.first > before.first + 30)
                assertTrue("Photo layer visible after its end: $after $before",
                    after.first <= before.first + 20)
                assertTrue("Green screen leaked to export: $before $during",
                    during.second <= before.second + 30)
            }
        } finally {
            app.contentResolver.delete(uri,null,null)
        }
    }
}
