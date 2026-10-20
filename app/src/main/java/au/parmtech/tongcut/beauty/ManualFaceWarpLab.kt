package au.parmtech.tongcut.beauty

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

private enum class FaceAnchor(val label: String) {
    LEFT_EYE("Left eye"),
    RIGHT_EYE("Right eye"),
    LEFT_CHEEK("Left cheek"),
    RIGHT_CHEEK("Right cheek"),
    NOSE("Nose"),
    MOUTH("Mouth"),
    CHIN("Chin")
}

/**
 * Isolated manual PhotoLab prototype. The caller supplies a still Bitmap; this composable
 * does not pick media, detect faces, persist edits, or connect to video editor/export.
 */
@Composable
fun ManualFaceWarpLab(
    sourceImage: Bitmap,
    modifier: Modifier = Modifier
) {
    var landmarks by remember(sourceImage) {
        mutableStateOf(
            ManualFaceLandmarks(
                leftEye = FacePoint(0.40f, 0.38f),
                rightEye = FacePoint(0.60f, 0.38f),
                leftCheek = FacePoint(0.30f, 0.50f),
                rightCheek = FacePoint(0.70f, 0.50f),
                noseTip = FacePoint(0.50f, 0.54f),
                mouthCenter = FacePoint(0.50f, 0.64f),
                chin = FacePoint(0.50f, 0.82f)
            )
        )
    }
    var settings by remember(sourceImage) { mutableStateOf(ManualBeautySettings()) }
    var activeAnchor by remember(sourceImage) { mutableStateOf(FaceAnchor.LEFT_EYE) }
    var showEdited by remember(sourceImage) { mutableStateOf(true) }

    val renderedPair by produceState<Pair<Bitmap, Bitmap>?>(null, sourceImage, landmarks, settings) {
        value = withContext(Dispatchers.Default) {
            val bounded = boundedBitmap(sourceImage)
            val pixels = IntArray(bounded.width * bounded.height)
            bounded.getPixels(pixels, 0, bounded.width, 0, 0, bounded.width, bounded.height)
            val rendered = ManualFaceWarpRenderer.render(
                bounded.width, bounded.height, pixels, landmarks, settings
            )
            val output = Bitmap.createBitmap(bounded.width, bounded.height, Bitmap.Config.ARGB_8888)
            output.setPixels(rendered, 0, bounded.width, 0, 0, bounded.width, bounded.height)
            bounded to output
        }
    }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Manual Face PhotoLab", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(
            "Still image only. Tap or drag the selected anchor onto the face, then tune the local controls.",
            style = MaterialTheme.typography.bodyMedium
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(checked = showEdited, onCheckedChange = { showEdited = it })
            Text(if (showEdited) "Edited preview" else "Original image")
        }

        Box(
            Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 420.dp),
            contentAlignment = Alignment.Center
        ) {
            val pair = renderedPair
            val shown = pair?.let { if (showEdited) it.second else it.first }
            if (shown == null) {
                Text("Preparing bounded still-image preview…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Image(
                    bitmap = shown.asImageBitmap(),
                    contentDescription = if (showEdited) "Manual still-image beauty preview" else "Original still image",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }
            Canvas(
                Modifier.fillMaxSize()
                    .pointerInput(activeAnchor, landmarks, sourceImage.width, sourceImage.height) {
                        detectTapGestures { tap ->
                            normalizedPoint(tap.x, tap.y, size, sourceImage.width, sourceImage.height)?.let {
                                landmarks = updateAnchor(landmarks, activeAnchor, it)
                            }
                        }
                    }
                    .pointerInput(activeAnchor, landmarks, sourceImage.width, sourceImage.height) {
                        detectDragGestures(
                            onDragStart = { point ->
                                normalizedPoint(point.x, point.y, size, sourceImage.width, sourceImage.height)?.let {
                                    landmarks = updateAnchor(landmarks, activeAnchor, it)
                                }
                            }
                        ) { change, _ ->
                            normalizedPoint(change.position.x, change.position.y, size, sourceImage.width, sourceImage.height)?.let {
                                landmarks = updateAnchor(landmarks, activeAnchor, it)
                            }
                            change.consume()
                        }
                    }
            ) {
                val rect = imageRect(size, sourceImage.width, sourceImage.height)
                val anchorPoints = landmarks.entries()
                for ((anchor, point) in anchorPoints) {
                    val center = Offset(
                        rect.left + point.x * rect.width,
                        rect.top + point.y * rect.height
                    )
                    val color = if (anchor == activeAnchor) Color(0xFF20D6C7) else Color(0xFFFFC857)
                    drawCircle(color, radius = if (anchor == activeAnchor) 9.dp.toPx() else 6.dp.toPx(), center = center)
                    drawCircle(color.copy(alpha = 0.8f), radius = 14.dp.toPx(), center = center, style = Stroke(width = 1.5.dp.toPx()))
                }
            }
        }

        Text("Move anchor", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FaceAnchor.entries.forEach { anchor ->
                FilterChip(
                    selected = activeAnchor == anchor,
                    onClick = { activeAnchor = anchor },
                    label = { Text(anchor.label) }
                )
            }
        }
        Text("Selected: ${activeAnchor.label}. Landmark edits are temporary and reset when the image changes.")

        BeautySlider("Face width", settings.faceWidth, -0.2f..0.2f) {
            settings = settings.copy(faceWidth = it)
        }
        BeautySlider("Jaw width", settings.jawWidth, -0.2f..0.2f) {
            settings = settings.copy(jawWidth = it)
        }
        BeautySlider("Nose width", settings.noseWidth, -0.15f..0.15f) {
            settings = settings.copy(noseWidth = it)
        }
        BeautySlider("Eye scale", settings.eyeScale, 0f..0.25f) {
            settings = settings.copy(eyeScale = it)
        }
        BeautySlider("Softening", settings.skinSoftening, 0f..0.65f) {
            settings = settings.copy(skinSoftening = it)
        }
        Text(
            "Offline CPU still-image preview only; input is downscaled to at most ${ManualFaceWarpRenderer.MAX_PIXELS} pixels. " +
                "Preview may take time to refresh. No video or export support is implied.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun BeautySlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onValueChange: (Float) -> Unit) {
    Column {
        Text("$label · ${(value * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
        Slider(value = value, onValueChange = onValueChange, valueRange = range)
    }
}

private data class ImageRect(val left: Float, val top: Float, val width: Float, val height: Float)

private fun imageRect(size: IntSize, imageWidth: Int, imageHeight: Int): ImageRect {
    val scale = minOf(size.width.toFloat() / imageWidth, size.height.toFloat() / imageHeight)
    val width = imageWidth * scale
    val height = imageHeight * scale
    return ImageRect((size.width - width) / 2f, (size.height - height) / 2f, width, height)
}

private fun normalizedPoint(x: Float, y: Float, size: IntSize, imageWidth: Int, imageHeight: Int): FacePoint? {
    val rect = imageRect(size, imageWidth, imageHeight)
    if (rect.width <= 0f || rect.height <= 0f || x !in rect.left..(rect.left + rect.width) ||
        y !in rect.top..(rect.top + rect.height)
    ) return null
    return FacePoint(
        ((x - rect.left) / rect.width).coerceIn(0f, 1f),
        ((y - rect.top) / rect.height).coerceIn(0f, 1f)
    )
}

private fun updateAnchor(current: ManualFaceLandmarks, anchor: FaceAnchor, point: FacePoint): ManualFaceLandmarks =
    runCatching {
        when (anchor) {
            FaceAnchor.LEFT_EYE -> current.copy(leftEye = point)
            FaceAnchor.RIGHT_EYE -> current.copy(rightEye = point)
            FaceAnchor.LEFT_CHEEK -> current.copy(leftCheek = point)
            FaceAnchor.RIGHT_CHEEK -> current.copy(rightCheek = point)
            FaceAnchor.NOSE -> current.copy(noseTip = point)
            FaceAnchor.MOUTH -> current.copy(mouthCenter = point)
            FaceAnchor.CHIN -> current.copy(chin = point)
        }
    }.getOrDefault(current)

private fun ManualFaceLandmarks.entries(): List<Pair<FaceAnchor, FacePoint>> = listOf(
    FaceAnchor.LEFT_EYE to leftEye,
    FaceAnchor.RIGHT_EYE to rightEye,
    FaceAnchor.LEFT_CHEEK to leftCheek,
    FaceAnchor.RIGHT_CHEEK to rightCheek,
    FaceAnchor.NOSE to noseTip,
    FaceAnchor.MOUTH to mouthCenter,
    FaceAnchor.CHIN to chin
)

private fun boundedBitmap(source: Bitmap): Bitmap {
    require(source.width >= 2 && source.height >= 2) { "Still image must be at least 2 by 2 pixels" }
    if (source.width.toLong() * source.height <= ManualFaceWarpRenderer.MAX_PIXELS) return source
    val scale = sqrt(ManualFaceWarpRenderer.MAX_PIXELS.toDouble() / (source.width.toDouble() * source.height))
    var width = maxOf(2, (source.width * scale).toInt())
    var height = maxOf(2, (source.height * scale).toInt())
    if (width.toLong() * height > ManualFaceWarpRenderer.MAX_PIXELS) {
        if (width >= height) width = ManualFaceWarpRenderer.MAX_PIXELS / height
        else height = ManualFaceWarpRenderer.MAX_PIXELS / width
    }
    return Bitmap.createScaledBitmap(source, width, height, true)
}
