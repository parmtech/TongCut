package au.parmtech.tongcut

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Canvas
import android.net.Uri
import android.view.Gravity
import android.view.TextureView
import android.widget.FrameLayout
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * P3 preview proof-of-pixels. Use the same shader and transparent TextureView
 * configured by VisualOverlayPreview (not a bitmap/mock calculation).
 * An unkeyed lime pixel must reveal the parent BLUE frame, while the RED
 * rectangle survives. The existing transformer test separately proves export.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VideoMattePreviewDeviceTest {
    @Test fun keyedVideoPreviewShowsBlueParentBehindGreenAndKeepsRedObject() {
        val app = ApplicationProvider.getApplicationContext<android.content.Context>()
        val fixture = File(app.cacheDir, "realtime-chroma-preview.mp4")
        InstrumentationRegistry.getInstrumentation().context.assets
            .open("overlay_chroma.mp4").use { input ->
                fixture.outputStream().use { output -> input.copyTo(output) }
            }
        assertTrue("Preview fixture missing", fixture.length() > 500L)

        val shown = CountDownLatch(1)
        val failure = AtomicReference<String?>()
        val player = AtomicReference<ExoPlayer?>()
        val holder = AtomicReference<Pair<FrameLayout, TextureView>?>()
        val screenshot = AtomicReference<Bitmap?>()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { activity ->
                val frame = FrameLayout(activity).apply {
                    setBackgroundColor(Color.BLUE)
                }
                val texture = TextureView(activity).apply {
                    isOpaque = false
                }
                frame.addView(texture, FrameLayout.LayoutParams(
                    640, 360, Gravity.CENTER
                ))
                activity.setContentView(frame)
                holder.set(frame to texture)

                val preview = ExoPlayer.Builder(activity).build()
                player.set(preview)
                preview.addListener(object : Player.Listener {
                    override fun onRenderedFirstFrame() { shown.countDown() }
                    override fun onPlayerError(error: PlaybackException) {
                        failure.set(error.message ?: "GPU video preview failed")
                        shown.countDown()
                    }
                })
                preview.setVideoEffects(listOf(
                    VideoMatteEffect(PhotoEffects(
                        chroma = PhotoChroma(
                            keyArgb = 0xFF00FF00.toInt(),
                            tolerance = 0.16f,
                            feather = 0.12f
                        )
                    ))
                ))
                preview.setVideoTextureView(texture)
                preview.setMediaItem(MediaItem.fromUri(Uri.fromFile(fixture)))
                preview.prepare()
                preview.play()
            }
            assertTrue("Never received keyed preview frame",
                shown.await(30L, TimeUnit.SECONDS))
            assertNull("GPU effect rejected preview: ${failure.get()}", failure.get())

            // Wait a few more rendered frames to avoid observing a surface
            // before its first transparent texture was latched by TextureView.
            Thread.sleep(350L)
            scenario.onActivity {
                val (frame, texture) = requireNotNull(holder.get())
                assertTrue("TextureView was detached", texture.isAvailable)
                val snapshot = Bitmap.createBitmap(
                    frame.width, frame.height, Bitmap.Config.ARGB_8888
                )
                frame.draw(Canvas(snapshot))
                screenshot.set(snapshot)
            }
            val snap = requireNotNull(screenshot.get())
            try {
                val (frame, texture) = requireNotNull(holder.get())
                // Coordinates expressed relative to the actual TextureView
                // rectangle inside the parent blue FrameLayout.
                val tx = texture.left
                val ty = texture.top
                val width = texture.width
                val height = texture.height
                assertTrue("Unexpected preview dimensions", width >= 320 && height >= 180)
                val key = snap.getPixel(
                    tx + (width * 0.12f).toInt(),
                    ty + (height * 0.15f).toInt()
                )
                val subject = snap.getPixel(
                    tx + (width * 0.50f).toInt(),
                    ty + (height * 0.50f).toInt()
                )
                assertTrue("Keyed area did not reveal blue base: $key",
                    Color.blue(key) > Color.green(key) + 75 &&
                        Color.blue(key) > Color.red(key) + 75)
                assertTrue("Red object missing from keyed preview: $subject",
                    Color.red(subject) > Color.green(subject) + 75 &&
                        Color.red(subject) > Color.blue(subject) + 75)
            } finally {
                snap.recycle()
            }
        } finally {
            scenario.onActivity { player.getAndSet(null)?.release() }
            scenario.close()
        }
    }
}
