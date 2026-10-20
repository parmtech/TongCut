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
