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

/** Graph of actual persisted motion keyframes; every control commits one undoable draft edit. */
private enum class MotionAxis(val title: String, val minimum: Float, val maximum: Float) {
    X("Horizontal", -2f, 2f),
    Y("Vertical", -2f, 2f),
    ZOOM("Scale", 0.1f, 4f),
    ROTATION("Rotation", -360f, 360f),
    OPACITY("Opacity", 0f, 1f);

    fun read(pose: MotionPose): Float = when (this) {
        X -> pose.x
        Y -> pose.y
        ZOOM -> pose.scale
        ROTATION -> pose.rotation
        OPACITY -> pose.opacity
    }

    fun write(pose: MotionPose, value: Float): MotionPose = when (this) {
        X -> pose.copy(x = value)
        Y -> pose.copy(y = value)
        ZOOM -> pose.copy(scale = value)
        ROTATION -> pose.copy(rotation = value)
        OPACITY -> pose.copy(opacity = value)
    }

    fun normalized(pose: MotionPose): Float =
        ((read(pose) - minimum) / (maximum - minimum)).coerceIn(0f, 1f)
}

@Composable
internal fun MotionKeyframeEditor(
    draft: EditorDraft,
    layer: TimelineLayer,
    onChange: (EditorDraft) -> Unit,
    onError: (String) -> Unit
) {
    if (layer.motion.isEmpty()) return
    var axis by remember(layer.id) { mutableStateOf(MotionAxis.OPACITY) }
    var selectedTimeUs by remember(layer.id) {
        mutableLongStateOf(layer.motion.first().timeUs)
    }
    val selected = layer.motion.minByOrNull { abs(it.timeUs - selectedTimeUs) } ?: return
    var pending by remember(layer.id, selected.timeUs, axis) {
        mutableStateOf<Float?>(null)
    }

    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            TextButton(onClick = {
                axis = MotionAxis.entries[(axis.ordinal + 1) % MotionAxis.entries.size]
            }) { Text("Curve: ${axis.title}", color = ProCyan, fontSize = 11.sp) }
            Text(
                "◆ ${selected.timeUs / 1_000L}ms / ${layer.durationUs / 1_000L}ms",
                color = Color.White, fontSize = 11.sp,
                modifier = Modifier.padding(top = 12.dp)
            )
        }

        Canvas(
            modifier = Modifier.fillMaxWidth().height(63.dp)
                .pointerInput(layer.id, layer.motion, layer.durationUs) {
                    detectTapGestures { tap ->
                        val ratio = (tap.x / size.width.toFloat()).coerceIn(0f, 1f)
                        val target = (ratio * layer.durationUs).toLong()
                        selectedTimeUs = layer.motion.minByOrNull {
                            abs(it.timeUs - target)
                        }?.timeUs ?: selectedTimeUs
                    }
                }
        ) {
            val w = size.width
            val h = size.height
            drawLine(Color.Gray.copy(alpha = 0.35f),
                Offset(0f, h / 2f), Offset(w, h / 2f), 1.dp.toPx())
            val segments = 60
            var last: Offset? = null
            for (index in 0..segments) {
                val relativeUs = (layer.durationUs.toDouble() * index / segments)
                    .roundToLong()
                val pose = MotionCurve.at(layer.motion, relativeUs)
                val current = Offset(
                    w * index / segments,
                    h * (1f - axis.normalized(pose))
                )
                last?.let { prev ->
                    drawLine(ProCyan, prev, current, strokeWidth = 2.dp.toPx())
                }
                last = current
            }
            layer.motion.forEach { key ->
                val point = Offset(
                    w * key.timeUs.toFloat() / layer.durationUs.toFloat(),
                    h * (1f - axis.normalized(key.pose))
                )
                drawCircle(
                    if (key.timeUs == selected.timeUs) Color.White else ProCyan,
                    radius = if (key.timeUs == selected.timeUs) 5.dp.toPx() else 3.dp.toPx(),
                    center = point
                )
            }
        }

        Slider(
            value = (pending ?: axis.read(selected.pose))
                .coerceIn(axis.minimum, axis.maximum),
            onValueChange = { pending = it },
            valueRange = axis.minimum..axis.maximum,
            onValueChangeFinished = {
                val changed = pending
                if (changed != null) {
                    runCatching {
                        LayerEdits.setMotionKeyPose(
                            draft, layer.id, selected.timeUs,
                            axis.write(selected.pose, changed)
                        )
                    }.onSuccess(onChange)
                        .onFailure { onError(it.message ?: "Keyframe value failed") }
                    pending = null
                }
            },
            modifier = Modifier.fillMaxWidth().height(32.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            TextButton(onClick = {
                val modes = MotionInterpolation.entries
                val mode = modes[(selected.interpolation.ordinal + 1) % modes.size]
                runCatching {
                    LayerEdits.setMotionKeyInterpolation(
                        draft, layer.id, selected.timeUs, mode
                    )
                }.onSuccess(onChange)
                    .onFailure { onError(it.message ?: "Easing failed") }
            }) {
                Text("Easing: ${selected.interpolation.name}", color = ProCyan, fontSize = 11.sp)
            }
            listOf(-1, 1).forEach { direction ->
                TextButton(onClick = {
                    val frameUs = draft.layers.rate.atFrame(1L)
                    val next = (selected.timeUs + frameUs * direction)
                        .coerceIn(0L, layer.durationUs)
                    runCatching {
                        LayerEdits.moveMotionKey(draft, layer.id, selected.timeUs, next)
                    }.onSuccess {
                        selectedTimeUs = next
                        onChange(it)
                    }.onFailure { onError(it.message ?: "Move key failed") }
                }) {
                    Text(if (direction < 0) "◀ 1 frame" else "1 frame ▶",
                        color = ProCyan, fontSize = 11.sp)
                }
            }
            TextButton(onClick = {
                runCatching {
                    LayerEdits.removeMotionKey(draft, layer.id, selected.timeUs)
                }.onSuccess(onChange)
                    .onFailure { onError(it.message ?: "Delete key failed") }
            }) { Text("Delete key", color = Color(0xFFFF998C), fontSize = 11.sp) }
        }
    }
}
