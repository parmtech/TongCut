package au.parmtech.tongcut

/** Presets use step changes, not fake smooth curves, to match the actual export engine. */
enum class SpeedRhythmPreset(val label: String) {
    HERO("Hero · slow → fast → slow"),
    PULSE("Pulse · 1× / 2×"),
    RUSH("Rush · accelerate"),
    DREAM("Dream · slow middle"),
    RESET("Reset · 1×")
}

object SpeedRhythmPresets {
    fun build(sourceSpanUs: Long, preset: SpeedRhythmPreset): List<SpeedKeyframe> {
        require(sourceSpanUs >= 4_000L && sourceSpanUs % 1_000L == 0L) {
            "Preset needs at least four milliseconds of source footage"
        }
        val durationMs = sourceSpanUs / 1_000L
        val pattern: List<Pair<Int, Float>> = when (preset) {
            SpeedRhythmPreset.HERO -> listOf(0 to 0.5f, 30 to 2f, 70 to 0.5f)
            SpeedRhythmPreset.PULSE -> listOf(0 to 1f, 25 to 2f, 50 to 1f, 75 to 2f)
            SpeedRhythmPreset.RUSH -> listOf(0 to 0.5f, 25 to 1f, 50 to 2f, 75 to 4f)
            SpeedRhythmPreset.DREAM -> listOf(0 to 1f, 30 to 0.5f, 70 to 1f)
            SpeedRhythmPreset.RESET -> listOf(0 to 1f)
        }
        val points = pattern.map { (percent, speed) ->
            val ms = durationMs * percent / 100L
            SpeedKeyframe(ms * 1_000L, speed)
        }.distinctBy { it.sourceOffsetUs }
        SpeedRamp.validate(sourceSpanUs, points)
        return points
    }
}
