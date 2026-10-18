package com.naivety.zhicolor.bridge;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.SystemClock;
import java.util.*;

public final class MusicAppCatalog {
    public static final class Entry {
        public final String packageName, name;
        public final boolean providerInstalled;
        public Entry(String pkg, String label, boolean provider) { packageName=pkg; name=label; providerInstalled=provider; }
    }
    private static final Map<String,String> KNOWN=new LinkedHashMap<>();
    private static List<Entry> cached=Collections.emptyList();
    private static long cachedAt=-1;
    static { Map<String,String> known=KNOWN;
        known.put("com.netease.cloudmusic", "io.github.proify.lyricon.cmprovider");
        known.put("com.hihonor.cloudmusic", "io.github.proify.lyricon.cmprovider");
        known.put("com.apple.android.music", "io.github.proify.lyricon.amprovider");
        known.put("org.akanework.gramophone", "io.github.proify.lyricon.gramophoneprovider");
        known.put("com.kugou.android", "io.github.proify.lyricon.kgprovider");
        known.put("com.kugou.android.lite", "io.github.proify.lyricon.kgprovider");
        known.put("cn.kuwo.player", "io.github.proify.lyricon.kwprovider");
        known.put("cn.toside.music.mobile", "io.github.proify.lyricon.lxprovider");
        known.put("com.ikunshare.music.mobile", "io.github.proify.lyricon.lxprovider");
        known.put("com.lxnetease.music.mobile", "io.github.proify.lyricon.lxprovider");
        known.put("fun.upup.musicfree", "io.github.proify.lyricon.musicfreeprovider");
        known.put("com.maxmpz.audioplayer", "io.github.proify.lyricon.paprovider");
        known.put("com.maxmpz.audioplayer.unlock", "io.github.proify.lyricon.paprovider");
        known.put("com.luna.music", "io.github.proify.lyricon.qishuiprovider");
        known.put("com.tencent.qqmusic", "io.github.proify.lyricon.qmprovider");
        known.put("com.tencent.qqmusicpad", "io.github.proify.lyricon.qmhdprovider");
        known.put("com.salt.music", "io.github.proify.lyricon.saltprovider");
        known.put("com.spotify.music", "io.github.proify.lyricon.spotifyprovider");
        known.put("app.symfonik.music.player", "io.github.proify.lyricon.symfoniumprovider");
    }
    public static boolean isLyriconProvider(Context c,String pkg) {
        try {ApplicationInfo info=c.getPackageManager().getApplicationInfo(pkg,PackageManager.GET_META_DATA);return KNOWN.containsValue(pkg)||(info.metaData!=null&&info.metaData.getBoolean("lyricon_module",false));}catch(Exception e){return false;}
    }
    public static synchronized void invalidate() { cachedAt=-1; }
    public static synchronized List<Entry> installed(Context context) {
        long now=SystemClock.elapsedRealtime();
        if(cachedAt>=0 && now-cachedAt<5000) return new ArrayList<>(cached);
        PackageManager pm=context.getPackageManager();
        Map<String,ApplicationInfo> apps=new LinkedHashMap<>();
        for(ApplicationInfo info:pm.getInstalledApplications(PackageManager.GET_META_DATA)) apps.put(info.packageName,info);
        Set<String> music=new LinkedHashSet<>();
        Set<String> providers=new LinkedHashSet<>();
        Set<String> mediaServices=new LinkedHashSet<>();
        for(String action:new String[]{"android.media.browse.MediaBrowserService","androidx.media3.session.MediaLibraryService","androidx.media3.session.MediaSessionService"})
            for(ResolveInfo result:pm.queryIntentServices(new Intent(action),0))if(result.serviceInfo!=null)mediaServices.add(result.serviceInfo.packageName);
        Set<String> audioHandlers=new LinkedHashSet<>();
        for(ResolveInfo result:pm.queryIntentActivities(new Intent(Intent.ACTION_VIEW).setType("audio/*"),0))if(result.activityInfo!=null)audioHandlers.add(result.activityInfo.packageName);
        for(Map.Entry<String,String> e:KNOWN.entrySet()) {
            if(apps.containsKey(e.getKey())) music.add(e.getKey());
            if(apps.containsKey(e.getValue())) providers.add(e.getKey());
        }
        for(ApplicationInfo info:apps.values()) {
            Bundle meta=info.metaData;
            if(meta!=null && meta.getBoolean("lyricon_module",false)) {
                int scope=meta.getInt("xposedscope",0);
                if(scope!=0) try {
                    for(String pkg:pm.getResourcesForApplication(info).getStringArray(scope)) {
                        if(apps.containsKey(pkg) && !pkg.equals("com.android.systemui")) {music.add(pkg);providers.add(pkg);}
                    }
                } catch(Exception ignored) {}
                continue;
            }
            if((info.category==ApplicationInfo.CATEGORY_AUDIO&&(mediaServices.contains(info.packageName)||audioHandlers.contains(info.packageName))) && pm.getLaunchIntentForPackage(info.packageName)!=null) music.add(info.packageName);
        }
        Intent intent=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MUSIC);
        for(ResolveInfo item:pm.queryIntentActivities(intent,0)) if(item.activityInfo!=null) music.add(item.activityInfo.packageName);
        for(Map.Entry<String,?> e:BridgeSettings.controls(context).getAll().entrySet()) {
            if(!e.getKey().startsWith("provider.") || !(e.getValue() instanceof String))continue;
            String player=e.getKey().substring("provider.".length());String provider=(String)e.getValue();
            if(apps.containsKey(player)){music.add(player);if(isLyriconProvider(context,provider))providers.add(player);}
        }
        List<Entry> result=new ArrayList<>();
        for(String pkg:music) {
            ApplicationInfo info=apps.get(pkg);
            if(info==null || pkg.equals(context.getPackageName()) || (info.metaData!=null && info.metaData.getBoolean("lyricon_module",false))) continue;
            result.add(new Entry(pkg,pm.getApplicationLabel(info).toString(),providers.contains(pkg)));
        }
        result.sort(Comparator.comparing(e->e.name)); cached=result; cachedAt=now;
        return new ArrayList<>(result);
    }
}
