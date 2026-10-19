package au.parmtech.tongcut

import android.content.Intent
import android.net.Uri
import android.media.MediaMetadataRetriever
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
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
    val audioMonitor = remember(context) { AudioOverlayEngine(context) }
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

    val scope = rememberCoroutineScope()
    val missingMedia = remember { mutableStateListOf<Int>() }
    var relinkIndex by remember { mutableIntStateOf(-1) }
    val relinkPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null && relinkIndex in draft.clips.indices) {
            val targetIndex = relinkIndex
            relinkIndex = -1
            scope.launch {
                try {
                    runCatching {
                        context.contentResolver.takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    }
                    val candidate = withContext(Dispatchers.IO) {
                        val name = context.contentResolver.query(
                            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
                        )?.use { cursor ->
                            if (cursor.moveToFirst()) cursor.getString(0) else null
                        } ?: "Relinked video"
                        val duration = MediaMetadataRetriever().use { retriever ->
                            retriever.setDataSource(context, uri)
                            retriever.extractMetadata(
                                MediaMetadataRetriever.METADATA_KEY_DURATION
                            )?.toLongOrNull()
                        } ?: throw IllegalArgumentException("Unreadable replacement media")
                        Triple(uri.toString(), name, duration)
                    }
                    val oldClip = draft.clips.getOrNull(targetIndex)
                        ?: throw IllegalStateException("Selected clip is no longer available")
                    val next = oldClip.relink(
                        candidate.first, candidate.second, candidate.third
                    )
                    onChange(draft.withReplacedClip(targetIndex, next))
                    onInfo("Reconnected media without deleting your edits.")
                } catch (failure: Exception) {
                    onInfo("Relink failed: ${failure.message}")
                }
            }
        }
    }

    val audioPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    runCatching {
                        context.contentResolver.takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    }
                    val duration = withContext(Dispatchers.IO) {
                        MediaMetadataRetriever().use { retriever ->
                            retriever.setDataSource(context, uri)
                            retriever.extractMetadata(
                                MediaMetadataRetriever.METADATA_KEY_DURATION
                            )?.toLongOrNull()
                        }
                    } ?: throw IllegalArgumentException("Audio duration not available")
                    onChange(LayerEdits.addAudio(draft, playMs, uri.toString(), duration))
                    panel = "Audio"
                    onInfo("Audio added. Preview follows the playhead; mix export needs device verification.")
                } catch (error: Exception) {
                    onInfo("Audio import failed: ${error.message}")
                }
            }
        }
    }

    LaunchedEffect(draft.clips) {
        val missing = withContext(Dispatchers.IO) {
            draft.clips.mapIndexedNotNull { index, item ->
                val accessible = runCatching {
                    context.contentResolver.openFileDescriptor(
                        Uri.parse(item.sourceUri), "r"
                    )?.use { true } ?: false
                }.getOrDefault(false)
                if (accessible) null else index
            }
        }
        missingMedia.clear()
        missingMedia.addAll(missing)
    }


    val active = previewDraft ?: draft
    val clip = active.selectedClip
    DisposableEffect(player) { onDispose { player.release() } }
    DisposableEffect(audioMonitor) { onDispose { audioMonitor.release() } }
    val editTimeline = remember(active.clips) { SequentialTimeline(active.clips) }

    // A single ExoPlayer playlist plays trimmed media clips consecutively.
    // source URI and clipping window are the same values sent to Transformer.
    LaunchedEffect(active.clips, active.selectedIndex) {
        val resume = player.isPlaying
        player.pause()
        playing = false
        val mediaItems = active.clips.map { item ->
            MediaItem.Builder().setUri(item.sourceUri.toUri())
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(item.inMs)
                        .setEndPositionMs(item.outMs)
                        .build()
                ).build()
        }
        if (mediaItems.isEmpty()) {
            player.clearMediaItems()
            playMs = 0L
        } else {
            player.setMediaItems(mediaItems)
            player.prepare()
            val selected = active.selectedIndex.coerceIn(0, mediaItems.lastIndex)
            player.seekTo(selected, 0)
            playMs = editTimeline.startOf(selected)
            if (resume) player.play()
        }
    }
    LaunchedEffect(player, editTimeline, active.layers) {
        var lastAudioWarning: String? = null
        while (true) {
            if (player.mediaItemCount > 0 && player.currentMediaItemIndex in editTimeline.clips.indices) {
                val index = player.currentMediaItemIndex
                playMs = editTimeline.timeOf(index, player.currentPosition)
            }
            playing = player.isPlaying
            val warning = runCatching {
                audioMonitor.sync(active.layers, playMs, playing)
            }.getOrElse { "Audio preview unavailable: ${it.message ?: "decoder failed"}" }
            if (warning != null && warning != lastAudioWarning) onInfo(warning)
            lastAudioWarning = warning
            delay(90)
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
                onClick = {
                    if (active.canExportWithLegacyRenderer) exportDialog = true
                    else onUnavailable("Layered preview/export compositor")
                },
                enabled = active.clips.isNotEmpty() && !processing,
                colors = ButtonDefaults.buttonColors(containerColor = ProCyan),
                shape = RoundedCornerShape(11.dp),
                contentPadding = PaddingValues(horizontal = 16.dp)
            ) {
                Text("Export", color = Color.Black, fontWeight = FontWeight.SemiBold)
            }
        }

        if (missingMedia.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().height(43.dp).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Warning, "Media warning", tint = Color(0xFFFFCC60))
                Spacer(Modifier.width(6.dp))
                Text("${missingMedia.size} missing media file(s)",
                    color = Color(0xFFFFCC60), fontSize = 12.sp,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    relinkIndex = if (draft.selectedIndex in missingMedia) {
                        draft.selectedIndex
                    } else missingMedia.first()
                    relinkPicker.launch(arrayOf("video/*"))
                }) { Text("Relink", color = ProCyan) }
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
            val visibleText = active.layers.activeAt(playMs * 1_000L)
                .filter { it.kind == LayerKind.TEXT }
                .mapNotNull { it.text }
            if (visibleText.isNotEmpty()) {
                Column(
                    modifier = Modifier.align(Alignment.Center)
                        .fillMaxWidth(0.88f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    visibleText.forEach { line ->
                        Text(
                            line,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 21.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
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
                else if (active.clips.isNotEmpty()) {
                    if (playMs >= editTimeline.totalMs) player.seekTo(0, 0)
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
            SequenceStrip(
                draft = active,
                positionMs = playMs,
                onSeek = { point ->
                    player.pause()
                    player.seekTo(point.clipIndex, point.offsetMs)
                    playMs = point.timelineMs
                },
                onSelect = { index ->
                    if (index != active.selectedIndex) onChange(active.copy(selectedIndex = index))
                    else {
                        player.pause()
                        player.seekTo(index, 0)
                        playMs = editTimeline.startOf(index)
                    }
                    panel = "Edit"
                },
                onAddMedia = onMedia,
                onAddText = { panel = "Text" },
                onAddAudio = { audioPicker.launch(arrayOf("audio/*")) },
                onLayerSelect = { panel = if (it == LayerKind.TEXT) "Text" else "Audio" },
                onUnavailable = onUnavailable
            )
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
                                val at = editTimeline.pointAt(playMs)
                                val split = at?.let {
                                    active.copy(selectedIndex = it.clipIndex).splitSelected(it.sourceMs)
                                }
                                if (split == null) onInfo("Move playhead inside a clip before splitting.")
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
                            ToolPill("Relink") {
                                relinkIndex = active.selectedIndex
                                relinkPicker.launch(arrayOf("video/*"))
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
                    "Audio" -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = active.muteOriginalAudio,
                                onCheckedChange = { onChange(active.copy(muteOriginalAudio = it)) }
                            )
                            Text("Mute original audio", color = Color.White)
                        }
                        LayerTools(
                            active, playMs, LayerKind.AUDIO, onChange,
                            onAddAudio = { audioPicker.launch(arrayOf("audio/*")) },
                            onError = onInfo
                        )
                    }
                    "Text" -> LayerTools(
                        active, playMs, LayerKind.TEXT, onChange,
                        onAddAudio = { audioPicker.launch(arrayOf("audio/*")) },
                        onError = onInfo
                    )
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
                            if (item.name in listOf("Effects", "Overlay", "Captions", "Adjust", "AI"))
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
                    enabled = active.clips.isNotEmpty() && active.canExportWithLegacyRenderer,
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
