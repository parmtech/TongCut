package au.parmtech.tongcut

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

private data class ProTool(val name: String, val icon: ImageVector)
private val tools = listOf(
    ProTool("Edit", Icons.Default.ContentCut),
    ProTool("Audio", Icons.Default.MusicNote),
    ProTool("Text", Icons.Default.TextFields),
    ProTool("Effects", Icons.Default.AutoAwesome),
    ProTool("Overlay", Icons.Default.Layers),
    ProTool("Captions", Icons.Default.Subtitles),
    ProTool("Filters", Icons.Default.FilterVintage),
    ProTool("Adjust", Icons.Default.Tune),
    ProTool("Canvas", Icons.Default.AspectRatio),
    ProTool("AI", Icons.Default.SmartToy)
)

@Composable
fun VideoEditorScreen(
    draft: EditorDraft,
    exporter: VideoExporter,
    onBack: () -> Unit,
    onMedia: () -> Unit,
    onChange: (EditorDraft) -> Unit,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onUnavailable: (String) -> Unit,
    onInfo: (String) -> Unit
) {
    val context = LocalContext.current
    val player = remember(context) { ExoPlayer.Builder(context).build() }
    var playing by remember { mutableStateOf(false) }
    var playMs by remember { mutableLongStateOf(0L) }
    var fullscreen by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf<String?>(null) }
    var exportDialog by remember { mutableStateOf(false) }
    var processing by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var exportResolution by remember { mutableIntStateOf(draft.shortSidePixels) }
    var muteOriginal by remember { mutableStateOf(draft.muteOriginalAudio) }
    var lastExport by remember { mutableStateOf<Uri?>(null) }
    var previewDraft by remember { mutableStateOf<EditorDraft?>(null) }

    val active = previewDraft ?: draft
    val clip = active.selectedClip
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(clip?.sourceUri, active.selectedIndex) {
        player.pause()
        playing = false
        if (clip != null) {
            player.setMediaItem(MediaItem.fromUri(clip.sourceUri.toUri()))
            player.prepare()
            player.seekTo(clip.inMs)
            playMs = clip.inMs
        } else {
            player.clearMediaItems()
            playMs = 0
        }
    }
    LaunchedEffect(player, clip?.sourceUri, clip?.outMs) {
        while (true) {
            val now = player.currentPosition
            playMs = now
            playing = player.isPlaying
            if (clip != null && player.isPlaying && now >= clip.outMs) {
                player.pause()
                player.seekTo(clip.inMs)
                playing = false
            }
            delay(120)
        }
    }

    Column(
        Modifier.fillMaxSize().background(ProBlack)
            .statusBarsPadding().navigationBarsPadding()
    ) {
        Row(
            Modifier.fillMaxWidth().height(59.dp).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.Close, "Back to projects", tint = Color.White)
            }
            IconButton(onClick = { onUnavailable("Media search") }) {
                Icon(Icons.Default.Search, "Search", tint = Color.LightGray)
            }
            Spacer(Modifier.weight(1f))
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color(0xFF292929),
                modifier = Modifier.clickable {
                    onUnavailable("AI UHD / AI upscaling")
                }
            ) {
                Text("AI UHD  ▾", modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp),
                    fontWeight = FontWeight.SemiBold, color = Color.White)
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { exportDialog = true },
                enabled = active.clips.isNotEmpty() && !processing,
                colors = ButtonDefaults.buttonColors(containerColor = ProCyan),
                shape = RoundedCornerShape(11.dp),
                contentPadding = PaddingValues(horizontal = 16.dp)
            ) {
                Text("Export", color = Color.Black, fontWeight = FontWeight.SemiBold)
            }
        }

        // Immersive player uses source aspect ratio; export/preview parity is tracked separately.
        Box(
            Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            if (clip == null) {
                Text("Add a video to begin editing", color = Color.LightGray)
            } else {
                AndroidView(
                    factory = { viewContext ->
                        PlayerView(viewContext).apply {
                            useController = false
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            setShutterBackgroundColor(android.graphics.Color.BLACK)
                        }
                    },
                    update = { view -> view.player = player },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().height(47.dp).padding(horizontal = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(onClick = { fullscreen = !fullscreen }) {
                Icon(if (fullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                    "Toggle fullscreen timeline", tint = Color.LightGray)
            }
            IconButton(onClick = {
                if (player.isPlaying) player.pause()
                else if (clip != null) {
                    if (player.currentPosition >= clip.outMs) player.seekTo(clip.inMs)
                    player.play()
                }
            }) {
                Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    "Play or pause", tint = Color.LightGray, modifier = Modifier.size(31.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onUnavailable("Preview comparison") }) {
                    Icon(Icons.Default.Compare, "Compare preview", tint = Color.Gray)
                }
                IconButton(onClick = onUndo, enabled = canUndo && !processing) {
                    Icon(Icons.Default.Undo, "Undo", tint = if (canUndo) Color.White else Color.Gray)
                }
                IconButton(onClick = onRedo, enabled = canRedo && !processing) {
                    Icon(Icons.Default.Redo, "Redo", tint = if (canRedo) Color.White else Color.Gray)
                }
            }
        }

        if (!fullscreen) {
            Column(
                Modifier.fillMaxWidth().height(245.dp).background(Color(0xFF161616))
            ) {
                Row(
                    Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${(playMs.coerceAtLeast(0)).asTimestamp()} / ${active.clips.sumOf { it.editedDurationMs }.asTimestamp()}",
                        color = Color.White, fontSize = 12.sp)
                    Spacer(Modifier.width(20.dp))
                    val total = active.clips.sumOf { it.editedDurationMs }
                    Row(Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(23.dp)) {
                        val maxTick = (total / 1000).coerceIn(4, 20).toInt()
                        (0..maxTick step 2).forEach { second ->
                            Text("00:${second.toString().padStart(2, '0')}",
                                color = Color.LightGray, fontSize = 11.sp)
                        }
                    }
                }

                Box(Modifier.fillMaxWidth().weight(1f)) {
                    Row(Modifier.fillMaxSize()) {
                        Column(
                            Modifier.width(105.dp).fillMaxHeight()
                                .background(Color(0xFF171717)).padding(start = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(7.dp),
                                modifier = Modifier.height(68.dp)
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.clickable {
                                        onChange(active.copy(muteOriginalAudio = !active.muteOriginalAudio))
                                    }
                                ) {
                                    Icon(if (active.muteOriginalAudio) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                                        "Mute source", tint = Color.LightGray)
                                    Text("Mute", fontSize = 10.sp, color = Color.White)
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.clickable { onUnavailable("AI clipper") }
                                ) {
                                    Icon(Icons.Default.ContentCut, "AI clipper", tint = ProCyan)
                                    Text("AI", fontSize = 10.sp, color = Color.White)
                                }
                            }
                            Icon(Icons.Default.MusicNote, "Audio lane", tint = Color.Gray,
                                modifier = Modifier.align(Alignment.End))
                            Icon(Icons.Default.TextFields, "Text lane", tint = Color.Gray,
                                modifier = Modifier.align(Alignment.End))
                        }
                        Column(Modifier.weight(1f)) {
                            LazyRow(
                                modifier = Modifier.fillMaxWidth().height(70.dp),
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                itemsIndexed(active.clips) { index, item ->
                                    Surface(
                                        color = Color(0xFF303030),
                                        border = BorderStroke(
                                            if (index == active.selectedIndex) 2.dp else 0.dp,
                                            if (index == active.selectedIndex) Color.White else Color.Transparent
                                        ),
                                        shape = RoundedCornerShape(5.dp),
                                        modifier = Modifier.width(
                                            ((item.editedDurationMs / 1000f) * 55f)
                                                .coerceIn(110f, 350f).dp
                                        ).fillMaxHeight().clickable {
                                            onChange(active.copy(selectedIndex = index))
                                            panel = "Edit"
                                        }
                                    ) {
                                        Box {
                                            Row(Modifier.fillMaxSize()) {
                                                repeat(4) {
                                                    GalleryThumbnail(item.sourceUri.toUri(),
                                                        Modifier.weight(1f).fillMaxHeight())
                                                }
                                            }
                                            Text(item.label, color = Color.White, fontSize = 10.sp,
                                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.align(Alignment.BottomStart)
                                                    .background(Color(0xA0000000))
                                                    .padding(horizontal = 5.dp, vertical = 2.dp))
                                        }
                                    }
                                }
                                item {
                                    Box(
                                        Modifier.size(66.dp).background(Color(0xFF333333),
                                            RoundedCornerShape(7.dp)).clickable(onClick = onMedia),
                                        contentAlignment = Alignment.Center
                                    ) { Icon(Icons.Default.Add, "Add video", tint = Color.White) }
                                }
                            }
                            Spacer(Modifier.height(7.dp))
                            Surface(
                                onClick = { onUnavailable("Audio tracks") },
                                color = Color(0xFF262626),
                                modifier = Modifier.fillMaxWidth().height(43.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 12.dp)) {
                                    Text("+  Add audio", color = Color.LightGray, fontSize = 13.sp)
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Surface(
                                onClick = { onUnavailable("Text tracks") },
                                color = Color(0xFF262626),
                                modifier = Modifier.fillMaxWidth().height(43.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 12.dp)) {
                                    Text("+  Add text", color = Color.LightGray, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                    // A central playhead gives a visual editing reference; precise sync is P2.
                    Box(
                        Modifier.align(Alignment.Center).padding(start = 105.dp)
                            .width(2.dp).fillMaxHeight().background(Color.White)
                    )
                }
            }
        }

        panel?.let { opened ->
            Column(
                Modifier.fillMaxWidth().background(Color(0xFF292929))
                    .heightIn(max = 175.dp).verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(opened, fontWeight = FontWeight.Bold, color = Color.White)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { panel = null }) {
                        Icon(Icons.Default.Close, "Close tools", tint = Color.LightGray)
                    }
                }
                when (opened) {
                    "Edit" -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.horizontalScroll(rememberScrollState())) {
                            ToolPill("Split") {
                                val split = active.splitSelected(playMs)
                                if (split == null) onInfo("Move playhead inside the clip before splitting.")
                                else onChange(split)
                            }
                            ToolPill("←") {
                                if (active.selectedIndex > 0) onChange(active.withMovedClip(
                                    active.selectedIndex, active.selectedIndex - 1))
                            }
                            ToolPill("→") {
                                if (active.selectedIndex < active.clips.lastIndex)
                                    onChange(active.withMovedClip(active.selectedIndex, active.selectedIndex + 1))
                            }
                            ToolPill("Delete") {
                                if (active.selectedClip != null) onChange(active.withRemovedClip(active.selectedIndex))
                            }
                        }
                        if (clip != null) {
                            Text("Trim start  ${clip.inMs.asTimestamp()}", color = Color.LightGray,
                                fontSize = 12.sp)
                            Slider(
                                value = clip.inMs.toFloat(),
                                valueRange = 0f..(clip.outMs - 100).toFloat(),
                                onValueChange = { change ->
                                    previewDraft = active.withReplacedClip(active.selectedIndex,
                                        clip.withStart(change.toLong()))
                                    player.seekTo(previewDraft?.selectedClip?.inMs ?: 0L)
                                },
                                onValueChangeFinished = {
                                    previewDraft?.let(onChange)
                                    previewDraft = null
                                }
                            )
                            Text("Trim end  ${clip.outMs.asTimestamp()}", color = Color.LightGray,
                                fontSize = 12.sp)
                            Slider(
                                value = clip.outMs.toFloat(),
                                valueRange = (clip.inMs + 100).toFloat()..clip.durationMs.toFloat(),
                                onValueChange = { change ->
                                    previewDraft = active.withReplacedClip(active.selectedIndex,
                                        clip.withEnd(change.toLong()))
                                },
                                onValueChangeFinished = {
                                    previewDraft?.let(onChange)
                                    previewDraft = null
                                }
                            )
                        }
                    }
                    "Filters" -> {
                        ToolChips(Look.entries.toList(), active.look, { onChange(active.copy(look = it)) }) {
                            it.label
                        }
                        Text("Source preview only. Exported MP4 applies chosen filter.",
                            color = Color.LightGray, fontSize = 11.sp)
                    }
                    "Canvas" -> ToolChips(CanvasRatio.entries.toList(), active.canvas,
                        { onChange(active.copy(canvas = it)) }) { it.label }
                    "Audio" -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = active.muteOriginalAudio,
                            onCheckedChange = { onChange(active.copy(muteOriginalAudio = it)) }
                        )
                        Text("Mute original audio", color = Color.White)
                    }
                    else -> Text("$opened tools are being implemented. No nonfunctional editing buttons.",
                        color = Color.LightGray)
                }
            }
        }

        LazyRow(
            modifier = Modifier.fillMaxWidth().height(89.dp)
                .background(Color(0xFF151515)),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 7.dp)
        ) {
            itemsIndexed(tools) { _, item ->
                Column(
                    modifier = Modifier.width(65.dp).fillMaxHeight()
                        .clickable {
                            panel = if (panel == item.name) null else item.name
                            if (item.name in listOf("Text", "Effects", "Overlay", "Captions", "Adjust", "AI"))
                                onUnavailable(item.name)
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceEvenly
                ) {
                    Icon(item.icon, item.name, modifier = Modifier.size(29.dp),
                        tint = if (panel == item.name) ProCyan else Color(0xFFD9D9D9))
                    Text(item.name, color = Color.White, fontSize = 12.sp)
                }
            }
        }
    }

    if (exportDialog) {
        AlertDialog(
            onDismissRequest = { if (!processing) exportDialog = false },
            title = { Text(if (processing) "Exporting" else "Export settings") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    if (processing) {
                        LinearProgressIndicator(progress = { progress / 100f },
                            modifier = Modifier.fillMaxWidth())
                        Text("$progress% · Keep TongCut open")
                    } else {
                        Text("Short-edge resolution (device dependent)")
                        ToolChips(listOf(720, 1080, 2160), exportResolution,
                            { exportResolution = it }) { "${it}p" }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = muteOriginal,
                                onCheckedChange = { muteOriginal = it })
                            Text("Mute source audio")
                        }
                        lastExport?.let { uri ->
                            TextButton(onClick = {
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(uri, "video/mp4")
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    })
                                }.onFailure { onInfo("Saved to Movies/TongCut.") }
                            }) { Text("Open last exported video") }
                        }
                    }
                }
            },
            confirmButton = {
                if (!processing) Button(
                    enabled = active.clips.isNotEmpty(),
                    onClick = {
                        val inputs = runCatching {
                            active.clips.map {
                                Clip(Uri.parse(it.sourceUri), it.label, it.durationMs,
                                    it.inMs, it.outMs)
                            }
                        }
                        if (inputs.isFailure) {
                            onInfo("Cannot export: ${inputs.exceptionOrNull()?.message}")
                            return@Button
                        }
                        processing = true
                        progress = 0
                        exporter.start(
                            inputs.getOrThrow(),
                            active.exportOptions().copy(
                                shortSidePixels = exportResolution,
                                mute = muteOriginal
                            ),
                            onProgress = { progress = it },
                            onSuccess = {
                                processing = false
                                lastExport = it
                                exportDialog = false
                                onInfo("Exported to Movies/TongCut")
                            },
                            onError = {
                                processing = false
                                onInfo("Export failed: $it")
                            }
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ProCyan)
                ) { Text("Export MP4", color = Color.Black) }
            },
            dismissButton = {
                TextButton(onClick = {
                    if (processing) {
                        if (exporter.cancel()) {
                            processing = false
                            onInfo("Export cancelled")
                        }
                    } else exportDialog = false
                }) { Text(if (processing) "Cancel export" else "Close") }
            }
        )
    }
}

@Composable
private fun ToolPill(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, shape = RoundedCornerShape(7.dp),
        contentPadding = PaddingValues(horizontal = 13.dp, vertical = 2.dp)) {
        Text(label)
    }
}

@Composable
private fun <T> ToolChips(
    values: List<T>, selected: T, onSelected: (T) -> Unit, label: (T) -> String
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        items(values.size) { index ->
            val value = values[index]
            FilterChip(
                selected = selected == value, onClick = { onSelected(value) },
                label = { Text(label(value)) }
            )
        }
    }
}
