package au.parmtech.tongcut

import java.util.UUID

/** Serializable, immutable editor state. A source URI is stored, not copied to app storage. */
data class DraftClip(
    val sourceUri: String,
    val label: String,
    val durationMs: Long,
    val inMs: Long = 0,
    val outMs: Long = durationMs,
    val sourceAudioMuted: Boolean = false,
    val reverseVideo: Boolean = false,
    /** Stable identity used by cut-attached transitions and persistence. */
    val id: String = UUID.randomUUID().toString()
) {
    init {
        require(sourceUri.isNotBlank()) { "Missing source" }
        require(id.isNotBlank() && id.length <= 100) { "Invalid clip identifier" }
        require(durationMs >= 100) { "Source is too short" }
        require(inMs >= 0 && outMs <= durationMs && outMs - inMs >= 100) { "Invalid trim" }
    }

    val editedDurationMs: Long get() = outMs - inMs

    fun withStart(value: Long): DraftClip = copy(inMs = value.coerceIn(0, outMs - 100))
    fun withEnd(value: Long): DraftClip = copy(outMs = value.coerceIn(inMs + 100, durationMs))

    /** Reconnects moved media. Retain the edit where possible, clamp to new duration. */
    fun relink(uri: String, name: String, replacementDurationMs: Long): DraftClip {
        require(uri.isNotBlank()) { "Missing replacement source" }
        require(replacementDurationMs >= 100) { "Replacement video is too short" }
        val safeStart = inMs.coerceIn(0L, replacementDurationMs - 100)
        val safeEnd = outMs.coerceIn(safeStart + 100, replacementDurationMs)
        return copy(sourceUri = uri, label = name, durationMs = replacementDurationMs,
            inMs = safeStart, outMs = safeEnd)
    }

    /** Splits on the SOURCE timeline. Each result must keep at least 100 ms. */
    fun split(sourcePositionMs: Long): Pair<DraftClip, DraftClip>? {
        if (sourcePositionMs < inMs + 100 || sourcePositionMs > outMs - 100) return null
        return copy(outMs = sourcePositionMs) to copy(
            id = UUID.randomUUID().toString(), inMs = sourcePositionMs
        )
    }
}

data class EditorDraft(
    val title: String = "Untitled Project",
    val clips: List<DraftClip> = emptyList(),
    val selectedIndex: Int = 0,
    val look: Look = Look.ORIGINAL,
    val colorGrade: ColorGrade = ColorGrade(),
    val exportVideoCodec: ExportVideoCodec = ExportVideoCodec.H264,
    val exportVideoBitrateBps: Int = 8_000_000,
    val canvas: CanvasRatio = CanvasRatio.SOURCE,
    val shortSidePixels: Int = 1080,
    val muteOriginalAudio: Boolean = false,
    // Secondary audio/video/text/image lanes; legacy primary clips stay unchanged.
    val layers: LayerTimeline = LayerTimeline(),
    val exportAudioBitrateBps: Int = 128_000,
    val exportMaxFrameRate: Int = ExportFrameRate.DEFAULT,
    val colorLutProfile: ColorLutProfile? = null,
    /** Bounded SDR blur for primary footage, shared by editor preview and export. */
    val primaryBlur: Float = 0f,
    val primarySharpen: Float = 0f,
    val primaryGrain: Float = 0f,
    val primaryVignette: Float = 0f,
    /** Quarter-turn rotation and horizontal mirror for primary footage. */
    val primaryQuarterTurns: Int = 0,
    val primaryFlipHorizontal: Boolean = false,
    val faceRetouch: ManualFaceRetouch = ManualFaceRetouch(),
    /** Stable clip-pair transitions; legacy project schemas load this empty. */
    val primaryTransitions: List<PrimaryTransitionSpec> = emptyList()
) {
    val selectedClip: DraftClip? get() = clips.getOrNull(selectedIndex)

    init {
        require(exportVideoBitrateBps in 1_000_000..50_000_000)
        require(exportAudioBitrateBps in 64_000..320_000)
        require(exportMaxFrameRate in ExportFrameRate.choices)
        require(primaryBlur.isFinite() && primaryBlur in 0f..0.08f)
        require(primarySharpen.isFinite() && primarySharpen in 0f..1f)
        require(primaryGrain.isFinite() && primaryGrain in 0f..1f)
        require(primaryVignette.isFinite() && primaryVignette in 0f..1f)
        require(primaryQuarterTurns in 0..3)
        require(clips.map { it.id }.toSet().size == clips.size) { "Duplicate clip identifier" }
        require(primaryTransitions.map { it.afterClipIndex }.toSet().size == primaryTransitions.size)
        require(primaryTransitions.all { it.afterClipIndex in 0 until clips.lastIndex })
    }

    fun normalized(): EditorDraft =
        copy(selectedIndex = if (clips.isEmpty()) 0 else selectedIndex.coerceIn(0, clips.lastIndex))

    fun withReplacedClip(index: Int, updated: DraftClip): EditorDraft {
        require(index in clips.indices)
        val mutable = clips.toMutableList()
        mutable[index] = updated.copy(id = clips[index].id)
        return copy(clips = mutable)
    }

    private fun reattachAdjacentTransitions(newClips: List<DraftClip>): List<PrimaryTransitionSpec> =
        primaryTransitions.mapNotNull { transition ->
            val outgoingId = clips[transition.afterClipIndex].id
            val incomingId = clips[transition.afterClipIndex + 1].id
            val newIndex = newClips.indexOfFirst { it.id == outgoingId }
            if (newIndex >= 0 && newClips.getOrNull(newIndex + 1)?.id == incomingId) {
                transition.copy(afterClipIndex = newIndex)
            } else null
        }

    fun withMovedClip(from: Int, to: Int): EditorDraft {
        require(from in clips.indices && to in clips.indices)
        val mutable = clips.toMutableList()
        val moved = mutable.removeAt(from)
        mutable.add(to, moved)
        return copy(clips = mutable, selectedIndex = to,
            primaryTransitions = reattachAdjacentTransitions(mutable))
    }

    fun withRemovedClip(index: Int): EditorDraft {
        require(index in clips.indices)
        val remaining = clips.filterIndexed { i, _ -> i != index }
        return copy(clips = remaining,
            primaryTransitions = reattachAdjacentTransitions(remaining)).normalized()
    }

    fun splitSelected(sourcePositionMs: Long): EditorDraft? {
        val selected = selectedClip ?: return null
        val halves = selected.split(sourcePositionMs) ?: return null
        val items = clips.toMutableList()
        items.removeAt(selectedIndex)
        items.add(selectedIndex, halves.second)
        items.add(selectedIndex, halves.first)
        val transitions = primaryTransitions.map { transition ->
            if (transition.afterClipIndex == selectedIndex) transition.copy(afterClipIndex = selectedIndex + 1)
            else transition
        }
        return copy(clips = items, primaryTransitions = transitions)
    }

    /** Adds or removes a transition attached to the cut after [index]. */
    fun withPrimaryTransitionAfter(index: Int, durationMs: Long?): EditorDraft {
        require(index in 0 until clips.lastIndex) { "A transition needs two adjacent primary clips" }
        val kept = primaryTransitions.filterNot { it.afterClipIndex == index }
        if (durationMs == null) return copy(primaryTransitions = kept)
        require(!clips[index].reverseVideo && !clips[index + 1].reverseVideo) {
            "Primary transitions are unavailable on reversed clips"
        }
        val updated = kept + PrimaryTransitionSpec(index, durationMs * 1_000L)
        PrimaryTransitionPlan.build(
            clips.map { PrimaryTransitionClip(it.id, it.durationMs * 1_000L,
                it.inMs * 1_000L, it.outMs * 1_000L) }, updated
        )
        return copy(primaryTransitions = updated.sortedBy { it.afterClipIndex })
    }

    /** Prevent silent omission of layered content by the older sequential renderer. */
    val canExportWithLegacyRenderer: Boolean get() =
        layers.layers.none { it.kind == LayerKind.STICKER } &&
            runCatching {
                val totalUs = SequentialTimeline(clips, primaryTransitions).totalMs * 1_000L
                require(totalUs > 0)
                AudioMixPlan.from(layers, totalUs)
                val visual = VisualMixPlan.from(layers, totalUs)
                visual.filter { lane -> lane.items.any {
                    it is VisualPart.Clip && it.kind == LayerKind.VIDEO
                } }.forEach { lane ->
                    lane.items.filterIsInstance<VisualPart.Clip>().forEach { item ->
                        require(item.sourceInUs % 1_000L == 0L &&
                            item.durationUs % 1_000L == 0L)
                    }
                }
            }.isSuccess

    fun exportOptions() = ExportOptions(
        look = look,
        ratio = canvas,
        shortSidePixels = shortSidePixels,
        mute = muteOriginalAudio,
        colorGrade = colorGrade,
        videoCodec = exportVideoCodec,
        videoBitrateBps = exportVideoBitrateBps,
        audioBitrateBps = exportAudioBitrateBps,
        maxFrameRate = exportMaxFrameRate,
        colorLutProfile = colorLutProfile,
        primaryBlur = primaryBlur,
        primarySharpen = primarySharpen,
        primaryGrain = primaryGrain,
        primaryVignette = primaryVignette,
        primaryQuarterTurns = primaryQuarterTurns,
        primaryFlipHorizontal = primaryFlipHorizontal,
        faceRetouch = faceRetouch
    )
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
