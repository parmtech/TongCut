package au.parmtech.tongcut

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
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
        val textureWindowPosition = AtomicReference<Pair<Int, Int>>()
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
                preview.repeatMode = Player.REPEAT_MODE_ONE
                preview.setVideoTextureView(texture)
                preview.setMediaItem(MediaItem.fromUri(Uri.fromFile(fixture)))
                preview.prepare()
                preview.play()
            }
            assertTrue("Never received keyed preview frame",
                shown.await(30L, TimeUnit.SECONDS))
            assertNull("GPU effect rejected preview: ${failure.get()}", failure.get())

            // First-frame callback may precede Android's asynchronous
            // TextureView compositor latch by multiple frames under emulator
            // GPU contention. Poll REAL hardware window pixels for <= 5 sec,
            // but still fail with the last observed colours if compositing
            // never becomes correct. This is not a colour-assertion bypass.
            var validFrames = 0
            var lastKey = 0
            var lastSubject = 0
            var copies = 0
            for (attempt in 0 until 20) {
                val copied = CountDownLatch(1)
                val pixelCopyResult = AtomicReference<Int>()
                scenario.onActivity { activity ->
                    val (_, texture) = requireNotNull(holder.get())
                    assertTrue("TextureView was detached", texture.isAvailable)
                    val point = IntArray(2)
                    texture.getLocationInWindow(point)
                    textureWindowPosition.set(point[0] to point[1])
                    val root = activity.window.decorView
                    val snapshot = Bitmap.createBitmap(
                        root.width, root.height, Bitmap.Config.ARGB_8888
                    )
                    screenshot.set(snapshot)
                    PixelCopy.request(
                        activity.window, snapshot,
                        { result ->
                            pixelCopyResult.set(result)
                            copied.countDown()
                        },
                        Handler(Looper.getMainLooper())
                    )
                }
                assertTrue("PixelCopy from app window timed out",
                    copied.await(8L, TimeUnit.SECONDS))
                assertEquals("PixelCopy could not read hardware video",
                    PixelCopy.SUCCESS, pixelCopyResult.get())
                val snap = requireNotNull(screenshot.getAndSet(null))
                try {
                    val (_, texture) = requireNotNull(holder.get())
                    val (tx, ty) = requireNotNull(textureWindowPosition.get())
                    val width = texture.width
                    val height = texture.height
                    assertTrue("Unexpected preview dimensions",
                        width >= 320 && height >= 180)
                    lastKey = snap.getPixel(
                        tx + (width * 0.12f).toInt(),
                        ty + (height * 0.15f).toInt()
                    )
                    lastSubject = snap.getPixel(
                        tx + (width * 0.50f).toInt(),
                        ty + (height * 0.50f).toInt()
                    )
                    copies++
                    val blueUnderKey =
                        Color.blue(lastKey) > Color.green(lastKey) + 75 &&
                            Color.blue(lastKey) > Color.red(lastKey) + 75
                    val redStillVisible =
                        Color.red(lastSubject) > Color.green(lastSubject) + 75 &&
                            Color.red(lastSubject) > Color.blue(lastSubject) + 75
                    validFrames = if (blueUnderKey && redStillVisible) validFrames + 1 else 0
                } finally {
                    snap.recycle()
                }
                if (validFrames >= 2) break // require consecutive composited frames
                Thread.sleep(150L)
            }
            assertTrue(
                "Preview failed to show stable blue-through-green + red subject " +
                    "after $copies actual PixelCopy samples; last key=" +
                    Integer.toHexString(lastKey) + " subject=" +
                    Integer.toHexString(lastSubject),
                validFrames >= 2
            )
        } finally {
            scenario.onActivity { player.getAndSet(null)?.release() }
            scenario.close()
        }
    }
}
