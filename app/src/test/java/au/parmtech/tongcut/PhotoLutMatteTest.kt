package au.parmtech.tongcut

import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoLutMatteTest {
    @Test fun imageMatteAppliesEnabledLutAfterGradeAndPreservesSourceAlpha() {
        val source = 0x7f804020
        val output = PhotoMatte.applyAlpha(
            source, 0, 0, 1, 1,
            PhotoEffects(applyColorLut = true),
            channelSwapProfile()
        )

        assertEquals(0x7f204080, output)
    }

    @Test fun absentOrDisabledLutRemainsNeutralForLegacyEffects() {
        val source = 0x7f804020
        assertEquals(
            source,
            PhotoMatte.applyAlpha(source, 0, 0, 1, 1, PhotoEffects(), channelSwapProfile())
        )
        assertEquals(
            source,
            PhotoMatte.applyAlpha(
                source, 0, 0, 1, 1, PhotoEffects(applyColorLut = true), null
            )
        )
    }

    @Test fun layerLutStrengthIsBoundedAndDefaultsToFullProfileStrength() {
        assertEquals(1f, PhotoEffects().colorLutStrength, 0f)
        try {
            PhotoEffects(colorLutStrength = Float.NaN)
            throw AssertionError("Expected non-finite layer strength to be rejected")
        } catch (_: IllegalArgumentException) {
            // The effect model is bounded before it reaches bitmap processing.
        }
    }

    private fun channelSwapProfile(): ColorLutProfile {
        val size = 2
        val entries = buildList(size * size * size) {
            for (blue in 0 until size) for (green in 0 until size) for (red in 0 until size) {
                add((0xff shl 24) or (blue * 255 shl 16) or
                    (green * 255 shl 8) or (red * 255))
            }
        }
        return ColorLutProfile("Test channel swap", size, entries)
    }
}
