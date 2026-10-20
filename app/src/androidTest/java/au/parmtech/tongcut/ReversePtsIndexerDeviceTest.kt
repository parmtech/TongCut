package au.parmtech.tongcut

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CancellationException

/** Exercises MediaExtractor timestamp indexing against synthetic H.264 MP4 input. */
@RunWith(AndroidJUnit4::class)
class ReversePtsIndexerDeviceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun videoFixture(): Uri {
        val file = File(context.cacheDir, "reverse-pts-indexer-fixture.mp4")
        InstrumentationRegistry.getInstrumentation().context.assets
            .open("main_blue.mp4").use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
        assertTrue("Synthetic MP4 fixture missing", file.length() > 500L)
        return Uri.fromFile(file)
    }

    @Test fun indexesExtractorPresentationTimesAndHonorsHalfOpenTrim() {
        val uri = videoFixture()
        val full = ReversePtsIndexer.index(context, uri, 0L, 3_000_000L)
        assertNotNull(full)
        assertEquals(90, full!!.frameCount)
        assertEquals(0L, full.sourceTimeUs(full.frameCount - 1))
        assertTrue(full.sourceTimeUs(0) > full.sourceTimeUs(full.frameCount - 1))

        val trimmed = ReversePtsIndexer.index(context, uri, 500_000L, 1_000_000L)
        assertNotNull(trimmed)
        assertEquals(15, trimmed!!.frameCount)
        assertTrue(trimmed.sourceTimeUs(0) < 1_000_000L)
        assertTrue(trimmed.sourceTimeUs(trimmed.frameCount - 1) >= 500_000L)
    }

    @Test(expected = CancellationException::class)
    fun cancelsBeforeReadingFirstSample() {
        ReversePtsIndexer.index(context, videoFixture(), 0L, 3_000_000L) { true }
    }
}

