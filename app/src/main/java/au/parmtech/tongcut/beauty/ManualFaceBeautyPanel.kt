package au.parmtech.tongcut.beauty

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import au.parmtech.tongcut.ColorLutProfile
import au.parmtech.tongcut.PhotoBitmapDecoder
import au.parmtech.tongcut.PhotoEffects
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ManualFaceBeautyPanel(sourceUri: String?, colorLutProfile: ColorLutProfile?,
                          value: ManualFaceBeauty, onChange: (ManualFaceBeauty) -> Unit) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, sourceUri, colorLutProfile) {
        this.value = withContext(Dispatchers.IO) {
            sourceUri?.let { uri -> runCatching {
                PhotoBitmapDecoder.decode(context, Uri.parse(uri),
                    PhotoEffects(applyColorLut = colorLutProfile != null), colorLutProfile)
            }.getOrNull() }
        }
    }
    var selected by remember { mutableIntStateOf(0) }
    val labels = listOf("L eye", "R eye", "L cheek", "R cheek", "Nose", "Mouth", "Chin")
    Text("IMAGE only · manual landmarks · no face detection", color = Color.White, fontSize = 11.sp)
    Text(labels[selected] + " selected — tap or drag to position",
        color = Color(0xFF91D9D2), fontSize = 10.sp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        labels.forEachIndexed { index, label ->
            Text(label.take(2), color = if (selected == index) Color.Black else Color.White,
                modifier = Modifier.background(if (selected == index) Color(0xFF91D9D2)
                    else Color(0xFF3A4148), RoundedCornerShape(5.dp)).padding(4.dp)
                    .pointerInput(index) { detectTapGestures { selected = index } }, fontSize = 9.sp)
        }
    }
    Canvas(Modifier.fillMaxWidth().height(170.dp)
        .pointerInput(value, selected) {
            detectTapGestures { pos ->
                onChange(value.moveAnchor(selected, anchor(pos.x / size.width, pos.y / size.height)))
            }
        }.pointerInput(value, selected) {
            detectDragGestures { change, _ ->
                change.consume()
                onChange(value.moveAnchor(selected,
                    anchor(change.position.x / size.width, change.position.y / size.height)))
            }
        }) {
        bitmap?.let { drawImage(it.asImageBitmap(), dstSize = IntSize(size.width.toInt(), size.height.toInt())) }
        value.landmarks.asPoints().forEachIndexed { index, point ->
            drawCircle(if (selected == index) Color(0xFF91D9D2) else Color.White,
                if (selected == index) 8.dp.toPx() else 5.dp.toPx(),
                Offset(point.x * size.width, point.y * size.height))
        }
    }
    Text("Face / jaw / nose / eye / softening strengths", color = Color.LightGray, fontSize = 10.sp)
    BeautyStep("Face", value.settings.faceWidth, 0.02f, -0.2f, 0.2f) {
        onChange(value.copy(settings = value.settings.copy(faceWidth = it)))
    }
    BeautyStep("Jaw", value.settings.jawWidth, 0.02f, -0.2f, 0.2f) {
        onChange(value.copy(settings = value.settings.copy(jawWidth = it)))
    }
    BeautyStep("Nose", value.settings.noseWidth, 0.015f, -0.15f, 0.15f) {
        onChange(value.copy(settings = value.settings.copy(noseWidth = it)))
    }
    BeautyStep("Eyes", value.settings.eyeScale, 0.025f, 0f, 0.25f) {
        onChange(value.copy(settings = value.settings.copy(eyeScale = it)))
    }
    BeautyStep("Softening", value.settings.skinSoftening, 0.05f, 0f, 0.65f) {
        onChange(value.copy(settings = value.settings.copy(skinSoftening = it)))
    }
    Text("CPU still-image preview only · max 1.5 MP · no video/body reshape",
        color = Color(0xFFFFC987), fontSize = 10.sp)
}

@Composable
private fun BeautyStep(label: String, value: Float, step: Float, low: Float, high: Float,
                       onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label + " " + (value * 100).toInt() + "%", color = Color.White, fontSize = 10.sp)
        Row {
            Text("−", color = Color.White, modifier = Modifier.padding(horizontal = 10.dp)
                .pointerInput(value) { detectTapGestures { onChange((value - step).coerceAtLeast(low)) } })
            Text("+", color = Color.White, modifier = Modifier.padding(horizontal = 10.dp)
                .pointerInput(value) { detectTapGestures { onChange((value + step).coerceAtMost(high)) } })
        }
    }
}
private fun anchor(x: Float, y: Float) = FacePoint(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
private fun ManualFaceLandmarks.asPoints() =
    listOf(leftEye, rightEye, leftCheek, rightCheek, noseTip, mouthCenter, chin)
private fun ManualFaceBeauty.moveAnchor(index: Int, point: FacePoint): ManualFaceBeauty {
    val l = landmarks
    val next = when (index) {
        0 -> l.copy(leftEye = point)
        1 -> l.copy(rightEye = point)
        2 -> l.copy(leftCheek = point)
        3 -> l.copy(rightCheek = point)
        4 -> l.copy(noseTip = point)
        5 -> l.copy(mouthCenter = point)
        else -> l.copy(chin = point)
    }
    return copy(landmarks = next)
}
