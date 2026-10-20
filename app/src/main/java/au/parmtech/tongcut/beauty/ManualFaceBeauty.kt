package au.parmtech.tongcut.beauty

/** Stable, schema-friendly manual retouch payload. Neutral by default for old projects. */
data class ManualFaceBeauty(
    val landmarks: ManualFaceLandmarks = ManualFaceLandmarks(
        leftEye = FacePoint(0.40f, 0.38f),
        rightEye = FacePoint(0.60f, 0.38f),
        leftCheek = FacePoint(0.30f, 0.55f),
        rightCheek = FacePoint(0.70f, 0.55f),
        noseTip = FacePoint(0.50f, 0.55f),
        mouthCenter = FacePoint(0.50f, 0.66f),
        chin = FacePoint(0.50f, 0.82f)
    ),
    val settings: ManualBeautySettings = ManualBeautySettings()
) {
    val isNeutral: Boolean get() = settings.isNeutral

    companion object {
        val Neutral = ManualFaceBeauty()
    }
}
