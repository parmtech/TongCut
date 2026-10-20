package au.parmtech.tongcut

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualFaceRetouchTest {
    @Test fun defaultsAreNeutralAndBoundedControlsActivateTheEffect() {
        val defaults = ManualFaceRetouch()
        assertFalse(defaults.isActive)
        assertTrue(ManualFaceRetouch(faceWidth = 0.2f).isActive)
        assertTrue(ManualFaceRetouch(skinSoftening = 0.4f).isActive)
    }

    @Test fun settingsRoundTripAndMissingOrInvalidValuesUseNeutralDefaults() {
        val settings = ManualFaceRetouch(
            centerX = 0.37f,
            centerY = 0.61f,
            radiusX = 0.31f,
            radiusY = 0.27f,
            faceWidth = 0.18f,
            jawWidth = -0.12f,
            noseWidth = 0.08f,
            eyeScale = 0.22f,
            skinSoftening = 0.6f
        )
        assertEquals(settings, ManualFaceRetouchCodec.decode(
            ManualFaceRetouchCodec.encode(settings)
        ))
        assertEquals(ManualFaceRetouch(), ManualFaceRetouchCodec.decode(null))
        val malformed = JSONObject().put("faceWidth", 1.0)
        assertEquals(ManualFaceRetouch(), ManualFaceRetouchCodec.decode(malformed))
        val offFrame = JSONObject().put("centerX", 0.1).put("radiusX", 0.4)
        assertEquals(0.402f, ManualFaceRetouchCodec.decode(offFrame).centerX, 0.00001f)
    }

    @Test fun contourBoundsMatchFoldFreeShaderProfile() {
        assertTrue(ManualFaceRetouch(faceWidth = 0.25f, jawWidth = 0.25f).isActive)
        assertTrue(ManualFaceRetouch(noseWidth = -0.08f).isActive)
        assertTrue(runCatching { ManualFaceRetouch(jawWidth = 0.251f) }.isFailure)
        assertTrue(runCatching { ManualFaceRetouch(noseWidth = 0.081f) }.isFailure)
    }

    @Test fun presetsPreserveTheAuthoredRegionAndUseBoundedLocalValues() {
        val region = ManualFaceRetouch(
            centerX = 0.39f, centerY = 0.56f, radiusX = 0.19f, radiusY = 0.27f,
            faceWidth = 0.20f, jawWidth = 0.10f, noseWidth = 0.03f,
            eyeScale = 0.15f, skinSoftening = 0.40f
        )
        val neutral = ManualFaceRetouchPreset.NEUTRAL.applyTo(region)
        assertEquals(region.centerX, neutral.centerX, 0f)
        assertEquals(region.centerY, neutral.centerY, 0f)
        assertEquals(region.radiusX, neutral.radiusX, 0f)
        assertEquals(region.radiusY, neutral.radiusY, 0f)
        assertEquals(ManualFaceRetouch(), neutral.copy(centerX = 0.5f, centerY = 0.42f,
            radiusX = 0.23f, radiusY = 0.30f))

        val expectedValues = mapOf(
            ManualFaceRetouchPreset.NEUTRAL to listOf(0f, 0f, 0f, 0f, 0f),
            ManualFaceRetouchPreset.NATURAL to listOf(0.05f, 0.035f, 0f, 0.04f, 0.12f),
            ManualFaceRetouchPreset.SCULPT to listOf(0.13f, 0.10f, 0.025f, 0.08f, 0.10f),
            ManualFaceRetouchPreset.SOFT_FOCUS to listOf(0f, 0f, 0f, 0f, 0.48f),
            ManualFaceRetouchPreset.OPEN_EYES to listOf(0f, 0f, 0f, 0.16f, 0.08f),
            ManualFaceRetouchPreset.FULLER to listOf(-0.12f, -0.05f, 0f, 0.03f, 0.08f)
        )
        ManualFaceRetouchPreset.entries.forEach { preset ->
            val adjusted = preset.applyTo(region)
            val expected = expectedValues.getValue(preset)
            assertEquals(expected[0], adjusted.faceWidth, 0f)
            assertEquals(expected[1], adjusted.jawWidth, 0f)
            assertEquals(expected[2], adjusted.noseWidth, 0f)
            assertEquals(expected[3], adjusted.eyeScale, 0f)
            assertEquals(expected[4], adjusted.skinSoftening, 0f)
            assertEquals(region.centerX, adjusted.centerX, 0f)
            assertEquals(region.centerY, adjusted.centerY, 0f)
            assertEquals(region.radiusX, adjusted.radiusX, 0f)
            assertEquals(region.radiusY, adjusted.radiusY, 0f)
            assertTrue(adjusted.faceWidth in -0.25f..0.25f)
            assertTrue(adjusted.jawWidth in -0.25f..0.25f)
            assertTrue(adjusted.noseWidth in -0.08f..0.08f)
            assertTrue(adjusted.eyeScale in 0f..0.35f)
            assertTrue(adjusted.skinSoftening in 0f..1f)
        }
    }

    @Test fun reversePreviewMapIsNeutralOrBoundedInsideTheFrame() {
        val uv = FloatArray(2)
        ManualFaceRetouchMath.mapSourceUv(
            ManualFaceRetouch(), 0.23f, 0.71f, 640, 360, uv
        )
        assertEquals(0.23f, uv[0], 0.00001f)
        assertEquals(0.71f, uv[1], 0.00001f)

        val maximum = ManualFaceRetouch(
            faceWidth = 0.25f, jawWidth = 0.25f, noseWidth = 0.08f, eyeScale = 0.35f
        )
        for (y in 1..99 step 7) for (x in 1..99 step 7) {
            ManualFaceRetouchMath.mapSourceUv(
                maximum, x / 100f, y / 100f, 640, 360, uv
            )
            assertTrue(uv[0] in 0f..1f)
            assertTrue(uv[1] in 0f..1f)
        }
    }

    @Test fun reversePreviewRotationAndMirrorMapIntoThePostTransformFrame() {
        val uv = FloatArray(2)
        val rawU = 0.2f
        val rawV = 0.3f
        val expected = listOf(
            listOf(0.2f, 0.3f), listOf(0.3f, 0.8f),
            listOf(0.8f, 0.7f), listOf(0.7f, 0.2f)
        )
        val mirroredExpected = listOf(
            listOf(0.8f, 0.3f), listOf(0.3f, 0.2f),
            listOf(0.2f, 0.7f), listOf(0.7f, 0.8f)
        )
        for (turns in 0..3) {
            ManualFaceRetouchMath.rawToOrientedUv(rawU, rawV, turns, false, uv)
            assertEquals(expected[turns][0], uv[0], 0.00001f)
            assertEquals(expected[turns][1], uv[1], 0.00001f)
            ManualFaceRetouchMath.orientedToRawUv(
                uv[0], uv[1], turns, false, uv
            )
            assertEquals(rawU, uv[0], 0.00001f)
            assertEquals(rawV, uv[1], 0.00001f)

            ManualFaceRetouchMath.rawToOrientedUv(rawU, rawV, turns, true, uv)
            assertEquals(mirroredExpected[turns][0], uv[0], 0.00001f)
            assertEquals(mirroredExpected[turns][1], uv[1], 0.00001f)
            ManualFaceRetouchMath.orientedToRawUv(
                uv[0], uv[1], turns, true, uv
            )
            assertEquals(rawU, uv[0], 0.00001f)
            assertEquals(rawV, uv[1], 0.00001f)
        }
    }

    @Test fun reversePreviewRetouchUsesTheSameOrientedRegionCoordinates() {
        val settings = ManualFaceRetouch(
            centerX = 0.30f, centerY = 0.70f, radiusX = 0.24f, radiusY = 0.28f,
            faceWidth = 0.16f, jawWidth = 0.08f, noseWidth = 0.04f, eyeScale = 0.12f
        )
        val uv = FloatArray(2)
        val oriented = FloatArray(2)
        val expectedRaw = FloatArray(2)
        val rawU = 0.22f
        val rawV = 0.41f
        val turns = 1
        val flip = true
        ManualFaceRetouchMath.rawToOrientedUv(rawU, rawV, turns, flip, oriented)
        ManualFaceRetouchMath.mapSourceUv(
            settings, oriented[0], oriented[1], 360, 640, expectedRaw
        )
        ManualFaceRetouchMath.orientedToRawUv(
            expectedRaw[0], expectedRaw[1], turns, flip, expectedRaw
        )
        ManualFaceRetouchMath.mapSourceUv(
            settings, rawU, rawV, 640, 360, uv, turns, flip
        )
        assertEquals(expectedRaw[0], uv[0], 0.00001f)
        assertEquals(expectedRaw[1], uv[1], 0.00001f)
        assertEquals(
            ManualFaceRetouchMath.softeningAmount(settings, oriented[0], oriented[1]),
            ManualFaceRetouchMath.softeningAmount(settings, rawU, rawV, turns, flip),
            0.00001f
        )
    }

    @Test fun reversePreviewPixelBudgetPreservesLandscapeAndPortraitAspect() {
        val landscape = ManualFaceRetouchBitmapPreview.previewSize(1920, 1080)
        val portrait = ManualFaceRetouchBitmapPreview.previewSize(1080, 1920)
        assertTrue(landscape.first.toLong() * landscape.second <=
            ManualFaceRetouchBitmapPreview.MAX_PREVIEW_PIXELS)
        assertTrue(portrait.first.toLong() * portrait.second <=
            ManualFaceRetouchBitmapPreview.MAX_PREVIEW_PIXELS)
        assertTrue(kotlin.math.abs(landscape.first.toFloat() / landscape.second - 16f / 9f) < 0.01f)
        assertTrue(kotlin.math.abs(portrait.first.toFloat() / portrait.second - 9f / 16f) < 0.01f)
    }

    @Test fun stillPhotoRetouchCanUseTheFullBoundedDecodeResolution() {
        val cap = PhotoBitmapDecoder.MAX_SIDE * PhotoBitmapDecoder.MAX_SIDE
        val square = ManualFaceRetouchBitmapPreview.previewSize(1024, 1024, cap)
        assertEquals(1024, square.first)
        assertEquals(1024, square.second)
        val bounded = ManualFaceRetouchBitmapPreview.previewSize(4_000, 3_000, cap)
        assertTrue(bounded.first.toLong() * bounded.second <= cap.toLong())
    }

    @Test fun outOfRangeAndNonFiniteControlsAreRejected() {
        assertTrue(runCatching { ManualFaceRetouch(centerX = Float.NaN) }.isFailure)
        assertTrue(runCatching { ManualFaceRetouch(radiusY = 0f) }.isFailure)
        assertTrue(runCatching {
            ManualFaceRetouch(centerX = 0.1f, radiusX = 0.2f)
        }.isFailure)
        assertTrue(runCatching { ManualFaceRetouch(faceWidth = 0.5f) }.isFailure)
        assertTrue(runCatching { ManualFaceRetouch(eyeScale = -0.1f) }.isFailure)
    }
}
