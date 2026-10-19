package au.parmtech.tongcut

import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

class MainActivity : ComponentActivity() {
    private val exporter by lazy { VideoExporter(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFFB6F650),
                    onPrimary = Color(0xFF10170A),
                    background = Color(0xFF0D0F12),
                    surface = Color(0xFF1D222A),
                    surfaceVariant = Color(0xFF272D37),
                    onSurface = Color(0xFFF1F4F6),
                    onSurfaceVariant = Color(0xFFBEC7D0)
                )
            ) {
                EditorWorkspace(exporter)
            }
        }
    }

    override fun onDestroy() {
        exporter.cancel()
        super.onDestroy()
    }
}

private fun DraftClip.asMediaClip() = Clip(
    uri = Uri.parse(sourceUri),
    label = label,
    durationMs = durationMs,
    startMs = inMs,
    endMs = outMs
)

@Composable
private fun EditorWorkspace(exporter: VideoExporter) {
    val context = LocalContext.current
    val store = remember(context) { ProjectStore(context.applicationContext) }
    val history = remember { EditHistory(EditorDraft()) }
    var draft by remember { mutableStateOf(EditorDraft()) }
    var loaded by remember { mutableStateOf(false) }
    var gestureStart by remember { mutableStateOf<EditorDraft?>(null) }
    var tool by remember { mutableStateOf(EditorTool.EDIT) }
    var feedback by remember { mutableStateOf("Import videos to create a project.") }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var lastExport by remember { mutableStateOf<Uri?>(null) }
    val selected = draft.selectedClip

    fun finishTrimGesture() {
        val before = gestureStart ?: return
        gestureStart = null
        if (before != draft) history.record(before, draft)
    }
    fun commit(updated: EditorDraft) {
        if (busy || !loaded) return
        finishTrimGesture()
        val safe = updated.normalized()
        if (history.apply(safe)) draft = safe
    }
    fun undo() {
        if (busy) return
        finishTrimGesture()
        history.undo()?.let { draft = it }
    }
    fun redo() {
        if (busy) return
        finishTrimGesture()
        history.redo()?.let { draft = it }
    }

    // Load once. Corrupt drafts are NOT overwritten: users are warned, and saving is disabled.
    var saveAllowed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try {
            val recovered = withContext(Dispatchers.IO) { store.load() }
            if (recovered != null) {
                draft = recovered
                history.reset(recovered)
                feedback = "Recovered draft • ${recovered.clips.size} clip(s)"
            } else feedback = "New project • imports stay on your device"
            saveAllowed = true
        } catch (error: Exception) {
            feedback = "Cannot open draft: ${error.message}. Existing file was not overwritten."
            saveAllowed = false
        } finally {
            loaded = true
        }
    }
    LaunchedEffect(draft, loaded, saveAllowed) {
        if (loaded && saveAllowed) {
            delay(650) // coalesce edits and seek/trim previews into one atomic save
            try {
                withContext(Dispatchers.IO) { store.save(draft) }
            } catch (error: Exception) {
                feedback = "Autosave failed: ${error.message}"
            }
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (!loaded || busy || !saveAllowed) return@rememberLauncherForActivityResult
        val added = mutableListOf<DraftClip>()
        var nonPersistent = 0
        uris.forEach { uri ->
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                nonPersistent++
            }
            runCatching { inspectClip(context, uri) }.getOrNull()?.let { clip ->
                added += DraftClip(
                    sourceUri = clip.uri.toString(),
                    label = clip.label,
                    durationMs = clip.durationMs
                )
            }
        }
        if (added.isNotEmpty()) {
            commit(draft.copy(
                clips = draft.clips + added,
                selectedIndex = draft.clips.size
            ))
            feedback = "Imported ${added.size} video(s)" +
                if (nonPersistent > 0) " • Some file access might expire after restart" else ""
        } else if (uris.isNotEmpty()) {
            feedback = "No readable videos found."
        }
    }

    val player = remember(context) { ExoPlayer.Builder(context).build() }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(draft.selectedIndex, selected?.sourceUri) {
        player.pause()
        if (selected == null) player.clearMediaItems()
        else {
            player.setMediaItem(MediaItem.fromUri(Uri.parse(selected.sourceUri)))
            player.prepare()
            player.seekTo(selected.inMs)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding().navigationBarsPadding()
    ) {
        // Fixed toolbar does not slide offscreen while editing.
        Row(
            Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("TONG", color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
            Text("CUT", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = ::undo, enabled = loaded && history.canUndo && !busy) {
                Text("↶", style = MaterialTheme.typography.headlineSmall)
            }
            TextButton(onClick = ::redo, enabled = loaded && history.canRedo && !busy) {
                Text("↷", style = MaterialTheme.typography.headlineSmall)
            }
            Button(
                onClick = { tool = EditorTool.EXPORT },
                enabled = loaded && draft.clips.isNotEmpty(),
                contentPadding = PaddingValues(horizontal = 13.dp, vertical = 4.dp)
            ) { Text("Export") }
        }

        // Preview uses the source stream. Multi-layer preview will use the shared render graph.
        Box(
            Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                color = Color.Black,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                if (selected == null) Box(Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center) {
                    Text("Tap + to import video", color = Color.LightGray)
                } else AndroidView(
                    factory = { viewContext ->
                        PlayerView(viewContext).apply {
                            this.player = player
                            useController = true
                        }
                    },
                    update = { it.player = player },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        Row(
            Modifier.fillMaxWidth().height(45.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                selected?.label ?: "No media",
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                selected?.editedDurationMs?.asTimestamp() ?: "00:00",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            OutlinedButton(
                onClick = { picker.launch(arrayOf("video/*")) },
                enabled = loaded && !busy && saveAllowed,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
            ) { Text("+ Media") }
        }

        // First iteration of a dedicated horizontally-scrolling timeline.
        Column(Modifier.fillMaxWidth().height(111.dp).padding(start = 12.dp, end = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("VIDEO 1", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text("${draft.clips.size} clips • ${draft.clips.sumOf { it.editedDurationMs }.asTimestamp()}",
                    style = MaterialTheme.typography.labelSmall)
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(draft.clips) { index, clip ->
                    val width = (75f + clip.editedDurationMs / 1000f * 6f).coerceIn(90f, 235f)
                    FilterChip(
                        selected = index == draft.selectedIndex,
                        enabled = loaded && !busy,
                        onClick = { finishTrimGesture(); commit(draft.copy(selectedIndex = index)) },
                        modifier = Modifier.width(width.dp).height(68.dp),
                        label = {
                            Column {
                                Text("${index + 1}  ${clip.label}", maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelMedium)
                                Text(clip.editedDurationMs.asTimestamp(),
                                    style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    )
                }
            }
            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.surfaceVariant)
        }

        // Stable tool dock; editor controls scroll *inside* their own sheet only.
        ScrollableTabRow(
            selectedTabIndex = tool.ordinal,
            edgePadding = 7.dp,
            containerColor = MaterialTheme.colorScheme.background,
            divider = {}
        ) {
            EditorTool.entries.forEach { item ->
                Tab(
                    selected = tool == item,
                    onClick = { finishTrimGesture(); tool = item },
                    text = { Text(item.label, style = MaterialTheme.typography.labelMedium) }
                )
            }
        }
        Column(
            Modifier.fillMaxWidth().height(160.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            when (tool) {
                EditorTool.EDIT -> {
                    if (selected == null) {
                        Text("Import videos. Select a clip to edit, split or reorder it.")
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    val split = draft.splitSelected(player.currentPosition)
                                    if (split == null) feedback = "Move playhead inside clip before splitting"
                                    else {
                                        commit(split)
                                        feedback = "Clip split without changing source file"
                                    }
                                }, enabled = !busy
                            ) { Text("Split") }
                            OutlinedButton(
                                onClick = {
                                    if (draft.selectedIndex > 0) commit(draft.withMovedClip(
                                        draft.selectedIndex, draft.selectedIndex - 1
                                    ))
                                }, enabled = !busy && draft.selectedIndex > 0
                            ) { Text("←") }
                            OutlinedButton(
                                onClick = {
                                    if (draft.selectedIndex < draft.clips.lastIndex) commit(
                                        draft.withMovedClip(draft.selectedIndex, draft.selectedIndex + 1)
                                    )
                                }, enabled = !busy && draft.selectedIndex < draft.clips.lastIndex
                            ) { Text("→") }
                            OutlinedButton(
                                onClick = { commit(draft.withRemovedClip(draft.selectedIndex)) },
                                enabled = !busy
                            ) { Text("Delete") }
                        }
                        Text("Trim start • ${selected.inMs.asTimestamp()}")
                        Slider(
                            value = selected.inMs.toFloat(),
                            valueRange = 0f..max(0f, (selected.outMs - 100).toFloat()),
                            enabled = !busy,
                            onValueChange = { value ->
                                if (gestureStart == null) gestureStart = draft
                                draft = draft.withReplacedClip(draft.selectedIndex,
                                    selected.withStart(value.toLong()))
                                player.seekTo(draft.selectedClip?.inMs ?: 0)
                            },
                            onValueChangeFinished = ::finishTrimGesture
                        )
                        Text("Trim end • ${selected.outMs.asTimestamp()}")
                        Slider(
                            value = selected.outMs.toFloat(),
                            valueRange = (selected.inMs + 100).toFloat()..selected.durationMs.toFloat(),
                            enabled = !busy,
                            onValueChange = { value ->
                                if (gestureStart == null) gestureStart = draft
                                draft = draft.withReplacedClip(draft.selectedIndex,
                                    selected.withEnd(value.toLong()))
                            },
                            onValueChangeFinished = ::finishTrimGesture
                        )
                    }
                }
                EditorTool.COLOR -> {
                    Text("Colour look • applied during MP4 export")
                    PickOptions(
                        Look.entries.toList(), draft.look, !busy,
                        onSelect = { commit(draft.copy(look = it)) },
                        caption = { it.label }
                    )
                }
                EditorTool.CANVAS -> {
                    Text("Canvas • frame and aspect")
                    PickOptions(
                        CanvasRatio.entries.toList(), draft.canvas, !busy,
                        onSelect = { commit(draft.copy(canvas = it)) },
                        caption = { it.label }
                    )
                }
                EditorTool.EXPORT -> {
                    Text("Resolution • short edge (depends on phone codec)")
                    PickOptions(
                        listOf(720, 1080, 2160), draft.shortSidePixels, !busy,
                        onSelect = { commit(draft.copy(shortSidePixels = it)) },
                        caption = { "${it}p" }
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = draft.muteOriginalAudio,
                            onCheckedChange = { commit(draft.copy(muteOriginalAudio = it)) },
                            enabled = !busy
                        )
                        Text("Mute original audio")
                    }
                }
            }
        }

        if (busy) {
            LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (busy) {
                OutlinedButton(onClick = {
                    if (exporter.cancel()) {
                        busy = false
                        feedback = "Export cancelled"
                    } else feedback = "Saving to gallery; please wait"
                }) { Text("Cancel") }
                Text("Exporting $progress%")
            } else {
                Button(
                    enabled = loaded && draft.clips.isNotEmpty() && saveAllowed,
                    onClick = {
                        finishTrimGesture()
                        val input = runCatching { draft.clips.map { it.asMediaClip() } }
                        if (input.isFailure) {
                            feedback = "Invalid input: ${input.exceptionOrNull()?.message}"
                        } else {
                            busy = true
                            progress = 0
                            lastExport = null
                            exporter.start(
                                input.getOrThrow(), draft.exportOptions(),
                                onProgress = { progress = it },
                                onSuccess = { uri ->
                                    busy = false
                                    lastExport = uri
                                    feedback = "Saved video to Movies/TongCut"
                                },
                                onError = { message ->
                                    busy = false
                                    feedback = message
                                }
                            )
                        }
                    }, modifier = Modifier.weight(1f)
                ) { Text("Export MP4") }
            }
            lastExport?.let { uri ->
                TextButton(onClick = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "video/mp4")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        })
                    }.onFailure { feedback = "Video saved; find it in Movies/TongCut" }
                }) { Text("Open") }
            }
        }
        Text(
            feedback,
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 5.dp),
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private enum class EditorTool(val label: String) {
    EDIT("Edit"), COLOR("Color"), CANVAS("Canvas"), EXPORT("Settings")
}

@Composable
private fun <T> PickOptions(
    options: List<T>, selected: T, enabled: Boolean,
    onSelect: (T) -> Unit, caption: (T) -> String
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(options.size) { index ->
            val item = options[index]
            FilterChip(
                selected = item == selected,
                enabled = enabled,
                onClick = { onSelect(item) },
                label = { Text(caption(item)) }
            )
        }
    }
}

private fun inspectClip(context: android.content.Context, uri: Uri): Clip? {
    val title = context.contentResolver.query(
        uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
    )?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    } ?: "Video"
    val duration = MediaMetadataRetriever().use { retriever ->
        retriever.setDataSource(context, uri)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
    } ?: return null
    if (duration < 100) return null
    return Clip(uri, title, duration)
}
