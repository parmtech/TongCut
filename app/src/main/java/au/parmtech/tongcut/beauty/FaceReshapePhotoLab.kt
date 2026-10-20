package au.parmtech.tongcut.beauty

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A standalone, consent-controlled still-photo test surface. It is not
 * connected to TongCut's editor, rendering pipeline or export until the owner
 * accepts phone QA and A20 coordinates the shared-file changes.
 *
 * The caller must supply a software Bitmap already downsampled for preview
 * (at most 1024 x 1024 pixels) and the corresponding original-orientation
 * face anchors. The callback receives settings/anchors only, NOT a fake
 * "exported video" or saved project.
 */
@Composable
internal fun FaceReshapePhotoLab(
    previewPhoto: Bitmap,
    initialLandmarks: FaceReshapeLandmarks,
    initialParams: FaceReshapeParams,
    onCommit: (FaceReshapeLandmarks, FaceReshapeParams) -> Unit,
    modifier: Modifier = Modifier
) {
    var anchors by remember(previewPhoto, initialLandmarks) {
        mutableStateOf(initialLandmarks)
    }
    var params by remember(previewPhoto, initialParams) {
        mutableStateOf(initialParams)
    }
    var showAnchorEditor by remember(previewPhoto) { mutableStateOf(true) }

    // Keep transformations off the UI thread. The actual CPU renderer has a
    // higher pixel limit, but frequent real-time slider previews must not run
    // with full-resolution source images.
    val preview by produceState<PhotoLabPreview>(
        initialValue = PhotoLabPreview(null, null),
        previewPhoto,
        anchors,
        params
    ) {
        value = PhotoLabPreview(null, null)
        try {
            require(previewPhoto.width > 1 && previewPhoto.height > 1 &&
                previewPhoto.width.toLong() * previewPhoto.height <= 1_048_576L) {
                "Please use a downsampled preview photo (up to one megapixel)"
            }
            val result = withContext(Dispatchers.Default) {
                FaceReshapeBitmap.render(previewPhoto, anchors, params)
            }
            value = PhotoLabPreview(result, null)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: RuntimeException) {
            value = PhotoLabPreview(
                null, error.message ?: "Photo reshape preview failed"
            )
        }
    }

    Column(
        modifier = modifier.fillMaxWidth().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Face Reshape — offline photo preview",
            style = MaterialTheme.typography.titleMedium)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Adjust face points", style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = showAnchorEditor,
                onCheckedChange = { showAnchorEditor = it }
            )
        }
        if (showAnchorEditor) {
            FaceReshapeLandmarkPicker(
                preview = previewPhoto.asImageBitmap(),
                landmarks = anchors,
                onLandmarksChanged = { anchors = it }
            )
        } else {
            val bitmap = preview.image
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Photo after the current manual face reshape",
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxWidth()
                        .aspectRatio(bitmap.width.toFloat() / bitmap.height.toFloat())
                )
            } else {
                Text(preview.error ?: "Rendering preview…")
            }
        }
        FaceReshapeControls(
            params = params,
            landmarksAvailable = true,
            onParamsChanged = { params = it }
        )
        Button(
            onClick = { onCommit(anchors, params) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Use these settings")
        }
        Text(
            "Still-photo prototype only. Editor and video export not connected.",
            style = MaterialTheme.typography.labelSmall
        )
    }
}

private data class PhotoLabPreview(
    val image: Bitmap?,
    val error: String?
)
