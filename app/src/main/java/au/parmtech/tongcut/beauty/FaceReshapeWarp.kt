package au.parmtech.tongcut.beauty

import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Normalized source-frame coordinates (origin at upper left).
 *
 * The caller supplies landmarks, e.g. from consented on-device detection or a
 * manual landmark editor. This module does NOT pretend to detect faces.
 */
data class FacePoint(val x: Float, val y: Float) {
    init {
        require(x.isFinite() && x in 0f..1f) { "Face x must be normalized" }
        require(y.isFinite() && y in 0f..1f) { "Face y must be normalized" }
    }
}

/**
 * Upright, front-facing landmark subset; all values describe the ORIGINAL image.
 * Faces that are rotated, occluded or in profile need their own validated detector
 * / manual marker path before this model should be used.
 */
data class FaceReshapeLandmarks(
    val leftEye: FacePoint,
    val rightEye: FacePoint,
    val leftCheek: FacePoint,
    val rightCheek: FacePoint,
    val leftJaw: FacePoint,
    val rightJaw: FacePoint,
    val leftNose: FacePoint,
    val rightNose: FacePoint,
    val chin: FacePoint
) {
    init {
        require(leftEye.x < rightEye.x && leftCheek.x < rightCheek.x &&
            leftJaw.x < rightJaw.x && leftNose.x < rightNose.x) {
            "Ordered left/right facial landmarks required"
        }
        require(rightEye.x - leftEye.x >= 0.02f &&
            rightCheek.x - leftCheek.x >= 0.04f) {
            "Face landmark spacing is too narrow"
        }
    }
}

/** Strengths are intentionally bounded: the renderer is not a body tracking model. */
data class FaceReshapeParams(
    val cheekSlim: Float = 0f,
    val jawSlim: Float = 0f,
    val eyeEnlarge: Float = 0f,
    val noseSlim: Float = 0f,
    val chinLength: Float = 0f
) {
    init {
        listOf(cheekSlim, jawSlim, eyeEnlarge, noseSlim).forEach {
            require(it.isFinite() && it in 0f..1f) { "Reshape strength must be 0..1" }
        }
        require(chinLength.isFinite() && chinLength in -1f..1f) {
            "Chin length must be -1..1"
        }
    }

    val isNeutral: Boolean get() =
        cheekSlim == 0f && jawSlim == 0f && eyeEnlarge == 0f &&
            noseSlim == 0f && chinLength == 0f
}

/**
 * Deterministic, pure Kotlin manual facial geometry warp. No Android Bitmap,
 * network calls, face identification, ML models or proprietary assets required.
 *
 * This is a CPU still-frame prototype. The same displacement geometry must later
 * be implemented in the GPU playback/export compositor for video. Until then,
 * it must NOT be advertised as a working video-editor Face Reshape feature.
 */
object FaceReshapeWarp {
    const val MAX_PIXELS: Long = 16_777_216L

    private data class Pin(
        val targetX: Float,
        val targetY: Float,
        val deltaX: Float,
        val deltaY: Float,
        val radiusX: Float,
        val radiusY: Float
    )

    fun renderArgb(
        source: IntArray,
        width: Int,
        height: Int,
        landmarks: FaceReshapeLandmarks,
        params: FaceReshapeParams
    ): IntArray {
        require(width >= 2 && height >= 2 &&
            width.toLong() * height <= MAX_PIXELS) {
            "Invalid or oversized frame"
        }
        require(source.size.toLong() == width.toLong() * height) {
            "Frame buffer size mismatch"
        }
        if (params.isNeutral) return source.copyOf()

        val span = landmarks.rightCheek.x - landmarks.leftCheek.x
        val eyeGap = landmarks.rightEye.x - landmarks.leftEye.x
        val jawSpan = landmarks.rightJaw.x - landmarks.leftJaw.x
        val noseSpan = landmarks.rightNose.x - landmarks.leftNose.x
        val pins = ArrayList<Pin>(9)

        fun addPin(point: FacePoint, dx: Float, dy: Float, rx: Float, ry: Float) {
            if (dx == 0f && dy == 0f) return
            // Mapping OUTPUT target -> original source avoids forward-map holes.
            pins += Pin(point.x + dx, point.y + dy, -dx, -dy,
                rx.coerceAtLeast(0.025f), ry.coerceAtLeast(0.025f))
        }

        val faceRadiusX = (span * 0.48f).coerceIn(0.04f, 0.32f)
        val faceRadiusY = (span * 0.62f).coerceIn(0.05f, 0.35f)
        val cheekMove = span * 0.075f * params.cheekSlim
        addPin(landmarks.leftCheek, cheekMove, 0f, faceRadiusX, faceRadiusY)
        addPin(landmarks.rightCheek, -cheekMove, 0f, faceRadiusX, faceRadiusY)

        val jawMove = jawSpan * 0.09f * params.jawSlim
        addPin(landmarks.leftJaw, jawMove, 0f, faceRadiusX, faceRadiusY)
        addPin(landmarks.rightJaw, -jawMove, 0f, faceRadiusX, faceRadiusY)

        val noseMove = noseSpan * 0.15f * params.noseSlim
        addPin(landmarks.leftNose, noseMove, 0f, noseSpan * 0.65f, span * 0.15f)
        addPin(landmarks.rightNose, -noseMove, 0f, noseSpan * 0.65f, span * 0.15f)

        val chinShift = span * 0.11f * params.chinLength
        addPin(landmarks.chin, 0f, chinShift, span * 0.3f, span * 0.32f)

        val eyes = arrayOf(landmarks.leftEye, landmarks.rightEye)
        val eyeRadiusX = (eyeGap * 0.34f).coerceAtLeast(0.03f)
        val eyeRadiusY = (eyeGap * 0.22f).coerceAtLeast(0.025f)
        val output = IntArray(source.size)

        for (y in 0 until height) {
            val normalizedY = y.toFloat() / (height - 1).toFloat()
            for (x in 0 until width) {
                val normalizedX = x.toFloat() / (width - 1).toFloat()
                var sampleX = normalizedX
                var sampleY = normalizedY

                for (pin in pins) {
                    val nx = (normalizedX - pin.targetX) / pin.radiusX
                    val ny = (normalizedY - pin.targetY) / pin.radiusY
                    val distanceSq = nx * nx + ny * ny
                    if (distanceSq < 1f) {
                        val influence = smooth(1f - distanceSq)
                        sampleX += pin.deltaX * influence
                        sampleY += pin.deltaY * influence
                    }
                }

                if (params.eyeEnlarge > 0f) {
                    // Radial inverse scaling keeps the eye center fixed and
                    // expands its surroundings without a hard circular seam.
                    for (eye in eyes) {
                        val ex = (normalizedX - eye.x) / eyeRadiusX
                        val ey = (normalizedY - eye.y) / eyeRadiusY
                        val distanceSq = ex * ex + ey * ey
                        if (distanceSq < 1f) {
                            val influence = smooth(1f - distanceSq)
                            val factor = 1f / (1f + 0.40f * params.eyeEnlarge * influence)
                            sampleX += (normalizedX - eye.x) * (factor - 1f)
                            sampleY += (normalizedY - eye.y) * (factor - 1f)
                        }
                    }
                }

                output[y * width + x] = bilinear(
                    source, width, height,
                    sampleX.coerceIn(0f, 1f) * (width - 1),
                    sampleY.coerceIn(0f, 1f) * (height - 1)
                )
            }
        }
        return output
    }

    private fun smooth(value: Float): Float {
        val t = value.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** Alpha-correct bilinear interpolation avoids colored transparent fringes. */
    private fun bilinear(data: IntArray, width: Int, height: Int, x: Float, y: Float): Int {
        val x0 = floor(x).toInt().coerceIn(0, width - 1)
        val y0 = floor(y).toInt().coerceIn(0, height - 1)
        val x1 = (x0 + 1).coerceAtMost(width - 1)
        val y1 = (y0 + 1).coerceAtMost(height - 1)
        val fx = x - x0
        val fy = y - y0

        val colors = intArrayOf(
            data[y0 * width + x0], data[y0 * width + x1],
            data[y1 * width + x0], data[y1 * width + x1]
        )
        val weights = floatArrayOf(
            (1f - fx) * (1f - fy), fx * (1f - fy),
            (1f - fx) * fy, fx * fy
        )

        var alpha = 0f
        var premulRed = 0f
        var premulGreen = 0f
        var premulBlue = 0f
        for (index in colors.indices) {
            val color = colors[index]
            val weight = weights[index]
            val a = ((color ushr 24) and 255) / 255f
            alpha += a * weight
            premulRed += ((color ushr 16) and 255) * a * weight
            premulGreen += ((color ushr 8) and 255) * a * weight
            premulBlue += (color and 255) * a * weight
        }
        if (alpha <= 0.000001f) return 0

        fun channel(value: Float): Int =
            (value / alpha).roundToInt().coerceIn(0, 255)
        val a = (alpha * 255f).roundToInt().coerceIn(0, 255)
        return (a shl 24) or (channel(premulRed) shl 16) or
            (channel(premulGreen) shl 8) or channel(premulBlue)
    }
}
