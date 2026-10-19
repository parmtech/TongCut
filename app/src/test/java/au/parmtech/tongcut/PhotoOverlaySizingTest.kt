package au.parmtech.tongcut

import org.junit.Assert.*
import org.junit.Test

class PhotoOverlaySizingTest {
    @Test fun squarePhotoFitsPreviewBoxRatherThanTinyBitmapPixels() {
        val pixels = PhotoOverlaySizing.outputWidth(720, 16f / 9f)
        assertEquals(1280, pixels)
        val scale = PhotoOverlaySizing.fitScale(128, 128, pixels)
        // Preview box is 537.6 x 302.4. Square bitmap must fit to 302px.
        assertEquals(302.4f / 128f, scale, 0.01f)
        assertTrue("old native-pixel scale caused tiny export", scale > 2f)
    }

    @Test fun widePhotoOccupiesFortyTwoPercentOfFrameWidth() {
        val scale = PhotoOverlaySizing.fitScale(320,180,1280)
        assertEquals(0.42f * 1280 / 320, scale,0.001f)
    }

    @Test fun portraitVideoIsBoundedByItsNarrowWidth() {
        assertEquals(720,PhotoOverlaySizing.outputWidth(720,9f/16f))
        val factor = PhotoOverlaySizing.fitScale(128,128,720)
        assertEquals(0.42f * 720 / (16f/9f) / 128f, factor,0.001f)
    }

    @Test(expected=IllegalArgumentException::class)
    fun rejectsInvalidSourceDimensions() {
        PhotoOverlaySizing.fitScale(0,128,1280)
    }

    @Test(expected=IllegalArgumentException::class)
    fun rejectsNonFiniteCanvasAspectRatio() {
        PhotoOverlaySizing.outputWidth(1080,Float.NaN)
    }

    @Test fun mixedPortraitAndLandscapeVideoFitTheSamePiPBoxAsPhotos() {
        val baseWidth=720
        val portraitSource=PhotoOverlaySizing.fitScale(180,320,baseWidth)
        val wideSource=PhotoOverlaySizing.fitScale(320,180,baseWidth)
        val targetWidth=720f * 0.42f
        val targetHeight=targetWidth / (16f/9f)
        assertEquals(targetHeight,320f*portraitSource,0.001f)
        assertEquals(targetHeight,180f*wideSource,0.001f)
        assertTrue(portraitSource < wideSource)
    }

}