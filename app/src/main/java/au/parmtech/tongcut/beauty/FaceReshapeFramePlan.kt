package au.parmtech.tongcut.beauty

import kotlin.math.abs

/**
 * A frame's verified, already-normalized landmarks. The ID is a session-local
 * tracking key, NOT a biometric identity. Never persist a person's identity.
 */
data class FaceReshapeObservation(
    val presentationTimeUs: Long,
    val trackKey: Int,
    val landmarks: FaceReshapeLandmarks,
    val confidence: Float
) {
    init {
        require(presentationTimeUs >= 0)
        require(trackKey >= 0)
        require(confidence.isFinite() && confidence in 0f..1f)
    }
}

/**
 * Source-clock interpolation for a single manually selected, reliably tracked
 * face. Refuses to extrapolate, bridge long/low-confidence gaps or blend faces.
 *
 * This is NOT a detector/tracker. Source observations must be collected by a
 * separately approved offline landmark pipeline and transformed to the same
 * unrotated/unmirrored source-frame coordinate system before calling.
 */
object FaceReshapeFramePlan {
    const val DEFAULT_MAX_GAP_US = 250_000L
    const val DEFAULT_MIN_CONFIDENCE = 0.70f

    fun validate(samples: List<FaceReshapeObservation>) {
        require(samples.size <= 30_000) { "Too many face observations" }
        require(samples.zipWithNext().all { (a, b) ->
            a.presentationTimeUs < b.presentationTimeUs
        }) { "Observations must have unique increasing source timestamps" }
    }

    fun at(
        samples: List<FaceReshapeObservation>,
        sourceTimeUs: Long,
        maxGapUs: Long = DEFAULT_MAX_GAP_US,
        minConfidence: Float = DEFAULT_MIN_CONFIDENCE
    ): FaceReshapeLandmarks? {
        require(sourceTimeUs >= 0)
        require(maxGapUs in 1L..1_000_000L)
        require(minConfidence.isFinite() && minConfidence in 0f..1f)
        if (samples.isEmpty()) return null
        validate(samples)
        if (sourceTimeUs < samples.first().presentationTimeUs ||
            sourceTimeUs > samples.last().presentationTimeUs) return null

        // The binary search uses source timestamps, not the edited project
        // clock: speed ramps and reverse must map project -> source first.
        var lo = 0
        var hi = samples.lastIndex
        while (lo <= hi) {
            val mid = lo + (hi - lo) / 2
            val sample = samples[mid]
            when {
                sample.presentationTimeUs == sourceTimeUs ->
                    return if (sample.confidence >= minConfidence) sample.landmarks else null
                sample.presentationTimeUs < sourceTimeUs -> lo = mid + 1
                else -> hi = mid - 1
            }
        }
        if (hi < 0 || lo > samples.lastIndex) return null

        val left = samples[hi]
        val right = samples[lo]
        val duration = right.presentationTimeUs - left.presentationTimeUs
        if (duration <= 0 || duration > maxGapUs ||
            left.trackKey != right.trackKey ||
            left.confidence < minConfidence ||
            right.confidence < minConfidence ||
            jumped(left.landmarks, right.landmarks)) {
            return null
        }
        val t = (sourceTimeUs - left.presentationTimeUs).toDouble() /
            duration.toDouble()
        return interpolate(left.landmarks, right.landmarks, t.toFloat())
    }

    // Sudden moves usually indicate lost tracking, a hard cut, or swapped faces.
    // Do not interpolate a rubber-band face through such a discontinuity.
    private fun jumped(a: FaceReshapeLandmarks, b: FaceReshapeLandmarks): Boolean {
        val pairs = listOf(
            a.leftEye to b.leftEye, a.rightEye to b.rightEye,
            a.leftCheek to b.leftCheek, a.rightCheek to b.rightCheek,
            a.chin to b.chin
        )
        return pairs.any { (p, q) ->
            abs(p.x - q.x) > 0.18f || abs(p.y - q.y) > 0.18f
        }
    }

    private fun interpolate(
        a: FaceReshapeLandmarks,
        b: FaceReshapeLandmarks,
        t: Float
    ): FaceReshapeLandmarks {
        fun point(p: FacePoint, q: FacePoint): FacePoint =
            FacePoint(
                (p.x + (q.x - p.x) * t).coerceIn(0f, 1f),
                (p.y + (q.y - p.y) * t).coerceIn(0f, 1f)
            )
        return FaceReshapeLandmarks(
            leftEye = point(a.leftEye, b.leftEye),
            rightEye = point(a.rightEye, b.rightEye),
            leftCheek = point(a.leftCheek, b.leftCheek),
            rightCheek = point(a.rightCheek, b.rightCheek),
            leftJaw = point(a.leftJaw, b.leftJaw),
            rightJaw = point(a.rightJaw, b.rightJaw),
            leftNose = point(a.leftNose, b.leftNose),
            rightNose = point(a.rightNose, b.rightNose),
            chin = point(a.chin, b.chin)
        )
    }
}
