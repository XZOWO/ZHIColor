package com.naivety.zhicolor.ui.screen

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.naivety.zhicolor.R
import com.naivety.zhicolor.bridge.BridgeSettings
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
internal fun LyricBridgeSettings() {
    val context=LocalContext.current
    val revision=rememberSettingsRevision()
    val apps=rememberMusicApps(revision)
    val selectedApps=remember(revision,apps){apps.associate {it.packageName to BridgeSettings.playerEnabled(context,it.packageName)}}
    val master=remember(revision){BridgeSettings.enabled(context)}
    Column {
        Card(Modifier.padding(horizontal=12.dp)) {
            SwitchPreference(title=stringResource(R.string.scr_lyric_bridge),checked=master,onCheckedChange={BridgeSettings.setEnabled(context,it)})
        }
        AnimatedVisibility(visible=master,enter=expandVertically(tween(260))+fadeIn(tween(200)),exit=shrinkVertically(tween(220))+fadeOut(tween(160))) {
            Column {
                SmallTitle(stringResource(R.string.scr_music_apps))
                Card(Modifier.padding(horizontal=12.dp)) {
                    if(apps.isEmpty()) BasicComponent(title=stringResource(R.string.scr_no_music_apps))
                    apps.forEach {app->key(app.packageName) {
                        if(app.providerInstalled) SwitchPreference(title=app.name,checked=selectedApps[app.packageName]==true,onCheckedChange={BridgeSettings.setPlayerEnabled(context,app.packageName,it)})
                        else BasicComponent(title=app.name,summary=stringResource(R.string.scr_install_provider))
                    }}
                }
            }
        }
    }
}
