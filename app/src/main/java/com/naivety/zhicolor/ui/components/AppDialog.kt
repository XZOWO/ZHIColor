package com.naivety.zhicolor.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import com.naivety.zhicolor.R
import com.naivety.zhicolor.ui.components.motion.PredictiveBackMotionPolicy
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun AppDialog(
    show: Boolean,
    title: String? = null,
    summary: String? = null,
    onDismissRequest: () -> Unit,
    onDismissFinished: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val surface = MiuixTheme.colorScheme.surface
    val foreground = MiuixTheme.colorScheme.onSurface
    val secondary = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val dismissLabel = stringResource(R.string.dialog_dismiss)
    GlassOverlay(show, onDismissRequest, onDismissFinished, paneTitle = title) { visibility ->
        BoxWithConstraints(
            Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.ime))
                .padding(16.dp),
        ) {
            val centered = maxWidth >= 600.dp
            val contentHeight = (maxHeight - 26.dp).coerceAtLeast(1.dp)
            DialogContentLayout(
                availableHeight = contentHeight,
                modifier = Modifier.align(if (centered) Alignment.Center else Alignment.BottomCenter)
                    .widthIn(max = 480.dp).fillMaxWidth().heightIn(max = maxHeight)
                    .graphicsLayer {
                        val motion = PredictiveBackMotionPolicy.dialogFrame(visibility, centered, size.height, density)
                        alpha = motion.alpha
                        translationY = motion.translationY
                        scaleX = motion.scale
                        scaleY = motion.scale
                    }
                    .glassOverlaySurface(RoundedCornerShape(28.dp), surface)
                    .pointerInput(Unit) { detectTapGestures { } }
                    .verticalScroll(rememberScrollState())
                    .padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 16.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f).padding(start = 4.dp, top = 8.dp, bottom = 10.dp)) {
                        title?.let {
                            Text(it, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = foreground)
                        }
                        summary?.takeIf(String::isNotBlank)?.let {
                            Text(it, fontSize = 14.sp, color = secondary, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                    Box(
                        Modifier.size(48.dp).clip(CircleShape)
                            .clickable(role = Role.Button, onClickLabel = dismissLabel, onClick = onDismissRequest),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.Close, contentDescription = dismissLabel, tint = secondary, modifier = Modifier.size(20.dp))
                    }
                }
                CompositionLocalProvider(LocalDialogBodyLimit provides contentHeight) { content() }
            }
        }
    }
}

/** Measure the title and actions before assigning the remaining viewport to scrollable bodies. */
@Composable
private fun DialogContentLayout(
    availableHeight: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { children, constraints ->
        val placeables = arrayOfNulls<Placeable>(children.size)
        val bodyIndices = children.indices.filter { children[it].layoutId == DialogBodyLayoutId }
        val unboundedHeight = constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)
        var fixedHeight = 0
        children.forEachIndexed { index, child ->
            if (index !in bodyIndices) {
                val measured = child.measure(unboundedHeight)
                placeables[index] = measured
                fixedHeight += measured.height
            }
        }
        val viewport = availableHeight.roundToPx().coerceAtLeast(1)
        // When chrome alone fills a short window, the outer scroller keeps every action reachable.
        val bodyBudget = (viewport - fixedHeight)
            .coerceAtLeast(48.dp.roundToPx()).coerceAtMost(viewport)
        bodyIndices.forEach { index ->
            placeables[index] = children[index].measure(
                constraints.copy(minHeight = 0, maxHeight = bodyBudget / bodyIndices.size),
            )
        }
        val height = placeables.sumOf { it?.height ?: 0 }
        layout(constraints.maxWidth, height) {
            var top = 0
            placeables.forEach { child ->
                child?.placeRelative(0, top)
                top += child?.height ?: 0
            }
        }
    }
}
