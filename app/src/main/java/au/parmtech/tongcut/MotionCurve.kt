package au.parmtech.tongcut

import kotlin.math.PI
import kotlin.math.cos

/**
 * P3 immutable keyframe motion engine. Coordinates are normalized viewport offsets
 * and are independent of image resolution or device density.
 */
enum class MotionInterpolation { LINEAR, SMOOTH, HOLD }

data class MotionPose(
    val x: Float = 0f,
    val y: Float = 0f,
    val scale: Float = 1f,
    val rotation: Float = 0f,
    val opacity: Float = 1f
) {
    init {
        require(x.isFinite() && x in -2f..2f)
        require(y.isFinite() && y in -2f..2f)
        require(scale.isFinite() && scale in 0.1f..4f)
        require(rotation.isFinite() && rotation in -360f..360f)
        require(opacity.isFinite() && opacity in 0f..1f)
    }
}

data class MotionKeyframe(
    val timeUs: Long,
    val pose: MotionPose,
    val interpolation: MotionInterpolation = MotionInterpolation.LINEAR
) {
    init { require(timeUs >= 0) }
}

object MotionCurve {
    const val MAX_KEYFRAMES = 120

    fun validate(keyframes: List<MotionKeyframe>, durationUs: Long) {
        require(keyframes.size <= MAX_KEYFRAMES)
        require(keyframes.all { it.timeUs <= durationUs })
        require(keyframes.zipWithNext().all { (a, b) -> a.timeUs < b.timeUs }) {
            "Motion keyframes must have unique, ascending timestamps"
        }
    }

    fun at(keyframes: List<MotionKeyframe>, relativeUs: Long): MotionPose {
        if (keyframes.isEmpty()) return MotionPose()
        val at = relativeUs.coerceAtLeast(0)
        if (at <= keyframes.first().timeUs) return keyframes.first().pose
        if (at >= keyframes.last().timeUs) return keyframes.last().pose
        val upper = keyframes.indexOfFirst { it.timeUs > at }
        val left = keyframes[upper - 1]
        val right = keyframes[upper]
        val raw = (at - left.timeUs).toDouble() / (right.timeUs - left.timeUs).toDouble()
        val f = when (left.interpolation) {
            MotionInterpolation.LINEAR -> raw
            MotionInterpolation.HOLD -> 0.0
            MotionInterpolation.SMOOTH -> 0.5 - 0.5 * cos(raw * PI)
        }.toFloat()
        fun mix(a: Float, b: Float) = a + (b - a) * f
        return MotionPose(
            x = mix(left.pose.x, right.pose.x),
            y = mix(left.pose.y, right.pose.y),
            scale = mix(left.pose.scale, right.pose.scale),
            rotation = mix(left.pose.rotation, right.pose.rotation),
            opacity = mix(left.pose.opacity, right.pose.opacity)
        )
    }

    fun upsert(
        keyframes: List<MotionKeyframe>,
        frame: MotionKeyframe,
        durationUs: Long
    ): List<MotionKeyframe> {
        require(frame.timeUs <= durationUs)
        val result = (keyframes.filterNot { it.timeUs == frame.timeUs } + frame)
            .sortedBy { it.timeUs }
        validate(result, durationUs)
        return result
    }

    /**
     * Splits motion at the cut without changing its visible pose. New right-side
     * keyframes are expressed relative to the right clip, with an interpolated
     * boundary keyframe and correct source-side interpolation.
     */
    fun split(
        keyframes: List<MotionKeyframe>,
        splitUs: Long,
        durationUs: Long
    ): Pair<List<MotionKeyframe>, List<MotionKeyframe>> {
        require(splitUs in 1 until durationUs)
        validate(keyframes, durationUs)
        if (keyframes.isEmpty()) return emptyList<MotionKeyframe>() to emptyList()
        val boundary = MotionKeyframe(splitUs, at(keyframes, splitUs),
            keyframes.lastOrNull { it.timeUs <= splitUs }?.interpolation
                ?: MotionInterpolation.LINEAR)
        val left = (keyframes.filter { it.timeUs < splitUs } + boundary)
            .sortedBy { it.timeUs }
        val right = (listOf(boundary.copy(timeUs = 0)) +
            keyframes.filter { it.timeUs > splitUs }
                .map { it.copy(timeUs = it.timeUs - splitUs) })
            .sortedBy { it.timeUs }
        validate(left, splitUs)
        validate(right, durationUs - splitUs)
        return left to right
    }
}
