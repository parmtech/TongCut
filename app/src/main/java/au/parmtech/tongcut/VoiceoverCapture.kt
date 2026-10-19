package au.parmtech.tongcut

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Environment
import java.io.File
import java.util.UUID

/** Records an original local AAC voiceover. No network, camera or media-library access. */
internal class VoiceoverCapture(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var destination: File? = null
    val isRecording: Boolean get() = recorder != null

    fun start() {
        check(!isRecording) { "A voiceover recording is already active" }
        val directory = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            ?: context.filesDir
        require(directory.exists() || directory.mkdirs()) { "Cannot create voiceover folder" }
        val file = File(directory, "TongCut-voice-${UUID.randomUUID()}.m4a")
        var initialized: MediaRecorder? = null
        try {
            initialized = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context)
                else @Suppress("DEPRECATION") MediaRecorder()
            initialized.apply {
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
        } catch (failure: Exception) {
            runCatching { initialized?.reset() }
            runCatching { initialized?.release() }
            file.delete()
            throw IllegalStateException(
                "Microphone recording could not start: ${failure.message}", failure
            )
        }
    }

    /** Returns a durable, app-private file plus the actual encoded media duration. */
    fun finish(): Pair<Uri, Long> {
        val active = checkNotNull(recorder) { "No active voiceover recording" }
        val file = checkNotNull(destination)
        recorder = null
        destination = null
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
            active.reset()
            active.release()
        }
    }

    /** Never leave an incomplete recording on disk after navigation/cancellation. */
    fun cancel() {
        val active = recorder
        recorder = null
        val file = destination
        destination = null
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
