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
}
