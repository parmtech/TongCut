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
    val tint: Float = 0f
) {
    init {
        require(exposure.isFinite() && exposure in -2f..2f)
        require(contrast.isFinite() && contrast in 0f..2f)
        require(saturation.isFinite() && saturation in 0f..2f)
        require(temperature.isFinite() && temperature in -1f..1f)
        require(tint.isFinite() && tint in -1f..1f)
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
                tint = value.optDouble("tint", 0.0).toFloat()
            )
        }.getOrDefault(ColorGrade())
    }
}
