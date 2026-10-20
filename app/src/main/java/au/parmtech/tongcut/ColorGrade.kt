Warning: fs was declared with const; use let for reassignable variables.
Warning: s was declared with const; use let for reassignable variables.
package au.parmtech.tongcut

import org.json.JSONObject
import kotlin.math.pow

/** Bounded, SDR-oriented primary-footage grade shared by preview and export. */
data class ColorGrade(
    /** Exposure in stops. */
    val exposure: Float = 0f,
    /** Contrast around middle gray. */
    val contrast: Float = 1f,
    val saturation: Float = 1f,
    /** Warm (+) to cool (-), a restrained red/blue channel balance. */
    val temperature: Float = 0f,
    /** Magenta (+) to green (-) white-balance tint. */
    val tint: Float = 0f,
    /** Global HSL hue rotation in degrees. */
    val hueDegrees: Float = 0f,
    /** Additive HSL saturation adjustment. */
    val hslSaturation: Float = 0f,
    /** Additive HSL lightness shift; -1/1 move to black/white. */
    val hslLightness: Float = 0f
) {
    init {
        require(exposure.isFinite() && exposure in -2f..2f)
        require(contrast.isFinite() && contrast in 0f..2f)
        require(saturation.isFinite() && saturation in 0f..2f)
        require(temperature.isFinite() && temperature in -1f..1f)
        require(tint.isFinite() && tint in -1f..1f)
        require(hueDegrees.isFinite() && hueDegrees in -180f..180f)
        require(hslSaturation.isFinite() && hslSaturation in -1f..1f)
        require(hslLightness.isFinite() && hslLightness in -1f..1f)
    }

    fun apply(argb: Int): Int {
        if (this == ColorGrade()) return argb
        val multiplier = 2f.pow(exposure)
        var red = ((argb ushr 16) and 255) / 255f * multiplier
        var green = ((argb ushr 8) and 255) / 255f * multiplier
        var blue = (argb and 255) / 255f * multiplier
        red = (red - 0.5f) * contrast + 0.5f
        green = (green - 0.5f) * contrast + 0.5f
        blue = (blue - 0.5f) * contrast + 0.5f
        val luminance = red * 0.2126f + green * 0.7152f + blue * 0.0722f
        red = luminance + (red - luminance) * saturation + temperature * 0.10f
        green = luminance + (green - luminance) * saturation
        blue = luminance + (blue - luminance) * saturation - temperature * 0.10f
        red += tint * 0.05f
        green -= tint * 0.10f
        blue += tint * 0.05f
        if (kotlin.math.abs(hueDegrees) > 0.000001f ||
            kotlin.math.abs(hslSaturation) > 0.000001f ||
            kotlin.math.abs(hslLightness) > 0.000001f) {
            red = red.coerceIn(0f, 1f)
            green = green.coerceIn(0f, 1f)
            blue = blue.coerceIn(0f, 1f)
            val maximum = maxOf(red, green, blue)
            val minimum = minOf(red, green, blue)
            val delta = maximum - minimum
            val lightness = (maximum + minimum) * 0.5f
            val saturation = (if (delta <= 0.000001f) 0f else
                (delta / (1f - kotlin.math.abs(2f * lightness - 1f)).coerceAtLeast(0.000001f) +
                    hslSaturation).coerceIn(0f, 1f))
            val baseHue = when {
                delta <= 0.000001f -> 0f
                maximum == red -> ((green - blue) / delta).let { if (it < 0f) it + 6f else it }
                maximum == green -> (blue - red) / delta + 2f
                else -> (red - green) / delta + 4f
            }
            val hue = (((baseHue + hueDegrees / 60f) % 6f) + 6f) % 6f
            val adjustedLightness = (lightness + hslLightness * 0.5f).coerceIn(0f, 1f)
            val chroma = (1f - kotlin.math.abs(2f * adjustedLightness - 1f)) * saturation
            val secondary = chroma * (1f - kotlin.math.abs((hue % 2f) - 1f))
            val offset = adjustedLightness - chroma * 0.5f
            val (r, g, b) = when {
                hue < 1f -> Triple(chroma, secondary, 0f)
                hue < 2f -> Triple(secondary, chroma, 0f)
                hue < 3f -> Triple(0f, chroma, secondary)
                hue < 4f -> Triple(0f, secondary, chroma)
                hue < 5f -> Triple(secondary, 0f, chroma)
                else -> Triple(chroma, 0f, secondary)
            }
            red = r + offset
            green = g + offset
            blue = b + offset
        }
        fun channel(value: Float) = (value.coerceIn(0f, 1f) * 255f).toInt()
        return (argb and -0x1000000) or (channel(red) shl 16) or
            (channel(green) shl 8) or channel(blue)
    }
}

object ColorGradeCodec {
    fun encode(grade: ColorGrade): JSONObject = JSONObject().apply {
        put("exposure", grade.exposure.toDouble())
        put("contrast", grade.contrast.toDouble())
        put("saturation", grade.saturation.toDouble())
        put("temperature", grade.temperature.toDouble())
        put("tint", grade.tint.toDouble())
        put("hueDegrees", grade.hueDegrees.toDouble())
        put("hslSaturation", grade.hslSaturation.toDouble())
        put("hslLightness", grade.hslLightness.toDouble())
    }

    /** Missing/invalid adjustment data safely restores neutral colour. */
    fun decode(value: JSONObject?): ColorGrade {
        if (value == null) return ColorGrade()
        return runCatching {
            ColorGrade(
                exposure = value.optDouble("exposure", 0.0).toFloat(),
                contrast = value.optDouble("contrast", 1.0).toFloat(),
                saturation = value.optDouble("saturation", 1.0).toFloat(),
                temperature = value.optDouble("temperature", 0.0).toFloat(),
                tint = value.optDouble("tint", 0.0).toFloat(),
                hueDegrees = value.optDouble("hueDegrees", 0.0).toFloat(),
                hslSaturation = value.optDouble("hslSaturation", 0.0).toFloat(),
                hslLightness = value.optDouble("hslLightness", 0.0).toFloat()
            )
        }.getOrDefault(ColorGrade())
    }
}
