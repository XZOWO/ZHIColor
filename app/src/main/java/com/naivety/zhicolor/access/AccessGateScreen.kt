package com.naivety.zhicolor.access

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.naivety.zhicolor.R
import com.naivety.zhicolor.localization.AppLanguage
import com.naivety.zhicolor.ui.components.AppDialog
import com.naivety.zhicolor.ui.components.DialogBody
import com.naivety.zhicolor.ui.components.GlassMenuItem
import com.naivety.zhicolor.ui.components.GlassOverlayHost
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun AccessGateScreen(state: AccessState) {
    val context = LocalContext.current
    val surface = MiuixTheme.colorScheme.surface
    val foreground = MiuixTheme.colorScheme.onSurface
    val secondary = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val backdrop = rememberLayerBackdrop {
        drawRect(surface)
        drawContent()
    }
    var languageVisible by rememberSaveable { mutableStateOf(false) }
    val language = AppLanguage.current(context)
    val languageLabels = listOf(stringResource(R.string.about_language_system), "简体中文", "English")

    GlassOverlayHost(backdrop = backdrop) {
        Column(
            modifier = Modifier.fillMaxSize().background(surface).layerBackdrop(backdrop)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("ZHIColor", color = foreground, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(R.string.access_title),
                modifier = Modifier.padding(top = 12.dp),
                color = foreground,
                fontSize = 21.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                stringResource(R.string.access_description),
                modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
                color = secondary,
                fontSize = 14.sp,
                lineHeight = 21.sp,
            )
            Card(Modifier.fillMaxWidth()) {
                AccessStatusRow(
                    title = stringResource(R.string.access_root),
                    status = when (state.root) {
                        AccessStatus.CHECKING, AccessStatus.UNKNOWN -> stringResource(R.string.access_checking)
                        AccessStatus.GRANTED -> stringResource(R.string.access_granted)
                        AccessStatus.REQUIRED -> stringResource(R.string.access_required)
                    },
                    granted = state.root == AccessStatus.GRANTED,
                )
                AccessStatusRow(
                    title = stringResource(R.string.access_framework),
                    status = when (state.framework) {
                        AccessStatus.CHECKING, AccessStatus.UNKNOWN -> stringResource(R.string.access_checking)
                        AccessStatus.GRANTED -> stringResource(R.string.access_enabled)
                        AccessStatus.REQUIRED -> stringResource(R.string.access_required)
                    },
                    granted = state.framework == AccessStatus.GRANTED,
                )
            }
            Box(
                modifier = Modifier.padding(top = 20.dp)
                    .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                    .clickable(role = Role.Button) { languageVisible = true }
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.access_language),
                    color = MiuixTheme.colorScheme.primary,
                    fontSize = 14.sp,
                    textDecoration = TextDecoration.Underline,
                )
            }
        }
        AppDialog(
            show = languageVisible,
            title = stringResource(R.string.access_language),
            onDismissRequest = { languageVisible = false },
        ) {
            DialogBody(modifier = Modifier.selectableGroup(), verticalSpacing = 0.dp) {
                AppLanguage.entries.forEachIndexed { index, value ->
                    GlassMenuItem(title = languageLabels[index], selected = language == value, onClick = {
                        languageVisible = false
                        AppLanguage.set(context, value)
                    })
                }
            }
        }
    }
}

@Composable
private fun AccessStatusRow(title: String, status: String, granted: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, modifier = Modifier.weight(1f), color = MiuixTheme.colorScheme.onSurface, fontSize = 16.sp)
        Text(
            text = status,
            modifier = Modifier.padding(start = 16.dp),
            color = if (granted) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 13.sp,
        )
    }
}
