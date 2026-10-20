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
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Export stays in the foreground for the alpha build; cancelling leaves no partial MediaStore file. */
@androidx.annotation.OptIn(UnstableApi::class, ExperimentalApi::class)
class VideoExporter(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var transformer: Transformer? = null
    private var poller: Runnable? = null
    private var scratch: File? = null
    private var active = false
    private var saving = false
    @Volatile private var runGeneration = 0L
    private var activeReverseCleanup: (() -> Unit)? = null

    fun start(
        clips: List<Clip>,
        options: ExportOptions,
        onProgress: (Int) -> Unit,
        onSuccess: (Uri) -> Unit,
        onError: (String) -> Unit,
        overlayLayers: LayerTimeline = LayerTimeline(),
        onWarning: (String) -> Unit = {},
        exportJobId: String? = null,
        reverseSequences: Map<Int, ReverseImageSequence> = emptyMap(),
        reverseAudioFiles: Map<Int, File> = emptyMap()
    ) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Call export on main thread" }
        require(!active) { "Export already in progress" }
        require(clips.isNotEmpty()) { "Select at least one video" }
        require(reverseAudioFiles.keys.all { index ->
            index in clips.indices && index in reverseSequences.keys &&
                clips[index].reverseVideo && reverseAudioFiles[index]?.isFile == true
        }) { "Reverse audio cache does not match the selected reverse clips" }
        require(exportJobId == null || Regex("[a-fA-F0-9-]{36}").matches(exportJobId)) {
            "Invalid export job identifier"
        }
        require(overlayLayers.layers.none { it.kind == LayerKind.STICKER }) {
            "Sticker compositor has not been implemented"
        }
        val original = clips.toList()
        val generation = Math.addExact(runGeneration, 1L).also { runGeneration = it }
        val runReverseSequences = reverseSequences.values.distinct()
        val runReverseAudioFiles = reverseAudioFiles.values.distinct()
        val reverseSequencesClosed = AtomicBoolean(false)
        fun releaseRunReverseSequences() {
            if (reverseSequencesClosed.compareAndSet(false, true)) {
                runReverseSequences.forEach { runCatching { it.close() } }
                runReverseAudioFiles.forEach { runCatching { it.delete() } }
            }
        }
        activeReverseCleanup = { releaseRunReverseSequences() }
        val file = File(
            context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.cacheDir,
            "TongCut-${exportJobId ?: System.currentTimeMillis()}-$generation.mp4"
        )
        scratch = file
        active = true
        try {
            val clipEffects = primaryClipVideoEffects(options)
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
                    .setFrameRate(options.maxFrameRate)
                    .setRemoveAudio(options.mute || clip.removeAudio || clip.reverseVideo)
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
            var reverseVideoSequence: EditedMediaItemSequence? = null
            if (reverseSequences.isNotEmpty()) {
                require(reverseSequences.keys.all { it in original.indices }) {
                    "Reverse sequence points to a missing primary clip"
                }
                reverseSequences.forEach { (index, reverse) ->
                    val expectedUs = Math.multiplyExact(original[index].trimmedDurationMs, 1_000L)
                    val sequenceUs = reverse.items.fold(0L) { total, item ->
                        Math.addExact(total, item.durationUs)
                    }
                    require(sequenceUs == expectedUs) {
                        "Reverse frame sequence duration does not match primary clip $index"
                    }
                }
                val reverseVideo = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO))
                original.forEachIndexed { index, clip ->
                    val reverse = reverseSequences[index]
                    if (reverse == null) {
                        reverseVideo.addGap(Math.multiplyExact(clip.trimmedDurationMs, 1_000L))
                    } else {
                        reverse.items.forEach(reverseVideo::addItem)
                    }
                }
                // Opaque reverse frames replace the matching forward video interval while the
                // main sequence below retains ordinary clip audio and project timing.
                reverseVideoSequence = reverseVideo.build()
                onWarning(when {
                    options.mute -> "Reverse video is enabled; export audio is muted."
                    reverseAudioFiles.isEmpty() ->
                        "Reverse video is enabled; no reverse audio was prepared, so those clips are silent."
                    else -> "Reverse video and sample-reversed audio are enabled for ${reverseAudioFiles.size} clip(s)."
                })
            }
            // Media3 composites in reverse sequence order, so PiP video lanes MUST
            // be registered before the fully opaque main video, then audio-only lanes.
            allSequences.addAll(VisualComposition.sequences(
                videoLanes, baseVideoDurationUs, overlayLayers, options.maxFrameRate
            ))
            reverseVideoSequence?.let(allSequences::add)
            allSequences += sequence
            if (!options.mute && reverseAudioFiles.isNotEmpty()) {
                val reverseAudioSequence = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO))
                var cursorUs = 0L
                original.forEachIndexed { index, clip ->
                    val audioFile = reverseAudioFiles[index]
                    if (audioFile != null) {
                        val targetStartUs = original.take(index).sumOf {
                            Math.multiplyExact(it.trimmedDurationMs, 1_000L)
                        }
                        if (targetStartUs > cursorUs) {
                            reverseAudioSequence.addGap(targetStartUs - cursorUs)
                        }
                        val audioItem = MediaItem.Builder().setUri(Uri.fromFile(audioFile))
                            .setClippingConfiguration(
                                MediaItem.ClippingConfiguration.Builder()
                                    .setStartPositionMs(0L)
                                    .setEndPositionMs(clip.trimmedDurationMs)
                                    .build()
                            ).build()
                        reverseAudioSequence.addItem(
                            EditedMediaItem.Builder(audioItem).setRemoveVideo(true).build()
                        )
                        cursorUs = Math.addExact(targetStartUs,
                            Math.multiplyExact(clip.trimmedDurationMs, 1_000L))
                    }
                }
                val remainderUs = baseVideoDurationUs - cursorUs
                if (remainderUs > 0L) reverseAudioSequence.addGap(remainderUs)
                allSequences += reverseAudioSequence.build()
            }
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
                            // from 440 Hz. Persist a per-clip pitch policy; VIDEO PiP
                            // remains separate from the audio time-stretch path.
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
                                    /* shouldMaintainPitch = */ part.preservePitch
                                ))
                            } else if (part.playbackSpeed != 1f) {
                                val clipSpeed = part.playbackSpeed
                                edited.setSpeed(SpeedParameters(
                                    object : androidx.media3.common.audio.SpeedProvider {
                                        override fun getSpeed(timeUs: Long): Float = clipSpeed
                                        override fun getNextSpeedChangeTimeUs(timeUs: Long): Long =
                                            C.TIME_UNSET
                                    },
                                    /* shouldMaintainPitch = */ part.preservePitch
                                ))
                            }
                            val audioProcessors = mutableListOf<androidx.media3.common.audio.AudioProcessor>()
                            if (!lane.equalizer.isNeutral || lane.stereoBalance != 0f ||
                                lane.monoFoldDown || lane.rumbleReduction) {
                                audioProcessors += AudioEqualizerProcessor(
                                    lane.equalizer, lane.stereoBalance, lane.monoFoldDown,
                                    lane.rumbleReduction)
                            }
                            if (lane.gain < 1f || part.envelope.isNotEmpty()) {
                                audioProcessors += GainProcessor(object : GainProcessor.GainProvider {
                                    override fun getGainFactorAtSamplePosition(
                                        samplePosition: Long, sampleRate: Int
                                    ): Float {
                                        val positionUs = AudioEnvelope.samplePositionUs(
                                            samplePosition, sampleRate
                                        )
                                        return lane.gain * AudioEnvelope.gainAt(
                                            positionUs, part.durationUs, part.envelope
                                        )
                                    }

                                    override fun isUnityUntil(
                                        samplePosition: Long, sampleRate: Int
                                    ): Long {
                                        val unityEnd = AudioEnvelope.unityUntilSamplePosition(
                                            samplePosition, sampleRate, part.durationUs,
                                            part.envelope, lane.gain
                                        ) ?: return C.TIME_UNSET
                                        return if (unityEnd == AudioEnvelope.UNITY_TO_END_OF_STREAM) {
                                            C.TIME_END_OF_SOURCE
                                        } else unityEnd
                                    }
                                })
                            }
                            if (audioProcessors.isNotEmpty()) {
                                edited.setEffects(Effects(audioProcessors, emptyList()))
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
                .setVideoMimeType(options.videoCodec.mimeType)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setEncoderFactory(DefaultEncoderFactory.Builder(context)
                    .setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder()
                        .setBitrate(options.audioBitrateBps)
                        .build())
                    .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder()
                        .setBitrate(options.videoBitrateBps)
                        .build())
                    .build())
                .addListener(object : Transformer.Listener {
                    override fun onFallbackApplied(
                        composition: Composition,
                        originalTransformationRequest: androidx.media3.transformer.TransformationRequest,
                        fallbackTransformationRequest: androidx.media3.transformer.TransformationRequest
                    ) {
                        main.post {
                            if (generation == runGeneration) {
                                onWarning("The device could not use the requested codec or size; the renderer selected a supported fallback.")
                            }
                        }
                    }

                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (generation != runGeneration) {
                            releaseRunReverseSequences()
                            file.delete()
                            return
                        }
                        runGeneration = Math.addExact(runGeneration, 1L)
                        stopPoll()
                        transformer = null
                        releaseRunReverseSequences()
                        activeReverseCleanup = null
                        saving = true
                        // MediaStore copy is I/O intensive; never block Android's UI thread.
                        Thread {
                            try {
                                val savedUri = saveToGallery(file, exportJobId)
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
                        if (generation != runGeneration) {
                            releaseRunReverseSequences()
                            file.delete()
                            return
                        }
                        runGeneration = Math.addExact(runGeneration, 1L)
                        stopPoll()
                        transformer = null
                        releaseRunReverseSequences()
                        activeReverseCleanup = null
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
            if (generation != runGeneration) {
                releaseRunReverseSequences()
                file.delete()
                return
            }
            runGeneration = Math.addExact(runGeneration, 1L)
            stopPoll()
            transformer?.cancel()
            transformer = null
            releaseRunReverseSequences()
            activeReverseCleanup = null
            active = false
            scratch = null
            file.delete()
            onError("Unable to start export: ${t.message ?: "unknown error"}")
        }
    }

    fun cancel(): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (saving) return false
        runGeneration = Math.addExact(runGeneration, 1L)
        stopPoll()
        transformer?.cancel()
        transformer = null
        activeReverseCleanup?.invoke()
        activeReverseCleanup = null
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
    private fun saveToGallery(file: File, exportJobId: String?): Uri {
        if (!file.exists() || file.length() <= 0) throw IOException("Rendered file is empty")
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME,
                "TongCut_${exportJobId ?: System.currentTimeMillis()}.mp4")
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

/** Same primary-footage look pipeline for regular clips and cached reverse-frame items. */
@androidx.annotation.OptIn(UnstableApi::class, ExperimentalApi::class)
internal fun primaryClipVideoEffects(options: ExportOptions): List<androidx.media3.common.Effect> =
    buildList {
        if (options.primaryQuarterTurns != 0 || options.primaryFlipHorizontal) {
            add(androidx.media3.effect.ScaleAndRotateTransformation.Builder()
                .setScale(if (options.primaryFlipHorizontal) -1f else 1f, 1f)
                .setRotationDegrees(options.primaryQuarterTurns * 90f)
                .build())
        }
        when (options.look) {
            Look.ORIGINAL -> Unit
            Look.MONO -> add(RgbFilter.createGrayscaleFilter())
            Look.INVERT -> add(RgbFilter.createInvertedFilter())
            Look.WARM -> add(RgbAdjustment.Builder()
                .setRedScale(1.10f).setBlueScale(0.90f).build())
            Look.COOL -> add(RgbAdjustment.Builder()
                .setRedScale(0.90f).setBlueScale(1.10f).build())
        }
        if (options.colorGrade != ColorGrade()) add(ColorGradeEffect(options.colorGrade))
        options.colorLutProfile?.let { add(it.toMedia3Effect()) }
        if (options.primaryBlur > 0f || options.primarySharpen > 0f ||
            options.primaryGrain > 0f || options.primaryVignette > 0f) {
            add(VideoMatteEffect(PhotoEffects(
                blur = options.primaryBlur, sharpen = options.primarySharpen,
                grain = options.primaryGrain
            )))
        }
        if (options.faceRetouch.isActive) {
            add(ManualFaceRetouchEffect(options.faceRetouch))
        }
        if (options.primaryVignette > 0f) add(VideoVignetteEffect(options.primaryVignette))
    }
