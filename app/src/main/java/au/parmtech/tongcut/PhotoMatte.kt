package au.parmtech.tongcut

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * P3 photo matte settings are persisted on IMAGE layers. A photo effect is deliberately
 * not applied to VIDEO layers until a frame-level GPU processor has been tested.
 */
enum class PhotoMask { NONE, CIRCLE, ROUNDED_RECT, LINEAR_WIPE }

data class PhotoChroma(
    val keyArgb: Int = 0xFF00FF00.toInt(),
    val tolerance: Float = 0.18f,
    val feather: Float = 0.12f
) {
    init {
        require(tolerance.isFinite() && tolerance in 0f..1f)
        require(feather.isFinite() && feather in 0f..1f)
    }
}

data class PhotoEffects(
    val mask: PhotoMask = PhotoMask.NONE,
    /** Normalized feather distance, 0..0.4 relative to smallest image dimension. */
    val feather: Float = 0.04f,
    val chroma: PhotoChroma? = null
) {
    init { require(feather.isFinite() && feather in 0f..0.4f) }
}

/** Pure pixel-matte math, shared by Android bitmap processing and JVM tests. */
object PhotoMatte {
    private fun smooth(low: Float, high: Float, value: Float): Float {
        if (high <= low) return if (value >= high) 1f else 0f
        val t = ((value - low) / (high - low)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /**
     * Coordinates are pixel centres. Mask is evaluated in physical pixels, not
     * stretched normalized circles, so a CIRCLE remains circular in a wide photo.
     */
    fun maskAlpha(x: Int, y: Int, width: Int, height: Int, mask: PhotoMask, feather: Float): Float {
        require(width > 0 && height > 0)
        require(x in 0 until width && y in 0 until height)
        require(feather.isFinite() && feather in 0f..0.4f)
        if (mask == PhotoMask.NONE) return 1f

        val dimension = min(width, height).toFloat()
        val px = x + 0.5f - width / 2f
        val py = y + 0.5f - height / 2f
        val blur = dimension * feather
        return when (mask) {
            PhotoMask.NONE -> 1f
            PhotoMask.CIRCLE -> {
                val radius = dimension * 0.48f
                val distanceInside = radius - sqrt(px * px + py * py)
                smooth(-blur / 2f, blur / 2f, distanceInside)
            }
            PhotoMask.ROUNDED_RECT -> {
                val radius = dimension * 0.19f
                val qx = abs(px) - (width / 2f - radius)
                val qy = abs(py) - (height / 2f - radius)
                val outerDistance = sqrt(max(qx, 0f) * max(qx, 0f) +
                    max(qy, 0f) * max(qy, 0f)) + min(max(qx, qy), 0f) - radius
                smooth(-blur / 2f, blur / 2f, -outerDistance)
            }
            PhotoMask.LINEAR_WIPE -> {
                val centre = width * 0.5f
                smooth(-blur / 2f, blur / 2f, centre - (x + 0.5f))
            }
        }
    }

    fun chromaAlpha(pixelArgb: Int, key: PhotoChroma): Float {
        val kr = ((key.keyArgb ushr 16) and 255) / 255f
        val kg = ((key.keyArgb ushr 8) and 255) / 255f
        val kb = (key.keyArgb and 255) / 255f
        val dr = ((pixelArgb ushr 16) and 255) / 255f - kr
        val dg = ((pixelArgb ushr 8) and 255) / 255f - kg
        val db = (pixelArgb and 255) / 255f - kb
        val distance = sqrt((dr * dr + dg * dg + db * db) / 3f)
        return smooth(key.tolerance, key.tolerance + key.feather, distance)
    }

    fun applyAlpha(pixelArgb: Int, x: Int, y: Int, width: Int, height: Int,
                   settings: PhotoEffects): Int {
        val current = pixelArgb ushr 24
        if (current == 0) return pixelArgb
        val mask = maskAlpha(x, y, width, height, settings.mask, settings.feather)
        val chroma = settings.chroma?.let { chromaAlpha(pixelArgb, it) } ?: 1f
        val next = (current * mask * chroma + 0.5f).toInt().coerceIn(0, 255)
        return (pixelArgb and 0x00FFFFFF) or (next shl 24)
    }
}
