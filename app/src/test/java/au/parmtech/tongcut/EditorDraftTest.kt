package au.parmtech.tongcut

import org.junit.Assert.*
import org.junit.Test

class EditorDraftTest {
    private fun item() = DraftClip("content://sample/one", "one.mp4", 10_000L)

    @Test fun splitPreservesDurationAndSource() {
        val original = item().copy(inMs = 500, outMs = 7_500)
        val (a, b) = original.split(3_000)!!
        assertEquals(7_000L, a.editedDurationMs + b.editedDurationMs)
        assertEquals(original.sourceUri, b.sourceUri)
        assertEquals(500L, a.inMs)
        assertEquals(7_500L, b.outMs)
    }

    @Test fun splitRejectsTooShortSegments() {
        assertNull(item().split(30))
        assertNull(item().split(9_950))
    }

    @Test fun trimsClampToValidRange() {
        val clip = item().withStart(20_000)
        assertEquals(9_900L, clip.inMs)
        assertEquals(10_000L, clip.outMs)
        assertEquals(100L, clip.editedDurationMs)
    }

    @Test fun moveAndRemovalRemainNonDestructive() {
        val one = item()
        val two = DraftClip("content://sample/two", "two.mp4", 4_000)
        val before = EditorDraft(clips = listOf(one, two))
        val moved = before.withMovedClip(0, 1)
        assertEquals(two, moved.clips[0])
        assertEquals(1, moved.selectedIndex)
        assertEquals(one, before.clips[0])
        assertEquals(1, moved.withRemovedClip(1).clips.size)
    }

    @Test fun undoRedoAndRedoInvalidation() {
        val history = EditHistory("A", 2)
        history.apply("B")
        history.apply("C")
        assertEquals("B", history.undo())
        assertEquals("C", history.redo())
        assertEquals("B", history.undo())
        history.apply("D")
        assertFalse(history.canRedo)
        assertEquals("B", history.undo())
        assertEquals("A", history.undo())
        assertNull(history.undo())
    }

    @Test fun oneDragOneUndoEntry() {
        val history = EditHistory(0)
        val before = history.current
        val preview = 40
        history.record(before, preview)
        assertEquals(0, history.undo())
    }

    @Test fun relinkClampsTrimSafely() {
        val clip = item().copy(inMs = 5_000, outMs = 9_000)
        val replacement = clip.relink("content://recovered/video", "recovered.mp4", 6_000)
        assertEquals(5_000L, replacement.inMs)
        assertEquals(6_000L, replacement.outMs)
        assertEquals("content://recovered/video", replacement.sourceUri)
        assertEquals("content://sample/one", clip.sourceUri)
    }

    @Test fun selectedColorGradeFlowsIntoExportOptions() {
        val grade = ColorGrade(exposure = 0.5f, contrast = 1.2f,
            saturation = 0.7f, temperature = 0.3f)
        assertEquals(grade, EditorDraft(colorGrade = grade).exportOptions().colorGrade)
    }

    @Test fun primaryBlurIsBoundedAndFlowsIntoExportOptions() {
        assertEquals(0.04f, EditorDraft(primaryBlur = 0.04f).exportOptions().primaryBlur)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectUnboundedPrimaryBlur() {
        EditorDraft(primaryBlur = 0.081f)
    }

    @Test fun primaryTransformFlowsIntoExportOptions() {
        val draft = EditorDraft(primaryQuarterTurns = 3, primaryFlipHorizontal = true)
        assertEquals(3, draft.exportOptions().primaryQuarterTurns)
        assertTrue(draft.exportOptions().primaryFlipHorizontal)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectUnsupportedPrimaryQuarterTurns() {
        EditorDraft(primaryQuarterTurns = 4)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectUnusableReplacement() {
        item().relink("content://bad", "bad", 50)
    }
}
