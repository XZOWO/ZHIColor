package com.naivety.zhicolor.ui.components.background

import java.util.concurrent.atomic.AtomicReference
import com.naivety.zhicolor.home.BackgroundAudioFrame
import android.os.Handler
import android.os.Looper
import android.animation.ValueAnimator
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import kotlin.math.roundToInt
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** The GPU's final blurred frame is drawn by Compose, shared by cards, tabs and portals. */
@Composable
fun ArtworkMusicBackground(
    artwork: ImageBitmap?, bands: List<Float>, playing: Boolean, active: Boolean,
    modifier: Modifier = Modifier,
    onArtworkReady: (ImageBitmap) -> Unit = {},
    backgroundAudio: BackgroundAudioFrame = BackgroundAudioFrame.SILENT,
    trackKey: String? = null,
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val dark = isSystemInDarkTheme()
    val reducedMotion = !ValueAnimator.areAnimatorsEnabled()
    var dimensions by remember { mutableStateOf(IntSize.Zero) }
    val frame = remember { mutableStateOf<ImageBitmap?>(null) }
    var renderer by remember { mutableStateOf<ArtworkGlRenderer?>(null) }
    val latestReady by rememberUpdatedState(onArtworkReady)
    val latestArtwork by rememberUpdatedState(artwork)
    val latestBands by rememberUpdatedState(bands)
    val latestBackgroundAudio by rememberUpdatedState(backgroundAudio)
    val latestTrackKey by rememberUpdatedState(trackKey)
    val latestPlaying by rememberUpdatedState(playing)
    val latestDark by rememberUpdatedState(dark)
    val latestReduced by rememberUpdatedState(reducedMotion)
    DisposableEffect(artwork != null, active, dimensions, density) {
        val main = Handler(Looper.getMainLooper())
        var disposed = false
        var notified: ImageBitmap? = null
        val pending = AtomicReference<Pair<ImageBitmap, ImageBitmap>?>(null)
        val deliver = Runnable {
            val prepared = pending.getAndSet(null)
            if (!disposed && prepared != null) {
                frame.value = prepared.first
                if (latestArtwork === prepared.second && notified !== prepared.second) {
                    notified = prepared.second
                    latestReady(prepared.second)
                }
            }
        }
        val instance = if (artwork != null && active && dimensions.width > 0 && dimensions.height > 0) {
            ArtworkGlRenderer(context.applicationContext, dimensions.width, dimensions.height, density) { bitmap, preparedArtwork, _ ->
                // A busy UI consumes the latest complete frame, never a queue of obsolete frames.
                pending.set(bitmap.asImageBitmap() to preparedArtwork)
                main.removeCallbacks(deliver)
                main.post(deliver)
            }.also { it.update(checkNotNull(latestArtwork), latestBands, latestPlaying, latestDark, latestReduced, trackKey = latestTrackKey, backgroundAudio = latestBackgroundAudio) }
        } else null
        renderer = instance
        if (artwork == null) frame.value = null
        onDispose { disposed = true; main.removeCallbacks(deliver); pending.set(null); instance?.release(); renderer = null }
    }
    SideEffect {
        artwork?.let { renderer?.update(it, bands, playing, dark, reducedMotion, trackKey = trackKey, backgroundAudio = backgroundAudio) }
    }
    Box(modifier.background(MiuixTheme.colorScheme.background).onSizeChanged { dimensions = it }) {
        if (artwork != null) Canvas(Modifier.matchParentSize()) {
            frame.value?.let { image ->
                drawImage(image, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                    filterQuality = FilterQuality.Low)
            }
        }
    }
}
