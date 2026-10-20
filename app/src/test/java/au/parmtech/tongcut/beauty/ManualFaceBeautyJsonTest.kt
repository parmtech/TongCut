package au.parmtech.tongcut.beauty

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualFaceBeautyJsonTest {
    @Test fun absentPayloadUsesNeutralOldProjectDefault() {
        assertEquals(ManualFaceBeauty.Neutral, ManualFaceBeautyJson.decode(null))
    }

    @Test fun boundedManualSettingsAndLandmarksRoundTrip() {
        val original = ManualFaceBeauty(
            settings = ManualBeautySettings(faceWidth = 0.08f, jawWidth = -0.04f,
                noseWidth = 0.03f, eyeScale = 0.1f, skinSoftening = 0.2f)
        )
        assertEquals(original, ManualFaceBeautyJson.decode(ManualFaceBeautyJson.encode(original)))
    }

    @Test fun warpPreservesAlphaChannel() {
        val source = IntArray(32 * 32) { index ->
            0x88607080.toInt()
        }
        val settings = ManualBeautySettings(faceWidth = 0.1f, skinSoftening = 0.3f)
        val result = ManualFaceWarpRenderer.render(32, 32, source,
            ManualFaceBeauty.Neutral.landmarks, settings)
        val outputAlpha = result.first() ushr 24
        assertTrue(outputAlpha > 0)
        assertTrue(result.all { (it ushr 24) == outputAlpha })
    }
}
