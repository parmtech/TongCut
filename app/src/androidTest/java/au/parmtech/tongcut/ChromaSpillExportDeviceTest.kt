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

/** Validates P3 despill on actually encoded H.264 output, not only pixel math. */
@RunWith(AndroidJUnit4::class)
class ChromaSpillExportDeviceTest {
    private val app: Context get() = ApplicationProvider.getApplicationContext()

    private fun fixture(fileName: String): Uri {
        val file = File(app.cacheDir, "spill-" + fileName)
        InstrumentationRegistry.getInstrumentation().context.assets
            .open(fileName).use { src -> file.outputStream().use { src.copyTo(it) } }
        assertTrue("No source fixture " + fileName, file.length() > 500L)
        return Uri.fromFile(file)
    }

    private data class Samples(val foreground: Int, val background: Int)

    private fun render(spill: Float): Samples {
        val blue = fixture("main_blue.mp4")
        val keyed = fixture("overlay_spill.mp4")
        val graph = LayerTimeline(
            tracks = listOf(TimelineTrack("pip", "PiP green screen", LayerKind.VIDEO, 0)),
            layers = listOf(
                TimelineLayer("spill", "pip", LayerKind.VIDEO,
                    startUs = 500_000L, durationUs = 1_500_000L,
                    sourceUri = keyed.toString(), sourceDurationUs = 3_000_000L,
                    photoEffects = PhotoEffects(chroma = PhotoChroma(
                        tolerance = 0.16f, feather = 0.12f,
                        spillSuppression = spill
                    ))
                )
            )
        )
        val latch = CountDownLatch(1)
        var result: Uri? = null
        var failure: String? = null
        Handler(Looper.getMainLooper()).post {
            VideoExporter(app).start(
                clips = listOf(Clip(blue, "Blue base", 3_000L)),
                options = ExportOptions(shortSidePixels = 720, mute = true),
                overlayLayers = graph, onProgress = {},
                onSuccess = { result = it; latch.countDown() },
                onError = { failure = it; latch.countDown() }
            )
        }
        assertTrue("No video export completion",
            latch.await(240L, TimeUnit.SECONDS))
        assertNull("Keyed export failed: $failure", failure)
        val uri = result ?: throw AssertionError("Missing output")
        try {
            return MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(app, uri)
                val frame = retriever.getFrameAtTime(
                    1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST
                ) ?: throw AssertionError("Missing chroma output frame")
                try {
                    fun colour(x: Float, y: Float): Int =
                        frame.getPixel(
                            (frame.width * x).toInt(),
                            (frame.height * y).toInt()
                        )
                    Samples(
                        foreground = colour(0.79f, 0.76f),
                        background = colour(0.65f, 0.65f)
                    )
                } finally {
                    frame.recycle()
                }
            }
        } finally {
            app.contentResolver.delete(uri, null, null)
        }
    }

    @Test fun exportedDespillRemovesGreenContaminationButKeepsSourceRedAndBlueBase() {
        val plain = render(0f)
        val cleaned = render(1f)
        val plainRed = Color.red(plain.foreground)
        val cleanRed = Color.red(cleaned.foreground)
        val plainGreen = Color.green(plain.foreground)
        val cleanGreen = Color.green(cleaned.foreground)
        assertTrue("Contaminated test object must be green dominant: " +
            Integer.toHexString(plain.foreground),
            plainGreen > plainRed + 25)
        assertTrue("Despill did not reduce exported green: $plainGreen -> $cleanGreen",
            cleanGreen < plainGreen - 30)
        assertTrue("Despill changed actual red subject colour: $plainRed -> $cleanRed",
            kotlin.math.abs(cleanRed - plainRed) < 35)
        assertTrue("Blue base vanished beneath transparent keyed background",
            Color.blue(cleaned.background) >
                Color.green(cleaned.background) + 70 &&
                Color.blue(cleaned.background) >
                Color.red(cleaned.background) + 70)
    }
}
