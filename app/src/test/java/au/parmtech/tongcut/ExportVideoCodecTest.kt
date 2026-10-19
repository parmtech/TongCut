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

    @Test fun resolutionPolicyUsesAspectRatioAndRequiresReportedSupport() {
        val checked = mutableListOf<Pair<Int, Int>>()
        val result = ExportVideoCodecSupport.supportedShortSides(
            listOf(720, 1080, 2160), 9f / 16f
        ) { width, height ->
            checked += width to height
            height <= 1920
        }
        assertEquals(listOf(720, 1080), result)
        assertEquals(listOf(406 to 720, 608 to 1080, 1216 to 2160), checked)
    }

    @Test fun invalidAspectAndNonPositivePresetsAreRejected() {
        assertEquals(emptyList<Int>(), ExportVideoCodecSupport.supportedShortSides(
            listOf(720, 1080), Float.NaN
        ) { _, _ -> true })
        assertEquals(emptyList<Int>(), ExportVideoCodecSupport.supportedShortSides(
            listOf(0, -1), 16f / 9f
        ) { _, _ -> true })
    }
}

