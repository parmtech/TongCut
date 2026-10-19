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

internal val ProCyan = Color(0xFF00C9DE)
internal val ProBlack = Color(0xFF151515)
private val HomeInk = Color(0xFF14161A)

private enum class Screen { HOME, GALLERY, EDITOR }

/** Navigation and project ownership. Editor/Picker are implemented as independently testable screens. */
@Composable
fun TongCutApp(exporter: VideoExporter) {
    val context = LocalContext.current
    val store = remember(context) { ProjectStore(context.applicationContext) }
    val history = remember { EditHistory(EditorDraft()) }
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf(EditorDraft()) }
    var screen by remember { mutableStateOf(Screen.HOME) }
    var loaded by remember { mutableStateOf(false) }
    var saveAllowed by remember { mutableStateOf(false) }
    var banner by remember { mutableStateOf("Loading drafts…") }
    var dialogText by remember { mutableStateOf<String?>(null) }
    var galleryPhotos by remember { mutableStateOf(false) }

    fun commit(next: EditorDraft) {
        if (!loaded || !saveAllowed) return
        val stable = next.normalized()
        if (history.apply(stable)) draft = stable
    }

    LaunchedEffect(Unit) {
        try {
            val recovered = withContext(Dispatchers.IO) { store.load() }
            if (recovered != null) {
                draft = recovered
                history.reset(recovered)
                banner = "Recovered draft · ${recovered.clips.size} clips"
            } else banner = "Your edit stays on this device."
            saveAllowed = true
        } catch (failure: Exception) {
            saveAllowed = false
            banner = "Draft recovery failed. Existing data is preserved."
            dialogText = "Your saved project could not be loaded: ${failure.message}. It has not been overwritten."
        } finally { loaded = true }
    }

    LaunchedEffect(draft, loaded, saveAllowed) {
        if (loaded && saveAllowed) {
            delay(700)
            try { withContext(Dispatchers.IO) { store.save(draft) } }
            catch (e: Exception) { banner = "Autosave failed: ${e.message}" }
        }
    }

    fun importMedia(uris: List<Uri>) {
        if (!loaded || !saveAllowed || uris.isEmpty()) return
        scope.launch {
            val clips = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    runCatching { inspectMediaClip(context, uri) }.getOrNull()
                }
            }
            if (clips.isNotEmpty()) {
                commit(draft.copy(
                    clips = draft.clips + clips,
                    selectedIndex = draft.clips.size
                ))
                screen = Screen.EDITOR
                banner = "Added ${clips.size} clip(s)."
            } else dialogText = "No supported videos found. Please use a playable video file."
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

    when (screen) {
        Screen.HOME -> HomeScreen(
            draft = draft,
            banner = banner,
            canResume = draft.clips.isNotEmpty() && saveAllowed,
            onNew = { galleryPhotos = false; screen = Screen.GALLERY },
            onPhotos = { galleryPhotos = true; screen = Screen.GALLERY },
            onResume = { screen = Screen.EDITOR },
            onFeature = { name ->
                dialogText = "$name is on the TongCut development roadmap. This feature is not enabled until it works on real media."
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
            exporter = exporter,
            onBack = { screen = Screen.HOME },
            onMedia = { galleryPhotos = false; screen = Screen.GALLERY },
            onChange = { updated -> commit(updated) },
            canUndo = history.canUndo,
            canRedo = history.canRedo,
            onUndo = { history.undo()?.let { draft = it } },
            onRedo = { history.redo()?.let { draft = it } },
            onUnavailable = { dialogText = "$it is in development. It will be enabled only when preview and export both work." },
            onInfo = { banner = it }
        )
    }

    dialogText?.let { message ->
        AlertDialog(
            onDismissRequest = { dialogText = null },
            title = { Text("TongCut") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { dialogText = null }) { Text("OK") }
            }
        )
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
    draft: EditorDraft, banner: String, canResume: Boolean,
    onNew: () -> Unit, onPhotos: () -> Unit, onResume: () -> Unit,
    onFeature: (String) -> Unit
) {
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
                    if (canResume) {
                        Text("Recent projects", color = HomeInk, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 16.dp))
                        LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            item {
                                Column(Modifier.width(132.dp).clickable(onClick = onResume)) {
                                    Box(Modifier.size(132.dp).background(Color(0xFFE5F5FA),
                                        RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                                        draft.clips.firstOrNull()?.let {
                                            GalleryThumbnail(it.sourceUri.toUri(), Modifier.fillMaxSize())
                                        } ?: Icon(Icons.Default.Movie, "Project", tint = HomeInk)
                                        Surface(
                                            color = Color(0xD9000000),
                                            shape = RoundedCornerShape(10.dp),
                                            modifier = Modifier.align(Alignment.BottomEnd).padding(7.dp)
                                        ) {
                                            Text("${draft.clips.size} clips", color = Color.White,
                                                style = MaterialTheme.typography.labelSmall,
                                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp))
                                        }
                                    }
                                    Text(draft.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        color = HomeInk, modifier = Modifier.padding(top = 6.dp))
                                }
                            }
                        }
                    } else {
                        Text("Projects you create will appear here.",
                            color = Color.DarkGray, modifier = Modifier.padding(horizontal = 16.dp))
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
