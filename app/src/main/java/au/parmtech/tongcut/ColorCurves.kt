package au.parmtech.tongcut

import kotlin.math.roundToInt

internal val CURVE_CHANNEL_NAMES = listOf("Master", "Red", "Green", "Blue")

data class CurvePoint(val x: Float, val y: Float) {
    init { require(x.isFinite() && x in 0f..1f && y.isFinite() && y in 0f..1f) }
}

/** Piecewise-linear curve with stable black/white endpoints and at most six authored knots. */
data class ToneCurve(
    val points: List<CurvePoint> = listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f))
) {
    init {
        require(points.size in 2..8)
        require(points.first().x == 0f && points.last().x == 1f) {
            "Tone curves must retain black and white endpoints"
        }
        require(points.zipWithNext().all { (left, right) -> left.x < right.x }) {
            "Tone curve inputs must increase strictly"
        }
    }

    fun map(value: Float): Float {
        val x = value.coerceIn(0f, 1f)
        for (index in 0 until points.lastIndex) {
            val left = points[index]
            val right = points[index + 1]
            if (x <= right.x) {
                val progress = (x - left.x) / (right.x - left.x)
                return left.y + (right.y - left.y) * progress
            }
        }
        return points.last().y
    }

    /** Insert after the closest segment or move an interior point without crossing neighbors. */
    fun withPoint(index: Int, point: CurvePoint): ToneCurve {
        require(index in 1 until points.lastIndex)
        val leftX = points[index - 1].x
        val rightX = points[index + 1].x
        val padding = minOf(0.005f, (rightX - leftX) * 0.25f)
        val left = leftX + padding
        val right = rightX - padding
        require(left <= right) { "Tone curve knots are too close to adjust" }
        val next = points.toMutableList()
        next[index] = point.copy(x = point.x.coerceIn(left, right))
        return copy(points = next)
    }

    fun addMidpoint(afterIndex: Int): ToneCurve {
        require(afterIndex in 0 until points.lastIndex)
        require(points.size < 8)
        val a = points[afterIndex]
        val b = points[afterIndex + 1]
        val next = points.toMutableList().apply {
            add(afterIndex + 1, CurvePoint((a.x + b.x) * 0.5f, (a.y + b.y) * 0.5f))
        }
        return copy(points = next)
    }

    fun removePoint(index: Int): ToneCurve {
        require(index in 1 until points.lastIndex)
        return copy(points = points.toMutableList().apply { removeAt(index) })
    }
}

data class ColorCurves(
    val master: ToneCurve = ToneCurve(),
    val red: ToneCurve = ToneCurve(),
    val green: ToneCurve = ToneCurve(),
    val blue: ToneCurve = ToneCurve()
) {
    fun curve(channel: Int): ToneCurve = when (channel) {
        0 -> master
        1 -> red
        2 -> green
        3 -> blue
        else -> throw IllegalArgumentException("Curve channel must be 0..3")
    }

    fun withCurve(channel: Int, curve: ToneCurve): ColorCurves = when (channel) {
        0 -> copy(master = curve)
        1 -> copy(red = curve)
        2 -> copy(green = curve)
        3 -> copy(blue = curve)
        else -> throw IllegalArgumentException("Curve channel must be 0..3")
    }

    fun apply(redValue: Float, greenValue: Float, blueValue: Float): Triple<Float, Float, Float> {
        val masterRed = master.map(redValue)
        val masterGreen = master.map(greenValue)
        val masterBlue = master.map(blueValue)
        return Triple(red.map(masterRed), green.map(masterGreen), blue.map(masterBlue))
    }

    /** 256-entry RGBA LUT: each channel composes master then its own curve. */
    fun rgbaLut(): ByteArray = ByteArray(256 * 4).also { bytes ->
        for (index in 0..255) {
            val input = index / 255f
            val offset = index * 4
            bytes[offset] = (red.map(master.map(input)) * 255f).roundToInt().toByte()
            bytes[offset + 1] = (green.map(master.map(input)) * 255f).roundToInt().toByte()
            bytes[offset + 2] = (blue.map(master.map(input)) * 255f).roundToInt().toByte()
            bytes[offset + 3] = 255.toByte()
        }
    }
}
