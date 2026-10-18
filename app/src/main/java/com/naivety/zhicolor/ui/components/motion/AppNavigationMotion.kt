package com.naivety.zhicolor.ui.components.motion

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun AppNavigationEntrance(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(260, easing = LinearOutSlowInEasing)) +
            slideInVertically(tween(380, easing = LinearOutSlowInEasing)) { height -> height / 3 },
        exit = fadeOut(tween(180, easing = FastOutLinearInEasing)) +
            slideOutVertically(tween(240, easing = FastOutLinearInEasing)) { height -> height / 3 },
    ) {
        content()
    }
}
