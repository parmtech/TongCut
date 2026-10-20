package au.parmtech.tongcut.motion

/**
 * Stable clip-edge identity. Unlike a list index, this key survives reorder and display changes.
 * IDs are kept as opaque project identifiers and encoded length-prefixed to avoid delimiter
 * collisions (for example IDs containing ':' or '->').
 */
data class PrimaryTransitionBoundary(
    val outgoingClipId: String,
    val incomingClipId: String
) {
    init {
        require(outgoingClipId.isNotBlank() && incomingClipId.isNotBlank())
        require(outgoingClipId.length <= MAX_CLIP_ID_LENGTH && incomingClipId.length <= MAX_CLIP_ID_LENGTH)
        require(outgoingClipId != incomingClipId) { "A transition boundary needs two different clips" }
    }

    val stableKey: String
        get() = "${outgoingClipId.length}:$outgoingClipId${incomingClipId.length}:$incomingClipId"

    companion object {
        const val MAX_CLIP_ID_LENGTH = 128
    }
}

enum class PrimaryTransitionKind(val id: String) {
    CROSS_DISSOLVE("cross-dissolve-v1")
}

enum class PrimaryTransitionCurve(val id: String) {
    LINEAR("linear-v1"),
    SMOOTHSTEP("smoothstep-v1");

    fun map(progress: Float): Float {
        val t = progress.coerceIn(0f, 1f)
        return when (this) {
            LINEAR -> t
            SMOOTHSTEP -> t * t * (3f - 2f * t)
        }
    }
}

/** User-authored boundary config. Defaults are neutral: no config means a hard cut. */
data class PrimaryTransitionConfig(
    val boundary: PrimaryTransitionBoundary,
    val durationMs: Long = DEFAULT_DURATION_MS,
    val kind: PrimaryTransitionKind = PrimaryTransitionKind.CROSS_DISSOLVE,
    val curve: PrimaryTransitionCurve = PrimaryTransitionCurve.SMOOTHSTEP
) {
    init {
        require(durationMs in MIN_DURATION_MS..MAX_DURATION_MS) {
            "Transition duration must be between $MIN_DURATION_MS and $MAX_DURATION_MS ms"
        }
    }

    companion object {
        const val MIN_DURATION_MS = 100L
        const val DEFAULT_DURATION_MS = 500L
        const val MAX_DURATION_MS = 3_000L
    }
}

/**
 * Source-clock and project-clock facts for one trimmed primary clip at normal forward speed.
 * timelineStart and selected source interval define the ordinary hard-cut sequence.
 */
data class PrimaryClipTiming(
    val clipId: String,
    val timelineStartMs: Long,
    val sourceDurationMs: Long,
    val sourceInMs: Long,
    val sourceOutMs: Long
) {
    init {
        require(clipId.isNotBlank() && clipId.length <= PrimaryTransitionBoundary.MAX_CLIP_ID_LENGTH)
        require(timelineStartMs >= 0L)
        require(sourceDurationMs > 0L)
        require(sourceInMs >= 0L && sourceOutMs > sourceInMs && sourceOutMs <= sourceDurationMs)
        require(runCatching { Math.addExact(timelineStartMs, selectedDurationMs) }.isSuccess) {
            "Clip project end overflows the supported clock"
        }
    }

    val selectedDurationMs: Long get() = sourceOutMs - sourceInMs
    val timelineEndMs: Long get() = Math.addExact(timelineStartMs, selectedDurationMs)
    val availableHeadHandleMs: Long get() = sourceInMs
    val availableTailHandleMs: Long get() = sourceDurationMs - sourceOutMs
}

/** Source samples used by both visual inputs at one project-clock timestamp. */
data class PrimaryTransitionSample(
    val projectTimeMs: Long,
    val outgoingSourceTimeMs: Long,
    val incomingSourceTimeMs: Long,
    val incomingMix: Float
) {
    val outgoingMix: Float get() = 1f - incomingMix
}

/**
 * Math-only plan for a full-frame cross-dissolve centered on an existing hard cut.
 *
 * The transition borrows source handles outside each clip's selected trim: the incoming clip's
 * head handle plays before the cut, and the outgoing clip's tail handle plays after it. The
 * ordinary clip body and every later project timestamp stay fixed, so project duration delta is
 * exactly zero. This plan does not decode, composite, persist, or render media.
 */
class PrimaryTransitionPlan private constructor(
    val config: PrimaryTransitionConfig,
    val cutTimeMs: Long,
    val transitionStartMs: Long,
    val transitionEndMs: Long,
    val incomingHeadUsedMs: Long,
    val outgoingTailUsedMs: Long,
    val outgoingSourceStartMs: Long,
    val incomingSourceStartMs: Long,
    val projectDurationBeforeMs: Long
) {
    val durationMs: Long get() = config.durationMs
    val projectDurationAfterMs: Long get() = projectDurationBeforeMs
    val projectDurationDeltaMs: Long get() = 0L

    /** Returns the two source clocks and eased mix weights for an in-window project time. */
    fun sampleAt(projectTimeMs: Long): PrimaryTransitionSample {
        require(projectTimeMs in transitionStartMs..transitionEndMs) {
            "Sample time must be inside the primary transition window"
        }
        val elapsedMs = projectTimeMs - transitionStartMs
        val progress = elapsedMs.toFloat() / durationMs.toFloat()
        return PrimaryTransitionSample(
            projectTimeMs = projectTimeMs,
            outgoingSourceTimeMs = Math.addExact(outgoingSourceStartMs, elapsedMs),
            incomingSourceTimeMs = Math.addExact(incomingSourceStartMs, elapsedMs),
            incomingMix = config.curve.map(progress)
        )
    }

    companion object {
        /**
         * Plan a symmetric-duration window around two adjacent clips.
         * Odd millisecond durations allocate the extra millisecond to the outgoing tail.
         */
        fun create(
            outgoing: PrimaryClipTiming,
            incoming: PrimaryClipTiming,
            config: PrimaryTransitionConfig,
            projectDurationMs: Long
        ): PrimaryTransitionPlan {
            require(projectDurationMs >= 0L)
            require(outgoing.clipId == config.boundary.outgoingClipId &&
                incoming.clipId == config.boundary.incomingClipId) {
                "Transition boundary no longer matches the adjacent clip IDs"
            }
            require(outgoing.timelineEndMs == incoming.timelineStartMs) {
                "Primary clips must meet at a hard cut with no timeline gap or overlap"
            }
            require(incoming.timelineEndMs <= projectDurationMs) {
                "Incoming clip exceeds the project duration"
            }
            val cut = outgoing.timelineEndMs
            val head = config.durationMs / 2L
            val tail = config.durationMs - head
            require(outgoing.selectedDurationMs >= head && incoming.selectedDurationMs >= tail) {
                "Each selected clip needs enough body to cover its side of the dissolve"
            }
            require(incoming.availableHeadHandleMs >= head) {
                "Incoming clip lacks source media before its selected in-point"
            }
            require(outgoing.availableTailHandleMs >= tail) {
                "Outgoing clip lacks source media after its selected out-point"
            }
            val start = Math.subtractExact(cut, head)
            val end = Math.addExact(cut, tail)
            require(start >= 0L && end <= projectDurationMs) {
                "Transition handle window would escape the project clock"
            }

            return PrimaryTransitionPlan(
                config = config,
                cutTimeMs = cut,
                transitionStartMs = start,
                transitionEndMs = end,
                incomingHeadUsedMs = head,
                outgoingTailUsedMs = tail,
                outgoingSourceStartMs = outgoing.sourceOutMs - head,
                incomingSourceStartMs = incoming.sourceInMs - head,
                projectDurationBeforeMs = projectDurationMs
            )
        }
    }
}
