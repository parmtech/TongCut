package au.parmtech.tongcut.beauty

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure Kotlin numeric checks for the GPU's planned inverse-geometry uniforms. */
class FaceReshapeGpuGeometryTest {
    private val points = FaceReshapeLandmarks(
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

    @Test fun neutralSettingsProduceIdentityGeometry() {
        val geometry = FaceReshapeGpuGeometry.from(points, FaceReshapeParams())
        assertEquals(7, geometry.pins.size)
        assertEquals(2, geometry.eyes.size)
        for ((x, y) in listOf(0f to 0f, 0.1f to 0.7f, 0.35f to 0.42f, 1f to 1f)) {
            val sampled = FaceReshapeGpuGeometry.sourceAt(geometry, x, y)
            assertEquals(x, sampled.x, 0.00001f)
            assertEquals(y, sampled.y, 0.00001f)
        }
    }

    @Test fun cheekPinUsesInverseMappingForInwardSlimming() {
        val strength = 1f
        val geometry = FaceReshapeGpuGeometry.from(
            points, FaceReshapeParams(cheekSlim = strength)
        )
        val cheekSpan = points.rightCheek.x - points.leftCheek.x
        val outputX = points.leftCheek.x + cheekSpan * 0.075f * strength
        val sampling = FaceReshapeGpuGeometry.sourceAt(
            geometry, outputX, points.leftCheek.y
        )
        assertEquals(points.leftCheek.x, sampling.x, 0.0001f)
        assertEquals(points.leftCheek.y, sampling.y, 0.0001f)
    }

    @Test fun eyeEnlargeInverseMapContractsSamplesTowardEyeCenter() {
        val geometry = FaceReshapeGpuGeometry.from(
            points, FaceReshapeParams(eyeEnlarge = 1f)
        )
        val outputX = points.leftEye.x + 0.02f
        val sample = FaceReshapeGpuGeometry.sourceAt(
            geometry, outputX, points.leftEye.y
        )
        assertTrue(sample.x < outputX)
        assertTrue(sample.x > points.leftEye.x)
        assertEquals(points.leftEye.y, sample.y, 0.0001f)
    }

    @Test fun allSettingsKeepTexcoordsInFrame() {
        val geometry = FaceReshapeGpuGeometry.from(
            points,
            FaceReshapeParams(
                cheekSlim = 1f,
                jawSlim = 1f,
                eyeEnlarge = 1f,
                noseSlim = 1f,
                chinLength = -1f
            )
        )
        for (iy in 0..20) {
            for (ix in 0..20) {
                val result = FaceReshapeGpuGeometry.sourceAt(
                    geometry, ix / 20f, iy / 20f
                )
                assertTrue(result.x.isFinite() && result.x in 0f..1f)
                assertTrue(result.y.isFinite() && result.y in 0f..1f)
            }
        }
    }

    @Test fun sevenGpuPinsMatchCpuPhysicalStrengthDefinitions() {
        val params = FaceReshapeParams(
            cheekSlim = 0.5f,
            jawSlim = 0.4f,
            eyeEnlarge = 0.3f,
            noseSlim = 0.8f,
            chinLength = -0.6f
        )
        val g = FaceReshapeGpuGeometry.from(points, params)
        val span = points.rightCheek.x - points.leftCheek.x
        assertEquals(-span * 0.075f * params.cheekSlim, g.pins[0].sourceDeltaX, 0.0001f)
        assertEquals(+span * 0.075f * params.cheekSlim, g.pins[1].sourceDeltaX, 0.0001f)
        assertEquals(-span * 0.11f * params.chinLength, g.pins[6].sourceDeltaY, 0.0001f)
        assertEquals(params.eyeEnlarge, g.eyeStrength, 0.0001f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsCoordinatesOutsidePhoto() {
        val g = FaceReshapeGpuGeometry.from(points, FaceReshapeParams())
        FaceReshapeGpuGeometry.sourceAt(g, 1.2f, 0.5f)
    }
}
