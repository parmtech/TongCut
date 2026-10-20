package au.parmtech.tongcut.beauty

import org.json.JSONArray
import org.json.JSONObject

/** Strict JSON adapter. Missing objects are neutral; malformed present values fail decode. */
object ManualFaceBeautyJson {
    fun encode(value: ManualFaceBeauty): JSONObject = JSONObject().apply {
        put("landmarks", JSONObject().apply {
            fun putPoint(name: String, p: FacePoint) {
                put(name, JSONArray().put(p.x.toDouble()).put(p.y.toDouble()))
            }
            putPoint("leftEye", value.landmarks.leftEye)
            putPoint("rightEye", value.landmarks.rightEye)
            putPoint("leftCheek", value.landmarks.leftCheek)
            putPoint("rightCheek", value.landmarks.rightCheek)
            putPoint("noseTip", value.landmarks.noseTip)
            putPoint("mouthCenter", value.landmarks.mouthCenter)
            putPoint("chin", value.landmarks.chin)
        })
        put("settings", JSONObject().apply {
            put("faceWidth", value.settings.faceWidth.toDouble())
            put("jawWidth", value.settings.jawWidth.toDouble())
            put("noseWidth", value.settings.noseWidth.toDouble())
            put("eyeScale", value.settings.eyeScale.toDouble())
            put("skinSoftening", value.settings.skinSoftening.toDouble())
        })
    }

    fun decode(json: JSONObject?): ManualFaceBeauty {
        if (json == null) return ManualFaceBeauty.Neutral
        val points = json.getJSONObject("landmarks")
        fun point(name: String): FacePoint {
            val pair = points.getJSONArray(name)
            require(pair.length() == 2) { "Invalid manual beauty landmark: $name" }
            return FacePoint(pair.getDouble(0).toFloat(), pair.getDouble(1).toFloat())
        }
        val values = json.getJSONObject("settings")
        return ManualFaceBeauty(
            ManualFaceLandmarks(
                point("leftEye"), point("rightEye"), point("leftCheek"),
                point("rightCheek"), point("noseTip"), point("mouthCenter"), point("chin")
            ),
            ManualBeautySettings(
                faceWidth = values.optDouble("faceWidth", 0.0).toFloat(),
                jawWidth = values.optDouble("jawWidth", 0.0).toFloat(),
                noseWidth = values.optDouble("noseWidth", 0.0).toFloat(),
                eyeScale = values.optDouble("eyeScale", 0.0).toFloat(),
                skinSoftening = values.optDouble("skinSoftening", 0.0).toFloat()
            )
        )
    }
}
