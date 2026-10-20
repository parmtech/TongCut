package au.parmtech.tongcut.beauty

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceReshapeWarpTest {
    private val landmarks = FaceReshapeLandmarks(
        leftEye = FacePoint(0.36f, 0.36f),
        rightEye = FacePoint(0.64f, 0.36f),
        leftCheek = FacePoint(0.28f, 0.56f),
        rightCheek = FacePoint(0.72f, 0.56f),
        leftJaw = FacePoint(0.35f, 0.74f),
        rightJaw = FacePoint(0.65f, 0.74f),
        leftNose = FacePoint(0.45f, 0.55f),
        rightNose = FacePoint(0.55f, 0.55f),
        chin = FacePoint(0.50f, 0.90f)
    )

    @Test fun neutralWarpProducesByteExactPixelsWithoutAliasingSource() {
        val input = IntArray(64 * 64) { i ->
            (0xff000000.toInt() or ((i * 17 and 255) shl 16) or
                ((i * 3 and 255) shl 8) or (i * 5 and 255))
        }
        val result = FaceReshapeWarp.renderArgb(
            input, 64, 64, landmarks, FaceReshapeParams()
        )
        assertArrayEquals(input, result)
        assertFalse(result === input)
    }

    @Test fun cheekSlimChangesExpectedLandmarkAreaWithoutMovingRemoteCorner() {
        val width = 128
        val height = 128
        val pixels = IntArray(width * height) { 0xff000000.toInt() }
        val centerX = (landmarks.leftCheek.x * (width - 1)).toInt()
        val centerY = (landmarks.leftCheek.y * (height - 1)).toInt()
        for (y in centerY - 1..centerY + 1) {
            for (x in centerX - 1..centerX + 1) {
                pixels[y * width + x] = 0xffff0000.toInt()
            }
        }

        val changed = FaceReshapeWarp.renderArgb(
            pixels, width, height, landmarks,
            FaceReshapeParams(cheekSlim = 1f)
        )
        assertFalse(pixels.contentEquals(changed))
        assertEquals(pixels[0], changed[0])
        val movedX = centerX + 4 // ~0.44 * 0.075 * 127 pixels of movement
        val redAtOldTarget = (pixels[centerY * width + movedX] ushr 16) and 255
        val redAtNewTarget = (changed[centerY * width + movedX] ushr 16) and 255
        assertTrue("Expected left cheek pixel to shift inward", redAtNewTarget > redAtOldTarget)
    }

    @Test fun eyeEnlargementMovesEdgesButRetainsCenterColor() {
        val width = 128
        val height = 128
        val cx = (landmarks.leftEye.x * (width - 1)).toInt()
        val cy = (landmarks.leftEye.y * (height - 1)).toInt()
        val input = IntArray(width * height) { 0xff000000.toInt() }
        for (y in 0 until height) {
            for (x in 0 until width) {
                val dx = x - cx
                val dy = y - cy
                if (dx * dx + dy * dy <= 6 * 6) {
                    input[y * width + x] = 0xffffffff.toInt()
                }
            }
        }

        val changed = FaceReshapeWarp.renderArgb(
            input, width, height, landmarks,
            FaceReshapeParams(eyeEnlarge = 1f)
        )
        val originalWhite = input.count { (it and 255) >= 240 }
        val enlargedWhite = changed.count { (it and 255) >= 240 }
        assertTrue("Eye area should expand", enlargedWhite > originalWhite)
        assertEquals(0xffffffff.toInt(), changed[cy * width + cx])
    }

    @Test fun noRedFringeLeaksFromTransparentPixels() {
        val width = 64
        val height = 64
        val input = IntArray(width * height) { 0xff00ff00.toInt() }
        // Transparent red must not contaminate bilinearly resampled green.
        for (y in 10..50) {
            for (x in 10..30) {
                input[y * width + x] = 0x00ff0000
            }
        }
        val changed = FaceReshapeWarp.renderArgb(
            input, width, height, landmarks,
            FaceReshapeParams(eyeEnlarge = 1f, cheekSlim = 1f)
        )
        assertTrue(changed.all { (it ushr 16 and 255) == 0 })
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidLandmarkOrderFailsClosed() {
        landmarks.copy(rightEye = FacePoint(0.20f, 0.36f))
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidStrengthFailsClosed() {
        FaceReshapeParams(noseSlim = Float.NaN)
    }

    @Test(expected = IllegalArgumentException::class)
    fun oversizedFrameFailsBeforePixelProcessing() {
        FaceReshapeWarp.renderArgb(
            IntArray(16), 6000, 6000, landmarks, FaceReshapeParams(cheekSlim = 1f)
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun missingImagePixelsFailClosed() {
        FaceReshapeWarp.renderArgb(
            IntArray(10), 16, 16, landmarks, FaceReshapeParams(eyeEnlarge = 1f)
        )
    }
}
