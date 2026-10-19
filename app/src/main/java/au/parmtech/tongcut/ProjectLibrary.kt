package au.parmtech.tongcut

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Lightweight project metadata; media and editable draft are stored separately. */
data class ProjectCard(
    val id: String,
    val title: String,
    val clipCount: Int,
    val updatedMs: Long
)

data class ProjectCatalog(
    val projects: List<ProjectCard> = emptyList(),
    val activeId: String? = null
) {
    init {
        require(projects.map { it.id }.distinct().size == projects.size) {
            "Duplicate project IDs"
        }
        require(activeId == null || projects.any { it.id == activeId }) {
            "Active project is missing"
        }
    }

    fun upsert(card: ProjectCard): ProjectCatalog {
        val changed = projects.filterNot { it.id == card.id } + card
        return copy(projects = changed.sortedWith(
            compareByDescending<ProjectCard> { it.updatedMs }.thenBy { it.id }
        ))
    }

    fun select(id: String): ProjectCatalog {
        require(projects.any { it.id == id }) { "Missing project" }
        return copy(activeId = id)
    }

    fun remove(id: String): ProjectCatalog {
        val kept = projects.filterNot { it.id == id }
        return ProjectCatalog(kept, if (activeId == id) kept.firstOrNull()?.id else activeId)
    }
}

/** All operations are serialised and atomic. Older v0.1 single-project drafts are migrated,
 * but their original file is kept as a recovery backup.
 */
class ProjectLibrary(private val context: Context) {
    private val root = File(context.filesDir, "tongcut-projects")
    private val indexFile by lazy {
        if (!root.isDirectory && !root.mkdirs())
            throw IllegalStateException("Project directory cannot be created")
        AtomicFile(File(root, "index.json"))
    }

    @Synchronized
    fun catalog(): ProjectCatalog {
        val index = indexFile
        if (!index.baseFile.exists() && !File(index.baseFile.path + ".bak").exists()) {
            val legacy = ProjectStore(context).load() ?: return ProjectCatalog()
            val migratedId = UUID.randomUUID().toString()
            ProjectStore(context, migratedId).save(legacy)
            val now = System.currentTimeMillis()
            val result = ProjectCatalog(
                listOf(ProjectCard(migratedId, legacy.title, legacy.clips.size, now)),
                migratedId
            )
            saveIndex(result)
            return result
        }
        return ProjectCatalogCodec.decode(
            index.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
        )
    }

    @Synchronized
    fun read(id: String): EditorDraft {
        require(catalog().projects.any { it.id == id }) { "Unknown project" }
        return ProjectStore(context, id).load()
            ?: throw IllegalStateException("Project file missing: $id")
    }

    @Synchronized
    fun create(title: String = "New project"): Pair<ProjectCatalog, EditorDraft> {
        val normalizedTitle = title.trim().take(120).ifBlank { "New project" }
        val id = UUID.randomUUID().toString()
        val project = EditorDraft(title = normalizedTitle)
        ProjectStore(context, id).save(project)
        val after = catalog().upsert(
            ProjectCard(id, normalizedTitle, 0, System.currentTimeMillis())
        ).select(id)
        saveIndex(after)
        return after to project
    }

    @Synchronized
    fun save(id: String, draft: EditorDraft): ProjectCatalog {
        val before = catalog()
        require(before.projects.any { it.id == id }) { "Unknown project" }
        val snapshot = draft.normalized()
        ProjectStore(context, id).save(snapshot)
        val updated = before.upsert(
            ProjectCard(id, snapshot.title, snapshot.clips.size, System.currentTimeMillis())
        )
        saveIndex(updated)
        return updated
    }

    @Synchronized
    fun activate(id: String): Pair<ProjectCatalog, EditorDraft> {
        val project = read(id)
        val result = catalog().select(id)
        saveIndex(result)
        return result to project
    }

    @Synchronized
    fun duplicate(id: String): Pair<ProjectCatalog, EditorDraft> {
        val before = read(id)
        val newTitle = ("${before.title} (copy)").take(120)
        val copy = before.copy(title = newTitle)
        val newId = UUID.randomUUID().toString()
        ProjectStore(context, newId).save(copy)
        val result = catalog().upsert(
            ProjectCard(newId, newTitle, copy.clips.size, System.currentTimeMillis())
        ).select(newId)
        saveIndex(result)
        return result to copy
    }

    @Synchronized
    fun rename(id: String, title: String): ProjectCatalog {
        val clean = title.trim().take(120)
        require(clean.isNotEmpty()) { "Project name cannot be empty" }
        val updated = read(id).copy(title = clean)
        return save(id, updated)
    }

    @Synchronized
    fun delete(id: String): ProjectCatalog {
        val current = catalog()
        require(current.projects.any { it.id == id }) { "Unknown project" }
        val result = current.remove(id)
        // Remove index reference before deleting data to avoid links to missing files.
        saveIndex(result)
        ProjectStore(context, id).delete()
        return result
    }

    private fun saveIndex(catalog: ProjectCatalog) {
        val file = indexFile
        val stream = file.startWrite()
        try {
            stream.write(ProjectCatalogCodec.encode(catalog).toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (exception: Exception) {
            file.failWrite(stream)
            throw exception
        }
    }
}

object ProjectCatalogCodec {
    fun encode(catalog: ProjectCatalog): String {
        return JSONObject().apply {
            put("schema", 1)
            put("active", catalog.activeId ?: JSONObject.NULL)
            put("projects", JSONArray().apply {
                catalog.projects.forEach { project ->
                    put(JSONObject().apply {
                        put("id", project.id)
                        put("title", project.title)
                        put("clips", project.clipCount)
                        put("updated", project.updatedMs)
                    })
                }
            })
        }.toString()
    }

    fun decode(raw: String): ProjectCatalog {
        val root = JSONObject(raw)
        require(root.optInt("schema", -1) == 1) { "Unsupported catalog schema" }
        val items = root.getJSONArray("projects")
        require(items.length() <= 500) { "Too many projects" }
        val projects = (0 until items.length()).map { position ->
            val objectValue = items.getJSONObject(position)
            val id = objectValue.getString("id")
            require(Regex("[a-zA-Z0-9_-]{1,80}").matches(id)) { "Invalid project ID" }
            val clips = objectValue.getInt("clips")
            require(clips in 0..500) { "Invalid clip count" }
            ProjectCard(
                id = id,
                title = objectValue.getString("title").take(120),
                clipCount = clips,
                updatedMs = objectValue.getLong("updated")
            )
        }
        val id = if (root.isNull("active")) null else root.getString("active")
        return ProjectCatalog(projects, id)
    }
}
