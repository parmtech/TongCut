package au.parmtech.tongcut

/**
 * Source-independent canonical clock for the consecutive (video-1) track.
 * The position is on the edited timeline, not on any source file timeline.
 * All internal arithmetic uses Long milliseconds until the track engine migrates to
 * rational frame/timebase units. Do not claim frame accuracy for this revision.
 */
data class TimelinePosition(
    val clipIndex: Int,
    val offsetMs: Long,
    val sourceMs: Long,
    val timelineMs: Long
)

data class ReverseTimelineTarget(
    val clipIndex: Int,
    val clip: DraftClip,
    val startMs: Long,
    val localMs: Long
)

class SequentialTimeline(
    val clips: List<DraftClip>,
    transitions: List<PrimaryTransitionSpec> = emptyList()
) {
    private val offsets: List<Long>
    val totalMs: Long

    val transitionPlan: PrimaryTransitionPlan? = if (clips.isEmpty()) null else
        PrimaryTransitionPlan.build(
            clips.map { clip ->
                PrimaryTransitionClip(
                    id = clip.id,
                    sourceDurationUs = clip.durationMs * 1_000L,
                    sourceInUs = clip.inMs * 1_000L,
                    sourceOutUs = clip.outMs * 1_000L
                )
            },
            transitions
        )

    init {
        offsets = transitionPlan?.clips?.map { it.timelineStartUs / 1_000L } ?: emptyList()
        totalMs = (transitionPlan?.durationUs ?: 0L) / 1_000L
    }

    fun startOf(index: Int): Long = offsets[index]

    fun pointAt(timeMs: Long): TimelinePosition? {
        if (clips.isEmpty()) return null
        val t = timeMs.coerceIn(0L, totalMs)
        // Exact boundaries belong to the next clip (except end-of-timeline).
        val index = if (t == totalMs) clips.lastIndex else {
            var low = 0
            var high = clips.lastIndex
            var answer = 0
            while (low <= high) {
                val mid = (low + high) ushr 1
                if (offsets[mid] <= t) {
                    answer = mid
                    low = mid + 1
                } else high = mid - 1
            }
            answer
        }
        val clip = clips[index]
        val local = (t - offsets[index]).coerceIn(0L, clip.editedDurationMs)
        return TimelinePosition(index, local, clip.inMs + local, t)
    }

    /** Reverse preview follows the playhead; clip selection remains an inspector concern. */
    fun reverseTargetAt(timeMs: Long): ReverseTimelineTarget? {
        val point = pointAt(timeMs) ?: return null
        val clip = clips[point.clipIndex]
        if (!clip.reverseVideo || point.offsetMs >= clip.editedDurationMs) return null
        return ReverseTimelineTarget(
            clipIndex = point.clipIndex,
            clip = clip,
            startMs = startOf(point.clipIndex),
            localMs = point.offsetMs
        )
    }

    fun timeOf(index: Int, localMs: Long): Long {
        val clip = clips[index]
        return offsets[index] + localMs.coerceIn(0L, clip.editedDurationMs)
    }

    fun sourceTimeOf(index: Int, sourceMs: Long): Long {
        val clip = clips[index]
        return timeOf(index, sourceMs.coerceIn(clip.inMs, clip.outMs) - clip.inMs)
    }
}
