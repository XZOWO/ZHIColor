package com.naivety.zhicolor.bridge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.naivety.zhicolor.ui.screen.LyricsScreen
import com.naivety.zhicolor.ui.theme.AppTheme
import com.naivety.zhicolor.ui.components.GlassOverlayHost
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier

/** Debug-only host for the actual settings composable on an isolated emulator. */
class LyricsSettingsPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BridgeSettings.initialize(this)
        setContent { AppTheme {
            val backdrop=rememberLayerBackdrop()
            GlassOverlayHost(backdrop=backdrop) {
                Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) { LyricsScreen() }
            }
        } }
    }
}
