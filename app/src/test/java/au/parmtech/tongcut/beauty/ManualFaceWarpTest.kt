package au.parmtech.tongcut.beauty

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualFaceWarpTest {
    private val landmarks = ManualFaceLandmarks(
        leftEye = FacePoint(0.40f, 0.38f),
        rightEye = FacePoint(0.60f, 0.38f),
        leftCheek = FacePoint(0.30f, 0.50f),
        rightCheek = FacePoint(0.70f, 0.50f),
        noseTip = FacePoint(0.50f, 0.54f),
        mouthCenter = FacePoint(0.50f, 0.64f),
        chin = FacePoint(0.50f, 0.82f)
    )

    @Test
    fun neutralWarpIsIdentityAndRendererReturnsIndependentCopy() {
        val point = FacePoint(0.23f, 0.71f)
        assertEquals(point, ManualFaceWarp.sourceAt(point, landmarks, ManualBeautySettings()))

        val pixels = intArrayOf(0xff010203.toInt(), 0x80405060.toInt(), 0x00000000, 0xffaabbcc.toInt())
        val rendered = ManualFaceWarpRenderer.render(2, 2, pixels, landmarks, ManualBeautySettings())
        assertNotSame(pixels, rendered)
        assertTrue(pixels.contentEquals(rendered))
    }

    @Test
    fun faceWidthMovesBothSidesOutwardSymmetrically() {
        val settings = ManualBeautySettings(faceWidth = 0.2f)
        val left = ManualFaceWarp.sourceAt(FacePoint(0.36f, 0.52f), landmarks, settings)
        val right = ManualFaceWarp.sourceAt(FacePoint(0.64f, 0.52f), landmarks, settings)
        assertTrue(left.x < 0.36f)
        assertTrue(right.x > 0.64f)
        assertEquals(1f - left.x, right.x, 0.0001f)
    }

    @Test
    fun everyMappedCoordinateStaysInsideImageAndRetouchPreservesAlpha() {
        val settings = ManualBeautySettings(
            faceWidth = -0.2f, jawWidth = 0.2f, noseWidth = -0.15f,
            eyeScale = 0.25f, skinSoftening = 0.65f
        )
        for (y in 0..20) for (x in 0..20) {
            val mapped = ManualFaceWarp.sourceAt(FacePoint(x / 20f, y / 20f), landmarks, settings)
            assertTrue(mapped.x in 0f..1f)
            assertTrue(mapped.y in 0f..1f)
        }

        val source = IntArray(20 * 20) { index ->
            val alpha = (index * 37) and 0xff
            (alpha shl 24) or ((index * 11 and 0xff) shl 16) or
                ((index * 17 and 0xff) shl 8) or (index * 23 and 0xff)
        }
        val result = ManualFaceWarpRenderer.render(20, 20, source, landmarks, settings)
        assertEquals(source.size, result.size)
        for (i in source.indices) assertEquals(source[i] ushr 24, result[i] ushr 24)
    }

    @Test
    fun cpuPixelLimitRejectsBeforeAllocatingOutput() {
        assertThrows(IllegalArgumentException::class.java) {
            ManualFaceWarpRenderer.render(2_000, 2_000, intArrayOf(), landmarks, ManualBeautySettings(faceWidth = 0.1f))
        }
    }

    @Test
    fun invalidManualAnchorOrderingIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ManualFaceLandmarks(
                leftEye = FacePoint(0.62f, 0.38f),
                rightEye = FacePoint(0.60f, 0.38f),
                leftCheek = landmarks.leftCheek,
                rightCheek = landmarks.rightCheek,
                noseTip = landmarks.noseTip,
                mouthCenter = landmarks.mouthCenter,
                chin = landmarks.chin
            )
        }
    }
}
