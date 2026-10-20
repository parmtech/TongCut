package au.parmtech.tongcut

import org.junit.Assert.assertEquals
import org.junit.Test

class ColorLutOverlayTransformTest {
    @Test fun identityCubePreservesOverlayPixelIncludingAlpha() {
        val profile = CubeLutParser.parse("identity.cube", identityCube())
        assertEquals(0x7f397fb2, ColorLutOverlayTransform.applyArgb(0x7f397fb2, profile))
    }

    @Test fun trilinearSamplingProducesMidpointForTwoByTwoCube() {
        val profile = CubeLutParser.parse("gray.cube", identityCube())
        assertEquals(0xff808080.toInt(),
            ColorLutOverlayTransform.applyArgb(0xff808080.toInt(), profile))
    }

    @Test fun strengthBlendsMappedRgbButNeverChangesOverlayAlpha() {
        val invert = CubeLutParser.parse("invert.cube", """
            LUT_3D_SIZE 2
            1 1 1
            0 1 1
            1 0 1
            0 0 1
            1 1 0
            0 1 0
            1 0 0
            0 0 0
        """.trimIndent()).copy(strength = 0.5f)
        val output = ColorLutOverlayTransform.applyArgb(0x22336699, invert)
        assertEquals(0x22, (output ushr 24) and 0xff)
        assertEquals(0x77, (output ushr 16) and 0xff)
        assertEquals(0x80, (output ushr 8) and 0xff)
        assertEquals(0x80, output and 0xff)
    }

    private fun identityCube() = """
        LUT_3D_SIZE 2
        0 0 0
        1 0 0
        0 1 0
        1 1 0
        0 0 1
        1 0 1
        0 1 1
        1 1 1
    """.trimIndent()
}
