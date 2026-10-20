package au.parmtech.tongcut.beauty

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FaceReshapeFramePlanTest {
    private val sample = FaceReshapeLandmarks(
        leftEye = FacePoint(0.35f, 0.35f),
        rightEye = FacePoint(0.65f, 0.35f),
        leftCheek = FacePoint(0.25f, 0.55f),
        rightCheek = FacePoint(0.75f, 0.55f),
        leftJaw = FacePoint(0.32f, 0.72f),
        rightJaw = FacePoint(0.68f, 0.72f),
        leftNose = FacePoint(0.45f, 0.54f),
        rightNose = FacePoint(0.55f, 0.54f),
        chin = FacePoint(0.50f, 0.89f)
    )

    @Test fun interpolatesOnlyBetweenTrustedSourceFrames() {
        val next = sample.copy(
            leftEye = FacePoint(0.39f, 0.37f),
            rightEye = FacePoint(0.69f, 0.37f)
        )
        val observations = listOf(
            FaceReshapeObservation(100_000L, 4, sample, 0.99f),
            FaceReshapeObservation(200_000L, 4, next, 0.90f)
        )
        val midpoint = FaceReshapeFramePlan.at(observations, 150_000L)
        assertNotNull(midpoint)
        assertEquals(0.37f, midpoint!!.leftEye.x, 0.0001f)
        assertEquals(0.36f, midpoint.leftEye.y, 0.0001f)
        assertNull(FaceReshapeFramePlan.at(observations, 99_999L))
        assertNull(FaceReshapeFramePlan.at(observations, 200_001L))
    }

    @Test fun sameTimestampCanUseReliableAnchorWithoutInterpolation() {
        val observations = listOf(
            FaceReshapeObservation(50_000L, 2, sample, 0.95f)
        )
        assertEquals(sample, FaceReshapeFramePlan.at(observations, 50_000L))
    }

    @Test fun differentTrackKeysNeverBlendFaces() {
        val observations = listOf(
            FaceReshapeObservation(0L, 1, sample, 0.9f),
            FaceReshapeObservation(100_000L, 2, sample, 0.9f)
        )
        assertNull(FaceReshapeFramePlan.at(observations, 50_000L))
    }

    @Test fun largeGapAndLowConfidenceFailClosed() {
        val gap = listOf(
            FaceReshapeObservation(0L, 1, sample, 0.99f),
            FaceReshapeObservation(1_000_000L, 1, sample, 0.99f)
        )
        assertNull(FaceReshapeFramePlan.at(gap, 500_000L))
        val untrusted = listOf(
            FaceReshapeObservation(0L, 1, sample, 0.5f),
            FaceReshapeObservation(100_000L, 1, sample, 0.99f)
        )
        assertNull(FaceReshapeFramePlan.at(untrusted, 50_000L))
        assertNull(FaceReshapeFramePlan.at(untrusted, 0L))
    }

    @Test fun jumpedLandmarksDoNotSmearAcrossCuts() {
        val jumped = sample.copy(
            leftEye = FacePoint(0.55f, 0.35f),
            rightEye = FacePoint(0.80f, 0.35f)
        )
        val observations = listOf(
            FaceReshapeObservation(0L, 1, sample, 0.95f),
            FaceReshapeObservation(50_000L, 1, jumped, 0.95f)
        )
        assertNull(FaceReshapeFramePlan.at(observations, 25_000L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicatePresentationTimestampsAreRejected() {
        FaceReshapeFramePlan.at(
            listOf(
                FaceReshapeObservation(1L, 1, sample, 1f),
                FaceReshapeObservation(1L, 1, sample, 1f)
            ),
            1L
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun impossibleConfidenceIsRejected() {
        FaceReshapeObservation(1L, 1, sample, 2f)
    }
}
