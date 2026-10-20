package au.parmtech.tongcut.beauty

/**
 * Normalized inverse-warp controls for a Media3 shader. The point positions,
 * deltas and radii intentionally mirror FaceReshapeWarp.renderArgb(), so both
 * implementations can be checked on the same synthetic input later.
 *
 * All values are in original source-frame coordinates with the origin at the
 * TOP LEFT. The GPU shader flips Y only at sampling time.
 */
internal data class FaceGpuPin(
    val targetX: Float, val targetY: Float,
    val sourceDeltaX: Float, val sourceDeltaY: Float,
    val radiusX: Float, val radiusY: Float
) {
    fun coordinates() = floatArrayOf(targetX, targetY, sourceDeltaX, sourceDeltaY)
    fun radii() = floatArrayOf(radiusX, radiusY)
}

internal data class FaceGpuEye(
    val centerX: Float, val centerY: Float,
    val radiusX: Float, val radiusY: Float
) {
    fun coordinates() = floatArrayOf(centerX, centerY, radiusX, radiusY)
}

internal data class FaceGpuFrame(
    val pins: List<FaceGpuPin>,
    val eyes: List<FaceGpuEye>,
    val eyeStrength: Float
) {
    init {
        require(pins.size == 7 && eyes.size == 2)
        require(eyeStrength.isFinite() && eyeStrength in 0f..1f)
    }
}

/**
 * Pure Kotlin uniform preparation, independent of GL and Android runtime.
 * This intentionally emits seven possible pins, even when a strength is zero.
 * With zero displacement the shader does not change source coordinates.
 */
internal object FaceReshapeGpuGeometry {
    fun from(
        landmarks: FaceReshapeLandmarks,
        params: FaceReshapeParams
    ): FaceGpuFrame {
        val span = landmarks.rightCheek.x - landmarks.leftCheek.x
        val eyeGap = landmarks.rightEye.x - landmarks.leftEye.x
        val jawSpan = landmarks.rightJaw.x - landmarks.leftJaw.x
        val noseSpan = landmarks.rightNose.x - landmarks.leftNose.x
        val faceRadiusX = (span * 0.48f).coerceIn(0.04f, 0.32f)
        val faceRadiusY = (span * 0.62f).coerceIn(0.05f, 0.35f)

        fun pin(
            point: FacePoint, movedX: Float, movedY: Float,
            radiusX: Float, radiusY: Float
        ): FaceGpuPin = FaceGpuPin(
            targetX = point.x + movedX,
            targetY = point.y + movedY,
            sourceDeltaX = -movedX,
            sourceDeltaY = -movedY,
            radiusX = radiusX.coerceAtLeast(0.025f),
            radiusY = radiusY.coerceAtLeast(0.025f)
        )

        val cheek = span * 0.075f * params.cheekSlim
        val jaw = jawSpan * 0.09f * params.jawSlim
        val nose = noseSpan * 0.15f * params.noseSlim
        val chin = span * 0.11f * params.chinLength

        val pins = listOf(
            pin(landmarks.leftCheek, cheek, 0f, faceRadiusX, faceRadiusY),
            pin(landmarks.rightCheek, -cheek, 0f, faceRadiusX, faceRadiusY),
            pin(landmarks.leftJaw, jaw, 0f, faceRadiusX, faceRadiusY),
            pin(landmarks.rightJaw, -jaw, 0f, faceRadiusX, faceRadiusY),
            pin(landmarks.leftNose, nose, 0f, noseSpan * 0.65f, span * 0.15f),
            pin(landmarks.rightNose, -nose, 0f, noseSpan * 0.65f, span * 0.15f),
            pin(landmarks.chin, 0f, chin, span * 0.30f, span * 0.32f)
        )
        val eyeRadiusX = (eyeGap * 0.34f).coerceAtLeast(0.03f)
        val eyeRadiusY = (eyeGap * 0.22f).coerceAtLeast(0.025f)
        val eyes = listOf(
            FaceGpuEye(landmarks.leftEye.x, landmarks.leftEye.y,
                eyeRadiusX, eyeRadiusY),
            FaceGpuEye(landmarks.rightEye.x, landmarks.rightEye.y,
                eyeRadiusX, eyeRadiusY)
        )
        return FaceGpuFrame(pins, eyes, params.eyeEnlarge)
    }

    /**
     * CPU mathematical oracle for shader displacement (without texture
     * sampling). This is NOT a substitute for a GLES pixel test.
     */
    fun sourceAt(
        geometry: FaceGpuFrame,
        outputX: Float,
        outputY: Float
    ): FacePoint {
        require(outputX.isFinite() && outputY.isFinite() &&
            outputX in 0f..1f && outputY in 0f..1f)

        fun influence(normalX: Float, normalY: Float): Float {
            val distanceSq = normalX * normalX + normalY * normalY
            if (distanceSq >= 1f) return 0f
            val t = (1f - distanceSq).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }

        var x = outputX
        var y = outputY
        geometry.pins.forEach { pin ->
            val weight = influence(
                (outputX - pin.targetX) / pin.radiusX,
                (outputY - pin.targetY) / pin.radiusY
            )
            x += pin.sourceDeltaX * weight
            y += pin.sourceDeltaY * weight
        }
        if (geometry.eyeStrength > 0f) {
            geometry.eyes.forEach { eye ->
                val weight = influence(
                    (outputX - eye.centerX) / eye.radiusX,
                    (outputY - eye.centerY) / eye.radiusY
                )
                val factor = 1f / (1f + 0.40f * geometry.eyeStrength * weight)
                x += (outputX - eye.centerX) * (factor - 1f)
                y += (outputY - eye.centerY) * (factor - 1f)
            }
        }
        return FacePoint(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
    }
}
