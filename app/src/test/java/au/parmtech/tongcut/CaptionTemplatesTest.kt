package au.parmtech.tongcut

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionTemplatesTest {
    private fun base() = EditorDraft(clips = listOf(
        DraftClip("content://footage", "Clip", durationMs = 10_000)
    ))

    @Test fun everyOriginalTemplateInsertsEditableStyledLayersAsOneUndoableDraftChange() {
        assertEquals(6, TongCutCaptionTemplates.all.size)
        assertEquals(6, TongCutCaptionTemplates.all.map { it.id }.toSet().size)

        TongCutCaptionTemplates.all.forEach { template ->
            val initial = base()
            val result = TongCutCaptionTemplates.insert(initial, 2_000, template)

            assertTrue(initial.layers.layers.isEmpty())
            assertEquals(template.lines.size, result.layers.layers.size)
            assertTrue(result.layers.layers.all { it.kind == LayerKind.TEXT })
            result.layers.layers.zip(template.lines).forEach { (layer, spec) ->
                assertEquals(2_000_000L, layer.startUs)
                assertEquals(3_000_000L, layer.durationUs)
                assertEquals(spec.placeholder, layer.text)
                assertEquals(spec.style, layer.captionStyle)
            }

            val history = EditHistory(initial)
            history.apply(result)
            assertFalse(history.current.layers.layers.isEmpty())
            assertEquals(initial, history.undo())
            assertEquals(result, history.redo())
        }
    }
}
