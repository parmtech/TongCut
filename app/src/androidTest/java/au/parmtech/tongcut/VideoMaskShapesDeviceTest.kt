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

/**
 * Export acceptance for EACH mask geometry. A green/red chroma test alone
 * cannot establish CIRCLE/ROUNDED_RECT/WIPE correctness. All samples are from
 * decoded H.264 MP4 frames rather than JVM shader/mask calculations.
 */
@RunWith(AndroidJUnit4::class)
class VideoMaskShapesDeviceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun fixture(name: String): Uri {
        val file = File(context.cacheDir, "masked-video-shapes-" + name)
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { stream ->
            file.outputStream().use { stream.copyTo(it) }
        }
        assertTrue("Missing synthetic fixture: " + name, file.length() > 500L)
        return Uri.fromFile(file)
    }

    private fun renderedFrame(mask: PhotoMask): Bitmap {
        val blue = fixture("main_blue.mp4")
        val red = fixture("overlay_red.mp4")
        val graph = LayerTimeline(
            tracks = listOf(TimelineTrack("pip", "Video mask", LayerKind.VIDEO, 0)),
            layers = listOf(
                TimelineLayer(
                    id = "masked", trackId = "pip", kind = LayerKind.VIDEO,
                    startUs = 500_000L, durationUs = 1_500_000L,
                    sourceUri = red.toString(), sourceDurationUs = 3_000_000L,
                    photoEffects = PhotoEffects(mask = mask, feather = 0.025f)
                )
            )
        )
        val done = CountDownLatch(1)
        var result: Uri? = null
        var error: String? = null
        Handler(Looper.getMainLooper()).post {
            VideoExporter(context).start(
                clips = listOf(Clip(blue, "Blue", 3_000L)),
                options = ExportOptions(shortSidePixels = 720, mute = true),
                overlayLayers = graph,
                onProgress = {},
                onSuccess = { result = it; done.countDown() },
                onError = { error = it; done.countDown() }
            )
        }
        assertTrue("Mask export timed out for " + mask,
            done.await(240L, TimeUnit.SECONDS))
        assertNull("Failed to render " + mask + ": " + error, error)
        val media = result ?: throw AssertionError("No MP4 output for " + mask)
        try {
            return MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(context, media)
                retriever.getFrameAtTime(1_000_000L,
                    MediaMetadataRetriever.OPTION_CLOSEST)
                    ?: throw AssertionError("No output frame for " + mask)
            }
        } finally {
            context.contentResolver.delete(media, null, null)
        }
    }

    /** Anchor 0 is bottom right 42%-wide and fit to 16:9 video over 16:9 base. */
    private fun at(frame: Bitmap, normX: Float, normY: Float): Int =
        frame.getPixel(
            (frame.width * normX).toInt().coerceIn(0, frame.width - 1),
            (frame.height * normY).toInt().coerceIn(0, frame.height - 1)
        )

    private fun assertBlue(pixel: Int, detail: String) {
        assertTrue(detail + " should reveal blue, got " +
            Integer.toHexString(pixel),
            Color.blue(pixel) > Color.red(pixel) + 70 &&
            Color.blue(pixel) > Color.green(pixel) + 70)
    }

    private fun assertRed(pixel: Int, detail: String) {
        assertTrue(detail + " should preserve red, got " +
            Integer.toHexString(pixel),
            Color.red(pixel) > Color.blue(pixel) + 70 &&
            Color.red(pixel) > Color.green(pixel) + 70)
    }

    @Test fun circleMaskRetainsRedCenterButRevealsBlueAlongSides() {
        val frame = renderedFrame(PhotoMask.CIRCLE)
        try {
            assertRed(at(frame, 0.79f, 0.79f), "Circle centre")
            assertBlue(at(frame, 0.62f, 0.79f), "Circle left side")
            assertBlue(at(frame, 0.96f, 0.79f), "Circle right side")
        } finally {
            frame.recycle()
        }
    }

    @Test fun roundedRectangleMaskRevealsCornerButNotCentre() {
        val frame = renderedFrame(PhotoMask.ROUNDED_RECT)
        try {
            assertRed(at(frame, 0.79f, 0.79f), "Rounded centre")
            // PiP begins at approx (0.58, 0.58). The old (0.61,0.61)
            // point was INSIDE a 19%-radius rounded corner and was correctly
            // red. Sample 0.59: inside the PiP box, but outside the rounded
            // boundary by >5 source pixels for reliable H.264 acceptance.
            assertBlue(at(frame, 0.59f, 0.59f), "Rounded upper-left")
            assertRed(at(frame, 0.79f, 0.63f), "Rounded upper middle")
        } finally {
            frame.recycle()
        }
    }

    @Test fun linearWipeShowsOnlyLeftHalfOfVideoPiP() {
        val frame = renderedFrame(PhotoMask.LINEAR_WIPE)
        try {
            assertRed(at(frame, 0.64f, 0.79f), "Wipe left half")
            assertBlue(at(frame, 0.94f, 0.79f), "Wipe right half")
            assertBlue(at(frame, 0.79f, 0.31f), "Outside PiP untouched")
        } finally {
            frame.recycle()
        }
    }
}
