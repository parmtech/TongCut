package au.parmtech.tongcut

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * One trimmed source window from the ordinary primary video sequence.
 *
 * Source and timeline durations are both microseconds. This first transition
 * foundation is intentionally unit-speed; it never changes the source trim.
 */
data class PrimaryTransitionClip(
    val id: String,
    val sourceDurationUs: Long,
    val sourceInUs: Long = 0L,
    val sourceOutUs: Long = sourceDurationUs
) {
    init {
        require(id.isNotBlank() && id.length <= 100)
        require(sourceDurationUs >= MIN_CLIP_US)
        require(sourceInUs >= 0L && sourceOutUs > sourceInUs)
        require(sourceOutUs <= sourceDurationUs)
    }

    val durationUs: Long get() = sourceOutUs - sourceInUs

    companion object {
        const val MIN_CLIP_US = 100_000L
    }
}

/** A transition attached to the cut after [afterClipIndex] in the primary sequence. */
data class PrimaryTransitionSpec(
    val afterClipIndex: Int,
    val durationUs: Long,
    val kind: PrimaryTransitionKind = PrimaryTransitionKind.CROSS_DISSOLVE
) {
    init {
        require(afterClipIndex >= 0)
        require(durationUs in MIN_TRANSITION_US..MAX_TRANSITION_US)
    }

    companion object {
        const val MIN_TRANSITION_US = 100_000L
        const val MAX_TRANSITION_US = 3_000_000L
    }
}

enum class PrimaryTransitionKind { CROSS_DISSOLVE }

data class PrimaryClipPlacement(
    val clip: PrimaryTransitionClip,
    val timelineStartUs: Long
) {
    val timelineEndUs: Long get() = Math.addExact(timelineStartUs, clip.durationUs)

    /** Source clock at an active project time, preserving the selected source trim. */
    fun sourceAt(timelineUs: Long): Long {
        require(timelineUs in timelineStartUs until timelineEndUs)
        return Math.addExact(clip.sourceInUs, timelineUs - timelineStartUs)
    }
}

data class PrimaryTransitionWindow(
    val outgoingClipId: String,
    val incomingClipId: String,
    val startUs: Long,
    val endUs: Long,
    val kind: PrimaryTransitionKind
) {
    init {
        require(outgoingClipId != incomingClipId)
        require(startUs >= 0L && endUs > startUs)
    }

    val durationUs: Long get() = endUs - startUs

    /** Linear visual blend progress for a half-open transition interval. */
    fun progressAt(timelineUs: Long): Float {
        require(timelineUs in startUs until endUs)
        return ((timelineUs - startUs).toDouble() / durationUs).toFloat()
    }

    fun alphaAt(timelineUs: Long): Pair<Float, Float> {
        val incoming = progressAt(timelineUs)
        return (1f - incoming) to incoming
    }

    /** Equal-power audio gains avoid the level dip of a linear audio crossfade. */
    fun audioGainsAt(timelineUs: Long): Pair<Float, Float> {
        val angle = progressAt(timelineUs) * (PI / 2.0)
        return cos(angle).toFloat() to sin(angle).toFloat()
    }
}

/**
 * Pure schedule for transitions between consecutive primary-footage clips.
 *
 * This class does not alter EditDraft persistence or claim renderer integration.
 * It defines validated source/timeline windows for the future shared preview and
 * export path; secondary PiP/video overlay transitions remain a separate system.
 */
data class PrimaryTransitionPlan(
    val clips: List<PrimaryClipPlacement>,
    val transitions: List<PrimaryTransitionWindow>,
    val durationUs: Long
) {
    init {
        require(clips.isNotEmpty())
        require(durationUs == clips.last().timelineEndUs)
        require(clips.zipWithNext().all { (left, right) ->
            right.timelineStartUs <= left.timelineEndUs
        })
        require(transitions.all { it.endUs <= durationUs })
    }

    fun activeAt(timelineUs: Long): List<PrimaryClipPlacement> {
        if (timelineUs !in 0 until durationUs) return emptyList()
        return clips.filter { timelineUs >= it.timelineStartUs && timelineUs < it.timelineEndUs }
    }

    fun transitionAt(timelineUs: Long): PrimaryTransitionWindow? =
        transitions.firstOrNull { timelineUs >= it.startUs && timelineUs < it.endUs }

    companion object {
        private const val MIN_REMAINING_CLIP_US = 100_000L

        fun build(
            sourceClips: List<PrimaryTransitionClip>,
            specs: List<PrimaryTransitionSpec>
        ): PrimaryTransitionPlan {
            require(sourceClips.isNotEmpty()) { "Primary sequence must contain a clip" }
            require(sourceClips.map { it.id }.toSet().size == sourceClips.size) {
                "Primary clip identifiers must be unique"
            }
            require(specs.map { it.afterClipIndex }.toSet().size == specs.size) {
                "Only one transition can be attached to each cut"
            }
            val transitionsByCut = specs.associateBy { it.afterClipIndex }
            require(transitionsByCut.keys.all { it in 0 until sourceClips.lastIndex }) {
                "Transition must follow a clip with a next primary clip"
            }

            val incomingOverlap = LongArray(sourceClips.size)
            val outgoingOverlap = LongArray(sourceClips.size)
            specs.forEach { spec ->
                outgoingOverlap[spec.afterClipIndex] = spec.durationUs
                incomingOverlap[spec.afterClipIndex + 1] = spec.durationUs
            }
            sourceClips.forEachIndexed { index, clip ->
                val used = Math.addExact(incomingOverlap[index], outgoingOverlap[index])
                require(used <= clip.durationUs - MIN_REMAINING_CLIP_US) {
                    "Adjacent transitions must leave at least 0.1 second of each clip"
                }
            }

            val placements = ArrayList<PrimaryClipPlacement>(sourceClips.size)
            var nextStartUs = 0L
            sourceClips.forEachIndexed { index, clip ->
                val placement = PrimaryClipPlacement(clip, nextStartUs)
                placements += placement
                if (index < sourceClips.lastIndex) {
                    val overlapUs = outgoingOverlap[index]
                    nextStartUs = Math.subtractExact(placement.timelineEndUs, overlapUs)
                }
            }
            val windows = specs.sortedBy { it.afterClipIndex }.map { spec ->
                val outgoing = placements[spec.afterClipIndex]
                val incoming = placements[spec.afterClipIndex + 1]
                PrimaryTransitionWindow(
                    outgoingClipId = outgoing.clip.id,
                    incomingClipId = incoming.clip.id,
                    startUs = incoming.timelineStartUs,
                    endUs = outgoing.timelineEndUs,
                    kind = spec.kind
                ).also {
                    require(it.durationUs == spec.durationUs) {
                        "Transition schedule does not match requested overlap"
                    }
                }
            }
            return PrimaryTransitionPlan(
                clips = placements,
                transitions = windows,
                durationUs = placements.last().timelineEndUs
            )
        }
    }
}
