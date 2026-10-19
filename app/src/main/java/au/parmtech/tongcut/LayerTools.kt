package au.parmtech.tongcut

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Real model operations. Buttons either change persistent layer state or are clearly disabled. */
@Composable
internal fun LayerTools(
    draft: EditorDraft,
    playheadMs: Long,
    kind: LayerKind,
    onChange: (EditorDraft) -> Unit,
    onAddAudio: () -> Unit,
    onError: (String) -> Unit
) {
    var editingId by remember { mutableStateOf<String?>(null) }
    var entry by remember { mutableStateOf("") }
    val layers = draft.layers.layers
        .filter { it.kind == kind }
        .sortedWith(compareBy<TimelineLayer> { it.startUs }.thenBy { it.id })

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (kind == LayerKind.TEXT) "Text overlays" else "Audio layers",
                color = Color.White,
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = {
                if (kind == LayerKind.TEXT) {
                    editingId = ""
                    entry = ""
                } else onAddAudio()
            }) {
                Text(if (kind == LayerKind.TEXT) "+ Add text" else "+ Add audio", color = ProCyan)
            }
        }
        layers.forEach { layer ->
            val track = draft.layers.tracks.first { it.id == layer.trackId }
            Column(
                Modifier.fillMaxWidth().background(Color(0xFF373737), RoundedCornerShape(9.dp))
                    .padding(horizontal = 9.dp, vertical = 4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (kind == LayerKind.TEXT) layer.text.orEmpty() else "Audio clip",
                        color = if (track.hidden) Color.Gray else Color.White,
                        modifier = Modifier.weight(1f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp
                    )
                    Text(
                        "${layer.startUs / 1_000_000.0}s · ${layer.durationUs / 1_000_000.0}s",
                        fontSize = 10.sp, color = Color.LightGray
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    if (kind == LayerKind.TEXT) {
                        SmallLayerButton("Edit") { editingId = layer.id; entry = layer.text.orEmpty() }
                    }
                    SmallLayerButton("◀ 0.5s") {
                        runCatching {
                            LayerEdits.placeLayer(draft, layer.id, (layer.startUs / 1000L - 500).coerceAtLeast(0))
                        }.onSuccess(onChange).onFailure { onError(it.message ?: "Move failed") }
                    }
                    SmallLayerButton("0.5s ▶") {
                        runCatching {
                            LayerEdits.placeLayer(draft, layer.id, layer.startUs / 1000L + 500)
                        }.onSuccess(onChange).onFailure { onError(it.message ?: "Move failed") }
                    }
                    SmallLayerButton("− Duration") {
                        runCatching { LayerEdits.trimLayer(draft, layer.id, layer.durationUs - 500_000L) }
                            .onSuccess(onChange).onFailure { onError(it.message ?: "Cannot trim") }
                    }
                    SmallLayerButton("+ Duration") {
                        val end = SequentialTimeline(draft.clips).totalMs * 1000L
                        runCatching {
                            LayerEdits.trimLayer(
                                draft, layer.id, minOf(layer.durationUs + 500_000L, end - layer.startUs)
                            )
                        }.onSuccess(onChange).onFailure { onError(it.message ?: "Cannot extend") }
                    }
                    SmallLayerButton(if (track.hidden) "Show" else "Hide") {
                        onChange(LayerEdits.setTrackVisibility(draft, track.id, !track.hidden))
                    }
                    if (kind == LayerKind.AUDIO) {
                        SmallLayerButton(if (track.muted) "Unmute" else "Mute") {
                            onChange(LayerEdits.setTrackMuted(draft, track.id, !track.muted))
                        }
                        SmallLayerButton("Vol −") {
                            onChange(LayerEdits.setAudioGain(draft, track.id,
                                (track.gain - 0.1f).coerceAtLeast(0f)))
                        }
                        Text("${(track.gain * 100).toInt()}%", color = Color.LightGray, fontSize = 11.sp)
                        SmallLayerButton("Vol +") {
                            onChange(LayerEdits.setAudioGain(draft, track.id,
                                (track.gain + 0.1f).coerceAtMost(1f)))
                        }
                    }
                    SmallLayerButton("Delete") {
                        runCatching { LayerEdits.remove(draft, layer.id) }
                            .onSuccess(onChange).onFailure { onError(it.message ?: "Delete failed") }
                    }
                }
            }
        }
    }

    editingId?.let { selected ->
        AlertDialog(
            onDismissRequest = { editingId = null },
            title = { Text(if (selected.isEmpty()) "Add text" else "Edit text") },
            text = {
                OutlinedTextField(
                    value = entry,
                    onValueChange = { entry = it.take(300) },
                    label = { Text("Caption (up to 300 characters)") },
                    maxLines = 4
                )
            },
            confirmButton = {
                TextButton(
                    enabled = entry.isNotBlank(),
                    onClick = {
                        runCatching {
                            if (selected.isEmpty()) LayerEdits.addText(draft, playheadMs, entry)
                            else LayerEdits.changeText(draft, selected, entry)
                        }.onSuccess {
                            onChange(it)
                            editingId = null
                        }.onFailure { onError(it.message ?: "Text edit failed") }
                    }
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { editingId = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun SmallLayerButton(label: String, click: () -> Unit) {
    TextButton(
        onClick = click,
        contentPadding = PaddingValues(horizontal = 7.dp, vertical = 2.dp),
        modifier = Modifier.heightIn(min = 35.dp)
    ) {
        Text(label, color = ProCyan, fontSize = 11.sp)
    }
}
