package au.parmtech.tongcut

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.media.MediaMetadataRetriever
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.media3.common.C
import androidx.media3.common.audio.GainProcessor
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.SpeedParameters
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbAdjustment
import androidx.media3.effect.RgbFilter
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.File
import java.io.IOException

/** Export stays in the foreground for the alpha build; cancelling leaves no partial MediaStore file. */
@androidx.annotation.OptIn(UnstableApi::class, ExperimentalApi::class)
class VideoExporter(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var transformer: Transformer? = null
    private var poller: Runnable? = null
    private var scratch: File? = null
    private var active = false
    private var saving = false

    fun start(
        clips: List<Clip>,
        options: ExportOptions,
        onProgress: (Int) -> Unit,
        onSuccess: (Uri) -> Unit,
        onError: (String) -> Unit,
        overlayLayers: LayerTimeline = LayerTimeline()
    ) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Call export on main thread" }
        require(!active) { "Export already in progress" }
        require(clips.isNotEmpty()) { "Select at least one video" }
        require(overlayLayers.layers.none { it.kind == LayerKind.STICKER }) {
            "Sticker compositor has not been implemented"
        }
        val original = clips.toList()
        val file = File(
            context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.cacheDir,
            "TongCut-${System.currentTimeMillis()}.mp4"
        )
        scratch = file
        active = true
        try {
            val clipEffects = mutableListOf<androidx.media3.common.Effect>()
            when (options.look) {
                Look.ORIGINAL -> Unit
                Look.MONO -> clipEffects += RgbFilter.createGrayscaleFilter()
                Look.INVERT -> clipEffects += RgbFilter.createInvertedFilter()
                Look.WARM -> clipEffects += RgbAdjustment.Builder()
                    .setRedScale(1.10f).setBlueScale(0.90f).build()
                Look.COOL -> clipEffects += RgbAdjustment.Builder()
                    .setRedScale(0.90f).setBlueScale(1.10f).build()
            }
            if (options.colorGrade != ColorGrade()) {
                clipEffects += ColorGradeEffect(options.colorGrade)
            }
            // Apply common framing after concatenation, not to each clip independently.
            val compositionEffects = mutableListOf<androidx.media3.common.Effect>()
            options.ratio.ratio?.let { ratio ->
                compositionEffects += Presentation.createForAspectRatio(
                    ratio,
                    Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
                )
            }
            compositionEffects += Presentation.createForShortSide(options.shortSidePixels)
            // An overlay is sized relative to the *output frame*, not to its
            // source bitmap pixels. Use the rotated primary-video dimensions
            // when SOURCE canvas is selected.
            val hasVisiblePhoto = overlayLayers.layers.any { layer ->
                layer.kind == LayerKind.IMAGE &&
                    overlayLayers.tracks.firstOrNull { it.id == layer.trackId }?.hidden != true
            }
            val photoCanvas = if (hasVisiblePhoto) {
                val ratio = options.ratio.ratio ?: MediaMetadataRetriever().use { media ->
                    media.setDataSource(context, original.first().uri)
                    var videoWidth = media.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
                    )?.toIntOrNull() ?: throw IllegalArgumentException("Cannot read video width")
                    var videoHeight = media.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
                    )?.toIntOrNull() ?: throw IllegalArgumentException("Cannot read video height")
                    val degrees = media.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
                    )?.toIntOrNull() ?: 0
                    if (degrees == 90 || degrees == 270) {
                        val previous = videoWidth
                        videoWidth = videoHeight
                        videoHeight = previous
                    }
                    require(videoWidth > 0 && videoHeight > 0)
                    videoWidth.toFloat() / videoHeight.toFloat()
                }
                PhotoOverlaySizing.outputWidth(options.shortSidePixels, ratio) to ratio
            } else 0 to (16f / 9f)

            // Post-composition overlays draw photos first, titles last.
            val visibleOverlays = (if (hasVisiblePhoto) {
                PhotoOverlayEffects.overlays(
                    context, overlayLayers, photoCanvas.first, photoCanvas.second
                )
            } else emptyList()) + TimedTextEffects.overlays(overlayLayers)
            if (visibleOverlays.isNotEmpty()) {
                compositionEffects += OverlayEffect(visibleOverlays)
            }

            val editedClips = original.map { clip ->
                val source = MediaItem.Builder().setUri(clip.uri)
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(clip.startMs)
                            .setEndPositionMs(clip.endMs)
                            .build()
                    ).build()
                EditedMediaItem.Builder(source)
                    .setRemoveAudio(options.mute || clip.removeAudio)
                    .setEffects(Effects(emptyList(), clipEffects))
                    .build()
            }
            val sequence = if (options.mute) {
                EditedMediaItemSequence.withVideoFrom(editedClips)
            } else {
                EditedMediaItemSequence.withAudioAndVideoFrom(editedClips)
            }
            val baseVideoDurationUs = original.sumOf { it.trimmedDurationMs * 1_000L }
            val audioPlans = AudioMixPlan.from(overlayLayers, baseVideoDurationUs)
            val videoLanes = VisualComposition.exportLanes(overlayLayers, baseVideoDurationUs)
            val allSequences = mutableListOf<EditedMediaItemSequence>()
            // Media3 composites in reverse sequence order, so PiP video lanes MUST
            // be registered before the fully opaque main video, then audio-only lanes.
            allSequences.addAll(VisualComposition.sequences(videoLanes, baseVideoDurationUs, overlayLayers))
            allSequences += sequence
            audioPlans.forEach { lane ->
                val audioOnly = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO))
                lane.parts.forEach { part ->
                    when (part) {
                        is AudioPart.Gap -> audioOnly.addGap(part.durationUs)
                        is AudioPart.Source -> {
                            // MediaItem clipping is millisecond-based. We reject
                            // sub-ms boundaries to avoid exporting offset audio.
                            require(part.sourceInUs % 1_000L == 0L &&
                                part.durationUs % 1_000L == 0L &&
                                part.consumedSourceUs % 1_000L == 0L) {
                                "Audio trim/speed needs full source millisecond boundaries"
                            }
                            val beginMs = part.sourceInUs / 1_000L
                            val endMs = beginMs + part.consumedSourceUs / 1_000L
                            val media = MediaItem.Builder().setUri(part.uri)
                                .setClippingConfiguration(
                                    MediaItem.ClippingConfiguration.Builder()
                                        .setStartPositionMs(beginMs)
                                        .setEndPositionMs(endMs)
                                        .build()
                                ).build()
                            val edited = EditedMediaItem.Builder(media)
                                .setRemoveVideo(true)
                            // Media3's setSpeed(SpeedProvider) defaults to
                            // shouldMaintainPitch=false: 2x audio becomes 880 Hz
                            // from 440 Hz. Use SpeedParameters(true) for the
                            // time-stretching audio path; VIDEO PiP remains separate.
                            if (part.speedRamp.isNotEmpty()) {
                                val speedCurve = part.speedRamp
                                edited.setSpeed(SpeedParameters(
                                    object : androidx.media3.common.audio.SpeedProvider {
                                        override fun getSpeed(timeUs: Long): Float =
                                            SpeedRamp.speedAt(timeUs, speedCurve)

                                        override fun getNextSpeedChangeTimeUs(timeUs: Long): Long =
                                            SpeedRamp.nextChangeAfter(timeUs, speedCurve)
                                                ?: C.TIME_UNSET
                                    },
                                    /* shouldMaintainPitch = */ true
                                ))
                            } else if (part.playbackSpeed != 1f) {
                                val clipSpeed = part.playbackSpeed
                                edited.setSpeed(SpeedParameters(
                                    object : androidx.media3.common.audio.SpeedProvider {
                                        override fun getSpeed(timeUs: Long): Float = clipSpeed
                                        override fun getNextSpeedChangeTimeUs(timeUs: Long): Long =
                                            C.TIME_UNSET
                                    },
                                    /* shouldMaintainPitch = */ true
                                ))
                            }
                            if (lane.gain < 1f || part.envelope.isNotEmpty()) {
                                val processor = GainProcessor(object : GainProcessor.GainProvider {
                                    override fun getGainFactorAtSamplePosition(
                                        samplePosition: Long, sampleRate: Int
                                    ): Float {
                                        // Divide first so a long export cannot overflow while
                                        // converting a PCM sample index to microseconds.
                                        val seconds = samplePosition / sampleRate
                                        val remainder = samplePosition % sampleRate
                                        val positionUs = seconds * 1_000_000L +
                                            remainder * 1_000_000L / sampleRate
                                        return lane.gain * AudioEnvelope.gainAt(
                                            positionUs, part.durationUs, part.envelope
                                        )
                                    }

                                    override fun isUnityUntil(
                                        samplePosition: Long, sampleRate: Int
                                    ): Long = C.TIME_UNSET
                                })
                                edited.setEffects(Effects(listOf(processor), emptyList()))
                            }
                            audioOnly.addItem(edited.build())
                        }
                    }
                }
                allSequences += audioOnly.build()
            }
            val compositionBuilder = Composition.Builder(allSequences)
                .setEffects(Effects(emptyList(), compositionEffects))
            if (videoLanes.isNotEmpty()) {
                compositionBuilder.setVideoCompositorSettings(
                    VisualComposition.settings(videoLanes, overlayLayers, baseVideoDurationUs)
                )
            }
            val composition = compositionBuilder.build()
            transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        stopPoll()
                        transformer = null
                        saving = true
                        // MediaStore copy is I/O intensive; never block Android's UI thread.
                        Thread {
                            try {
                                val savedUri = saveToGallery(file)
                                main.post {
                                    active = false
                                    saving = false
                                    scratch = null
                                    onProgress(100)
                                    onSuccess(savedUri)
                                }
                            } catch (t: Exception) {
                                main.post {
                                    active = false
                                    saving = false
                                    scratch = null
                                    onError("Saving video failed: ${t.message ?: "unknown error"}")
                                }
                            } finally {
                                file.delete()
                            }
                        }.start()
                    }
                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException
                    ) {
                        stopPoll()
                        transformer = null
                        active = false
                        scratch = null
                        file.delete()
                        // Preserve nested Media3/GL failures for actionable device reports.
                        val causeSummary = generateSequence<Throwable>(exportException) {
                            it.cause
                        }.take(6).joinToString(" <- ") { cause ->
                            "${cause.javaClass.simpleName}: ${cause.message ?: "no details"}"
                        }
                        onError("Export failed: ${causeSummary.take(700)}")
                    }
                }).build()
            transformer!!.start(composition, file.absolutePath)
            val progressHolder = ProgressHolder()
            val task = object : Runnable {
                override fun run() {
                    val job = transformer ?: return
                    val state = job.getProgress(progressHolder)
                    if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                        onProgress(progressHolder.progress.coerceIn(0, 99))
                    }
                    main.postDelayed(this, 350)
                }
            }
            poller = task
            main.post(task)
        } catch (t: Exception) {
            stopPoll()
            transformer?.cancel()
            transformer = null
            active = false
            scratch = null
            file.delete()
            onError("Unable to start export: ${t.message ?: "unknown error"}")
        }
    }

    fun cancel(): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (saving) return false
        stopPoll()
        transformer?.cancel()
        transformer = null
        active = false
        scratch?.delete()
        scratch = null
        return true
    }

    private fun stopPoll() {
        poller?.let(main::removeCallbacks)
        poller = null
    }

    @Throws(IOException::class)
    private fun saveToGallery(file: File): Uri {
        if (!file.exists() || file.length() <= 0) throw IOException("Rendered file is empty")
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "TongCut_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/TongCut")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Unable to create MediaStore video")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().buffered().use { input -> input.copyTo(out) }
            } ?: throw IOException("Unable to open gallery output")
            resolver.update(uri, ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
            }, null, null)
            return uri
        } catch (t: Exception) {
            resolver.delete(uri, null, null)
            throw t
        }
    }
}

