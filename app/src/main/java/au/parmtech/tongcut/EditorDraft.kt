package au.parmtech.tongcut

/** Serializable, immutable editor state. A source URI is stored, not copied to app storage. */
data class DraftClip(
    val sourceUri: String,
    val label: String,
    val durationMs: Long,
    val inMs: Long = 0,
    val outMs: Long = durationMs
) {
    init {
        require(sourceUri.isNotBlank()) { "Missing source" }
        require(durationMs >= 100) { "Source is too short" }
        require(inMs >= 0 && outMs <= durationMs && outMs - inMs >= 100) { "Invalid trim" }
    }

    val editedDurationMs: Long get() = outMs - inMs

    fun withStart(value: Long): DraftClip = copy(inMs = value.coerceIn(0, outMs - 100))
    fun withEnd(value: Long): DraftClip = copy(outMs = value.coerceIn(inMs + 100, durationMs))

    /** Splits on the SOURCE timeline. Each result must keep at least 100 ms. */
    fun split(sourcePositionMs: Long): Pair<DraftClip, DraftClip>? {
        if (sourcePositionMs < inMs + 100 || sourcePositionMs > outMs - 100) return null
        return copy(outMs = sourcePositionMs) to copy(inMs = sourcePositionMs)
    }
}

data class EditorDraft(
    val title: String = "Untitled Project",
    val clips: List<DraftClip> = emptyList(),
    val selectedIndex: Int = 0,
    val look: Look = Look.ORIGINAL,
    val canvas: CanvasRatio = CanvasRatio.SOURCE,
    val shortSidePixels: Int = 1080,
    val muteOriginalAudio: Boolean = false
) {
    val selectedClip: DraftClip? get() = clips.getOrNull(selectedIndex)

    fun normalized(): EditorDraft =
        copy(selectedIndex = if (clips.isEmpty()) 0 else selectedIndex.coerceIn(0, clips.lastIndex))

    fun withReplacedClip(index: Int, updated: DraftClip): EditorDraft {
        require(index in clips.indices)
        val mutable = clips.toMutableList()
        mutable[index] = updated
        return copy(clips = mutable)
    }

    fun withMovedClip(from: Int, to: Int): EditorDraft {
        require(from in clips.indices && to in clips.indices)
        val mutable = clips.toMutableList()
        val moved = mutable.removeAt(from)
        mutable.add(to, moved)
        return copy(clips = mutable, selectedIndex = to)
    }

    fun withRemovedClip(index: Int): EditorDraft {
        require(index in clips.indices)
        return copy(clips = clips.filterIndexed { i, _ -> i != index }).normalized()
    }

    fun splitSelected(sourcePositionMs: Long): EditorDraft? {
        val selected = selectedClip ?: return null
        val halves = selected.split(sourcePositionMs) ?: return null
        val items = clips.toMutableList()
        items.removeAt(selectedIndex)
        items.add(selectedIndex, halves.second)
        items.add(selectedIndex, halves.first)
        return copy(clips = items)
    }

    fun exportOptions() = ExportOptions(look, canvas, shortSidePixels, muteOriginalAudio)
}

/** Bounded undo/redo history. The caller persists only current snapshot; not old UI states. */
class EditHistory<T>(initial: T, private val maxUndo: Int = 80) {
    init { require(maxUndo >= 1) }
    private val undoStack = ArrayDeque<T>()
    private val redoStack = ArrayDeque<T>()
    var current: T = initial
        private set

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun reset(snapshot: T) {
        current = snapshot
        undoStack.clear()
        redoStack.clear()
    }

    fun record(before: T, after: T): Boolean {
        if (before == after) return false
        // A trim gesture may change the UI transiently; history still records one gesture.
        check(current == before) { "History is out of sync" }
        if (undoStack.size == maxUndo) undoStack.removeFirst()
        undoStack.addLast(before)
        redoStack.clear()
        current = after
        return true
    }

    fun apply(next: T) = record(current, next)

    fun undo(): T? {
        if (undoStack.isEmpty()) return null
        redoStack.addLast(current)
        current = undoStack.removeLast()
        return current
    }

    fun redo(): T? {
        if (redoStack.isEmpty()) return null
        if (undoStack.size == maxUndo) undoStack.removeFirst()
        undoStack.addLast(current)
        current = redoStack.removeLast()
        return current
    }
}
