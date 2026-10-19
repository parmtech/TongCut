package au.parmtech.tongcut

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Editable video speed graph. X is source clock, Y is the step's playback speed.
 * This intentionally matches the piecewise-constant SpeedRamp provider used
 * for preview and Media3 output; drawing smooth slopes would be misleading.
 */
@Composable
internal fun SpeedCurveEditor(
    draft: EditorDraft,
    layer: TimelineLayer,
    onChange: (EditorDraft) -> Unit,
    onError: (String) -> Unit
) {
    if (layer.kind != LayerKind.VIDEO || layer.speedRamp.isEmpty()) return
    val points = layer.speedRamp
    val sourceSpan = layer.consumedSourceUs
    var chosenOffset by remember(layer.id) { mutableLongStateOf(0L) }
    val selected = points.minByOrNull { abs(it.sourceOffsetUs - chosenOffset) } ?: return
    var sliderValue by remember(layer.id, selected.sourceOffsetUs, selected.speed) {
        mutableFloatStateOf(selected.speed)
    }
    val cyan = ProCyan
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            "Speed curve (SOURCE timeline) · ${points.size} keys · " +
                "${"%.2f".format(sourceSpan / 1_000_000.0)}s media / " +
                "${"%.2f".format(layer.durationUs / 1_000_000.0)}s timeline",
            color = Color.LightGray,
            fontSize = 11.sp
        )

        Canvas(Modifier.fillMaxWidth().height(72.dp)
            .pointerInput(layer.id, points, sourceSpan) {
                detectTapGestures { tap ->
                    val ratio = (tap.x / size.width.coerceAtLeast(1).toFloat())
                        .coerceIn(0f,1f)
                    val sourceAt = (sourceSpan * ratio).roundToLong()
                    chosenOffset = points.minByOrNull {
                        abs(it.sourceOffsetUs - sourceAt)
                    }?.sourceOffsetUs ?: chosenOffset
                }
            }
        ) {
            val width = size.width
            val height = size.height
            fun y(speed: Float): Float =
                height * (1f - ((speed - 0.25f) / 3.75f).coerceIn(0f,1f))
            fun x(time: Long): Float =
                width * time.toFloat() / sourceSpan.coerceAtLeast(1L).toFloat()
            drawLine(Color.Gray.copy(alpha = 0.3f),
                Offset(0f,y(1f)),Offset(width,y(1f)),1.dp.toPx())
            points.forEachIndexed { index, point ->
                val nextTime = points.getOrNull(index + 1)?.sourceOffsetUs ?: sourceSpan
                drawLine(cyan, Offset(x(point.sourceOffsetUs), y(point.speed)),
                    Offset(x(nextTime),y(point.speed)),2.dp.toPx())
                if (index+1 < points.size) {
                    drawLine(cyan.copy(alpha = 0.6f),
                        Offset(x(nextTime), y(point.speed)),
                        Offset(x(nextTime), y(points[index+1].speed)),
                        1.dp.toPx())
                }
                drawCircle(
                    if (point.sourceOffsetUs == selected.sourceOffsetUs) Color.White else cyan,
                    if (point.sourceOffsetUs == selected.sourceOffsetUs)
                        5.dp.toPx() else 3.dp.toPx(),
                    Offset(x(point.sourceOffsetUs),y(point.speed))
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Key ${selected.sourceOffsetUs/1000L}ms: " +
                    "${"%.2f".format(sliderValue)}×",
                color = Color.White, fontSize = 12.sp
            )
        }
        Slider(
            value = sliderValue.coerceIn(0.25f,4f),
            onValueChange = { sliderValue = it },
            valueRange = 0.25f..4f,
            onValueChangeFinished = {
                val speed = (sliderValue * 20f).roundToLong() / 20f
                runCatching {
                    LayerEdits.changeSpeedKey(draft,layer.id,selected.sourceOffsetUs,speed)
                }.onSuccess(onChange)
                    .onFailure { onError(it.message ?: "Speed key edit failed") }
            },
            modifier = Modifier.fillMaxWidth().height(35.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            listOf(-1,1).forEach { direction ->
                TextButton(
                    enabled = selected.sourceOffsetUs != 0L,
                    onClick = {
                        // Speed keys must remain aligned to MediaItem millisecond boundaries.
                        val frameMs = ((draft.layers.rate.atFrame(1L) + 500L) / 1_000L)
                            .coerceAtLeast(1L)
                        val movedUs = (selected.sourceOffsetUs / 1_000L +
                            direction * frameMs).coerceAtLeast(1L) * 1_000L
                        runCatching {
                            LayerEdits.moveSpeedKey(
                                draft,layer.id,selected.sourceOffsetUs,movedUs
                            )
                        }.onSuccess {
                            chosenOffset = movedUs
                            onChange(it)
                        }.onFailure { onError(it.message ?: "Speed point move failed") }
                    }
                ) {
                    Text(
                        if (direction == -1) "◀ Source frame" else "Source frame ▶",
                        color = cyan, fontSize = 11.sp
                    )
                }
            }
            TextButton(
                enabled = selected.sourceOffsetUs != 0L,
                onClick = {
                    runCatching {
                        LayerEdits.removeSpeedKey(draft,layer.id,selected.sourceOffsetUs)
                    }.onSuccess {
                        chosenOffset = 0L
                        onChange(it)
                    }.onFailure { onError(it.message ?: "Delete point failed") }
                }
            ) { Text("Delete point", color = Color(0xFFFF998C), fontSize = 11.sp) }
        }
    }
}
