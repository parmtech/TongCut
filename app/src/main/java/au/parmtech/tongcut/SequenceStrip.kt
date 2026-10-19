package au.parmtech.tongcut

import android.graphics.Paint
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.roundToInt

private const val PIXELS_PER_SECOND_DP = 56f
private enum class LayerGesture { MOVE, TRIM_START, TRIM_END }

/** A unified scrolling ruler and consecutive clip strip, driven by real edited milliseconds.
 *
 * This is the sequential video track foundation. Audio/text rows currently show planned lanes,
 * not mock editing actions or a complete multi-track render graph.
 */
@Composable
internal fun SequenceStrip(
    draft: EditorDraft,
    positionMs: Long,
    onSeek: (TimelinePosition) -> Unit,
    onSelect: (Int) -> Unit,
    onAddMedia: () -> Unit,
    onAddText: () -> Unit,
    onAddAudio: () -> Unit,
    onAddVisual: (LayerKind) -> Unit,
    selectedLayerId: String? = null,
    onLayerSelect: (LayerKind, String) -> Unit,
    onMoveLayer: (String, Long) -> Unit,
    onTrimLayerStart: (String, Long) -> Unit,
    onTrimLayerEnd: (String, Long) -> Unit,
    onUnavailable: (String) -> Unit
) {
    val timeline = remember(draft.clips) { SequentialTimeline(draft.clips) }
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    var pxPerSecond by remember { mutableFloatStateOf(PIXELS_PER_SECOND_DP) }
    var programmaticSeek by remember { mutableStateOf(false) }
    var dragged by remember { mutableStateOf(false) }
    val textPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.LTGRAY
            textSize = 11f
        }
    }

    Column(Modifier.fillMaxWidth().height(349.dp).background(Color(0xFF171717))) {
        Row(Modifier.fillMaxWidth().height(28.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(positionMs.asTimestamp(), fontSize = 11.sp, color = Color.White)
            Text("  /  ${timeline.totalMs.asTimestamp()}", fontSize = 11.sp, color = Color.LightGray)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { pxPerSecond = (pxPerSecond / 1.4f).coerceAtLeast(20f) },
                contentPadding = PaddingValues(horizontal = 6.dp)) { Text("−", color = Color.LightGray) }
            Text("Timeline", fontSize = 11.sp, color = Color.LightGray)
            TextButton(onClick = { pxPerSecond = (pxPerSecond * 1.4f).coerceAtMost(110f) },
                contentPadding = PaddingValues(horizontal = 6.dp)) { Text("+", color = Color.LightGray) }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val sidePadding = maxWidth / 2
            val displayWidthDp = (timeline.totalMs / 1000f * pxPerSecond).coerceAtLeast(1f)
            val totalPx = with(density) { displayWidthDp.dp.roundToPx() }

            LaunchedEffect(positionMs, timeline, pxPerSecond, scroll.isScrollInProgress) {
                if (!scroll.isScrollInProgress && !programmaticSeek) {
                    val next = if (timeline.totalMs == 0L) 0 else {
                        ((positionMs.toDouble() / timeline.totalMs.toDouble()) * totalPx).roundToInt()
                    }
                    programmaticSeek = true
                    scroll.scrollTo(next.coerceIn(0, scroll.maxValue.coerceAtLeast(0)))
                    programmaticSeek = false
                }
            }

            LaunchedEffect(scroll.value, scroll.isScrollInProgress, timeline, pxPerSecond) {
                if (scroll.isScrollInProgress && !programmaticSeek) {
                    dragged = true
                    delay(45)
                    val position = ((scroll.value.toDouble() / totalPx.coerceAtLeast(1)) *
                        timeline.totalMs).toLong().coerceIn(0, timeline.totalMs)
                    timeline.pointAt(position)?.let(onSeek)
                } else if (!scroll.isScrollInProgress && dragged) {
                    dragged = false
                    val position = ((scroll.value.toDouble() / totalPx.coerceAtLeast(1)) *
                        timeline.totalMs).toLong().coerceIn(0, timeline.totalMs)
                    timeline.pointAt(position)?.let(onSeek)
                }
            }
            Row(
                modifier = Modifier.fillMaxSize().horizontalScroll(scroll),
                verticalAlignment = Alignment.Top
            ) {
                Spacer(Modifier.width(sidePadding))
                Column(Modifier.width(displayWidthDp.dp)) {
                    Canvas(Modifier.fillMaxWidth().height(28.dp)) {
                        val spacingPx = with(density) { pxPerSecond.dp.toPx() }
                        val seconds = ceil(timeline.totalMs / 1000.0).toInt()
                        textPaint.textSize = with(density) { 10.sp.toPx() }
                        for (second in 0..seconds) {
                            val x = second * spacingPx
                            drawLine(
                                color = if (second % 5 == 0) Color.LightGray else Color.DarkGray,
                                start = androidx.compose.ui.geometry.Offset(x, 19f),
                                end = androidx.compose.ui.geometry.Offset(x, if (second % 5 == 0) 27f else 23f),
                                strokeWidth = 1f
                            )
                            if (second % 5 == 0) {
                                drawIntoCanvas { canvas ->
                                    canvas.nativeCanvas.drawText(
                                        (second * 1000L).asTimestamp(), x + 3f, 12f, textPaint
                                    )
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().height(76.dp)) {
                        timeline.clips.forEachIndexed { index, clip ->
                            val width = (clip.editedDurationMs / 1000f * pxPerSecond).coerceAtLeast(1f).dp
                            Surface(
                                color = Color(0xFF323232),
                                shape = RoundedCornerShape(3.dp),
                                border = BorderStroke(if (index == draft.selectedIndex) 2.dp else 1.dp,
                                    if (index == draft.selectedIndex) Color.White else Color(0xFF575757)),
                                modifier = Modifier.width(width).fillMaxHeight().clickable { onSelect(index) }
                            ) {
                                Box {
                                    Row(Modifier.fillMaxSize()) {
                                        val frames = (clip.editedDurationMs / 1200L).toInt().coerceIn(1, 8)
                                        repeat(frames) { frameIndex ->
                                            // Sample evenly across the trimmed SOURCE interval.
                                            val fraction = (frameIndex + 0.5) / frames.toDouble()
                                            val sampledMs = clip.inMs +
                                                (clip.editedDurationMs * fraction).toLong()
                                            VideoFrameThumbnail(
                                                uri = clip.sourceUri.toUri(),
                                                sourceTimestampUs = sampledMs * 1_000L,
                                                modifier = Modifier.weight(1f).fillMaxHeight()
                                            )
                                        }
                                    }
                                    Text(clip.label, color = Color.White, fontSize = 10.sp, maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.align(Alignment.BottomStart)
                                            .background(Color(0xA7000000)).padding(horizontal = 4.dp))
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Column(
                        Modifier.fillMaxWidth().height(189.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        listOf(LayerKind.VIDEO, LayerKind.IMAGE, LayerKind.AUDIO, LayerKind.TEXT).forEach { kind ->
                            val relevant = draft.layers.tracks
                                .filter { it.kind == kind }.sortedBy { it.order }
                            val lanes = if (relevant.isEmpty()) listOf<TimelineTrack?>(null)
                                else relevant
                            lanes.forEach { lane ->
                                Box(
                                    Modifier.fillMaxWidth().height(40.dp)
                                        .background(Color(0xFF262626), RoundedCornerShape(3.dp))
                                        .clickable {
                                            when (kind) {
                                                LayerKind.TEXT -> onAddText()
                                                LayerKind.AUDIO -> onAddAudio()
                                                LayerKind.VIDEO, LayerKind.IMAGE -> onAddVisual(kind)
                                                else -> onUnavailable("Sticker track")
                                            }
                                        }
                                ) {
                                    if (lane == null) {
                                        Text(
                                            when (kind) {
                                                LayerKind.AUDIO -> "+ Add audio"
                                                LayerKind.TEXT -> "+ Add text"
                                                LayerKind.VIDEO -> "+ Add overlay video"
                                                LayerKind.IMAGE -> "+ Add overlay photo"
                                                else -> "+ Add media"
                                            },
                                            color = Color.Gray,
                                            fontSize = 12.sp,
                                            modifier = Modifier.align(Alignment.CenterStart)
                                                .padding(start = 9.dp)
                                        )
                                    } else {
                                        draft.layers.layers.filter { it.trackId == lane.id }
                                            .forEach { layer ->
                                                val start = layer.startUs / 1_000_000f * pxPerSecond
                                                val length = layer.durationUs / 1_000_000f * pxPerSecond
                                                var dragPx by remember(layer.id) { mutableFloatStateOf(0f) }
                                                var gesture by remember(layer.id) {
                                                    mutableStateOf(LayerGesture.MOVE)
                                                }
                                                val shiftedPx = when (gesture) {
                                                    LayerGesture.TRIM_END -> 0f
                                                    else -> dragPx
                                                }
                                                val resizingPx = when (gesture) {
                                                    LayerGesture.TRIM_START -> -dragPx
                                                    LayerGesture.TRIM_END -> dragPx
                                                    else -> 0f
                                                }
                                                val resizedWidthDp = (length +
                                                    resizingPx / density.density).coerceAtLeast(8f)
                                                Surface(
                                                    color = if (kind == LayerKind.TEXT)
                                                        Color(0xFF6D469E) else Color(0xFF315A67),
                                                    shape = RoundedCornerShape(3.dp),
                                                    border = if (selectedLayerId == layer.id)
                                                        BorderStroke(2.dp, TongCutPalette.AquaSoft) else null,
                                                    modifier = Modifier
                                                        .offset {
                                                            IntOffset(
                                                                with(density) { start.dp.roundToPx() } +
                                                                    shiftedPx.roundToInt(),
                                                                0
                                                            )
                                                        }
                                                        .width(resizedWidthDp.dp)
                                                        .height(38.dp)
                                                        .pointerInput(
                                                            layer.id, layer.startUs, layer.durationUs,
                                                            pxPerSecond
                                                        ) {
                                                            detectDragGestures(
                                                                onDragStart = { finger ->
                                                                    dragPx = 0f
                                                                    val edge = with(density) { 14.dp.toPx() }
                                                                    gesture = if (size.width < edge * 3f) {
                                                                        LayerGesture.MOVE
                                                                    } else when {
                                                                        finger.x <= edge -> LayerGesture.TRIM_START
                                                                        finger.x >= size.width - edge ->
                                                                            LayerGesture.TRIM_END
                                                                        else -> LayerGesture.MOVE
                                                                    }
                                                                },
                                                                onDragCancel = {
                                                                    dragPx = 0f
                                                                    gesture = LayerGesture.MOVE
                                                                },
                                                                onDragEnd = {
                                                                    val offsetUs = (
                                                                        (dragPx / density.density) /
                                                                            pxPerSecond * 1_000_000f
                                                                    ).toLong()
                                                                    when (gesture) {
                                                                        LayerGesture.MOVE -> onMoveLayer(
                                                                            layer.id, layer.startUs + offsetUs
                                                                        )
                                                                        LayerGesture.TRIM_START -> onTrimLayerStart(
                                                                            layer.id, layer.startUs + offsetUs
                                                                        )
                                                                        LayerGesture.TRIM_END -> onTrimLayerEnd(
                                                                            layer.id, layer.endUs + offsetUs
                                                                        )
                                                                    }
                                                                    dragPx = 0f
                                                                    gesture = LayerGesture.MOVE
                                                                },
                                                                onDrag = { change, amount ->
                                                                    change.consume()
                                                                    dragPx += amount.x
                                                                }
                                                            )
                                                        }
                                                        .clickable { onLayerSelect(kind, layer.id) }
                                                ) {
                                                    Box(contentAlignment = Alignment.CenterStart) {
                                                        // Visible left/right trim handles. Narrow
                                                        // clips retain move gestures; tool buttons
                                                        // always offer precise split/trim editing.
                                                        Box(
                                                            Modifier.align(Alignment.CenterStart)
                                                                .width(3.dp).fillMaxHeight()
                                                                .background(Color.White.copy(alpha = 0.45f))
                                                        )
                                                        Box(
                                                            Modifier.align(Alignment.CenterEnd)
                                                                .width(3.dp).fillMaxHeight()
                                                                .background(Color.White.copy(alpha = 0.45f))
                                                        )
                                                        if (kind == LayerKind.AUDIO) {
                                                            AudioWaveform(
                                                                layer = layer,
                                                                modifier = Modifier.fillMaxSize().padding(3.dp)
                                                            )
                                                        }
                                                        if (kind == LayerKind.IMAGE || kind == LayerKind.VIDEO) {
                                                            GalleryThumbnail(
                                                                layer.sourceUri.orEmpty().toUri(),
                                                                Modifier.fillMaxSize()
                                                            )
                                                        }
                                                        Text(
                                                            when (kind) {
                                                                LayerKind.TEXT -> layer.text.orEmpty()
                                                                LayerKind.AUDIO -> "♪ Audio"
                                                                LayerKind.VIDEO -> "Overlay video"
                                                                LayerKind.IMAGE -> "Photo"
                                                                else -> "Layer"
                                                            },
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis,
                                                            color = if (lane.hidden) Color.Gray
                                                                else Color.White,
                                                            fontSize = 11.sp,
                                                            modifier = Modifier.padding(horizontal = 6.dp)
                                                        )
                                                    }
                                                }
                                            }
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.width(sidePadding))
            }
            // Keep a fixed, centered playhead while the content scrolls beneath it.
            Box(Modifier.align(Alignment.Center).width(2.dp).fillMaxHeight()
                .background(Color.White))
        }
        Row(Modifier.fillMaxWidth().height(39.dp).padding(horizontal = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("${draft.clips.size} clip(s)", color = Color.LightGray, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onAddMedia) {
                Icon(Icons.Default.Add, contentDescription = null, tint = ProCyan)
                Text("Media", color = ProCyan)
            }
        }
    }
}
