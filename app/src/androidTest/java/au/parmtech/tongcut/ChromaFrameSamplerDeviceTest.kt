package au.parmtech.tongcut

import android.content.Context
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Samples the RAW source video, not its keyed/exported appearance. */
@RunWith(AndroidJUnit4::class)
class ChromaFrameSamplerDeviceTest {
    @Test fun frozenSourceImageSamplesOriginalGreenAndRedPixels() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val fixture = File(app.cacheDir, "original-video-colour-picker.mp4")
        InstrumentationRegistry.getInstrumentation().context.assets
            .open("overlay_chroma.mp4").use { source ->
                fixture.outputStream().use { source.copyTo(it) }
            }
        val image = ChromaFrameSampler.frameAt(
            app, Uri.fromFile(fixture).toString(), 1_000_000L
        )
        try {
            assertTrue(image.width in 1..ChromaFrameSampler.MAX_EDGE_PX)
            assertTrue(image.height in 1..ChromaFrameSampler.MAX_EDGE_PX)
            val (greenX, greenY) = ChromaSampleMath.pixelAt(
                25f, 25f, 320, 180, image.width, image.height
            )
            val (redX, redY) = ChromaSampleMath.pixelAt(
                160f, 90f, 320, 180, image.width, image.height
            )
            val green = ChromaSampleMath.opaqueArgb(image.getPixel(greenX, greenY))
            val red = ChromaSampleMath.opaqueArgb(image.getPixel(redX, redY))
            assertTrue("Source background was not green: $green",
                Color.green(green) > Color.red(green) + 100 &&
                    Color.green(green) > Color.blue(green) + 100)
            assertTrue("Source subject was not red: $red",
                Color.red(red) > Color.green(red) + 100 &&
                    Color.red(red) > Color.blue(red) + 100)
            assertEquals(255, Color.alpha(green))
            // The selected pixel colour can be persisted as the chroma key.
            val preview = PhotoEffects(chroma = PhotoChroma(keyArgb=green))
            assertEquals(green, preview.chroma?.keyArgb)
        } finally {
            image.recycle()
        }
    }
}
