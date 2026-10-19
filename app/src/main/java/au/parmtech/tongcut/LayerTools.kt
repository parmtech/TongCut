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
    onAddVisual: (LayerKind) -> Unit = {},
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
                when (kind) {
                    LayerKind.TEXT -> "Text overlays"
                    LayerKind.AUDIO -> "Audio layers"
                    LayerKind.VIDEO -> "Video overlays"
                    LayerKind.IMAGE -> "Photo overlays"
                    else -> "Layers"
                },
                color = Color.White,
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = {
                when (kind) {
                    LayerKind.TEXT -> { editingId = ""; entry = "" }
                    LayerKind.AUDIO -> onAddAudio()
                    LayerKind.VIDEO, LayerKind.IMAGE -> onAddVisual(kind)
                    else -> Unit
                }
            }) {
                Text(when (kind) {
                    LayerKind.TEXT -> "+ Add text"
                    LayerKind.AUDIO -> "+ Add audio"
                    LayerKind.VIDEO -> "+ Add video"
                    LayerKind.IMAGE -> "+ Add photo"
                    else -> "+ Add"
                }, color = ProCyan)
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
                        when (kind) {
                            LayerKind.TEXT -> layer.text.orEmpty()
                            LayerKind.AUDIO -> "Audio clip"
                            LayerKind.VIDEO -> "Video overlay"
                            LayerKind.IMAGE -> "Photo overlay"
                            else -> "Layer"
                        },
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
                    if (kind != LayerKind.AUDIO && kind != LayerKind.STICKER) {
                        val fade = layer.fade.resetToClip()
                        fun fadeChange(nextIn: Long, nextOut: Long) {
                            runCatching {
                                LayerEdits.setLayerFade(
                                    draft, layer.id, LayerFade(nextIn,nextOut)
                                )
                            }.onSuccess(onChange)
                                .onFailure { onError(it.message ?: "Fade change failed") }
                        }
                        SmallLayerButton("Fade in −") {
                            fadeChange((fade.inUs - 250_000L).coerceAtLeast(0L),fade.outUs)
                        }
                        SmallLayerButton("Fade in +") {
                            fadeChange(minOf(fade.inUs + 250_000L,
                                layer.durationUs - fade.outUs),fade.outUs)
                        }
                        SmallLayerButton("Fade out −") {
                            fadeChange(fade.inUs,
                                (fade.outUs - 250_000L).coerceAtLeast(0L))
                        }
                        SmallLayerButton("Fade out +") {
                            fadeChange(fade.inUs,
                                minOf(fade.outUs + 250_000L, layer.durationUs - fade.inUs))
                        }

                        fun motion(update: (MotionPose) -> MotionPose) {
                            runCatching {
                                LayerEdits.setMotionAt(draft, layer.id, playheadMs, mutate = update)
                            }.onSuccess(onChange).onFailure { onError(it.message ?: "Motion failed") }
                        }
                        SmallLayerButton("◆ Keyframe") { motion { it } }
                        SmallLayerButton("X −") { motion { it.copy(x = (it.x - 0.1f).coerceAtLeast(-1f)) } }
                        SmallLayerButton("X +") { motion { it.copy(x = (it.x + 0.1f).coerceAtMost(1f)) } }
                        SmallLayerButton("Y −") { motion { it.copy(y = (it.y - 0.1f).coerceAtLeast(-1f)) } }
                        SmallLayerButton("Y +") { motion { it.copy(y = (it.y + 0.1f).coerceAtMost(1f)) } }
                        SmallLayerButton("Zoom −") {
                            motion { it.copy(scale = (it.scale - 0.1f).coerceAtLeast(0.1f)) }
                        }
                        SmallLayerButton("Zoom +") {
                            motion { it.copy(scale = (it.scale + 0.1f).coerceAtMost(4f)) }
                        }
                        SmallLayerButton("↻ 15°") {
                            motion { it.copy(rotation = (it.rotation + 15f).coerceAtMost(360f)) }
                        }
                        SmallLayerButton("Opacity −") {
                            motion { it.copy(opacity = (it.opacity - 0.1f).coerceAtLeast(0f)) }
                        }
                        SmallLayerButton("Opacity +") {
                            motion { it.copy(opacity = (it.opacity + 0.1f).coerceAtMost(1f)) }
                        }
                    }
                    if (kind == LayerKind.IMAGE) {
                        val fx = layer.photoEffects
                        SmallLayerButton("Mask: ${fx.mask.name.replace('_',' ')}") {
                            val modes = PhotoMask.entries
                            val next = modes[(modes.indexOf(fx.mask) + 1) % modes.size]
                            runCatching {
                                LayerEdits.setPhotoEffects(draft,layer.id,fx.copy(mask=next))
                            }.onSuccess(onChange).onFailure { onError(it.message ?: "Mask failed") }
                        }
                        SmallLayerButton("Edge −") {
                            runCatching {
                                LayerEdits.setPhotoEffects(draft,layer.id,fx.copy(
                                    feather=(fx.feather - 0.02f).coerceAtLeast(0f)))
                            }.onSuccess(onChange).onFailure { onError(it.message ?: "Feather failed") }
                        }
                        SmallLayerButton("Edge +") {
                            runCatching {
                                LayerEdits.setPhotoEffects(draft,layer.id,fx.copy(
                                    feather=(fx.feather + 0.02f).coerceAtMost(0.4f)))
                            }.onSuccess(onChange).onFailure { onError(it.message ?: "Feather failed") }
                        }
                        SmallLayerButton(if (fx.chroma == null) "Enable green key" else "Disable key") {
                            runCatching {
                                LayerEdits.setPhotoEffects(draft,layer.id,fx.copy(
                                    chroma=if(fx.chroma==null) PhotoChroma() else null))
                            }.onSuccess(onChange).onFailure { onError(it.message ?: "Chroma failed") }
                        }
                        fx.chroma?.let { key ->
                            SmallLayerButton("Key: ${if(key.keyArgb==0xFF00FF00.toInt()) "Green" else "Blue"}") {
                                val colour = if(key.keyArgb==0xFF00FF00.toInt())
                                    0xFF0000FF.toInt() else 0xFF00FF00.toInt()
                                onChange(LayerEdits.setPhotoEffects(draft,layer.id,fx.copy(
                                    chroma=key.copy(keyArgb=colour))))
                            }
                            SmallLayerButton("Tolerance −") {
                                onChange(LayerEdits.setPhotoEffects(draft,layer.id,fx.copy(
                                    chroma=key.copy(tolerance=(key.tolerance-0.03f).coerceAtLeast(0f)))))
                            }
                            SmallLayerButton("Tolerance +") {
                                onChange(LayerEdits.setPhotoEffects(draft,layer.id,fx.copy(
                                    chroma=key.copy(tolerance=(key.tolerance+0.03f).coerceAtMost(1f)))))
                            }
                        }
                    }
                    if (kind == LayerKind.VIDEO) {
                        SmallLayerButton("Speed −") {
                            runCatching {
                                LayerEdits.setVisualSpeed(
                                    draft, layer.id,
                                    (layer.playbackSpeed - 0.25f).coerceAtLeast(0.25f)
                                )
                            }.onSuccess(onChange).onFailure {
                                onError(it.message ?: "Speed change failed")
                            }
                        }
                        Text("${layer.playbackSpeed}×", color = Color.LightGray, fontSize = 11.sp)
                        SmallLayerButton("Speed +") {
                            runCatching {
                                LayerEdits.setVisualSpeed(
                                    draft, layer.id,
                                    (layer.playbackSpeed + 0.25f).coerceAtMost(4f)
                                )
                            }.onSuccess(onChange).onFailure {
                                onError(it.message ?: "Speed change failed")
                            }
                        }
                        // Ramp points are authored at this layer's current playhead SOURCE frame.
                        // These are real persisted edit operations with preview/export mapping.
                        listOf(0.5f, 1f, 2f).forEach { requested ->
                            SmallLayerButton("Ramp ${requested}×") {
                                runCatching {
                                    LayerEdits.setSpeedKeyAt(draft, layer.id, playheadMs, requested)
                                }.onSuccess(onChange).onFailure {
                                    onError(it.message ?: "Speed ramp failed")
                                }
                            }
                        }
                        if (layer.speedRamp.isNotEmpty()) {
                            SmallLayerButton("Reset ramp") {
                                runCatching {
                                    LayerEdits.setVisualSpeed(draft, layer.id, 1f)
                                }.onSuccess(onChange).onFailure {
                                    onError(it.message ?: "Cannot reset ramp")
                                }
                            }
                            Text(
                                "${layer.speedRamp.size} speed points",
                                color = Color.LightGray, fontSize = 10.sp
                            )
                        }
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
                    SmallLayerButton(if (track.locked) "Unlock" else "Lock") {
                        onChange(LayerEdits.setTrackLocked(draft, track.id, !track.locked))
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
