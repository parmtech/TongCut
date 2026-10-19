package au.parmtech.tongcut

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorGradeTest {
    @Test fun defaultGradePreservesAllArgbBits() {
        val pixel = 0x7F36A8E1
        assertEquals(pixel, ColorGrade().apply(pixel))
    }

    @Test fun exposureRaisesMidtonesAndClipsHighlights() {
        val pixel = 0xFF4080C0.toInt()
        val adjusted = ColorGrade(exposure = 1f).apply(pixel)
        assertEquals(128, (adjusted ushr 16) and 255)
        assertEquals(255, (adjusted ushr 8) and 255)
        assertEquals(255, adjusted and 255)
    }

    @Test fun zeroSaturationProducesNeutralChannelsAndKeepsAlpha() {
        val adjusted = ColorGrade(saturation = 0f).apply(0x7FFF0000)
        val red = (adjusted ushr 16) and 255
        val green = (adjusted ushr 8) and 255
        val blue = adjusted and 255
        assertTrue(kotlin.math.abs(red - green) <= 1)
        assertTrue(kotlin.math.abs(green - blue) <= 1)
        assertEquals(0x7F, (adjusted ushr 24) and 255)
    }

    @Test fun warmthMovesRedAndBlueInOppositeDirections() {
        val source = 0xFF808080.toInt()
        val warm = ColorGrade(temperature = 1f).apply(source)
        val cool = ColorGrade(temperature = -1f).apply(source)
        assertTrue(((warm ushr 16) and 255) > ((cool ushr 16) and 255))
        assertTrue((warm and 255) < (cool and 255))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnboundedExposure() { ColorGrade(exposure = 2.01f) }

    @Test fun gradeCodecRoundTripsAndDefaultsLegacyProjects() {
        val grade = ColorGrade(exposure = 0.75f, contrast = 1.2f,
            saturation = 0.8f, temperature = -0.3f)
        assertEquals(grade, ColorGradeCodec.decode(ColorGradeCodec.encode(grade)))
        assertEquals(ColorGrade(), ColorGradeCodec.decode(null))
    }

    @Test fun gradeCodecRejectsMalformedRangesToNeutralDefaults() {
        val malformed = JSONObject().put("exposure", 99.0).put("contrast", 1.0)
        assertEquals(ColorGrade(), ColorGradeCodec.decode(malformed))
    }
}

