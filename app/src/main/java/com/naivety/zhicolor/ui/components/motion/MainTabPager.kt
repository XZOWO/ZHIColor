package com.naivety.zhicolor.ui.components.motion

import androidx.compose.runtime.staticCompositionLocalOf

/** Retained pager pages keep their state without handling the visible tab's back gesture. */
val LocalMainTabActive = staticCompositionLocalOf { true }
