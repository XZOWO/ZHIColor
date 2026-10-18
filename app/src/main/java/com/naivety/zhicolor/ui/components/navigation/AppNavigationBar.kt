package com.naivety.zhicolor.ui.components.navigation

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.naivety.zhicolor.R
import com.naivety.zhicolor.ui.components.navigation.kyant.components.LiquidBottomTab
import com.naivety.zhicolor.ui.components.navigation.kyant.components.LiquidBottomTabs
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Project routes and resources around the licensed Kyant 1.0.6 catalog component. */
@Composable
fun AppNavigationBar(
    currentScreen: String,
    onScreenSelected: (String) -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    shadowVisibilityProgress: Float = 1f,
) {
    val labels = listOf(
        stringResource(R.string.tab_home),
        stringResource(R.string.tab_tools),
        stringResource(R.string.tab_lyrics),
        stringResource(R.string.tab_about),
    )
    val icons = listOf(R.drawable.nav_home, R.drawable.nav_tools, R.drawable.nav_lyrics, R.drawable.nav_about)
    val selectedIndex = AppNavigationPolicy.selectedIndex(currentScreen)
    val latestSelection = rememberUpdatedState(selectedIndex)
    val latestOnSelected = rememberUpdatedState(onScreenSelected)
    val selectionSource = remember { { latestSelection.value } }
    val selectTab = remember {
        { index: Int ->
            if (index in AppNavigationPolicy.routes.indices) {
                latestOnSelected.value(AppNavigationPolicy.routes[index])
            }
        }
    }
    val colors = MiuixTheme.colorScheme
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val barHeight = with(density) { maxOf(64.dp, 24.dp + 2.dp + 12.sp.toDp() + 16.dp) }
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    BoxWithConstraints(
        modifier = modifier.padding(start = 12.dp, end = 12.dp, bottom = bottomInset + 12.dp)
            .widthIn(max = 480.dp).fillMaxWidth(),
    ) {
        // New geometry gets a fresh gesture scope. An old gesture cannot commit after a resize or RTL change.
        key(constraints.maxWidth, density.density, density.fontScale, direction, barHeight) {
            LiquidBottomTabs(
                selectedTabIndex = selectionSource,
                onTabSelected = selectTab,
                backdrop = backdrop,
                tabsCount = AppNavigationPolicy.routes.size,
                modifier = Modifier.fillMaxWidth().selectableGroup(),
                accentColor = colors.primary,
                containerColor = colors.surface.copy(alpha = 0.4f),
                shadowVisibilityProgress = shadowVisibilityProgress,
                contentHeight = barHeight,
            ) {
                AppNavigationPolicy.routes.forEachIndexed { index, _ ->
                    val selected = selectedIndex == index
                    val tint = if (selected) colors.primary else colors.onSurface
                    LiquidBottomTab(
                        onClick = { selectTab(index) },
                        selected = selected,
                    ) {
                        Icon(
                            imageVector = ImageVector.vectorResource(icons[index]),
                            contentDescription = null,
                            tint = tint,
                            modifier = Modifier.size(24.dp),
                        )
                        Text(
                            text = labels[index],
                            color = tint,
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
