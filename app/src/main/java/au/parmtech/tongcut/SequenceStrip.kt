package au.parmtech.tongcut

import android.graphics.Paint
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
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

    Column(Modifier.fillMaxWidth().height(249.dp).background(Color(0xFF171717))) {
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
                                        repeat(frames) {
                                            GalleryThumbnail(clip.sourceUri.toUri(),
                                                Modifier.weight(1f).fillMaxHeight())
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
                    Spacer(Modifier.height(5.dp))
                    Surface(
                        color = Color(0xFF262626),
                        modifier = Modifier.fillMaxWidth().height(38.dp),
                        onClick = { onUnavailable("Audio timeline layers") }
                    ) {
                        Box(contentAlignment = Alignment.CenterStart) {
                            Text("+ Add audio", color = Color.Gray, fontSize = 12.sp,
                                modifier = Modifier.padding(start = 9.dp))
                        }
                    }
                    Spacer(Modifier.height(5.dp))
                    Surface(
                        color = Color(0xFF262626),
                        modifier = Modifier.fillMaxWidth().height(38.dp),
                        onClick = { onUnavailable("Text timeline layers") }
                    ) {
                        Box(contentAlignment = Alignment.CenterStart) {
                            Text("+ Add text", color = Color.Gray, fontSize = 12.sp,
                                modifier = Modifier.padding(start = 9.dp))
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
