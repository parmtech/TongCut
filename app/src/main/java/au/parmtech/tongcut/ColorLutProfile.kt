package au.parmtech.tongcut

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/** Bounded, self-contained 3D .cube LUT; RGB entries use the file's red-fastest order. */
data class ColorLutProfile(
    val name: String,
    val size: Int,
    val entriesArgb: List<Int>,
    val strength: Float = 1f
) {
    init {
        require(name.isNotBlank() && name.length <= 120)
        require(size in MIN_SIZE..MAX_SIZE)
        require(entriesArgb.size == size * size * size)
        require(strength.isFinite() && strength in 0f..1f)
    }

    /** Media3 indexes cube values as cube[R][G][B]. Intensity is baked for shared rendering. */
    fun toCube(): Array<Array<IntArray>> {
        val cube = Array(size) { Array(size) { IntArray(size) } }
        for (blue in 0 until size) for (green in 0 until size) for (red in 0 until size) {
            val fileIndex = red + size * (green + size * blue)
            val lut = entriesArgb[fileIndex]
            val identityR = (red * 255f / (size - 1)).roundToInt()
            val identityG = (green * 255f / (size - 1)).roundToInt()
            val identityB = (blue * 255f / (size - 1)).roundToInt()
            fun blend(shift: Int, identity: Int): Int {
                val output = (lut ushr shift) and 0xff
                return (identity + (output - identity) * strength).roundToInt().coerceIn(0, 255)
            }
            cube[red][green][blue] = (0xff shl 24) or
                (blend(16, identityR) shl 16) or
                (blend(8, identityG) shl 8) or blend(0, identityB)
        }
        return cube
    }

    companion object {
        const val MIN_SIZE = 2
        const val MAX_SIZE = 33
        const val MAX_TEXT_CHARS = 2 * 1024 * 1024
        const val MAX_ENTRIES = MAX_SIZE * MAX_SIZE * MAX_SIZE
    }
}

/** Strict, local-only parser for the portable .cube 3D LUT subset. */
object CubeLutParser {
    fun parse(filename: String, text: String): ColorLutProfile {
        require(text.length <= ColorLutProfile.MAX_TEXT_CHARS) { "LUT file exceeds 2 MB" }
        var size: Int? = null
        var title: String? = null
        var domainMin = floatArrayOf(0f, 0f, 0f)
        var domainMax = floatArrayOf(1f, 1f, 1f)
        var sawDomainMin = false
        var sawDomainMax = false
        val values = ArrayList<FloatArray>()

        text.lineSequence().forEachIndexed { lineIndex, raw ->
            require(lineIndex <= ColorLutProfile.MAX_ENTRIES + 32) { "LUT has too many lines" }
            val line = raw.substringBefore('#').trim().removePrefix("\uFEFF").trim()
            if (line.isEmpty()) return@forEachIndexed
            val parts = line.split(Regex("\\s+"))
            when (parts[0].uppercase()) {
                "TITLE" -> {
                    require(parts.size >= 2) { "TITLE value is missing" }
                    title = line.substringAfter(parts[0]).trim().trim('"').take(120)
                }
                "LUT_3D_SIZE" -> {
                    require(size == null && parts.size == 2) { "Invalid LUT_3D_SIZE declaration" }
                    val parsed = parts[1].toIntOrNull()
                        ?: throw IllegalArgumentException("Invalid 3D LUT size")
                    require(parsed in ColorLutProfile.MIN_SIZE..ColorLutProfile.MAX_SIZE) {
                                "3D LUT size must be between ${ColorLutProfile.MIN_SIZE} and ${ColorLutProfile.MAX_SIZE}"
                    }
                    size = parsed
                }
                "DOMAIN_MIN", "DOMAIN_MAX" -> {
                    require(parts.size == 4) { "${parts[0]} needs three channel values" }
                    val vector = FloatArray(3) { channel ->
                        parts[channel + 1].toFloatOrNull()?.also {
                            require(it.isFinite()) { "Domain values must be finite" }
                        } ?: throw IllegalArgumentException("Invalid LUT domain value")
                    }
                    if (parts[0].equals("DOMAIN_MIN", ignoreCase = true)) {
                        domainMin = vector
                        sawDomainMin = true
                    } else {
                        domainMax = vector
                        sawDomainMax = true
                    }
                }
                "LUT_1D_SIZE" -> throw IllegalArgumentException("1D LUTs are not supported; import a 3D .cube LUT")
                else -> {
                    require(size != null) { "LUT_3D_SIZE must appear before color values" }
                    require(parts.size == 3) { "Invalid LUT color at line ${lineIndex + 1}" }
                    require(values.size < ColorLutProfile.MAX_ENTRIES) { "LUT has too many color entries" }
                    values += FloatArray(3) { channel ->
                        parts[channel].toFloatOrNull()?.also {
                            require(it.isFinite() && it in -64f..64f) {
                                "LUT values must be finite and within -64..64"
                            }
                        } ?: throw IllegalArgumentException("Invalid LUT color at line ${lineIndex + 1}")
                    }
                }
            }
        }

        val cubeSize = requireNotNull(size) { "Missing LUT_3D_SIZE declaration" }
        require(sawDomainMin == sawDomainMax) { "DOMAIN_MIN and DOMAIN_MAX must be supplied together" }
        require((0..2).all { domainMax[it] > domainMin[it] }) { "Each LUT domain maximum must exceed its minimum" }
        require(values.size == cubeSize * cubeSize * cubeSize) {
            "Expected ${cubeSize * cubeSize * cubeSize} LUT colors; found ${values.size}"
        }
        val outputs = values.map { value ->
            IntArray(3) { channel -> (value[channel].coerceIn(0f, 1f) * 255f).roundToInt() }
        }
        // DOMAIN_MIN/MAX describe the input coordinates. Resample the source cube onto
        // Media3's canonical [0, 1] grid so the same profile is honored by preview/export.
        fun sample(red: Float, green: Float, blue: Float, channel: Int): Int {
            val input = floatArrayOf(red, green, blue)
            val mapped = FloatArray(3) { axis ->
                ((input[axis] - domainMin[axis]) / (domainMax[axis] - domainMin[axis]))
                    .coerceIn(0f, 1f) * (cubeSize - 1)
            }
            val low = IntArray(3) { mapped[it].toInt() }
            val high = IntArray(3) { (low[it] + 1).coerceAtMost(cubeSize - 1) }
            val fraction = FloatArray(3) { mapped[it] - low[it] }
            fun at(r: Int, g: Int, b: Int): Int = outputs[r + cubeSize * (g + cubeSize * b)][channel]
            var result = 0f
            for (ri in 0..1) for (gi in 0..1) for (bi in 0..1) {
                val r = if (ri == 0) low[0] else high[0]
                val g = if (gi == 0) low[1] else high[1]
                val b = if (bi == 0) low[2] else high[2]
                val weight = (if (ri == 0) 1f - fraction[0] else fraction[0]) *
                    (if (gi == 0) 1f - fraction[1] else fraction[1]) *
                    (if (bi == 0) 1f - fraction[2] else fraction[2])
                result += at(r, g, b) * weight
            }
            return result.roundToInt().coerceIn(0, 255)
        }
        val colors = buildList(cubeSize * cubeSize * cubeSize) {
            for (blue in 0 until cubeSize) for (green in 0 until cubeSize) for (red in 0 until cubeSize) {
                val r = red.toFloat() / (cubeSize - 1)
                val g = green.toFloat() / (cubeSize - 1)
                val b = blue.toFloat() / (cubeSize - 1)
                val rr = sample(r, g, b, 0)
                val gg = sample(r, g, b, 1)
                val bb = sample(r, g, b, 2)
                add((0xff shl 24) or (rr shl 16) or (gg shl 8) or bb)
            }
        }
        val fallbackName = filename.substringAfterLast('/').substringBeforeLast('.')
            .ifBlank { "Imported LUT" }.take(120)
        return ColorLutProfile(title?.takeIf { it.isNotBlank() } ?: fallbackName, cubeSize, colors)
    }
}

object ColorLutProfileCodec {
    fun encode(profile: ColorLutProfile): JSONObject = JSONObject().apply {
        put("name", profile.name)
        put("size", profile.size)
        put("strength", profile.strength.toDouble())
        put("entries", JSONArray().apply { profile.entriesArgb.forEach(::put) })
    }

    fun decode(value: JSONObject?): ColorLutProfile? {
        if (value == null) return null
        return runCatching {
            val size = value.getInt("size")
            require(size in ColorLutProfile.MIN_SIZE..ColorLutProfile.MAX_SIZE)
            val entries = value.getJSONArray("entries")
            require(entries.length() == size * size * size)
            ColorLutProfile(
                name = value.optString("name", "Imported LUT").take(120),
                size = size,
                entriesArgb = (0 until entries.length()).map { entries.getInt(it) },
                strength = value.optDouble("strength", 1.0).toFloat()
            )
        }.getOrNull()
    }
}
