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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlin.math.min

class MainActivity : ComponentActivity() {
    private val exporter by lazy { VideoExporter(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val colors = darkColorScheme(
                primary = Color(0xFFB4FA4F),
                onPrimary = Color(0xFF0D1308),
                background = Color(0xFF0A0D11),
                surface = Color(0xFF171C22),
                onSurface = Color(0xFFEAF1F5)
            )
            MaterialTheme(colorScheme = colors) { EditorScreen(exporter) }
        }
    }

    override fun onDestroy() {
        exporter.cancel()
        super.onDestroy()
    }
}

@Composable
private fun EditorScreen(exporter: VideoExporter) {
    val context = LocalContext.current
    val clips = remember { mutableStateListOf<Clip>() }
    var activeIndex by remember { mutableIntStateOf(0) }
    var look by remember { mutableStateOf(Look.ORIGINAL) }
    var ratio by remember { mutableStateOf(CanvasRatio.SOURCE) }
    var resolution by remember { mutableIntStateOf(1080) }
    var mute by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var feedback by remember { mutableStateOf("Import videos to begin. All edits stay on your device.") }
    var exported by remember { mutableStateOf<Uri?>(null) }
    val chosen = clips.getOrNull(activeIndex)

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        var count = 0
        uris.forEach { uri ->
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) { /* The immediate grant can still be used. */ }
            val clip = runCatching { inspectClip(context, uri) }.getOrNull()
            if (clip != null) {
                clips.add(clip)
                count++
            }
        }
        if (count > 0) activeIndex = clips.lastIndex
        feedback = if (count > 0) "Imported $count video(s). Tap a clip to edit it."
            else "No compatible videos selected. Try MP4/H.264."
    }
    val player = remember(context) { ExoPlayer.Builder(context).build() }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(chosen?.uri) {
        player.pause()
        if (chosen != null) {
            player.setMediaItem(MediaItem.fromUri(chosen.uri))
            player.prepare()
            player.seekTo(chosen.startMs)
        } else {
            player.clearMediaItems()
        }
    }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("TONG", style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Black)
            Text("CUT", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
            Spacer(Modifier.weight(1f))
            Text("ALPHA 0.1", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary)
        }
        Text("An offline Android editing studio", style = MaterialTheme.typography.bodySmall)
        Button(onClick = { picker.launch(arrayOf("video/*")) }, enabled = !busy,
            modifier = Modifier.fillMaxWidth()) { Text("+ Add videos") }

        Surface(shape = RoundedCornerShape(16.dp), color = Color.Black) {
            if (chosen == null) {
                Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
                    Text("Your video preview appears here", color = Color.LightGray)
                }
            } else {
                AndroidView(
                    factory = { viewContext -> PlayerView(viewContext).apply {
                        this.player = player
                        useController = true
                    } },
                    update = { it.player = player },
                    modifier = Modifier.fillMaxWidth().height(280.dp)
                )
            }
        }
        Text("Preview shows original footage; filters and crop apply in exported MP4.",
            style = MaterialTheme.typography.bodySmall, color = Color.LightGray)

        if (clips.isNotEmpty()) {
            SectionTitle("Timeline · ${clips.size} clips")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(clips) { index, clip ->
                    FilterChip(
                        selected = activeIndex == index,
                        onClick = { activeIndex = index },
                        label = { Text("${index + 1}. ${clip.label.take(15)} · ${clip.trimmedDurationMs.asTimestamp()}") }
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    if (activeIndex > 0) {
                        val old = clips.removeAt(activeIndex)
                        clips.add(activeIndex - 1, old)
                        activeIndex--
                    }
                }, enabled = !busy && activeIndex > 0) { Text("← Move") }
                OutlinedButton(onClick = {
                    if (activeIndex < clips.lastIndex) {
                        val old = clips.removeAt(activeIndex)
                        clips.add(activeIndex + 1, old)
                        activeIndex++
                    }
                }, enabled = !busy && activeIndex < clips.lastIndex) { Text("Move →") }
                OutlinedButton(onClick = {
                    clips.removeAt(activeIndex)
                    activeIndex = min(activeIndex, clips.lastIndex).coerceAtLeast(0)
                }, enabled = !busy) { Text("Remove") }
            }
            if (chosen != null) {
                SectionTitle("Trim · ${chosen.label}")
                Text("Start: ${chosen.startMs.asTimestamp()} / ${chosen.durationMs.asTimestamp()}")
                Slider(
                    value = chosen.startMs.toFloat(),
                    onValueChange = { input ->
                        clips[activeIndex] = chosen.trimStart(input.toLong())
                        player.seekTo(clips[activeIndex].startMs)
                    },
                    valueRange = 0f..(chosen.endMs - 100).toFloat(),
                    enabled = !busy
                )
                Text("End: ${chosen.endMs.asTimestamp()} / ${chosen.durationMs.asTimestamp()}")
                Slider(
                    value = chosen.endMs.toFloat(),
                    onValueChange = { input -> clips[activeIndex] = chosen.trimEnd(input.toLong()) },
                    valueRange = (chosen.startMs + 100).toFloat()..chosen.durationMs.toFloat(),
                    enabled = !busy
                )
            }
        }
        SectionTitle("Color look")
        OptionPicker(Look.entries.toList(), look, { look = it }, { it.label }, busy)
        SectionTitle("Canvas crop")
        OptionPicker(CanvasRatio.entries.toList(), ratio, { ratio = it }, { it.label }, busy)
        SectionTitle("Export resolution · short edge")
        OptionPicker(listOf(720, 1080, 2160), resolution, { resolution = it }, { "${it}p" }, busy)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = mute, onCheckedChange = { mute = it }, enabled = !busy)
            Text("Mute original audio")
        }
        HorizontalDivider()
        if (busy) {
            LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
            Text("Exporting... $progress% · Keep TongCut open")
            OutlinedButton(onClick = {
                if (exporter.cancel()) {
                    busy = false
                    feedback = "Export cancelled"
                } else feedback = "Finalising gallery save; cannot cancel this step."
            }) { Text("Cancel export") }
        } else {
            Button(
                enabled = clips.isNotEmpty(), modifier = Modifier.fillMaxWidth(),
                onClick = {
                    busy = true
                    progress = 0
                    exported = null
                    feedback = "Export started"
                    exporter.start(
                        clips.toList(), ExportOptions(look, ratio, resolution, mute),
                        onProgress = { progress = it },
                        onSuccess = { uri ->
                            busy = false
                            feedback = "Saved to Movies/TongCut"
                            exported = uri
                        },
                        onError = { msg -> busy = false; feedback = msg }
                    )
                }
            ) { Text("Export MP4") }
        }
        Text(feedback, style = MaterialTheme.typography.bodySmall)
        exported?.let { uri ->
            OutlinedButton(onClick = {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "video/mp4")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runCatching { context.startActivity(intent) }
                    .onFailure { feedback = "Saved video; open it from Movies/TongCut." }
            }) { Text("Open exported video") }
        }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun <T> OptionPicker(
    options: List<T>, selected: T, onSelect: (T) -> Unit,
    caption: (T) -> String, disabled: Boolean
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(options.size) { i ->
            val option = options[i]
            FilterChip(
                selected = selected == option,
                onClick = { onSelect(option) },
                enabled = !disabled,
                label = { Text(caption(option)) }
            )
        }
    }
}

private fun inspectClip(context: android.content.Context, uri: Uri): Clip? {
    val title = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME),
        null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    } ?: "Video"
    val duration = MediaMetadataRetriever().use { retriever ->
        retriever.setDataSource(context, uri)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
    } ?: return null
    if (duration < 100) return null
    return Clip(uri, title, duration)
}
