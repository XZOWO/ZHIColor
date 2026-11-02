/* Home cards adapted from ZHITool (GPL-3.0; see NOTICE). */
package com.naivety.zhicolor.ui.screen

import androidx.compose.foundation.isSystemInDarkTheme
import com.naivety.zhicolor.ui.components.BurningCover
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Security
import com.naivety.zhicolor.ui.components.GlassSectionTitle
import com.naivety.zhicolor.ui.components.GlassCard
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naivety.zhicolor.R
import com.naivety.zhicolor.core.ScopeRestartController
import com.naivety.zhicolor.home.*
import com.naivety.zhicolor.ui.components.AppListPage
import com.naivety.zhicolor.ui.components.AlbumSpectrum
import com.naivety.zhicolor.ui.components.AlbumSpectrumMotion
import com.naivety.zhicolor.ui.components.GlassDropdownMenu
import com.naivety.zhicolor.ui.components.GlassMenuItem
import com.naivety.zhicolor.ui.components.motion.LocalMainTabActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun HomeScreen(bottomInnerPadding: Dp = 0.dp, cover: ImageBitmap?, audio: HomeAudioState) {
    val playing by HomeRepository.playback.collectAsState()
    val lyricConnected by HomeRepository.lyricConnected.collectAsState()
    val frameworkActive by FrameworkBridge.active.collectAsState()
    val device by produceState<DeviceInfo?>(DeviceInfoLoader.cached()) {
        if (value == null) value = withContext(Dispatchers.IO) { DeviceInfoLoader.load() }
    }
    AppListPage(
        title = "ZHIColor", bottomPadding = bottomInnerPadding, actions = { HomeActions() },
    ) {
        item { SongCard(playing, cover, audio) }
        item { GlassSectionTitle(stringResource(R.string.scr_status)) }
        item {
            GlassCard(Modifier.padding(horizontal = 12.dp)) {
                StatusRow(Icons.Rounded.Security, stringResource(R.string.scr_framework_permission),
                    stringResource(if (frameworkActive) R.string.scr_connected else R.string.scr_inactive), frameworkActive)
                StatusRow(Icons.Rounded.GraphicEq, stringResource(R.string.scr_lyric_source),
                    stringResource(if (lyricConnected) R.string.scr_connected else R.string.scr_waiting), lyricConnected)
            }
        }
        item { GlassSectionTitle(stringResource(R.string.scr_device_info)) }
        item {
            GlassCard(Modifier.padding(horizontal = 12.dp)) {
                InfoRow(stringResource(R.string.scr_device), device?.device ?: stringResource(R.string.scr_loading))
                InfoRow(stringResource(R.string.scr_android_version), device?.android ?: stringResource(R.string.scr_loading))
                InfoRow(stringResource(R.string.scr_coloros_version), device?.colorOs?.let {
                    if (it == "未识别") stringResource(R.string.scr_unrecognized) else it
                } ?: stringResource(R.string.scr_loading))
                InfoRow(stringResource(R.string.scr_app_version), device?.appVersion ?: stringResource(R.string.scr_loading))
            }
        }
    }
}

@Composable
private fun HomeActions() {
    val context = LocalContext.current
    val busy by ScopeRestartController.busy.collectAsState()
    var expanded by remember { mutableStateOf(false) }
    var bounds by remember { mutableStateOf(Rect.Zero) }
    Box(Modifier.onGloballyPositioned { bounds = it.boundsInRoot() }) {
        IconButton(onClick = { expanded = !expanded }) {
            Icon(Icons.Rounded.MoreHoriz, contentDescription = stringResource(R.string.scr_more))
        }
        GlassDropdownMenu(show = expanded, anchorBounds = bounds, minWidth = 208.dp,
            onDismissRequest = { expanded = false }) {
            GlassMenuItem(title = stringResource(if (busy) R.string.scr_restarting else R.string.scr_restart_scope),
                onClick = { expanded = false; if (!busy) ScopeRestartController.request(context) })
        }
    }
}

@Composable
private fun SongCard(song: HomePlayback, cover: ImageBitmap?, audio: HomeAudioState) {
    GlassCard(modifier = Modifier.padding(12.dp).fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            SongAlbum(cover, song, audio)
            Spacer(Modifier.size(8.dp))
            Column(Modifier.weight(1f)) {
                Text(song.title?.takeIf(String::isNotBlank) ?: stringResource(R.string.scr_no_track), fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!song.title.isNullOrBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(song.artist?.takeIf(String::isNotBlank) ?: stringResource(R.string.scr_unknown_artist),
                        fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (!song.title.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (song.playing) stringResource(R.string.scr_playing) else stringResource(R.string.scr_paused), fontSize = 13.sp, fontWeight = FontWeight.Medium,
                            color = if (song.playing) statusGreen() else if (isSystemInDarkTheme()) Color(0xFFFFC46B) else Color(0xFF855000))
                    }
                }
            }
        }
    }
}

@Composable
private fun SongAlbum(cover: ImageBitmap?, song: HomePlayback, audio: HomeAudioState) {
    val playing = song.playing
    val bands = audio.bands
    val loudness = audio.loudness
    val captureActive = audio.visible && LocalMainTabActive.current
    val rotation = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(playing, captureActive, cover != null) {
        if (!playing || !captureActive || cover == null) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) rotation.floatValue = (rotation.floatValue + (360f / 16f) * ((now - last) / 1_000_000_000f)) % 360f
                last = now
            }
        }
    }
    Box(Modifier.size(AlbumSpectrumMotion.CANVAS_DP.dp), contentAlignment = Alignment.Center) {
        AlbumSpectrum(
            bands = bands,
            loudness = loudness,
            active = playing && audio.active && captureActive,
            color = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxSize(),
            resetKey = listOf(song.playerPackage, song.audioSessionId, song.audioRouteKey, song.title, song.artist)
        )
        if (cover != null) {
            BurningCover(cover, { rotation.floatValue }, AlbumSpectrumMotion.COVER_DIAMETER_DP.dp)
        } else {
            Image(painterResource(R.drawable.ic_brand_generated), contentDescription = stringResource(R.string.scr_cover),
                modifier = Modifier.size(AlbumSpectrumMotion.COVER_DIAMETER_DP.dp).clip(CircleShape))
        }
    }
}

@Composable
private fun InfoRow(title: String, summary: String) {
    BasicComponent(title = title, summary = summary, insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp))
}

@Composable
private fun StatusRow(icon: ImageVector, title: String, value: String, ok: Boolean) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.size(12.dp))
        Text(title, fontSize = 15.sp, color = MiuixTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (ok) statusGreen() else if (isSystemInDarkTheme()) Color(0xFFFF8B8B) else Color(0xFFB4232B))
    }
}

@Composable
private fun statusGreen() = if (isSystemInDarkTheme()) Color(0xFF73E49A) else Color(0xFF146C35)
