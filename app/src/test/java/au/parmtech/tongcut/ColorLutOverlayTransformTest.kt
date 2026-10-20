package au.parmtech.tongcut

import org.junit.Assert.assertEquals
import org.junit.Test

class ColorLutOverlayTransformTest {
    @Test fun trilinearSamplingAppliesChannelPermutationAndPreservesAlpha() {
        val input = 0x7f804020
        val mapped = ColorLutOverlayTransform.applyArgb(input, channelSwapLut())

        assertEquals(0x7f204080, mapped)
    }

    @Test fun strengthBlendsAgainstOriginalPixel() {
        val profile = channelSwapLut().copy(strength = 0.5f)

        assertEquals(0x7f504050, ColorLutOverlayTransform.applyArgb(0x7f804020, profile))
    }

    @Test fun zeroStrengthIsNeutral() {
        val input = 0x1280c040
        val profile = channelSwapLut().copy(strength = 0f)

        assertEquals(input, ColorLutOverlayTransform.applyArgb(input, profile))
    }

    private fun channelSwapLut(): ColorLutProfile {
        val size = 2
        val entries = buildList(size * size * size) {
            for (blue in 0 until size) for (green in 0 until size) for (red in 0 until size) {
                // A deliberately simple affine transform makes interpolation expectations exact.
                add((0xff shl 24) or (blue * 255 shl 16) or
                    (green * 255 shl 8) or (red * 255))
            }
        }
        return ColorLutProfile("Test channel swap", size, entries)
    }
}
