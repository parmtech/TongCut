package au.parmtech.tongcut

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorGradeTest {
    @Test fun originalColorLooksAreUniqueBoundedAndKeepNeutralIdentity() {
        val presets = ColorGradePresets.all
        assertEquals(10, presets.size)
        assertEquals(presets.size, presets.map { it.name }.toSet().size)
        assertEquals(presets.size, presets.map { it.grade }.toSet().size)
        assertEquals(ColorGrade(), presets.single { it.name == "Neutral" }.grade)
        assertEquals(0x7F123456, presets.single { it.name == "Neutral" }
            .grade.apply(0x7F123456))
        assertTrue(presets.map { it.name }.containsAll(listOf(
            "Cobalt Film", "Rose Quartz", "Graphite", "Apricot Glow",
            "Moss Light", "Midnight Teal"
        )))
    }

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

    @Test fun tintMovesNeutralPixelsBetweenGreenAndMagenta() {
        val source = 0xFF808080.toInt()
        val neutral = ColorGrade().apply(source)
        val magenta = ColorGrade(tint = 1f).apply(source)
        val green = ColorGrade(tint = -1f).apply(source)
        assertTrue(((magenta ushr 16) and 255) > ((neutral ushr 16) and 255))
        assertTrue(((magenta ushr 8) and 255) < ((neutral ushr 8) and 255))
        assertTrue((magenta and 255) > (neutral and 255))
        assertTrue(((green ushr 8) and 255) > ((neutral ushr 8) and 255))
    }

    @Test fun hueRotationMovesRedTowardYellowAndLeavesNeutralGrayUntinted() {
        val yellow = ColorGrade(hueDegrees = 60f).apply(0xFFFF0000.toInt())
        assertTrue(((yellow ushr 16) and 255) > 240)
        assertTrue(((yellow ushr 8) and 255) > 240)
        assertTrue((yellow and 255) < 10)
        assertEquals(0xFF808080.toInt(),
            ColorGrade(hueDegrees = -150f).apply(0xFF808080.toInt()))
    }

    @Test fun hslLightnessMovesMidGrayToBlackOrWhiteAtItsBounds() {
        assertEquals(0xFF000000.toInt(),
            ColorGrade(hslLightness = -1f).apply(0xFF808080.toInt()))
        assertEquals(0xFFFFFFFF.toInt(),
            ColorGrade(hslLightness = 1f).apply(0xFF808080.toInt()))
    }

    @Test fun hslSaturationZeroNeutralizesColorWithoutChangingLightness() {
        val source = ColorGrade(hslSaturation = -1f).apply(0xFFBF8080.toInt())
        val red = (source ushr 16) and 255
        val green = (source ushr 8) and 255
        val blue = source and 255
        assertTrue(kotlin.math.abs(red - green) <= 1)
        assertTrue(kotlin.math.abs(green - blue) <= 1)
        assertTrue(green in 140..180)
    }

    @Test fun increasingHslSaturationLeavesAchromaticPixelsNeutral() {
        val gray = 0xFF808080.toInt()
        assertEquals(gray, ColorGrade(hslSaturation = 1f).apply(gray))
    }

    @Test fun selectiveHslChangesOnlyItsHueBandAndPersistsWithLegacyDefaults() {
        val bands = DEFAULT_HSL_BANDS.toMutableList().apply {
            this[0] = HslBandAdjustment(saturation = -1f)
        }
        val grade = ColorGrade(hslBands = bands)
        val red = grade.apply(0xFFFF0000.toInt())
        val green = grade.apply(0xFF00FF00.toInt())

        assertEquals(0xFF7F7F7F.toInt(), red)
        assertEquals(0xFF00FF00.toInt(), green)
        assertEquals(grade, ColorGradeCodec.decode(ColorGradeCodec.encode(grade)))
        assertEquals(DEFAULT_HSL_BANDS, ColorGradeCodec.decode(JSONObject()).hslBands)
    }

    @Test fun toneCurvesInterpolatePersistAndProduceGpuLookupTable() {
        val curve = ToneCurve(listOf(
            CurvePoint(0f, 0f), CurvePoint(0.5f, 0.8f), CurvePoint(1f, 1f)
        ))
        assertEquals(0.4f, curve.map(0.25f), 0.0001f)
        val grade = ColorGrade(curves = ColorCurves(red = curve))
        val restored = ColorGradeCodec.decode(ColorGradeCodec.encode(grade))
        assertEquals(grade, restored)
        val legacy = ColorGradeCodec.decode(JSONObject())
        assertEquals(ColorCurves(), legacy.curves)
        val lut = grade.curves.rgbaLut()
        assertEquals(204, lut[128 * 4].toInt() and 255)
        assertEquals(128, lut[128 * 4 + 1].toInt() and 255)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnboundedExposure() { ColorGrade(exposure = 2.01f) }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnboundedTint() { ColorGrade(tint = -1.01f) }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnboundedHueRotation() { ColorGrade(hueDegrees = 180.01f) }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnboundedHslLightness() { ColorGrade(hslLightness = 1.01f) }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnboundedHslSaturation() { ColorGrade(hslSaturation = -1.01f) }

    @Test fun gradeCodecRoundTripsAndDefaultsLegacyProjects() {
        val grade = ColorGrade(exposure = 0.75f, contrast = 1.2f,
            saturation = 0.8f, temperature = -0.3f, tint = 0.45f,
            hueDegrees = 72f, hslSaturation = 0.25f, hslLightness = -0.35f)
        assertEquals(grade, ColorGradeCodec.decode(ColorGradeCodec.encode(grade)))
        assertEquals(ColorGrade(), ColorGradeCodec.decode(null))
    }

    @Test fun missingLegacyTintDefaultsToNeutralWithoutResettingOtherAdjustments() {
        val decoded = ColorGradeCodec.decode(JSONObject()
            .put("exposure", 0.5).put("temperature", -0.25))
        assertEquals(0.5f, decoded.exposure)
        assertEquals(-0.25f, decoded.temperature)
        assertEquals(0f, decoded.tint)
        assertEquals(0f, decoded.hueDegrees)
        assertEquals(0f, decoded.hslLightness)
        assertEquals(0f, decoded.hslSaturation)
        assertEquals(DEFAULT_HSL_BANDS, decoded.hslBands)
    }

    @Test fun gradeCodecRejectsMalformedRangesToNeutralDefaults() {
        val malformed = JSONObject().put("exposure", 99.0).put("contrast", 1.0)
        assertEquals(ColorGrade(), ColorGradeCodec.decode(malformed))
    }
}
