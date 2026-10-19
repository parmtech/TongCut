package au.parmtech.tongcut

import android.net.Uri

/** All times are source-media milliseconds; end is exclusive. */
data class Clip(
    val uri: Uri,
    val label: String,
    val durationMs: Long,
    val startMs: Long = 0,
    val endMs: Long = durationMs,
    val removeAudio: Boolean = false
) {
    init {
        require(durationMs > 0) { "Video duration must be positive" }
        require(TrimWindow(startMs, endMs, durationMs).isValid()) { "Invalid clip trim" }
    }
    val trimmedDurationMs: Long get() = endMs - startMs
    fun trimStart(ms: Long): Clip = copy(startMs = ms.coerceIn(0, endMs - 100))
    fun trimEnd(ms: Long): Clip = copy(endMs = ms.coerceIn(startMs + 100, durationMs))
}

data class TrimWindow(val startMs: Long, val endMs: Long, val durationMs: Long) {
    fun isValid(): Boolean = durationMs >= 100 && startMs >= 0 &&
        endMs <= durationMs && endMs - startMs >= 100
}

enum class Look(val label: String) { ORIGINAL("Original"), MONO("Mono"), INVERT("Invert"), WARM("Warm"), COOL("Cool") }
enum class CanvasRatio(val label: String, val ratio: Float?) {
    SOURCE("Source", null), WIDE("16:9", 16f / 9f), PORTRAIT("9:16", 9f / 16f), SQUARE("1:1", 1f)
}

data class ExportOptions(
    val look: Look = Look.ORIGINAL,
    val ratio: CanvasRatio = CanvasRatio.SOURCE,
    val shortSidePixels: Int = 1080,
    val mute: Boolean = false,
    val colorGrade: ColorGrade = ColorGrade(),
    val videoCodec: ExportVideoCodec = ExportVideoCodec.H264,
    val videoBitrateBps: Int = 8_000_000,
    val audioBitrateBps: Int = 128_000,
    /** Maximum output frame rate; never up-converts slower source video. */
    val maxFrameRate: Int = ExportFrameRate.DEFAULT
) {
    init {
        require(videoBitrateBps in 1_000_000..50_000_000)
        require(audioBitrateBps in 64_000..320_000)
        require(maxFrameRate in ExportFrameRate.choices)
    }
}

/** User-facing CFR ceilings supported by the current Media3 item pipeline. */
object ExportFrameRate {
    const val DEFAULT = 30
    val choices = listOf(24, 30, 60)
}

fun Long.asTimestamp(): String {
    val total = this.coerceAtLeast(0) / 1000
    return "%02d:%02d".format(total / 60, total % 60)
}
