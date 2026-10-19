package au.parmtech.tongcut

import android.media.MediaCodecList
import androidx.media3.common.MimeTypes
import kotlin.math.roundToInt

enum class ExportVideoCodec(val title: String, val mimeType: String) {
    H264("H.264", MimeTypes.VIDEO_H264),
    H265("H.265", MimeTypes.VIDEO_H265)
}

/** Only expose codecs the device reports as encoders; Transformer still owns fallback. */
object ExportVideoCodecSupport {
    private val presetShortSides = listOf(720, 1080, 2160)

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

    /**
     * Returns only output sizes at the requested aspect ratio that at least one matching
     * hardware/software encoder reports as supported. This is a capability hint; Transformer
     * may still select another encoder or fall back at runtime.
     */
    fun supportedShortSides(codec: ExportVideoCodec, aspectRatio: Float): List<Int> {
        if (!aspectRatio.isFinite() || aspectRatio <= 0f) return emptyList()
        val encoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder && it.supportedTypes.any { type ->
                type.equals(codec.mimeType, ignoreCase = true)
            } }
        return supportedShortSides(presetShortSides, aspectRatio) { width, height ->
            encoders.any { info ->
                runCatching {
                    info.getCapabilitiesForType(codec.mimeType).videoCapabilities
                        ?.isSizeSupported(width, height) == true
                }.getOrDefault(false)
            }
        }
    }

    internal fun supportedShortSides(
        presets: List<Int>,
        aspectRatio: Float,
        supports: (width: Int, height: Int) -> Boolean
    ): List<Int> = presets.filter { shortSide ->
        if (shortSide <= 0) return@filter false
        val longSide = (shortSide * aspectRatio).roundToInt().coerceAtLeast(2)
        val width = if (aspectRatio >= 1f) longSide else shortSide
        val height = if (aspectRatio >= 1f) shortSide else longSide
        // Encoders generally require even 4:2:0 dimensions; use the nearest even
        // geometry while retaining the requested short edge exactly.
        val evenWidth = if (aspectRatio >= 1f) width + width % 2 else width
        val evenHeight = if (aspectRatio < 1f) height + height % 2 else height
        evenWidth >= 2 && evenHeight >= 2 && supports(evenWidth, evenHeight)
    }
}

