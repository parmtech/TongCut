package au.parmtech.tongcut

import android.media.MediaCodecList
import androidx.media3.common.MimeTypes

enum class ExportVideoCodec(val title: String, val mimeType: String) {
    H264("H.264", MimeTypes.VIDEO_H264),
    H265("H.265", MimeTypes.VIDEO_H265)
}

/** Only expose codecs the device reports as encoders; Transformer still owns fallback. */
object ExportVideoCodecSupport {
    fun available(): List<ExportVideoCodec> {
        val encoderTypes = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .asSequence()
            .filter { it.isEncoder }
            .flatMap { it.supportedTypes.asSequence() }
            .map { it.lowercase() }
            .toSet()
        return policy(encoderTypes)
    }

    internal fun policy(encoderTypes: Set<String>): List<ExportVideoCodec> =
        ExportVideoCodec.entries.filter { it.mimeType.lowercase() in encoderTypes }
}

