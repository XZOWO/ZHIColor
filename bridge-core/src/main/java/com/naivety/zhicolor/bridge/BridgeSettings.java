package com.naivety.zhicolor.bridge;

import android.content.*;
import android.os.*;
import java.util.*;
import org.json.*;
import com.naivety.zhicolor.bridge.diagnostics.BridgeDebugConfig;

/** App-facing adapter. Rendering schema and codec remain the upstream implementation. */
public final class BridgeSettings {
    public static final String CONTROL_PREFS="zhicolor_bridge_controls";
    public static final String CONFIG_AUTHORITY="com.naivety.zhicolor.bridgeconfig";
    public static final String CHANGED="com.naivety.zhicolor.action.BRIDGE_SETTINGS_CHANGED";
    public static final String EXTRA_SNAPSHOT="bridge_snapshot";
    private static final String CACHED="runtime_snapshot_cached";
    static final String TRANSLATION_RESET_REVISION="translation_reset_revision";
    static final String TRANSLATION_PLAYER_RESET_REVISION="translation_reset_revision.";
    public static final String PERMISSION="com.naivety.zhicolor.permission.CHANGE_LYRIC_UI_SETTINGS";
    public static final String MASTER="enabled", STYLE="style", APPS="enabled_apps";
    static final boolean DEFAULT_ENABLED = true;
    static final int DEFAULT_STYLE = 0;
    private static final Set<String> DEFAULT_APPS = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList("com.tencent.qqmusic", "com.apple.android.music",
                    "com.netease.cloudmusic")));
    private static final Map<String, String> DEFAULT_PROVIDERS;
    static {
        Map<String, String> providers = new LinkedHashMap<>();
        providers.put("com.apple.android.music", "com.naivety.lyricon.amprovider");
        providers.put("com.tencent.qqmusic", "io.github.proify.lyricon.qmprovider");
        providers.put("com.netease.cloudmusic", "io.github.proify.lyricon.cmprovider");
        DEFAULT_PROVIDERS = Collections.unmodifiableMap(providers);
    }
    public static SharedPreferences controls(Context c) {
        SharedPreferences preferences = c.getSharedPreferences(CONTROL_PREFS,0);
        initializeControls(preferences);
        return preferences;
    }
    static void initializeControls(SharedPreferences preferences) {
        SharedPreferences.Editor editor = null;
        if (!preferences.contains(MASTER)) editor = preferences.edit().putBoolean(MASTER, DEFAULT_ENABLED);
        if (!preferences.contains(STYLE)) {
            if (editor == null) editor = preferences.edit();
            editor.putInt(STYLE, DEFAULT_STYLE);
        }
        if (!preferences.contains(APPS)) {
            if (editor == null) editor = preferences.edit();
            editor.putStringSet(APPS, new LinkedHashSet<>(DEFAULT_APPS));
        }
        for (Map.Entry<String, String> entry : DEFAULT_PROVIDERS.entrySet()) {
            String key = "provider." + entry.getKey();
            if (!preferences.contains(key)) {
                if (editor == null) editor = preferences.edit();
                editor.putString(key, entry.getValue());
            }
        }
        if (editor != null) {
            editor.apply();
            MusicAppCatalog.invalidate();
        }
    }
    static SharedPreferences prefs(Context c) { return c.getSharedPreferences(LyricUiSettings.PREFERENCES_NAME,0); }
    public static void initialize(Context c) {
        LyricUiConfigRepository.load(prefs(c));
        controls(c);
    }
    public static Map<String,Object> values(Context c) { initialize(c); return new LinkedHashMap<>(LyricUiConfigCodec.encode(LyricUiConfigRepository.load(prefs(c)))); }
    public static void save(Context c, Map<String,?> values) {
        initialize(c);
        boolean clearTranslationOverrides=save(prefs(c),values);
        sync(c,clearTranslationOverrides,new String[0]);
    }
    static boolean save(SharedPreferences preferences,Map<String,?> values) {
        Map<String,Object> next=new LinkedHashMap<>(values); next.put(LyricUiConfigCodec.SCHEMA,LyricUiConfig.SCHEMA_VERSION);
        Object intensity=next.get(LyricUiConfigCodec.GLOW_INTENSITY);if(intensity instanceof Number)next.put(LyricUiConfigCodec.GLOW_ENABLED,((Number)intensity).intValue()>0);
        LyricUiConfig previous=LyricUiConfigRepository.load(preferences);
        LyricUiConfig config=LyricUiConfigCodec.decode(next,previous,false);
        if(config==null) throw new IllegalArgumentException("设置无效");
        LyricUiConfigRepository.save(preferences,config);
        return config.defaultTranslationEnabled!=previous.defaultTranslationEnabled;
    }
    public static boolean enabled(Context c) { return controls(c).getBoolean(MASTER,DEFAULT_ENABLED); }
    public static int style(Context c) { return controls(c).getInt(STYLE,DEFAULT_STYLE)==1?1:0; }
    public static void setEnabled(Context c, boolean v) { controls(c).edit().putBoolean(MASTER,v).apply(); sync(c); }
    public static void setStyle(Context c,int v) { controls(c).edit().putInt(STYLE,v==1?1:0).apply(); sync(c); }
    public static boolean playerEnabled(Context c,String pkg) { return controls(c).getStringSet(APPS,DEFAULT_APPS).contains(pkg); }
    public static void recordProvider(Context c,String player,String provider) {
        if(player==null||provider==null||player.equals("com.android.systemui")||player.equals(BuildConfig.APPLICATION_ID)||!MusicAppCatalog.isLyriconProvider(c,provider))return;
        try{c.getPackageManager().getApplicationInfo(player,0);}catch(Exception e){return;}
        String key="provider."+player;if(provider.equals(controls(c).getString(key,"")))return;
        controls(c).edit().putString(key,provider).apply();MusicAppCatalog.invalidate();sync(c);
    }
    public static void setPlayerEnabled(Context c,String pkg,boolean enabled) {
        Set<String> items=new HashSet<>(controls(c).getStringSet(APPS,DEFAULT_APPS));
        if(enabled) items.add(pkg); else items.remove(pkg);
        controls(c).edit().putStringSet(APPS,items).apply();sync(c);
    }
    static void restoreDefaults(Context c) { BridgeConfigBackupRepository.clearAll(c);initialize(c); }
    public static void reset(Context c) {
        long toolsRevision=c.getSharedPreferences(ToolsSettings.PREFERENCES,0).getLong(ToolsSettings.REVISION,0L);
        long translationResetRevision=controls(c).getLong(TRANSLATION_RESET_REVISION,0L);
        restoreDefaults(c);sync(c,true,new String[0],translationResetRevision);resyncTools(c,toolsRevision);
    }
    public static String exportSettings(Context c) { initialize(c); return BridgeConfigBackupRepository.exportAll(c); }
    public static void importSettings(Context c,String value) {
        BridgeConfigBackupCodec.Backup backup=BridgeConfigBackupCodec.decode(value);
        Map<String,Map<String,?>> namespaces=new LinkedHashMap<>();
        for(String name:backup.namespaceNames()) {
            String target=name.equals("lockscreen_lyrics")?LyricUiSettings.PREFERENCES_NAME:name.equals("lockscreen_lyrics_debug")?BridgeDebugConfig.PREFS_NAME:name;
            if(namespaces.containsKey(target))throw new IllegalArgumentException("重复设置项");
            namespaces.put(target,backup.namespace(name));
        }
        long toolsRevision=c.getSharedPreferences(ToolsSettings.PREFERENCES,0).getLong(ToolsSettings.REVISION,0L);
        long translationResetRevision=controls(c).getLong(TRANSLATION_RESET_REVISION,0L);
        BridgeConfigBackupRepository.restoreAll(c,BridgeConfigBackupCodec.encode(namespaces));
        initialize(c);sync(c,true,new String[0],translationResetRevision);
        if(namespaces.containsKey(ToolsSettings.PREFERENCES))resyncTools(c,toolsRevision);
    }
    public static int parseTimeout(String value){return LyricUiSettings.parseScreenTimeoutSeconds(value);}
    public static String cleanup(Context c) { return LyricContentCleanupRepository.load(prefs(c)).encode(); }
    public static void saveCleanup(Context c,String json) {
        LyricContentCleanupConfig config=LyricContentCleanupConfig.decode(json);
        if(config==null) throw new IllegalArgumentException("设置无效");
        LyricContentCleanupRepository.save(prefs(c),config);sync(c);
    }
    public static String validateCleanupRule(String type,String text) {
        LyricContentCleanupConfig.LearnedType kind=LyricContentCleanupConfig.LearnedType.fromId(type);
        if(kind==null)throw new IllegalArgumentException("清理格式类型无效");
        String normalized=LyricOpeningCleanup.normalizeForComparison(text);
        int limit=kind==LyricContentCleanupConfig.LearnedType.PREFIX
                ?LyricContentCleanupConfig.MAX_PREFIX_CHARS:LyricContentCleanupConfig.MAX_EXACT_CHARS;
        if(normalized.isEmpty()||(kind==LyricContentCleanupConfig.LearnedType.PREFIX&&normalized.length()<2))
            throw new IllegalArgumentException("清理格式太短");
        if(normalized.length()>limit)throw new IllegalArgumentException("清理格式过长");
        return new LyricContentCleanupConfig.LearnedRule(kind,normalized).value;
    }
    public static boolean defaultTranslationButton(Context c) {return LyricUiSettings.defaultTranslationButtonEnabled(prefs(c));}
    public static void setDefaultTranslationButton(Context c,boolean value) {prefs(c).edit().putBoolean(LyricUiSettings.KEY_DEFAULT_TRANSLATION_BUTTON,value).apply();sync(c);}
    public static boolean translation(Context c,String pkg) { return prefs(c).getBoolean(LyricUiSettings.translationDefaultKeyForPackage(pkg),LyricUiConfigRepository.load(prefs(c)).defaultTranslationEnabled); }
    public static boolean translationButton(Context c,String pkg) { return prefs(c).getBoolean(LyricUiSettings.translationButtonKeyForPackage(pkg),LyricUiSettings.defaultTranslationButtonEnabled(prefs(c))); }
    public static boolean hasTranslationOverride(Context c,String pkg) {return prefs(c).contains(LyricUiSettings.translationDefaultKeyForPackage(pkg));}
    public static boolean hasTranslationButtonOverride(Context c,String pkg) {return prefs(c).contains(LyricUiSettings.translationButtonKeyForPackage(pkg));}
    public static void setTranslationEnabled(Context c,String pkg,boolean value) {
        setTranslationEnabled(prefs(c),pkg,value);sync(c,false,new String[]{pkg});
    }
    static void setTranslationEnabled(SharedPreferences p,String pkg,boolean value) {
        requirePlayerPackage(pkg);p.edit().putBoolean(LyricUiSettings.translationDefaultKeyForPackage(pkg),value).apply();
    }
    public static void setTranslationButton(Context c,String pkg,boolean value) {
        setTranslationButton(prefs(c),pkg,value);sync(c);
    }
    static void setTranslationButton(SharedPreferences p,String pkg,boolean value) {
        requirePlayerPackage(pkg);p.edit().putBoolean(LyricUiSettings.translationButtonKeyForPackage(pkg),value).apply();
    }
    public static void followTranslationDefault(Context c,String pkg) {
        followTranslationDefault(prefs(c),pkg);sync(c,false,new String[]{pkg});
    }
    static void followTranslationDefault(SharedPreferences p,String pkg) {
        requirePlayerPackage(pkg);p.edit().remove(LyricUiSettings.translationDefaultKeyForPackage(pkg)).apply();
    }
    public static void followTranslationButtonDefault(Context c,String pkg) {
        followTranslationButtonDefault(prefs(c),pkg);sync(c);
    }
    static void followTranslationButtonDefault(SharedPreferences p,String pkg) {
        requirePlayerPackage(pkg);p.edit().remove(LyricUiSettings.translationButtonKeyForPackage(pkg)).apply();
    }
    public static void resetPlayerTranslation(Context c,String pkg) {
        requirePlayerPackage(pkg);prefs(c).edit().remove(LyricUiSettings.translationDefaultKeyForPackage(pkg))
                .remove(LyricUiSettings.translationButtonKeyForPackage(pkg)).apply();sync(c,false,new String[]{pkg});
    }
    private static void requirePlayerPackage(String pkg) {
        if(pkg==null||!pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
                ||pkg.equals("com.android.systemui")||pkg.equals(BuildConfig.APPLICATION_ID))
            throw new IllegalArgumentException("播放器无效");
    }
    /** Kept for callers that intentionally set both fields; individual controls use the split APIs. */
    public static void setTranslation(Context c,String pkg,boolean value,boolean button) {
        setTranslationEnabled(prefs(c),pkg,value);setTranslationButton(prefs(c),pkg,button);sync(c,false,new String[]{pkg});
    }
    public static Map<String,Boolean> debug(Context c) {
        SharedPreferences p=c.getSharedPreferences(BridgeDebugConfig.PREFS_NAME,0);
        Map<String,Boolean> result=new LinkedHashMap<>();
        for(String key:debugKeys()) result.put(key,p.getBoolean(key,!key.equals(BridgeDebugConfig.KEY_MASTER)));
        return result;
    }
    public static String[] debugKeys() { return new String[]{BridgeDebugConfig.KEY_MASTER,BridgeDebugConfig.KEY_BOOTSTRAP,BridgeDebugConfig.KEY_MEDIA,BridgeDebugConfig.KEY_LYRIC,BridgeDebugConfig.KEY_RENDERER,BridgeDebugConfig.KEY_AOD,BridgeDebugConfig.KEY_PLAYER_SPECIAL,BridgeDebugConfig.KEY_PERFORMANCE}; }
    public static void setDebug(Context c,String key,boolean v) { if(!Arrays.asList(debugKeys()).contains(key)) return; c.getSharedPreferences(BridgeDebugConfig.PREFS_NAME,0).edit().putBoolean(key,v).apply();sync(c); }
    public static String[] colorPalette(String key) {return (LyricUiConfigCodec.GLOW_COLOR.equals(key)?SettingsColorPalette.GLOW:SettingsColorPalette.PRIMARY).clone();}
    public interface SnapshotCallback { void received(Bundle value); }
    public static void requestSnapshot(Context c,SnapshotCallback callback) {
        ResultReceiver result=new ResultReceiver(new Handler(Looper.getMainLooper())) { protected void onReceiveResult(int code,Bundle data) {callback.received(data==null?new Bundle():data);} };
        c.sendBroadcast(new Intent(LyricUiSettings.ACTION_REQUEST_MEDIA_SNAPSHOT).setPackage("com.android.systemui").putExtra(LyricUiSettings.EXTRA_RESULT_RECEIVER,remoteReceiver(result)));
    }
    public static final class CleanupLine {
        public final String text,fingerprint,reason; public final long time; public final boolean hidden;
        CleanupLine(LyricOpeningCleanup.Decision d){text=d.line.text;fingerprint=d.line.fingerprint;time=d.line.timeMillis;hidden=d.reason!=LyricOpeningCleanup.Reason.VISIBLE;reason=LyricOpeningCleanup.reasonLabel(d.reason);}
    }
    public static List<CleanupLine> cleanupLines(Context c,String raw,String track) {
        List<CleanupLine> result=new ArrayList<>();
        for(LyricOpeningCleanup.Decision d:LyricOpeningCleanup.analyze(LyricOpeningCleanup.parseLines(raw),track,LyricContentCleanupRepository.load(prefs(c)))) result.add(new CleanupLine(d));
        return result;
    }
    public static void setFirstLine(Context c,String track,String fingerprint) {
        LyricContentCleanupConfig.Builder builder=LyricContentCleanupRepository.load(prefs(c)).buildUpon();
        if(fingerprint==null) builder.removeTrackOverride(track); else builder.firstFormalLine(track,fingerprint);
        LyricContentCleanupRepository.save(prefs(c),builder.build());sync(c);
    }
    public static void addLearned(Context c,String text) {
        LyricContentCleanupConfig.LearnedRule rule=LyricOpeningCleanup.proposeLearnedRule(text);
        if(rule==null) throw new IllegalArgumentException("未识别到清理格式");
        LyricContentCleanupRepository.save(prefs(c),LyricContentCleanupRepository.load(prefs(c)).buildUpon().addLearnedRule(rule).build());sync(c);
    }
    public static void requestStatus(Context c,SnapshotCallback callback) {
        ResultReceiver result=new ResultReceiver(new Handler(Looper.getMainLooper())) {protected void onReceiveResult(int code,Bundle data){callback.received(data==null?new Bundle():data);}};
        c.sendBroadcast(new Intent(LyricUiSettings.ACTION_REQUEST_MODULE_STATUS).setPackage("com.android.systemui").putExtra(LyricUiSettings.EXTRA_RESULT_RECEIVER,remoteReceiver(result)));
    }
    private static ResultReceiver remoteReceiver(ResultReceiver receiver) {
        Parcel parcel=Parcel.obtain();
        try {receiver.writeToParcel(parcel,0);parcel.setDataPosition(0);return ResultReceiver.CREATOR.createFromParcel(parcel);}
        finally {parcel.recycle();}
    }
    public static Bundle snapshot(Context c) {
        ArrayList<String> enabled=new ArrayList<>();
        for(MusicAppCatalog.Entry app:MusicAppCatalog.installed(c)) if(app.providerInstalled && playerEnabled(c,app.packageName)) enabled.add(app.packageName);
        return snapshot(c,enabled);
    }
    static Bundle cachedSnapshot(Context c) {
        if(!controls(c).getBoolean(CACHED,false))return null;
        return snapshot(c,new ArrayList<>(controls(c).getStringSet(APPS,DEFAULT_APPS)));
    }
    private static Bundle snapshot(Context c,ArrayList<String> enabled) {
        initialize(c); Bundle bundle=new Bundle();
        bundle.putBoolean(MASTER,enabled(c));bundle.putInt(STYLE,style(c));
        bundle.putStringArrayList(APPS,enabled);
        Bundle main=preferencesBundle(prefs(c).getAll());
        bundle.putBundle("main",main);
        bundle.putBundle("controls",preferencesBundle(controls(c).getAll()));
        bundle.putBundle("debug",BridgeDebugConfig.load(c.getSharedPreferences(BridgeDebugConfig.PREFS_NAME,0)).putExtras(new Intent()).getExtras());
        return bundle;
    }
    private static Bundle preferencesBundle(Map<String,?> values) {
        Bundle bundle=new Bundle();
        for(Map.Entry<String,?> e:values.entrySet()) {
            Object v=e.getValue();
            if(v instanceof String)bundle.putString(e.getKey(),(String)v);
            else if(v instanceof Integer)bundle.putInt(e.getKey(),(Integer)v);
            else if(v instanceof Boolean)bundle.putBoolean(e.getKey(),(Boolean)v);
            else if(v instanceof Long)bundle.putLong(e.getKey(),(Long)v);
            else if(v instanceof Float)bundle.putFloat(e.getKey(),(Float)v);
            else if(v instanceof Set)bundle.putSerializable(e.getKey(),new LinkedHashSet<>((Set<?>)v));
        }
        return bundle;
    }
    private static Map<String,Object> bundleValues(Bundle bundle) {
        Map<String,Object> values=new LinkedHashMap<>();
        for(String key:bundle.keySet())values.put(key,bundle.get(key));
        return values;
    }
    static void applySnapshot(Context context,Bundle bundle) {
        Bundle main=bundle.getBundle("main");
        Bundle controlValues=bundle.getBundle("controls");
        String[] cleared=bundle.getStringArray(LyricUiSettings.EXTRA_CLEAR_TRANSLATION_PACKAGES);
        Set<String> clearPackages=cleared==null?new LinkedHashSet<>():new LinkedHashSet<>(Arrays.asList(cleared));
        SharedPreferences targetControls=controls(context);
        boolean clearAll=bundle.getBoolean(LyricUiSettings.EXTRA_CLEAR_TRANSLATION_OVERRIDES,false);
        if(controlValues!=null) {
            Map<String,Object> incomingControls=bundleValues(controlValues);
            if(hasNewTranslationReset(incomingControls,targetControls.getAll()))clearAll=true;
            clearPackages.addAll(newPlayerTranslationResets(incomingControls,targetControls.getAll()));
        }
        if(main!=null)applyMainSnapshot(prefs(context),bundleValues(main),
                clearAll,clearPackages);
        Bundle debug=bundle.getBundle("debug");
        if(debug!=null)BridgeDebugConfig.save(context.getSharedPreferences(BridgeDebugConfig.PREFS_NAME,0),BridgeDebugConfig.fromIntent(new Intent().putExtras(debug),BridgeDebugConfig.disabled()));
        if(controlValues!=null)writePreferences(controls(context),bundleValues(controlValues));
        ArrayList<String> apps=bundle.getStringArrayList(APPS);
        controls(context).edit().putBoolean(MASTER,bundle.getBoolean(MASTER,false)).putInt(STYLE,bundle.getInt(STYLE,0))
            .putStringSet(APPS,apps==null?Collections.emptySet():new LinkedHashSet<>(apps)).putBoolean(CACHED,true).apply();
    }
    static void applyMainSnapshot(SharedPreferences target,Map<String,?> incoming,
            boolean clearTranslationOverrides,Set<String> clearPackages) {
        Map<String,Object> exact=new LinkedHashMap<>();
        if(!clearTranslationOverrides)for(Map.Entry<String,?> entry:target.getAll().entrySet()) {
            String key=entry.getKey();
            if(!LyricUiSettings.isTranslationOverrideKey(key))continue;
            boolean clear=false;
            for(String pkg:clearPackages)if(key.equals(LyricUiSettings.TRANSLATION_PREFERENCE_KEY+"."+pkg))clear=true;
            if(!clear)exact.put(key,entry.getValue());
        }
        for(Map.Entry<String,?> entry:incoming.entrySet()) {
            // Card memory belongs to SystemUI; app snapshots replace defaults, not live choices.
            if(!LyricUiSettings.isTranslationOverrideKey(entry.getKey()))exact.put(entry.getKey(),entry.getValue());
        }
        writePreferences(target,exact);
    }
    static boolean hasNewTranslationReset(Map<String,?> incoming,Map<String,?> previous) {
        return revision(incoming.get(TRANSLATION_RESET_REVISION))>revision(previous.get(TRANSLATION_RESET_REVISION));
    }
    static Set<String> newPlayerTranslationResets(Map<String,?> incoming,Map<String,?> previous) {
        Set<String> packages=new LinkedHashSet<>();
        for(String key:incoming.keySet())if(key.startsWith(TRANSLATION_PLAYER_RESET_REVISION)
                &&revision(incoming.get(key))>revision(previous.get(key)))
            packages.add(key.substring(TRANSLATION_PLAYER_RESET_REVISION.length()));
        return packages;
    }
    private static long revision(Object value) {return value instanceof Number?((Number)value).longValue():0L;}
    @SuppressWarnings("unchecked")
    private static void writePreferences(SharedPreferences preferences,Map<String,?> values) {
        SharedPreferences.Editor e=preferences.edit().clear();
        for(Map.Entry<String,?> entry:values.entrySet()) {
            String key=entry.getKey();Object v=entry.getValue();
            if(v instanceof String)e.putString(key,(String)v);else if(v instanceof Integer)e.putInt(key,(Integer)v);
            else if(v instanceof Boolean)e.putBoolean(key,(Boolean)v);else if(v instanceof Long)e.putLong(key,(Long)v);
            else if(v instanceof Float)e.putFloat(key,(Float)v);else if(v instanceof Set)e.putStringSet(key,new LinkedHashSet<>((Set<String>)v));
            else throw new IllegalArgumentException("设置类型无效");
        }
        e.apply();
    }
    public static void sync(Context c) {
        sync(c,false,new String[0]);
    }
    private static void sync(Context c,boolean clearTranslationOverrides,String[] clearPackages) {
        sync(c,clearTranslationOverrides,clearPackages,0L);
    }
    private static void sync(Context c,boolean clearTranslationOverrides,String[] clearPackages,long previousTranslationResetRevision) {
        initialize(c);
        if(clearTranslationOverrides||clearPackages.length>0) {
            SharedPreferences control=controls(c);SharedPreferences.Editor editor=control.edit();
            if(clearTranslationOverrides)editor.putLong(TRANSLATION_RESET_REVISION,
                    Math.max(System.currentTimeMillis(),Math.max(previousTranslationResetRevision,
                            control.getLong(TRANSLATION_RESET_REVISION,0L))+1L));
            for(String pkg:clearPackages) {
                String key=TRANSLATION_PLAYER_RESET_REVISION+pkg;
                editor.putLong(key,Math.max(System.currentTimeMillis(),control.getLong(key,0L)+1L));
            }
            editor.apply();
        }
        Bundle snapshot=snapshot(c);
        if(clearTranslationOverrides)snapshot.putBoolean(LyricUiSettings.EXTRA_CLEAR_TRANSLATION_OVERRIDES,true);
        if(clearPackages.length>0)snapshot.putStringArray(LyricUiSettings.EXTRA_CLEAR_TRANSLATION_PACKAGES,clearPackages);
        c.sendBroadcast(new Intent(CHANGED).setPackage("com.android.systemui").putExtra(EXTRA_SNAPSHOT,snapshot));
        c.sendBroadcast(new Intent(CHANGED).setPackage("android").putExtra(EXTRA_SNAPSHOT,snapshot));
        try { BridgeConfigRuntimeSync.applyAll(c,clearTranslationOverrides,clearPackages); } catch(RuntimeException e) { android.util.Log.w("ZHIColorSettings","sync pending",e); }
    }
    private static void resyncTools(Context context,long previousRevision) {
        SharedPreferences p=context.getSharedPreferences(ToolsSettings.PREFERENCES,0);
        long revision=Math.max(System.currentTimeMillis(),Math.max(previousRevision,p.getLong(ToolsSettings.REVISION,0L))+1L);
        p.edit().putLong(ToolsSettings.REVISION,revision).apply();
        context.sendBroadcast(new Intent(ToolsSettings.CHANGED).setPackage("com.android.systemui")
                .putExtras(ToolsSettings.snapshot(context)));
    }
}
