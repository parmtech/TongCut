package au.parmtech.tongcut

/** Deterministic, clip-relative text entrance poses shared by preview and export. */
data class CaptionEntrancePose(
    val alpha: Float = 1f,
    val scale: Float = 1f,
    /** Normalized frame-up offset, added to authored motion and placement. */
    val y: Float = 0f
)

object CaptionEntranceMotion {
    const val DURATION_US = 300_000L

    fun at(preset: CaptionEntrance, relativeUs: Long, layerDurationUs: Long): CaptionEntrancePose {
        require(relativeUs >= 0L && layerDurationUs > 0L)
        if (preset == CaptionEntrance.NONE) return CaptionEntrancePose()
        val duration = minOf(DURATION_US, layerDurationUs)
        val progress = (relativeUs.coerceAtMost(layerDurationUs).toFloat() / duration)
            .coerceIn(0f, 1f)
        val eased = progress * progress * (3f - 2f * progress)
        return when (preset) {
            CaptionEntrance.NONE -> CaptionEntrancePose()
            CaptionEntrance.FADE -> CaptionEntrancePose(alpha = eased)
            CaptionEntrance.POP -> CaptionEntrancePose(alpha = eased, scale = 0.78f + 0.22f * eased)
            CaptionEntrance.RISE -> CaptionEntrancePose(\n                alpha = eased, y = if (eased >= 1f) 0f else -0.16f * (1f - eased)\n            )
        }
    }
}
