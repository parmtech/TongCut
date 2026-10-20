package au.parmtech.tongcut.beauty

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/** Normalized top-left-origin landmark coordinates, deliberately entered by the user. */
data class FacePoint(val x: Float, val y: Float) {
    init {
        require(x.isFinite() && x in 0f..1f)
        require(y.isFinite() && y in 0f..1f)
    }
}

/** Minimum manual anchors for an isolated local geometry experiment; never auto-detected. */
data class ManualFaceLandmarks(
    val leftEye: FacePoint,
    val rightEye: FacePoint,
    val leftCheek: FacePoint,
    val rightCheek: FacePoint,
    val noseTip: FacePoint,
    val mouthCenter: FacePoint,
    val chin: FacePoint
) {
    init {
        require(leftEye.x < rightEye.x && leftCheek.x < rightCheek.x)
        require(leftEye.y < chin.y && rightEye.y < chin.y)
        require(leftCheek.y < chin.y && rightCheek.y < chin.y)
        require((rightCheek.x - leftCheek.x) in MIN_FACE_WIDTH..MAX_FACE_WIDTH) {
            "Manual cheek anchors must span a plausible normalized face width"
        }
    }

    companion object {
        const val MIN_FACE_WIDTH = 0.08f
        const val MAX_FACE_WIDTH = 0.9f
    }
}

/** Conservative non-generative adjustments. Positive width values narrow a region. */
data class ManualBeautySettings(
    val faceWidth: Float = 0f,
    val jawWidth: Float = 0f,
    val noseWidth: Float = 0f,
    val eyeScale: Float = 0f,
    val skinSoftening: Float = 0f
) {
    init {
        require(faceWidth.isFinite() && faceWidth in -0.2f..0.2f)
        require(jawWidth.isFinite() && jawWidth in -0.2f..0.2f)
        require(noseWidth.isFinite() && noseWidth in -0.15f..0.15f)
        require(eyeScale.isFinite() && eyeScale in 0f..0.25f)
        require(skinSoftening.isFinite() && skinSoftening in 0f..0.65f)
    }

    val isNeutral: Boolean get() = this == ManualBeautySettings()
}

/** Inverse normalized mapping: each output coordinate samples a bounded source coordinate. */
object ManualFaceWarp {
    fun sourceAt(
        output: FacePoint,
        landmarks: ManualFaceLandmarks,
        settings: ManualBeautySettings
    ): FacePoint {
        if (settings.isNeutral) return output
        val eyeY = (landmarks.leftEye.y + landmarks.rightEye.y) * 0.5f
        val centerX = (landmarks.leftCheek.x + landmarks.rightCheek.x) * 0.5f
        val halfWidth = (landmarks.rightCheek.x - landmarks.leftCheek.x) * 0.5f
        val halfHeight = ((landmarks.chin.y - eyeY) * 0.55f).coerceAtLeast(0.04f)
        val centerY = eyeY + (landmarks.chin.y - eyeY) * 0.48f

        var sourceX = output.x
        val faceWeight = weight(output.x, output.y, centerX, centerY,
            halfWidth * 1.15f, halfHeight * 1.15f)
        sourceX += (output.x - centerX) * settings.faceWidth * faceWeight

        val jawY = eyeY + (landmarks.chin.y - eyeY) * 0.78f
        val jawWeight = weight(output.x, output.y, centerX, jawY,
            halfWidth * 0.78f, halfHeight * 0.53f)
        sourceX += (output.x - centerX) * settings.jawWidth * jawWeight

        val noseWeight = weight(output.x, output.y, landmarks.noseTip.x, landmarks.noseTip.y,
            halfWidth * 0.24f, halfHeight * 0.27f)
        sourceX += (output.x - landmarks.noseTip.x) * settings.noseWidth * noseWeight

        var sourceY = output.y
        for (eye in listOf(landmarks.leftEye, landmarks.rightEye)) {
            val eyeWeight = weight(output.x, output.y, eye.x, eye.y,
                halfWidth * 0.24f, halfHeight * 0.15f)
            sourceX -= (output.x - eye.x) * settings.eyeScale * eyeWeight
            sourceY -= (output.y - eye.y) * settings.eyeScale * eyeWeight
        }
        return FacePoint(sourceX.coerceIn(0f, 1f), sourceY.coerceIn(0f, 1f))
    }

    /** Softening mask avoids eye, nostril and mouth anchors; it does not detect skin. */
    fun skinWeight(point: FacePoint, landmarks: ManualFaceLandmarks): Float {
        val eyeY = (landmarks.leftEye.y + landmarks.rightEye.y) * 0.5f
        val centerX = (landmarks.leftCheek.x + landmarks.rightCheek.x) * 0.5f
        val halfWidth = (landmarks.rightCheek.x - landmarks.leftCheek.x) * 0.5f
        val halfHeight = ((landmarks.chin.y - eyeY) * 0.55f).coerceAtLeast(0.04f)
        val centerY = eyeY + (landmarks.chin.y - eyeY) * 0.48f
        val face = weight(point.x, point.y, centerX, centerY, halfWidth, halfHeight)
        val eyeExclusion = max(
            weight(point.x, point.y, landmarks.leftEye.x, landmarks.leftEye.y,
                halfWidth * 0.32f, halfHeight * 0.22f),
            weight(point.x, point.y, landmarks.rightEye.x, landmarks.rightEye.y,
                halfWidth * 0.32f, halfHeight * 0.22f)
        )
        val noseExclusion = weight(point.x, point.y, landmarks.noseTip.x, landmarks.noseTip.y,
            halfWidth * 0.24f, halfHeight * 0.28f)
        val mouthExclusion = weight(point.x, point.y, landmarks.mouthCenter.x, landmarks.mouthCenter.y,
            halfWidth * 0.38f, halfHeight * 0.20f)
        return (face * (1f - max(eyeExclusion, max(noseExclusion, mouthExclusion))))
            .coerceIn(0f, 1f)
    }

    private fun weight(x: Float, y: Float, cx: Float, cy: Float, rx: Float, ry: Float): Float {
        if (rx <= 0f || ry <= 0f) return 0f
        val dx = (x - cx) / rx
        val dy = (y - cy) / ry
        val d2 = dx * dx + dy * dy
        if (d2 >= 1f) return 0f
        val edge = 1f - d2
        return edge * edge
    }
}

/**
 * Bounded ARGB_8888 CPU proof-of-concept for still images and synthetic fixtures.
 * It keeps source alpha during softening and uses premultiplied-alpha interpolation
 * when sampling warped coordinates. This is not a real-time video effect.
 */
object ManualFaceWarpRenderer {
    const val MAX_PIXELS = 1_500_000

    fun render(
        width: Int,
        height: Int,
        sourceArgb: IntArray,
        landmarks: ManualFaceLandmarks,
        settings: ManualBeautySettings
    ): IntArray {
        require(width >= 2 && height >= 2)
        require(width.toLong() * height <= MAX_PIXELS) { "Image exceeds CPU proof-of-concept bound" }
        require(sourceArgb.size == width * height)
        if (settings.isNeutral) return sourceArgb.copyOf()

        val output = IntArray(sourceArgb.size)
        val blurRadiusX = 2f / (width - 1)
        val blurRadiusY = 2f / (height - 1)
        for (y in 0 until height) {
            val ny = y.toFloat() / (height - 1)
            for (x in 0 until width) {
                val nx = x.toFloat() / (width - 1)
                val outputPoint = FacePoint(nx, ny)
                val source = ManualFaceWarp.sourceAt(outputPoint, landmarks, settings)
                val warped = sample(sourceArgb, width, height, source.x, source.y)
                val mask = ManualFaceWarp.skinWeight(source, landmarks) * settings.skinSoftening
                val pixel = if (mask > 0f) {
                    val blurred = blur(sourceArgb, width, height, source.x, source.y,
                        blurRadiusX, blurRadiusY)
                    blendRgbKeepAlpha(warped, blurred, mask)
                } else warped
                output[y * width + x] = pixel
            }
        }
        return output
    }

    private fun blur(
        pixels: IntArray, width: Int, height: Int, x: Float, y: Float, rx: Float, ry: Float
    ): Int {
        var alpha = 0f
        var redPremultiplied = 0f
        var greenPremultiplied = 0f
        var bluePremultiplied = 0f
        for (dy in -1..1) for (dx in -1..1) {
            val p = sample(pixels, width, height,
                (x + dx * rx).coerceIn(0f, 1f), (y + dy * ry).coerceIn(0f, 1f))
            val a = (p ushr 24 and 0xff) / 255f
            alpha += a / 9f
            redPremultiplied += (p ushr 16 and 0xff) * a / 9f
            greenPremultiplied += (p ushr 8 and 0xff) * a / 9f
            bluePremultiplied += (p and 0xff) * a / 9f
        }
        if (alpha <= 0f) return 0
        return ((alpha * 255f).toInt().coerceIn(0, 255) shl 24) or
            ((redPremultiplied / alpha).toInt().coerceIn(0, 255) shl 16) or
            ((greenPremultiplied / alpha).toInt().coerceIn(0, 255) shl 8) or
            (bluePremultiplied / alpha).toInt().coerceIn(0, 255)
    }

    private fun blendRgbKeepAlpha(base: Int, blurred: Int, amount: Float): Int {
        val t = amount.coerceIn(0f, 1f)
        val a = base ushr 24 and 0xff
        fun channel(shift: Int): Int {
            val left = base ushr shift and 0xff
            val right = blurred ushr shift and 0xff
            return (left + (right - left) * t).toInt().coerceIn(0, 255)
        }
        return (a shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    private fun sample(pixels: IntArray, width: Int, height: Int, nx: Float, ny: Float): Int {
        val fx = nx.coerceIn(0f, 1f) * (width - 1)
        val fy = ny.coerceIn(0f, 1f) * (height - 1)
        val x0 = fx.toInt()
        val y0 = fy.toInt()
        val x1 = min(width - 1, x0 + 1)
        val y1 = min(height - 1, y0 + 1)
        val tx = fx - x0
        val ty = fy - y0
        val samples = arrayOf(
            pixels[y0 * width + x0] to ((1f - tx) * (1f - ty)),
            pixels[y0 * width + x1] to (tx * (1f - ty)),
            pixels[y1 * width + x0] to ((1f - tx) * ty),
            pixels[y1 * width + x1] to (tx * ty)
        )
        var a = 0f
        var rp = 0f
        var gp = 0f
        var bp = 0f
        for ((pixel, weight) in samples) {
            val pa = (pixel ushr 24 and 0xff) / 255f
            a += pa * weight
            rp += (pixel ushr 16 and 0xff) * pa * weight
            gp += (pixel ushr 8 and 0xff) * pa * weight
            bp += (pixel and 0xff) * pa * weight
        }
        if (a <= 0f) return 0
        return ((a * 255f).toInt().coerceIn(0, 255) shl 24) or
            ((rp / a).toInt().coerceIn(0, 255) shl 16) or
            ((gp / a).toInt().coerceIn(0, 255) shl 8) or
            (bp / a).toInt().coerceIn(0, 255)
    }
}
