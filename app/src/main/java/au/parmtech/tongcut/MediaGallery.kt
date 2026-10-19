package au.parmtech.tongcut

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class GalleryEntry(
    val uri: Uri,
    val name: String,
    val durationMs: Long,
    val video: Boolean
)

/** Local gallery with Android's runtime media permissions. SAF Files is always offered. */
@Composable
fun GalleryScreen(
    initialPhotos: Boolean,
    onBack: () -> Unit,
    onAdd: (List<Uri>) -> Unit,
    onFiles: () -> Unit,
    onInfo: (String) -> Unit
) {
    val context = LocalContext.current
    var photos by remember { mutableStateOf(initialPhotos) }
    var generation by remember { mutableIntStateOf(0) }
    var granted by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    val media = remember { mutableStateListOf<GalleryEntry>() }
    val selected = remember { mutableStateListOf<Uri>() }

    val permissions = remember {
        if (Build.VERSION.SDK_INT >= 34) arrayOf(
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        )
        else if (Build.VERSION.SDK_INT >= 33) arrayOf(
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_IMAGES
        )
        else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        generation++
    }
    LaunchedEffect(Unit) {
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
        else generation++
    }
    LaunchedEffect(photos, generation) {
        val videoAccess = if (Build.VERSION.SDK_INT >= 33)
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_MEDIA_VIDEO
            ) == PackageManager.PERMISSION_GRANTED
        else ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED

        val photoAccess = if (Build.VERSION.SDK_INT >= 33)
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_MEDIA_IMAGES
            ) == PackageManager.PERMISSION_GRANTED
        else videoAccess

        val partialAccess = Build.VERSION.SDK_INT >= 34 &&
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            ) == PackageManager.PERMISSION_GRANTED

        granted = (if (photos) photoAccess else videoAccess) || partialAccess
        loading = true
        media.clear()
        if (granted) {
            val found = withContext(Dispatchers.IO) { findLocalMedia(context, photos) }
            media.addAll(found)
        }
        loading = false
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFF090909))
            .statusBarsPadding().navigationBarsPadding()
    ) {
        Row(Modifier.fillMaxWidth().height(67.dp).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.Close, "Close media picker", tint = Color.White)
            }
            TextButton(onClick = {
                onInfo("Albums: using your phone's permitted photo and video gallery.")
            }) { Text("Albums ▾", color = Color.White, fontSize = 18.sp) }
            Spacer(Modifier.width(10.dp))
            Text("Generated", color = Color.Gray, modifier = Modifier.clickable {
                onInfo("Generated media is planned. AI outputs will appear here after provider setup.")
            })
            Spacer(Modifier.weight(1f))
            Text("Files", color = Color.White, modifier = Modifier.clickable(onClick = onFiles))
        }
        HorizontalDivider(color = Color(0xFF242424))
        Row(Modifier.fillMaxWidth().height(52.dp)) {
            listOf(false to "Videos", true to "Photos").forEach { (mode, name) ->
                Column(
                    Modifier.weight(1f).fillMaxHeight().clickable {
                        photos = mode
                        selected.clear()
                    },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom
                ) {
                    Text(name,
                        color = if (photos == mode) Color(0xFF78E9F2) else Color.White,
                        fontSize = 16.sp,
                        fontWeight = if (photos == mode) FontWeight.SemiBold else FontWeight.Normal
                    )
                    Spacer(Modifier.height(13.dp))
                    Box(Modifier.fillMaxWidth().height(3.dp)
                        .background(if (photos == mode) ProCyan else Color.Transparent))
                }
            }
        }
        Box(Modifier.weight(1f)) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = ProCyan
                )
            } else if (!granted) {
                Column(
                    Modifier.align(Alignment.Center).padding(25.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Allow local media access or choose files", color = Color.White)
                    Spacer(Modifier.height(13.dp))
                    Button(onClick = { permissionLauncher.launch(permissions) },
                        colors = ButtonDefaults.buttonColors(containerColor = ProCyan)) {
                        Text("Allow gallery", color = Color.Black)
                    }
                    OutlinedButton(onClick = onFiles) { Text("Choose video files") }
                }
            } else if (media.isEmpty()) {
                Text("No ${if (photos) "photos" else "videos"} found in permitted media.",
                    modifier = Modifier.align(Alignment.Center),
                    color = Color.White)
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(media, key = { it.uri.toString() }) { entry ->
                        Box(
                            modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                                .clickable {
                                    if (entry.uri in selected) selected.remove(entry.uri)
                                    else selected.add(entry.uri)
                                }
                        ) {
                            GalleryThumbnail(entry.uri, Modifier.fillMaxSize())
                            val order = selected.indexOf(entry.uri)
                            Surface(
                                modifier = Modifier.align(Alignment.TopEnd).padding(10.dp)
                                    .size(24.dp),
                                shape = CircleShape,
                                border = BorderStroke(2.dp, Color.White),
                                color = if (order >= 0) ProCyan else Color(0x4D000000)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    if (order >= 0) Text("${order + 1}", color = Color.Black,
                                        fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                            }
                            if (entry.video) {
                                Text(entry.durationMs.asTimestamp(), color = Color.White,
                                    fontSize = 13.sp,
                                    modifier = Modifier.align(Alignment.BottomEnd)
                                        .padding(6.dp).background(Color(0x88000000),
                                            RoundedCornerShape(4.dp))
                                        .padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
        HorizontalDivider(color = Color(0xFF242424))
        Row(
            modifier = Modifier.fillMaxWidth().height(72.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("${selected.size} selected", color = Color.LightGray, fontSize = 14.sp)
            Spacer(Modifier.weight(1f))
            Button(
                enabled = selected.isNotEmpty(),
                onClick = {
                    if (photos) onInfo("Photo editing and photo-to-video imports are not implemented yet.")
                    else onAdd(selected.toList())
                },
                colors = ButtonDefaults.buttonColors(containerColor = ProCyan),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Add", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().height(58.dp)
                .background(Color(0xFF161616)),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable(onClick = onFiles)) {
                Icon(Icons.Default.PermMedia, null, tint = Color.White)
                Spacer(Modifier.width(6.dp))
                Text("Add media", color = Color.White)
            }
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable {
                    onInfo("Script-to-video will become available after AI provider integrations.")
                }) {
                Icon(Icons.Default.Article, null, tint = Color.Gray)
                Spacer(Modifier.width(6.dp))
                Text("Script to video", color = Color.Gray)
            }
        }
    }
}

private fun findLocalMedia(context: Context, photos: Boolean): List<GalleryEntry> {
    val collection = if (photos) MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    val fields = if (photos) arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.DISPLAY_NAME
    ) else arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.Video.Media.DURATION
    )
    return runCatching {
        context.contentResolver.query(
            collection,
            fields,
            null,
            null,
            "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val durationIndex = if (photos) -1
                else cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            buildList {
                while (cursor.moveToNext() && size < 750) {
                    val id = cursor.getLong(idIndex)
                    add(GalleryEntry(
                        ContentUris.withAppendedId(collection, id),
                        cursor.getString(nameIndex) ?: "Media",
                        if (durationIndex >= 0) cursor.getLong(durationIndex) else 0,
                        !photos
                    ))
                }
            }
        } ?: emptyList()
    }.getOrElse { emptyList() }
}

/** Thumbnail is derived from user's local media URI, never from copied CapCut art. */
@Composable
internal fun GalleryThumbnail(uri: Uri, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(uri) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.loadThumbnail(uri, Size(350, 350), null)
                    .asImageBitmap()
            }.getOrNull()
        }
    }
    Box(modifier.background(Color(0xFF34383A)), contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image != null) {
            Image(bitmap = image, contentDescription = "Video or photo thumbnail",
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(Icons.Default.Movie, contentDescription = null, tint = Color.Gray)
        }
    }
}
