package au.parmtech.tongcut

/**
 * Dialogue-priority automation shared by monitor and Media3 export. A marked
 * voice/priority track gently attenuates other audio lanes around its clips.
 */
object AudioDucking {
    const val DUCK_GAIN = 0.25f
    const val ATTACK_US = 120_000L
    const val RELEASE_US = 260_000L

    data class Window(val startUs: Long, val endUs: Long) {
        init { require(startUs >= 0L && endUs > startUs) }
    }

    fun sourceWindows(timeline: LayerTimeline): List<Window> {
        val activeSourceTracks = timeline.tracks.asSequence()
            .filter { it.kind == LayerKind.AUDIO && it.duckingSource && !it.muted }
            .map { it.id }.toSet()
        val ordered = timeline.layers.asSequence()
            .filter { it.kind == LayerKind.AUDIO && it.trackId in activeSourceTracks }
            .map { Window(it.startUs, it.endUs) }
            .sortedBy { it.startUs }
            .toList()
        if (ordered.isEmpty()) return emptyList()

        // Merge talking pauses shorter than the attack+release envelope so music
        // does not pump back up between adjacent dialogue edits.
        val merged = ArrayList<Window>()
        var start = ordered.first().startUs
        var end = ordered.first().endUs
        for (next in ordered.drop(1)) {
            val joinBefore = if (end > Long.MAX_VALUE - ATTACK_US - RELEASE_US)
                Long.MAX_VALUE else end + ATTACK_US + RELEASE_US
            if (next.startUs <= joinBefore) end = maxOf(end, next.endUs)
            else {
                merged += Window(start, end)
                start = next.startUs
                end = next.endUs
            }
        }
        merged += Window(start, end)
        return merged
    }

    /** Linear gain at an absolute project-clock position. */
    fun gainAt(projectUs: Long, windows: List<Window>): Float {
        if (windows.isEmpty()) return 1f
        val time = projectUs.coerceAtLeast(0L)
        for (window in windows) {
            val attackStart = (window.startUs - ATTACK_US).coerceAtLeast(0L)
            val releaseEnd = if (window.endUs > Long.MAX_VALUE - RELEASE_US)
                Long.MAX_VALUE else window.endUs + RELEASE_US
            when {
                time < attackStart -> return 1f
                time < window.startUs -> {
                    val span = window.startUs - attackStart
                    if (span <= 0L) return DUCK_GAIN
                    val progress = (time - attackStart).toFloat() / span
                    return 1f + (DUCK_GAIN - 1f) * progress
                }
                time < window.endUs -> return DUCK_GAIN
                time < releaseEnd -> {
                    val span = releaseEnd - window.endUs
                    if (span <= 0L) return 1f
                    val progress = (time - window.endUs).toFloat() / span
                    return DUCK_GAIN + (1f - DUCK_GAIN) * progress
                }
            }
        }
        return 1f
    }

    /**
     * Build the clip-local envelope consumed by both monitor and export. Existing
     * manual automation is multiplied by the duck curve. Midpoints bound the
     * linear processor's approximation of that product; excessive dialogue
     * regions fail clearly instead of silently truncating automation.
     */
    fun envelopeFor(
        layer: TimelineLayer,
        windows: List<Window>
    ): List<AudioGainKeyframe> {
        if (windows.isEmpty()) return layer.audioEnvelope
        // Envelope points use whole milliseconds even when the timeline is
        // frame-snapped at a rational rate (for example 30,000/1,001 FPS).
        val maxAlignedTime = layer.durationUs / 1_000L * 1_000L
        val points = sortedSetOf(0L, maxAlignedTime)
        layer.audioEnvelope.forEach { points += it.timeUs }
        val clipStart = layer.startUs
        val clipEnd = layer.endUs
        fun local(projectUs: Long): Long {
            val raw = (projectUs - clipStart).coerceIn(0L, layer.durationUs)
            val wholeMs = raw / 1_000L
            val nearestMs = if (raw % 1_000L >= 500L) wholeMs + 1L else wholeMs
            return minOf(nearestMs, layer.durationUs / 1_000L) * 1_000L
        }
        windows.forEach { window ->
            val attack = (window.startUs - ATTACK_US).coerceAtLeast(0L)
            val release = if (window.endUs > Long.MAX_VALUE - RELEASE_US)
                Long.MAX_VALUE else window.endUs + RELEASE_US
            listOf(attack, window.startUs, window.endUs, release).forEach { time ->
                if (time in clipStart..clipEnd) points += local(time)
            }
            val attackMid = attack + (window.startUs - attack) / 2L
            val releaseMid = window.endUs + (release - window.endUs) / 2L
            listOf(attackMid, releaseMid).forEach { time ->
                if (time in clipStart..clipEnd) points += local(time)
            }
        }
        require(points.size <= AudioEnvelope.MAX_POINTS) {
            "Auto ducking creates too many automation points for ${layer.id}; combine nearby dialogue clips or disable ducking on a source track"
        }
        return points.map { time ->
            val authoredGain = AudioEnvelope.gainAt(time, layer.durationUs, layer.audioEnvelope)
            AudioGainKeyframe(time, authoredGain * gainAt(clipStart + time, windows))
        }
    }
}
