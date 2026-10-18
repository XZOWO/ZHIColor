package com.naivety.zhicolor.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun rememberHeaderBlurState(): HazeState = rememberHazeState()

@Composable
fun rememberHeaderBlurStyle(): HazeStyle {
    val surface = MiuixTheme.colorScheme.surface
    val tint = HazeTint(surface.copy(alpha = if (surface.luminance() < 0.5f) 0.72f else 0.82f))
    return remember(surface) {
        HazeStyle(backgroundColor = surface, tint = tint, blurRadius = 24.dp, noiseFactor = 0f)
    }
}

/** Pair a scrolling content source with the header drawn above that content. */
@OptIn(ExperimentalHazeApi::class)
fun Modifier.headerBlur(state: HazeState, style: HazeStyle): Modifier = hazeEffect(state = state, style = style) {
    inputScale = HazeInputScale.Fixed(0.35f)
    forceInvalidateOnPreDraw = false
}

@OptIn(ExperimentalHazeApi::class)
fun Modifier.headerBlurSource(state: HazeState): Modifier = hazeSource(state = state)
