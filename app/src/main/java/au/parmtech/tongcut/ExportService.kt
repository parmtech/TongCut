package au.parmtech.tongcut

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.io.File
import java.util.concurrent.CancellationException

/** Owns Media3 while the editor Activity is stopped or destroyed. */
@androidx.annotation.OptIn(
    androidx.media3.common.util.UnstableApi::class,
    androidx.media3.common.util.ExperimentalApi::class
)
class ExportService : Service() {
    private var exporter: VideoExporter? = null
    private var activeJobId: String? = null
    private var cancellingJobId: String? = null
    private var reversePreparation: Thread? = null
    private var preparedReverseSequences: Map<Int, ReverseImageSequence> = emptyMap()
    private var preparedReverseAudioFiles: Map<Int, File> = emptyMap()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val jobs = ExportJobs.get(applicationContext)
        val type = if (Build.VERSION.SDK_INT >= 35) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(jobs.state.value), type)
        jobs.restore()
        updateNotification(jobs.state.value)

        when (intent?.action) {
            ACTION_CANCEL -> {
                cancelJob(intent.getStringExtra(EXTRA_JOB_ID), jobs)
                stopWhenIdle()
            }
            ACTION_START -> startJob(intent.getStringExtra(EXTRA_JOB_ID), jobs)
            else -> stopWhenIdle()
        }
        return START_NOT_STICKY
    }

    private fun startJob(id: String?, jobs: ExportJobs) {
        if (id == null) {
            stopWhenIdle()
            return
        }
        if (activeJobId != null) return
        val queued = jobs.state.value
        if (queued.id != id || queued.phase != ExportPhase.QUEUED) {
            stopWhenIdle()
            return
        }
        val snapshot = try {
            jobs.snapshotForService(id)
        } catch (error: Exception) {
            null
        }
        if (snapshot == null) {
            jobs.fail(id, "The saved project snapshot is unavailable.")
            updateNotification(jobs.state.value)
            stopWhenIdle()
            return
        }

        activeJobId = id
        jobs.markRunning(id)
        updateNotification(jobs.state.value)
        val reverseClips = snapshot.clips.withIndex().filter { it.value.reverseVideo }
        if (reverseClips.isEmpty()) {
            renderSnapshot(id, snapshot, jobs, emptyMap(), emptyMap())
            return
        }

        val preparation = Thread({
            val cache = ReverseFrameCache(File(cacheDir, "tongcut-reverse"))
            val sequences = LinkedHashMap<Int, ReverseImageSequence>()
            val reverseAudio = LinkedHashMap<Int, File>()
            try {
                reverseClips.forEach { (index, draftClip) ->
                    check(!Thread.currentThread().isInterrupted) { "Reverse preparation cancelled" }
                    val source = Uri.parse(draftClip.sourceUri)
                    val inUs = Math.multiplyExact(draftClip.inMs, 1_000L)
                    val outUs = Math.multiplyExact(draftClip.outMs, 1_000L)
                    val plan = ReversePtsIndexer.index(applicationContext, source, inUs, outUs)
                        ?: throw IllegalArgumentException("No reverse frames were found in ${draftClip.label}.")
                    val segments = ArrayList<CachedReverseVideoSegment>()
                    var from = 0
                    while (from < plan.frameCount) {
                        check(!Thread.currentThread().isInterrupted) { "Reverse preparation cancelled" }
                        var to = minOf(from + ReverseFrameCache.MAX_SEGMENT_LEASE_FRAMES, plan.frameCount)
                        var chunk = plan.sliceOutputRange(from, to)
                        while (chunk.durationUs > 20_000_000L && to > from + 1) {
                            to--
                            chunk = plan.sliceOutputRange(from, to)
                        }
                        require(chunk.durationUs <= 20_000_000L) {
                            "A reverse frame interval exceeds the decoder segment limit"
                        }
                        segments += ExactReverseFrameDecoder.materialize(
                            context = applicationContext,
                            source = source,
                            inUs = chunk.sourceInUs,
                            outUs = chunk.sourceOutUs,
                            plan = chunk,
                            cache = cache,
                            isCancelled = { Thread.currentThread().isInterrupted }
                        )
                        from = to
                    }
                    sequences[index] = ReverseImageSequence.openSegments(
                        segments,
                        cache,
                        snapshot.exportMaxFrameRate,
                        primaryClipVideoEffects(snapshot.exportOptions())
                    ) ?: throw IllegalStateException("Reverse frame cache is incomplete for ${draftClip.label}.")
                    if (!snapshot.muteOriginalAudio && !draftClip.sourceAudioMuted) {
                        ReverseAudioMaterializer.materialize(
                            context = applicationContext,
                            source = source,
                            sourceInUs = inUs,
                            sourceOutUs = outUs,
                            targetDurationUs = Math.multiplyExact(draftClip.editedDurationMs, 1_000L),
                            isCancelled = { Thread.currentThread().isInterrupted }
                        )?.let { audioFile ->
                            val totalBytes = reverseAudio.values.sumOf { it.length() } + audioFile.length()
                            if (totalBytes > MAX_REVERSE_AUDIO_CACHE_BYTES) {
                                audioFile.delete()
                                throw IllegalArgumentException(
                                    "Reverse audio cache exceeds the 512 MB per-export limit"
                                )
                            }
                            reverseAudio[index] = audioFile
                        }
                    }
                }
                Handler(Looper.getMainLooper()).post {
                    reversePreparation = null
                    if (activeJobId != id || cancellingJobId == id) {
                        sequences.values.forEach { it.close() }
                        reverseAudio.values.forEach { it.delete() }
                        if (cancellingJobId == id) jobs.cancelled(id)
                        updateNotification(jobs.state.value)
                        stopJob()
                    } else {
                        preparedReverseSequences = sequences
                        preparedReverseAudioFiles = reverseAudio
                        renderSnapshot(id, snapshot, jobs, sequences, reverseAudio)
                    }
                }
            } catch (error: Throwable) {
                sequences.values.forEach { it.close() }
                reverseAudio.values.forEach { it.delete() }
                Handler(Looper.getMainLooper()).post {
                    reversePreparation = null
                    if (cancellingJobId == id || error is CancellationException || error is InterruptedException) {
                        jobs.cancelled(id)
                    } else {
                        jobs.fail(id, "Unable to prepare reverse video: ${error.message ?: error.javaClass.simpleName}")
                    }
                    updateNotification(jobs.state.value)
                    stopJob()
                }
            }
        }, "TongCut-reverse-$id").apply { isDaemon = true }
        reversePreparation = preparation
        preparation.start()
    }

    private fun renderSnapshot(
        id: String,
        snapshot: EditorDraft,
        jobs: ExportJobs,
        reverseSequences: Map<Int, ReverseImageSequence>,
        reverseAudioFiles: Map<Int, File>
    ) {
        if (activeJobId != id || cancellingJobId == id) {
            reverseSequences.values.forEach { it.close() }
            reverseAudioFiles.values.forEach { it.delete() }
            return
        }
        VideoExporter(applicationContext).also { renderer ->
            exporter = renderer
            try {
                val clips = snapshot.clips.map { clip ->
                    Clip(
                        uri = Uri.parse(clip.sourceUri),
                        label = clip.label,
                        durationMs = clip.durationMs,
                        startMs = clip.inMs,
                        endMs = clip.outMs,
                        removeAudio = clip.sourceAudioMuted,
                        reverseVideo = clip.reverseVideo
                    )
                }
                renderer.start(
                    clips = clips,
                    options = snapshot.exportOptions(),
                    overlayLayers = snapshot.layers,
                    onProgress = { percent ->
                        jobs.updateProgress(id, percent)
                        updateNotification(jobs.state.value)
                    },
                    onSuccess = { uri ->
                        preparedReverseSequences = emptyMap()
                        jobs.complete(id, uri)
                        updateNotification(jobs.state.value)
                        stopJob()
                    },
                    onError = { message ->
                        preparedReverseSequences = emptyMap()
                        if (cancellingJobId != id && jobs.state.value.phase == ExportPhase.RUNNING) {
                            jobs.fail(id, message)
                        }
                        updateNotification(jobs.state.value)
                        stopJob()
                    },
                    onWarning = { warning ->
                        jobs.updateWarning(id, warning)
                        updateNotification(jobs.state.value)
                    },
                    exportJobId = id,
                    reverseSequences = reverseSequences,
                    reverseAudioFiles = reverseAudioFiles
                )
                preparedReverseAudioFiles = emptyMap()
            } catch (error: Exception) {
                reverseSequences.values.forEach { runCatching { it.close() } }
                reverseAudioFiles.values.forEach { it.delete() }
                preparedReverseSequences = emptyMap()
                preparedReverseAudioFiles = emptyMap()
                jobs.fail(id, "Unable to render export: ${error.message ?: "unknown error"}")
                updateNotification(jobs.state.value)
                stopJob()
            }
        }
    }

    private fun cancelJob(id: String?, jobs: ExportJobs) {
        val current = jobs.state.value
        if (id == null || current.id != id) return
        if (activeJobId == null && current.phase in setOf(
                ExportPhase.QUEUED, ExportPhase.RUNNING, ExportPhase.INTERRUPTED
            )) {
            jobs.cancelled(id)
            updateNotification(jobs.state.value)
            return
        }
        if (id != activeJobId) return
        cancellingJobId = id
        reversePreparation?.let { preparation ->
            preparation.interrupt()
            updateNotification(jobs.state.value.copy(message = "Cancelling reverse preparation…"))
            return
        }
        if (exporter?.cancel() == true) {
            jobs.cancelled(id)
            updateNotification(jobs.state.value)
            stopJob()
        } else {
            cancellingJobId = null
            updateNotification(jobs.state.value.copy(message = "Saving the finished video…"))
        }
    }

    private fun stopJob() {
        reversePreparation?.interrupt()
        reversePreparation = null
        preparedReverseSequences.values.forEach { runCatching { it.close() } }
        preparedReverseSequences = emptyMap()
        preparedReverseAudioFiles.values.forEach { it.delete() }
        preparedReverseAudioFiles = emptyMap()
        exporter = null
        activeJobId = null
        cancellingJobId = null
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private fun stopWhenIdle() {
        if (activeJobId == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun updateNotification(state: ExportJobState) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification(state))
    }

    private fun notification(state: ExportJobState): Notification {
        val active = state.phase == ExportPhase.QUEUED || state.phase == ExportPhase.RUNNING
        val title = when (state.phase) {
            ExportPhase.COMPLETED -> "TongCut export complete"
            ExportPhase.FAILED -> "TongCut export stopped"
            ExportPhase.CANCELLED -> "TongCut export cancelled"
            else -> "Exporting ${state.title.ifBlank { "video" }}"
        }
        val detail = when (state.phase) {
            ExportPhase.COMPLETED -> "Saved to Movies/TongCut"
            ExportPhase.FAILED -> state.message ?: "Export could not be completed"
            ExportPhase.CANCELLED -> "Your saved edit is ready to export again"
            else -> state.message ?: "${state.progress}% complete · You can leave TongCut open in the background"
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_export_notification)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setOnlyAlertOnce(true)
            .setOngoing(active)
            .setAutoCancel(!active)
            .setContentIntent(PendingIntent.getActivity(
                this,
                CONTENT_INTENT,
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ))
        if (active) {
            builder.setProgress(100, state.progress.coerceIn(0, 99), state.phase == ExportPhase.QUEUED)
            state.id?.let { id ->
                builder.addAction(
                    0,
                    "Cancel",
                    PendingIntent.getService(
                        this,
                        CANCEL_INTENT,
                        cancelIntent(this, id),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
            }
        }
        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Video exports",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Progress and results for TongCut video exports" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        val id = activeJobId
        if (id != null) {
            cancellingJobId = id
            reversePreparation?.interrupt()
            exporter?.cancel()
            ExportJobs.get(applicationContext).fail(
                id,
                "Android paused this long export. Restart it from the saved edit."
            )
        }
        stopJob()
    }

    override fun onDestroy() {
        val interruptedId = activeJobId
        if (interruptedId != null) {
            cancellingJobId = interruptedId
            if (exporter?.cancel() == true) {
                ExportJobs.get(applicationContext).interrupted(interruptedId)
            } else {
                // A false cancel result means output may already be in its publish/save phase.
                // Persist recovery state now but leave cleanup to the next service restore.
                ExportJobs.get(applicationContext).interrupted(
                    interruptedId,
                    cleanupIncomplete = false
                )
            }
        } else exporter?.cancel()
        reversePreparation?.interrupt()
        preparedReverseSequences.values.forEach { runCatching { it.close() } }
        preparedReverseSequences = emptyMap()
        preparedReverseAudioFiles.values.forEach { it.delete() }
        preparedReverseAudioFiles = emptyMap()
        exporter = null
        activeJobId = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "au.parmtech.tongcut.export.START"
        const val ACTION_CANCEL = "au.parmtech.tongcut.export.CANCEL"
        const val EXTRA_JOB_ID = "export_job_id"
        private const val CHANNEL_ID = "tongcut-video-export"
        private const val NOTIFICATION_ID = 7341
        private const val CONTENT_INTENT = 7342
        private const val CANCEL_INTENT = 7343
        private const val MAX_REVERSE_AUDIO_CACHE_BYTES = 512L * 1024L * 1024L

        fun cancelIntent(context: Context, jobId: String) = Intent(context, ExportService::class.java)
            .setAction(ACTION_CANCEL)
            .putExtra(EXTRA_JOB_ID, jobId)
    }
}
