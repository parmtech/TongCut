package au.parmtech.tongcut

import org.json.JSONObject
import kotlin.math.pow
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** One of six smoothly overlapping hue ranges: red, yellow, green, cyan, blue, magenta. */
data class HslBandAdjustment(
    val hueDegrees: Float = 0f,
    val saturation: Float = 0f,
    val lightness: Float = 0f
) {
    init {
        require(hueDegrees.isFinite() && hueDegrees in -180f..180f)
        require(saturation.isFinite() && saturation in -1f..1f)
        require(lightness.isFinite() && lightness in -1f..1f)
    }
}

internal val DEFAULT_HSL_BANDS = List(6) { HslBandAdjustment() }
internal val HSL_BAND_NAMES = listOf("Red", "Yellow", "Green", "Cyan", "Blue", "Magenta")

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
    val hslLightness: Float = 0f,
    /** Selective hue/saturation/lightness edits, in red-to-magenta order. */
    val hslBands: List<HslBandAdjustment> = DEFAULT_HSL_BANDS,
    /** Master plus per-channel RGB tone curves. */
    val curves: ColorCurves = ColorCurves()
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
        require(hslBands.size == 6)
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
        val selective = HslBandMath.apply(red, green, blue, hslBands)
        red = selective.first
        green = selective.second
        blue = selective.third
        val curved = curves.apply(red, green, blue)
        red = curved.first
        green = curved.second
        blue = curved.third
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
        put("hslBands", org.json.JSONArray().apply {
            grade.hslBands.forEach { band ->
                put(JSONObject().apply {
                    put("hue", band.hueDegrees.toDouble())
                    put("saturation", band.saturation.toDouble())
                    put("lightness", band.lightness.toDouble())
                })
            }
        })
        put("curves", JSONObject().apply {
            put("master", curveJson(grade.curves.master))
            put("red", curveJson(grade.curves.red))
            put("green", curveJson(grade.curves.green))
            put("blue", curveJson(grade.curves.blue))
        })
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
                hslLightness = value.optDouble("hslLightness", 0.0).toFloat(),
                hslBands = value.optJSONArray("hslBands")?.let { bands ->
                    require(bands.length() == 6)
                    (0 until 6).map { index ->
                        val band = bands.getJSONObject(index)
                        HslBandAdjustment(
                            hueDegrees = band.optDouble("hue", 0.0).toFloat(),
                            saturation = band.optDouble("saturation", 0.0).toFloat(),
                            lightness = band.optDouble("lightness", 0.0).toFloat()
                        )
                    }
                } ?: DEFAULT_HSL_BANDS,
                curves = value.optJSONObject("curves")?.let { curves ->
                    ColorCurves(
                        master = decodeCurve(curves.optJSONArray("master")),
                        red = decodeCurve(curves.optJSONArray("red")),
                        green = decodeCurve(curves.optJSONArray("green")),
                        blue = decodeCurve(curves.optJSONArray("blue"))
                    )
                } ?: ColorCurves()
            )
        }.getOrDefault(ColorGrade())
    }

    private fun curveJson(curve: ToneCurve) = org.json.JSONArray().apply {
        curve.points.forEach { put(org.json.JSONArray().put(it.x.toDouble()).put(it.y.toDouble())) }
    }

    private fun decodeCurve(points: org.json.JSONArray?): ToneCurve {
        if (points == null) return ToneCurve()
        require(points.length() in 2..8)
        return ToneCurve((0 until points.length()).map { index ->
            val point = points.getJSONArray(index)
            require(point.length() == 2)
            CurvePoint(point.getDouble(0).toFloat(), point.getDouble(1).toFloat())
        })
    }
}

/** Deterministic CPU selective HSL transform; the GLSL paths mirror its triangular weights. */
internal object HslBandMath {
    fun apply(red: Float, green: Float, blue: Float,
        bands: List<HslBandAdjustment>): Triple<Float, Float, Float> {
        if (bands.all { it == HslBandAdjustment() }) return Triple(red, green, blue)
        val boundedRed = red.coerceIn(0f, 1f)
        val boundedGreen = green.coerceIn(0f, 1f)
        val boundedBlue = blue.coerceIn(0f, 1f)
        val maximum = max(boundedRed, max(boundedGreen, boundedBlue))
        val minimum = min(boundedRed, min(boundedGreen, boundedBlue))
        val delta = maximum - minimum
        if (delta <= 0.000001f) return Triple(boundedRed, boundedGreen, boundedBlue)
        val lightness = (maximum + minimum) * 0.5f
        val saturation = delta / (1f - abs(2f * lightness - 1f)).coerceAtLeast(0.000001f)
        val rawHue = when {
            maximum == boundedRed -> ((boundedGreen - boundedBlue) / delta).let {
                if (it < 0f) it + 6f else it
            }
            maximum == boundedGreen -> (boundedBlue - boundedRed) / delta + 2f
            else -> (boundedRed - boundedGreen) / delta + 4f
        }
        val hueDegrees = rawHue * 60f
        var hueShift = 0f
        var saturationShift = 0f
        var lightnessShift = 0f
        bands.forEachIndexed { index, band ->
            val center = index * 60f
            val rawDistance = abs(hueDegrees - center)
            val distance = min(rawDistance, 360f - rawDistance)
            val weight = (1f - distance / 60f).coerceIn(0f, 1f)
            hueShift += band.hueDegrees * weight
            saturationShift += band.saturation * weight
            lightnessShift += band.lightness * weight
        }
        val adjustedSaturation = (saturation + saturationShift).coerceIn(0f, 1f)
        val adjustedLightness = (lightness + lightnessShift * 0.5f).coerceIn(0f, 1f)
        val hue = ((rawHue + hueShift / 60f) % 6f + 6f) % 6f
        val chroma = (1f - abs(2f * adjustedLightness - 1f)) * adjustedSaturation
        val secondary = chroma * (1f - abs(hue % 2f - 1f))
        val offset = adjustedLightness - chroma * 0.5f
        val rgb = when {
            hue < 1f -> Triple(chroma, secondary, 0f)
            hue < 2f -> Triple(secondary, chroma, 0f)
            hue < 3f -> Triple(0f, chroma, secondary)
            hue < 4f -> Triple(0f, secondary, chroma)
            hue < 5f -> Triple(secondary, 0f, chroma)
            else -> Triple(chroma, 0f, secondary)
        }
        return Triple(rgb.first + offset, rgb.second + offset, rgb.third + offset)
    }
}
