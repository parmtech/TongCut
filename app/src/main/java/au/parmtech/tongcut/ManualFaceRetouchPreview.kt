package au.parmtech.tongcut

import android.graphics.Bitmap
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Shared normalized inverse map used by the bounded reverse-frame CPU preview. */
internal object ManualFaceRetouchMath {
    private val eyeSides = floatArrayOf(-1f, 1f)

    fun normalizeQuarterTurns(quarterTurns: Int): Int =
        ((quarterTurns % 4) + 4) % 4

    fun rawToOrientedUv(
        rawU: Float,
        rawV: Float,
        quarterTurns: Int,
        flipHorizontal: Boolean,
        out: FloatArray
    ) {
        require(out.size >= 2 && rawU.isFinite() && rawV.isFinite())
        val turns = normalizeQuarterTurns(quarterTurns)
        out[0] = orientedU(rawU, rawV, turns, flipHorizontal)
        out[1] = orientedV(rawU, rawV, turns, flipHorizontal)
    }

    fun orientedToRawUv(
        orientedU: Float,
        orientedV: Float,
        quarterTurns: Int,
        flipHorizontal: Boolean,
        out: FloatArray
    ) {
        require(out.size >= 2 && orientedU.isFinite() && orientedV.isFinite())
        val turns = normalizeQuarterTurns(quarterTurns)
        out[0] = rawUFromOriented(orientedU, orientedV, turns, flipHorizontal)
        out[1] = rawVFromOriented(orientedU, orientedV, turns)
    }

    internal fun rawUFromOriented(
        orientedU: Float, orientedV: Float, turns: Int, flipHorizontal: Boolean
    ): Float {
        val rawU = when (turns) {
            0 -> orientedU
            1 -> 1f - orientedV
            2 -> 1f - orientedU
            else -> orientedV
        }
        return if (flipHorizontal) 1f - rawU else rawU
    }

    internal fun rawVFromOriented(orientedU: Float, orientedV: Float, turns: Int): Float =
        when (turns) {
            0 -> orientedV
            1 -> orientedU
            2 -> 1f - orientedV
            else -> 1f - orientedU
        }

    private fun orientedU(rawU: Float, rawV: Float, turns: Int, flipHorizontal: Boolean): Float {
        val u = if (flipHorizontal) 1f - rawU else rawU
        return when (turns) {
            0 -> u
            1 -> rawV
            2 -> 1f - u
            else -> 1f - rawV
        }
    }

    private fun orientedV(rawU: Float, rawV: Float, turns: Int, flipHorizontal: Boolean): Float {
        val u = if (flipHorizontal) 1f - rawU else rawU
        return when (turns) {
            0 -> rawV
            1 -> 1f - u
            2 -> 1f - rawV
            else -> u
        }
    }

    fun mapSourceUv(
        settings: ManualFaceRetouch,
        outputU: Float,
        outputV: Float,
        frameWidth: Int,
        frameHeight: Int,
        out: FloatArray,
        quarterTurns: Int = 0,
        flipHorizontal: Boolean = false
    ) {
        require(frameWidth > 0 && frameHeight > 0 && out.size >= 2)
        require(outputU.isFinite() && outputV.isFinite())
        val turns = normalizeQuarterTurns(quarterTurns)
        val orientedWidth = if (turns % 2 == 0) frameWidth else frameHeight
        val orientedHeight = if (turns % 2 == 0) frameHeight else frameWidth
        val orientedOutputU = orientedU(outputU, outputV, turns, flipHorizontal)
        val orientedOutputV = orientedV(outputU, outputV, turns, flipHorizontal)

        val localX = (orientedOutputU - settings.centerX) / settings.radiusX
        val localY = (orientedOutputV - settings.centerY) / settings.radiusY
        val faceY = verticalFalloff(localY)
        val jawY = faceY * smoothStep(-0.12f, 0.72f, localY)
        var sourceU = orientedOutputU
        var sourceV = orientedOutputV

        if (settings.faceWidth != 0f || settings.jawWidth != 0f) {
            val contour = (settings.faceWidth * faceY + settings.jawWidth * jawY)
                .coerceIn(-0.5f, 0.5f)
            sourceU += settings.radiusX * contour * shapeBump(localX)
        }
        if (settings.noseWidth != 0f) {
            val noseLocalX = (sourceU - settings.centerX) / settings.radiusX
            val noseFalloff = 1f - smoothStep(
                0.08f, 0.45f, abs(localY - 0.08f)
            )
            sourceU += settings.radiusX * settings.noseWidth * noseFalloff *
                shapeBump(3f * noseLocalX)
        }
        if (settings.eyeScale > 0f) {
            eyeSides.forEach { side ->
                val eyeCenterU = settings.centerX + side * 0.29f * settings.radiusX
                val eyeCenterV = settings.centerY - 0.21f * settings.radiusY
                val offsetPixelsX = (sourceU - eyeCenterU) * orientedWidth
                val offsetPixelsY = (sourceV - eyeCenterV) * orientedHeight
                val eyeRadiusPixelsX = settings.radiusX * orientedWidth * 0.22f
                val eyeRadiusPixelsY = settings.radiusY * orientedHeight * 0.15f
                val normalizedX = offsetPixelsX / eyeRadiusPixelsX
                val normalizedY = offsetPixelsY / eyeRadiusPixelsY
                val distance = sqrt(normalizedX * normalizedX + normalizedY * normalizedY)
                val influence = 1f - smoothStep(0f, 1f, distance)
                val scale = 1f + settings.eyeScale * influence
                sourceU = eyeCenterU + (offsetPixelsX / scale) / orientedWidth
                sourceV = eyeCenterV + (offsetPixelsY / scale) / orientedHeight
            }
        }
        out[0] = rawUFromOriented(sourceU, sourceV, turns, flipHorizontal)
        out[1] = rawVFromOriented(sourceU, sourceV, turns)
    }

    fun softeningAmount(
        settings: ManualFaceRetouch,
        outputU: Float,
        outputV: Float,
        quarterTurns: Int = 0,
        flipHorizontal: Boolean = false
    ): Float {
        if (settings.skinSoftening <= 0f) return 0f
        val turns = normalizeQuarterTurns(quarterTurns)
        val orientedOutputU = orientedU(outputU, outputV, turns, flipHorizontal)
        val orientedOutputV = orientedV(outputU, outputV, turns, flipHorizontal)
        val localX = (orientedOutputU - settings.centerX) / settings.radiusX
        val localY = (orientedOutputV - settings.centerY) / settings.radiusY
        val distance = sqrt(localX * localX + localY * localY)
        return (1f - smoothStep(0.68f, 1f, distance)) *
            settings.skinSoftening * 0.34f
    }

    private fun verticalFalloff(y: Float): Float = 1f - smoothStep(0.72f, 1f, abs(y))

    private fun shapeBump(value: Float): Float {
        if (abs(value) >= 1f) return 0f
        val edge = 1f - value * value
        return value * edge * edge
    }

    private fun smoothStep(low: Float, high: Float, value: Float): Float {
        val t = ((value - low) / (high - low)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}

/**
 * Reduced-size software rendering for the cached reverse-frame preview only.
 * Forward preview and export keep using the shared Media3 GPU effect.
 */
internal object ManualFaceRetouchBitmapPreview {
    const val MAX_PREVIEW_PIXELS = 640 * 360
    private val channelShifts = intArrayOf(16, 8, 0, 24)

    fun previewSize(width: Int, height: Int, maxPixels: Int = MAX_PREVIEW_PIXELS): Pair<Int, Int> {
        require(width > 0 && height > 0 && maxPixels > 0)
        val area = width.toLong() * height.toLong()
        val scale = minOf(1.0, sqrt(maxPixels.toDouble() / area))
        var outputWidth = (width * scale).roundToInt().coerceAtLeast(1)
        var outputHeight = (height * scale).roundToInt().coerceAtLeast(1)
        while (outputWidth.toLong() * outputHeight > maxPixels) {
            if (outputWidth >= outputHeight) outputWidth-- else outputHeight--
        }
        return outputWidth to outputHeight
    }

    fun render(
        source: Bitmap,
        settings: ManualFaceRetouch,
        quarterTurns: Int = 0,
        flipHorizontal: Boolean = false,
        maxPixels: Int = MAX_PREVIEW_PIXELS,
        isCancelled: () -> Boolean = { false }
    ): Bitmap {
        if (!settings.isActive) return source
        val turns = ManualFaceRetouchMath.normalizeQuarterTurns(quarterTurns)
        val (width, height) = previewSize(source.width, source.height, maxPixels)
        val orientedWidth = if (turns % 2 == 0) width else height
        val orientedHeight = if (turns % 2 == 0) height else width
        val working = if (width == source.width && height == source.height) source
            else Bitmap.createScaledBitmap(source, width, height, true)
        var processed: Bitmap? = null
        try {
            val input = IntArray(width * height)
            working.getPixels(input, 0, width, 0, 0, width, height)
            val output = IntArray(input.size)
            val outputUv = FloatArray(2)
            val orientedSourceUv = FloatArray(2)
            val center = FloatArray(4)
            val right = FloatArray(4)
            val left = FloatArray(4)
            val down = FloatArray(4)
            val up = FloatArray(4)
            val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            processed = result
            for (y in 0 until height) {
                if (isCancelled()) throw CancellationException(
                    "Reverse retouch preview cancelled"
                )
                val outputV = (y + 0.5f) / height
                for (x in 0 until width) {
                    val outputU = (x + 0.5f) / width
                    ManualFaceRetouchMath.mapSourceUv(
                        settings, outputU, outputV, width, height, outputUv, turns,
                        flipHorizontal
                    )
                    ManualFaceRetouchMath.rawToOrientedUv(
                        outputUv[0], outputUv[1], turns, flipHorizontal, orientedSourceUv
                    )
                    val sourceU = orientedSourceUv[0]
                    val sourceV = orientedSourceUv[1]
                    sampleOriented(
                        input, width, height, sourceU, sourceV, turns, flipHorizontal, center
                    )
                    val amount = ManualFaceRetouchMath.softeningAmount(
                        settings, outputU, outputV, turns, flipHorizontal
                    )
                    var red = center[0]
                    var green = center[1]
                    var blue = center[2]
                    var alpha = center[3]
                    if (amount > 0f) {
                        sampleOriented(input, width, height, sourceU + 1f / orientedWidth,
                            sourceV, turns, flipHorizontal, right)
                        sampleOriented(input, width, height, sourceU - 1f / orientedWidth,
                            sourceV, turns, flipHorizontal, left)
                        sampleOriented(input, width, height, sourceU,
                            sourceV + 1f / orientedHeight, turns, flipHorizontal, down)
                        sampleOriented(input, width, height, sourceU,
                            sourceV - 1f / orientedHeight, turns, flipHorizontal, up)
                        val wr = edgeWeight(center, right)
                        val wl = edgeWeight(center, left)
                        val wd = edgeWeight(center, down)
                        val wu = edgeWeight(center, up)
                        val inverseWeight = 1f / (1f + wr + wl + wd + wu)
                        red = soften(center[0], right[0], left[0], down[0], up[0],
                            wr, wl, wd, wu, inverseWeight, amount)
                        green = soften(center[1], right[1], left[1], down[1], up[1],
                            wr, wl, wd, wu, inverseWeight, amount)
                        blue = soften(center[2], right[2], left[2], down[2], up[2],
                            wr, wl, wd, wu, inverseWeight, amount)
                        alpha = soften(center[3], right[3], left[3], down[3], up[3],
                            wr, wl, wd, wu, inverseWeight, amount)
                    }
                    output[y * width + x] = argb(red, green, blue, alpha)
                }
            }
            result.setPixels(output, 0, width, 0, 0, width, height)
            return result
        } catch (failure: Throwable) {
            processed?.let { if (!it.isRecycled) it.recycle() }
            throw failure
        } finally {
            if (working !== source && !working.isRecycled) working.recycle()
        }
    }

    private fun sampleOriented(
        pixels: IntArray,
        width: Int,
        height: Int,
        orientedU: Float,
        orientedV: Float,
        quarterTurns: Int,
        flipHorizontal: Boolean,
        out: FloatArray
    ) {
        val rawU = ManualFaceRetouchMath.rawUFromOriented(
            orientedU, orientedV, quarterTurns, flipHorizontal
        )
        val rawV = ManualFaceRetouchMath.rawVFromOriented(
            orientedU, orientedV, quarterTurns
        )
        sample(pixels, width, height, rawU, rawV, out)
    }

    private fun sample(
        pixels: IntArray,
        width: Int,
        height: Int,
        u: Float,
        v: Float,
        out: FloatArray
    ) {
        val pixelX = (u.coerceIn(0f, 1f) * width - 0.5f).coerceIn(0f, width - 1f)
        val pixelY = (v.coerceIn(0f, 1f) * height - 0.5f).coerceIn(0f, height - 1f)
        val x0 = floor(pixelX).toInt()
        val y0 = floor(pixelY).toInt()
        val x1 = minOf(x0 + 1, width - 1)
        val y1 = minOf(y0 + 1, height - 1)
        val tx = pixelX - x0
        val ty = pixelY - y0
        val topLeft = pixels[y0 * width + x0]
        val topRight = pixels[y0 * width + x1]
        val bottomLeft = pixels[y1 * width + x0]
        val bottomRight = pixels[y1 * width + x1]
        for (channel in 0..3) {
            val shift = channelShifts[channel]
            val a = ((topLeft ushr shift) and 255) / 255f
            val b = ((topRight ushr shift) and 255) / 255f
            val c = ((bottomLeft ushr shift) and 255) / 255f
            val d = ((bottomRight ushr shift) and 255) / 255f
            val top = a + (b - a) * tx
            val bottom = c + (d - c) * tx
            out[channel] = top + (bottom - top) * ty
        }
    }

    private fun edgeWeight(center: FloatArray, sample: FloatArray): Float {
        val red = sample[0] - center[0]
        val green = sample[1] - center[1]
        val blue = sample[2] - center[2]
        val alpha = sample[3] - center[3]
        return 0.12f * exp(-(red * red + green * green + blue * blue + alpha * alpha) * 45f)
    }

    private fun soften(
        center: Float, right: Float, left: Float, down: Float, up: Float,
        wr: Float, wl: Float, wd: Float, wu: Float, inverseWeight: Float, amount: Float
    ): Float {
        val smooth = (center + right * wr + left * wl + down * wd + up * wu) *
            inverseWeight
        return center + (smooth - center) * amount
    }

    private fun argb(red: Float, green: Float, blue: Float, alpha: Float): Int {
        fun channel(value: Float) = (value.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        return (channel(alpha) shl 24) or (channel(red) shl 16) or
            (channel(green) shl 8) or channel(blue)
    }
}
