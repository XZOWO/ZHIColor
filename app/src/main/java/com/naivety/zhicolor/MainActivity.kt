package com.naivety.zhicolor

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.naivety.zhicolor.access.AccessGateScreen
import com.naivety.zhicolor.access.AccessViewModel
import com.naivety.zhicolor.home.HomeRepository
import com.naivety.zhicolor.localization.AppLanguage
import com.naivety.zhicolor.ui.components.GlassOverlayHost
import com.naivety.zhicolor.ui.components.LocalGlassOverlayActive
import com.naivety.zhicolor.ui.components.motion.AppNavigationEntrance
import com.naivety.zhicolor.ui.components.motion.LocalMainTabActive
import com.naivety.zhicolor.ui.components.navigation.AppNavigationBar
import com.naivety.zhicolor.ui.components.navigation.AppNavigationPolicy
import com.naivety.zhicolor.ui.screen.AboutScreen
import com.naivety.zhicolor.ui.screen.HomeScreen
import com.naivety.zhicolor.ui.screen.LyricsScreen
import com.naivety.zhicolor.ui.screen.ToolsScreen
import com.naivety.zhicolor.ui.theme.AppTheme
import com.naivety.zhicolor.update.UpdateDialog
import com.naivety.zhicolor.update.UpdateViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.theme.MiuixTheme

class MainActivity : ComponentActivity() {
    private val access by lazy { ViewModelProvider(this)[AccessViewModel::class.java] }
    private val updates by lazy { ViewModelProvider(this)[UpdateViewModel::class.java] }
    private var foreground by mutableStateOf(false)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                if (access.state.allowed) {
                    if (foreground) {
                        DisposableEffect(Unit) {
                            HomeRepository.start(this@MainActivity)
                            updates.onForeground()
                            onDispose {
                                updates.onBackground(isChangingConfigurations)
                                HomeRepository.stop()
                            }
                        }
                    }
                    AppShell(updates)
                } else {
                    LaunchedEffect(access.state.accessRevoked) {
                        // CHECKING intentionally gates the UI while a permission is being
                        // revalidated. Only a confirmed REQUIRED state revokes active work.
                        HomeRepository.stop()
                        if (access.state.accessRevoked) updates.onAccessRevoked()
                    }
                    AccessGateScreen(state = access.state)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        access.onForeground()
        foreground = true
    }

    override fun onStop() {
        foreground = false
        access.onBackground()
        super.onStop()
    }
}

@Composable
private fun AppShell(updates: UpdateViewModel) {
    val routes = AppNavigationPolicy.routes
    val pager = rememberPagerState { routes.size }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var pageChange by remember { mutableStateOf<Job?>(null) }
    var lyricsHasChildPage by remember { mutableStateOf(false) }
    var navigationHeight by remember { mutableStateOf(0.dp) }
    var navigationVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { navigationVisible = true }
    val navigationShadow by animateFloatAsState(
        targetValue = if (navigationVisible) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (navigationVisible) 380 else 240,
            easing = if (navigationVisible) FastOutSlowInEasing else FastOutLinearInEasing,
        ),
        label = "navigationShadow",
    )

    val surface = MiuixTheme.colorScheme.surface
    val backdrop = rememberLayerBackdrop {
        drawRect(surface)
        drawContent()
    }
    GlassOverlayHost(backdrop = backdrop) {
        val modalActive = LocalGlassOverlayActive.current
        val childPageActive = routes[pager.settledPage] == "lyrics" && lyricsHasChildPage
        Box(Modifier.fillMaxSize().clipToBounds().background(surface)) {
            HorizontalPager(
                state = pager,
                modifier = Modifier.fillMaxSize().layerBackdrop(backdrop),
                key = { index -> routes[index] },
                userScrollEnabled = !modalActive && !childPageActive,
            ) { index ->
                CompositionLocalProvider(
                    LocalMainTabActive provides (index == pager.currentPage && !pager.isScrollInProgress),
                ) {
                    when (routes[index]) {
                        "home" -> HomeScreen(bottomInnerPadding = navigationHeight)
                        "tools" -> ToolsScreen(bottomInnerPadding = navigationHeight)
                        "lyrics" -> LyricsScreen(
                            bottomInnerPadding = navigationHeight,
                            onChildPageActiveChanged = { lyricsHasChildPage = it },
                        )
                        "about" -> AboutScreen(
                            bottomInnerPadding = navigationHeight,
                            checkingForUpdates = updates.state.checking,
                            onCheckForUpdates = updates::checkManually,
                        )
                    }
                }
            }
            AppNavigationEntrance(
                visible = navigationVisible,
                modifier = Modifier.align(Alignment.BottomCenter).onGloballyPositioned { coordinates ->
                    navigationHeight = with(density) { coordinates.size.height.toDp() }
                },
            ) {
                AppNavigationBar(
                    // currentPage is an intermediate page during a multi-page settle. The
                    // bottom indicator follows the pager target so it cannot be pulled back
                    // through each intermediate page while a single drag is settling.
                    currentScreen = routes[pager.targetPage],
                    backdrop = backdrop,
                    shadowVisibilityProgress = navigationShadow,
                    onScreenSelected = { route ->
                        val index = routes.indexOf(route)
                        if (index >= 0) {
                            pageChange?.cancel()
                            pageChange = scope.launch {
                                pager.stopScroll(MutatePriority.PreventUserInput)
                                pager.animateScrollToPage(index, animationSpec = tween(280))
                            }
                        }
                    },
                )
            }
        }
        UpdateDialog(updates)
    }
}
