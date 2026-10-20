package au.parmtech.tongcut

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoMatteGeometryTest {
    @Test fun editablePolygonMaskKeepsCustomFootprintAndRoundTrips() {
        val points = listOf(
            MaskPoint(-0.7f, -0.7f), MaskPoint(0.7f, -0.7f), MaskPoint(0f, 0.7f)
        )
        val polygon = PhotoEffects(mask = PhotoMask.POLYGON, feather = 0f,
            maskPoints = points)
        assertEquals(1f, PhotoMatte.maskAlpha(
            50, 50, 100, 100, polygon.mask, polygon.feather,
            polygonPoints = polygon.maskPoints
        ), 0f)
        assertEquals(0f, PhotoMatte.maskAlpha(
            20, 40, 100, 100, polygon.mask, polygon.feather,
            polygonPoints = polygon.maskPoints
        ), 0f)

        val timeline = LayerTimeline(
            tracks = listOf(TimelineTrack("video", "Video", LayerKind.VIDEO, 0)),
            layers = listOf(TimelineLayer(
                id = "polygon", trackId = "video", kind = LayerKind.VIDEO,
                startUs = 0, durationUs = 1_000_000,
                sourceUri = "content://synthetic/video", sourceDurationUs = 2_000_000,
                photoEffects = polygon
            ))
        )
        assertEquals(timeline, LayerTimelineCodec.decode(LayerTimelineCodec.encode(timeline)))
    }

    @Test fun polygonFeatherUsesPhysicalDistanceAcrossFrameAspectRatios() {
        val points = listOf(
            MaskPoint(-0.5f, -0.5f), MaskPoint(0.5f, -0.5f),
            MaskPoint(0.5f, 0.5f), MaskPoint(-0.5f, 0.5f)
        )
        // Both samples sit 5.5 physical pixels beyond the polygon, despite
        // the frame's 2:1 aspect ratio and the differently oriented edges.
        val beyondVerticalEdge = PhotoMatte.maskAlpha(
            153, 50, 200, 100, PhotoMask.POLYGON, 0.2f,
            polygonPoints = points
        )
        val beyondHorizontalEdge = PhotoMatte.maskAlpha(
            100, 79, 200, 100, PhotoMask.POLYGON, 0.2f,
            polygonPoints = points
        )

        assertEquals(beyondVerticalEdge, beyondHorizontalEdge, 0.0001f)
        assertTrue(beyondVerticalEdge > 0f && beyondVerticalEdge < 1f)
    }

    @Test fun ellipseAndDiamondKeepDistinctRotatedGeometry() {
        val ellipse = PhotoMatte.maskAlpha(78, 78, 100, 100,
            PhotoMask.ELLIPSE, 0f)
        val diamond = PhotoMatte.maskAlpha(78, 78, 100, 100,
            PhotoMask.DIAMOND, 0f)

        assertTrue("Ellipse should retain diagonal point", ellipse > 0.99f)
        assertEquals("Diamond should cut the same diagonal point", 0f, diamond, 0f)
        assertTrue(PhotoMatte.maskAlpha(78, 50, 100, 100,
            PhotoMask.DIAMOND, 0f) > 0.99f)
    }

    @Test fun centerAndIndependentScaleMoveAndResizeTheMask() {
        val settings = PhotoEffects(
            mask = PhotoMask.ELLIPSE,
            feather = 0f,
            maskCenterX = 0.25f,
            maskScaleX = 0.5f
        )
        assertTrue(PhotoMatte.maskAlpha(
            62, 50, 100, 100, settings.mask, settings.feather,
            settings.maskCenterX, settings.maskCenterY,
            settings.maskScaleX, settings.maskScaleY
        ) > 0.99f)
        assertEquals(0f, PhotoMatte.maskAlpha(
            90, 50, 100, 100, settings.mask, settings.feather,
            settings.maskCenterX, settings.maskCenterY,
            settings.maskScaleX, settings.maskScaleY
        ), 0f)
    }

    @Test fun rotationChangesAnisotropicShapeAndInversionRetainsTheExterior() {
        val ordinary = PhotoEffects(mask = PhotoMask.ELLIPSE, feather = 0f,
            maskScaleX = 0.5f, maskScaleY = 1f)
        val rotated = ordinary.copy(maskRotationDegrees = 90f)
        assertTrue(PhotoMatte.maskAlpha(50, 85, 100, 100,
            ordinary.mask, ordinary.feather, scaleX = ordinary.maskScaleX,
            scaleY = ordinary.maskScaleY) > 0.99f)
        assertEquals(0f, PhotoMatte.maskAlpha(50, 85, 100, 100,
            rotated.mask, rotated.feather, scaleX = rotated.maskScaleX,
            scaleY = rotated.maskScaleY,
            rotationDegrees = rotated.maskRotationDegrees), 0f)

        val inverted = ordinary.copy(invertMask = true)
        val center = PhotoMatte.applyAlpha(0xFFFF0000.toInt(), 50, 50, 100, 100, inverted)
        val exterior = PhotoMatte.applyAlpha(0xFFFF0000.toInt(), 0, 0, 100, 100, inverted)
        assertEquals(0, center ushr 24)
        assertEquals(255, exterior ushr 24)
    }

    @Test fun editableGeometryRoundTripsAndOlderPhotoFxGetsSafeDefaults() {
        val effects = PhotoEffects(
            mask = PhotoMask.DIAMOND,
            feather = 0.03f,
            maskCenterX = -0.3f,
            maskCenterY = 0.2f,
            maskScaleX = 0.7f,
            maskScaleY = 0.4f,
            invertMask = true,
            maskRotationDegrees = 35f
        )
        val timeline = LayerTimeline(
            tracks = listOf(TimelineTrack("video", "Video", LayerKind.VIDEO, 0)),
            layers = listOf(TimelineLayer(
                id = "masked", trackId = "video", kind = LayerKind.VIDEO,
                startUs = 0, durationUs = 1_000_000,
                sourceUri = "content://synthetic/video", sourceDurationUs = 2_000_000,
                photoEffects = effects
            ))
        )
        val encoded = LayerTimelineCodec.encode(timeline)
        assertEquals(timeline, LayerTimelineCodec.decode(encoded))

        val legacy = LayerTimelineCodec.encode(timeline)
        val photoFx = legacy.getJSONArray("layers").getJSONObject(0)
            .getJSONObject("photoFx")
        listOf("centerX", "centerY", "scaleX", "scaleY", "invert", "rotation")
            .forEach(photoFx::remove)
        val restored = LayerTimelineCodec.decode(legacy).layers.single().photoEffects
        assertEquals(0f, restored.maskCenterX, 0f)
        assertEquals(0f, restored.maskCenterY, 0f)
        assertEquals(1f, restored.maskScaleX, 0f)
        assertEquals(1f, restored.maskScaleY, 0f)
        assertEquals(false, restored.invertMask)
        assertEquals(0f, restored.maskRotationDegrees, 0f)
    }
}
