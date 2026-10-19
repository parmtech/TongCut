package au.parmtech.tongcut

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class SampleFrame(
    val bitmap: Bitmap? = null,
    val error: String? = null
)

/** Tap on a still from the original underlying VIDEO media to select any RGB key. */
@Composable
internal fun VideoChromaKeyPicker(
    layer: TimelineLayer,
    frozenPlayheadMs: Long,
    onPicked: (Int) -> Unit,
    onCancel: () -> Unit
) {
    require(layer.kind == LayerKind.VIDEO)
    val context = LocalContext.current
    // The picker is frozen at the OPEN time: do not run a decoder every 90ms.
    val sourceUs = remember(layer.id, layer.sourceInUs, layer.speedRamp, frozenPlayheadMs) {
        layer.sourceAt(Math.multiplyExact(frozenPlayheadMs, 1_000L))
    }
    val source = requireNotNull(layer.sourceUri)
    val frame by produceState<SampleFrame>(
        initialValue = SampleFrame(), source, sourceUs
    ) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                SampleFrame(bitmap = ChromaFrameSampler.frameAt(context, source, sourceUs))
            }.getOrElse { SampleFrame(error = it.message ?: "Cannot decode colour frame") }
        }
    }
    // Capture the exact bitmap allocated by this effect: onDispose must not
    // accidentally recycle the NEXT frame during a recomposition.
    val ownedBitmap = frame.bitmap
    DisposableEffect(ownedBitmap) {
        onDispose { ownedBitmap?.let { if (!it.isRecycled) it.recycle() } }
    }

    Column(
        modifier = Modifier.fillMaxWidth()
            .background(Color(0xFF202020), RoundedCornerShape(9.dp))
            .padding(9.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Text(
            "Pick chroma colour: tap the ORIGINAL video frame",
            color = Color.White, fontSize = 12.sp
        )
        Text(
            "Nearest decoded source frame. Tap a clean background area; the chosen colour is saved to this clip.",
            color = Color.LightGray, fontSize = 10.sp
        )
        val image = frame.bitmap
        if (image != null && !image.isRecycled) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = "Raw source video frame. Tap a pixel to sample the chroma key colour.",
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxWidth()
                    .aspectRatio(image.width.toFloat() / image.height)
                    .pointerInput(image) {
                        detectTapGestures { offset ->
                            val (x, y) = ChromaSampleMath.pixelAt(
                                offset.x, offset.y, size.width, size.height,
                                image.width, image.height
                            )
                            onPicked(ChromaSampleMath.opaqueArgb(image.getPixel(x, y)))
                        }
                    }
            )
        } else {
            Text(
                frame.error ?: "Decoding source frame…",
                color = if (frame.error != null) Color(0xFFFFC987) else Color.LightGray,
                fontSize = 11.sp
            )
        }
        TextButton(onClick = onCancel) {
            Text("Cancel colour picker", color = ProCyan, fontSize = 11.sp)
        }
    }
}
