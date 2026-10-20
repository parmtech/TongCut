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
    onFreezeVideo: (String, Long) -> Unit = { _, _ -> },
    onError: (String) -> Unit,
    focusedLayerId: String? = null,
    selectedGroupIds: Set<String> = emptySet(),
    onGroupToggle: (String) -> Unit = {},
    onGroupClear: () -> Unit = {}
) {
    var editingId by remember { mutableStateOf<String?>(null) }
    var entry by remember { mutableStateOf("") }
    var pickerId by remember { mutableStateOf<String?>(null) }
    var pickerAtMs by remember { mutableLongStateOf(0L) }
    val layers = draft.layers.layers
        .filter { it.kind == kind }
        // Selected clip first: avoid scrolling through an unrelated full-page form.
        .sortedWith(compareBy<TimelineLayer> { if (it.id == focusedLayerId) 0 else 1 }
            .thenBy { it.startUs }.thenBy { it.id })
    val validGroup = selectedGroupIds.intersect(draft.layers.layers.map { it.id }.toSet())

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
        if (validGroup.isNotEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            ) {
                Text(
                    "Group: ${validGroup.size} clips",
                    color = Color.White, fontSize = 11.sp
                )
                if (validGroup.size >= 2) {
                    SmallLayerButton("Group ◀ 0.5s") {
                        runCatching {
                            AdvancedClipEdits.moveGroupBy(draft, validGroup, -500L)
                        }.onSuccess(onChange)
                            .onFailure { onError(it.message ?: "Cannot move selected clips") }
                    }
                    SmallLayerButton("Group 0.5s ▶") {
                        runCatching {
                            AdvancedClipEdits.moveGroupBy(draft, validGroup, 500L)
                        }.onSuccess(onChange)
                            .onFailure { onError(it.message ?: "Cannot move selected clips") }
                    }
                }
                SmallLayerButton("Clear selection") { onGroupClear() }
            }
        }
        layers.forEach { layer ->
            val track = draft.layers.tracks.first { it.id == layer.trackId }
            Column(
                Modifier.fillMaxWidth().background(Color(0xFF373737), RoundedCornerShape(9.dp))
                    .padding(horizontal = 9.dp, vertical = 4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (layer.id == focusedLayerId) {
                        Text("◆ ", color = ProCyan, fontSize = 12.sp)
                    }
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
                        SmallLayerButton("Edit") {
                            editingId = layer.id
                            entry = layer.text.orEmpty()
                        }
                        fun style(next: CaptionStyle) {
                            runCatching {
                                LayerEdits.changeCaptionStyle(draft, layer.id, next)
                            }.onSuccess(onChange)
                                .onFailure { onError(it.message ?: "Caption styling failed") }
                        }
                        val current = layer.captionStyle
                        SmallLayerButton("Colour: ${current.tone.title}") {
                            val tones = CaptionTone.entries
                            val next = tones[(tones.indexOf(current.tone) + 1) % tones.size]
                            style(current.copy(tone = next))
                        }
                        SmallLayerButton(if (current.bold) "Bold ✓" else "Bold") {
                            style(current.copy(bold = !current.bold))
                        }
                        SmallLayerButton("Text −") {
                            style(current.copy(size = (current.size - 0.1f).coerceAtLeast(0.5f)))
                        }
                        SmallLayerButton("Text +") {
                            style(current.copy(size = (current.size + 0.1f).coerceAtMost(3f)))
                        }
                        SmallLayerButton("Font: ${current.font.title}") {
                            val fonts = CaptionFont.entries
                            val next = fonts[(fonts.indexOf(current.font) + 1) % fonts.size]
                            style(current.copy(font = next))
                        }
                    }
                    SmallLayerButton(
                        if (layer.id in validGroup) "✓ Group" else "+ Group"
                    ) { onGroupToggle(layer.id) }
                    SmallLayerButton("Split ◆") {
                        runCatching {
                            LayerEdits.splitLayerAtFrame(
                                draft, layer.id, Math.multiplyExact(playheadMs, 1_000L)
                            )
                        }.onSuccess(onChange)
                            .onFailure { onError(it.message ?: "Split failed") }
                    }
                    SmallLayerButton("Trim ◀ here") {
                        runCatching {
                            LayerEdits.trimLayerStartAtFrame(
                                draft, layer.id, Math.multiplyExact(playheadMs, 1_000L)
                            )
                        }.onSuccess(onChange)
                            .onFailure { onError(it.message ?: "Start trim failed") }
                    }
                    SmallLayerButton("Trim ▶ here") {
                        runCatching {
                            LayerEdits.trimLayerEndAtFrame(
                                draft, layer.id, Math.multiplyExact(playheadMs, 1_000L)
                            )
                        }.onSuccess(onChange)
                            .onFailure { onError(it.message ?: "End trim failed") }
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
                    // P3: non-destructive clip clipboard and source-/timeline-clock edits.
                    SmallLayerButton("Duplicate here") {
                        runCatching {
                            AdvancedClipEdits.duplicateAt(draft, layer.id, playheadMs)
                        }.onSuccess(onChange)
                            .onFailure { onError(it.message ?: "Duplicate failed") }
                    }
                    if (kind == LayerKind.VIDEO || kind == LayerKind.AUDIO) {
                        SmallLayerButton("Slip source ◀") {
                            runCatching {
                                AdvancedClipEdits.slipSourceBy(draft, layer.id, -250L)
                            }.onSuccess(onChange)
                                .onFailure { onError(it.message ?: "Cannot slip source earlier") }
                        }
                        SmallLayerButton("Slip source ▶") {
                            runCatching {
                                AdvancedClipEdits.slipSourceBy(draft, layer.id, 250L)
                            }.onSuccess(onChange)
                                .onFailure { onError(it.message ?: "Cannot slip source later") }
                        }
                    }
                    SmallLayerButton("Ripple lane ◀") {
                        runCatching {
                            AdvancedClipEdits.rippleLaneBy(draft, layer.id, -500L)
                        }.onSuccess(onChange)
                            .onFailure { onError(it.message ?: "Cannot ripple earlier") }
                    }
                    SmallLayerButton("Ripple lane ▶") {
                        runCatching {
                            AdvancedClipEdits.rippleLaneBy(draft, layer.id, 500L)
                        }.onSuccess(onChange)
                            .onFailure { onError(it.message ?: "Cannot ripple later") }
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
                    if (kind == LayerKind.IMAGE || kind == LayerKind.VIDEO) {
                        fun applyMatte(next: PhotoEffects): EditorDraft =
                            if (kind == LayerKind.IMAGE) {
                                LayerEdits.setPhotoEffects(draft, layer.id, next)
                            } else {
                                LayerEdits.setVideoEffects(draft, layer.id, next)
                            }
                        if (kind == LayerKind.VIDEO && layer.photoEffects != PhotoEffects()) {
                            Text("Video matte uses SDR GPU preview/export; verify on your phone",
                                color = Color(0xFFFFC987), fontSize = 10.sp)
                        }
                        val fx = layer.photoEffects
                        fun grade(next: ColorGrade) {
                            runCatching { applyMatte(fx.copy(colorGrade = next)) }
                                .onSuccess(onChange)
                                .onFailure { onError(it.message ?: "Color adjustment failed") }
                        }
                        SmallLayerButton("Exp −") {
                            grade(fx.colorGrade.copy(exposure =
                                (fx.colorGrade.exposure - 0.25f).coerceAtLeast(-2f)))
                        }
                        SmallLayerButton("Exp +") {
                            grade(fx.colorGrade.copy(exposure =
                                (fx.colorGrade.exposure + 0.25f).coerceAtMost(2f)))
                        }
                        SmallLayerButton("Sat −") {
                            grade(fx.colorGrade.copy(saturation =
                                (fx.colorGrade.saturation - 0.25f).coerceAtLeast(0f)))
                        }
                        SmallLayerButton("Sat +") {
                            grade(fx.colorGrade.copy(saturation =
                                (fx.colorGrade.saturation + 0.25f).coerceAtMost(2f)))
                        }
                        SmallLayerButton("Contrast −") {
                            grade(fx.colorGrade.copy(contrast =
                                (fx.colorGrade.contrast - 0.25f).coerceAtLeast(0f)))
                        }
                        SmallLayerButton("Contrast +") {
                            grade(fx.colorGrade.copy(contrast =
                                (fx.colorGrade.contrast + 0.25f).coerceAtMost(2f)))
                        }
                        SmallLayerButton("Warm") {
                            grade(fx.colorGrade.copy(temperature =
                                (fx.colorGrade.temperature + 0.25f).coerceAtMost(1f)))
                        }
                        SmallLayerButton("Cool") {
                            grade(fx.colorGrade.copy(temperature =
                                (fx.colorGrade.temperature - 0.25f).coerceAtLeast(-1f)))
                        }
                        SmallLayerButton("Blur −") {
                            runCatching { applyMatte(fx.copy(blur =
                                (fx.blur - 0.01f).coerceAtLeast(0f))) }
                                .onSuccess(onChange)
                                .onFailure { onError(it.message ?: "Blur change failed") }
                        }
                        SmallLayerButton("Blur +") {
                            runCatching { applyMatte(fx.copy(blur =
                                (fx.blur + 0.01f).coerceAtMost(0.08f))) }
                                .onSuccess(onChange)
                                .onFailure { onError(it.message ?: "Blur change failed") }
                        }
                        if (fx.colorGrade != ColorGrade()) {
                            SmallLayerButton("Reset colour") { grade(ColorGrade()) }
                        }
                        if (fx.blur > 0f) {
                            SmallLayerButton("Reset blur") {
                                runCatching { applyMatte(fx.copy(blur = 0f)) }
                                    .onSuccess(onChange)
                                    .onFailure { onError(it.message ?: "Blur reset failed") }
                            }
                        }
                        SmallLayerButton("Mask: ${fx.mask.name.replace('_',' ')}") {
                            val modes = PhotoMask.entries
                            val next = modes[(modes.indexOf(fx.mask) + 1) % modes.size]
                            runCatching {
                                applyMatte(fx.copy(mask=next))
                            }.onSuccess(onChange).onFailure { onError(it.message ?: "Mask failed") }
                        }
                        if (fx.mask != PhotoMask.NONE) {
                            SmallLayerButton(
                                if (fx.invertMask) "Mask: inside" else "Mask: invert"
                            ) {
                                runCatching {
                                    applyMatte(fx.copy(invertMask = !fx.invertMask))
                                }.onSuccess(onChange)
                                    .onFailure { onError(it.message ?: "Mask inversion failed") }
                            }
                        }
                        if (fx.mask == PhotoMask.LINEAR_WIPE) {
                            SmallLayerButton("Wipe ←") {
                                runCatching {
                                    applyMatte(fx.copy(maskCenterX =
                                        (fx.maskCenterX - 0.1f).coerceAtLeast(-1f)))
                                }.onSuccess(onChange)
                                    .onFailure { onError(it.message ?: "Wipe change failed") }
                            }
                            SmallLayerButton("Wipe →") {
                                runCatching {
                                    applyMatte(fx.copy(maskCenterX =
                                        (fx.maskCenterX + 0.1f).coerceAtMost(1f)))
                                }.onSuccess(onChange)
                                    .onFailure { onError(it.message ?: "Wipe change failed") }
                            }
                        }
                        if (fx.mask == PhotoMask.ELLIPSE ||
                            fx.mask == PhotoMask.DIAMOND) {
                            fun changeShape(next: PhotoEffects) {
                                runCatching { applyMatte(next) }.onSuccess(onChange)
                                    .onFailure {
                                        onError(it.message ?: "Mask geometry change failed")
                                    }
                            }
                            SmallLayerButton("Mask ←") {
                                changeShape(fx.copy(maskCenterX =
                                    (fx.maskCenterX - 0.1f).coerceAtLeast(-1f)))
                            }
                            SmallLayerButton("Mask →") {
                                changeShape(fx.copy(maskCenterX =
                                    (fx.maskCenterX + 0.1f).coerceAtMost(1f)))
                            }
                            SmallLayerButton("Mask ↑") {
                                changeShape(fx.copy(maskCenterY =
                                    (fx.maskCenterY + 0.1f).coerceAtMost(1f)))
                            }
                            SmallLayerButton("Mask ↓") {
                                changeShape(fx.copy(maskCenterY =
                                    (fx.maskCenterY - 0.1f).coerceAtLeast(-1f)))
                            }
                            SmallLayerButton("Width −") {
                                changeShape(fx.copy(maskScaleX =
                                    (fx.maskScaleX - 0.1f).coerceAtLeast(0.1f)))
                            }
                            SmallLayerButton("Width +") {
                                changeShape(fx.copy(maskScaleX =
                                    (fx.maskScaleX + 0.1f).coerceAtMost(1f)))
                            }
                            SmallLayerButton("Height −") {
                                changeShape(fx.copy(maskScaleY =
                                    (fx.maskScaleY - 0.1f).coerceAtLeast(0.1f)))
                            }
                            SmallLayerButton("Height +") {
                                changeShape(fx.copy(maskScaleY =
                                    (fx.maskScaleY + 0.1f).coerceAtMost(1f)))
                            }
                            SmallLayerButton("Rotate −15°") {
                                changeShape(fx.copy(maskRotationDegrees =
                                    (fx.maskRotationDegrees - 15f).coerceAtLeast(-180f)))
                            }
                            SmallLayerButton("Rotate +15°") {
                                changeShape(fx.copy(maskRotationDegrees =
                                    (fx.maskRotationDegrees + 15f).coerceAtMost(180f)))
                            }
                            SmallLayerButton("Reset mask shape") {
                                changeShape(fx.copy(maskCenterX = 0f,
                                    maskCenterY = 0f, maskScaleX = 1f, maskScaleY = 1f,
                                    maskRotationDegrees = 0f))
                            }
                        }
                        SmallLayerButton("Edge −") {
                            runCatching {
                                applyMatte(fx.copy(
                                    feather=(fx.feather - 0.02f).coerceAtLeast(0f)))
                            }.onSuccess(onChange).onFailure { onError(it.message ?: "Feather failed") }
                        }
                        SmallLayerButton("Edge +") {
                            runCatching {
                                applyMatte(fx.copy(
                                    feather=(fx.feather + 0.02f).coerceAtMost(0.4f)))
                            }.onSuccess(onChange).onFailure { onError(it.message ?: "Feather failed") }
                        }
                        SmallLayerButton(if (fx.chroma == null) "Enable green key" else "Disable key") {
                            runCatching {
                                applyMatte(fx.copy(
                                    chroma=if(fx.chroma==null) PhotoChroma() else null))
                            }.onSuccess(onChange).onFailure { onError(it.message ?: "Chroma failed") }
                        }
                        if (kind == LayerKind.VIDEO) {
                            SmallLayerButton("Sample source key") {
                                pickerId = layer.id
                                pickerAtMs = playheadMs
                            }
                        }
                        fx.chroma?.let { key ->
                            val keyName = when (key.keyArgb) {
                                0xFF00FF00.toInt() -> "Green"
                                0xFF0000FF.toInt() -> "Blue"
                                else -> "#" + Integer.toHexString(
                                    key.keyArgb and 0x00FFFFFF
                                ).padStart(6, '0').uppercase()
                            }
                            SmallLayerButton("Key: $keyName") {
                                val colour = if(key.keyArgb==0xFF00FF00.toInt())
                                    0xFF0000FF.toInt() else 0xFF00FF00.toInt()
                                runCatching {
                                    applyMatte(fx.copy(chroma = key.copy(keyArgb = colour)))
                                }.onSuccess(onChange)
                                    .onFailure { onError(it.message ?: "Key colour change failed") }
                            }
                            SmallLayerButton("Tolerance −") {
                                runCatching {
                                    applyMatte(fx.copy(chroma = key.copy(
                                        tolerance = (key.tolerance - 0.03f).coerceAtLeast(0f)
                                    )))
                                }.onSuccess(onChange)
                                    .onFailure { onError(it.message ?: "Tolerance change failed") }
                            }
                            SmallLayerButton("Tolerance +") {
                                runCatching {
                                    applyMatte(fx.copy(chroma = key.copy(
                                        tolerance = (key.tolerance + 0.03f).coerceAtMost(1f)
                                    )))
                                }.onSuccess(onChange)
                                    .onFailure { onError(it.message ?: "Tolerance change failed") }
                            }
                            SmallLayerButton("Key edge −") {
                                runCatching {
                                    applyMatte(fx.copy(chroma = key.copy(
                                        feather = (key.feather - 0.02f).coerceAtLeast(0f)
                                    )))
                                }.onSuccess(onChange)
                                    .onFailure { onError(it.message ?: "Key edge change failed") }
                            }
                            Text("Key edge ${(key.feather * 100).toInt()}%",
                                color = Color.LightGray, fontSize = 11.sp)
                            SmallLayerButton("Key edge +") {
                                runCatching {
                                    applyMatte(fx.copy(chroma = key.copy(
                                        feather = (key.feather + 0.02f).coerceAtMost(1f)
                                    )))
                                }.onSuccess(onChange)
                                    .onFailure { onError(it.message ?: "Key edge change failed") }
                            }
                            SmallLayerButton("Despill −") {
                                runCatching {
                                    applyMatte(fx.copy(chroma = key.copy(spillSuppression =
                                        (key.spillSuppression - 0.25f).coerceAtLeast(0f))))
                                }.onSuccess(onChange)
                                    .onFailure { onError(it.message ?: "Despill change failed") }
                            }
                            Text("Despill ${(key.spillSuppression * 100).toInt()}%",
                                color=Color.LightGray, fontSize=11.sp)
                            SmallLayerButton("Despill +") {
                                runCatching {
                                    applyMatte(fx.copy(chroma = key.copy(spillSuppression =
                                        (key.spillSuppression + 0.25f).coerceAtMost(1f))))
                                }.onSuccess(onChange)
                                    .onFailure { onError(it.message ?: "Despill change failed") }
                            }
                        }
                    }
                    if (kind == LayerKind.VIDEO) {
                        SmallLayerButton("Dissolve 0.5s → next") {
                            runCatching {
                                VideoCrossfadeEdits.crossfadeWithNext(draft, layer.id, 500L)
                            }.onSuccess(onChange)
                                .onFailure { onError(it.message ?: "Cannot crossfade these clips") }
                        }
                        SmallLayerButton("Dissolve 1s → next") {
                            runCatching {
                                VideoCrossfadeEdits.crossfadeWithNext(draft, layer.id, 1_000L)
                            }.onSuccess(onChange)
                                .onFailure { onError(it.message ?: "Cannot crossfade these clips") }
                        }
                        SmallLayerButton("Freeze 1s") {
                            onFreezeVideo(layer.id,playheadMs)
                        }
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
                        SpeedRhythmPreset.entries.forEach { preset ->
                            SmallLayerButton(preset.label) {
                                runCatching {
                                    LayerEdits.applySpeedPreset(draft, layer.id, preset)
                                }.onSuccess(onChange)
                                    .onFailure { onError(it.message ?: "Speed preset failed") }
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
                        SmallLayerButton(
                            if (track.duckingSource) "Dialogue ducking: on"
                            else "Dialogue ducking: off"
                        ) {
                            runCatching {
                                LayerEdits.setAudioDuckingSource(
                                    draft, track.id, !track.duckingSource
                                )
                            }.onSuccess(onChange).onFailure {
                                onError(it.message ?: "Dialogue ducking setting failed")
                            }
                        }
                        Text(
                            if (track.duckingSource) "Other audio dips to 25% around this lane"
                            else "Set voice/dialogue lane to lower other tracks",
                            color = Color.LightGray, fontSize = 10.sp
                        )
                        SmallLayerButton("Fade in 0.5s") {
                            runCatching { LayerEdits.setAudioFades(draft, layer.id, 500L, 0L) }
                                .onSuccess(onChange)
                                .onFailure { onError(it.message ?: "Fade in failed") }
                        }
                        SmallLayerButton("Fade out 0.5s") {
                            runCatching { LayerEdits.setAudioFades(draft, layer.id, 0L, 500L) }
                                .onSuccess(onChange)
                                .onFailure { onError(it.message ?: "Fade out failed") }
                        }
                        SmallLayerButton("Fade both 0.5s") {
                            runCatching { LayerEdits.setAudioFades(draft, layer.id, 500L, 500L) }
                                .onSuccess(onChange)
                                .onFailure { onError(it.message ?: "Audio fades failed") }
                        }
                        SmallLayerButton("Audio 0.5×") {
                            runCatching {
                                AudioSpeedEdits.setConstant(draft,layer.id,0.5f)
                            }.onSuccess(onChange)
                                .onFailure { onError(it.message ?: "Audio speed failed") }
                        }
                        SmallLayerButton("Audio 1×") {
                            runCatching {
                                AudioSpeedEdits.setConstant(draft,layer.id,1f)
                            }.onSuccess(onChange)
                                .onFailure { onError(it.message ?: "Audio speed failed") }
                        }
                        SmallLayerButton("Audio 2×") {
                            runCatching {
                                AudioSpeedEdits.setConstant(draft,layer.id,2f)
                            }.onSuccess(onChange)
                                .onFailure { onError(it.message ?: "Audio speed failed") }
                        }
                        SpeedRhythmPreset.entries.forEach { preset ->
                            SmallLayerButton("Audio: ${preset.label}") {
                                runCatching {
                                    AudioSpeedEdits.applyPreset(draft,layer.id,preset)
                                }.onSuccess(onChange)
                                    .onFailure { onError(it.message ?: "Audio speed preset failed") }
                            }
                        }
                        SmallLayerButton("Speed key here") {
                            runCatching {
                                AudioSpeedEdits.addKeyAt(
                                    draft,layer.id,playheadMs,2f
                                )
                            }.onSuccess(onChange)
                                .onFailure { onError(it.message ?: "Audio speed key failed") }
                        }
                        if (layer.speedRamp.isNotEmpty()) {
                            SmallLayerButton("Reset audio curve") {
                                runCatching {
                                    AudioSpeedEdits.setConstant(draft,layer.id,1f)
                                }.onSuccess(onChange)
                                    .onFailure { onError(it.message ?: "Audio speed reset failed") }
                            }
                        }
                        val clipGain = AudioEnvelope.gainAt(
                            (playheadMs * 1_000L - layer.startUs)
                                .coerceIn(0L, layer.durationUs),
                            layer.durationUs, layer.audioEnvelope
                        )
                        SmallLayerButton("Envelope −") {
                            runCatching {
                                LayerEdits.setAudioEnvelopeAt(draft, layer.id, playheadMs,
                                    (clipGain - 0.1f).coerceAtLeast(0f))
                            }.onSuccess(onChange).onFailure { onError(it.message ?: "Envelope failed") }
                        }
                        Text("Clip ${(clipGain * 100).toInt()}%", color = Color.LightGray, fontSize = 11.sp)
                        SmallLayerButton("Envelope +") {
                            runCatching {
                                LayerEdits.setAudioEnvelopeAt(draft, layer.id, playheadMs,
                                    (clipGain + 0.1f).coerceAtMost(1f))
                            }.onSuccess(onChange).onFailure { onError(it.message ?: "Envelope failed") }
                        }
                        if (layer.audioEnvelope.isNotEmpty()) {
                            SmallLayerButton("Reset envelope") {
                                runCatching { LayerEdits.clearAudioEnvelope(draft, layer.id) }
                                    .onSuccess(onChange)
                                    .onFailure { onError(it.message ?: "Envelope reset failed") }
                            }
                            Text("${layer.audioEnvelope.size} envelope points", color = Color.LightGray, fontSize = 10.sp)
                        }
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
                if (kind == LayerKind.VIDEO && pickerId == layer.id) {
                    VideoChromaKeyPicker(
                        layer = layer,
                        frozenPlayheadMs = pickerAtMs,
                        onPicked = { colour ->
                            val current = layer.photoEffects
                            val key = (current.chroma ?: PhotoChroma()).copy(
                                keyArgb = colour
                            )
                            runCatching {
                                LayerEdits.setVideoEffects(draft, layer.id,
                                    current.copy(chroma = key))
                            }.onSuccess {
                                onChange(it)
                                pickerId = null
                            }.onFailure {
                                onError(it.message ?: "Cannot set sampled colour")
                            }
                        },
                        onCancel = { pickerId = null }
                    )
                }
                if (kind in setOf(LayerKind.VIDEO, LayerKind.AUDIO) &&
                    layer.speedRamp.isNotEmpty()) {
                    SpeedCurveEditor(draft, layer, onChange, onError)
                }
                if (kind in setOf(LayerKind.VIDEO, LayerKind.IMAGE, LayerKind.TEXT) &&
                    layer.motion.isNotEmpty()
                ) {
                    MotionKeyframeEditor(draft, layer, onChange, onError)
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
