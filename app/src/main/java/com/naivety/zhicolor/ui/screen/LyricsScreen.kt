package com.naivety.zhicolor.ui.screen

import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naivety.zhicolor.R
import com.naivety.zhicolor.bridge.BridgeSettings
import com.naivety.zhicolor.ui.components.ActionPreference
import com.naivety.zhicolor.ui.components.DialogBody
import com.naivety.zhicolor.ui.components.DialogAction
import com.naivety.zhicolor.ui.components.DialogActions
import com.naivety.zhicolor.ui.components.AppDialog
import com.naivety.zhicolor.ui.components.motion.PredictiveBackPage
import com.naivety.zhicolor.ui.theme.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import kotlin.math.roundToInt

@Composable
fun LyricsScreen(bottomInnerPadding:Dp=0.dp,onChildPageActiveChanged:(Boolean)->Unit={}) {
    val context=LocalContext.current
    val resources=LocalResources.current
    val revision=rememberSettingsRevision()
    val mode=remember(revision){BridgeSettings.style(context)}
    val groups=lyricGroups()
    val draft=remember {mutableStateMapOf<String,Any>().apply {putAll(BridgeSettings.values(context))}}
    LaunchedEffect(revision){draft.clear();draft.putAll(BridgeSettings.values(context))}
    var page by rememberSaveable {mutableStateOf("root")}
    var reset by remember {mutableStateOf(false)}
    val coroutine=rememberCoroutineScope()
    fun save(){runCatching{BridgeSettings.save(context,draft.toMap())}.onFailure{draft.clear();draft.putAll(BridgeSettings.values(context));Toast.makeText(context,resources.getString(R.string.scr_save_failed),Toast.LENGTH_SHORT).show()}}
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")){uri->
        if(uri!=null)coroutine.launch {val result=withContext(Dispatchers.IO){runCatching{context.contentResolver.openOutputStream(uri)?.use {it.write(BridgeSettings.exportSettings(context).toByteArray())} ?: error("stream")}};Toast.makeText(context,if(result.isSuccess)resources.getString(R.string.scr_exported) else resources.getString(R.string.scr_export_failed),Toast.LENGTH_SHORT).show()}
    }
    val import=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri!=null)coroutine.launch {val result=withContext(Dispatchers.IO){runCatching{val bytes=context.contentResolver.openInputStream(uri)?.use {readBackupBytes(it)}?:error("stream");require(bytes.size<=4*1024*1024);BridgeSettings.importSettings(context,bytes.toString(Charsets.UTF_8))}};Toast.makeText(context,if(result.isSuccess)resources.getString(R.string.scr_imported) else resources.getString(R.string.scr_import_failed),Toast.LENGTH_SHORT).show()}
    }
    val titles=mapOf("root" to stringResource(R.string.scr_lyrics),"translation" to stringResource(R.string.scr_translation),"cleanup" to stringResource(R.string.scr_cleanup),"debug" to stringResource(R.string.scr_logs),"bridge" to stringResource(R.string.scr_lyric_bridge))
    PredictiveBackPage(targetState=page,backTarget="root",onBack={page="root"},label="lyric-settings-page",onChildActiveChanged=onChildPageActiveChanged) {destination->
    key(destination) {
    val scroll=MiuixScrollBehavior();val haze=rememberHeaderBlurState();val hazeStyle=rememberHeaderBlurStyle()
    Scaffold(topBar={
        TopAppBar(title=titles[destination]?:stringResource(R.string.scr_lyrics),scrollBehavior=scroll,color=Color.Transparent,modifier=Modifier.headerBlur(haze,hazeStyle),
            navigationIcon={if(destination!="root")IconButton(onClick={page="root"},minHeight=48.dp,minWidth=48.dp){Icon(Icons.Rounded.ChevronLeft,contentDescription=stringResource(R.string.scr_back),tint=MiuixTheme.colorScheme.onBackground,modifier=Modifier.size(28.dp))}})
    }) {padding->
            LazyColumn(modifier=Modifier.fillMaxSize().overScrollVertical().nestedScroll(scroll.nestedScrollConnection).headerBlurSource(haze),
                contentPadding=PaddingValues(top=padding.calculateTopPadding()+12.dp,bottom=padding.calculateBottomPadding()+bottomInnerPadding+12.dp),overscrollEffect=null) {
                if(destination=="root") {
                    item {Card(Modifier.padding(horizontal=12.dp)){
                        ChoiceRow(stringResource(R.string.scr_style),if(mode==0)stringResource(R.string.scr_standard) else stringResource(R.string.scr_advanced),listOf(stringResource(R.string.scr_standard),stringResource(R.string.scr_advanced)),mode){runCatching{BridgeSettings.setStyle(context,it)}.onFailure{Toast.makeText(context,resources.getString(R.string.scr_save_failed),Toast.LENGTH_SHORT).show()}}
                        if(mode==1)BasicComponent(title=stringResource(R.string.scr_advanced),summary=stringResource(R.string.scr_in_development))
                    }}
                        groups.forEach {group->
                            item(key="header_"+group.title){SmallTitle(group.title)}
                            item(key="group_"+group.title){Card(Modifier.padding(horizontal=12.dp)){
                                group.fields.forEach {field->key(field.key){
                                    LyricFieldEditor(field,draft,onCommit={save()})
                                }}
                            }}
                        }
                        item {SmallTitle(stringResource(R.string.scr_more))}
                        item {Card(Modifier.padding(horizontal=12.dp)){
                            ActionPreference(title=stringResource(R.string.scr_lyric_bridge),onClick={page="bridge"})
                            ActionPreference(title=stringResource(R.string.scr_translation),onClick={page="translation"})
                            ActionPreference(title=stringResource(R.string.scr_cleanup),onClick={page="cleanup"})
                            ActionPreference(title=stringResource(R.string.scr_import),onClick={import.launch(arrayOf("text/plain","application/octet-stream","*/*"))})
                            ActionPreference(title=stringResource(R.string.scr_export),onClick={export.launch("ZHIColor-settings.txt")})
                            ActionPreference(title=stringResource(R.string.scr_logs),onClick={page="debug"})
                            ActionPreference(title=stringResource(R.string.scr_reset),onClick={reset=true})
                        }}
                } else if(destination=="translation") {
                    item {TranslationSettings()}
                } else if(destination=="cleanup") {
                    item {CleanupSettings()}
                } else if(destination=="debug") {
                    item {DebugSettings()}
                } else if(destination=="bridge") {
                    item {LyricBridgeSettings()}
                }
            }
        }
    }
    }
    AppDialog(show=reset,title=stringResource(R.string.scr_reset),onDismissRequest={reset=false}) {
        Text(stringResource(R.string.scr_reset_summary),color=MiuixTheme.colorScheme.onSurfaceVariantSummary)
        DialogActions {
            DialogAction(text=stringResource(R.string.scr_confirm),destructive=true,onClick={runCatching{BridgeSettings.reset(context);reset=false}.onFailure{Toast.makeText(context,resources.getString(R.string.scr_reset_failed),Toast.LENGTH_SHORT).show()}})
        }
    }
}

@Composable
private fun LyricFieldEditor(field:LyricField,draft:MutableMap<String,Any>,onCommit:()->Unit) {
    val dependency=field.dependsOn
    val visible=dependency==null || if(dependency.startsWith("!"))draft[dependency.drop(1)]!=true else draft[dependency]==true
    val maximum=LyricFieldValuePolicy.maximum(field.key,draft,field.max).coerceAtLeast(field.min)
    AnimatedVisibility(visible=visible,enter=expandVertically(tween(220))+fadeIn(tween(160)),exit=shrinkVertically(tween(180))+fadeOut(tween(120))) {
        LyricFieldRow(field.copy(max=maximum),draft[field.key],onChange={LyricFieldValuePolicy.update(draft,field.key,it)},onCommit=onCommit)
    }
}

@Composable
private fun LyricFieldRow(field:LyricField,value:Any?,onChange:(Any)->Unit,onCommit:()->Unit) {
    val number=(value as? Number)?.toInt()?:0
    when(field.kind) {
        "switch" -> SwitchPreference(title=field.title,checked=value==true,onCheckedChange={onChange(it);onCommit()})
        "choice" -> {
            val index=if(field.optionValues.isEmpty())number.coerceIn(0,field.options.lastIndex) else field.optionValues.indexOf(number).coerceAtLeast(0)
            ChoiceRow(field.title,field.options[index],field.options,index){onChange(if(field.optionValues.isEmpty())it else field.optionValues[it]);onCommit()}
        }
        "color" -> ColorRow(field.title,value?.toString()?:"#FFFFFF",BridgeSettings.colorPalette(field.key).toList()){onChange(it);onCommit()}
        "number" -> TextInputRow(field.title,number.toString(),numeric=true,displayValue=if(number==0)stringResource(R.string.scr_unlimited) else stringResource(R.string.scr_seconds,number)){onChange(BridgeSettings.parseTimeout(it));onCommit()}
        else -> Column(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=12.dp)) {
            Row {Text(field.title,fontSize=17.sp,modifier=Modifier.weight(1f));val amount=number/field.factor;Text((if(amount%1f==0f)amount.toInt().toString() else "%.1f".format(amount))+field.unit,color=MiuixTheme.colorScheme.onSurfaceVariantSummary,fontSize=14.sp)}
            Slider(value=number.toFloat().coerceIn(field.min.toFloat(),field.max.toFloat()),onValueChange={onChange(((it/field.step).roundToInt()*field.step).coerceIn(field.min,field.max))},onValueChangeFinished=onCommit,valueRange=field.min.toFloat()..field.max.toFloat(),steps=((field.max-field.min)/field.step-1).coerceAtLeast(0),modifier=Modifier.padding(top=8.dp).fillMaxWidth())
        }
    }
}

@Composable
private fun TranslationSettings() {
    val resources=LocalResources.current
    val context=LocalContext.current;val revision=rememberSettingsRevision();val apps=rememberMusicApps(revision).filter {it.providerInstalled}
    val draft=remember {mutableStateMapOf<String,Any>().apply{putAll(BridgeSettings.values(context))}}
    LaunchedEffect(revision){draft.clear();draft.putAll(BridgeSettings.values(context))}
    val fields=translationFields()
    val options=listOf(stringResource(R.string.scr_follow_global),stringResource(R.string.scr_enabled),stringResource(R.string.ov_off))
    fun save(){runCatching{BridgeSettings.save(context,draft.toMap())}.onFailure{draft.clear();draft.putAll(BridgeSettings.values(context));Toast.makeText(context,resources.getString(R.string.scr_save_failed),Toast.LENGTH_SHORT).show()}}
    fun update(action:()->Unit){runCatching(action).onFailure{Toast.makeText(context,resources.getString(R.string.scr_save_failed),Toast.LENGTH_SHORT).show()}}
    Column {
        Card(Modifier.padding(horizontal=12.dp)) {
            fields.forEach {field->key(field.key){
                LyricFieldEditor(field,draft,onCommit={save()})
                if(field.key=="default_translation_enabled")SwitchPreference(title=stringResource(R.string.scr_default_translation_button),checked=BridgeSettings.defaultTranslationButton(context),onCheckedChange={update{BridgeSettings.setDefaultTranslationButton(context,it)}})
            }}
        }
        apps.forEach {app->key(app.packageName){
            val translationMode=if(!BridgeSettings.hasTranslationOverride(context,app.packageName))0 else if(BridgeSettings.translation(context,app.packageName))1 else 2
            val buttonMode=if(!BridgeSettings.hasTranslationButtonOverride(context,app.packageName))0 else if(BridgeSettings.translationButton(context,app.packageName))1 else 2
            SmallTitle(app.name)
            Card(Modifier.padding(horizontal=12.dp)) {
                ChoiceRow(stringResource(R.string.scr_translation),options[translationMode],options,translationMode){selected->update{if(selected==0)BridgeSettings.followTranslationDefault(context,app.packageName)else BridgeSettings.setTranslationEnabled(context,app.packageName,selected==1)}}
                ChoiceRow(stringResource(R.string.scr_translation_button),options[buttonMode],options,buttonMode){selected->update{if(selected==0)BridgeSettings.followTranslationButtonDefault(context,app.packageName)else BridgeSettings.setTranslationButton(context,app.packageName,selected==1)}}
            }
        }}
    }
}

@Composable
private fun DebugSettings() {
    val resources=LocalResources.current
    val context=LocalContext.current;val revision=rememberSettingsRevision()
    val values=remember(revision){BridgeSettings.debug(context)}
    val labels=listOf(stringResource(R.string.scr_logs),stringResource(R.string.scr_initialization),stringResource(R.string.scr_media),stringResource(R.string.scr_lyrics),stringResource(R.string.scr_renderer),stringResource(R.string.scr_aod),stringResource(R.string.scr_player_support),stringResource(R.string.scr_performance))
    var connected by remember {mutableStateOf(false)}
    var pending by remember {mutableStateOf(false)}
    var generation by remember {mutableIntStateOf(0)}
    var timedOut by remember {mutableStateOf(false)}
    fun requestStatus(){
        if(pending)return
        val current=++generation
        pending=true;connected=false;timedOut=false
        runCatching{BridgeSettings.requestStatus(context){result->
            if(current==generation&&pending){connected=result.getInt("module_version_code",0)>0;pending=false}
        }}.onFailure{if(current==generation){pending=false;connected=false;timedOut=true}}
    }
    DisposableEffect(Unit){onDispose{generation++;pending=false}}
    LaunchedEffect(revision){requestStatus()}
    LaunchedEffect(generation,pending){
        if(pending){val current=generation;delay(5000);if(current==generation&&pending){generation++;pending=false;connected=false;timedOut=true}}
    }
    Column {Card(Modifier.padding(horizontal=12.dp)) {
        ActionPreference(title=stringResource(R.string.scr_status),summary=when{pending->stringResource(R.string.scr_loading);timedOut->stringResource(R.string.scr_request_timeout);connected->stringResource(R.string.scr_connected);else->stringResource(R.string.scr_disconnected)},enabled=!pending,onClick={requestStatus()})
        BridgeSettings.debugKeys().forEachIndexed {index,key -> SwitchPreference(title=labels[index],checked=values[key]==true,onCheckedChange={runCatching{BridgeSettings.setDebug(context,key,it)}.onFailure{Toast.makeText(context,resources.getString(R.string.scr_save_failed),Toast.LENGTH_SHORT).show()}})}
    }}
}

@Composable
private fun CleanupSettings() {
    val resources=LocalResources.current
    val context=LocalContext.current;val revision=rememberSettingsRevision()
    val config=remember(revision){JSONObject(BridgeSettings.cleanup(context))}
    var snapshot by remember {mutableStateOf(Bundle.EMPTY)}
    var request by remember {mutableStateOf(false)}
    var generation by remember {mutableIntStateOf(0)}
    var readMessage by remember {mutableStateOf<String?>(null)}
    var add by remember {mutableStateOf(false)}
    var editingRule by remember {mutableIntStateOf(-1)}
    var newRule by remember {mutableStateOf("")};var ruleType by remember {mutableIntStateOf(0)}
    var selectedLine by remember {mutableStateOf<BridgeSettings.CleanupLine?>(null)}
    var selectedTrack by remember {mutableStateOf("")}
    var showLine by remember {mutableStateOf(false)}
    var clearAll by remember {mutableStateOf(false)}
    val track=snapshot.getString("track_key").orEmpty();val raw=snapshot.getString("raw_lyric").orEmpty()
    val noTrack=stringResource(R.string.scr_no_track)
    val lines=remember(raw,track,revision){BridgeSettings.cleanupLines(context,raw,track)}
    fun feedback(message:Int){Toast.makeText(context,resources.getString(message),Toast.LENGTH_SHORT).show()}
    fun persist(next:JSONObject):Boolean=runCatching {BridgeSettings.saveCleanup(context,next.toString())}.onFailure {feedback(R.string.scr_save_failed)}.isSuccess
    fun readSnapshot(expectedTrack:String?=null,action:((String,Bundle)->Unit)?=null){
        if(request)return
        val current=++generation
        request=true;readMessage=null
        runCatching{BridgeSettings.requestSnapshot(context){result->
            if(current!=generation||!request)return@requestSnapshot
            request=false;snapshot=Bundle(result)
            val verifiedTrack=result.getString("track_key").orEmpty()
            if(expectedTrack!=null&&(expectedTrack.isBlank()||verifiedTrack!=expectedTrack)){
                showLine=false;readMessage=resources.getString(R.string.scr_track_changed)
                feedback(R.string.scr_track_changed)
            }else if(action!=null){
                runCatching{action(verifiedTrack,result)}.onFailure{feedback(R.string.scr_save_failed)}
            }
        }}.onFailure{if(current==generation){request=false;readMessage=resources.getString(R.string.scr_read_failed);feedback(R.string.scr_read_failed)}}
    }
    DisposableEffect(Unit){onDispose{generation++;request=false}}
    LaunchedEffect(generation,request){
        if(request){val current=generation;delay(5000);if(current==generation&&request){generation++;request=false;readMessage=resources.getString(R.string.scr_request_timeout);feedback(R.string.scr_request_timeout)}}
    }
    Column {
        Card(Modifier.padding(horizontal=12.dp)) {
            listOf("copyright" to stringResource(R.string.scr_copyright),"production" to stringResource(R.string.scr_production),"titleArtist" to stringResource(R.string.scr_title_artist)).forEach { (key,label)->
                SwitchPreference(title=label,checked=config.optBoolean(key),onCheckedChange={persist(JSONObject(config.toString()).put(key,it))})
            }
        }
        SmallTitle(stringResource(R.string.scr_current_song))
        Card(Modifier.padding(horizontal=12.dp)) {
            BasicComponent(title=snapshot.getString("title").orEmpty().ifBlank{noTrack},summary=snapshot.getString("artist").orEmpty().ifBlank{null})
            ActionPreference(title=if(request)stringResource(R.string.scr_loading) else stringResource(R.string.scr_read_lyrics),summary=readMessage,enabled=!request,onClick={readSnapshot()})
            if(lines.isEmpty()) BasicComponent(title=stringResource(R.string.scr_no_lyrics))
            else lines.forEach {line->ActionPreference(title=line.text,summary=if(line.hidden)stringResource(R.string.scr_hidden) else null,enabled=!request&&track.isNotBlank(),onClick={selectedLine=line;selectedTrack=track;showLine=true})}
            ActionPreference(title=stringResource(R.string.scr_clear_current),enabled=!request&&track.isNotBlank(),onClick={readSnapshot(track){verified,_->BridgeSettings.setFirstLine(context,verified,null)}})
            ActionPreference(title=stringResource(R.string.scr_clear_all),enabled=!request&&(config.optJSONArray("tracks")?.length()?:0)>0,onClick={clearAll=true})
        }
        SmallTitle(stringResource(R.string.scr_cleanup_rules))
        Card(Modifier.padding(horizontal=12.dp)) {
            val rules=config.optJSONArray("learned")?:JSONArray()
            for(i in 0 until rules.length()) {
                val rule=rules.getJSONObject(i)
                ActionPreference(title=rule.optString("value"),summary=if(rule.optString("type")=="prefix")stringResource(R.string.scr_prefix_match) else stringResource(R.string.scr_exact_match),onClick={editingRule=i;newRule=rule.optString("value");ruleType=if(rule.optString("type")=="prefix")0 else 1;add=true})
            }
            ActionPreference(title=stringResource(R.string.scr_add_rule),onClick={editingRule=-1;newRule="";ruleType=0;add=true})
        }
    }
    AppDialog(show=add,title=if(editingRule<0)stringResource(R.string.scr_add_rule) else stringResource(R.string.scr_edit_rule),onDismissRequest={add=false}) {
        DialogBody {
        ChoiceRow(stringResource(R.string.scr_match_type),if(ruleType==0)stringResource(R.string.scr_prefix_match) else stringResource(R.string.scr_exact_match),listOf(stringResource(R.string.scr_prefix_match),stringResource(R.string.scr_exact_match)),ruleType){ruleType=it}
        TextField(value=newRule,onValueChange={newRule=it},modifier=Modifier.fillMaxWidth())
        }
        DialogActions(
            startActions = {
                if (editingRule >= 0) {
                    DialogAction(text=stringResource(R.string.scr_delete),destructive=true,onClick={val current=config.optJSONArray("learned")?:JSONArray();val remaining=JSONArray();for(i in 0 until current.length())if(i!=editingRule)remaining.put(current.get(i));if(persist(JSONObject(config.toString()).put("learned",remaining)))add=false})
                }
            },
        ) {
            DialogAction(text=stringResource(R.string.scr_confirm),onClick={
            val type=if(ruleType==0)"prefix" else "exact"
            val validated=runCatching{BridgeSettings.validateCleanupRule(type,newRule)}
            if(validated.isFailure){feedback(if(ruleType==0)R.string.scr_invalid_prefix_rule else R.string.scr_invalid_exact_rule)}
            else {val next=JSONObject(config.toString());val array=next.optJSONArray("learned")?:JSONArray();val rule=JSONObject().put("type",type).put("value",validated.getOrThrow());if(editingRule<0)array.put(rule)else array.put(editingRule,rule);if(persist(next.put("learned",array)))add=false}
            })
        }
    }
    selectedLine?.let {line -> AppDialog(show=showLine,title=line.text,onDismissRequest={showLine=false},onDismissFinished={selectedLine=null}) {
        DialogAction(text=stringResource(R.string.scr_first_line),modifier=Modifier.fillMaxWidth(),enabled=!request&&selectedTrack.isNotBlank(),onClick={val expected=selectedTrack;showLine=false;readSnapshot(expected){verified,result->
            val present=BridgeSettings.cleanupLines(context,result.getString("raw_lyric").orEmpty(),verified).any{it.fingerprint==line.fingerprint}
            if(present)BridgeSettings.setFirstLine(context,verified,line.fingerprint)else{readMessage=resources.getString(R.string.scr_lyrics_changed);feedback(R.string.scr_lyrics_changed)}
        }})
        Spacer(Modifier.height(8.dp))
        DialogAction(text=stringResource(R.string.scr_learn_rule),modifier=Modifier.fillMaxWidth(),enabled=!request&&selectedTrack.isNotBlank(),onClick={val expected=selectedTrack;showLine=false;readSnapshot(expected){verified,result->
            val present=BridgeSettings.cleanupLines(context,result.getString("raw_lyric").orEmpty(),verified).any{it.fingerprint==line.fingerprint}
            if(present)runCatching{BridgeSettings.addLearned(context,line.text)}.onFailure{feedback(R.string.scr_rule_not_found)}else{readMessage=resources.getString(R.string.scr_lyrics_changed);feedback(R.string.scr_lyrics_changed)}
        }})
    }}
    AppDialog(show=clearAll,title=stringResource(R.string.scr_clear_all),onDismissRequest={clearAll=false}){
        Text(stringResource(R.string.scr_clear_all_summary),color=MiuixTheme.colorScheme.onSurfaceVariantSummary)
        DialogActions {
            DialogAction(text=stringResource(R.string.scr_confirm),destructive=true,enabled=!request,onClick={if(!request&&persist(JSONObject(BridgeSettings.cleanup(context)).put("tracks",JSONArray())))clearAll=false})
        }
    }
}

private fun readBackupBytes(input:java.io.InputStream):ByteArray { val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192);while(true){val n=input.read(buffer);if(n<0)break;require(out.size()+n<=4*1024*1024);out.write(buffer,0,n)};return out.toByteArray() }
