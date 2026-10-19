package au.parmtech.tongcut

import kotlin.math.roundToInt

/**
 * Media3 bitmap overlays are scaled from bitmap pixels, whereas the Compose preview
 * allocates a 42%-of-frame-width 16:9 PiP box and fits the image inside it.
 * Use the exact same content-fit geometry for export. This is pure Kotlin and
 * intentionally covered by JVM tests to prevent tiny-photo regressions.
 */
internal object PhotoOverlaySizing {
    const val PIP_WIDTH_FRACTION = 0.42f
    const val PREVIEW_BOX_ASPECT = 16f / 9f

    fun fitScale(bitmapWidth: Int, bitmapHeight: Int, videoWidth: Int): Float {
        require(bitmapWidth > 0 && bitmapHeight > 0 && videoWidth > 0)
        val boxWidth = videoWidth.toDouble() * PIP_WIDTH_FRACTION
        val boxHeight = boxWidth / PREVIEW_BOX_ASPECT
        val result = minOf(boxWidth / bitmapWidth, boxHeight / bitmapHeight).toFloat()
        require(result.isFinite() && result > 0f)
        return result
    }

    /**
     * Mirrors Presentation.createForShortSide for photo overlay sizing.
     * The exporter provides the rotated source-video aspect ratio for SOURCE canvas.
     */
    fun outputWidth(shortSidePixels: Int, finalAspectRatio: Float): Int {
        require(shortSidePixels in 144..4320)
        require(finalAspectRatio.isFinite() && finalAspectRatio in 0.1f..10f)
        return if (finalAspectRatio >= 1f) {
            (shortSidePixels.toDouble() * finalAspectRatio).roundToInt()
        } else shortSidePixels
    }
}
