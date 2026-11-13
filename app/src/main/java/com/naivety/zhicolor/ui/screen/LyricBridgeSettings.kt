package com.naivety.zhicolor.ui.screen

import androidx.compose.foundation.layout.*
import com.naivety.zhicolor.ui.components.GlassSectionTitle
import com.naivety.zhicolor.ui.components.GlassCard
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
    Column {
        GlassSectionTitle(stringResource(R.string.scr_music_apps))
        GlassCard(Modifier.padding(horizontal=12.dp)) {
            if(apps.isEmpty()) BasicComponent(title=stringResource(R.string.scr_no_music_apps))
            apps.forEach {app->key(app.packageName) {
                if(app.providerInstalled) SwitchPreference(title=app.name,checked=selectedApps[app.packageName]==true,onCheckedChange={BridgeSettings.setPlayerEnabled(context,app.packageName,it)})
                else BasicComponent(title=app.name,summary=stringResource(R.string.scr_install_provider))
            }}
        }
    }
}
