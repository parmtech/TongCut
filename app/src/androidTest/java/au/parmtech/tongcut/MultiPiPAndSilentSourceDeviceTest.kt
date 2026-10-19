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
 * Acceptance coverage missing from the initial PiP tests:
 * two live VIDEO decoders at once and a silent source's audio-track behavior.
 * All media is reproducible and generated in CI; never use an owner's content.
 */
@RunWith(AndroidJUnit4::class)
class MultiPiPAndSilentSourceDeviceTest {
    private val app: Context get() = ApplicationProvider.getApplicationContext()

    private fun synthetic(name: String): Uri {
        val file = File(app.cacheDir, "multi-accept-$name")
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { source ->
            file.outputStream().use { source.copyTo(it) }
        }
        assertTrue("Missing fixture: $name", file.length() > 500)
        return Uri.fromFile(file)
    }

    private fun render(
        base: Uri,
        timeline: LayerTimeline,
        muteOriginal: Boolean = true
    ): Uri {
        val done = CountDownLatch(1)
        var output: Uri? = null
        var failure: String? = null
        Handler(Looper.getMainLooper()).post {
            VideoExporter(app).start(
                clips = listOf(Clip(base, "Synthetic blue", 3_000L)),
                options = ExportOptions(shortSidePixels = 720, mute = muteOriginal),
                overlayLayers = timeline,
                onProgress = {},
                onSuccess = { output = it; done.countDown() },
                onError = { failure = it; done.countDown() }
            )
        }
        assertTrue("Export did not complete", done.await(240L, TimeUnit.SECONDS))
        assertNull("Export failed: $failure", failure)
        return output ?: throw AssertionError("Rendered output missing")
    }

    private fun hits(bitmap: Bitmap, leftHalf: Boolean, green: Boolean): Int {
        val startX = if (leftHalf) 0 else bitmap.width / 2
        val endX = if (leftHalf) bitmap.width / 2 else bitmap.width
        var result = 0
        for (y in bitmap.height / 2 until bitmap.height step 6) {
            for (x in startX until endX step 6) {
                val pixel = bitmap.getPixel(x, y)
                val red = Color.red(pixel)
                val blue = Color.blue(pixel)
                val grn = Color.green(pixel)
                if (green) {
                    if (grn > 100 && grn > red * 1.6 && grn > blue * 1.6) result++
                } else {
                    if (red > 120 && red > grn * 1.6 && red > blue * 1.6) result++
                }
            }
        }
        return result
    }

    @Test fun twoSimultaneousPictureInPictureMoviesRemainInSeparateSlots() {
        val blue = synthetic("main_blue.mp4")
        val red = synthetic("overlay_red.mp4")
        val green = synthetic("overlay_green.mp4")
        val timeline = LayerTimeline(
            tracks = listOf(
                TimelineTrack("right", "Right PiP", LayerKind.VIDEO, 0),
                TimelineTrack("left", "Left PiP", LayerKind.VIDEO, 1)
            ),
            layers = listOf(
                TimelineLayer(
                    "red", "right", LayerKind.VIDEO, 500_000L, 1_500_000L,
                    sourceUri = red.toString(), sourceDurationUs = 3_000_000L
                ),
                TimelineLayer(
                    "green", "left", LayerKind.VIDEO, 500_000L, 1_500_000L,
                    sourceUri = green.toString(), sourceDurationUs = 3_000_000L
                )
            )
        )
        val output = render(blue, timeline)
        try {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(app, output)
                val during = retriever.getFrameAtTime(
                    1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST
                ) ?: throw AssertionError("No simultaneous PiP frame")
                val after = retriever.getFrameAtTime(
                    2_700_000L, MediaMetadataRetriever.OPTION_CLOSEST
                ) ?: throw AssertionError("No post-PiP frame")
                try {
                    val redDuring = hits(during, leftHalf = false, green = false)
                    val greenDuring = hits(during, leftHalf = true, green = true)
                    val redAfter = hits(after, leftHalf = false, green = false)
                    val greenAfter = hits(after, leftHalf = true, green = true)
                    assertTrue(
                        "Right red PiP invisible: $redDuring vs $redAfter",
                        redDuring > redAfter + 75
                    )
                    assertTrue(
                        "Left green PiP invisible: $greenDuring vs $greenAfter",
                        greenDuring > greenAfter + 75
                    )
                    assertTrue("Red leaked after its end: $redAfter", redAfter < 25)
                    assertTrue("Green leaked after its end: $greenAfter", greenAfter < 25)
                } finally {
                    during.recycle()
                    after.recycle()
                }
            }
        } finally {
            app.contentResolver.delete(output, null, null)
        }
    }

    @Test fun silentMainVideoDoesNotLoseImportedAudioWithSourceUnmuted() {
        val blue = synthetic("main_blue.mp4")  // video-only, no source audio
        val audio = synthetic("music_440.m4a")
        val timeline = LayerTimeline(
            tracks = listOf(TimelineTrack("audio", "Music", LayerKind.AUDIO, 0)),
            layers = listOf(
                TimelineLayer(
                    "music", "audio", LayerKind.AUDIO, 0L, 3_000_000L,
                    sourceUri = audio.toString(), sourceDurationUs = 3_000_000L
                )
            )
        )
        val output = render(blue, timeline, muteOriginal = false)
        try {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(app, output)
                assertEquals("yes", retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO
                ))
                val duration = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION
                )?.toLongOrNull() ?: 0
                assertTrue("Unexpected output duration: $duration", duration in 2_800L..3_250L)
            }
        } finally {
            app.contentResolver.delete(output, null, null)
        }
    }
}
