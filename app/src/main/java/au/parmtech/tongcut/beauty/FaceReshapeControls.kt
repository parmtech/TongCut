package au.parmtech.tongcut.beauty

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Reusable manual controls, intentionally independent of the main editor.
 *
 * The integrator must provide consented/manual anchors and persist the current
 * FaceReshapeParams in a real project edit. No button is added to the editor
 * until preview+export use the same reshaping pipeline.
 */
@Composable
fun FaceReshapeControls(
    params: FaceReshapeParams,
    landmarksAvailable: Boolean,
    onParamsChanged: (FaceReshapeParams) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Manual Face Reshape", style = MaterialTheme.typography.titleMedium)
        if (!landmarksAvailable) {
            Text(
                "Set facial landmarks to enable reshaping. Photos stay on this device.",
                style = MaterialTheme.typography.bodySmall
            )
        }
        FaceReshapeSlider(
            "Slim cheeks",
            params.cheekSlim,
            0f..1f,
            landmarksAvailable,
            { onParamsChanged(params.copy(cheekSlim = it)) }
        )
        FaceReshapeSlider(
            "Slim jaw",
            params.jawSlim,
            0f..1f,
            landmarksAvailable,
            { onParamsChanged(params.copy(jawSlim = it)) }
        )
        FaceReshapeSlider(
            "Enlarge eyes",
            params.eyeEnlarge,
            0f..1f,
            landmarksAvailable,
            { onParamsChanged(params.copy(eyeEnlarge = it)) }
        )
        FaceReshapeSlider(
            "Slim nose",
            params.noseSlim,
            0f..1f,
            landmarksAvailable,
            { onParamsChanged(params.copy(noseSlim = it)) }
        )
        FaceReshapeSlider(
            "Chin length",
            params.chinLength,
            -1f..1f,
            landmarksAvailable,
            { onParamsChanged(params.copy(chinLength = it)) }
        )
    }
}

@Composable
private fun FaceReshapeSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    onChanged: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("$label: ${(value * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium)
        Slider(
            value = value,
            onValueChange = onChanged,
            enabled = enabled,
            valueRange = range,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
