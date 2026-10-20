package au.parmtech.tongcut

import kotlin.math.roundToInt

/**
 * CPU sampler for applying an imported 3D LUT to overlay pixels.
 *
 * The profile stores rows in .cube red-fastest order. Sampling is trilinear,
 * matching the continuous behavior expected from the GPU LUT texture, and the
 * original alpha channel is retained so transparent overlay edges stay clean.
 */
object ColorLutOverlayTransform {
    fun applyArgb(argb: Int, profile: ColorLutProfile): Int {
        val red = ((argb ushr 16) and 0xff) / 255f
        val green = ((argb ushr 8) and 0xff) / 255f
        val blue = (argb and 0xff) / 255f
        val size = profile.size
        val rPosition = red * (size - 1)
        val gPosition = green * (size - 1)
        val bPosition = blue * (size - 1)
        val r0 = rPosition.toInt()
        val g0 = gPosition.toInt()
        val b0 = bPosition.toInt()
        val r1 = (r0 + 1).coerceAtMost(size - 1)
        val g1 = (g0 + 1).coerceAtMost(size - 1)
        val b1 = (b0 + 1).coerceAtMost(size - 1)
        val rf = rPosition - r0
        val gf = gPosition - g0
        val bf = bPosition - b0

        fun channelAt(r: Int, g: Int, b: Int, shift: Int): Float {
            val index = r + size * (g + size * b)
            return ((profile.entriesArgb[index] ushr shift) and 0xff).toFloat()
        }

        fun sample(shift: Int): Int {
            var output = 0f
            for (ri in 0..1) for (gi in 0..1) for (bi in 0..1) {
                val r = if (ri == 0) r0 else r1
                val g = if (gi == 0) g0 else g1
                val b = if (bi == 0) b0 else b1
                val weight = (if (ri == 0) 1f - rf else rf) *
                    (if (gi == 0) 1f - gf else gf) *
                    (if (bi == 0) 1f - bf else bf)
                output += channelAt(r, g, b, shift) * weight
            }
            val original = when (shift) {
                16 -> red * 255f
                8 -> green * 255f
                else -> blue * 255f
            }
            return (original + (output - original) * profile.strength)
                .roundToInt().coerceIn(0, 255)
        }

        return (argb and -0x1000000) or (sample(16) shl 16) or
            (sample(8) shl 8) or sample(0)
    }
}
