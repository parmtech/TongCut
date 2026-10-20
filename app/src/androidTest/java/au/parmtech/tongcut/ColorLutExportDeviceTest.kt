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

/** Checks that imported 3D LUT data changes decoded pixels of the encoded MP4. */
@RunWith(AndroidJUnit4::class)
class ColorLutExportDeviceTest {
    @Test fun constantRedCubeTransformsBlueSourcePixelsInExport() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val videoFile = File(app.cacheDir, "lut-main-blue.mp4")
        InstrumentationRegistry.getInstrumentation().context.assets.open("main_blue.mp4").use { input ->
            videoFile.outputStream().use { input.copyTo(it) }
        }
        assertTrue(videoFile.length() > 500L)
        val profile = ColorLutProfile(
            name = "Synthetic Red",
            size = 2,
            entriesArgb = List(8) { Color.rgb(255, 0, 0) }
        )
        val complete = CountDownLatch(1)
        var output: Uri? = null
        var failure: String? = null
        Handler(Looper.getMainLooper()).post {
            VideoExporter(app).start(
                clips = listOf(Clip(Uri.fromFile(videoFile), "Blue source", 3_000L)),
                options = ExportOptions(
                    shortSidePixels = 720,
                    mute = true,
                    colorLutProfile = profile
                ),
                onProgress = {},
                onSuccess = { output = it; complete.countDown() },
                onError = { failure = it; complete.countDown() }
            )
        }
        assertTrue("LUT export timed out", complete.await(240L, TimeUnit.SECONDS))
        assertNull("LUT export failed: $failure", failure)
        val uri = output ?: throw AssertionError("LUT MP4 was not created")
        try {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(app, uri)
                val bitmap = retriever.getFrameAtTime(
                    500_000L, MediaMetadataRetriever.OPTION_CLOSEST
                ) ?: throw AssertionError("LUT MP4 frame could not be decoded")
                try {
                    val center = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
                    assertTrue("Exported frame was not red after LUT: #${center.toUInt().toString(16)}",
                        Color.red(center) > 170 && Color.red(center) > Color.blue(center) * 1.7)
                } finally {
                    bitmap.recycle()
                }
            }
        } finally {
            app.contentResolver.delete(uri, null, null)
            videoFile.delete()
        }
    }
}
