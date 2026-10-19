package au.parmtech.tongcut

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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
        assertEquals(128_000, options.audioBitrateBps)
        assertEquals(ExportFrameRate.DEFAULT, options.maxFrameRate)
    }

    @Test fun exportFrameRateMaximumFlowsFromDraftAndRejectsUnsupportedValues() {
        assertEquals(60, EditorDraft(exportMaxFrameRate = 60).exportOptions().maxFrameRate)
        assertEquals(listOf(24, 30, 60), ExportFrameRate.choices)
        assertThrows(IllegalArgumentException::class.java) {
            EditorDraft(exportMaxFrameRate = 120)
        }
    }

    @Test fun audioBitratePreferenceFlowsIntoExportAndEnforcesBounds() {
        assertEquals(192_000, EditorDraft(exportAudioBitrateBps = 192_000)
            .exportOptions().audioBitrateBps)
        ExportOptions(audioBitrateBps = 64_000)
        ExportOptions(audioBitrateBps = 320_000)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOutOfRangeAudioBitrate() {
        ExportOptions(audioBitrateBps = 500_000)
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
        assertEquals(listOf(720 to 1280, 1080 to 1920, 2160 to 3840), checked)
        assertTrue(checked.all { (width, height) -> height > width })
    }

    @Test fun invalidAspectAndNonPositivePresetsAreRejected() {
        assertEquals(emptyList<Int>(), ExportVideoCodecSupport.supportedShortSides(
            listOf(720, 1080), Float.NaN
        ) { _, _ -> true })
        assertEquals(emptyList<Int>(), ExportVideoCodecSupport.supportedShortSides(
            listOf(720, 1080), Float.POSITIVE_INFINITY
        ) { _, _ -> true })
        assertEquals(emptyList<Int>(), ExportVideoCodecSupport.supportedShortSides(
            listOf(0, -1), 16f / 9f
        ) { _, _ -> true })
    }
}
