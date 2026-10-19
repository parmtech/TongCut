package au.parmtech.tongcut

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Local non-upload thumbnail frames at actual source timestamps.
 * Bounded, small images; avoids loading full-resolution video frames into memory.
 */
private object FrameCache {
    private val frames = object : LruCache<String, Bitmap>(48) {}

    @Synchronized fun get(key: String): Bitmap? = frames.get(key)
    @Synchronized fun put(key: String, bitmap: Bitmap) { frames.put(key, bitmap) }
}

@Composable
internal fun VideoFrameThumbnail(
    uri: Uri,
    sourceTimestampUs: Long,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val roundedTimestamp = sourceTimestampUs.coerceAtLeast(0L)
    val key = "${uri}#${roundedTimestamp / 50_000L}" // 50ms cache granularity
    var frame by remember(key) { mutableStateOf(FrameCache.get(key)) }

    LaunchedEffect(key) {
        if (frame == null) {
            val decoded = withContext(Dispatchers.IO) {
                FrameCache.get(key) ?: runCatching {
                    MediaMetadataRetriever().use { retriever ->
                        retriever.setDataSource(context, uri)
                        retriever.getScaledFrameAtTime(
                            roundedTimestamp,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                            160, 92
                        ) ?: retriever.getFrameAtTime(
                            roundedTimestamp,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                        )?.let { original ->
                            val scaled = Bitmap.createScaledBitmap(original, 160, 92, true)
                            if (scaled !== original) original.recycle()
                            scaled
                        }
                    }
                }.getOrNull()?.also { FrameCache.put(key, it) }
            }
            frame = decoded
        }
    }

    Box(modifier.background(Color(0xFF262626)), contentAlignment = Alignment.Center) {
        frame?.let { bitmap ->
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Video frame",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
    }
}
