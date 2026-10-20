package au.parmtech.tongcut

import kotlin.math.roundToInt

/**
 * Applies an imported SDR 3D LUT to one ARGB pixel using trilinear interpolation.
 *
 * Cube rows are stored red-fastest, matching [CubeLutParser] and [ColorLutProfile].
 * The profile strength blends the LUT result with the original RGB value; source
 * alpha is preserved. This CPU path is intended for bitmap/layer preview adapters.
 */
internal object ColorLutOverlayTransform {
    fun applyArgb(argb: Int, profile: ColorLutProfile): Int {
        if (profile.strength <= 0f) return argb

        val input = floatArrayOf(
            ((argb ushr 16) and 0xff) / 255f * (profile.size - 1),
            ((argb ushr 8) and 0xff) / 255f * (profile.size - 1),
            (argb and 0xff) / 255f * (profile.size - 1)
        )
        val low = IntArray(3) { input[it].toInt() }
        val high = IntArray(3) { (low[it] + 1).coerceAtMost(profile.size - 1) }
        val fraction = FloatArray(3) { input[it] - low[it] }

        fun channelAt(red: Int, green: Int, blue: Int, shift: Int): Int {
            val index = red + profile.size * (green + profile.size * blue)
            return (profile.entriesArgb[index] ushr shift) and 0xff
        }

        fun interpolate(shift: Int): Int {
            var result = 0f
            for (ri in 0..1) for (gi in 0..1) for (bi in 0..1) {
                val red = if (ri == 0) low[0] else high[0]
                val green = if (gi == 0) low[1] else high[1]
                val blue = if (bi == 0) low[2] else high[2]
                val weight = (if (ri == 0) 1f - fraction[0] else fraction[0]) *
                    (if (gi == 0) 1f - fraction[1] else fraction[1]) *
                    (if (bi == 0) 1f - fraction[2] else fraction[2])
                result += channelAt(red, green, blue, shift) * weight
            }
            return result.roundToInt().coerceIn(0, 255)
        }

        fun blend(shift: Int): Int {
            val original = (argb ushr shift) and 0xff
            val mapped = interpolate(shift)
            return (original + (mapped - original) * profile.strength)
                .roundToInt().coerceIn(0, 255)
        }

        return (argb and -0x1000000) or (blend(16) shl 16) or
            (blend(8) shl 8) or blend(0)
    }
}
