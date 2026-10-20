package au.parmtech.tongcut

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import java.io.File
import java.util.UUID

/** Records an original local AAC voiceover. No network, camera or media-library access. */
internal class VoiceoverCapture(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var destination: File? = null
    private var paused = false
    private var activeSegmentStartedAtMs = 0L
    private var accumulatedDurationMs = 0L
    val isRecording: Boolean get() = recorder != null
    val isPaused: Boolean get() = recorder != null && paused
    val elapsedDurationMs: Long
        get() = accumulatedDurationMs + if (recorder != null && !paused) {
            (SystemClock.elapsedRealtime() - activeSegmentStartedAtMs).coerceAtLeast(0L)
        } else 0L

    @Suppress("DEPRECATION")
    private fun legacyRecorder(): MediaRecorder = MediaRecorder()

    fun start() {
        check(!isRecording) { "A voiceover recording is already active" }
        val directory = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            ?: context.filesDir
        require(directory.exists() || directory.mkdirs()) { "Cannot create voiceover folder" }
        val file = File(directory, "TongCut-voice-${UUID.randomUUID()}.m4a")
        var initialized: MediaRecorder? = null
        try {
            val active = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context)
                else legacyRecorder()
            initialized = active
            active.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44_100)
                setAudioEncodingBitRate(128_000)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            destination = file
            recorder = initialized
            paused = false
            accumulatedDurationMs = 0L
            activeSegmentStartedAtMs = SystemClock.elapsedRealtime()
        } catch (failure: Exception) {
            runCatching { initialized?.reset() }
            runCatching { initialized?.release() }
            file.delete()
            throw IllegalStateException(
                "Microphone recording could not start: ${failure.message}", failure
            )
        }
    }

    fun pause() {
        val active = checkNotNull(recorder) { "No active voiceover recording" }
        check(!paused) { "Voiceover recording is already paused" }
        active.pause()
        accumulatedDurationMs = elapsedDurationMs
        paused = true
    }

    fun resume() {
        val active = checkNotNull(recorder) { "No active voiceover recording" }
        check(paused) { "Voiceover recording is not paused" }
        active.resume()
        activeSegmentStartedAtMs = SystemClock.elapsedRealtime()
        paused = false
    }

    /** Returns a durable, app-private file plus the actual encoded media duration. */
    fun finish(): Pair<Uri, Long> {
        val active = checkNotNull(recorder) { "No active voiceover recording" }
        val file = checkNotNull(destination)
        recorder = null
        destination = null
        paused = false
        activeSegmentStartedAtMs = 0L
        accumulatedDurationMs = 0L
        try {
            active.stop()
            require(file.exists() && file.length() > 256L) {
                "Voiceover recording was empty"
            }
            val duration = MediaMetadataRetriever().use { metadata ->
                metadata.setDataSource(file.absolutePath)
                metadata.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION
                )?.toLongOrNull()
            } ?: throw IllegalArgumentException("Unable to read recorded voiceover length")
            require(duration >= 100L) { "Please record at least 100 ms" }
            return Uri.fromFile(file) to duration
        } catch (failure: Exception) {
            file.delete()
            throw IllegalStateException("Voiceover could not be saved: ${failure.message}", failure)
        } finally {
            runCatching { active.reset() }
            runCatching { active.release() }
        }
    }

    /** Never leave an incomplete recording on disk after navigation/cancellation. */
    fun cancel() {
        val active = recorder
        recorder = null
        val file = destination
        destination = null
        paused = false
        activeSegmentStartedAtMs = 0L
        accumulatedDurationMs = 0L
        if (active != null) {
            runCatching { active.stop() }
            runCatching { active.reset() }
            runCatching { active.release() }
        }
        file?.delete()
    }

    /** Delete an uncommitted recording, but retain files referenced by history. */
    fun discard(uri: Uri) {
        val path = uri.path ?: return
        val base = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            ?: context.filesDir
        val target = File(path)
        if (target.parentFile?.canonicalPath == base.canonicalPath &&
            target.name.startsWith("TongCut-voice-") && target.name.endsWith(".m4a")
        ) target.delete()
    }
}
