package com.naivety.zhicolor.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Each input band owns one ray. Audio normalization belongs exclusively to the audio pipeline. */
@Composable
fun AlbumSpectrum(
    bands: List<Float>,
    loudness: Float,
    active: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    resetKey: Any? = null
) {
    val latestBands by rememberUpdatedState(bands)
    val latestLoudness by rememberUpdatedState(loudness)
    var renderedBands by remember(resetKey) { mutableStateOf(List(AlbumSpectrumMotion.BAND_COUNT) { 0f }) }
    var renderedLoudness by remember(resetKey) { mutableFloatStateOf(0f) }

    LaunchedEffect(active, resetKey) {
        var previousTime = 0L
        while (isActive) {
            var atRest = true
            withFrameNanos { now ->
                val elapsed = if (previousTime == 0L) 1f / 60f else
                    ((now - previousTime) / 1_000_000_000f).coerceIn(0f, 0.25f)
                previousTime = now
                renderedBands = List(AlbumSpectrumMotion.BAND_COUNT) { index ->
                    val target = if (active) AlbumSpectrumMotion.visibleStrength(
                        latestBands.getOrElse(index) { 0f }, latestLoudness
                    ) else 0f
                    val next = AlbumSpectrumMotion.stepBand(renderedBands[index], target, elapsed)
                    val value = if (abs(next - target) < 0.001f) target else next
                    if (value > 0.001f) atRest = false
                    value
                }
                val targetGlow = AlbumSpectrumMotion.spectrumStrength(if (active) latestLoudness else 0f)
                val nextGlow = AlbumSpectrumMotion.stepGlow(renderedLoudness, targetGlow, elapsed)
                renderedLoudness = if (abs(nextGlow - targetGlow) < 0.001f) targetGlow else nextGlow
                if (renderedLoudness > 0.001f) atRest = false
            }
            if (!active && atRest) break
        }
    }

    Box(modifier.drawWithCache {
        val origin = Offset(size.width / 2f, size.height / 2f)
        // Cover and canvas share dp geometry; never scale this layer independently of the cover.
        val coverRadius = AlbumSpectrumMotion.COVER_RADIUS_DP.dp.toPx()
        val radius = AlbumSpectrumMotion.BASE_RADIUS_DP.dp.toPx()
        val lift = AlbumSpectrumMotion.SPECTRUM_LIFT_DP.dp.toPx()
        val rayWidth = AlbumSpectrumMotion.RAY_WIDTH_DP.dp.toPx()
        val outsideCover = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(Rect(Offset.Zero, size))
            addOval(Rect(origin - Offset(coverRadius, coverRadius), origin + Offset(coverRadius, coverRadius)))
        }
        val directions = Array(AlbumSpectrumMotion.BAND_COUNT) { index ->
            val angle = AlbumSpectrumMotion.angleRadians(index)
            Offset(cos(angle).toFloat(), sin(angle).toFloat())
        }
        onDrawBehind {
            // The halo stays circular and responds only to real loudness, never to FFT shape or time.
            val level = renderedLoudness
            if (level > 0.001f) {
                val outerRadius = AlbumSpectrumMotion.glowRadiusDp(level).dp.toPx()
                val innerStop = coverRadius / outerRadius
                val width = 1f - innerStop
                // Expand only the OUTER edge; the bright inner annulus stays attached to the cover.
                val glowBrush = Brush.radialGradient(
                    0f to Color.Transparent,
                    innerStop to color.copy(alpha = 0.5f),
                    (innerStop + width * 0.24f) to color.copy(alpha = 0.32f),
                    (innerStop + width * 0.62f) to color.copy(alpha = 0.10f),
                    1f to Color.Transparent,
                    center = origin, radius = outerRadius
                )
                clipPath(outsideCover) {
                    drawCircle(glowBrush, radius = outerRadius, alpha = level)
                }
            }
            for (index in directions.indices) {
                val strength = renderedBands[index]
                if (strength <= 0.001f && level <= 0.001f) continue
                val direction = directions[index]
                // A fixed zero-height round cap preserves equal ray density, without invented energy.
                drawLine(
                    color = color.copy(alpha = 0.30f + 0.58f * strength),
                    start = origin + direction * radius,
                    end = origin + direction * (radius + lift * strength),
                    strokeWidth = rayWidth,
                    cap = StrokeCap.Round
                )
            }
        }
    })
}
