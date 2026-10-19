package au.parmtech.tongcut

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A versioned, atomic draft snapshot; legacy single-project format remains readable. */
class ProjectStore(context: Context, projectId: String? = null) {
    private val file: AtomicFile

    init {
        val filename = if (projectId == null) {
            File(context.filesDir, "tongcut-current-project.json")
        } else {
            require(Regex("[a-zA-Z0-9_-]{1,80}").matches(projectId)) {
                "Unsafe project identifier"
            }
            val folder = File(context.filesDir, "tongcut-projects")
            if (!folder.isDirectory && !folder.mkdirs()) {
                throw IllegalStateException("Cannot create project storage")
            }
            File(folder, "$projectId.json")
        }
        file = AtomicFile(filename)
    }

    fun load(): EditorDraft? {
        if (!file.baseFile.exists() &&
            !File(file.baseFile.path + ".bak").exists()) return null
        val raw = file.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
        val root = JSONObject(raw)
        val schema = root.optInt("schema", -1)
        require(schema in 1..6) { "Unsupported project schema" }
        val array = root.getJSONArray("clips")
        require(array.length() <= 500) { "Project exceeds clip limit" }
        val clips = (0 until array.length()).map { index ->
            val clip = array.getJSONObject(index)
            DraftClip(
                sourceUri = clip.getString("uri"),
                label = clip.optString("label", "Video"),
                durationMs = clip.getLong("durationMs"),
                inMs = clip.getLong("inMs"),
                outMs = clip.getLong("outMs"),
                sourceAudioMuted = clip.optBoolean("sourceAudioMuted", false)
            )
        }
        return EditorDraft(
            title = root.optString("title", "Untitled Project").take(120),
            clips = clips,
            selectedIndex = root.optInt("selected", 0),
            look = Look.entries.firstOrNull { it.name == root.optString("look") } ?: Look.ORIGINAL,
            colorGrade = ColorGradeCodec.decode(root.optJSONObject("colorGrade")),
            exportVideoCodec = ExportVideoCodec.entries.firstOrNull {
                it.name == root.optString("exportVideoCodec")
            } ?: ExportVideoCodec.H264,
            exportVideoBitrateBps = root.optInt("exportVideoBitrateBps", 8_000_000)
                .takeIf { it in 1_000_000..50_000_000 } ?: 8_000_000,
            exportAudioBitrateBps = root.optInt("exportAudioBitrateBps", 128_000)
                .takeIf { it in 64_000..320_000 } ?: 128_000,
            canvas = CanvasRatio.entries.firstOrNull { it.name == root.optString("canvas") } ?: CanvasRatio.SOURCE,
            shortSidePixels = root.optInt("shortSide", 1080).takeIf {
                it in listOf(720, 1080, 2160)
            } ?: 1080,
            muteOriginalAudio = root.optBoolean("mute", false),
            layers = if (schema >= 2) {
                LayerTimelineCodec.decode(root.getJSONObject("layerTimeline"))
            } else LayerTimeline()
        ).normalized()
    }

    fun save(project: EditorDraft) {
        require(project.clips.size <= 500) { "Project exceeds clip limit" }
        val root = JSONObject().apply {
            put("schema", 6)
            put("title", project.title.take(120))
            put("selected", project.selectedIndex)
            put("look", project.look.name)
            put("colorGrade", ColorGradeCodec.encode(project.colorGrade))
            put("exportVideoCodec", project.exportVideoCodec.name)
            put("exportVideoBitrateBps", project.exportVideoBitrateBps)
            put("exportAudioBitrateBps", project.exportAudioBitrateBps)
            put("canvas", project.canvas.name)
            put("shortSide", project.shortSidePixels)
            put("mute", project.muteOriginalAudio)
            put("layerTimeline", LayerTimelineCodec.encode(project.layers))
            put("clips", JSONArray().apply {
                project.clips.forEach { clip ->
                    put(JSONObject().apply {
                        put("uri", clip.sourceUri)
                        put("label", clip.label)
                        put("durationMs", clip.durationMs)
                        put("inMs", clip.inMs)
                        put("outMs", clip.outMs)
                        put("sourceAudioMuted", clip.sourceAudioMuted)
                    })
                }
            })
        }
        val output = file.startWrite()
        try {
            output.write(root.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (exception: Exception) {
            file.failWrite(output)
            throw exception
        }
    }

    fun delete() { file.delete() }
}

