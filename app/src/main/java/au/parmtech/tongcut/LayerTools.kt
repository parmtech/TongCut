package au.parmtech.tongcut

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToLong
import kotlin.math.roundToInt

/** Real model operations. Buttons either change persistent layer state or are clearly disabled. */
@Composable
internal fun LayerTools(
    draft: EditorDraft,
    playheadMs: Long,
    kind: LayerKind,
    onChange: (EditorDraft) -> Unit,
    onAddAudio: () -> Unit,
    onAddSoundEffect: (TongCutSoundEffect) -> Unit = {},
    onAddTextTemplate: (TongCutCaptionTemplate) -> Unit = {},
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
    var soundEffectMenuExpanded by remember { mutableStateOf(false) }
    var textTemplateMenuExpanded by remember { mutableStateOf(false) }
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
        if (kind == LayerKind.AUDIO) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                SmallLayerButton(if (soundEffectMenuExpanded) "Hide TongCut SFX" else "TongCut SFX") {
                    soundEffectMenuExpanded = !soundEffectMenuExpanded
                }
                if (soundEffectMenuExpanded) {
                    TongCutSoundEffect.entries.forEach { effect ->
                        SmallLayerButton(effect.title) {
                            soundEffectMenuExpanded = false
                            onAddSoundEffect(effect)
                        }
                    }
                }
            }
        }
        if (kind == LayerKind.TEXT) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    SmallLayerButton(
                        if (textTemplateMenuExpanded) "Hide text templates" else "TongCut templates"
                    ) {
                        textTemplateMenuExpanded = !textTemplateMenuExpanded
                    }
                    if (textTemplateMenuExpanded) {
                        TongCutCaptionTemplates.all.forEach { template ->
                            SmallLayerButton(template.title) {
                                textTemplateMenuExpanded = false
                                onAddTextTemplate(template)
                            }
                        }
                    }
                }
                if (textTemplateMenuExpanded) {
                    Text(
                        "Offline originals add editable title/subtitle clips to the timeline.",
                        color = Color.LightGray, fontSize = 11.sp
                    )
                }
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
                        val currentLook = CaptionLook.entries.firstOrNull { it.matches(current) }
                        SmallLayerButton("Look: ${currentLook?.title ?: "Custom"}") {
                            val nextIndex = currentLook?.let { CaptionLook.entries.indexOf(it) + 1 } ?: 0
                            val next = CaptionLook.entries[nextIndex % CaptionLook.entries.size]
                            style(next.applyTo(current))
                        }
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
                        SmallLayerButton("Align: ${current.alignment.title}") {
                            val alignments = CaptionAlignment.entries
                            val next = alignments[(alignments.indexOf(current.alignment) + 1) %
                                alignments.size]
                            style(current.copy(alignment = next))
                        }
                        SmallLayerButton(if (current.background) "Backdrop ✓" else "Backdrop") {
                            style(current.copy(background = !current.background))
                        }
                        SmallLayerButton(if (current.shadow) "Shadow ✓" else "Shadow") {
                            style(current.copy(shadow = !current.shadow))
                        }
                        SmallLayerButton("Position: ${current.placement.title}") {
                            val placements = CaptionPlacement.entries
                            val next = placements[(placements.indexOf(current.placement) + 1) %
                                placements.size]
                            style(current.copy(placement = next))
                        }
                        SmallLayerButton("Entrance: ${current.entrance.title}") {
                            val presets = CaptionEntrance.entries
                            val next = presets[(presets.indexOf(current.entrance) + 1) %
                                presets.size]
                            style(current.copy(entrance = next))
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
                            Text("Video matte/retouch uses SDR GPU preview/export; verify on your phone",
                                color = Color(0xFFFFC987), fontSize = 10.sp)
                        }
                        val fx = layer.photoEffects
                        val face = fx.faceRetouch
                        fun editFace(update: (ManualFaceRetouch) -> ManualFaceRetouch) {
                            runCatching {
                                applyMatte(fx.copy(faceRetouch = update(face)))
                            }.onSuccess(onChange)
                                .onFailure { onError(it.message ?: "Face retouch edit failed") }
                        }
                        Text("Offline face reshape · manual region · no detection",
                            color = Color.LightGray, fontSize = 11.sp)
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                            ManualFaceRetouchPreset.entries.forEach { preset ->
                                SmallLayerButton(preset.label) {
                                    editFace { preset.applyTo(it) }
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                            SmallLayerButton("Region X −") {
                                editFace { it.copy(centerX = (it.centerX - 0.02f).coerceAtLeast(
                                    it.radiusX + ManualFaceRetouch.FRAME_GUARD)) }
                            }
                            SmallLayerButton("Region X +") {
                                editFace { it.copy(centerX = (it.centerX + 0.02f).coerceAtMost(
                                    1f - it.radiusX - ManualFaceRetouch.FRAME_GUARD)) }
                            }
                            SmallLayerButton("Region Y −") {
                                editFace { it.copy(centerY = (it.centerY - 0.02f).coerceAtLeast(
                                    it.radiusY + ManualFaceRetouch.FRAME_GUARD)) }
                            }
                            SmallLayerButton("Region Y +") {
                                editFace { it.copy(centerY = (it.centerY + 0.02f).coerceAtMost(
                                    1f - it.radiusY - ManualFaceRetouch.FRAME_GUARD)) }
                            }
                        }
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                            SmallLayerButton("Region width −") {
                                editFace { current ->
                                    val radius = (current.radiusX - 0.02f).coerceAtLeast(0.08f)
                                    current.copy(radiusX = radius, centerX = current.centerX.coerceIn(
                                        radius + ManualFaceRetouch.FRAME_GUARD,
                                        1f - radius - ManualFaceRetouch.FRAME_GUARD))
                                }
                            }
                            SmallLayerButton("Region width +") {
                                editFace { current ->
                                    val radius = (current.radiusX + 0.02f).coerceAtMost(0.48f)
                                    current.copy(radiusX = radius, centerX = current.centerX.coerceIn(
                                        radius + ManualFaceRetouch.FRAME_GUARD,
                                        1f - radius - ManualFaceRetouch.FRAME_GUARD))
                                }
                            }
                            SmallLayerButton("Region height −") {
                                editFace { current ->
                                    val radius = (current.radiusY - 0.02f).coerceAtLeast(0.08f)
                                    current.copy(radiusY = radius, centerY = current.centerY.coerceIn(
                                        radius + ManualFaceRetouch.FRAME_GUARD,
                                        1f - radius - ManualFaceRetouch.FRAME_GUARD))
                                }
                            }
                            SmallLayerButton("Region height +") {
                                editFace { current ->
                                    val radius = (current.radiusY + 0.02f).coerceAtMost(0.48f)
                                    current.copy(radiusY = radius, centerY = current.centerY.coerceIn(
                                        radius + ManualFaceRetouch.FRAME_GUARD,
                                        1f - radius - ManualFaceRetouch.FRAME_GUARD))
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                            SmallLayerButton("Face −") {
                                editFace { it.copy(faceWidth = (it.faceWidth - 0.02f).coerceAtLeast(-0.25f)) }
                            }
                            SmallLayerButton("Face +") {
                                editFace { it.copy(faceWidth = (it.faceWidth + 0.02f).coerceAtMost(0.25f)) }
                            }
                            SmallLayerButton("Jaw −") {
                                editFace { it.copy(jawWidth = (it.jawWidth - 0.02f).coerceAtLeast(-0.25f)) }
                            }
                            SmallLayerButton("Jaw +") {
                                editFace { it.copy(jawWidth = (it.jawWidth + 0.02f).coerceAtMost(0.25f)) }
                            }
                            SmallLayerButton("Nose −") {
                                editFace { it.copy(noseWidth = (it.noseWidth - 0.01f).coerceAtLeast(-0.08f)) }
                            }
                            SmallLayerButton("Nose +") {
                                editFace { it.copy(noseWidth = (it.noseWidth + 0.01f).coerceAtMost(0.08f)) }
                            }
                        }
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                            SmallLayerButton("Eyes −") {
                                editFace { it.copy(eyeScale = (it.eyeScale - 0.02f).coerceAtLeast(0f)) }
                            }
                            SmallLayerButton("Eyes +") {
                                editFace { it.copy(eyeScale = (it.eyeScale + 0.02f).coerceAtMost(0.35f)) }
                            }
                            SmallLayerButton("Softening −") {
                                editFace { it.copy(skinSoftening = (it.skinSoftening - 0.1f).coerceAtLeast(0f)) }
                            }
                            SmallLayerButton("Softening +") {
                                editFace { it.copy(skinSoftening = (it.skinSoftening + 0.1f).coerceAtMost(1f)) }
                            }
                            SmallLayerButton("Reset retouch") { editFace { ManualFaceRetouch() } }
                        }
                        var selectedColorLook by remember(layer.id) {
                            mutableStateOf<ColorGradePreset?>(null)
                        }
                        var colorLookStrength by remember(layer.id) { mutableFloatStateOf(1f) }
                        var selectedPolygonVertex by remember(layer.id) { mutableIntStateOf(0) }
                        var selectedHslBand by remember(layer.id) { mutableIntStateOf(0) }
                        var selectedCurveChannel by remember(layer.id) { mutableIntStateOf(0) }
                        var selectedCurvePoint by remember(layer.id) { mutableIntStateOf(1) }
                        fun grade(next: ColorGrade, keepLookSelection: Boolean = false) {
                            if (!keepLookSelection) selectedColorLook = null
                            runCatching { applyMatte(fx.copy(colorGrade = next)) }
                                .onSuccess(onChange)
                                .onFailure { onError(it.message ?: "Color adjustment failed") }
                        }
                        ColorGradePresets.all.forEach { preset ->
                            SmallLayerButton("Look ${preset.name}") {
                                selectedColorLook = preset
                                colorLookStrength = 1f
                                grade(preset.gradeAtStrength(1f), keepLookSelection = true)
                            }
                        }
                        selectedColorLook?.let { preset ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("Look strength", color = Color.LightGray, fontSize = 10.sp)
                                Spacer(Modifier.weight(1f))
                                Text("${(colorLookStrength * 100f).roundToInt()}%",
                                    color = Color.LightGray, fontSize = 10.sp)
                                SmallLayerButton("−10%") {
                                    colorLookStrength = (colorLookStrength - 0.1f).coerceAtLeast(0f)
                                    grade(preset.gradeAtStrength(colorLookStrength),
                                        keepLookSelection = true)
                                }
                                SmallLayerButton("+10%") {
                                    colorLookStrength = (colorLookStrength + 0.1f).coerceAtMost(1f)
                                    grade(preset.gradeAtStrength(colorLookStrength),
                                        keepLookSelection = true)
                                }
                            }
                        }
                        Text("Looks replace this layer's grade; adjust strength, then refine it.",
                            color = Color.LightGray, fontSize = 10.sp)
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
                        SmallLayerButton("Tint M+") {
                            grade(fx.colorGrade.copy(tint =
                                (fx.colorGrade.tint + 0.25f).coerceAtMost(1f)))
                        }
                        SmallLayerButton("Tint G+") {
                            grade(fx.colorGrade.copy(tint =
                                (fx.colorGrade.tint - 0.25f).coerceAtLeast(-1f)))
                        }
                        SmallLayerButton("Hue −15°") {
                            grade(fx.colorGrade.copy(hueDegrees =
                                (fx.colorGrade.hueDegrees - 15f).coerceAtLeast(-180f)))
                        }
                        SmallLayerButton("Hue +15°") {
                            grade(fx.colorGrade.copy(hueDegrees =
                                (fx.colorGrade.hueDegrees + 15f).coerceAtMost(180f)))
                        }
                        SmallLayerButton("HSL Sat −") {
                            grade(fx.colorGrade.copy(hslSaturation =
                                (fx.colorGrade.hslSaturation - 0.1f).coerceAtLeast(-1f)))
                        }
                        SmallLayerButton("HSL Sat +") {
                            grade(fx.colorGrade.copy(hslSaturation =
                                (fx.colorGrade.hslSaturation + 0.1f).coerceAtMost(1f)))
                        }
                        SmallLayerButton("Light −") {
                            grade(fx.colorGrade.copy(hslLightness =
                                (fx.colorGrade.hslLightness - 0.1f).coerceAtLeast(-1f)))
                        }
                        SmallLayerButton("Light +") {
                            grade(fx.colorGrade.copy(hslLightness =
                                (fx.colorGrade.hslLightness + 0.1f).coerceAtMost(1f)))
                        }
                        SmallLayerButton("HSL ${HSL_BAND_NAMES[selectedHslBand]}") {
                            selectedHslBand = (selectedHslBand + 1) % HSL_BAND_NAMES.size
                        }
                        SmallLayerButton("Band Hue −") {
                            val bands = fx.colorGrade.hslBands.toMutableList()
                            val band = bands[selectedHslBand]
                            bands[selectedHslBand] = band.copy(
                                hueDegrees = (band.hueDegrees - 15f).coerceAtLeast(-180f))
                            grade(fx.colorGrade.copy(hslBands = bands))
                        }
                        SmallLayerButton("Band Hue +") {
                            val bands = fx.colorGrade.hslBands.toMutableList()
                            val band = bands[selectedHslBand]
                            bands[selectedHslBand] = band.copy(
                                hueDegrees = (band.hueDegrees + 15f).coerceAtMost(180f))
                            grade(fx.colorGrade.copy(hslBands = bands))
                        }
                        SmallLayerButton("Band Sat −") {
                            val bands = fx.colorGrade.hslBands.toMutableList()
                            val band = bands[selectedHslBand]
                            bands[selectedHslBand] = band.copy(
                                saturation = (band.saturation - 0.1f).coerceAtLeast(-1f))
                            grade(fx.colorGrade.copy(hslBands = bands))
                        }
                        SmallLayerButton("Band Sat +") {
                            val bands = fx.colorGrade.hslBands.toMutableList()
                            val band = bands[selectedHslBand]
                            bands[selectedHslBand] = band.copy(
                                saturation = (band.saturation + 0.1f).coerceAtMost(1f))
                            grade(fx.colorGrade.copy(hslBands = bands))
                        }
                        SmallLayerButton("Band Light −") {
                            val bands = fx.colorGrade.hslBands.toMutableList()
                            val band = bands[selectedHslBand]
                            bands[selectedHslBand] = band.copy(
                                lightness = (band.lightness - 0.1f).coerceAtLeast(-1f))
                            grade(fx.colorGrade.copy(hslBands = bands))
                        }
                        SmallLayerButton("Band Light +") {
                            val bands = fx.colorGrade.hslBands.toMutableList()
                            val band = bands[selectedHslBand]
                            bands[selectedHslBand] = band.copy(
                                lightness = (band.lightness + 0.1f).coerceAtMost(1f))
                            grade(fx.colorGrade.copy(hslBands = bands))
                        }
                        SmallLayerButton("Curve ${CURVE_CHANNEL_NAMES[selectedCurveChannel]}") {
                            selectedCurveChannel =
                                (selectedCurveChannel + 1) % CURVE_CHANNEL_NAMES.size
                        }
                        SmallLayerButton("Curve add") {
                            val curve = fx.colorGrade.curves.curve(selectedCurveChannel)
                            if (curve.points.size < 8) {
                                val after = (selectedCurvePoint - 1).coerceIn(
                                    0, curve.points.lastIndex - 1)
                                val next = curve.addMidpoint(after)
                                grade(fx.colorGrade.copy(curves =
                                    fx.colorGrade.curves.withCurve(selectedCurveChannel, next)))
                                selectedCurvePoint = after + 1
                            }
                        }
                        val activeCurve = fx.colorGrade.curves.curve(selectedCurveChannel)
                        ToneCurveGraph(
                            curve = activeCurve,
                            channel = selectedCurveChannel,
                            selectedPoint = selectedCurvePoint,
                            onSelectPoint = { selectedCurvePoint = it },
                            onMovePoint = { index, x, y ->
                                val latest = fx.colorGrade.curves.curve(selectedCurveChannel)
                                if (index in 1 until latest.points.lastIndex) {
                                    val point = latest.points[index]
                                    val next = latest.withPoint(index, point.copy(x = x, y = y))
                                    grade(fx.colorGrade.copy(curves =
                                        fx.colorGrade.curves.withCurve(selectedCurveChannel, next)))
                                }
                            }
                        )
                        if (activeCurve.points.size > 2) {
                            val pointIndex = selectedCurvePoint.coerceIn(1,
                                activeCurve.points.lastIndex - 1)
                            val point = activeCurve.points[pointIndex]
                            fun moveCurvePoint(dx: Float, dy: Float) {
                                val next = activeCurve.withPoint(pointIndex,
                                    point.copy(x = point.x + dx, y = point.y + dy))
                                grade(fx.colorGrade.copy(curves =
                                    fx.colorGrade.curves.withCurve(selectedCurveChannel, next)))
                            }
                            SmallLayerButton("Curve X−") { moveCurvePoint(-0.05f, 0f) }
                            SmallLayerButton("Curve X+") { moveCurvePoint(0.05f, 0f) }
                            SmallLayerButton("Curve Y−") { moveCurvePoint(0f, -0.05f) }
                            SmallLayerButton("Curve Y+") { moveCurvePoint(0f, 0.05f) }
                            SmallLayerButton("Curve remove") {
                                val next = activeCurve.removePoint(pointIndex)
                                grade(fx.colorGrade.copy(curves =
                                    fx.colorGrade.curves.withCurve(selectedCurveChannel, next)))
                                selectedCurvePoint = (pointIndex - 1).coerceAtLeast(1)
                            }
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
                        SmallLayerButton("Sharp −") {
                            runCatching { applyMatte(fx.copy(sharpen =
                                (fx.sharpen - 0.1f).coerceAtLeast(0f))) }
                                .onSuccess(onChange)
                                .onFailure { onError(it.message ?: "Sharpen change failed") }
                        }
                        SmallLayerButton("Sharp +") {
                            runCatching { applyMatte(fx.copy(sharpen =
                                (fx.sharpen + 0.1f).coerceAtMost(1f))) }
                                .onSuccess(onChange)
                                .onFailure { onError(it.message ?: "Sharpen change failed") }
                        }
                        SmallLayerButton("Grain −") {
                            runCatching { applyMatte(fx.copy(grain =
                                (fx.grain - 0.1f).coerceAtLeast(0f))) }
                                .onSuccess(onChange)
                                .onFailure { onError(it.message ?: "Grain change failed") }
                        }
                        SmallLayerButton("Grain +") {
                            runCatching { applyMatte(fx.copy(grain =
                                (fx.grain + 0.1f).coerceAtMost(1f))) }
                                .onSuccess(onChange)
                                .onFailure { onError(it.message ?: "Grain change failed") }
                        }
                        SmallLayerButton("Vignette −") {
                            runCatching { applyMatte(fx.copy(vignette =
                                (fx.vignette - 0.1f).coerceAtLeast(0f))) }
                                .onSuccess(onChange)
                                .onFailure { onError(it.message ?: "Vignette change failed") }
                        }
                        SmallLayerButton("Vignette +") {
                            runCatching { applyMatte(fx.copy(vignette =
                                (fx.vignette + 0.1f).coerceAtMost(1f))) }
                                .onSuccess(onChange)
                                .onFailure { onError(it.message ?: "Vignette change failed") }
                        }
                        Text("Vignette ${(fx.vignette * 100).toInt()}%",
                            color = Color.LightGray, fontSize = 10.sp)
                        if (draft.colorLutProfile == null) {
                            Text(
                                "Import a project LUT in Adjust to enable overlay grading.",
                                color = Color.LightGray, fontSize = 10.sp
                            )
                        } else {
                            Text(
                                "Project LUT: ${draft.colorLutProfile.name} · overlay render pending",
                                color = Color(0xFFFFC987), fontSize = 10.sp
                            )
                            SmallLayerButton(
                                if (fx.applyColorLut) "Overlay LUT on" else "Apply project LUT"
                            ) {
                                runCatching { applyMatte(fx.copy(applyColorLut = !fx.applyColorLut)) }
                                    .onSuccess(onChange)
                                    .onFailure { onError(it.message ?: "Overlay LUT change failed") }
                            }
                            if (fx.applyColorLut) {
                                Row(
                                    modifier = Modifier.fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Strength ${(fx.colorLutStrength * 100f).toInt()}%",
                                        color = Color.LightGray, fontSize = 10.sp)
                                    SmallLayerButton("− 10%") {
                                        runCatching {
                                            applyMatte(fx.copy(colorLutStrength =
                                                (fx.colorLutStrength - 0.1f).coerceAtLeast(0f)))
                                        }.onSuccess(onChange)
                                            .onFailure { onError(it.message ?: "LUT strength change failed") }
                                    }
                                    SmallLayerButton("+ 10%") {
                                        runCatching {
                                            applyMatte(fx.copy(colorLutStrength =
                                                (fx.colorLutStrength + 0.1f).coerceAtMost(1f)))
                                        }.onSuccess(onChange)
                                            .onFailure { onError(it.message ?: "LUT strength change failed") }
                                    }
                                }
                            }
                            Text("Saved and undoable; preview/export effect is not connected yet.",
                                color = Color.LightGray, fontSize = 10.sp)
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
                        if (fx.sharpen > 0f) {
                            SmallLayerButton("Reset sharp") {
                                runCatching { applyMatte(fx.copy(sharpen = 0f)) }
                                    .onSuccess(onChange)
                                    .onFailure { onError(it.message ?: "Sharpen reset failed") }
                            }
                        }
                        if (fx.grain > 0f) {
                            SmallLayerButton("Reset grain") {
                                runCatching { applyMatte(fx.copy(grain = 0f)) }
                                    .onSuccess(onChange)
                                    .onFailure { onError(it.message ?: "Grain reset failed") }
                            }
                        }
                        if (fx.vignette > 0f) {
                            SmallLayerButton("Reset vignette") {
                                runCatching { applyMatte(fx.copy(vignette = 0f)) }
                                    .onSuccess(onChange)
                                    .onFailure { onError(it.message ?: "Vignette reset failed") }
                            }
                        }
                        SmallLayerButton("Mask: ${fx.mask.name.replace('_',' ')}") {
                            val modes = PhotoMask.entries
                            val next = modes[(modes.indexOf(fx.mask) + 1) % modes.size]
                            runCatching {
                                applyMatte(fx.copy(mask=next))
                            }.onSuccess(onChange).onFailure { onError(it.message ?: "Mask failed") }
                        }
                        fun changeShape(next: PhotoEffects) {
                            runCatching { applyMatte(next) }.onSuccess(onChange)
                                .onFailure {
                                    onError(it.message ?: "Mask geometry change failed")
                                }
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
                        if (fx.mask == PhotoMask.POLYGON) {
                            val selectedVertex = selectedPolygonVertex.coerceIn(
                                0, fx.maskPoints.lastIndex
                            )
                            val selectedPoint = fx.maskPoints[selectedVertex]
                            Text(
                                "Freeform point ${selectedVertex + 1}/${fx.maskPoints.size} " +
                                    "(${(selectedPoint.x * 100).toInt()}%, " +
                                    "${(selectedPoint.y * 100).toInt()}%)",
                                color = Color.LightGray, fontSize = 10.sp
                            )
                            fun movePolygonPoint(dx: Float, dy: Float) {
                                val points = fx.maskPoints.toMutableList()
                                points[selectedVertex] = selectedPoint.copy(
                                    x = (selectedPoint.x + dx).coerceIn(-1f, 1f),
                                    y = (selectedPoint.y + dy).coerceIn(-1f, 1f)
                                )
                                changeShape(fx.copy(maskPoints = points))
                            }
                            SmallLayerButton("Point ◀") {
                                selectedPolygonVertex =
                                    (selectedPolygonVertex - 1 + fx.maskPoints.size) %
                                        fx.maskPoints.size
                            }
                            SmallLayerButton("Point ▶") {
                                selectedPolygonVertex =
                                    (selectedPolygonVertex + 1) % fx.maskPoints.size
                            }
                            SmallLayerButton("Point X−") { movePolygonPoint(-0.1f, 0f) }
                            SmallLayerButton("Point X+") { movePolygonPoint(0.1f, 0f) }
                            SmallLayerButton("Point Y−") { movePolygonPoint(0f, -0.1f) }
                            SmallLayerButton("Point Y+") { movePolygonPoint(0f, 0.1f) }
                            SmallLayerButton("Add vertex") {
                                if (fx.maskPoints.size < PhotoMatte.MAX_POLYGON_POINTS) {
                                    val next = (selectedVertex + 1) % fx.maskPoints.size
                                    val nextPoint = fx.maskPoints[next]
                                    val midpoint = MaskPoint(
                                        (selectedPoint.x + nextPoint.x) * 0.5f,
                                        (selectedPoint.y + nextPoint.y) * 0.5f
                                    )
                                    val points = fx.maskPoints.toMutableList().apply {
                                        add(next, midpoint)
                                    }
                                    changeShape(fx.copy(maskPoints = points))
                                    selectedPolygonVertex = next
                                }
                            }
                            SmallLayerButton("Remove vertex") {
                                if (fx.maskPoints.size > 3) {
                                    val points = fx.maskPoints.toMutableList().apply {
                                        removeAt(selectedVertex)
                                    }
                                    changeShape(fx.copy(maskPoints = points))
                                    selectedPolygonVertex = selectedPolygonVertex.coerceAtMost(
                                        points.lastIndex
                                    )
                                }
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
                            fx.mask == PhotoMask.DIAMOND ||
                            fx.mask == PhotoMask.POLYGON) {
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
                            if (layer.preservePitch) "Pitch: preserve" else "Pitch: follow speed"
                        ) {
                            runCatching {
                                AudioSpeedEdits.setPreservePitch(
                                    draft, layer.id, !layer.preservePitch
                                )
                            }.onSuccess(onChange).onFailure {
                                onError(it.message ?: "Pitch setting failed")
                            }
                        }
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
                        if (track.duckingSource) {
                            SmallLayerButton("Duck more −5%") {
                                runCatching {
                                    LayerEdits.setAudioDuckingGain(draft, track.id,
                                        (track.duckingGain - 0.05f).coerceAtLeast(0.05f))
                                }.onSuccess(onChange).onFailure {
                                    onError(it.message ?: "Dialogue duck level failed")
                                }
                            }
                            SmallLayerButton("Duck less +5%") {
                                runCatching {
                                    LayerEdits.setAudioDuckingGain(draft, track.id,
                                        (track.duckingGain + 0.05f).coerceAtMost(1f))
                                }.onSuccess(onChange).onFailure {
                                    onError(it.message ?: "Dialogue duck level failed")
                                }
                            }
                        }
                        Text(
                            if (track.duckingSource) "Other audio targets " +
                                "${(track.duckingGain * 100).toInt()}% around this lane"
                            else "Set voice/dialogue lane to lower other tracks; " +
                                "default duck level ${(track.duckingGain * 100).toInt()}%",
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
                        listOf(
                            "Bass" to track.equalizer.bassDb,
                            "Mid" to track.equalizer.midDb,
                            "Treble" to track.equalizer.trebleDb
                        ).forEachIndexed { band, (name, db) ->
                            val value = db.toInt()
                            val update: (Int) -> AudioEqualizer = { next ->
                                when (band) {
                                    0 -> track.equalizer.copy(bassDb = next.toFloat())
                                    1 -> track.equalizer.copy(midDb = next.toFloat())
                                    else -> track.equalizer.copy(trebleDb = next.toFloat())
                                }
                            }
                            SmallLayerButton("$name −") {
                                runCatching { LayerEdits.setAudioEqualizer(
                                    draft, track.id, update((value - 1).coerceAtLeast(-12)))
                                }.onSuccess(onChange).onFailure {
                                    onError(it.message ?: "$name EQ failed")
                                }
                            }
                            Text("$name ${if (value > 0) "+" else ""}${value} dB",
                                color = Color.LightGray, fontSize = 10.sp)
                            SmallLayerButton("$name +") {
                                runCatching { LayerEdits.setAudioEqualizer(
                                    draft, track.id, update((value + 1).coerceAtMost(12)))
                                }.onSuccess(onChange).onFailure {
                                    onError(it.message ?: "$name EQ failed")
                                }
                            }
                        }
                        if (!track.equalizer.isNeutral) {
                            SmallLayerButton("Reset EQ") {
                                runCatching { LayerEdits.setAudioEqualizer(
                                    draft, track.id, AudioEqualizer())
                                }.onSuccess(onChange).onFailure {
                                    onError(it.message ?: "Audio EQ reset failed")
                                }
                            }
                        }
                        SmallLayerButton("Balance ← 5%") {
                            runCatching { LayerEdits.setAudioStereoBalance(draft, track.id,
                                (track.stereoBalance - 0.05f).coerceAtLeast(-1f))
                            }.onSuccess(onChange).onFailure {
                                onError(it.message ?: "Audio balance failed")
                            }
                        }
                        Text(
                            when {
                                track.stereoBalance < 0f ->
                                    "Favor left ${(kotlin.math.abs(track.stereoBalance) * 100).toInt()}%"
                                track.stereoBalance > 0f ->
                                    "Favor right ${(track.stereoBalance * 100).toInt()}%"
                                else -> "Centered balance"
                            },
                            color = Color.LightGray, fontSize = 10.sp
                        )
                        SmallLayerButton("Balance → 5%") {
                            runCatching { LayerEdits.setAudioStereoBalance(draft, track.id,
                                (track.stereoBalance + 0.05f).coerceAtMost(1f))
                            }.onSuccess(onChange).onFailure {
                                onError(it.message ?: "Audio balance failed")
                            }
                        }
                        if (track.stereoBalance != 0f) {
                            SmallLayerButton("Center balance") {
                                runCatching { LayerEdits.setAudioStereoBalance(draft,
                                    track.id, 0f)
                                }.onSuccess(onChange).onFailure {
                                    onError(it.message ?: "Audio balance reset failed")
                                }
                            }
                        }
                        SmallLayerButton(if (track.rumbleReduction)
                            "Rumble reduction: On" else "Rumble reduction: Off") {
                            runCatching { LayerEdits.setAudioRumbleReduction(
                                draft, track.id, !track.rumbleReduction)
                            }.onSuccess(onChange).onFailure {
                                onError(it.message ?: "Rumble reduction failed")
                            }
                        }
                        Text("Fixed 80 Hz low-cut reduces DC and handling rumble",
                            color = Color.LightGray, fontSize = 10.sp)
                        SmallLayerButton(if (track.monoFoldDown)
                            "Stereo-to-mono: On" else "Stereo-to-mono: Off") {
                            runCatching { LayerEdits.setAudioMonoFoldDown(draft, track.id,
                                !track.monoFoldDown)
                            }.onSuccess(onChange).onFailure {
                                onError(it.message ?: "Audio channel layout failed")
                            }
                        }
                        Text("Averages stereo inputs; other channel layouts stay unchanged",
                            color = Color.LightGray, fontSize = 10.sp)
                        Text("Tap the curve to add a clip gain point",
                            color = Color.LightGray, fontSize = 10.sp)
                        AudioEnvelopeCurve(
                            layerId = layer.id,
                            durationUs = layer.durationUs,
                            points = layer.audioEnvelope,
                            playheadUs = (playheadMs * 1_000L - layer.startUs)
                                .coerceIn(0L, layer.durationUs),
                            onPoint = { relativeUs, gain ->
                                runCatching {
                                    LayerEdits.setAudioEnvelopeAt(
                                        draft, layer.id,
                                        (layer.startUs + relativeUs) / 1_000L, gain
                                    )
                                }.onSuccess(onChange).onFailure {
                                    onError(it.message ?: "Envelope point could not be added")
                                }
                            }
                        )
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
private fun AudioEnvelopeCurve(
    layerId: String,
    durationUs: Long,
    points: List<AudioGainKeyframe>,
    playheadUs: Long,
    onPoint: (Long, Float) -> Unit
) {
    val curveColor = ProCyan
    val latestOnPoint = rememberUpdatedState(onPoint)
    Canvas(
        Modifier.fillMaxWidth().height(76.dp)
            .pointerInput(layerId, durationUs) {
                detectTapGestures { tap ->
                    val inset = 8.dp.toPx()
                    val width = (size.width - inset * 2f).coerceAtLeast(1f)
                    val height = (size.height - inset * 2f).coerceAtLeast(1f)
                    val time = (((tap.x - inset) / width).coerceIn(0f, 1f) *
                        durationUs.toDouble()).roundToLong().coerceIn(0L, durationUs)
                    val gain = (1f - ((tap.y - inset) / height).coerceIn(0f, 1f))
                        .coerceIn(0f, 1f)
                    latestOnPoint.value(time, gain)
                }
            }
            .semantics {
                contentDescription = "Audio gain curve. Tap to set time and gain; accessibility action adds a point at the playhead."
                onClick("Add gain point at playhead") {
                    latestOnPoint.value(
                        playheadUs, AudioEnvelope.gainAt(playheadUs, durationUs, points)
                    )
                    true
                }
            }
    ) {
        val inset = 8.dp.toPx()
        val left = inset
        val right = (size.width - inset).coerceAtLeast(left + 1f)
        val top = inset
        val bottom = (size.height - inset).coerceAtLeast(top + 1f)
        val plotWidth = right - left
        val plotHeight = bottom - top
        for (step in 0..4) {
            val y = top + plotHeight * step / 4f
            drawLine(Color.DarkGray, Offset(left, y), Offset(right, y), 1.dp.toPx())
        }
        val path = Path()
        val samples = 80
        for (sample in 0..samples) {
            val relativeUs = (durationUs.toDouble() * sample / samples).toLong()
            val gain = AudioEnvelope.gainAt(relativeUs, durationUs, points)
            val x = left + plotWidth * sample / samples
            val y = bottom - plotHeight * gain
            if (sample == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, curveColor, style = Stroke(width = 2.dp.toPx()))
        points.forEach { point ->
            val x = left + plotWidth * point.timeUs.toFloat() / durationUs.toFloat()
            val y = bottom - plotHeight * point.gain
            drawCircle(curveColor, radius = 3.5.dp.toPx(), center = Offset(x, y))
        }
        val cursorX = left + plotWidth * playheadUs.toFloat() / durationUs.toFloat()
        drawLine(Color.White.copy(alpha = 0.6f), Offset(cursorX, top),
            Offset(cursorX, bottom), 1.dp.toPx())
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
