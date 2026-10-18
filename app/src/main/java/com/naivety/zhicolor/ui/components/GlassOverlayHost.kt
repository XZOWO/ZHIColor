package com.naivety.zhicolor.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.naivety.zhicolor.ui.components.motion.PredictiveBackDismissHandler
import com.naivety.zhicolor.ui.components.motion.PredictiveBackDismissState
import com.naivety.zhicolor.ui.components.motion.PredictiveBackMotionPolicy
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

private class GlassOverlayEntry(val content: @Composable () -> Unit)
private class GlassOverlayState {
    val entries = mutableStateListOf<GlassOverlayEntry>()
}

private val LocalGlassOverlayHost = staticCompositionLocalOf<GlassOverlayState?> { null }
private val LocalGlassOverlayIsTop = staticCompositionLocalOf { true }
/** Page callbacks yield to portals, including their retained closing frames. */
val LocalGlassOverlayActive = staticCompositionLocalOf { false }
val LocalGlassOverlayBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/** The page recorder and overlays share a render root, so refraction uses live pixels. */
@Composable
fun GlassOverlayHost(
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val host = remember { GlassOverlayState() }
    val contentFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val hasOverlay = host.entries.isNotEmpty()
    LaunchedEffect(hasOverlay) {
        if (hasOverlay) {
            contentFocus.saveFocusedChild()
            focusManager.clearFocus(force = true)
        } else {
            contentFocus.restoreFocusedChild()
        }
    }
    CompositionLocalProvider(
        LocalGlassOverlayHost provides host,
        LocalGlassOverlayBackdrop provides backdrop,
        LocalGlassOverlayActive provides hasOverlay,
    ) {
        Box(modifier.fillMaxSize()) {
            Box(
                Modifier.fillMaxSize()
                    .focusRequester(contentFocus)
                    .focusGroup()
                    .semantics { if (hasOverlay) hideFromAccessibility() },
            ) { content() }
            host.entries.forEachIndexed { index, entry ->
                key(entry) {
                    CompositionLocalProvider(LocalGlassOverlayIsTop provides (index == host.entries.lastIndex)) {
                        entry.content()
                    }
                }
            }
        }
    }
}

/** Portal a clipped/scrolled page's content into the root without a second Android window. */
@Composable
fun GlassOverlay(
    show: Boolean,
    onDismissRequest: (() -> Unit)?,
    onDismissFinished: (() -> Unit)? = null,
    enableWindowDim: Boolean = true,
    paneTitle: String? = null,
    content: @Composable BoxScope.(progress: Float) -> Unit,
) {
    val host = checkNotNull(LocalGlassOverlayHost.current) { "GlassOverlayHost must wrap the app content" }
    var retained by remember { mutableStateOf(false) }
    val back = remember { PredictiveBackDismissState() }
    val keyboard = LocalSoftwareKeyboardController.current
    val dismissRequest by rememberUpdatedState(onDismissRequest)
    val dismissFinished by rememberUpdatedState(onDismissFinished)
    val currentContent by rememberUpdatedState(content)
    val currentPaneTitle by rememberUpdatedState(paneTitle)
    val currentDim by rememberUpdatedState(enableWindowDim)
    val currentShow by rememberUpdatedState(show)
    val locals by rememberUpdatedState(currentCompositionLocalContext)

    LaunchedEffect(show) {
        back.invalidateGesture()
        if (show) back.commitGate.reset()
    }
    LaunchedEffect(show, back.gestureInProgress, back.settlementEpoch) {
        if (!back.gestureInProgress) {
            if (show) {
                retained = true
                back.animateOpenTo(1f)
            } else if (retained) {
                keyboard?.hide()
                back.animateOpenTo(0f)
                retained = false
                dismissFinished?.invoke()
            }
        }
    }
    val entry = remember {
        GlassOverlayEntry {
            val isTop = LocalGlassOverlayIsTop.current
            CompositionLocalProvider(locals) {
                val focus = remember { FocusRequester() }
                val dimColor = MiuixTheme.colorScheme.windowDimming
                PredictiveBackDismissHandler(
                    state = back,
                    enabled = isTop,
                    shown = currentShow,
                    dismissible = dismissRequest != null,
                    onDismiss = { dismissRequest?.invoke() },
                )
                val openFraction = back.openFraction.value
                val dimFraction = PredictiveBackMotionPolicy.visualFraction(openFraction)
                LaunchedEffect(isTop) {
                    if (isTop) {
                        if (!focus.restoreFocusedChild()) focus.requestFocus()
                    } else {
                        focus.saveFocusedChild()
                    }
                }
                Box(
                    Modifier.fillMaxSize()
                        .focusRequester(focus)
                        .focusProperties { onExit = { if (isTop) cancelFocusChange() } }
                        .focusGroup()
                        .focusable()
                        .semantics {
                            isTraversalGroup = true
                            currentPaneTitle?.let { this.paneTitle = it }
                            if (!isTop) hideFromAccessibility()
                            if (dismissRequest != null) dismiss { dismissRequest?.invoke(); true }
                        },
                ) {
                    Box(
                        Modifier.fillMaxSize()
                            .background(if (currentDim) dimColor.copy(alpha = dimColor.alpha * dimFraction) else Color.Transparent)
                            .pointerInput(Unit) { detectTapGestures { dismissRequest?.invoke() } },
                    )
                    currentContent(openFraction)
                }
            }
        }
    }
    val active = show || retained
    DisposableEffect(host, active) {
        if (active) host.entries.add(entry)
        onDispose { host.entries.remove(entry) }
    }
}

@Composable
fun Modifier.glassOverlaySurface(
    shape: Shape,
    tint: Color = MiuixTheme.colorScheme.surface,
): Modifier {
    val backdrop = checkNotNull(LocalGlassOverlayBackdrop.current)
    val light = MiuixTheme.colorScheme.surface.luminance() > 0.5f
    return drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            vibrancy()
            blur(18.dp.toPx())
            lens(28.dp.toPx(), 32.dp.toPx())
        },
        highlight = { Highlight.Default.copy(alpha = if (light) 0.9f else 0.6f) },
        shadow = { Shadow.Default.copy(color = Color.Black.copy(alpha = if (light) 0.16f else 0.3f), alpha = 0.8f) },
        innerShadow = { InnerShadow(radius = 12.dp, alpha = if (light) 0.12f else 0.2f) },
        onDrawSurface = {
            drawRect(tint.copy(alpha = if (light) 0.4f else 0.44f))
            drawRect(Color.White.copy(alpha = if (light) 0.045f else 0.025f))
        },
    ).clip(shape)
}

@Composable
fun GlassDropdownMenu(
    show: Boolean,
    anchorBounds: Rect,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    minWidth: Dp = 220.dp,
    content: @Composable () -> Unit,
) {
    GlassOverlay(show, onDismissRequest, enableWindowDim = true) { progress ->
        val density = LocalDensity.current
        val direction = LocalLayoutDirection.current
        val window = LocalWindowInfo.current.containerSize
        val insets = WindowInsets.safeDrawing.asPaddingValues()
        val top = with(density) { insets.calculateTopPadding().roundToPx() + 8.dp.roundToPx() }
        val bottom = with(density) { insets.calculateBottomPadding().roundToPx() + 8.dp.roundToPx() }
        val margin = with(density) { 12.dp.roundToPx() }
        val gap = with(density) { 6.dp.roundToPx() }
        val width = with(density) { minWidth.roundToPx() }.coerceAtMost((window.width - margin * 2).coerceAtLeast(1))
        var measured by remember { mutableStateOf(IntSize.Zero) }
        val x = (if (direction == LayoutDirection.Ltr) anchorBounds.right.roundToInt() - width else anchorBounds.left.roundToInt())
            .coerceIn(margin, (window.width - width - margin).coerceAtLeast(margin))
        val below = anchorBounds.bottom.roundToInt() + gap
        val y = (if (below + measured.height <= window.height - bottom) below else anchorBounds.top.roundToInt() - gap - measured.height)
            .coerceIn(top, (window.height - bottom - measured.height).coerceAtLeast(top))
        Box(
            modifier.offset { IntOffset(x, y) }
                .width(with(density) { width.toDp() })
                .heightIn(max = with(density) { (window.height - top - bottom).coerceAtLeast(1).toDp() })
                .onSizeChanged { measured = it }
                .graphicsLayer {
                    val frame = PredictiveBackMotionPolicy.dropdownFrame(progress, density.density)
                    alpha = frame.alpha
                    scaleX = frame.scale
                    scaleY = frame.scale
                    translationX = frame.translationX
                    translationY = frame.translationY
                }
                .glassOverlaySurface(RoundedCornerShape(24.dp))
                .pointerInput(Unit) { detectTapGestures { } }
                .verticalScroll(rememberScrollState())
                .padding(6.dp),
        ) { content() }
    }
}
