package au.parmtech.tongcut.beauty

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.matchParentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlin.math.hypot

/**
 * Optional offline, manual nine-point editor. Draws on an ORIGINAL-orientation
 * still image. The editor may wrap this in its own inspector once integration
 * is approved by A15/A20 and preview/output pixel parity has been tested.
 *
 * It does not silently guess landmarks or add a non-functional editor button.
 */
@Composable
fun FaceReshapeLandmarkPicker(
    preview: ImageBitmap,
    landmarks: FaceReshapeLandmarks,
    onLandmarksChanged: (FaceReshapeLandmarks) -> Unit,
    modifier: Modifier = Modifier
) {
    require(preview.width > 0 && preview.height > 0)
    val latestLandmarks by rememberUpdatedState(landmarks)
    val latestChanged by rememberUpdatedState(onLandmarksChanged)
    val aqua = Color(0xff00c9de)
    val coral = Color(0xffff939a)

    Box(modifier = modifier.fillMaxWidth()
        .aspectRatio(preview.width.toFloat() / preview.height.toFloat())) {
        Image(
            bitmap = preview,
            contentDescription = "Photo with manually adjustable face landmarks",
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.matchParentSize()
        )
        Canvas(
            modifier = Modifier.matchParentSize().pointerInput(preview.width, preview.height) {
                var dragged = -1
                detectDragGestures(
                    onDragStart = { start ->
                        val points = latestLandmarks.asOrderedList()
                        val shortest = points.indices.minByOrNull { i ->
                            val p = points[i]
                            hypot(
                                (p.x * size.width - start.x).toDouble(),
                                (p.y * size.height - start.y).toDouble()
                            )
                        } ?: -1
                        val p = points.getOrNull(shortest)
                        val distance = if (p == null) Float.MAX_VALUE else
                            hypot(p.x * size.width - start.x,
                                p.y * size.height - start.y)
                        // Approx 48dp touch diameter, plus compensation for
                        // tiny preview canvases on the phone.
                        dragged = if (distance <= 28.dp.toPx()) shortest else -1
                    },
                    onDrag = { change, amount ->
                        val index = dragged
                        if (index < 0) return@detectDragGestures
                        change.consume()
                        val existing = latestLandmarks
                        val old = existing.asOrderedList()[index]
                        val newPoint = FacePoint(
                            (old.x + amount.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f),
                            (old.y + amount.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                        )
                        val updated = existing.moved(index, newPoint)
                        if (updated != existing) latestChanged(updated)
                    },
                    onDragEnd = { dragged = -1 },
                    onDragCancel = { dragged = -1 }
                )
            }
        ) {
            // The connecting lines help the user locate facial regions
            // without importing any protected third-party diagram/assets.
            val points = landmarks.asOrderedList()
            val centers = points.map { p ->
                Offset(p.x * size.width, p.y * size.height)
            }
            for ((a, b) in listOf(0 to 1, 2 to 3, 4 to 5, 6 to 7)) {
                drawLine(color = aqua, start = centers[a], end = centers[b], strokeWidth = 2.dp.toPx())
            }
            for (i in centers.indices) {
                val isEye = i <= 1
                drawCircle(
                    color = Color.Black.copy(alpha = 0.68f),
                    radius = 9.dp.toPx(),
                    center = centers[i]
                )
                drawCircle(
                    color = if (isEye) coral else aqua,
                    radius = 7.dp.toPx(),
                    center = centers[i],
                    style = Stroke(width = 2.5.dp.toPx())
                )
            }
        }
    }
}

private fun FaceReshapeLandmarks.asOrderedList(): List<FacePoint> = listOf(
    leftEye, rightEye, leftCheek, rightCheek,
    leftJaw, rightJaw, leftNose, rightNose, chin
)

/** Never let dragging a point cross its left/right partner. */
private fun FaceReshapeLandmarks.moved(index: Int, value: FacePoint): FaceReshapeLandmarks =
    try {
        when (index) {
            0 -> copy(leftEye = value)
            1 -> copy(rightEye = value)
            2 -> copy(leftCheek = value)
            3 -> copy(rightCheek = value)
            4 -> copy(leftJaw = value)
            5 -> copy(rightJaw = value)
            6 -> copy(leftNose = value)
            7 -> copy(rightNose = value)
            8 -> copy(chin = value)
            else -> this
        }
    } catch (_: IllegalArgumentException) {
        this
    }
