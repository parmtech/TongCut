package au.parmtech.tongcut

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Test

class ExportVideoCodecTest {
    @Test fun policyOnlyOffersTransformersSupportedCodecsPresentOnDevice() {
        assertEquals(
            listOf(ExportVideoCodec.H264),
            ExportVideoCodecSupport.policy(setOf(MimeTypes.VIDEO_H264, "video/av01"))
        )
        assertEquals(
            ExportVideoCodec.entries.toList(),
            ExportVideoCodecSupport.policy(setOf(MimeTypes.VIDEO_H264, MimeTypes.VIDEO_H265))
        )
        assertEquals(emptyList<ExportVideoCodec>(), ExportVideoCodecSupport.policy(emptySet()))
    }

    @Test fun newExportSettingsRetainCompatibleDefaults() {
        val options = EditorDraft().exportOptions()
        assertEquals(ExportVideoCodec.H264, options.videoCodec)
        assertEquals(8_000_000, options.videoBitrateBps)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnboundedExportBitrate() {
        ExportOptions(videoBitrateBps = 100_000_000)
    }
}

