package com.naivety.zhicolor.ui.components.background

import androidx.compose.runtime.staticCompositionLocalOf

/** Main pages share one artwork renderer underneath the pager and navigation backdrop. */
internal val LocalArtworkBackgroundActive = staticCompositionLocalOf { false }
