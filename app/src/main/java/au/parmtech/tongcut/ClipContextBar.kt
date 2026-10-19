package au.parmtech.tongcut

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardDoubleArrowLeft
import androidx.compose.material.icons.filled.KeyboardDoubleArrowRight
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Independently designed TongCut context actions, shown only for a selected real clip. */
internal enum class ClipAction(val title: String) {
    BACK("Back"),
    SPLIT("Split"),
    TRIM_START("Trim in"),
    TRIM_END("Trim out"),
    DUPLICATE("Duplicate"),
    KEYFRAME("Keyframe"),
    TOOLS("Tools"),
    MUTE("Mute"),
    DELETE("Delete")
}

internal object ClipActionPolicy {
    fun forKind(kind: LayerKind): List<ClipAction> {
        val core = listOf(
            ClipAction.BACK, ClipAction.SPLIT,
            ClipAction.TRIM_START, ClipAction.TRIM_END, ClipAction.DUPLICATE
        )
        return when (kind) {
            LayerKind.VIDEO, LayerKind.IMAGE, LayerKind.TEXT ->
                core + listOf(ClipAction.KEYFRAME, ClipAction.TOOLS, ClipAction.DELETE)
            LayerKind.AUDIO ->
                core + listOf(ClipAction.MUTE, ClipAction.TOOLS, ClipAction.DELETE)
            LayerKind.STICKER -> listOf(ClipAction.BACK) // unsupported renderer remains gated
        }
    }
}

private fun clipActionIcon(action: ClipAction): ImageVector = when (action) {
    ClipAction.BACK -> Icons.Default.ArrowBack
    ClipAction.SPLIT -> Icons.Default.ContentCut
    ClipAction.TRIM_START -> Icons.Default.KeyboardDoubleArrowLeft
    ClipAction.TRIM_END -> Icons.Default.KeyboardDoubleArrowRight
    ClipAction.DUPLICATE -> Icons.Default.ContentCopy
    ClipAction.KEYFRAME -> Icons.Default.AutoFixHigh
    ClipAction.TOOLS -> Icons.Default.Tune
    ClipAction.MUTE -> Icons.Default.VolumeOff
    ClipAction.DELETE -> Icons.Default.DeleteOutline
}

@Composable
internal fun ClipContextBar(
    layer: TimelineLayer,
    muted: Boolean,
    onAction: (ClipAction) -> Unit
) {
    val type = when (layer.kind) {
        LayerKind.VIDEO -> "Video layer"
        LayerKind.AUDIO -> "Audio layer"
        LayerKind.IMAGE -> "Photo layer"
        LayerKind.TEXT -> "Text layer"
        LayerKind.STICKER -> "Sticker layer"
    }
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "TONGCUT  /  $type",
                color = Color(0xFF83E5E3),
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp
            )
            Spacer(Modifier.weight(1f))
            Text(
                "${(layer.durationUs / 1_000_000f)}s",
                color = Color(0xFFB4BDC1),
                fontSize = 11.sp
            )
        }
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 9.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            items(ClipActionPolicy.forKind(layer.kind)) { action ->
                val label = if (action == ClipAction.MUTE && muted) "Unmute" else action.title
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (action == ClipAction.DELETE) Color(0xFF3C2227)
                        else Color(0xFF262B30),
                    border = BorderStroke(1.dp, Color(0xFF3E5056)),
                    modifier = Modifier.width(75.dp).clickable { onAction(action) }
                ) {
                    Column(
                        modifier = Modifier.padding(vertical = 9.dp, horizontal = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            imageVector = if (action == ClipAction.MUTE && muted)
                                Icons.Default.VolumeUp else clipActionIcon(action),
                            contentDescription = label,
                            tint = if (action == ClipAction.DELETE) Color(0xFFFF939A)
                                else Color(0xFFE5F7F6)
                        )
                        Text(
                            label, fontSize = 10.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}
