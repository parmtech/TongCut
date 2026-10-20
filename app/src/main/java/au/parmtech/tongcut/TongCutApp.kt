package au.parmtech.tongcut

import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal val ProCyan = TongCutPalette.AuroraAqua
internal val ProBlack = Color(0xFF151515)
private val HomeInk = Color(0xFF14161A)

private enum class Screen { HOME, GALLERY, EDITOR }

/** Navigation and project ownership. Editor/Picker are implemented as independently testable screens. */
@Composable
internal fun TongCutApp(exportJobs: ExportJobs) {
    val context = LocalContext.current
    val exportState by exportJobs.state.collectAsState()
    val library = remember(context) { ProjectLibrary(context.applicationContext) }
    val history = remember { EditHistory(EditorDraft()) }
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf(EditorDraft()) }
    var catalog by remember { mutableStateOf(ProjectCatalog()) }
    var activeId by remember { mutableStateOf<String?>(null) }
    var screen by remember { mutableStateOf(Screen.HOME) }
    var loaded by remember { mutableStateOf(false) }
    var saveAllowed by remember { mutableStateOf(false) }
    var operationBusy by remember { mutableStateOf(false) }
    var banner by remember { mutableStateOf("Opening projects…") }
    var dialogText by remember { mutableStateOf<String?>(null) }
    var galleryPhotos by remember { mutableStateOf(false) }

    fun commit(next: EditorDraft) {
        if (!loaded || !saveAllowed || operationBusy) return
        val stable = next.normalized()
        if (history.apply(stable)) draft = stable
    }

    LaunchedEffect(Unit) {
        try {
            val current = withContext(Dispatchers.IO) {
                val index = library.catalog()
                index to index.activeId?.let { library.read(it) }
            }
            catalog = current.first
            activeId = current.first.activeId
            val recovered = current.second
            if (recovered != null) {
                draft = recovered
                history.reset(recovered)
                banner = "Recovered ${current.first.projects.size} saved project(s)."
            } else banner = "Projects are saved privately on this device."
            saveAllowed = true
        } catch (failure: Exception) {
            saveAllowed = false
            banner = "Project recovery failed: original files remain untouched."
            dialogText = "Cannot safely read projects: ${failure.message}. Files have not been overwritten."
        } finally { loaded = true }
    }

    LaunchedEffect(exportJobs) {
        withContext(Dispatchers.IO) { exportJobs.restore() }
    }

    // Capture the project ID together with the snapshot: do not save a switched draft
    // under a previous project's identifier.
    LaunchedEffect(draft, activeId, loaded, saveAllowed) {
        val projectId = activeId
        val snapshot = draft
        if (loaded && saveAllowed && projectId != null) {
            delay(750)
            try {
                val updated = withContext(Dispatchers.IO) { library.save(projectId, snapshot) }
                if (activeId == projectId) catalog = updated
            } catch (error: Exception) {
                banner = "Autosave failed: ${error.message}"
            }
        }
    }

    fun createProject(photos: Boolean) {
        if (!loaded || !saveAllowed || operationBusy) return
        scope.launch {
            operationBusy = true
            try {
                val previousId = activeId
                val previous = draft
                val result = withContext(Dispatchers.IO) {
                    if (previousId != null) library.save(previousId, previous)
                    library.create("New project")
                }
                catalog = result.first
                activeId = result.first.activeId
                draft = result.second
                history.reset(result.second)
                galleryPhotos = photos
                screen = Screen.GALLERY
                banner = "New project created"
            } catch (error: Exception) {
                dialogText = "Could not create project: ${error.message}"
            } finally { operationBusy = false }
        }
    }

    fun openProject(id: String) {
        if (!loaded || !saveAllowed || operationBusy) return
        scope.launch {
            operationBusy = true
            try {
                val oldId = activeId
                val oldDraft = draft
                val result = withContext(Dispatchers.IO) {
                    if (oldId != null) library.save(oldId, oldDraft)
                    library.activate(id)
                }
                catalog = result.first
                activeId = id
                draft = result.second
                history.reset(result.second)
                screen = Screen.EDITOR
                banner = "Opened ${result.second.title}"
            } catch (error: Exception) {
                dialogText = "Could not open project: ${error.message}"
            } finally { operationBusy = false }
        }
    }

    fun duplicateProject(id: String) {
        if (!loaded || !saveAllowed || operationBusy) return
        scope.launch {
            operationBusy = true
            try {
                val oldId = activeId
                val oldDraft = draft
                val result = withContext(Dispatchers.IO) {
                    if (oldId != null) library.save(oldId, oldDraft)
                    library.duplicate(id)
                }
                catalog = result.first
                activeId = result.first.activeId
                draft = result.second
                history.reset(result.second)
                screen = Screen.EDITOR
            } catch (error: Exception) {
                dialogText = "Could not duplicate project: ${error.message}"
            } finally { operationBusy = false }
        }
    }

    fun renameProject(id: String, title: String) {
        if (!loaded || !saveAllowed || operationBusy) return
        scope.launch {
            operationBusy = true
            try {
                val latestSnapshot = if (id == activeId) draft else null
                val changed = withContext(Dispatchers.IO) {
                    // Rename must not load an older snapshot over unsaved in-memory edits.
                    if (latestSnapshot != null) library.save(id, latestSnapshot)
                    library.rename(id, title)
                }
                catalog = changed
                if (id == activeId) {
                    val renamed = draft.copy(title = title.trim().take(120))
                    if (history.apply(renamed)) draft = renamed
                }
            } catch (error: Exception) {
                dialogText = "Could not rename project: ${error.message}"
            } finally { operationBusy = false }
        }
    }

    fun deleteProject(id: String) {
        if (!loaded || !saveAllowed || operationBusy) return
        scope.launch {
            operationBusy = true
            try {
                val previousId = activeId
                val previousDraft = draft
                val result = withContext(Dispatchers.IO) {
                    if (previousId != null && previousId != id) {
                        library.save(previousId, previousDraft)
                    }
                    val nextCatalog = library.delete(id)
                    nextCatalog to nextCatalog.activeId?.let { library.read(it) }
                }
                catalog = result.first
                activeId = result.first.activeId
                draft = result.second ?: EditorDraft()
                history.reset(draft)
                banner = "Project deleted"
            } catch (error: Exception) {
                dialogText = "Could not delete project: ${error.message}"
            } finally { operationBusy = false }
        }
    }

    fun importMedia(uris: List<Uri>) {
        if (!loaded || !saveAllowed || operationBusy || uris.isEmpty()) return
        scope.launch {
            operationBusy = true
            try {
                val clips = withContext(Dispatchers.IO) {
                    uris.mapNotNull { uri ->
                        runCatching { inspectMediaClip(context, uri) }.getOrNull()
                    }
                }
                if (clips.isNotEmpty()) {
                    // The Home screen creates a project before importing.
                    val id = activeId
                    if (id == null) {
                        dialogText = "Create a project before adding media."
                    } else {
                        val updated = draft.copy(
                            clips = draft.clips + clips,
                            selectedIndex = draft.clips.size
                        )
                        if (history.apply(updated)) draft = updated
                        val index = withContext(Dispatchers.IO) { library.save(id, updated) }
                        catalog = index
                        screen = Screen.EDITOR
                        banner = "Added ${clips.size} clip(s)."
                    }
                } else dialogText = "No supported videos found. Choose a playable media file."
            } catch (error: Exception) {
                dialogText = "Unable to import media: ${error.message}"
            } finally { operationBusy = false }
        }
    }

    val documentPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        uris.forEach { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
        importMedia(uris)
    }

    Column(Modifier.fillMaxSize()) {
        if (exportState.id != null && exportState.phase != ExportPhase.IDLE &&
            (!exportState.dismissed || exportState.isActive)) {
            ExportJobBanner(
                state = exportState,
                onCancel = exportJobs::cancel,
                onRetry = { runCatching { exportJobs.retry() }
                    .onFailure { banner = "Could not restart export: ${it.message}" } },
                onOpen = {
                    exportState.outputUri?.let { output ->
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(Uri.parse(output), "video/mp4")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            })
                        }.onFailure { banner = "Export saved to Movies/TongCut." }
                    }
                },
                onDismiss = exportJobs::dismiss
            )
        }
        Box(Modifier.weight(1f)) {
            when (screen) {
                Screen.HOME -> HomeScreen(
                    draft = draft,
                    projects = catalog.projects,
                    activeId = activeId,
                    banner = banner,
                    canResume = saveAllowed && !operationBusy,
                    onNew = { createProject(false) },
                    onPhotos = { createProject(true) },
                    onResume = { activeId?.let(::openProject) },
                    onOpen = ::openProject,
                    onDuplicate = ::duplicateProject,
                    onRename = ::renameProject,
                    onDelete = ::deleteProject,
                    onFeature = { name ->
                        dialogText = "$name is planned but not functional yet."
                    }
                )
                Screen.GALLERY -> GalleryScreen(
                    initialPhotos = galleryPhotos,
                    onBack = { screen = Screen.HOME },
                    onAdd = { importMedia(it) },
                    onFiles = { documentPicker.launch(arrayOf("video/*")) },
                    onInfo = { dialogText = it }
                )
                Screen.EDITOR -> VideoEditorScreen(
                    draft = draft,
                    exportJobs = exportJobs,
                    onBack = { screen = Screen.HOME },
                    onMedia = { galleryPhotos = false; screen = Screen.GALLERY },
                    onChange = { updated -> commit(updated) },
                    canUndo = history.canUndo,
                    canRedo = history.canRedo,
                    onUndo = { history.undo()?.let { draft = it } },
                    onRedo = { history.redo()?.let { draft = it } },
                    onUnavailable = { dialogText = "$it is in development. It will only be enabled once preview and export both work." },
                    onInfo = { banner = it }
                )
            }
        }
    }

    dialogText?.let { message ->
        AlertDialog(
            onDismissRequest = { dialogText = null },
            title = { Text("TongCut") },
            text = { Text(message) },
            confirmButton = {
                Row {
                    if (loaded && !saveAllowed) {
                        TextButton(onClick = {
                            scope.launch {
                                try {
                                    val recovered = withContext(Dispatchers.IO) {
                                        val index = library.recoverFromFiles()
                                        index to index.activeId?.let { library.read(it) }
                                    }
                                    catalog = recovered.first
                                    activeId = recovered.first.activeId
                                    draft = recovered.second ?: EditorDraft()
                                    history.reset(draft)
                                    saveAllowed = true
                                    banner = "Recovered ${catalog.projects.size} project(s)"
                                    dialogText = null
                                } catch (error: Exception) {
                                    dialogText = "Recovery failed: ${error.message}. Files remain untouched."
                                }
                            }
                        }) { Text("Recover projects") }
                    }
                    TextButton(onClick = { dialogText = null }) { Text("Close") }
                }
            }
        )
    }
}

@Composable
private fun ExportJobBanner(
    state: ExportJobState,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onOpen: () -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFF202A30),
        contentColor = Color.White,
        shadowElevation = 3.dp
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                val title = when (state.phase) {
                    ExportPhase.QUEUED -> "Preparing ${state.title.ifBlank { "video" }} export"
                    ExportPhase.RUNNING -> "Exporting ${state.title.ifBlank { "video" }} · ${state.progress}%"
                    ExportPhase.INTERRUPTED -> "Export paused"
                    ExportPhase.FAILED -> "Export needs attention"
                    ExportPhase.CANCELLED -> "Export cancelled"
                    ExportPhase.COMPLETED -> "Export saved to Movies/TongCut"
                    ExportPhase.IDLE -> ""
                }
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                when (state.phase) {
                    ExportPhase.RUNNING, ExportPhase.QUEUED -> LinearProgressIndicator(
                        progress = { state.progress.coerceIn(0, 100) / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    ExportPhase.INTERRUPTED, ExportPhase.FAILED, ExportPhase.CANCELLED ->
                        Text(state.message ?: "Restart this export from the saved edit.",
                            fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    else -> Unit
                }
            }
            when {
                state.isActive -> TextButton(onClick = onCancel) { Text("Cancel") }
                state.canRetry -> TextButton(onClick = onRetry) { Text("Retry") }
                state.phase == ExportPhase.COMPLETED -> TextButton(onClick = onOpen) { Text("Open") }
            }
            if (!state.isActive) {
                TextButton(onClick = onDismiss) { Text("Hide") }
            }
        }
    }
}

private fun inspectMediaClip(context: android.content.Context, uri: Uri): DraftClip? {
    val label = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME),
        null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    } ?: "Video"
    val duration = MediaMetadataRetriever().use { retriever ->
        retriever.setDataSource(context, uri)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
    } ?: return null
    if (duration < 100) return null
    return DraftClip(uri.toString(), label, duration)
}

@Composable
private fun HomeScreen(
    draft: EditorDraft,
    projects: List<ProjectCard>,
    activeId: String?,
    banner: String,
    canResume: Boolean,
    onNew: () -> Unit,
    onPhotos: () -> Unit,
    onResume: () -> Unit,
    onOpen: (String) -> Unit,
    onDuplicate: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onFeature: (String) -> Unit
) {
    var projectToRename by remember { mutableStateOf<ProjectCard?>(null) }
    var projectToDelete by remember { mutableStateOf<ProjectCard?>(null) }
    var nameText by remember { mutableStateOf("") }

    val actions = listOf(
        Triple("AutoCut", Icons.Default.Movie, false),
        Triple("AI clipper", Icons.Default.ContentCut, false),
        Triple("Generate media", Icons.Default.AutoAwesome, false),
        Triple("Marketing tools", Icons.Default.Work, false),
        Triple("Retouch", Icons.Default.Face, false),
        Triple("AI generator", Icons.Default.Stars, false),
        Triple("Photo tools", Icons.Default.Image, false),
        Triple("Product photos", Icons.Default.CameraAlt, false),
        Triple("Auto enhance", Icons.Default.AutoFixHigh, false)
    )
    Column(Modifier.fillMaxSize().background(Color.White).statusBarsPadding().navigationBarsPadding()) {
        Box(Modifier.weight(1f)) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Box(Modifier.fillMaxWidth().height(350.dp).background(
                    Brush.verticalGradient(listOf(Color(0xFF05BDD7), Color(0xFF15C8E8), Color.White))
                )) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(color = Color(0x39001D2C), shape = RoundedCornerShape(22.dp)) {
                                Text("✦  TongCut Studio", modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                    color = Color.White, style = MaterialTheme.typography.labelLarge)
                            }
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = { onFeature("Search") }) {
                                Icon(Icons.Default.Search, "Search", tint = Color.White)
                            }
                        }
                        Spacer(Modifier.height(37.dp))
                        Text("Create beyond limits", color = HomeInk,
                            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                        Text("Edit smarter in AI Lab  ›", color = HomeInk,
                            style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(18.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            HomeAction("New video", Icons.Default.AddBox, Modifier.weight(1.3f), onNew)
                            HomeAction("Edit photo", Icons.Default.Image, Modifier.weight(0.9f), onPhotos)
                        }
                    }
                }
                Column(
                    Modifier.fillMaxWidth().background(Color.White).padding(bottom = 22.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    Text("Recent projects", color = HomeInk, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 16.dp))
                    if (projects.isEmpty()) {
                        Text("Projects you create will appear here.",
                            color = Color.DarkGray, modifier = Modifier.padding(horizontal = 16.dp))
                    } else {
                        LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(projects, key = { it.id }) { project ->
                                var menuOpen by remember(project.id) { mutableStateOf(false) }
                                Column(Modifier.width(138.dp)) {
                                    Box(
                                        Modifier.fillMaxWidth().height(132.dp)
                                            .background(Color(0xFFE5F5FA), RoundedCornerShape(15.dp)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (project.id == activeId && draft.clips.isNotEmpty()) {
                                            GalleryThumbnail(draft.clips.first().sourceUri.toUri(),
                                                Modifier.fillMaxSize().clickable { if (canResume) onOpen(project.id) })
                                        } else {
                                            Icon(Icons.Default.Movie, "Open project", tint = HomeInk,
                                                modifier = Modifier.size(34.dp).clickable { if (canResume) onOpen(project.id) })
                                        }
                                        Surface(
                                            color = Color(0xD9000000),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.align(Alignment.BottomStart).padding(5.dp)
                                        ) {
                                            Text("${project.clipCount} clips", color = Color.White,
                                                style = MaterialTheme.typography.labelSmall,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp))
                                        }
                                        Box(Modifier.align(Alignment.TopEnd)) {
                                            IconButton(onClick = { menuOpen = true }) {
                                                Icon(Icons.Default.MoreVert, "Project options", tint = HomeInk)
                                            }
                                            DropdownMenu(
                                                expanded = menuOpen,
                                                onDismissRequest = { menuOpen = false }
                                            ) {
                                                DropdownMenuItem(
                                                    text = { Text("Open") },
                                                    onClick = { menuOpen = false; onOpen(project.id) }
                                                )
                                                DropdownMenuItem(
                                                    text = { Text("Rename") },
                                                    onClick = {
                                                        menuOpen = false
                                                        projectToRename = project
                                                        nameText = project.title
                                                    }
                                                )
                                                DropdownMenuItem(
                                                    text = { Text("Duplicate") },
                                                    onClick = { menuOpen = false; onDuplicate(project.id) }
                                                )
                                                DropdownMenuItem(
                                                    text = { Text("Delete") },
                                                    onClick = {
                                                        menuOpen = false
                                                        projectToDelete = project
                                                    }
                                                )
                                            }
                                        }
                                    }
                                    Text(project.title, maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = HomeInk,
                                        modifier = Modifier.fillMaxWidth()
                                            .clickable { if (canResume) onOpen(project.id) }
                                            .padding(top = 6.dp))
                                }
                            }
                        }
                    }

                    actions.chunked(3).forEach { chunk ->
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 10.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            chunk.forEach { (title, icon, enabled) ->
                                Column(
                                    modifier = Modifier.weight(1f).height(105.dp)
                                        .clickable { onFeature(title) },
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Icon(icon, title, tint = HomeInk, modifier = Modifier.size(30.dp))
                                    Spacer(Modifier.height(12.dp))
                                    Text(title, color = HomeInk, fontSize = 13.sp, maxLines = 2)
                                    if (!enabled) Text("Planned", color = Color.Gray, fontSize = 10.sp)
                                }
                            }
                        }
                    }
                    Text(banner, color = Color(0xFF53616A), fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
        }
        NavigationBar(containerColor = Color.White, tonalElevation = 0.dp, modifier = Modifier.height(72.dp)) {
            val options = listOf(
                Triple("Edit", Icons.Default.ContentCut, true),
                Triple("Templates", Icons.Default.ViewCarousel, false),
                Triple("AI Lab", Icons.Default.AutoAwesome, false),
                Triple("Library", Icons.Default.Folder, false),
                Triple("Me", Icons.Default.Person, false)
            )
            options.forEach { (name, icon, selected) ->
                NavigationBarItem(
                    selected = selected,
                    onClick = { if (name != "Edit") onFeature(name) },
                    icon = { Icon(icon, name) },
                    label = { Text(name, fontSize = 10.sp) },
                    alwaysShowLabel = true,
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = HomeInk,
                        selectedTextColor = HomeInk,
                        unselectedIconColor = Color.Gray,
                        unselectedTextColor = Color.Gray,
                        indicatorColor = Color.Transparent
                    )
                )
            }
        }
    }
    projectToRename?.let { project ->
        AlertDialog(
            onDismissRequest = { projectToRename = null },
            title = { Text("Rename project") },
            text = {
                OutlinedTextField(
                    value = nameText,
                    onValueChange = { nameText = it.take(120) },
                    singleLine = true,
                    label = { Text("Project name") }
                )
            },
            confirmButton = {
                TextButton(
                    enabled = nameText.isNotBlank(),
                    onClick = {
                        onRename(project.id, nameText.trim())
                        projectToRename = null
                    }
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { projectToRename = null }) { Text("Cancel") }
            }
        )
    }
    projectToDelete?.let { project ->
        AlertDialog(
            onDismissRequest = { projectToDelete = null },
            title = { Text("Delete project?") },
            text = { Text("Delete '${project.title}'? This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(project.id)
                    projectToDelete = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { projectToDelete = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun HomeAction(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(134.dp),
        shape = RoundedCornerShape(20.dp),
        color = Color(0xF5FFFFFF),
        shadowElevation = 1.dp
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(icon, title, tint = HomeInk, modifier = Modifier.size(32.dp))
            Spacer(Modifier.height(9.dp))
            Text(title, color = HomeInk, style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold)
        }
    }
}
