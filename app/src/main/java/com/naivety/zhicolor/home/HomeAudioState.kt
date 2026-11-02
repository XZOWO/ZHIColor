package com.naivety.zhicolor.home

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.naivety.zhicolor.advanced.AudioCaptureHub
import com.naivety.zhicolor.ui.components.motion.LocalMainTabActive

internal data class HomeAudioState(
    val bands: List<Float>,
    val loudness: Float,
    val visible: Boolean,
    val active: Boolean,
    val backgroundAudio: BackgroundAudioFrame = BackgroundAudioFrame.SILENT,
)

/** One player-session capture feeds both the cover visualization and the page artwork. */
@Composable
internal fun rememberHomeAudio(
    playing: Boolean,
    audioSessionId: Int,
    audioRouteKey: String,
    mediaKey: String = "",
    playerPackage: String = "",
): HomeAudioState {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val tabActive = LocalMainTabActive.current
    val controller = remember(context.applicationContext) {
        AudioCaptureHub.acquire(context.applicationContext ?: context)
    }
    val bands by controller.bands.collectAsState()
    val loudness by controller.loudness.collectAsState()
    val backgroundAudio by controller.backgroundAudio.collectAsState()
    var foreground by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    fun hasPermission() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    var permissionGranted by remember(context) { mutableStateOf(hasPermission()) }
    var permissionRequested by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissionGranted = it
    }
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, _ ->
            foreground = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            permissionGranted = hasPermission()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val visible = tabActive && foreground
    val requested = visible && playing && audioSessionId > 0
    LaunchedEffect(controller, requested, permissionGranted, audioSessionId, audioRouteKey, mediaKey, playerPackage) {
        if (requested && permissionGranted) {
            controller.start(audioSessionId, audioRouteKey, mediaKey, playerPackage)
        } else {
            controller.stop()
            if (requested && !permissionGranted && !permissionRequested) {
                permissionRequested = true
                launcher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }
    DisposableEffect(controller) { onDispose { controller.close() } }
    return HomeAudioState(bands, loudness, visible, requested && permissionGranted, backgroundAudio)
}
