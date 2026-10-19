package au.parmtech.tongcut

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Captures the true source-video frame for a non-destructive freeze interval.
 * Files live in private persistent storage (NOT cache) so project recovery and
 * media relinking do not silently erase a freeze on Android cache eviction.
 *
 * The generated image is an ordinary IMAGE layer in the compositor, making
 * alpha/size/position consistent with regular photo overlays. Frames are saved
 * atomically. The caller owns deletion if the project edit is not committed.
 */
internal object FreezeFrameCapture {
    suspend fun capture(context: Context, source: Uri, sourceTimeUs: Long): Uri =
        withContext(Dispatchers.IO) {
            require(sourceTimeUs >= 0L)
            val framesDir = File(context.filesDir, "tongcut-freeze-frames")
            require(framesDir.isDirectory || framesDir.mkdirs()) {
                "Unable to create freeze image directory"
            }
            val name = UUID.randomUUID().toString()
            val pending = File(framesDir, "$name.part")
            val destination = File(framesDir, "$name.png")
            var bitmap: Bitmap? = null
            try {
                val captured = MediaMetadataRetriever().use { media ->
                    media.setDataSource(context, source)
                    media.getScaledFrameAtTime(
                        sourceTimeUs,
                        MediaMetadataRetriever.OPTION_CLOSEST,
                        1280,
                        1280
                    ) ?: throw IOException("Unable to extract freeze frame")
                }
                bitmap = captured
                pending.outputStream().buffered().use { out ->
                    if (!captured.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                        throw IOException("Could not encode video freeze frame")
                    }
                    out.flush()
                }
                require(pending.length() > 0L) { "Freeze frame is empty" }
                if (!pending.renameTo(destination)) {
                    throw IOException("Could not store freeze frame")
                }
                Uri.fromFile(destination)
            } catch (failure: Exception) {
                pending.delete()
                destination.delete()
                throw failure
            } finally {
                bitmap?.recycle()
            }
        }

    fun discard(context: Context, uri: Uri) {
        val framesDir = File(context.filesDir, "tongcut-freeze-frames")
        val file = uri.path?.let(::File) ?: return
        if (file.parentFile?.canonicalPath == framesDir.canonicalPath &&
            file.name.endsWith(".png")
        ) file.delete()
    }
}
