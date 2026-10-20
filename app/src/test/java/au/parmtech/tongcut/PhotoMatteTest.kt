package au.parmtech.tongcut

import org.junit.Assert.*
import org.junit.Test

class PhotoMatteTest {
    @Test fun circularMaskIsTransparentInCornersAndOpaqueAtCentre() {
        val solid = PhotoEffects(mask=PhotoMask.CIRCLE,feather=0.04f)
        assertEquals(0, PhotoMatte.applyAlpha(0xFFFFFFFF.toInt(),0,0,100,100,solid) ushr 24)
        assertEquals(255, PhotoMatte.applyAlpha(0xFFFFFFFF.toInt(),50,50,100,100,solid) ushr 24)
    }

    @Test fun circleStaysCircularForWidescreenPixels() {
        assertTrue(PhotoMatte.maskAlpha(100,49,200,100,PhotoMask.CIRCLE,0.02f)>0.99f)
        assertTrue(PhotoMatte.maskAlpha(0,49,200,100,PhotoMask.CIRCLE,0.02f)<0.01f)
    }

    @Test fun roundedRectAndWipeChangeAlpha() {
        assertTrue(PhotoMatte.maskAlpha(0,0,100,100,PhotoMask.ROUNDED_RECT,0.03f)<0.1f)
        assertTrue(PhotoMatte.maskAlpha(50,50,100,100,PhotoMask.ROUNDED_RECT,0.03f)>0.99f)
        assertEquals(1f,PhotoMatte.maskAlpha(10,50,100,100,PhotoMask.LINEAR_WIPE,0f),0f)
        assertEquals(0f,PhotoMatte.maskAlpha(90,50,100,100,PhotoMask.LINEAR_WIPE,0f),0f)
    }

    @Test fun greenScreenRemovesGreenButKeepsRed() {
        val key = PhotoChroma()
        assertEquals(0f,PhotoMatte.chromaAlpha(0xFF00FF00.toInt(),key),0f)
        assertEquals(1f,PhotoMatte.chromaAlpha(0xFFFF0000.toInt(),key),0f)
    }

    @Test fun gradeChangesOverlayRgbButPreservesMatteAlpha() {
        val original = 0x7F4080C0
        val adjusted = PhotoMatte.applyAlpha(
            original, 50, 50, 100, 100,
            PhotoEffects(colorGrade = ColorGrade(exposure = 1f))
        )
        assertEquals(0x7F, (adjusted ushr 24) and 255)
        assertEquals(128, (adjusted ushr 16) and 255)
        assertEquals(255, (adjusted ushr 8) and 255)
        assertEquals(255, adjusted and 255)
    }

    @Test fun keyFeatherSoftensOnlyTheChromaBoundaryNotTheShapeMask() {
        val hard = PhotoChroma(tolerance = 0.12f, feather = 0f)
        val soft = hard.copy(feather = 0.2f)
        // Partially desaturated green near the key: the hard key retains it,
        // while the feathered key progressively removes the green edge.
        val lightGreen = 0xFF33D633.toInt()
        val hardAlpha = PhotoMatte.chromaAlpha(lightGreen, hard)
        val softAlpha = PhotoMatte.chromaAlpha(lightGreen, soft)
        assertTrue("A soft chroma boundary should differ from a hard key",
            softAlpha < hardAlpha)
        assertEquals(0.04f, PhotoEffects(chroma = soft).feather, 0f)
        assertEquals(0.2f, PhotoEffects(chroma = soft).chroma!!.feather, 0f)
    }

    @Test fun originalAlphaIsMultipliedRatherThanLost() {
        val fx=PhotoEffects(chroma=PhotoChroma())
        val redHalf=0x80FF0000.toInt()
        assertEquals(128,PhotoMatte.applyAlpha(redHalf,10,10,20,20,fx) ushr 24)
        val zero=PhotoMatte.applyAlpha(0x0000FF00,10,10,20,20,fx)
        assertEquals(0,zero ushr 24)
    }

    @Test fun noOpKeepsPixelsIdentical() {
        val pixel=0xAF573BB1.toInt()
        assertEquals(pixel,PhotoMatte.applyAlpha(pixel,4,4,20,20,PhotoEffects()))
    }

    @Test fun boundedBlurUsesNeighbourPixelsAndZeroIsIdentity() {
        val pixels = IntArray(9) { 0xFF000000.toInt() }
        pixels[4] = 0xFFFFFFFF.toInt()
        assertEquals(0xFF404040.toInt(), PhotoMatte.blurPixel(pixels, 1, 1, 3, 3, 1f / 3f))
        assertEquals(pixels[4], PhotoMatte.blurPixel(pixels, 1, 1, 3, 3, 0f))

        val transparentEdges = IntArray(9)
        transparentEdges[4] = 0xFFFFFFFF.toInt()
        assertEquals(
            0x40FFFFFF,
            PhotoMatte.blurPixel(transparentEdges, 1, 1, 3, 3, 1f / 3f)
        )
    }

    @Test(expected=IllegalArgumentException::class)
    fun invalidFeatherRejected() { PhotoEffects(feather=Float.NaN) }

    @Test fun greenSpillIsRemovedWithoutDarkeningRedOrChangingAlpha() {
        val key = PhotoChroma(spillSuppression = 1f)
        val contaminated = 0x8896DC28.toInt()
        val corrected = PhotoMatte.despill(contaminated, key)
        assertEquals(0x88969628.toInt(), corrected)
        assertEquals(contaminated,
            PhotoMatte.despill(contaminated, key.copy(spillSuppression=0f)))
        assertEquals(0xFFFF0000.toInt(),
            PhotoMatte.despill(0xFFFF0000.toInt(),key))
        val halfway = PhotoMatte.despill(contaminated,
            key.copy(spillSuppression=0.5f))
        assertEquals(185, (halfway ushr 8) and 255)
        assertEquals(136, halfway ushr 24)
    }

    @Test fun neutralKeyDoesNotModifyColourAndBlueKeyTargetsBlueOnly() {
        val neutral = PhotoChroma(keyArgb=0xFF888888.toInt(),spillSuppression=1f)
        assertEquals(-1,PhotoMatte.dominantChannel(neutral.keyArgb))
        assertEquals(0xFF7799BB.toInt(),
            PhotoMatte.despill(0xFF7799BB.toInt(),neutral))
        val blue = PhotoChroma(keyArgb=0xFF0000FF.toInt(),spillSuppression=1f)
        assertEquals(2,PhotoMatte.dominantChannel(blue.keyArgb))
        assertEquals(0xFF665566.toInt(),
            PhotoMatte.despill(0xFF6655CC.toInt(),blue))
    }

    @Test(expected=IllegalArgumentException::class)
    fun invalidSpillRejected() {
        PhotoChroma(spillSuppression = Float.NaN)
    }

    @Test(expected=IllegalArgumentException::class)
    fun invalidChromaRejected() { PhotoChroma(tolerance=1.2f) }
}
