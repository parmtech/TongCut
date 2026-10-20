package au.parmtech.tongcut

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.hypot

/** Compact draggable graph shared by primary-footage and layer color controls. */
@Composable
internal fun ToneCurveGraph(
    curve: ToneCurve,
    channel: Int,
    onSelectPoint: (Int) -> Unit,
    onMovePoint: (Int, Float, Float) -> Unit,
    onChangeFinished: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val currentCurve = rememberUpdatedState(curve)
    val selectPoint = rememberUpdatedState(onSelectPoint)
    val movePoint = rememberUpdatedState(onMovePoint)
    val changeFinished = rememberUpdatedState(onChangeFinished)
    val curveColor = when (channel) {
        1 -> Color(0xFFFF7777)
        2 -> Color(0xFF72E6A1)
        3 -> Color(0xFF79BFFF)
        else -> Color(0xFFE9F0FA)
    }
    Canvas(
        modifier = modifier.fillMaxWidth().height(172.dp)
            .clip(RoundedCornerShape(12.dp))
            .semantics { contentDescription = "Tone curve graph; drag an interior point to adjust" }
            .pointerInput(curve.points.size) {
                var activePoint: Int? = null
                var changed = false
                detectDragGestures(
                    onDragStart = { position ->
                        val points = currentCurve.value.points
                        activePoint = points.indices.drop(1).dropLast(1).minByOrNull { index ->
                            val point = points[index]
                            val px = point.x * size.width
                            val py = (1f - point.y) * size.height
                            hypot(position.x - px, position.y - py)
                        }?.takeIf { index ->
                            val point = points[index]
                            hypot(position.x - point.x * size.width,
                                position.y - (1f - point.y) * size.height) <= 48.dp.toPx()
                        }
                        activePoint?.let { selectPoint.value(it) }
                    },
                    onDrag = { change, _ ->
                        val index = activePoint ?: return@detectDragGestures
                        change.consume()
                        val x = (change.position.x / size.width).coerceIn(0f, 1f)
                        val y = (1f - change.position.y / size.height).coerceIn(0f, 1f)
                        movePoint.value(index, x, y)
                        changed = true
                    },
                    onDragEnd = {
                        activePoint = null
                        if (changed) changeFinished.value()
                        changed = false
                    },
                    onDragCancel = {
                        activePoint = null
                        if (changed) changeFinished.value()
                        changed = false
                    }
                )
            }
    ) {
        drawRect(Color(0xFF131B26))
        for (division in 1..3) {
            val x = size.width * division / 4f
            val y = size.height * division / 4f
            drawLine(Color(0xFF303B49), Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
            drawLine(Color(0xFF303B49), Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
        }
        val path = Path()
        curve.points.forEachIndexed { index, point ->
            val position = Offset(point.x * size.width, (1f - point.y) * size.height)
            if (index == 0) path.moveTo(position.x, position.y)
            else path.lineTo(position.x, position.y)
        }
        drawPath(path, curveColor, style = Stroke(width = 3.dp.toPx()))
        curve.points.forEachIndexed { index, point ->
            drawCircle(
                color = if (index == 0 || index == curve.points.lastIndex) Color(0xFF667487)
                    else curveColor,
                radius = if (index == 0 || index == curve.points.lastIndex) 4.dp.toPx()
                    else 7.dp.toPx(),
                center = Offset(point.x * size.width, (1f - point.y) * size.height)
            )
        }
    }
}
