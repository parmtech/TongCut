package au.parmtech.tongcut

import android.graphics.Color
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.TextOverlay
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class CaptionShadowOverlayTest {
    @Test fun shadowBitmapAddsClearPaddingWithoutClippingItsRenderedBounds() {
        val plain = captionOverlay(shadow = false)
        val shadowed = captionOverlay(shadow = true)
        val plainBitmap = plain.getBitmap(0L)
        val shadowBitmap = shadowed.getBitmap(0L)
        try {
            val padding = 18
            assertEquals(plainBitmap.width + padding * 2, shadowBitmap.width)
            assertEquals(plainBitmap.height + padding * 2, shadowBitmap.height)

            var plainLeft = plainBitmap.width
            var plainTop = plainBitmap.height
            var plainRight = -1
            var plainBottom = -1
            for (y in 0 until plainBitmap.height) {
                for (x in 0 until plainBitmap.width) {
                    if (Color.alpha(plainBitmap.getPixel(x, y)) > 8) {
                        plainLeft = minOf(plainLeft, x)
                        plainTop = minOf(plainTop, y)
                        plainRight = maxOf(plainRight, x)
                        plainBottom = maxOf(plainBottom, y)
                    }
                }
            }
            assertTrue("plain caption should render glyph pixels", plainRight >= plainLeft)

            var left = shadowBitmap.width
            var top = shadowBitmap.height
            var right = -1
            var bottom = -1
            var shadowOutsideGlyph = false
            for (y in 0 until shadowBitmap.height) {
                for (x in 0 until shadowBitmap.width) {
                    if (Color.alpha(shadowBitmap.getPixel(x, y)) > 8) {
                        left = minOf(left, x)
                        top = minOf(top, y)
                        right = maxOf(right, x)
                        bottom = maxOf(bottom, y)
                        val glyphX = x - padding
                        val glyphY = y - padding
                        val outsidePlainBitmap = glyphX !in 0 until plainBitmap.width ||
                            glyphY !in 0 until plainBitmap.height
                        val outsidePlainGlyph = glyphX !in plainLeft..plainRight ||
                            glyphY !in plainTop..plainBottom
                        if (outsidePlainBitmap || (outsidePlainGlyph &&
                                (glyphX !in 0 until plainBitmap.width ||
                                    glyphY !in 0 until plainBitmap.height ||
                                    Color.alpha(plainBitmap.getPixel(glyphX, glyphY)) <= 8))) {
                            shadowOutsideGlyph = true
                        }
                    }
                }
            }
            assertTrue("shadow must extend beyond the unshadowed glyph bounds", shadowOutsideGlyph)
            assertTrue("shadow render must leave pixels clear at the left edge", left > 0)
            assertTrue("shadow render must leave pixels clear at the right edge", right < shadowBitmap.width - 1)
            assertTrue("shadow render must leave pixels clear at the top edge", top > 0)
            assertTrue("shadow render must leave pixels clear at the bottom edge", bottom < shadowBitmap.height - 1)
        } finally {
            try {
                plain.release()
            } finally {
                shadowed.release()
            }
        }
    }

    private fun captionOverlay(shadow: Boolean): TextOverlay {
        val track = TimelineTrack("captions", "Captions", LayerKind.TEXT, 0)
        val layer = TimelineLayer(
            id = "caption", trackId = track.id, kind = LayerKind.TEXT,
            startUs = 0L, durationUs = 1_000_000L, text = "TongCut",
            captionStyle = CaptionStyle(shadow = shadow)
        )
        return TimedTextEffects.overlays(
            LayerTimeline(tracks = listOf(track), layers = listOf(layer))
        ).single() as TextOverlay
    }
}
