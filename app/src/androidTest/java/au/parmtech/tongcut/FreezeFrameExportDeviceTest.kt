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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A real codec/GL regression: during the VIDEO lane's freeze hole, a saved
 * still-image frame MUST remain visibly red above the blue primary video.
 * Compilation and model-only tests cannot prove this outcome.
 */
@RunWith(AndroidJUnit4::class)
class FreezeFrameExportDeviceTest {
    @Test fun capturedFrameAppearsDuringFreezeAndVideoResumesInEncodedMp4() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        fun fixture(name: String): File {
            val destination = File(app.cacheDir, "freeze-$name")
            InstrumentationRegistry.getInstrumentation().context.assets.open(name)
                .use { input -> destination.outputStream().use { input.copyTo(it) } }
            assertTrue(destination.length() > 1000L)
            return destination
        }
        val main = fixture("main_blue.mp4")
        val inset = fixture("overlay_red.mp4")
        val original = EditorDraft(
            clips = listOf(DraftClip(Uri.fromFile(main).toString(), "Blue main",3_000))
        )
        val withVideo = LayerEdits.addVisual(
            original,0L,LayerKind.VIDEO,Uri.fromFile(inset).toString(),2_000L
        )
        val videoId = withVideo.layers.layers.single().id
        val snapshot = runBlocking {
            FreezeFrameCapture.capture(app,Uri.fromFile(inset),1_000_000L)
        }
        try {
            assertTrue(File(requireNotNull(snapshot.path)).exists())
            val frozen = LayerEdits.freezeVisualAt(
                withVideo,videoId,1_000L,1_000L,snapshot.toString()
            )
            val freezeImage = frozen.layers.layers.single { it.kind == LayerKind.IMAGE }
            assertEquals(1_000_000L,freezeImage.startUs)
            assertEquals(1_000_000L,freezeImage.durationUs)
            val complete = CountDownLatch(1)
            var video: Uri? = null
            var error: String? = null
            Handler(Looper.getMainLooper()).post {
                VideoExporter(app).start(
                    clips=listOf(Clip(Uri.fromFile(main),"Blue main",3_000L)),
                    options=ExportOptions(shortSidePixels=720,mute=true),
                    overlayLayers=frozen.layers,
                    onProgress={},
                    onSuccess={ video=it; complete.countDown() },
                    onError={ error=it; complete.countDown() }
                )
            }
            assertTrue("Freeze+PiP export timed out",complete.await(180,TimeUnit.SECONDS))
            assertNull("Freeze+PiP failed to render: $error",error)
            val uri=video ?: throw AssertionError("Missing freeze MP4")
            try {
                MediaMetadataRetriever().use { media ->
                    media.setDataSource(app,uri)
                    fun redPixels(at: Long): Int {
                        val frame=media.getFrameAtTime(
                            at,MediaMetadataRetriever.OPTION_CLOSEST
                        ) ?: throw AssertionError("Missing frame at $at")
                        var count=0
                        for (y in 0 until frame.height step 8) {
                            for (x in 0 until frame.width step 8) {
                                val pixel=frame.getPixel(x,y)
                                if (Color.red(pixel)>110 &&
                                    Color.red(pixel)>Color.green(pixel)*1.6 &&
                                    Color.red(pixel)>Color.blue(pixel)*1.6) count++
                            }
                        }
                        frame.recycle()
                        return count
                    }
                    val before=redPixels(500_000L)
                    val frozenMiddle=redPixels(1_500_000L)
                    val resumed=redPixels(2_500_000L)
                    assertTrue(
                        "PiP red missing before freeze: $before", before>100
                    )
                    assertTrue(
                        "Captured still did not cover PiP gap: $frozenMiddle",
                        frozenMiddle>100
                    )
                    assertTrue(
                        "Video did not resume after freeze: $resumed",resumed>100
                    )
                }
            } finally {
                app.contentResolver.delete(uri,null,null)
            }
        } finally {
            FreezeFrameCapture.discard(app,snapshot)
        }
    }
}
