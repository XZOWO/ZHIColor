package com.naivety.zhicolor.ui.screen

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.naivety.zhicolor.R
import com.naivety.zhicolor.bridge.ToolsSettings
import com.naivety.zhicolor.ui.theme.headerBlur
import com.naivety.zhicolor.ui.theme.headerBlurSource
import com.naivety.zhicolor.ui.theme.rememberHeaderBlurState
import com.naivety.zhicolor.ui.theme.rememberHeaderBlurStyle
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
fun ToolsScreen(bottomInnerPadding: Dp = 0.dp) {
    val scrollBehavior = MiuixScrollBehavior()
    val hazeState = rememberHeaderBlurState()
    val hazeStyle = rememberHeaderBlurStyle()
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences(ToolsSettings.PREFERENCES, 0) }
    var hideLockscreenIcon by remember(context) { mutableStateOf(ToolsSettings.hideLockscreenMusicApp(context)) }
    var hideControlCenterIcon by remember(context) { mutableStateOf(ToolsSettings.hideControlCenterMusicApp(context)) }
    DisposableEffect(preferences) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                ToolsSettings.HIDE_LOCKSCREEN_MUSIC_APP ->
                    hideLockscreenIcon = ToolsSettings.hideLockscreenMusicApp(context)
                ToolsSettings.HIDE_CONTROL_CENTER_MUSIC_APP ->
                    hideControlCenterIcon = ToolsSettings.hideControlCenterMusicApp(context)
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.tab_tools),
                scrollBehavior = scrollBehavior,
                color = Color.Transparent,
                modifier = Modifier.headerBlur(hazeState, hazeStyle),
            )
        },
        contentWindowInsets = WindowInsets.systemBars,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .headerBlurSource(hazeState),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 12.dp,
                bottom = padding.calculateBottomPadding() + bottomInnerPadding + 12.dp,
            ),
            overscrollEffect = null,
        ) {
            item {
                Card(Modifier.padding(horizontal = 12.dp)) {
                    SwitchPreference(
                        title = stringResource(R.string.tool_hide_lockscreen_music_app),
                        checked = hideLockscreenIcon,
                        onCheckedChange = { hidden ->
                            ToolsSettings.setHideLockscreenMusicApp(context, hidden)
                            hideLockscreenIcon = hidden
                        },
                    )
                    SwitchPreference(
                        title = stringResource(R.string.tool_hide_control_center_music_app),
                        checked = hideControlCenterIcon,
                        onCheckedChange = { hidden ->
                            ToolsSettings.setHideControlCenterMusicApp(context, hidden)
                            hideControlCenterIcon = hidden
                        },
                    )
                }
            }
        }
    }
}
