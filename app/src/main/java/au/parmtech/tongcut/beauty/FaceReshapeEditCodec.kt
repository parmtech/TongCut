package au.parmtech.tongcut.beauty

import org.json.JSONArray
import org.json.JSONObject

/**
 * Isolated, versioned local project-edit payload for a selected video/photo
 * layer. A03 (project store) must approve final migration/wiring.
 *
 * This codec never automatically uploads, exports or identifies a face.
 * Callers must ask before retaining landmark data in a saved project.
 */
internal data class FaceReshapeEdit(
    val parameters: FaceReshapeParams,
    val observations: List<FaceReshapeObservation>
) {
    init {
        require(observations.size <= FaceReshapeEditCodec.MAX_SAMPLES)
        FaceReshapeFramePlan.validate(observations)
    }

    fun preparedTrack(): FaceReshapeFramePlan.Prepared =
        FaceReshapeFramePlan.prepare(observations)
}

internal object FaceReshapeEditCodec {
    const val VERSION = 1
    const val MAX_SAMPLES = 12_000

    fun encode(edit: FaceReshapeEdit): JSONObject = JSONObject().apply {
        put("version", VERSION)
        put("parameters", JSONObject().apply {
            put("cheekSlim", edit.parameters.cheekSlim.toDouble())
            put("jawSlim", edit.parameters.jawSlim.toDouble())
            put("eyeEnlarge", edit.parameters.eyeEnlarge.toDouble())
            put("noseSlim", edit.parameters.noseSlim.toDouble())
            put("chinLength", edit.parameters.chinLength.toDouble())
        })
        put("observations", JSONArray().apply {
            edit.observations.forEach { sample ->
                put(JSONObject().apply {
                    put("sourceTimeUs", sample.presentationTimeUs)
                    put("trackKey", sample.trackKey)
                    put("confidence", sample.confidence.toDouble())
                    put("face", encodeFace(sample.landmarks))
                })
            }
        })
    }

    fun decode(json: JSONObject): FaceReshapeEdit {
        require(json.getInt("version") == VERSION) {
            "Unknown face reshape data version; do not discard saved edits"
        }
        val values = json.getJSONObject("parameters")
        fun finite01(key: String): Float =
            values.getDouble(key).toFloat().also {
                require(it.isFinite() && it in 0f..1f) {
                    "Corrupt face reshape parameter: $key"
                }
            }
        val chin = values.getDouble("chinLength").toFloat()
        require(chin.isFinite() && chin in -1f..1f)
        val parameters = FaceReshapeParams(
            cheekSlim = finite01("cheekSlim"),
            jawSlim = finite01("jawSlim"),
            eyeEnlarge = finite01("eyeEnlarge"),
            noseSlim = finite01("noseSlim"),
            chinLength = chin
        )
        val raw = json.getJSONArray("observations")
        require(raw.length() <= MAX_SAMPLES) {
            "Too many facial observations for a local project"
        }
        val observations = ArrayList<FaceReshapeObservation>(raw.length())
        for (i in 0 until raw.length()) {
            val frame = raw.getJSONObject(i)
            val sourceTime = frame.getLong("sourceTimeUs")
            val trackKey = frame.getInt("trackKey")
            val confidence = frame.getDouble("confidence").toFloat()
            observations += FaceReshapeObservation(
                presentationTimeUs = sourceTime,
                trackKey = trackKey,
                confidence = confidence,
                landmarks = decodeFace(frame.getJSONObject("face"))
            )
        }
        return FaceReshapeEdit(parameters, observations)
    }

    private fun encodeFace(face: FaceReshapeLandmarks): JSONObject = JSONObject().apply {
        put("leftEye", point(face.leftEye))
        put("rightEye", point(face.rightEye))
        put("leftCheek", point(face.leftCheek))
        put("rightCheek", point(face.rightCheek))
        put("leftJaw", point(face.leftJaw))
        put("rightJaw", point(face.rightJaw))
        put("leftNose", point(face.leftNose))
        put("rightNose", point(face.rightNose))
        put("chin", point(face.chin))
    }

    private fun point(value: FacePoint): JSONArray = JSONArray().apply {
        put(value.x.toDouble())
        put(value.y.toDouble())
    }

    private fun decodeFace(json: JSONObject): FaceReshapeLandmarks {
        fun read(name: String): FacePoint {
            val xy = json.getJSONArray(name)
            require(xy.length() == 2) { "Face landmark must have two coordinates" }
            val x = xy.getDouble(0).toFloat()
            val y = xy.getDouble(1).toFloat()
            return FacePoint(x, y)
        }
        return FaceReshapeLandmarks(
            leftEye = read("leftEye"),
            rightEye = read("rightEye"),
            leftCheek = read("leftCheek"),
            rightCheek = read("rightCheek"),
            leftJaw = read("leftJaw"),
            rightJaw = read("rightJaw"),
            leftNose = read("leftNose"),
            rightNose = read("rightNose"),
            chin = read("chin")
        )
    }
}
