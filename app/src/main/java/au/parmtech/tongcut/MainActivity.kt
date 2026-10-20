package au.parmtech.tongcut

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

/** App entry point. Screens live in TongCutApp/MediaGallery/EditorWorkspace. */
class MainActivity : ComponentActivity() {
    private val exportJobs by lazy { ExportJobs.get(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF06C7D8),
                    onPrimary = Color(0xFF031318),
                    background = Color(0xFF121212),
                    surface = Color(0xFF202020),
                    surfaceVariant = Color(0xFF303030),
                    onSurface = Color.White,
                    onSurfaceVariant = Color(0xFFCBCBCB)
                )
            ) {
                TongCutApp(exportJobs)
            }
        }
    }

}
