package com.naivety.zhicolor.ui.screen

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naivety.zhicolor.BuildConfig
import com.naivety.zhicolor.R
import com.naivety.zhicolor.localization.AppLanguage
import com.naivety.zhicolor.ui.components.ActionPreference
import com.naivety.zhicolor.ui.components.AppDialog
import com.naivety.zhicolor.ui.components.DialogBody
import com.naivety.zhicolor.ui.components.GlassMenuItem
import com.naivety.zhicolor.ui.theme.headerBlur
import com.naivety.zhicolor.ui.theme.headerBlurSource
import com.naivety.zhicolor.ui.theme.rememberHeaderBlurState
import com.naivety.zhicolor.ui.theme.rememberHeaderBlurStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme

private enum class AboutPage { LANGUAGE, LICENSES, CREDITS, GPL, APACHE, LGPL, NOTICES }

@Composable
fun AboutScreen(
    bottomInnerPadding: Dp = 0.dp,
    checkingForUpdates: Boolean,
    onCheckForUpdates: () -> Unit,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val density = LocalDensity.current
    val blur = rememberHeaderBlurState()
    val blurStyle = rememberHeaderBlurStyle()
    val headerScroll = MiuixScrollBehavior()
    var headerHeight by remember { mutableStateOf(0.dp) }
    var page by rememberSaveable { mutableStateOf(AboutPage.LANGUAGE) }
    var dialogVisible by rememberSaveable { mutableStateOf(false) }
    val language = AppLanguage.current(context)
    val languageLabels = listOf(stringResource(R.string.about_language_system), "简体中文", "English")
    fun showPage(value: AboutPage) {
        page = value
        dialogVisible = true
    }
    fun visit(url: String) {
        runCatching { uriHandler.openUri(url) }.onFailure {
            Toast.makeText(context, R.string.about_open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().headerBlurSource(blur)
                .nestedScroll(headerScroll.nestedScrollConnection)
                .verticalScroll(rememberScrollState())
                .padding(top = headerHeight + 12.dp, bottom = bottomInnerPadding + 20.dp),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_brand_generated),
                    contentDescription = null,
                    modifier = Modifier.size(88.dp).clip(RoundedCornerShape(24.dp)),
                )
                Text("ZHIColor", modifier = Modifier.padding(top = 16.dp), fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(R.string.about_version_format, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                    modifier = Modifier.padding(top = 6.dp), fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            SmallTitle(stringResource(R.string.about_preferences))
            Card(Modifier.padding(horizontal = 12.dp)) {
                ActionPreference(
                    title = stringResource(R.string.about_language), summary = languageLabels[language.ordinal],
                    onClick = { showPage(AboutPage.LANGUAGE) },
                )
            }
            SmallTitle(stringResource(R.string.about_project))
            Card(Modifier.padding(horizontal = 12.dp)) {
                ActionPreference(
                    title = stringResource(R.string.update_check),
                    summary = if (checkingForUpdates) stringResource(R.string.update_checking) else null,
                    onClick = onCheckForUpdates,
                )
                BasicComponent(title = stringResource(R.string.about_application_id), summary = BuildConfig.APPLICATION_ID)
                ActionPreference(title = stringResource(R.string.about_licenses), onClick = { showPage(AboutPage.LICENSES) })
                ActionPreference(title = stringResource(R.string.about_credits), onClick = { showPage(AboutPage.CREDITS) })
            }
        }
        TopAppBar(
            title = stringResource(R.string.about_navigation), scrollBehavior = headerScroll, color = Color.Transparent,
            modifier = Modifier.align(Alignment.TopCenter).headerBlur(blur, blurStyle).onSizeChanged {
                headerHeight = with(density) { it.height.toDp() }
            },
        )
    }

    val title = when (page) {
        AboutPage.LANGUAGE -> stringResource(R.string.about_language)
        AboutPage.LICENSES -> stringResource(R.string.about_licenses)
        AboutPage.CREDITS -> stringResource(R.string.about_credits)
        AboutPage.GPL -> "GNU GPL-3.0"
        AboutPage.APACHE -> "Apache License 2.0"
        AboutPage.LGPL -> "GNU LGPL-3.0"
        AboutPage.NOTICES -> stringResource(R.string.about_notices)
    }
    AppDialog(show = dialogVisible, title = title, onDismissRequest = { dialogVisible = false }) {
        key(page) {
            when (page) {
                AboutPage.LANGUAGE -> DialogBody(modifier = Modifier.selectableGroup(), verticalSpacing = 0.dp) {
                    AppLanguage.entries.forEachIndexed { index, value ->
                        GlassMenuItem(title = languageLabels[index], selected = language == value, onClick = {
                            dialogVisible = false
                            AppLanguage.set(context, value)
                        })
                    }
                }
                AboutPage.LICENSES -> DialogBody(verticalSpacing = 0.dp) {
                    ActionPreference("GNU GPL-3.0", summary = "ZHIColor", onClick = { page = AboutPage.GPL })
                    ActionPreference("Apache License 2.0", summary = "ColorOS Live Lyrics Bridge", onClick = { page = AboutPage.APACHE })
                    ActionPreference("GNU LGPL-3.0", summary = "DexKit", onClick = { page = AboutPage.LGPL })
                    ActionPreference(stringResource(R.string.about_notices), onClick = { page = AboutPage.NOTICES })
                }
                AboutPage.CREDITS -> DialogBody(verticalSpacing = 0.dp) {
                    ActionPreference("ZHITool", summary = stringResource(R.string.about_credit_zhitool),
                        onClick = { visit("https://github.com/XZOWO/ZHITool") })
                    ActionPreference("ColorOS Live Lyrics Bridge", summary = stringResource(R.string.about_credit_bridge),
                        onClick = { visit("https://github.com/Andrea-lyz/ColorOS-Live-Lyrics-Bridge") })
                    ActionPreference("Lyricon", summary = stringResource(R.string.about_credit_lyricon),
                        onClick = { visit("https://github.com/tomakino/lyricon") })
                    BasicComponent(title = "AndroidX / Jetpack Compose", summary = stringResource(R.string.about_credit_androidx))
                    BasicComponent(title = "Miuix", summary = stringResource(R.string.about_credit_miuix))
                    BasicComponent(title = "Kyant Backdrop", summary = stringResource(R.string.about_credit_kyant))
                }
                else -> LicenseDocument(when (page) {
                    AboutPage.GPL -> "licenses/gpl-3.0.txt"
                    AboutPage.APACHE -> "licenses/apache-2.0.txt"
                    AboutPage.LGPL -> "licenses/lgpl-3.0.txt"
                    else -> "licenses/THIRD_PARTY_NOTICES.txt"
                })
            }
        }
    }
}

@Composable
private fun LicenseDocument(path: String) {
    val context = LocalContext.current
    val unavailable = stringResource(R.string.about_document_unavailable)
    val text by produceState<String?>(null, path, unavailable) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() } }
                .getOrElse { unavailable }
        }
    }
    DialogBody(verticalSpacing = 0.dp) {
        Text(text.orEmpty(), fontSize = 13.sp, lineHeight = 20.sp, modifier = Modifier.fillMaxWidth())
    }
}
