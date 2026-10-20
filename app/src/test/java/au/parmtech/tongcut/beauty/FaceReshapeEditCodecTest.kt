package au.parmtech.tongcut.beauty

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Project payload tests are source-only until the owner's designated QA pass. */
class FaceReshapeEditCodecTest {
    private val points = FaceReshapeLandmarks(
        leftEye = FacePoint(0.36f, 0.35f),
        rightEye = FacePoint(0.64f, 0.35f),
        leftCheek = FacePoint(0.26f, 0.54f),
        rightCheek = FacePoint(0.74f, 0.54f),
        leftJaw = FacePoint(0.34f, 0.73f),
        rightJaw = FacePoint(0.66f, 0.73f),
        leftNose = FacePoint(0.45f, 0.54f),
        rightNose = FacePoint(0.55f, 0.54f),
        chin = FacePoint(0.50f, 0.90f)
    )

    private fun valid(): FaceReshapeEdit = FaceReshapeEdit(
        parameters = FaceReshapeParams(
            cheekSlim = 0.4f,
            jawSlim = 0.2f,
            eyeEnlarge = 0.7f,
            noseSlim = 0.1f,
            chinLength = -0.5f
        ),
        observations = listOf(
            FaceReshapeObservation(10_000L, 1, points, 0.99f),
            FaceReshapeObservation(40_000L, 1,
                points.copy(chin = FacePoint(0.51f, 0.90f)), 0.91f)
        )
    )

    @Test fun jsonRoundTripPreservesAnchorAndSettings() {
        val first = valid()
        val json = FaceReshapeEditCodec.encode(first)
        val restored = FaceReshapeEditCodec.decode(JSONObject(json.toString()))
        assertEquals(first, restored)
        assertEquals(1, json.getInt("version"))
        assertEquals(2, restored.observations.size)
        assertEquals(0.505f, restored.preparedTrack().at(25_000L)!!.chin.x, 0.0001f)
    }

    @Test fun emptyManualTrackIsValidButDoesNotInventDetection() {
        val value = FaceReshapeEdit(FaceReshapeParams(eyeEnlarge = 1f), emptyList())
        val restored = FaceReshapeEditCodec.decode(
            FaceReshapeEditCodec.encode(value)
        )
        assertEquals(0, restored.observations.size)
        assertEquals(null, restored.preparedTrack().at(1L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownSchemaVersionRefusesSilentEditsDrop() {
        val json = FaceReshapeEditCodec.encode(valid())
        json.put("version", 999)
        FaceReshapeEditCodec.decode(json)
    }

    @Test(expected = IllegalArgumentException::class)
    fun corruptObservationCountIsRejectedEarly() {
        val json = FaceReshapeEditCodec.encode(valid())
        json.put("observations", JSONArray().apply {
            repeat(FaceReshapeEditCodec.MAX_SAMPLES + 1) { put(JSONObject()) }
        })
        FaceReshapeEditCodec.decode(json)
    }

    @Test(expected = IllegalArgumentException::class)
    fun outOfRangeSavedStrengthIsRejected() {
        val json = FaceReshapeEditCodec.encode(valid())
        json.getJSONObject("parameters").put("eyeEnlarge", 1.1)
        FaceReshapeEditCodec.decode(json)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidLeftRightAnchorsAreRejectedAfterRestore() {
        val json = FaceReshapeEditCodec.encode(valid())
        val face = json.getJSONArray("observations")
            .getJSONObject(0).getJSONObject("face")
        face.put("rightEye", JSONArray().put(0.1).put(0.35))
        FaceReshapeEditCodec.decode(json)
    }

    @Test fun doesNotPersistPhotoContentOrIdentifyingData() {
        val serialized = FaceReshapeEditCodec.encode(valid()).toString()
        assertTrue(serialized.contains("sourceTimeUs"))
        assertTrue(!serialized.contains("imagePath"))
        assertTrue(!serialized.contains("faceIdentity"))
        assertTrue(!serialized.contains("photoBytes"))
    }
}
