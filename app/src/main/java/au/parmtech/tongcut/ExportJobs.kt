package au.parmtech.tongcut

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.AtomicFile
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal enum class ExportPhase {
    IDLE, QUEUED, RUNNING, INTERRUPTED, FAILED, CANCELLED, COMPLETED
}

internal data class ExportJobState(
    val id: String? = null,
    val phase: ExportPhase = ExportPhase.IDLE,
    val progress: Int = 0,
    val title: String = "",
    val outputUri: String? = null,
    val message: String? = null,
    val dismissed: Boolean = false,
    val updatedAtMs: Long = 0L
) {
    val isActive: Boolean get() = phase == ExportPhase.QUEUED || phase == ExportPhase.RUNNING
    val canRetry: Boolean get() = phase == ExportPhase.INTERRUPTED ||
        phase == ExportPhase.FAILED || phase == ExportPhase.CANCELLED
}

/** App-wide owner for the foreground export service and its recoverable snapshot. */
internal class ExportJobs private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val store = ExportJobStore(appContext)
    private val mutableState = MutableStateFlow(ExportJobState())
    val state: StateFlow<ExportJobState> = mutableState
    private var restored = false
    private var lastProgressWriteAtMs = 0L
    private var lastPersistedProgress = 0

    @Synchronized
    fun restore() {
        if (restored || mutableState.value.id != null) return
        restored = true
        val saved = try {
            store.readState()
        } catch (error: Exception) {
            mutableState.value = ExportJobState(
                phase = ExportPhase.FAILED,
                title = "Export recovery",
                message = "Saved export information could not be read: ${error.message ?: "invalid file"}"
            )
            return
        } ?: return

        val publishedOutput = saved.id?.let {
            runCatching { ExportOutputCleanup.findPublishedOutput(appContext, it) }.getOrNull()
        }
        val recovered = when {
            publishedOutput != null -> saved.copy(
                phase = ExportPhase.COMPLETED,
                progress = 100,
                outputUri = publishedOutput.toString(),
                message = null
            )
            saved.phase == ExportPhase.RUNNING || saved.phase == ExportPhase.QUEUED -> saved.copy(
                phase = ExportPhase.INTERRUPTED,
                message = "Export stopped before it finished. Start it again from the saved edit."
            )
            else -> saved
        }
        mutableState.value = recovered
        runCatching { store.writeState(recovered) }
        runCatching {
            store.retainSnapshot(recovered.id.takeIf { recovered.canRetry || recovered.isActive })
        }
        if (saved.phase == ExportPhase.RUNNING || saved.phase == ExportPhase.QUEUED) {
            saved.id?.let { runCatching { ExportOutputCleanup.removeIncomplete(appContext, it) } }
        }
    }

    @Synchronized
    fun enqueue(snapshot: EditorDraft) {
        check(!mutableState.value.isActive) { "An export is already running" }
        val id = UUID.randomUUID().toString()
        val job = ExportJobState(
            id = id,
            phase = ExportPhase.QUEUED,
            title = snapshot.title.take(120),
            updatedAtMs = System.currentTimeMillis()
        )
        store.writeSnapshot(id, snapshot.normalized())
        publish(job)
        runCatching { store.retainSnapshot(id) }
        launchService(id)
    }

    @Synchronized
    fun retry() {
        val previous = mutableState.value
        check(previous.canRetry && previous.id != null) { "No interrupted export to restart" }
        val snapshot = checkNotNull(store.readSnapshot(previous.id)) {
            "The saved edit for this export is missing"
        }
        ExportOutputCleanup.removeIncomplete(appContext, previous.id)
        val id = UUID.randomUUID().toString()
        store.writeSnapshot(id, snapshot)
        val job = previous.copy(
            id = id,
            phase = ExportPhase.QUEUED,
            progress = 0,
            outputUri = null,
            message = null,
            dismissed = false,
            updatedAtMs = System.currentTimeMillis()
        )
        publish(job)
        runCatching { store.retainSnapshot(id) }
        launchService(id)
    }

    @Synchronized
    fun cancel() {
        val current = mutableState.value
        val id = current.id ?: return
        when (current.phase) {
            ExportPhase.QUEUED -> cancelled(id)
            ExportPhase.RUNNING -> ContextCompat.startForegroundService(
                appContext, ExportService.cancelIntent(appContext, id)
            )
            else -> Unit
        }
    }

    @Synchronized
    fun dismiss() {
        val current = mutableState.value
        if (current.id != null && !current.dismissed) {
            publish(current.copy(dismissed = true, updatedAtMs = System.currentTimeMillis()))
        }
    }

    internal fun snapshotForService(id: String): EditorDraft? = store.readSnapshot(id)

    @Synchronized
    internal fun markRunning(id: String) {
        if (mutableState.value.id != id || mutableState.value.phase != ExportPhase.QUEUED) return
        lastProgressWriteAtMs = System.currentTimeMillis()
        lastPersistedProgress = 0
        publish(mutableState.value.copy(
            phase = ExportPhase.RUNNING,
            progress = 0,
            message = null,
            updatedAtMs = System.currentTimeMillis()
        ))
    }

    @Synchronized
    internal fun updateProgress(id: String, percent: Int) {
        val current = mutableState.value
        if (current.id != id || current.phase != ExportPhase.RUNNING) return
        val value = percent.coerceIn(0, 99)
        if (value == current.progress) return
        val updated = current.copy(progress = value, updatedAtMs = System.currentTimeMillis())
        mutableState.value = updated
        if (value - lastPersistedProgress >= 5 ||
            updated.updatedAtMs - lastProgressWriteAtMs >= 10_000L) {
            store.writeState(updated)
            lastProgressWriteAtMs = updated.updatedAtMs
            lastPersistedProgress = value
        }
    }

    @Synchronized
    internal fun updateWarning(id: String, warning: String) {
        val current = mutableState.value
        if (current.id != id || current.phase != ExportPhase.RUNNING) return
        mutableState.value = current.copy(message = warning.take(400))
    }

    @Synchronized
    internal fun complete(id: String, uri: Uri) {
        val current = mutableState.value
        if (current.id != id || current.phase !in setOf(ExportPhase.RUNNING, ExportPhase.FAILED)) return
        publish(current.copy(
            phase = ExportPhase.COMPLETED,
            progress = 100,
            outputUri = uri.toString(),
            message = current.message,
            updatedAtMs = System.currentTimeMillis()
        ))
        runCatching { store.deleteSnapshot(id) }
    }

    @Synchronized
    internal fun fail(id: String, error: String) {
        val current = mutableState.value
        if (current.id != id || current.phase !in setOf(ExportPhase.QUEUED, ExportPhase.RUNNING)) return
        publish(current.copy(
            phase = ExportPhase.FAILED,
            message = error.take(700),
            updatedAtMs = System.currentTimeMillis()
        ))
    }

    @Synchronized
    internal fun cancelled(id: String) {
        val current = mutableState.value
        if (current.id != id || current.phase !in setOf(
                ExportPhase.QUEUED, ExportPhase.RUNNING, ExportPhase.INTERRUPTED
            )) return
        publish(current.copy(
            phase = ExportPhase.CANCELLED,
            message = "Export cancelled. Your saved edit is ready to export again.",
            updatedAtMs = System.currentTimeMillis()
        ))
        runCatching { ExportOutputCleanup.removeIncomplete(appContext, id) }
    }

    @Synchronized
    internal fun interrupted(id: String, cleanupIncomplete: Boolean = true) {
        val current = mutableState.value
        if (current.id != id || current.phase != ExportPhase.RUNNING) return
        publish(current.copy(
            phase = ExportPhase.INTERRUPTED,
            message = "Export stopped before it finished. Start it again from the saved edit.",
            updatedAtMs = System.currentTimeMillis()
        ))
        if (cleanupIncomplete) {
            runCatching { ExportOutputCleanup.removeIncomplete(appContext, id) }
        }
    }

    private fun publish(next: ExportJobState) {
        store.writeState(next)
        mutableState.value = next
    }

    private fun launchService(id: String) {
        try {
            ContextCompat.startForegroundService(
                appContext,
                Intent(appContext, ExportService::class.java)
                    .setAction(ExportService.ACTION_START)
                    .putExtra(ExportService.EXTRA_JOB_ID, id)
            )
        } catch (error: Exception) {
            fail(id, "Could not start background export: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    companion object {
        @Volatile private var instance: ExportJobs? = null

        fun get(context: Context): ExportJobs = instance ?: synchronized(this) {
            instance ?: ExportJobs(context).also { instance = it }
        }
    }
}

private class ExportJobStore(context: Context) {
    private val appContext = context.applicationContext
    private val directory = File(context.filesDir, "tongcut-export")
    private val stateFile: AtomicFile

    init {
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IllegalStateException("Cannot create export job storage")
        }
        stateFile = AtomicFile(File(directory, "job.json"))
    }

    @Synchronized
    fun writeSnapshot(id: String, snapshot: EditorDraft) = snapshotStore(id).save(snapshot)

    @Synchronized
    fun readSnapshot(id: String): EditorDraft? = snapshotStore(id).load()

    @Synchronized
    fun deleteSnapshot(id: String) = snapshotStore(id).delete()

    @Synchronized
    fun retainSnapshot(id: String?) {
        directory.listFiles { file -> file.isFile &&
            Regex("snapshot-([a-fA-F0-9-]{36})\\.json").matches(file.name)
        }?.forEach { file ->
            val match = Regex("snapshot-([a-fA-F0-9-]{36})\\.json").matchEntire(file.name)
            if (match?.groupValues?.get(1) != id) {
                val snapshotId = match?.groupValues?.get(1)
                if (snapshotId != null) runCatching { snapshotStore(snapshotId).delete() }
            }
        }
    }

    private fun snapshotStore(id: String) = ProjectStore.exportSnapshot(appContext, id)

    @Synchronized
    fun writeState(state: ExportJobState) {
        val bytes = JSONObject().apply {
            put("schema", 1)
            put("id", state.id ?: JSONObject.NULL)
            put("phase", state.phase.name)
            put("progress", state.progress.coerceIn(0, 100))
            put("title", state.title.take(120))
            put("outputUri", state.outputUri ?: JSONObject.NULL)
            put("message", state.message ?: JSONObject.NULL)
            put("dismissed", state.dismissed)
            put("updatedAt", state.updatedAtMs.coerceAtLeast(0L))
        }.toString().toByteArray(Charsets.UTF_8)
        val output = stateFile.startWrite()
        try {
            output.write(bytes)
            stateFile.finishWrite(output)
        } catch (error: Exception) {
            stateFile.failWrite(output)
            throw error
        }
    }

    @Synchronized
    fun readState(): ExportJobState? {
        if (!stateFile.baseFile.exists() && !File(stateFile.baseFile.path + ".bak").exists()) {
            return null
        }
        val root = stateFile.openRead().bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
        require(root.optInt("schema", -1) == 1) { "Unsupported export job schema" }
        val id = if (root.isNull("id")) null else root.getString("id")
        require(id == null || Regex("[a-fA-F0-9-]{36}").matches(id)) { "Invalid export job ID" }
        val phase = ExportPhase.entries.firstOrNull { it.name == root.optString("phase") }
            ?: throw IllegalArgumentException("Invalid export phase")
        val progress = root.optInt("progress", 0).coerceIn(0, 100)
        val uri = if (root.isNull("outputUri")) null else root.getString("outputUri")
        return ExportJobState(
            id = id,
            phase = phase,
            progress = progress,
            title = root.optString("title", "Export").take(120),
            outputUri = uri,
            message = if (root.isNull("message")) null else root.getString("message").take(700),
            dismissed = root.optBoolean("dismissed", false),
            updatedAtMs = root.optLong("updatedAt", 0L).coerceAtLeast(0L)
        )
    }
}

/** Removes only this app's private scratch files and its own unpublished TongCut gallery rows. */
private object ExportOutputCleanup {
    fun findPublishedOutput(context: Context, exportId: String): Uri? {
        if (!Regex("[a-fA-F0-9-]{36}").matches(exportId)) return null
        val resolver = context.contentResolver
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val selection = "${MediaStore.MediaColumns.IS_PENDING} = 0 AND " +
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND " +
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
        resolver.query(
            collection,
            arrayOf(MediaStore.Video.Media._ID),
            selection,
            arrayOf("${Environment.DIRECTORY_MOVIES}/TongCut/", "TongCut_$exportId.mp4"),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return Uri.withAppendedPath(collection,
                    cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)).toString())
            }
        }
        return null
    }

    fun removeIncomplete(context: Context, exportId: String) {
        if (!Regex("[a-fA-F0-9-]{36}").matches(exportId)) return
        val scratchDirectory = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: context.cacheDir
        scratchDirectory.listFiles { file ->
            file.isFile && file.name == "TongCut-$exportId.mp4"
        }?.forEach { it.delete() }

        val resolver = context.contentResolver
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val selection = "${MediaStore.MediaColumns.IS_PENDING} = 1 AND " +
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND " +
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
        resolver.query(
            collection,
            arrayOf(MediaStore.Video.Media._ID),
            selection,
            arrayOf("${Environment.DIRECTORY_MOVIES}/TongCut/", "TongCut_$exportId.mp4"),
            null
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            while (cursor.moveToNext()) {
                val uri = Uri.withAppendedPath(collection, cursor.getLong(idColumn).toString())
                resolver.delete(uri, null, null)
            }
        }
    }
}
