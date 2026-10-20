package au.parmtech.tongcut

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.core.content.ContextCompat
import android.net.Uri
import android.media.MediaExtractor
import android.media.MediaFormat
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
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
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
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collect
import androidx.compose.runtime.snapshotFlow
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private data class ProTool(val name: String, val icon: ImageVector)

private fun exportAspectRatio(context: android.content.Context, draft: EditorDraft): Float? {
    draft.canvas.ratio?.let { return it.takeIf { ratio -> ratio.isFinite() && ratio > 0f } }
    val first = draft.clips.firstOrNull() ?: return 16f / 9f
    return runCatching<Float?> {
        MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(context, Uri.parse(first.sourceUri))
            var width = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
            )?.toIntOrNull() ?: return@use null
            var height = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
            )?.toIntOrNull() ?: return@use null
            val rotation = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
            )?.toIntOrNull() ?: 0
            if (rotation == 90 || rotation == 270) {
                val previousWidth = width
                width = height
                height = previousWidth
            }
            if (width > 0 && height > 0) width.toFloat() / height else null
        }
    }.getOrNull()
}

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
internal fun VideoEditorScreen(
    draft: EditorDraft,
    exportJobs: ExportJobs,
    statusMessage: String,
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
    val exportState by exportJobs.state.collectAsState()
    val processing = exportState.isActive
    val progress = exportState.progress
    val player = remember(context) { ExoPlayer.Builder(context).build() }
    val audioMonitor = remember(context) { AudioOverlayEngine(context) }
    val voiceoverCapture = remember(context) { VoiceoverCapture(context.applicationContext) }
    var voiceoverStartMs by remember { mutableLongStateOf(0L) }
    var recordingVoiceover by remember { mutableStateOf(false) }
    var voiceoverPaused by remember { mutableStateOf(false) }
    var voiceoverElapsedMs by remember { mutableLongStateOf(0L) }
    var voiceoverVoiceProcessing by remember { mutableStateOf(false) }
    DisposableEffect(voiceoverCapture) {
        onDispose { voiceoverCapture.cancel() }
    }
    LaunchedEffect(recordingVoiceover) {
        while (recordingVoiceover) {
            voiceoverElapsedMs = voiceoverCapture.elapsedDurationMs
            delay(250L)
        }
    }
    var playing by remember { mutableStateOf(false) }
    var playMs by remember { mutableLongStateOf(0L) }
    var fullscreen by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf<String?>(null) }
    var exportDialog by remember { mutableStateOf(false) }
    var exportCodec by remember(draft.exportVideoCodec) { mutableStateOf(draft.exportVideoCodec) }
    var exportBitrate by remember(draft.exportVideoBitrateBps) {
        mutableIntStateOf(draft.exportVideoBitrateBps)
    }
    var exportAudioBitrate by remember(draft.exportAudioBitrateBps) {
        mutableIntStateOf(draft.exportAudioBitrateBps)
    }
    var exportMaxFrameRate by remember(draft.exportMaxFrameRate) {
        mutableIntStateOf(draft.exportMaxFrameRate)
    }
    val supportedExportCodecs by produceState(
        initialValue = emptyList<ExportVideoCodec>(), context
    ) {
        value = withContext(Dispatchers.IO) {
            runCatching { ExportVideoCodecSupport.available() }.getOrDefault(emptyList())
        }
    }
    val exportAspect by produceState(
        initialValue = draft.canvas.ratio ?: if (draft.clips.isEmpty()) 16f / 9f else null,
        draft.canvas,
        draft.clips.firstOrNull()?.sourceUri
    ) {
        value = withContext(Dispatchers.IO) { exportAspectRatio(context, draft) }
    }
    val supportedResolutions by produceState(
        initialValue = emptyList<Int>(), exportCodec, exportAspect
    ) {
        value = withContext(Dispatchers.IO) {
            exportAspect?.let { aspect ->
                runCatching {
                    ExportVideoCodecSupport.supportedShortSides(exportCodec, aspect)
                }.getOrDefault(emptyList())
            } ?: emptyList()
        }
    }
    val exportResolution = draft.shortSidePixels.takeIf { it in supportedResolutions }
        ?: supportedResolutions.firstOrNull() ?: draft.shortSidePixels
    var muteOriginal by remember { mutableStateOf(draft.muteOriginalAudio) }
    var lastExport by remember { mutableStateOf<Uri?>(null) }
    var previewDraft by remember { mutableStateOf<EditorDraft?>(null) }
    var selectedHslBand by remember { mutableIntStateOf(0) }
    var audioWarning by remember { mutableStateOf<String?>(null) }
    // Selected independent clips can span AUDIO/TEXT/VIDEO/IMAGE panels.
    // Selection is transient UI state; moving them commits one persistent undo edit.
    var groupIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    // A single selected layer drives the familiar clip-first editing tool rail.
    // This is transient navigation state, not a second mutable edit timeline.
    var selectedLayerId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(exportState.id, exportState.phase) {
        if (exportState.title == draft.title) {
            when (exportState.phase) {
                ExportPhase.COMPLETED -> {
                    lastExport = exportState.outputUri?.let(Uri::parse)
                    exportDialog = false
                    onInfo(exportState.message?.let {
                        "Exported using a supported fallback. $it"
                    } ?: "Exported to Movies/TongCut")
                }
                ExportPhase.FAILED -> onInfo("Export failed: ${exportState.message ?: "unknown error"}")
                ExportPhase.CANCELLED -> onInfo(exportState.message ?: "Export cancelled")
                else -> Unit
            }
        }
    }
    val onGroupToggle: (String) -> Unit = { id ->
        groupIds = if (id in groupIds) groupIds - id else groupIds + id
    }
    val onGroupClear: () -> Unit = { groupIds = emptySet() }

    val scope = rememberCoroutineScope()
    val currentDraft = rememberUpdatedState(draft)
    var capturingFreeze by remember { mutableStateOf(false) }
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

    val missingOverlayIds = remember { mutableStateListOf<String>() }
    var relinkOverlayId by remember { mutableStateOf<String?>(null) }
    val overlayRelinkPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val id = relinkOverlayId
        relinkOverlayId = null
        if (uri != null && id != null) {
            scope.launch {
                try {
                    runCatching {
                        context.contentResolver.takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    }
                    val original = draft.layers.layers.firstOrNull { it.id == id }
                        ?: throw IllegalArgumentException("Original overlay no longer exists")
                    val duration = withContext(Dispatchers.IO) {
                        if (original.kind == LayerKind.IMAGE) null else {
                            MediaMetadataRetriever().use { retriever ->
                                retriever.setDataSource(context, uri)
                                retriever.extractMetadata(
                                    MediaMetadataRetriever.METADATA_KEY_DURATION
                                )?.toLongOrNull()
                            } ?: throw IllegalArgumentException("Cannot read replacement duration")
                        }
                    }
                    onChange(LayerEdits.relinkLayer(
                        draft, id, uri.toString(), duration
                    ))
                    onInfo("Overlay media reconnected without deleting existing edits.")
                } catch (failure: Exception) {
                    onInfo("Cannot relink this layer: ${failure.message}")
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

    fun startVoiceover() {
        try {
            val lengthMs = SequentialTimeline(currentDraft.value.clips).totalMs
            require(playMs + 100L < lengthMs) {
                "Place playhead at least 100ms before the end of the project"
            }
            require(!voiceoverCapture.isRecording) { "Microphone is already recording" }
            player.pause()
            playing = false
            voiceoverCapture.start(voiceProcessing = voiceoverVoiceProcessing)
            voiceoverStartMs = playMs
            voiceoverElapsedMs = 0L
            voiceoverPaused = false
            recordingVoiceover = true
            panel = "Audio"
            onInfo(
                if (voiceoverVoiceProcessing) {
                    "Recording locally with Android voice processing where available."
                } else "Recording locally. Press Stop & add to create an editable audio layer."
            )
        } catch (error: Exception) {
            recordingVoiceover = false
            onInfo("Voiceover start failed: ${error.message}")
        }
    }
    val microphonePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startVoiceover()
        else onInfo("Microphone permission is required only to record voiceover.")
    }
    fun requestVoiceover() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) startVoiceover()
        else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
    }
    fun finishVoiceover() {
        var captured: Uri? = null
        try {
            val (uri, durationMs) = voiceoverCapture.finish()
            captured = uri
            recordingVoiceover = false
            voiceoverPaused = false
            val latest = currentDraft.value
            onChange(LayerEdits.addAudio(latest, voiceoverStartMs, uri.toString(), durationMs))
            onInfo("Voiceover added as an editable audio track.")
        } catch (error: Exception) {
            captured?.let(voiceoverCapture::discard)
            recordingVoiceover = false
            voiceoverPaused = false
            onInfo("Voiceover failed: ${error.message}")
        }
    }

    // Offline captions are ordinary TEXT layers: saved, undoable, previewed
    // and sent to the same Media3 text renderer as hand-written captions.
    val subtitleImportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val raw = withContext(Dispatchers.IO) {
                        val stream = context.contentResolver.openInputStream(uri)
                            ?: throw IllegalArgumentException("Unable to open subtitle file")
                        stream.use { input ->
                            val buffer = ByteArray(8_192)
                            val bytes = ByteArrayOutputStream()
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                if (read == 0) continue
                                require(bytes.size() + read <= SrtCaptions.MAX_INPUT_BYTES) {
                                    "The subtitle file exceeds 2 MB"
                                }
                                bytes.write(buffer, 0, read)
                            }
                            bytes.toString(Charsets.UTF_8.name())
                        }
                    }
                    val latest = currentDraft.value
                    val imported = SrtCaptions.importInto(latest, raw)
                    onChange(imported)
                    panel = "Captions"
                    onInfo("Imported ${imported.layers.layers.size - latest.layers.layers.size} timed captions.")
                } catch (error: Exception) {
                    onInfo("SRT import failed: ${error.message ?: "invalid file"}")
                }
            }
        }
    }
    val subtitleExportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-subrip")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val raw = SrtCaptions.exportFrom(currentDraft.value)
                    withContext(Dispatchers.IO) {
                        val output = context.contentResolver.openOutputStream(uri, "wt")
                            ?: throw IllegalArgumentException("Cannot create subtitle document")
                        output.bufferedWriter(Charsets.UTF_8).use { it.write(raw) }
                    }
                    onInfo("SRT subtitles saved.")
                } catch (error: Exception) {
                    onInfo("SRT export failed: ${error.message ?: "unknown error"}")
                }
            }
        }
    }

    val colorLutPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) scope.launch {
            try {
                val imported = withContext(Dispatchers.IO) {
                    val name = context.contentResolver.query(
                        uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
                    )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                        ?: "Imported LUT"
                    val input = context.contentResolver.openInputStream(uri)
                        ?: throw IllegalArgumentException("Cannot open selected LUT")
                    val raw = input.use { stream ->
                        val buffer = ByteArray(8_192)
                        val bytes = ByteArrayOutputStream()
                        while (true) {
                            val count = stream.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            require(bytes.size() + count <= ColorLutProfile.MAX_TEXT_CHARS) {
                                "LUT file exceeds 2 MB"
                            }
                            bytes.write(buffer, 0, count)
                        }
                        bytes.toString(Charsets.UTF_8.name())
                    }
                    CubeLutParser.parse(name, raw)
                }
                onChange(currentDraft.value.copy(colorLutProfile = imported))
                panel = "Filters"
                onInfo("${imported.name} imported into this project. SDR LUT is applied in preview and export.")
            } catch (error: Exception) {
                onInfo("LUT import failed: ${error.message ?: "invalid .cube file"}")
            }
        }
    }

    var overlayKind by remember { mutableStateOf(LayerKind.VIDEO) }
    val overlayPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val kind = overlayKind
            scope.launch {
                try {
                    runCatching {
                        context.contentResolver.takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    }
                    val duration = withContext(Dispatchers.IO) {
                        if (kind == LayerKind.IMAGE) 5_000L
                        else MediaMetadataRetriever().use { retriever ->
                            retriever.setDataSource(context, uri)
                            retriever.extractMetadata(
                                MediaMetadataRetriever.METADATA_KEY_DURATION
                            )?.toLongOrNull()
                        } ?: throw IllegalArgumentException("Video duration not available")
                    }
                    onChange(LayerEdits.addVisual(
                        draft, playMs, kind, uri.toString(), duration
                    ))
                    panel = "Overlay"
                    onInfo("Overlay added to the timeline; export rendering needs phone validation.")
                } catch (error: Exception) {
                    onInfo("Overlay import failed: ${error.message}")
                }
            }
        }
    }
    fun chooseOverlay(kind: LayerKind) {
        overlayKind = kind
        overlayPicker.launch(arrayOf(if (kind == LayerKind.IMAGE) "image/*" else "video/*"))
    }

    fun freezeVideoOverlay(id: String, positionMs: Long) {
        if (capturingFreeze) {
            onInfo("Wait until the previous freeze frame is saved")
            return
        }
        val initial = currentDraft.value
        val sourceLayer = initial.layers.layers.firstOrNull {
            it.id == id && it.kind == LayerKind.VIDEO
        }
        if (sourceLayer == null) {
            onInfo("Select a video overlay to freeze")
            return
        }
        val sourcePositionUs = runCatching {
            // Validate timeline space, editability and existing layer collisions
            // BEFORE expensive decoding or permanent frame creation.
            LayerEdits.freezeVisualAt(
                initial,id,positionMs,1_000L,"file:///dry-run-frame.png"
            )
            sourceLayer.sourceAt(Math.multiplyExact(positionMs, 1_000L))
        }.getOrElse {
            onInfo("Cannot freeze video: ${it.message}")
            return
        }
        capturingFreeze = true
        scope.launch {
            var generated: Uri? = null
            var saved = false
            try {
                generated = FreezeFrameCapture.capture(
                    context,Uri.parse(requireNotNull(sourceLayer.sourceUri)),
                    sourcePositionUs
                )
                // An async decoder may finish after another edit. Never apply an
                // old snapshot over a newer project or silently discard user edits.
                val latest = currentDraft.value
                require(latest.layers.layers.firstOrNull { it.id == id } == sourceLayer) {
                    "The clip changed while capturing; please freeze again"
                }
                val updated = LayerEdits.freezeVisualAt(
                    latest,id,positionMs,1_000L,generated.toString()
                )
                onChange(updated)
                saved = true
                onInfo("1-second freeze inserted; the overlay resumes from the same source frame.")
            } catch (error: Exception) {
                onInfo("Freeze failed: ${error.message ?: "unknown error"}")
            } finally {
                if (!saved) generated?.let { FreezeFrameCapture.discard(context,it) }
                capturingFreeze = false
            }
        }
    }

    LaunchedEffect(draft.layers.layers) {
        val missing = withContext(Dispatchers.IO) {
            draft.layers.layers.filter { layer ->
                layer.kind in setOf(LayerKind.VIDEO,LayerKind.AUDIO,LayerKind.IMAGE)
            }.mapNotNull { layer ->
                val accessible = runCatching {
                    context.contentResolver.openFileDescriptor(
                        Uri.parse(requireNotNull(layer.sourceUri)), "r"
                    )?.use { true } ?: false
                }.getOrDefault(false)
                if (accessible) null else layer.id
            }
        }
        missingOverlayIds.clear()
        missingOverlayIds.addAll(missing)
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
    var reversePreviewSession by remember { mutableStateOf<ReversePreviewSession?>(null) }
    var reversePreviewIssue by remember { mutableStateOf<String?>(null) }
    var reverseAudioIssue by remember { mutableStateOf<String?>(null) }
    var reversePreviewFrame by remember { mutableStateOf<Bitmap?>(null) }
    var reverseAudioReady by remember { mutableStateOf(false) }
    val reverseAudioGeneration = remember { AtomicInteger(0) }
    val reverseAudioPlayer = remember(context) { ExoPlayer.Builder(context).build() }
    val reversePreviewWorker = remember {
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "TongCut-reverse-preview").apply { isDaemon = true }
        }
    }
    val reversePreviewDecodeBusy = remember { AtomicBoolean(false) }
    val latestReversePreviewFrame by rememberUpdatedState(reversePreviewFrame)
    fun commitGradePreview() {
        previewDraft?.let(onChange)
        previewDraft = null
    }
    val selectedLayer = active.layers.layers.firstOrNull { it.id == selectedLayerId }
    LaunchedEffect(active.layers.layers.map { it.id }) {
        if (selectedLayerId != null &&
            active.layers.layers.none { it.id == selectedLayerId }) selectedLayerId = null
    }
    DisposableEffect(player) { onDispose { player.release() } }
    DisposableEffect(audioMonitor) { onDispose { audioMonitor.release() } }
    DisposableEffect(reverseAudioPlayer) { onDispose { reverseAudioPlayer.release() } }
    val editTimeline = remember(active.clips) { SequentialTimeline(active.clips) }
    val reverseTarget = editTimeline.reverseTargetAt(playMs)
    val reverseClipIndex = reverseTarget?.clipIndex
    val reverseClip = reverseTarget?.clip
    val reverseClipStartMs = reverseTarget?.startMs

    LaunchedEffect(reverseClipIndex, reverseClip?.sourceUri, reverseClip?.inMs,
        reverseClip?.outMs, reverseClip?.reverseVideo) {
        val previous = reversePreviewSession
        reversePreviewSession = null
        withContext(NonCancellable + Dispatchers.IO) { previous?.close() }
        reversePreviewIssue = null
        val priorFrame = reversePreviewFrame
        reversePreviewFrame = null
        priorFrame?.recycle()
        if (reverseClip?.reverseVideo != true) return@LaunchedEffect

        val selectedClip = reverseClip
        var opened: ReversePreviewSession? = null
        try {
            opened = ReversePreviewSession.openCancellable(context, selectedClip)
            if (!currentCoroutineContext().isActive) {
                withContext(NonCancellable + Dispatchers.IO) { opened?.close() }
                return@LaunchedEffect
            }
            reversePreviewSession = opened
            awaitCancellation()
        } catch (cancelledError: CancellationException) {
            throw cancelledError
        } catch (error: Exception) {
            reversePreviewIssue = error.message ?: "Reverse preview could not be prepared"
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { opened?.close() }
            if (reversePreviewSession === opened) reversePreviewSession = null
        }
    }

    DisposableEffect(player) {
        onDispose { latestReversePreviewFrame?.recycle() }
    }
    DisposableEffect(reversePreviewWorker) {
        onDispose { reversePreviewWorker.shutdownNow() }
    }

    LaunchedEffect(
        reversePreviewSession, reverseClipIndex, reverseClip?.sourceUri,
        reverseClip?.inMs, reverseClip?.outMs, reverseClip?.reverseVideo,
        reverseClip?.editedDurationMs, reverseClip?.sourceAudioMuted,
        reverseClipStartMs, active.muteOriginalAudio
    ) {
        val generation = reverseAudioGeneration.incrementAndGet()
        reverseAudioReady = false
        reverseAudioIssue = null
        var preparedAudio: File? = null
        try {
            val session = reversePreviewSession
            val selectedClip = reverseClip
            if (session == null || selectedClip == null || !selectedClip.reverseVideo ||
                selectedClip.sourceAudioMuted || active.muteOriginalAudio) return@LaunchedEffect
            val source = Uri.parse(selectedClip.sourceUri)
            preparedAudio = ReverseAudioMaterializer.materializeCancellable(
                context = context,
                source = source,
                sourceInUs = Math.multiplyExact(selectedClip.inMs, 1_000L),
                sourceOutUs = Math.multiplyExact(selectedClip.outMs, 1_000L),
                targetDurationUs = Math.multiplyExact(selectedClip.editedDurationMs, 1_000L)
            )
            val audioFile = preparedAudio
            if (audioFile == null) {
                reverseAudioIssue = "This video has no audio track to preview in reverse."
                return@LaunchedEffect
            }
            reverseAudioPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(audioFile)))
            reverseAudioPlayer.prepare()
            if (reverseAudioGeneration.get() == generation) reverseAudioReady = true
            awaitCancellation()
        } catch (cancelledError: CancellationException) {
            throw cancelledError
        } catch (error: Exception) {
            if (reverseAudioGeneration.get() == generation) {
                reverseAudioIssue = error.message ?: "Reverse audio preview is unavailable"
                onInfo("Reverse audio preview unavailable: ${reverseAudioIssue}")
            }
        } finally {
            if (reverseAudioGeneration.get() == generation) reverseAudioReady = false
            val audioUri = preparedAudio?.let { Uri.fromFile(it) }
            val playerOwnsThisFile = audioUri != null && runCatching {
                reverseAudioPlayer.currentMediaItem?.localConfiguration?.uri == audioUri
            }.getOrDefault(false)
            if (playerOwnsThisFile) runCatching {
                reverseAudioPlayer.pause()
                reverseAudioPlayer.clearMediaItems()
            }
            withContext(NonCancellable + Dispatchers.IO) { preparedAudio?.delete() }
        }
    }

    // Read once per primary source, on an IO dispatcher; never block Compose
    // recomposition on slow gallery/content-provider metadata.
    val mainCanvasUri = active.clips.firstOrNull()?.sourceUri
    val sourceAspect by produceState(16f / 9f, mainCanvasUri) {
        value = if (mainCanvasUri == null) 16f / 9f else
            withContext(Dispatchers.IO) {
                runCatching {
                    MediaMetadataRetriever().use { media ->
                        media.setDataSource(context, Uri.parse(mainCanvasUri))
                        var width = media.extractMetadata(
                            MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
                        )?.toIntOrNull() ?: error("Unknown source width")
                        var height = media.extractMetadata(
                            MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
                        )?.toIntOrNull() ?: error("Unknown source height")
                        val orientation = media.extractMetadata(
                            MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
                        )?.toIntOrNull() ?: 0
                        if (orientation == 90 || orientation == 270) {
                            val prior = width
                            width = height
                            height = prior
                        }
                        require(width > 0 && height > 0)
                        width.toFloat() / height.toFloat()
                    }
                }.getOrDefault(16f / 9f)
            }
    }

    // A single ExoPlayer playlist plays trimmed media clips consecutively.
    // source URI and clipping window are the same values sent to Transformer.
    LaunchedEffect(active.colorGrade, active.colorLutProfile, active.primaryBlur,
        active.primarySharpen, active.primaryGrain,
        active.primaryQuarterTurns, active.primaryFlipHorizontal) {
        val effects = mutableListOf<androidx.media3.common.Effect>()
        if (active.primaryQuarterTurns != 0 || active.primaryFlipHorizontal) {
            effects += androidx.media3.effect.ScaleAndRotateTransformation.Builder()
                .setScale(if (active.primaryFlipHorizontal) -1f else 1f, 1f)
                .setRotationDegrees(active.primaryQuarterTurns * 90f)
                .build()
        }
        if (active.colorGrade != ColorGrade()) effects += ColorGradeEffect(active.colorGrade)
        active.colorLutProfile?.let { effects += it.toMedia3Effect() }
        if (active.primaryBlur > 0f || active.primarySharpen > 0f || active.primaryGrain > 0f) {
            effects += VideoMatteEffect(PhotoEffects(
                blur = active.primaryBlur, sharpen = active.primarySharpen,
                grain = active.primaryGrain
            ))
        }
        player.setVideoEffects(effects)
    }
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
            player.volume = if (active.muteOriginalAudio ||
                active.clips[selected].sourceAudioMuted ||
                active.clips[selected].reverseVideo) 0f else 1f
            if (resume) player.play()
        }
    }
    LaunchedEffect(player, editTimeline, active.layers) {
        while (true) {
            if (player.mediaItemCount > 0 && player.currentMediaItemIndex in editTimeline.clips.indices) {
                val index = player.currentMediaItemIndex
                playMs = editTimeline.timeOf(index, player.currentPosition)
                val sourceMuted = active.muteOriginalAudio ||
                    editTimeline.clips[index].sourceAudioMuted ||
                    editTimeline.clips[index].reverseVideo
                val sourceVolume = if (sourceMuted) 0f else 1f
                if (player.volume != sourceVolume) player.volume = sourceVolume
            }
            playing = player.isPlaying
            val warning = runCatching {
                audioMonitor.sync(active.layers, playMs, playing)
            }.getOrElse { "Audio preview unavailable: ${it.message ?: "decoder failed"}" }
            audioWarning = warning
            delay(90)
        }
    }

    LaunchedEffect(
        reversePreviewSession, reverseClipIndex, reverseClip?.sourceUri,
        reverseClip?.inMs, reverseClip?.outMs, reverseClip?.reverseVideo,
        reverseClip?.editedDurationMs, reverseClip?.sourceAudioMuted,
        reverseClipStartMs, active.muteOriginalAudio,
        reverseAudioReady, playing
    ) {
        val session = reversePreviewSession
        snapshotFlow { playMs }.collect { currentPlayMs ->
            val selectedStartMs = reverseClipStartMs ?: 0L
            val selectedEndMs = selectedStartMs + (reverseClip?.editedDurationMs ?: 0L)
            val showingReverse = reverseClip?.reverseVideo == true &&
                currentPlayMs >= selectedStartMs && currentPlayMs < selectedEndMs
            val reverseLocalMs = (currentPlayMs - selectedStartMs).coerceAtLeast(0L)
            if (!showingReverse || !reverseAudioReady || active.muteOriginalAudio ||
                reverseClip?.sourceAudioMuted == true || session == null) {
                reverseAudioPlayer.pause()
            } else {
                if (kotlin.math.abs(reverseAudioPlayer.currentPosition - reverseLocalMs) > 250L) {
                    reverseAudioPlayer.seekTo(reverseLocalMs)
                }
                if (playing) reverseAudioPlayer.play() else reverseAudioPlayer.pause()
            }
        }
    }

    LaunchedEffect(
        reversePreviewSession, reverseClipIndex, reverseClip?.sourceUri,
        reverseClip?.inMs, reverseClip?.outMs, reverseClip?.reverseVideo
    ) {
        val session = reversePreviewSession
        snapshotFlow { playMs }.collect {
            // A previous session may still be finishing a non-interruptible bitmap decode.
            // Wait for the one in-flight worker, then honor the latest playhead value.
            while (reversePreviewDecodeBusy.get()) delay(16)
            val currentPlayMs = playMs
            val selectedStartMs = reverseClipStartMs ?: 0L
            val selectedEndMs = selectedStartMs + (reverseClip?.editedDurationMs ?: 0L)
            val showingReverse = reverseClip?.reverseVideo == true &&
                currentPlayMs >= selectedStartMs && currentPlayMs < selectedEndMs
            if (!showingReverse || session == null) {
                if (!showingReverse) {
                    withContext(Dispatchers.IO) { session?.invalidateLastFrame() }
                    val previous = reversePreviewFrame
                    reversePreviewFrame = null
                    previous?.recycle()
                }
                return@collect
            }
            val localTimeUs = Math.multiplyExact(
                (currentPlayMs - selectedStartMs).coerceAtLeast(0L), 1_000L
            )
            val next = decodeReverseFrameCancellable(
                session, localTimeUs, reversePreviewWorker, reversePreviewDecodeBusy
            )
            if (next != null) {
                if (currentCoroutineContext().isActive) {
                    val previous = reversePreviewFrame
                    reversePreviewFrame = next
                    if (previous !== next) previous?.recycle()
                } else next.recycle()
            }
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

        if (missingOverlayIds.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().height(45.dp).padding(horizontal=12.dp),
                verticalAlignment=Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Warning, "Overlay media warning",
                    tint=Color(0xFFFFCC60))
                Spacer(Modifier.width(6.dp))
                Text("${missingOverlayIds.size} overlay source(s) need relinking",
                    color=Color(0xFFFFCC60),fontSize=12.sp,
                    modifier=Modifier.weight(1f))
                TextButton(onClick = {
                    val id = missingOverlayIds.firstOrNull()
                    val original = draft.layers.layers.firstOrNull { it.id == id }
                    if (original != null) {
                        relinkOverlayId = original.id
                        overlayRelinkPicker.launch(arrayOf(when(original.kind) {
                            LayerKind.IMAGE -> "image/*"
                            LayerKind.AUDIO -> "audio/*"
                            else -> "video/*"
                        }))
                    }
                }) { Text("Relink",color=ProCyan) }
            }
        }

        if (statusMessage.isNotBlank()) {
            Text(
                statusMessage,
                color = Color(0xFFB5C2CA),
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            )
        }

        audioWarning?.let { warning ->
            Surface(color = Color(0xFF50370B), modifier = Modifier.fillMaxWidth()) {
                Text(
                    warning,
                    color = Color(0xFFFFD88F),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                )
            }
        }

        // The preview must use the exported VIDEO canvas, not the entire phone
        // screen's letterboxed player container. Otherwise corner PiPs and text
        // appear over black space on portrait / wide / square videos.
        BoxWithConstraints(
            Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            val transformedSourceAspect = if (active.primaryQuarterTurns % 2 == 1) {
                1f / sourceAspect
            } else sourceAspect
            val stageAspect = (active.canvas.ratio ?: transformedSourceAspect)
                .coerceIn(0.1f, 10f)
            val stageWidth = minOf(maxWidth, maxHeight * stageAspect)
            val stageHeight = stageWidth / stageAspect
            Box(
                modifier = Modifier.align(Alignment.Center)
                    .size(stageWidth, stageHeight)
            ) {
            if (clip == null) {
                Text("Add a video to begin editing", color = Color.LightGray)
            } else if (reverseClip != null) {
                val bitmap = reversePreviewFrame
                if (bitmap != null) {
                    val quarterTurned = active.primaryQuarterTurns % 2 != 0
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(
                                if (quarterTurned) stageHeight else stageWidth,
                                if (quarterTurned) stageWidth else stageHeight
                            )
                            .graphicsLayer {
                                rotationZ = -active.primaryQuarterTurns * 90f
                                scaleX = if (active.primaryFlipHorizontal) -1f else 1f
                            }
                    ) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Reverse video preview",
                            contentScale = if (active.canvas == CanvasRatio.SOURCE) {
                                ContentScale.Fit
                            } else ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                } else {
                    Text(
                        reverseAudioIssue ?: reversePreviewIssue ?: "Preparing reverse preview…",
                        color = Color.LightGray
                    )
                }
            } else {
                AndroidView(
                    factory = { viewContext ->
                        PlayerView(viewContext).apply {
                            useController = false
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            setShutterBackgroundColor(android.graphics.Color.BLACK)
                        }
                    },
                    update = { view ->
                        view.player = player
                        view.resizeMode = if (active.canvas == CanvasRatio.SOURCE)
                            AspectRatioFrameLayout.RESIZE_MODE_FIT
                        else AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
            // PiP previews are independent video decoders; Media3 export uses the
            // same saved layer timing and compositor layout. Hardware parity pending.
            val overlays = VisualLayerOrder.forPreview(active.layers, playMs * 1_000L)
            overlays.forEach { layer ->
                key(layer.id) {
                    val position = when (VisualOverlaySlots.slotFor(active.layers,layer)) {
                        0 -> Alignment.BottomEnd
                        1 -> Alignment.BottomStart
                        else -> Alignment.TopEnd
                    }
                    VisualOverlayPreview(
                        layer = layer, timelineMs = playMs, playing = playing,
                        modifier = Modifier.align(position).fillMaxWidth(0.42f)
                            .aspectRatio(16f / 9f)
                    )
                }
            }

            val visibleText = active.layers.activeAt(playMs * 1_000L)
                .filter { it.kind == LayerKind.TEXT }
            if (visibleText.isNotEmpty()) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val density = LocalDensity.current
                    val viewportWidthPx = with(density) { maxWidth.toPx() }
                    val viewportHeightPx = with(density) { maxHeight.toPx() }
                    visibleText.forEach { layer ->
                        val captionText = buildAnnotatedString {
                            append(layer.text.orEmpty())
                            if (layer.captionStyle.background && length > 0) {
                                addStyle(SpanStyle(background = Color(0xB820252C)), 0, length)
                            }
                        }
                        val motion = MotionCurve.at(
                            layer.motion,
                            (playMs * 1_000L - layer.startUs).coerceIn(0L, layer.durationUs)
                        )
                        val relativeCaptionUs =
                            (playMs * 1_000L - layer.startUs).coerceIn(0L, layer.durationUs)
                        val entrance = CaptionEntranceMotion.at(
                            layer.captionStyle.entrance, relativeCaptionUs, layer.durationUs
                        )
                        val alignedMotionX = when (layer.captionStyle.alignment) {
                            CaptionAlignment.LEFT -> maxOf(
                                motion.x,
                                -1f + CAPTION_ALIGNMENT_BOX_HALF_WIDTH * motion.scale
                            )
                            CaptionAlignment.CENTER -> motion.x
                            CaptionAlignment.RIGHT -> minOf(
                                motion.x,
                                1f - CAPTION_ALIGNMENT_BOX_HALF_WIDTH * motion.scale
                            )
                        }
                        val placementOffsetY = when (layer.captionStyle.placement) {
                            CaptionPlacement.TOP -> 0.65f
                            CaptionPlacement.CENTER -> 0f
                            CaptionPlacement.BOTTOM -> -0.65f
                        }
                        val alignedMotionY =
                            (motion.y + placementOffsetY + entrance.y).coerceIn(-1f, 1f)
                        Text(
                            text = captionText,
                            color = Color(layer.captionStyle.tone.argb),
                            style = TextStyle(shadow = if (layer.captionStyle.shadow) {
                                val radius = with(LocalDensity.current) {
                                    (21f * layer.captionStyle.size).sp.toPx() * 0.11f
                                }.coerceAtLeast(1f)
                                Shadow(Color(0xD9000000), Offset(0f, radius * 0.35f), radius)
                            } else null),
                            fontWeight = if (layer.captionStyle.bold)
                                FontWeight.Bold else FontWeight.Normal,
                            fontFamily = when (layer.captionStyle.font) {
                                CaptionFont.SANS -> FontFamily.SansSerif
                                CaptionFont.SERIF -> FontFamily.Serif
                                CaptionFont.MONOSPACE -> FontFamily.Monospace
                            },
                            fontSize = (21f * layer.captionStyle.size).sp,
                            textAlign = when (layer.captionStyle.alignment) {
                                CaptionAlignment.LEFT -> androidx.compose.ui.text.style.TextAlign.Start
                                CaptionAlignment.CENTER -> androidx.compose.ui.text.style.TextAlign.Center
                                CaptionAlignment.RIGHT -> androidx.compose.ui.text.style.TextAlign.End
                            },
                            maxLines = 20,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.align(Alignment.Center)
                                .fillMaxWidth(0.88f)
                                .graphicsLayer {
                                    translationX = alignedMotionX * viewportWidthPx / 2f
                                    translationY = -alignedMotionY * viewportHeightPx / 2f
                                    scaleX = motion.scale * entrance.scale
                                    scaleY = motion.scale * entrance.scale
                                    rotationZ = motion.rotation
                                    alpha = motion.opacity * entrance.alpha * layer.fade.alphaAt(
                                        relativeCaptionUs, layer.durationUs
                                    )
                                }
                        )
                    }
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
                    selectedLayerId = null
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
                onAddVisual = ::chooseOverlay,
                selectedLayerId = selectedLayerId,
                selectedGroupIds = groupIds,
                onLayerSelect = { kind, id ->
                    selectedLayerId = id
                    panel = when (kind) {
                        LayerKind.TEXT -> "Text"
                        LayerKind.AUDIO -> "Audio"
                        else -> "Overlay"
                    }
                },
                onMoveGroup = { ids, deltaUs ->
                    runCatching { AdvancedClipEdits.moveGroupByFrames(active, ids, deltaUs) }
                        .fold(
                            onSuccess = { updated -> onChange(updated); true },
                            onFailure = { error ->
                                onInfo("Cannot move group: ${error.message}")
                                false
                            }
                        )
                },
                onMoveLayer = { id, startUs ->
                    runCatching { LayerEdits.placeLayerAtFrame(active, id, startUs) }
                        .onSuccess(onChange)
                        .onFailure { onInfo("Cannot move layer: ${it.message}") }
                },
                onTrimLayerStart = { id, requestedUs ->
                    runCatching { LayerEdits.trimLayerStartAtFrame(
                        active, id, requestedUs.coerceAtLeast(0L)
                    ) }.onSuccess(onChange)
                        .onFailure { onInfo("Cannot trim start: ${it.message}") }
                },
                onTrimLayerEnd = { id, requestedUs ->
                    runCatching { LayerEdits.trimLayerEndAtFrame(
                        active, id, requestedUs.coerceAtLeast(0L)
                    ) }.onSuccess(onChange)
                        .onFailure { onInfo("Cannot trim end: ${it.message}") }
                },
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
                            if (clip != null) ToolPill(
                                if (clip.reverseVideo) "Reverse on" else "Reverse"
                            ) {
                                onChange(active.withReplacedClip(
                                    active.selectedIndex,
                                    clip.copy(reverseVideo = !clip.reverseVideo)
                                ))
                                if (!clip.reverseVideo) {
                                    onInfo("Reverse audio is prepared for export; this editor preview is muted for the reversed clip.")
                                }
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
                        Text("Imported 3D LUT affects primary SDR footage in preview and export.",
                            color = Color.LightGray, fontSize = 11.sp)
                        Text("TongCut original looks",
                            color = Color(0xFF7CE7E8), fontSize = 12.sp)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            itemsIndexed(ColorLutPresets.all) { _, preset ->
                                FilterChip(
                                    selected = active.colorLutProfile?.name == preset.name,
                                    onClick = {
                                        previewDraft = null
                                        onChange(active.copy(colorLutProfile = preset))
                                    },
                                    label = { Text(preset.name.removePrefix("TongCut ")) }
                                )
                            }
                        }
                        ToolPill("Import 3D .cube LUT") { colorLutPicker.launch(arrayOf("*/*")) }
                        active.colorLutProfile?.let { lut ->
                            Text("${lut.name} · ${lut.size}³ SDR LUT",
                                color = Color(0xFF7CE7E8), fontSize = 12.sp)
                            Text("Strength ${(lut.strength * 100f).toInt()}%",
                                color = Color.LightGray, fontSize = 11.sp)
                            Slider(
                                value = lut.strength,
                                valueRange = 0f..1f,
                                onValueChange = { value ->
                                    previewDraft = active.copy(
                                        colorLutProfile = lut.copy(strength = value)
                                    )
                                },
                                onValueChangeFinished = ::commitGradePreview
                            )
                            ToolPill("Remove imported LUT") {
                                previewDraft = null
                                onChange(active.copy(colorLutProfile = null))
                            }
                        }
                    }
                    "Adjust" -> {
                        val grade = active.colorGrade
                        Text("SDR colour adjustments update preview and export.",
                            color = Color.LightGray, fontSize = 11.sp)
                        GradeSlider("Exposure", grade.exposure, -2f..2f, { value ->
                            previewDraft = active.copy(colorGrade = grade.copy(exposure = value))
                        }, ::commitGradePreview)
                        GradeSlider("Contrast", grade.contrast, 0f..2f, { value ->
                            previewDraft = active.copy(colorGrade = grade.copy(contrast = value))
                        }, ::commitGradePreview)
                        GradeSlider("Saturation", grade.saturation, 0f..2f, { value ->
                            previewDraft = active.copy(colorGrade = grade.copy(saturation = value))
                        }, ::commitGradePreview)
                        GradeSlider("Warmth", grade.temperature, -1f..1f, { value ->
                            previewDraft = active.copy(colorGrade = grade.copy(temperature = value))
                        }, ::commitGradePreview)
                        GradeSlider("Tint (green ↔ magenta)", grade.tint, -1f..1f, { value ->
                            previewDraft = active.copy(colorGrade = grade.copy(tint = value))
                        }, ::commitGradePreview)
                        GradeSlider("Hue", grade.hueDegrees, -180f..180f, { value ->
                            previewDraft = active.copy(colorGrade = grade.copy(hueDegrees = value))
                        }, ::commitGradePreview)
                        GradeSlider("HSL saturation", grade.hslSaturation, -1f..1f, { value ->
                            previewDraft = active.copy(colorGrade = grade.copy(hslSaturation = value))
                        }, ::commitGradePreview)
                        GradeSlider("HSL lightness", grade.hslLightness, -1f..1f, { value ->
                            previewDraft = active.copy(colorGrade = grade.copy(hslLightness = value))
                        }, ::commitGradePreview)
                        ToolChips(HSL_BAND_NAMES, selectedHslBand,
                            { selectedHslBand = it }) { it }
                        val band = grade.hslBands[selectedHslBand]
                        fun updateBand(next: HslBandAdjustment) {
                            val bands = grade.hslBands.toMutableList().apply {
                                this[selectedHslBand] = next
                            }
                            previewDraft = active.copy(colorGrade = grade.copy(hslBands = bands))
                        }
                        GradeSlider("${HSL_BAND_NAMES[selectedHslBand]} hue",
                            band.hueDegrees, -180f..180f,
                            { updateBand(band.copy(hueDegrees = it)) }, ::commitGradePreview)
                        GradeSlider("${HSL_BAND_NAMES[selectedHslBand]} saturation",
                            band.saturation, -1f..1f,
                            { updateBand(band.copy(saturation = it)) }, ::commitGradePreview)
                        GradeSlider("${HSL_BAND_NAMES[selectedHslBand]} lightness",
                            band.lightness, -1f..1f,
                            { updateBand(band.copy(lightness = it)) }, ::commitGradePreview)
                        GradeSlider("Footage blur", active.primaryBlur, 0f..0.08f, { value ->
                            previewDraft = active.copy(primaryBlur = value)
                        }, ::commitGradePreview)
                        GradeSlider("Footage sharpen", active.primarySharpen, 0f..1f, { value ->
                            previewDraft = active.copy(primarySharpen = value)
                        }, ::commitGradePreview)
                        GradeSlider("Footage grain", active.primaryGrain, 0f..1f, { value ->
                            previewDraft = active.copy(primaryGrain = value)
                        }, ::commitGradePreview)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ToolPill("Rotate left") {
                                onChange(active.copy(primaryQuarterTurns =
                                    (active.primaryQuarterTurns + 1) % 4))
                            }
                            ToolPill("Rotate right") {
                                onChange(active.copy(primaryQuarterTurns =
                                    (active.primaryQuarterTurns + 3) % 4))
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ToolPill(if (active.primaryFlipHorizontal) "Unmirror" else "Mirror") {
                                onChange(active.copy(primaryFlipHorizontal =
                                    !active.primaryFlipHorizontal))
                            }
                            ToolPill("Reset transform") {
                                onChange(active.copy(primaryQuarterTurns = 0,
                                    primaryFlipHorizontal = false))
                            }
                        }
                        ToolPill("Reset adjustments") {
                            previewDraft = null
                            onChange(active.copy(colorGrade = ColorGrade(), primaryBlur = 0f,
                                primarySharpen = 0f, primaryGrain = 0f))
                        }
                    }
                    "Canvas" -> ToolChips(CanvasRatio.entries.toList(), active.canvas,
                        { onChange(active.copy(canvas = it)) }) { it.label }
                    "Audio" -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = voiceoverVoiceProcessing,
                                enabled = !recordingVoiceover,
                                onCheckedChange = { voiceoverVoiceProcessing = it }
                            )
                            Column {
                                Text("Voice processing", color = Color.White)
                                Text(
                                    "Uses Android communication tuning when supported",
                                    color = Color.LightGray,
                                    fontSize = 10.sp
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (!recordingVoiceover) {
                                ToolPill("● Record voiceover") { requestVoiceover() }
                            } else {
                                ToolPill(if (voiceoverPaused) "Resume" else "Pause") {
                                    runCatching {
                                        if (voiceoverPaused) voiceoverCapture.resume()
                                        else voiceoverCapture.pause()
                                        voiceoverPaused = voiceoverCapture.isPaused
                                    }.onFailure { onInfo("Voiceover control failed: ${it.message}") }
                                }
                                ToolPill("■ Stop & add") { finishVoiceover() }
                                ToolPill("Discard take") {
                                    voiceoverCapture.cancel()
                                    recordingVoiceover = false
                                    voiceoverPaused = false
                                    onInfo("Voiceover discarded.")
                                }
                                Text(
                                    "${if (voiceoverPaused) "PAUSED" else "REC"}  " +
                                        "%02d:%02d".format(
                                            voiceoverElapsedMs / 60_000L,
                                            voiceoverElapsedMs / 1_000L % 60L
                                        ),
                                    color = if (voiceoverPaused) Color.LightGray
                                        else Color(0xFFFF746D)
                                )
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = active.muteOriginalAudio,
                                onCheckedChange = { onChange(active.copy(muteOriginalAudio = it)) }
                            )
                            Text("Mute original audio", color = Color.White)
                        }
                        active.selectedClip?.let { clip ->
                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (clip.sourceAudioMuted) {
                                    Text("Clip audio is on an editable track", color = Color.LightGray)
                                } else ToolPill("Detach selected clip audio") {
                                    val clipIndex = active.selectedIndex
                                    val sourceUri = clip.sourceUri
                                    scope.launch {
                                        runCatching {
                                            val hasAudio = withContext(Dispatchers.IO) {
                                                val extractor = MediaExtractor()
                                                try {
                                                    extractor.setDataSource(context, sourceUri.toUri(), null)
                                                    (0 until extractor.trackCount).any { track ->
                                                        extractor.getTrackFormat(track)
                                                            .getString(MediaFormat.KEY_MIME)
                                                            ?.startsWith("audio/") == true
                                                    }
                                                } finally {
                                                    extractor.release()
                                                }
                                            }
                                            require(hasAudio) { "This video has no audio track to detach" }
                                            val latest = currentDraft.value
                                            require(latest.clips.getOrNull(clipIndex)?.sourceUri == sourceUri) {
                                                "The selected clip changed; select it again and retry"
                                            }
                                            LayerEdits.detachSourceAudio(latest, clipIndex)
                                        }.onSuccess {
                                            onChange(it)
                                            onInfo("Clip sound moved to an editable audio lane.")
                                        }.onFailure {
                                            onInfo(it.message ?: "Could not detach this clip's audio")
                                        }
                                    }
                                }
                            }
                        }
                        LayerTools(
                            active, playMs, LayerKind.AUDIO, onChange,
                            onAddAudio = { audioPicker.launch(arrayOf("audio/*")) },
                            onError = onInfo,
                            focusedLayerId = selectedLayerId,
                            selectedGroupIds = groupIds,
                            onGroupToggle = onGroupToggle,
                            onGroupClear = onGroupClear
                        )
                    }
                    "Text" -> LayerTools(
                        active, playMs, LayerKind.TEXT, onChange,
                        onAddAudio = { audioPicker.launch(arrayOf("audio/*")) },
                        onError = onInfo,
                        focusedLayerId = selectedLayerId,
                            selectedGroupIds = groupIds,
                        onGroupToggle = onGroupToggle,
                        onGroupClear = onGroupClear
                    )
                    "Captions" -> {
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ToolPill("Import .srt") {
                                subtitleImportPicker.launch(arrayOf(
                                    "application/x-subrip", "text/plain", "text/*",
                                    "application/octet-stream"
                                ))
                            }
                            ToolPill("Export .srt") {
                                if (active.layers.layers.none { it.kind == LayerKind.TEXT }) {
                                    onInfo("Add or import a caption before exporting.")
                                } else subtitleExportPicker.launch("TongCut-captions.srt")
                            }
                        }
                        Text("SRT timestamps are relative to project start. Imported text stays editable.",
                            color = Color.LightGray, fontSize = 11.sp)
                        LayerTools(
                            active, playMs, LayerKind.TEXT, onChange,
                            onAddAudio = { audioPicker.launch(arrayOf("audio/*")) },
                            onError = onInfo, focusedLayerId = selectedLayerId,
                            selectedGroupIds = groupIds,
                            onGroupToggle = onGroupToggle, onGroupClear = onGroupClear
                        )
                    }
                    "Overlay" -> {
                        LayerTools(
                            active, playMs, LayerKind.VIDEO, onChange,
                            onAddAudio = { audioPicker.launch(arrayOf("audio/*")) },
                            onAddVisual = ::chooseOverlay,
                            onFreezeVideo = ::freezeVideoOverlay, onError = onInfo,
                            focusedLayerId = selectedLayerId,
                            selectedGroupIds = groupIds,
                            onGroupToggle = onGroupToggle,
                            onGroupClear = onGroupClear
                        )
                        LayerTools(
                            active, playMs, LayerKind.IMAGE, onChange,
                            onAddAudio = { audioPicker.launch(arrayOf("audio/*")) },
                            onAddVisual = ::chooseOverlay, onError = onInfo,
                            focusedLayerId = selectedLayerId,
                            selectedGroupIds = groupIds,
                            onGroupToggle = onGroupToggle,
                            onGroupClear = onGroupClear
                        )
                    }
                    else -> Text("$opened tools are being implemented. No nonfunctional editing buttons.",
                        color = Color.LightGray)
                }
            }
        }

        if (selectedLayer != null) {
            // True clip-first navigation: a tapped track opens context-specific
            // actions while the main tool rail remains one "Back" tap away.
            ClipContextBar(
                layer = selectedLayer,
                muted = active.layers.tracks.firstOrNull {
                    it.id == selectedLayer.trackId
                }?.muted == true,
                onAction = { action ->
                    val layerId = selectedLayer.id
                    val timelineUs = ChromaSampleMath.safeProjectTimeUs(playMs)
                    fun applyEdit(edit: (EditorDraft) -> EditorDraft) {
                        runCatching { edit(active) }
                            .onSuccess(onChange)
                            .onFailure { onInfo(it.message ?: "Unable to edit selected clip") }
                    }
                    when (action) {
                        ClipAction.BACK -> {
                            selectedLayerId = null
                            panel = null
                        }
                        ClipAction.SPLIT -> applyEdit {
                            LayerEdits.splitLayerAtFrame(it, layerId, timelineUs)
                        }
                        ClipAction.TRIM_START -> applyEdit {
                            LayerEdits.trimLayerStartAtFrame(it, layerId, timelineUs)
                        }
                        ClipAction.TRIM_END -> applyEdit {
                            LayerEdits.trimLayerEndAtFrame(it, layerId, timelineUs)
                        }
                        ClipAction.DUPLICATE -> applyEdit {
                            AdvancedClipEdits.duplicateAt(it, layerId, playMs)
                        }
                        ClipAction.KEYFRAME -> applyEdit {
                            LayerEdits.setMotionAt(it, layerId, playMs, mutate = { pose -> pose })
                        }
                        ClipAction.TOOLS -> {
                            panel = when (selectedLayer.kind) {
                                LayerKind.AUDIO -> "Audio"
                                LayerKind.TEXT -> "Text"
                                LayerKind.VIDEO, LayerKind.IMAGE -> "Overlay"
                                LayerKind.STICKER -> null
                            }
                        }
                        ClipAction.MUTE -> {
                            if (selectedLayer.kind == LayerKind.AUDIO) {
                                val track = active.layers.tracks.firstOrNull {
                                    it.id == selectedLayer.trackId
                                }
                                if (track != null) applyEdit {
                                    LayerEdits.setTrackMuted(it, track.id, !track.muted)
                                }
                            }
                        }
                        ClipAction.DELETE -> {
                            runCatching { LayerEdits.remove(active, layerId) }
                                .onSuccess {
                                    onChange(it)
                                    selectedLayerId = null
                                    panel = null
                                }.onFailure { onInfo(it.message ?: "Cannot delete this layer") }
                        }
                    }
                }
            )
        } else {
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
                                if (item.name in listOf("Effects", "AI"))
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
    }

    if (exportDialog) {
        AlertDialog(
            onDismissRequest = { if (!processing) exportDialog = false },
            title = { Text(if (processing) "Exporting" else "Export settings") },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 480.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    if (processing) {
                        LinearProgressIndicator(progress = { progress / 100f },
                            modifier = Modifier.fillMaxWidth())
                        Text("$progress% · Export continues while TongCut is in the background")
                    } else {
                        Text("Short-edge resolution (reported by selected encoder)")
                        if (supportedResolutions.isEmpty()) {
                            Text(if (exportAspect == null)
                                "Cannot verify output sizes; check source media access."
                            else "No supported output sizes reported for this codec.",
                                color = Color(0xFFFFB4AB))
                        } else {
                            ToolChips(supportedResolutions, exportResolution, { resolution ->
                                onChange(active.copy(shortSidePixels = resolution))
                            }) { "${it}p" }
                        }
                        Text("Video codec")
                        ToolChips(supportedExportCodecs, exportCodec, { codec ->
                            exportCodec = codec
                            onChange(active.copy(exportVideoCodec = codec))
                        }) { it.title }
                        Text("AV1 output is not supported by the current renderer.",
                            color = Color.LightGray, fontSize = 11.sp)
                        Text("Video bitrate")
                        ToolChips(listOf(4_000_000, 8_000_000, 12_000_000, 20_000_000),
                            exportBitrate, { bitrate ->
                                exportBitrate = bitrate
                                onChange(active.copy(exportVideoBitrateBps = bitrate))
                            }) { "${it / 1_000_000} Mbps" }
                        Text("Maximum video frame rate (lower-rate sources are not upscaled)")
                        ToolChips(ExportFrameRate.choices, exportMaxFrameRate, { fps ->
                            exportMaxFrameRate = fps
                            onChange(active.copy(exportMaxFrameRate = fps))
                        }) { "${it} FPS max" }
                        Text("AAC audio bitrate (encoder may vary)")
                        ToolChips(listOf(96_000, 128_000, 192_000, 256_000),
                            exportAudioBitrate, { bitrate ->
                                exportAudioBitrate = bitrate
                                onChange(active.copy(exportAudioBitrateBps = bitrate))
                            }) { "${it / 1_000} kbps" }
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
                    enabled = active.clips.isNotEmpty() && active.canExportWithLegacyRenderer &&
                        exportCodec in supportedExportCodecs &&
                        exportResolution in supportedResolutions,
                    onClick = {
                        val inputs = runCatching {
                            active.clips.map {
                                Clip(Uri.parse(it.sourceUri), it.label, it.durationMs,
                                    it.inMs, it.outMs, it.sourceAudioMuted)
                            }
                        }
                        if (inputs.isFailure) {
                            onInfo("Cannot export: ${inputs.exceptionOrNull()?.message}")
                            return@Button
                        }
                        val snapshot = active.copy(
                            shortSidePixels = exportResolution,
                            muteOriginalAudio = muteOriginal,
                            exportVideoCodec = exportCodec,
                            exportVideoBitrateBps = exportBitrate,
                            exportAudioBitrateBps = exportAudioBitrate,
                            exportMaxFrameRate = exportMaxFrameRate
                        ).normalized()
                        runCatching { exportJobs.enqueue(snapshot) }
                            .onFailure { onInfo("Could not start export: ${it.message}") }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ProCyan)
                ) { Text("Export MP4", color = Color.Black) }
            },
            dismissButton = {
                TextButton(onClick = {
                    if (processing) {
                        exportJobs.cancel()
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
private fun GradeSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit
) {
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Color.White, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            Text("%.2f".format(value), color = Color.LightGray, fontSize = 11.sp)
        }
        Slider(
            value = value,
            valueRange = range,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished
        )
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

/** Cancellable bitmap handoff: a bitmap completed after effect cancellation is recycled on IO. */
private suspend fun decodeReverseFrameCancellable(
    session: ReversePreviewSession,
    outputTimeUs: Long,
    worker: ExecutorService,
    decodeBusy: AtomicBoolean
): Bitmap? {
    if (!decodeBusy.compareAndSet(false, true)) return null
    val pending = AtomicReference<Bitmap?>(null)
    val bitmap = try {
        suspendCancellableCoroutine<Bitmap?> { continuation ->
            val cancelled = AtomicBoolean(false)
            continuation.invokeOnCancellation {
                cancelled.set(true)
                pending.getAndSet(null)?.recycle()
            }
            try {
                worker.execute {
                    try {
                        if (cancelled.get()) return@execute
                        val decoded = session.decodeFrameAt(outputTimeUs)
                        pending.set(decoded)
                        if (cancelled.get() || !continuation.isActive) {
                            pending.getAndSet(null)?.recycle()
                        } else {
                            continuation.resume(decoded)
                        }
                    } catch (failure: Throwable) {
                        if (continuation.isActive) continuation.resumeWithException(failure)
                    } finally {
                        decodeBusy.set(false)
                    }
                }
            } catch (failure: Throwable) {
                decodeBusy.set(false)
                if (continuation.isActive) continuation.resumeWithException(failure)
            }
        }
    } catch (failure: Throwable) {
        decodeBusy.set(false)
        throw failure
    }
    pending.compareAndSet(bitmap, null)
    return bitmap
}
