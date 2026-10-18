package com.naivety.zhicolor.ui.screen

import android.content.*
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.naivety.zhicolor.R
import com.naivety.zhicolor.ui.components.ActionPreference
import com.naivety.zhicolor.ui.components.DialogBody
import com.naivety.zhicolor.ui.components.DialogAction
import com.naivety.zhicolor.ui.components.DialogActions
import com.naivety.zhicolor.ui.components.GlassMenuItem
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.naivety.zhicolor.bridge.BridgeSettings
import com.naivety.zhicolor.bridge.MusicAppCatalog
import com.naivety.zhicolor.ui.components.AppDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun rememberSettingsRevision(): Int {
    val context=LocalContext.current
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(context) {
        val listener=SharedPreferences.OnSharedPreferenceChangeListener { _,_->revision++ }
        val prefs=listOf("zhicolor_lyric_ui","zhicolor_lyrics_debug",BridgeSettings.CONTROL_PREFS).map { context.getSharedPreferences(it,0) }
        prefs.forEach {it.registerOnSharedPreferenceChangeListener(listener)}
        onDispose {prefs.forEach {it.unregisterOnSharedPreferenceChangeListener(listener)}}
    }
    return revision
}

@Composable
internal fun rememberMusicApps(refreshKey:Int=0): List<MusicAppCatalog.Entry> {
    val context=LocalContext.current;val owner=LocalLifecycleOwner.current
    var tick by remember {mutableIntStateOf(0)}
    DisposableEffect(context,owner) {
        val receiver=object:BroadcastReceiver(){override fun onReceive(c:Context,i:Intent){MusicAppCatalog.invalidate();tick++}}
        val filter=IntentFilter().apply {addAction(Intent.ACTION_PACKAGE_ADDED);addAction(Intent.ACTION_PACKAGE_REMOVED);addAction(Intent.ACTION_PACKAGE_CHANGED);addDataScheme("package")}
        ContextCompat.registerReceiver(context,receiver,filter,ContextCompat.RECEIVER_NOT_EXPORTED)
        val observer=LifecycleEventObserver { _,event->if(event==Lifecycle.Event.ON_RESUME){MusicAppCatalog.invalidate();tick++} }
        owner.lifecycle.addObserver(observer)
        onDispose {context.unregisterReceiver(receiver);owner.lifecycle.removeObserver(observer)}
    }
    val apps by produceState<List<MusicAppCatalog.Entry>>(emptyList(),tick,refreshKey) {value=withContext(Dispatchers.IO){MusicAppCatalog.installed(context)}}
    return apps
}

@Composable
internal fun ChoiceRow(title:String,value:String,options:List<String>,selected:Int,onSelect:(Int)->Unit) {
    var show by remember {mutableStateOf(false)}
    ActionPreference(title=title,summary=value,onClick={show=true})
    AppDialog(show=show,title=title,onDismissRequest={show=false}) {
        DialogBody(modifier=Modifier.selectableGroup(),verticalSpacing=0.dp) {
            options.forEachIndexed {index,label ->
                GlassMenuItem(title=label,selected=index==selected,onClick={onSelect(index);show=false})
            }
        }
    }
}

@Composable
internal fun TextInputRow(title:String,value:String,numeric:Boolean=false,displayValue:String=value,onSave:(String)->Unit) {
    var show by remember {mutableStateOf(false)};var draft by remember(value){mutableStateOf(value)}
    val context=LocalContext.current
    val resources=LocalResources.current
    ActionPreference(title=title,summary=displayValue,onClick={draft=value;show=true})
    AppDialog(show=show,title=title,onDismissRequest={show=false}) {
        DialogBody {
            TextField(value=draft,onValueChange={if(!numeric||it.all(Char::isDigit))draft=it},modifier=Modifier.fillMaxWidth())
        }
        DialogActions {
            DialogAction(text=stringResource(R.string.ov_confirm),onClick={runCatching{onSave(draft);show=false}.onFailure{Toast.makeText(context,resources.getString(R.string.ov_invalid_input),Toast.LENGTH_SHORT).show()}})
        }
    }
}

@Composable
internal fun ColorRow(title:String,value:String,palette:List<String>,onSave:(String)->Unit) {
    var show by remember {mutableStateOf(false)};var draft by remember(value){mutableStateOf(value)}
    fun hsvFor(hex:String)=FloatArray(3).also {android.graphics.Color.colorToHSV(runCatching{android.graphics.Color.parseColor(hex)}.getOrDefault(android.graphics.Color.WHITE),it)}
    var hsv by remember(value){mutableStateOf(hsvFor(value))}
    val context=LocalContext.current
    val resources=LocalResources.current
    ActionPreference(title=title,summary=value,onClick={draft=value;hsv=hsvFor(value);show=true})
    AppDialog(show=show,title=title,onDismissRequest={show=false}) {
        DialogBody {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly) {
            palette.forEach {hex->
                Box(Modifier.size(32.dp).background(Color(android.graphics.Color.parseColor(hex)),CircleShape).clickable{draft=hex;hsv=hsvFor(hex)})
            }
        }
        Spacer(Modifier.height(16.dp))
        TextField(value=draft,onValueChange={draft=it;if(Regex("#[0-9a-fA-F]{6}").matches(it))hsv=hsvFor(it)},modifier=Modifier.fillMaxWidth(),label="#RRGGBB")
        listOf(stringResource(R.string.ov_hue),stringResource(R.string.ov_saturation),stringResource(R.string.ov_brightness)).forEachIndexed {index,label->
            Text(label,fontSize=14.sp,modifier=Modifier.padding(top=10.dp))
            Slider(value=hsv[index],onValueChange={v->val next=hsv.copyOf();next[index]=v;hsv=next;draft=String.format("#%06X",android.graphics.Color.HSVToColor(next) and 0xFFFFFF)},valueRange=if(index==0)0f..360f else 0f..1f,modifier=Modifier.fillMaxWidth())
        }
        }
        DialogActions {
            DialogAction(text=stringResource(R.string.ov_confirm),onClick={if(Regex("#[0-9a-fA-F]{6}").matches(draft)){onSave(draft.uppercase());show=false}else Toast.makeText(context,resources.getString(R.string.ov_invalid_color),Toast.LENGTH_SHORT).show()})
        }
    }
}

internal data class LyricField(val key:String,val title:String,val kind:String="slider",val min:Int=0,val max:Int=100,val step:Int=1,val factor:Float=1f,val unit:String="%",val options:List<String> = emptyList(),val optionValues:List<Int> = emptyList(),val dependsOn:String?=null)
internal data class LyricGroup(val title:String,val fields:List<LyricField>)
@Composable
internal fun lyricGroups(): List<LyricGroup> = listOf(
    LyricGroup(stringResource(R.string.ov_group_typography),listOf(
        LyricField("main_font_tenths_sp",stringResource(R.string.ov_font_size),min=180,max=280,step=10,factor=10f,unit="sp"),
        LyricField("secondary_font_ratio_percent",stringResource(R.string.ov_secondary_font_size),min=55,max=75),
        LyricField("font_weight",stringResource(R.string.ov_font_weight),"choice",options=listOf(stringResource(R.string.ov_system),stringResource(R.string.ov_regular),stringResource(R.string.ov_medium),stringResource(R.string.ov_bold))),
        LyricField("alignment",stringResource(R.string.ov_alignment),"choice",options=listOf(stringResource(R.string.ov_default),stringResource(R.string.ov_left),stringResource(R.string.ov_center),stringResource(R.string.ov_right)),optionValues=listOf(3,0,1,2)),
        LyricField("line_spacing_tenths_dp",stringResource(R.string.ov_line_spacing),min=-50,max=200,step=5,factor=10f,unit="dp"),
        LyricField("wrapped_line_spacing_tenths_dp",stringResource(R.string.ov_wrapped_spacing),min=-10,max=80,step=5,factor=10f,unit="dp"))),
    LyricGroup(stringResource(R.string.ov_group_color),listOf(
        LyricField("primary_color",stringResource(R.string.ov_lyric_color),"color"),LyricField("glow_color",stringResource(R.string.ov_glow_color),"color"),
        LyricField("glow_intensity_percent",stringResource(R.string.ov_glow_intensity)),LyricField("glow_radius_percent",stringResource(R.string.ov_glow_radius),min=10,max=24))),
    LyricGroup(stringResource(R.string.ov_group_hierarchy),listOf(
        LyricField("active_opacity_percent",stringResource(R.string.ov_active_lyric),min=50,max=100),
        LyricField("current_unrevealed_opacity_percent",stringResource(R.string.ov_unrevealed_lyric),min=20,max=100),
        LyricField("inactive_opacity_percent",stringResource(R.string.ov_inactive_lyrics),min=30,max=100),
        LyricField("vertical_fade_enabled",stringResource(R.string.ov_edge_fade),"switch"),
        LyricField("vertical_fade_length_tenths_dp",stringResource(R.string.ov_fade_length),min=0,max=1200,step=10,factor=10f,unit="dp",dependsOn="vertical_fade_enabled"),
        LyricField("inactive_row_fade_enabled",stringResource(R.string.ov_distance_fade),"switch"),
        LyricField("inactive_row_fade_percent",stringResource(R.string.ov_fade_strength),min=50,max=100,dependsOn="inactive_row_fade_enabled"))),
    LyricGroup(stringResource(R.string.ov_group_motion),listOf(
        LyricField("motion_mode",stringResource(R.string.ov_motion),"choice",options=listOf(stringResource(R.string.ov_standard),stringResource(R.string.ov_gentle),stringResource(R.string.ov_off))),
        LyricField("lyric_ui_inactive_blur_enabled",stringResource(R.string.ov_inactive_blur),"switch"),
        LyricField("blur_radius_tenths_px",stringResource(R.string.ov_blur_radius),min=0,max=80,step=5,factor=10f,unit="px",dependsOn="lyric_ui_inactive_blur_enabled"),
        LyricField("lyric_ui_scroll_scale_enabled",stringResource(R.string.ov_inactive_scale),"switch"),
        LyricField("inactive_scale_percent",stringResource(R.string.ov_scale_ratio),min=75,max=100,dependsOn="lyric_ui_scroll_scale_enabled"),
        LyricField("char_lift_enabled",stringResource(R.string.ov_char_lift),"switch"),
        LyricField("char_lift_strength_percent",stringResource(R.string.ov_lift_strength),min=0,max=200,dependsOn="char_lift_enabled"))),
    LyricGroup(stringResource(R.string.ov_group_playback),listOf(
        LyricField("lyric_ui_line_timed_progress_enabled",stringResource(R.string.ov_line_progress),"switch"),
        LyricField("secondary_progress_enabled",stringResource(R.string.ov_secondary_progress),"switch"),
        LyricField("max_refresh_rate_hz",stringResource(R.string.ov_refresh_rate),"choice",options=listOf(stringResource(R.string.ov_follow_system),"60 Hz","90 Hz","120 Hz"),optionValues=listOf(0,60,90,120)),
        LyricField("screen_timeout_enabled",stringResource(R.string.ov_keep_screen_on),"switch"),
        LyricField("screen_timeout_seconds",stringResource(R.string.ov_screen_timeout),"number",unit=stringResource(R.string.ov_seconds),dependsOn="screen_timeout_enabled")))
)

@Composable
internal fun translationFields(): List<LyricField> = listOf(
    LyricField("default_translation_enabled",stringResource(R.string.ov_default_translation),"switch"),
    LyricField("translation_display_mode",stringResource(R.string.ov_translation_display),"choice",options=listOf(stringResource(R.string.ov_current_line),stringResource(R.string.ov_all_lines)),optionValues=listOf(0,1)),
    LyricField("translation_font_ratio_percent",stringResource(R.string.ov_translation_font_size),min=55,max=75),
    LyricField("lyric_ui_translation_progress_enabled",stringResource(R.string.ov_translation_progress),"switch"),
    LyricField("translation_marquee_enabled",stringResource(R.string.ov_translation_marquee),"switch"),
    LyricField("active_translation_progress_opacity_percent",stringResource(R.string.ov_active_translation_sung),min=20,max=100),
    LyricField("active_translation_opacity_percent",stringResource(R.string.ov_active_translation_upcoming),min=20,max=100),
    LyricField("inactive_translation_follows_main",stringResource(R.string.ov_translation_follow),"switch"),
    LyricField("inactive_translation_opacity_percent",stringResource(R.string.ov_inactive_translation),min=20,max=100,dependsOn="!inactive_translation_follows_main"),
)
